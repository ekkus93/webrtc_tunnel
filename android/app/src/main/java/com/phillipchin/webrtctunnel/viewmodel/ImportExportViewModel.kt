package com.phillipchin.webrtctunnel.viewmodel

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.phillipchin.webrtctunnel.data.AppDependencies
import com.phillipchin.webrtctunnel.data.ConfigurationAdmission
import com.phillipchin.webrtctunnel.data.ConfigurationMutationCoordinator
import com.phillipchin.webrtctunnel.data.ConfigurationOperation
import com.phillipchin.webrtctunnel.data.OperationFailure
import com.phillipchin.webrtctunnel.data.SnackbarController
import com.phillipchin.webrtctunnel.data.mutationResult
import com.phillipchin.webrtctunnel.security.readPrivateIdentityFile
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

data class ImportExportState(
    val configImportPath: String = "",
    val privateIdentityImportPath: String = "",
    val publicIdentityLine: String = "",
    val configExportPath: String = "",
    val publicIdentityExportPath: String = "",
    val privateIdentityExportPath: String = "",
    val diagnosticsExportPath: String = "",
    val resultMessage: String? = null,
    val isBusy: Boolean = false,
    // P1-008: the last failed operation, kept in state so an import/export failure survives
    // without a snackbar collector. Cleared on the next successful operation.
    val lastOperationFailure: OperationFailure? = null,
)

class ImportExportViewModel(private val deps: AppDependencies) : ViewModel() {
    private val _state = MutableStateFlow(ImportExportState())
    val state: StateFlow<ImportExportState> = _state.asStateFlow()
    private val importService = ImportExportService(deps)

    // Shared IO-op runner bound to this ViewModel's scope/state/dispatcher.
    private val io =
        ImportExportOps(
            viewModelScope,
            _state,
            deps.dispatchers.io,
            deps.snackbar,
            deps.configurationMutationCoordinator,
        )

    fun updateState(transform: (ImportExportState) -> ImportExportState) {
        _state.value = transform(_state.value).copy(resultMessage = null)
    }

    fun importConfig() =
        io.runImport("Config imported", "Config import failed", onSuccess = {
            _state.value = _state.value.copy(configImportPath = "")
        }) {
            val source = java.io.File(_state.value.configImportPath.trim())
            require(source.exists()) { "Config file not found" }
            importService.importContent(ImportKind.Config, source.readText())
        }

    fun importPrivateIdentity() =
        io.runImport("Private identity imported", "Private identity import failed", onSuccess = {
            cachedPublicIdentity = null
            _state.value = _state.value.copy(privateIdentityImportPath = "")
        }) {
            val privateIdentity =
                readPrivateIdentityFile(_state.value.privateIdentityImportPath.trim())
                    .getOrThrow()
            importService.importContent(ImportKind.PrivateIdentity, privateIdentity)
        }

    fun importPublicIdentity() =
        io.runImport("Public identity imported", "Public identity import failed", onSuccess = {
            _state.value = _state.value.copy(publicIdentityLine = "")
        }) {
            importService.importContent(ImportKind.PublicIdentity, _state.value.publicIdentityLine)
        }

    fun importFromUri(
        uri: Uri,
        kind: ImportKind,
    ) = io.runImport("${kind.label} imported", "${kind.label} import failed", onSuccess = {
        if (kind == ImportKind.PrivateIdentity) cachedPublicIdentity = null
    }) {
        val content =
            deps.context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                ?: error("Unable to read ${kind.label.lowercase()} from selected URI")
        importService.importContent(kind, content)
    }

    fun exportConfig(confirmSensitive: Boolean) =
        io.run("Raw config exported", "Config export failed") {
            val output = java.io.File(_state.value.configExportPath.trim())
            output.parentFile?.mkdirs()
            output.writeText(importService.configForExport(confirmSensitive))
        }

    fun exportConfigToUri(
        uri: Uri,
        confirmSensitive: Boolean,
    ) = io.run("Raw config exported", "Config export failed") {
        val payload = importService.configForExport(confirmSensitive)
        deps.context.contentResolver.openOutputStream(uri, "wt")?.use { stream ->
            stream.write(payload.toByteArray())
        } ?: error("Unable to open destination URI")
    }

    fun exportPublicIdentityToUri(uri: Uri) =
        io.run("Public identity exported", "Public identity export failed") {
            val payload = publicIdentityForShare()
            deps.context.contentResolver.openOutputStream(uri, "wt")?.use { stream ->
                stream.write(payload.toByteArray())
            } ?: error("Unable to open destination URI")
        }

    fun exportPrivateIdentityToUri(
        uri: Uri,
        confirmRisk: Boolean,
    ) = io.run("Private identity exported", "Private identity export failed") {
        require(confirmRisk) { "Private export requires explicit confirmation" }
        deps.identityRepository.usePrivateIdentityPlaintext { payload ->
            deps.context.contentResolver.openOutputStream(uri, "wb")?.use { stream ->
                stream.write(payload)
            } ?: error("Unable to open destination URI")
        }
    }

    // The public identity is public and stable within a session, so cache it to avoid a disk
    // read on every share/copy. Invalidated when a private identity is imported (which can
    // change the derived public identity).
    @Volatile private var cachedPublicIdentity: String? = null

    suspend fun publicIdentityForShare(): String {
        cachedPublicIdentity?.let { return it }
        return withContext(deps.dispatchers.io) {
            val value = deps.identityRepository.readPublicIdentity()
            require(value.isNotBlank()) { "No public identity available" }
            value
        }.also { cachedPublicIdentity = it }
    }
}

/**
 * Runs disk/ContentResolver/native import-export operations on [io] with a busy guard, so
 * the UI thread never blocks and duplicate taps are ignored while one is in flight.
 */
private class ImportExportOps(
    private val scope: CoroutineScope,
    private val state: MutableStateFlow<ImportExportState>,
    private val io: CoroutineDispatcher,
    private val snackbar: SnackbarController,
    private val coordinator: ConfigurationMutationCoordinator,
) {
    // Retained only for the LOCAL, non-authoritative export actions (FIX7 P0-001-C): they read
    // current state to a file/URI and never mutate config/identity/forwards, so they are not
    // part of the FIX7-INV-009 cross-feature admission guard. Real imports use [runImport].
    private val exportMutex = Mutex()

    fun run(
        successMessage: String,
        failureFallback: String,
        onSuccess: () -> Unit = {},
        block: suspend () -> Unit,
    ) {
        scope.launch {
            if (!exportMutex.tryLock()) return@launch
            state.value = state.value.copy(isBusy = true, resultMessage = null)
            try {
                // FIX6 P0-005: mutationResult (not runCatching) so a cancelled operation — e.g.
                // the ViewModel being cleared mid-import — propagates instead of falling through
                // to a stale failure snackbar as though it were an ordinary error.
                val result = withContext(io) { mutationResult { block() } }
                if (result.isSuccess) onSuccess()
                val message = result.fold({ successMessage }, { it.message ?: failureFallback })
                // P1-008: on failure keep a durable copy in state (mirroring the snackbar/result
                // message) so it survives without a collector; on success clear it.
                val failure = result.exceptionOrNull()?.let { OperationFailure("import_export_failed", message) }
                state.value =
                    state.value.copy(isBusy = false, resultMessage = message, lastOperationFailure = failure)
                snackbar.show(message)
            } finally {
                exportMutex.unlock()
            }
        }
    }

    /**
     * FIX7 P0-001-C: real config/identity mutations go through the global cross-feature
     * admission coordinator instead of a local mutex. A rejected overlap is a durable, specific
     * `configuration_operation_busy` failure — never a silently dropped `return@launch` (HIGH-1).
     */
    fun runImport(
        successMessage: String,
        failureFallback: String,
        onSuccess: () -> Unit = {},
        block: suspend () -> Unit,
    ) {
        scope.launch {
            when (
                val admission =
                    coordinator.tryRun(ConfigurationOperation.ConfigImport) {
                        state.value = state.value.copy(isBusy = true, resultMessage = null)
                        try {
                            withContext(io) { mutationResult { block() } }
                        } finally {
                            state.value = state.value.copy(isBusy = false)
                        }
                    }
            ) {
                is ConfigurationAdmission.Busy -> {
                    val message = "Another configuration operation is already in progress: ${admission.active}"
                    val failure = OperationFailure("configuration_operation_busy", message)
                    state.value = state.value.copy(resultMessage = message, lastOperationFailure = failure)
                    snackbar.show(message)
                }
                is ConfigurationAdmission.Completed -> {
                    val result = admission.value
                    if (result.isSuccess) onSuccess()
                    val message = result.fold({ successMessage }, { it.message ?: failureFallback })
                    val failure = result.exceptionOrNull()?.let { OperationFailure("import_export_failed", message) }
                    state.value = state.value.copy(resultMessage = message, lastOperationFailure = failure)
                    snackbar.show(message)
                }
            }
        }
    }
}
