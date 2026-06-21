package com.phillipchin.webrtctunnel.viewmodel

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.phillipchin.webrtctunnel.data.AppDependencies
import com.phillipchin.webrtctunnel.data.SnackbarController
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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
)

class ImportExportViewModel(private val deps: AppDependencies) : ViewModel() {
    private val _state = MutableStateFlow(ImportExportState())
    val state: StateFlow<ImportExportState> = _state.asStateFlow()
    private val importService = ImportExportService(deps)

    // Shared IO-op runner bound to this ViewModel's scope/state/dispatcher.
    private val io = ImportExportOps(viewModelScope, _state, deps.dispatchers.io, deps.snackbar)

    fun updateState(transform: (ImportExportState) -> ImportExportState) {
        _state.value = transform(_state.value).copy(resultMessage = null)
    }

    fun importConfig() =
        io.run("Config imported", "Config import failed", onSuccess = {
            _state.value = _state.value.copy(configImportPath = "")
        }) {
            val source = java.io.File(_state.value.configImportPath.trim())
            require(source.exists()) { "Config file not found" }
            importService.importContent(ImportKind.Config, source.readText())
        }

    fun importPrivateIdentity() =
        io.run("Private identity imported", "Private identity import failed", onSuccess = {
            cachedPublicIdentity = null
            _state.value = _state.value.copy(privateIdentityImportPath = "")
        }) {
            val privateIdentity =
                deps.identityRepository
                    .readPrivateIdentityFile(_state.value.privateIdentityImportPath.trim())
                    .getOrThrow()
            importService.importContent(ImportKind.PrivateIdentity, privateIdentity)
        }

    fun importPublicIdentity() =
        io.run("Public identity imported", "Public identity import failed", onSuccess = {
            _state.value = _state.value.copy(publicIdentityLine = "")
        }) {
            importService.importContent(ImportKind.PublicIdentity, _state.value.publicIdentityLine)
        }

    fun importFromUri(
        uri: Uri,
        kind: ImportKind,
    ) = io.run("${kind.label} imported", "${kind.label} import failed", onSuccess = {
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
) {
    fun run(
        successMessage: String,
        failureFallback: String,
        onSuccess: () -> Unit = {},
        block: suspend () -> Unit,
    ) {
        if (state.value.isBusy) return
        scope.launch {
            state.value = state.value.copy(isBusy = true, resultMessage = null)
            val result = withContext(io) { runCatching { block() } }
            if (result.isSuccess) onSuccess()
            val message = result.fold({ successMessage }, { it.message ?: failureFallback })
            state.value = state.value.copy(isBusy = false, resultMessage = message)
            snackbar.show(message)
        }
    }
}
