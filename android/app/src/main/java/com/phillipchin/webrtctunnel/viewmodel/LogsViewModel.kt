package com.phillipchin.webrtctunnel.viewmodel

import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.phillipchin.webrtctunnel.data.AppDependencies
import com.phillipchin.webrtctunnel.model.LogEvent
import com.phillipchin.webrtctunnel.model.NetworkStatus
import com.phillipchin.webrtctunnel.model.TunnelError
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LogsViewModel(private val deps: AppDependencies) : ViewModel() {
    private val _logs = MutableStateFlow<List<LogEvent>>(emptyList())
    val logs: StateFlow<List<LogEvent>> = _logs.asStateFlow()
    private val _filter = MutableStateFlow("all")
    val filter: StateFlow<String> = _filter.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()
    private val _isBusy = MutableStateFlow(false)
    val isBusy: StateFlow<Boolean> = _isBusy.asStateFlow()

    // P1-014: Generation counter to prevent stale refresh from overwriting newer results.
    private var refreshGeneration = 0L

    // P1-007: Own log error state locally so generation check covers both logs and error.
    private val _logsError = MutableStateFlow<String?>(null)
    val logsError: StateFlow<String?> = _logsError.asStateFlow()

    val filteredLogs: StateFlow<List<LogEvent>> =
        combine(_logs, _filter) { logs, level ->
            if (level == "all") logs else logs.filter { it.level.equals(level, ignoreCase = true) }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun refresh(maxEvents: Int = 200) {
        // P1-014: Bump generation to invalidate any in-flight refresh.
        val generation = ++refreshGeneration
        viewModelScope.launch {
            val result = withContext(deps.dispatchers.io) { deps.tunnelRepository.recentLogs(maxEvents) }
            // P1-007: Only apply if this generation is still the latest.
            if (refreshGeneration == generation) {
                _logs.value = result.logs
                _logsError.value = result.error
            }
        }
    }

    fun setFilter(level: String) {
        _filter.value = level
    }

    fun clearLogs() {
        _logs.value = emptyList()
        deps.snackbar.show("Logs cleared")
    }

    /** Surface a confirmation for the screen-side clipboard copy. */
    fun onLogsCopied() {
        deps.snackbar.show("Logs copied to clipboard")
    }

    fun exportDiagnostics(
        path: String,
        networkStatus: NetworkStatus,
    ) {
        if (_isBusy.value) return
        viewModelScope.launch {
            _isBusy.value = true
            try {
                val status = deps.tunnelRepository.status.value
                val logs = _logs.value
                val result =
                    withContext(deps.dispatchers.io) {
                        deps.diagnosticsRepository.exportRedactedDiagnostics(
                            outputPath = path,
                            status = status,
                            logs = logs,
                            networkStatus = networkStatus,
                        )
                    }
                val message = result.fold({ "Diagnostics exported" }, { it.message ?: "Diagnostics export failed" })
                _message.value = message
                deps.snackbar.show(message)
            } finally {
                _isBusy.value = false
            }
        }
    }

    fun exportDiagnosticsToUri(
        uri: Uri,
        networkStatus: NetworkStatus,
    ) {
        if (_isBusy.value) return
        viewModelScope.launch {
            _isBusy.value = true
            try {
                val status = deps.tunnelRepository.status.value
                val logs = _logs.value
                val result =
                    withContext(deps.dispatchers.io) {
                        runCatching {
                            val payload =
                                deps.diagnosticsRepository.buildRedactedDiagnosticsPayload(
                                    status = status,
                                    logs = logs,
                                    networkStatus = networkStatus,
                                )
                            deps.context.contentResolver.openOutputStream(uri, "wt")?.use { stream ->
                                stream.write(payload.toByteArray())
                            } ?: error("Unable to open destination URI")
                        }
                    }
                val message = result.fold({ "Diagnostics exported" }, { it.message ?: "Diagnostics export failed" })
                _message.value = message
                deps.snackbar.show(message)
            } finally {
                _isBusy.value = false
            }
        }
    }

    suspend fun diagnosticsShareIntent(networkStatus: NetworkStatus): Intent {
        val status = deps.tunnelRepository.status.value
        val logs = _logs.value
        val payload =
            withContext(deps.dispatchers.io) {
                deps.diagnosticsRepository.buildRedactedDiagnosticsPayload(
                    status = status,
                    logs = logs,
                    networkStatus = networkStatus,
                )
            }
        return Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "WebRTC Tunnel diagnostics (redacted)")
            putExtra(Intent.EXTRA_TEXT, payload)
        }
    }
}
