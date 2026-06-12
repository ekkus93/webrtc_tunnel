package com.phillipchin.webrtctunnel.viewmodel

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.phillipchin.webrtctunnel.data.AppDependencies
import com.phillipchin.webrtctunnel.data.SensitiveDataRedactor
import com.phillipchin.webrtctunnel.model.AndroidAppPreferences
import com.phillipchin.webrtctunnel.model.SetupConfigInput
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

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

    fun savePreferences(updated: AndroidAppPreferences) {
        viewModelScope.launch { deps.configRepository.savePreferences(updated) }
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

    fun statusJson(): String =
        runCatching {
            Json.encodeToString(SensitiveDataRedactor.redactStatus(deps.tunnelRepository.status.value))
        }.getOrDefault("{}")

    suspend fun redactedConfigOrEmpty(): String =
        withContext(deps.dispatchers.io) {
            runCatching {
                val configPath = deps.configRepository.configPath
                val raw = File(configPath).takeIf { it.exists() }?.readText() ?: return@runCatching ""
                SensitiveDataRedactor.redactText(raw)
            }.getOrDefault("")
        }

    fun resetConfiguration() {
        viewModelScope.launch {
            withContext(deps.dispatchers.io) {
                runCatching {
                    deps.configRepository.writeConfigAtomically(deps.configRepository.defaultConfigTemplate())
                    deps.configRepository.saveSetupInput(SetupConfigInput())
                    deps.forwardsStore.saveForwards(emptyList())
                }
            }
        }
    }

    suspend fun diagnosticsShareIntent(): Intent {
        val statusJson = statusJson()
        val redactedConfig = redactedConfigOrEmpty()
        val payload =
            buildString {
                appendLine("status_json=$statusJson")
                appendLine("config_redacted=$redactedConfig")
            }
        return Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "WebRTC Tunnel diagnostics (redacted)")
            putExtra(Intent.EXTRA_TEXT, payload)
        }
    }
}
