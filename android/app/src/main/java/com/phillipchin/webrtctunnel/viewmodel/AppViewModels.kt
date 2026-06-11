package com.phillipchin.webrtctunnel.viewmodel

import android.content.Intent
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.phillipchin.webrtctunnel.TunnelForegroundService
import com.phillipchin.webrtctunnel.data.AppDependencies
import com.phillipchin.webrtctunnel.data.SensitiveDataRedactor
import com.phillipchin.webrtctunnel.model.AndroidAppPreferences
import com.phillipchin.webrtctunnel.model.ForwardConfig
import com.phillipchin.webrtctunnel.model.LogEvent
import com.phillipchin.webrtctunnel.model.SetupConfigInput
import com.phillipchin.webrtctunnel.model.TunnelMode
import com.phillipchin.webrtctunnel.model.TunnelStatus
import com.phillipchin.webrtctunnel.model.ValidationResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket

internal const val MAX_PORT = 65535
private const val LOCAL_PORT_TEST_TIMEOUT_MS = 1200

enum class SetupStep {
    Mode,
    Identity,
    Broker,
    Peer,
    Forwards,
    NetworkPolicy,
    Review,
}

data class SetupWizardState(
    val currentStep: SetupStep = SetupStep.Mode,
    val input: SetupConfigInput = SetupConfigInput(),
    val importIdentityPath: String = "",
    val importPublicIdentity: String = "",
    val localPublicIdentity: String = "",
    val identityPeerId: String? = null,
    val remoteIdentityPeerId: String? = null,
    val brokerTestMessage: String? = null,
    val advancedExpanded: Boolean = false,
    val canAdvance: Boolean = false,
    val errorMessage: String? = null,
    val saveResult: String? = null,
)

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

data class SettingsUiState(
    val publicIdentity: String? = null,
    val publicIdentityLoadError: String? = null,
)

class HomeViewModel(private val deps: AppDependencies) : ViewModel() {
    val status: StateFlow<TunnelStatus> = deps.tunnelRepository.status
    private val _configuredForwards = MutableStateFlow(deps.forwardsStore.loadForwards())
    val configuredForwards: StateFlow<List<ForwardConfig>> = _configuredForwards.asStateFlow()

    fun startTunnel(mode: TunnelMode) {
        val action =
            when (mode) {
                TunnelMode.Offer -> TunnelForegroundService.ACTION_START_OFFER
                TunnelMode.Answer -> return
            }
        ContextCompat.startForegroundService(
            deps.context,
            Intent(deps.context, TunnelForegroundService::class.java).setAction(action),
        )
    }

    fun stopTunnel() {
        deps.context.startService(
            Intent(deps.context, TunnelForegroundService::class.java)
                .setAction(TunnelForegroundService.ACTION_STOP),
        )
    }

    fun allowMeteredTemporarily() {
        ContextCompat.startForegroundService(
            deps.context,
            Intent(deps.context, TunnelForegroundService::class.java)
                .setAction(TunnelForegroundService.ACTION_ALLOW_METERED_SESSION),
        )
    }

    fun refresh() = deps.tunnelRepository.refreshStatus()

    fun refreshForwards() {
        _configuredForwards.value = deps.forwardsStore.loadForwards()
    }
}

class SetupViewModel(
    private val deps: AppDependencies,
    private val loadPreferences: suspend () -> AndroidAppPreferences = { deps.configRepository.preferences.first() },
    private val persistPreferences: suspend (
        AndroidAppPreferences,
    ) -> Unit = { deps.configRepository.savePreferences(it) },
) : ViewModel() {
    private val _state = MutableStateFlow(SetupWizardState())
    val state: StateFlow<SetupWizardState> = _state.asStateFlow()
    private val _forwards = MutableStateFlow(emptyList<ForwardConfig>())
    val forwards: StateFlow<List<ForwardConfig>> = _forwards.asStateFlow()
    private val steps = SetupStep.entries
    val networkStatus =
        combine(deps.networkPolicyManager.status, state) { _, wizardState ->
            deps.networkPolicyManager.evaluateWithPolicy(wizardState.input.allowMetered)
        }
    val preferences = deps.configRepository.preferences

    private val stateAccess =
        WizardStateAccess(
            state = { _state.value },
            forwards = { _forwards.value },
            applyState = ::applyState,
            setForwards = { _forwards.value = it },
        )

    val save =
        SetupSaveController(
            deps = deps,
            scope = viewModelScope,
            loadPreferences = loadPreferences,
            persistPreferences = persistPreferences,
            access = stateAccess,
        )

    val identity = SetupIdentityController(deps, stateAccess)

    val forwardsEditor = SetupForwardsController(deps, stateAccess)

    init {
        loadStoredSetupInput(deps, stateAccess)
        identity.loadStoredIdentity()
        forwardsEditor.refreshForwards()
    }

    private fun applyState(newState: SetupWizardState) {
        _state.value = newState.copy(canAdvance = canAdvance(deps, newState, _forwards.value))
    }

    private fun updateState(transform: (SetupWizardState) -> SetupWizardState) {
        val updated = transform(_state.value)
        _state.value = updated.copy(canAdvance = canAdvance(deps, updated, _forwards.value))
    }

    fun setInput(update: SetupConfigInput) {
        updateState { current ->
            current.copy(
                input = update,
                errorMessage = null,
                saveResult = null,
                brokerTestMessage = null,
            )
        }
    }

    fun setImportIdentityPath(path: String) {
        updateState { current -> current.copy(importIdentityPath = path, errorMessage = null, saveResult = null) }
    }

    fun setImportPublicIdentity(value: String) {
        updateState { current ->
            current.copy(
                importPublicIdentity = value,
                remoteIdentityPeerId = null,
                errorMessage = null,
            )
        }
    }

    fun setAdvancedExpanded(expanded: Boolean) {
        updateState { current -> current.copy(advancedExpanded = expanded) }
    }

    fun goBack() {
        val current = _state.value.currentStep
        val index = steps.indexOf(current)
        if (index > 0) {
            _state.value =
                _state.value
                    .copy(currentStep = steps[index - 1], errorMessage = null)
                    .withCanAdvance(deps, _forwards.value)
        }
    }

    fun cancel() {
        _state.value = SetupWizardState()
        forwardsEditor.refreshForwards()
    }

    fun goNext() {
        val current = _state.value
        val validationError = validateStep(deps, current.currentStep, current)
        if (validationError != null) {
            _state.value =
                current
                    .copy(errorMessage = validationError)
                    .withCanAdvance(deps, _forwards.value)
            return
        }
        val index = steps.indexOf(current.currentStep)
        if (index < steps.lastIndex) {
            _state.value =
                current
                    .copy(currentStep = steps[index + 1], errorMessage = null)
                    .withCanAdvance(deps, _forwards.value)
        }
    }

    fun canAdvanceFromCurrentStep(): Boolean {
        return _state.value.canAdvance
    }
}

private fun SetupWizardState.withCanAdvance(
    deps: AppDependencies,
    forwards: List<ForwardConfig>,
): SetupWizardState = copy(canAdvance = canAdvance(deps, this, forwards))

private fun canAdvance(
    deps: AppDependencies,
    state: SetupWizardState,
    forwards: List<ForwardConfig>,
): Boolean {
    return when (state.currentStep) {
        SetupStep.Mode -> true
        SetupStep.Identity -> state.localPublicIdentity.isNotBlank() || state.importIdentityPath.isNotBlank()
        SetupStep.Broker -> state.input.brokerHost.isNotBlank() && state.input.brokerPort in 1..MAX_PORT
        SetupStep.Peer -> state.input.remotePeerId.isNotBlank() && state.importPublicIdentity.isNotBlank()
        SetupStep.Forwards -> forwards.isNotEmpty() && deps.forwardsStore.validateForwards(forwards) == null
        SetupStep.NetworkPolicy -> true
        SetupStep.Review -> {
            state.input.brokerHost.isNotBlank() &&
                state.input.brokerPort in 1..MAX_PORT &&
                state.input.remotePeerId.isNotBlank() &&
                state.importPublicIdentity.isNotBlank() &&
                forwards.isNotEmpty() &&
                deps.forwardsStore.validateForwards(forwards) == null
        }
    }
}

private fun loadStoredSetupInput(
    deps: AppDependencies,
    access: WizardStateAccess,
) {
    val saved = runCatching { deps.configRepository.loadSetupInput() }.getOrNull() ?: return
    if (saved.brokerHost.isNotBlank() || saved.remotePeerId.isNotBlank()) {
        access.applyState(access.state().copy(input = saved))
    }
}

class ForwardsViewModel(private val deps: AppDependencies) : ViewModel() {
    val status: StateFlow<TunnelStatus> = deps.tunnelRepository.status
    private val _forwards = MutableStateFlow(deps.forwardsStore.loadForwards())
    val forwards: StateFlow<List<ForwardConfig>> = _forwards.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun reload() {
        _forwards.value = deps.forwardsStore.loadForwards()
    }

    fun saveForward(forward: ForwardConfig) {
        val before = deps.forwardsStore.loadForwards()
        val result = deps.forwardsStore.upsertForward(forward)
        if (!result.valid) {
            _message.value = result.message ?: "Forward update failed"
            return
        }
        val sync = regenerateActiveConfig()
        if (!sync.valid) {
            deps.forwardsStore.saveForwards(before)
            reload()
            _message.value = sync.message ?: "Forward update failed"
            return
        }
        reload()
        _message.value = "Forward saved"
    }

    fun deleteForward(forwardId: String) {
        val before = deps.forwardsStore.loadForwards()
        deps.forwardsStore.deleteForward(forwardId)
        val sync = regenerateActiveConfig()
        if (!sync.valid) {
            deps.forwardsStore.saveForwards(before)
            reload()
            _message.value = sync.message ?: "Forward delete failed"
            return
        }
        reload()
        _message.value = "Forward deleted"
    }

    fun validateForwardDraft(
        draft: ForwardConfig,
        currentForwards: List<ForwardConfig>,
    ): String? {
        val updated =
            currentForwards.map { if (it.id == draft.id) draft else it }.let { candidates ->
                if (candidates.none { it.id == draft.id }) candidates + draft else candidates
            }
        return deps.forwardsStore.validateForwards(updated)
    }

    fun testLocalPort(forward: ForwardConfig) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            // Connect to the configured local host (blank falls back to loopback),
            // and report the host actually tested rather than a hardcoded address.
            val host = forward.localHost.trim().ifBlank { "127.0.0.1" }
            val resultMessage =
                runCatching {
                    Socket().use { socket ->
                        socket.connect(InetSocketAddress(host, forward.localPort), LOCAL_PORT_TEST_TIMEOUT_MS)
                    }
                    "Local port test succeeded for $host:${forward.localPort}"
                }.getOrElse {
                    "Local port test failed for $host:${forward.localPort}: ${it.message}"
                }
            _message.value = SensitiveDataRedactor.redactText(resultMessage)
        }
    }

    private fun regenerateActiveConfig(): ValidationResult {
        val input = deps.configRepository.loadSetupInput()
        val forwards = deps.forwardsStore.loadForwards().filter { it.enabled }
        val candidate = deps.configRepository.renderOfferConfig(input, forwards)
        val temp = File(deps.context.cacheDir, "config-forwards-candidate.toml")
        val identity = runCatching { deps.identityRepository.readPrivateIdentityPlaintext() }.getOrNull()
        return runCatching {
            temp.parentFile?.mkdirs()
            temp.writeText(candidate)
            val result =
                if (identity != null && identity.isNotEmpty()) {
                    deps.identityValidation.validateConfigWithIdentity(temp.absolutePath, identity)
                } else {
                    deps.identityValidation.validateConfig(temp.absolutePath)
                }
            if (result.valid) {
                deps.configRepository.writeConfigAtomically(candidate)
            }
            result
        }.getOrElse { ValidationResult(false, it.message) }.also {
            temp.delete()
        }
    }
}

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

class SettingsViewModel(
    private val deps: AppDependencies,
    private val loadPublicIdentity: suspend () -> String = {
        withContext(Dispatchers.IO) { deps.identityRepository.readPublicIdentity() }
    },
) : ViewModel() {
    val preferences = deps.configRepository.preferences
    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        refreshPublicIdentity()
    }

    fun validateConfig(): ValidationResult = deps.identityValidation.validateConfig(deps.configRepository.configPath)

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

    fun redactedConfigOrEmpty(): String =
        runCatching {
            val configPath = deps.configRepository.configPath
            val raw = File(configPath).takeIf { it.exists() }?.readText() ?: return@runCatching ""
            SensitiveDataRedactor.redactText(raw)
        }.getOrDefault("")

    fun resetConfiguration() {
        runCatching {
            deps.configRepository.writeConfigAtomically(deps.configRepository.defaultConfigTemplate())
            deps.configRepository.saveSetupInput(SetupConfigInput())
            deps.forwardsStore.saveForwards(emptyList())
        }
    }

    fun diagnosticsShareIntent(): Intent {
        val payload =
            buildString {
                appendLine("status_json=${statusJson()}")
                appendLine("config_redacted=${redactedConfigOrEmpty()}")
            }
        return Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "WebRTC Tunnel diagnostics (redacted)")
            putExtra(Intent.EXTRA_TEXT, payload)
        }
    }
}

class NetworkPolicyViewModel(private val deps: AppDependencies) : ViewModel() {
    val networkStatus =
        combine(deps.networkPolicyManager.status, deps.configRepository.preferences) { _, prefs ->
            deps.networkPolicyManager.evaluateWithPolicy(prefs.allowMetered)
        }
    val preferences = deps.configRepository.preferences

    fun savePreferences(updated: com.phillipchin.webrtctunnel.model.AndroidAppPreferences) {
        viewModelScope.launch { deps.configRepository.savePreferences(updated) }
    }
}

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

class AppViewModelFactory(private val deps: AppDependencies) {
    fun home() = HomeViewModel(deps)

    fun setup() = SetupViewModel(deps)

    fun forwards() = ForwardsViewModel(deps)

    fun logs() = LogsViewModel(deps)

    fun settings() = SettingsViewModel(deps)

    fun networkPolicy() = NetworkPolicyViewModel(deps)

    fun importExport() = ImportExportViewModel(deps)
}
