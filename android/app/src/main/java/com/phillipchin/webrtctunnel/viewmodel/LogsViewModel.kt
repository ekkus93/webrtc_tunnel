package com.phillipchin.webrtctunnel.viewmodel

import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.phillipchin.webrtctunnel.data.AppDependencies
import com.phillipchin.webrtctunnel.model.LogEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

class LogsViewModel(private val deps: AppDependencies) : ViewModel() {
    private val _logs = MutableStateFlow<List<LogEvent>>(emptyList())
    val logs: StateFlow<List<LogEvent>> = _logs.asStateFlow()
    private val _filter = MutableStateFlow("all")
    val filter: StateFlow<String> = _filter.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    val filteredLogs: StateFlow<List<LogEvent>> =
        combine(_logs, _filter) { logs, level ->
            if (level == "all") logs else logs.filter { it.level.equals(level, ignoreCase = true) }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun refresh(maxEvents: Int = 200) {
        _logs.value = deps.tunnelRepository.recentLogs(maxEvents)
    }

    fun setFilter(level: String) {
        _filter.value = level
    }

    fun clearLogs() {
        _logs.value = emptyList()
    }

    fun exportDiagnostics(
        path: String,
        networkStatus: com.phillipchin.webrtctunnel.model.NetworkStatus,
    ) {
        deps.diagnosticsRepository.exportRedactedDiagnostics(
            outputPath = path,
            status = deps.tunnelRepository.status.value,
            logs = _logs.value,
            networkStatus = networkStatus,
        ).onSuccess {
            _message.value = "Diagnostics exported"
        }.onFailure {
            _message.value = it.message ?: "Diagnostics export failed"
        }
    }

    fun exportDiagnosticsToUri(
        uri: Uri,
        networkStatus: com.phillipchin.webrtctunnel.model.NetworkStatus,
    ) {
        runCatching {
            val payload =
                deps.diagnosticsRepository.buildRedactedDiagnosticsPayload(
                    status = deps.tunnelRepository.status.value,
                    logs = _logs.value,
                    networkStatus = networkStatus,
                )
            deps.context.contentResolver.openOutputStream(uri, "wt")?.use { stream ->
                stream.write(payload.toByteArray())
            } ?: error("Unable to open destination URI")
        }.onSuccess {
            _message.value = "Diagnostics exported"
        }.onFailure {
            _message.value = it.message ?: "Diagnostics export failed"
        }
    }

    fun diagnosticsShareIntent(networkStatus: com.phillipchin.webrtctunnel.model.NetworkStatus): Intent {
        val payload =
            deps.diagnosticsRepository.buildRedactedDiagnosticsPayload(
                status = deps.tunnelRepository.status.value,
                logs = _logs.value,
                networkStatus = networkStatus,
            )
        return Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "WebRTC Tunnel diagnostics (redacted)")
            putExtra(Intent.EXTRA_TEXT, payload)
        }
    }
}
