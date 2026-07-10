package com.phillipchin.webrtctunnel.viewmodel

import android.content.Intent
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.phillipchin.webrtctunnel.data.AppDependencies
import com.phillipchin.webrtctunnel.data.ResetResult
import com.phillipchin.webrtctunnel.data.SensitiveDataRedactor
import com.phillipchin.webrtctunnel.model.AndroidAppPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
private data class StatusDiagnosticsError(
    @SerialName("status_json_error") val statusJsonError: String,
)

// Extracted so a test can force the statusJson() error path with a specific message
// (quotes/backslashes/newlines/secrets) without depending on the status object
// actually failing to serialize.
internal fun statusDiagnosticsErrorJson(message: String?): String =
    Json.encodeToString(
        StatusDiagnosticsError(
            statusJsonError = SensitiveDataRedactor.redactText(message ?: "unknown status serialization failure"),
        ),
    )

data class SettingsUiState(
    val publicIdentity: String? = null,
    val publicIdentityLoadError: String? = null,
    val configValidationMessage: String? = null,
    val configValid: Boolean? = null,
    val isValidatingConfig: Boolean = false,
)

class SettingsViewModel(
    private val deps: AppDependencies,
    private val loadPublicIdentity: suspend () -> String = {
        withContext(deps.dispatchers.io) { deps.identityRepository.readPublicIdentity() }
    },
) : ViewModel() {
    val preferences = deps.configRepository.preferences
    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        refreshPublicIdentity()
    }

    fun validateConfig() {
        if (_uiState.value.isValidatingConfig) return
        viewModelScope.launch {
            _uiState.value =
                _uiState.value.copy(isValidatingConfig = true, configValidationMessage = null, configValid = null)
            val result =
                withContext(deps.dispatchers.io) {
                    runCatching { deps.identityValidation.validateConfig(deps.configRepository.configPath) }
                }
            val valid = result.map { it.valid }.getOrDefault(false)
            val message =
                result.fold(
                    onSuccess = {
                        if (it.valid) "Configuration is valid." else (it.message ?: "Configuration is invalid.")
                    },
                    onFailure = { it.message ?: "Validation failed." },
                )
            _uiState.value =
                _uiState.value.copy(
                    isValidatingConfig = false,
                    configValid = valid,
                    configValidationMessage = SensitiveDataRedactor.redactText(message),
                )
        }
    }

    // P1-016: Surface preference-write failures.
    fun savePreferences(updated: AndroidAppPreferences) {
        viewModelScope.launch {
            deps.configRepository.savePreferences(updated).fold(
                onSuccess = { deps.snackbar.show("Preferences saved") },
                onFailure = { error -> deps.snackbar.show("Preferences save failed: ${error.message}") },
            )
        }
    }

    fun refreshPublicIdentity() {
        viewModelScope.launch {
            runCatching { loadPublicIdentity().ifBlank { null } }
                .onSuccess { publicIdentity ->
                    _uiState.value =
                        _uiState.value.copy(
                            publicIdentity = publicIdentity,
                            publicIdentityLoadError = null,
                        )
                }
                .onFailure { error ->
                    _uiState.value =
                        _uiState.value.copy(
                            publicIdentity = null,
                            publicIdentityLoadError =
                                SensitiveDataRedactor.redactText(
                                    error.message ?: "Unable to load local public identity",
                                ),
                        )
                }
        }
    }

    /** Real status JSON on success, or a JSON object carrying a redacted error message —
     * never a bare `"{}"`, which would be indistinguishable from a legitimately idle/empty
     * status. Used for both the "copy status JSON" clipboard action and diagnostics share. */
    fun statusJson(): String =
        runCatching {
            Json.encodeToString(SensitiveDataRedactor.redactStatus(deps.tunnelRepository.status.value))
        }.getOrElse { error -> statusDiagnosticsErrorJson(error.message) }

    /** Redacted config file contents, or an explicit marker distinguishing "no config file
     * yet" (expected before setup completes) from "config file present but unreadable/failed
     * to redact" — never a bare empty string for either case. Used for both the "copy redacted
     * config" clipboard action and diagnostics share. */
    suspend fun redactedConfig(): String =
        withContext(deps.dispatchers.io) {
            val file = File(deps.configRepository.configPath)
            if (!file.exists()) return@withContext "(no config file present)"
            runCatching { SensitiveDataRedactor.redactText(file.readText()) }
                .getOrElse { error ->
                    "(config read/redaction failed: " +
                        SensitiveDataRedactor.redactText(error.message ?: "unknown error") +
                        ")"
                }
        }

    // P2-003: Uses TransactionalResetCoordinator for atomic multi-file reset.
    fun resetConfiguration() {
        viewModelScope.launch {
            val result =
                withContext(deps.dispatchers.io) {
                    deps.transactionalResetCoordinator.resetConfiguration()
                }
            when (result) {
                ResetResult.Success -> {
                    deps.snackbar.show("Configuration reset")
                }
                is ResetResult.PartialFailure -> {
                    val summary = "Reset partial: ${result.failedStages.joinToString("; ")}"
                    Log.e("SettingsViewModel", summary)
                    deps.snackbar.show(summary)
                }
            }
        }
    }

    suspend fun diagnosticsShareIntent(): Intent {
        val payload =
            buildString {
                appendLine("status_json=${statusJson()}")
                appendLine("config_redacted=${redactedConfig()}")
            }
        return Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "WebRTC Tunnel diagnostics (redacted)")
            putExtra(Intent.EXTRA_TEXT, payload)
        }
    }
}
