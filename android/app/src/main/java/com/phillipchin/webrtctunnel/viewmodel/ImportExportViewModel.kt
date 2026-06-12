package com.phillipchin.webrtctunnel.viewmodel

import android.net.Uri
import androidx.lifecycle.ViewModel
import com.phillipchin.webrtctunnel.data.AppDependencies
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ImportExportState(
    val configImportPath: String = "",
    val privateIdentityImportPath: String = "",
    val publicIdentityLine: String = "",
    val configExportPath: String = "",
    val publicIdentityExportPath: String = "",
    val privateIdentityExportPath: String = "",
    val diagnosticsExportPath: String = "",
    val resultMessage: String? = null,
)

class ImportExportViewModel(private val deps: AppDependencies) : ViewModel() {
    private val _state = MutableStateFlow(ImportExportState())
    val state: StateFlow<ImportExportState> = _state.asStateFlow()
    private val importService = ImportExportService(deps)

    fun updateState(transform: (ImportExportState) -> ImportExportState) {
        _state.value = transform(_state.value).copy(resultMessage = null)
    }

    fun importConfig() {
        runCatching {
            val source = java.io.File(_state.value.configImportPath.trim())
            require(source.exists()) { "Config file not found" }
            importService.importContent(ImportKind.Config, source.readText())
        }.onSuccess {
            _state.value = _state.value.copy(resultMessage = "Config imported")
        }.onFailure {
            _state.value = _state.value.copy(resultMessage = it.message ?: "Config import failed")
        }
    }

    fun importPrivateIdentity() {
        runCatching {
            val privateIdentity =
                deps.identityRepository
                    .readPrivateIdentityFile(_state.value.privateIdentityImportPath.trim())
                    .getOrThrow()
            importService.importContent(ImportKind.PrivateIdentity, privateIdentity)
        }.onSuccess {
            _state.value = _state.value.copy(resultMessage = "Private identity imported")
        }.onFailure {
            _state.value = _state.value.copy(resultMessage = it.message ?: "Private identity import failed")
        }
    }

    fun importPublicIdentity() {
        runCatching {
            importService.importContent(ImportKind.PublicIdentity, _state.value.publicIdentityLine)
        }.onSuccess {
            _state.value = _state.value.copy(resultMessage = "Public identity imported")
        }.onFailure {
            _state.value = _state.value.copy(resultMessage = it.message ?: "Public identity import failed")
        }
    }

    fun importFromUri(
        uri: Uri,
        kind: ImportKind,
    ) {
        runCatching {
            val content =
                deps.context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                    ?: error("Unable to read ${kind.label.lowercase()} from selected URI")
            importService.importContent(kind, content)
        }.onSuccess {
            _state.value = _state.value.copy(resultMessage = "${kind.label} imported")
        }.onFailure {
            _state.value = _state.value.copy(resultMessage = it.message ?: "${kind.label} import failed")
        }
    }

    fun exportConfig(confirmSensitive: Boolean) {
        runCatching {
            val output = java.io.File(_state.value.configExportPath.trim())
            output.parentFile?.mkdirs()
            output.writeText(importService.configForExport(confirmSensitive))
        }.onSuccess {
            _state.value = _state.value.copy(resultMessage = "Raw config exported")
        }.onFailure {
            _state.value = _state.value.copy(resultMessage = it.message ?: "Config export failed")
        }
    }

    fun exportConfigToUri(
        uri: Uri,
        confirmSensitive: Boolean,
    ) {
        runCatching {
            val payload = importService.configForExport(confirmSensitive)
            deps.context.contentResolver.openOutputStream(uri, "wt")?.use { stream ->
                stream.write(payload.toByteArray())
            } ?: error("Unable to open destination URI")
        }.onSuccess {
            _state.value = _state.value.copy(resultMessage = "Raw config exported")
        }.onFailure {
            _state.value = _state.value.copy(resultMessage = it.message ?: "Config export failed")
        }
    }

    fun exportPublicIdentityToUri(uri: Uri) {
        runCatching {
            val payload = publicIdentityForShare()
            deps.context.contentResolver.openOutputStream(uri, "wt")?.use { stream ->
                stream.write(payload.toByteArray())
            } ?: error("Unable to open destination URI")
        }.onSuccess {
            _state.value = _state.value.copy(resultMessage = "Public identity exported")
        }.onFailure {
            _state.value = _state.value.copy(resultMessage = it.message ?: "Public identity export failed")
        }
    }

    fun exportPrivateIdentityToUri(
        uri: Uri,
        confirmRisk: Boolean,
    ) {
        runCatching {
            require(confirmRisk) { "Private export requires explicit confirmation" }
            val payload = deps.identityRepository.readPrivateIdentityPlaintext()
            deps.context.contentResolver.openOutputStream(uri, "wb")?.use { stream ->
                stream.write(payload)
            } ?: error("Unable to open destination URI")
        }.onSuccess {
            _state.value = _state.value.copy(resultMessage = "Private identity exported")
        }.onFailure {
            _state.value = _state.value.copy(resultMessage = it.message ?: "Private identity export failed")
        }
    }

    fun publicIdentityForShare(): String {
        val value = deps.identityRepository.readPublicIdentity()
        require(value.isNotBlank()) { "No public identity available" }
        return value
    }
}
