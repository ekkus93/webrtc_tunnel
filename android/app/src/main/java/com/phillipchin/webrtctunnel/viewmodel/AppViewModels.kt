package com.phillipchin.webrtctunnel.viewmodel

import android.content.Intent
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.phillipchin.webrtctunnel.TunnelForegroundService
import com.phillipchin.webrtctunnel.data.AppDependencies
import com.phillipchin.webrtctunnel.model.ForwardConfig
import com.phillipchin.webrtctunnel.model.LogEvent
import com.phillipchin.webrtctunnel.model.SetupConfigInput
import com.phillipchin.webrtctunnel.model.TunnelMode
import com.phillipchin.webrtctunnel.model.TunnelStatus
import com.phillipchin.webrtctunnel.model.ValidationResult
import com.phillipchin.webrtctunnel.data.SensitiveDataRedactor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.net.Socket
import java.io.File

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

class HomeViewModel(private val deps: AppDependencies) : ViewModel() {
    val status: StateFlow<TunnelStatus> = deps.tunnelRepository.status

    fun startTunnel(mode: TunnelMode): Unit {
        val action = when (mode) {
            TunnelMode.Offer -> TunnelForegroundService.ACTION_START_OFFER
            TunnelMode.Answer -> return
        }
        ContextCompat.startForegroundService(
            deps.context,
            Intent(deps.context, TunnelForegroundService::class.java).setAction(action),
        )
    }

    fun stopTunnel(): Unit {
        deps.context.startService(
            Intent(deps.context, TunnelForegroundService::class.java)
                .setAction(TunnelForegroundService.ACTION_STOP),
        )
    }

    fun refresh() = deps.tunnelRepository.refreshStatus()
}

class SetupViewModel(private val deps: AppDependencies) : ViewModel() {
    private val _state = MutableStateFlow(SetupWizardState())
    val state: StateFlow<SetupWizardState> = _state.asStateFlow()
    private val steps = SetupStep.entries
    val networkStatus = combine(deps.networkPolicyManager.status, state) { _, wizardState ->
        deps.networkPolicyManager.evaluateWithPolicy(wizardState.input.allowMetered)
    }
    val preferences = deps.configRepository.preferences

    fun validateConfig(): ValidationResult = deps.tunnelRepository.validateConfig(deps.configRepository.configPath)

    fun setInput(update: SetupConfigInput) {
        _state.value = _state.value.copy(input = update, errorMessage = null, saveResult = null)
    }

    fun setImportIdentityPath(path: String) {
        val trimmed = path.trim()
        if (trimmed.isBlank()) {
            _state.value = _state.value.copy(importIdentityPath = "", identityPeerId = null, errorMessage = null)
            return
        }
        val resolved = runCatching {
            val privateIdentity = deps.identityRepository.readPrivateIdentityFile(trimmed).getOrThrow()
            val validated = deps.tunnelRepository.validatePrivateIdentity(privateIdentity)
            require(validated.valid) { validated.message ?: "Invalid private identity" }
            val peerId = validated.peer_id ?: throw IllegalArgumentException("Missing identity peer id")
            val canonicalPublic = validated.canonical_public_identity ?: ""
            peerId to canonicalPublic
        }
        resolved.onSuccess { (peerId, canonicalPublic) ->
            _state.value = _state.value.copy(
                importIdentityPath = trimmed,
                identityPeerId = peerId,
                localPublicIdentity = canonicalPublic,
                input = _state.value.input.copy(localPeerId = peerId),
                errorMessage = null,
            )
        }.onFailure {
            _state.value = _state.value.copy(
                importIdentityPath = trimmed,
                identityPeerId = null,
                errorMessage = it.message ?: "Invalid private identity file",
            )
        }
    }

    fun setImportPublicIdentity(value: String) {
        val validated = deps.tunnelRepository.validatePublicIdentity(value)
        _state.value = _state.value.copy(
            importPublicIdentity = value,
            remoteIdentityPeerId = if (validated.valid) validated.peer_id else null,
            errorMessage = null,
        )
    }

    fun generateIdentity() {
        val current = _state.value
        val generated = deps.tunnelRepository.generateIdentity(current.input.localPeerId)
        if (!generated.valid) {
            _state.value = current.copy(errorMessage = generated.message ?: "Identity generation failed")
            return
        }
        val privateIdentity = generated.canonical_private_identity
        val publicIdentity = generated.canonical_public_identity
        if (privateIdentity.isNullOrBlank() || publicIdentity.isNullOrBlank()) {
            _state.value = current.copy(errorMessage = "Identity generation returned incomplete data")
            return
        }
        deps.identityRepository.storeEncryptedIdentity(privateIdentity.toByteArray(), publicIdentity)
        val peerId = generated.peer_id ?: current.input.localPeerId
        _state.value = current.copy(
            input = current.input.copy(localPeerId = peerId),
            localPublicIdentity = publicIdentity,
            identityPeerId = peerId,
            errorMessage = null,
            saveResult = "Identity generated",
        )
    }

    fun goBack() {
        val current = _state.value.currentStep
        val index = steps.indexOf(current)
        if (index > 0) {
            _state.value = _state.value.copy(currentStep = steps[index - 1], errorMessage = null)
        }
    }

    fun goNext() {
        val current = _state.value
        val validationError = validateStep(current.currentStep, current)
        if (validationError != null) {
            _state.value = current.copy(errorMessage = validationError)
            return
        }
        val index = steps.indexOf(current.currentStep)
        if (index < steps.lastIndex) {
            _state.value = current.copy(currentStep = steps[index + 1], errorMessage = null)
        }
    }

    fun loadSavedForwards(): List<ForwardConfig> = deps.configRepository.loadForwards()

    fun saveAndApplyConfig() {
        val current = _state.value
        val input = current.input
        val forwards = deps.configRepository.loadForwards().filter { it.enabled }
        val validationError = validateStep(SetupStep.Review, current)
        if (validationError != null) {
            _state.value = current.copy(errorMessage = validationError, saveResult = null)
            return
        }

        val importedIdentity = if (current.importIdentityPath.isNotBlank()) {
            val imported = importPrivateIdentity(current.importIdentityPath)
            if (imported.isFailure) {
                _state.value = current.copy(
                    errorMessage = imported.exceptionOrNull()?.message ?: "Failed importing private identity",
                    saveResult = null,
                )
                return
            }
            imported.getOrNull()
        } else {
            runCatching {
                val bytes = deps.identityRepository.readEncryptedIdentity()
                val validated = deps.tunnelRepository.validatePrivateIdentity(bytes.decodeToString())
                require(validated.valid) { validated.message ?: "Stored private identity is invalid" }
                val peerId = validated.peer_id ?: throw IllegalArgumentException("Missing identity peer id")
                val publicIdentity = validated.canonical_public_identity
                    ?: deps.identityRepository.readPublicIdentity()
                Triple(bytes, publicIdentity, peerId)
            }.getOrNull()
        }
        val identityBytes = importedIdentity?.first
        if (identityBytes == null || identityBytes.isEmpty()) {
            repositoryError(current, "Missing encrypted identity")
            return
        }
        val identityPeerId = importedIdentity.third
        if (identityPeerId != input.localPeerId) {
            repositoryError(
                current,
                "Local peer ID must match private identity peer ID ($identityPeerId)",
            )
            return
        }
        if (current.importPublicIdentity.isNotBlank()) {
            val imported = importPublicIdentity(current.importPublicIdentity, input.remotePeerId)
            if (imported.isFailure) {
                repositoryError(current, imported.exceptionOrNull()?.message ?: "Failed importing public identity")
                return
            }
        }
        val candidate = deps.configRepository.renderOfferConfig(input, forwards)
        val result = validateCandidateConfig(candidate, identityBytes)
        if (!result.valid) {
            _state.value = current.copy(errorMessage = result.message ?: "Config validation failed", saveResult = null)
            return
        }
        deps.configRepository.writeConfigAtomically(candidate)
        deps.configRepository.saveSetupInput(input)
        runBlocking {
            val existing = deps.configRepository.preferences.first()
            deps.configRepository.savePreferences(
                existing.copy(
                    allowMetered = input.allowMetered,
                    resumeOnUnmetered = input.resumeOnUnmetered,
                ),
            )
        }
        _state.value = current.copy(
            localPublicIdentity = importedIdentity.second,
            identityPeerId = identityPeerId,
            errorMessage = null,
            saveResult = "Configuration saved",
        )
    }

    fun startTunnelFromReview() {
        saveAndApplyConfig()
        val latest = _state.value
        if (latest.errorMessage != null) {
            return
        }
        ContextCompat.startForegroundService(
            deps.context,
            Intent(deps.context, TunnelForegroundService::class.java)
                .setAction(TunnelForegroundService.ACTION_START_OFFER),
        )
        _state.value = latest.copy(saveResult = "Tunnel start requested")
    }

    private fun importPrivateIdentity(path: String): Result<Triple<ByteArray, String, String>> = runCatching {
        val privateIdentity = deps.identityRepository.readPrivateIdentityFile(path).getOrThrow()
        val validated = deps.tunnelRepository.validatePrivateIdentity(privateIdentity)
        require(validated.valid) { validated.message ?: "Invalid private identity" }
        val canonicalPrivate = validated.canonical_private_identity ?: privateIdentity
        val canonicalPublic = validated.canonical_public_identity
            ?: throw IllegalArgumentException("Missing canonical public identity")
        val peerId = validated.peer_id ?: throw IllegalArgumentException("Missing canonical peer id")
        deps.identityRepository.storeEncryptedIdentity(canonicalPrivate.toByteArray(), canonicalPublic)
        Triple(canonicalPrivate.toByteArray(), canonicalPublic, peerId)
    }

    private fun importPublicIdentity(line: String, expectedRemotePeerId: String): Result<String> = runCatching {
        val validated = deps.tunnelRepository.validatePublicIdentity(line)
        require(validated.valid) { validated.message ?: "Invalid public identity" }
        val peerId = validated.peer_id ?: throw IllegalArgumentException("Public identity missing peer ID")
        require(peerId == expectedRemotePeerId) {
            "Remote peer ID must match imported public identity peer ID ($peerId)"
        }
        deps.identityRepository.appendAuthorizedPublicIdentity(
            validated.canonical_public_identity ?: line.trim(),
        ).getOrThrow()
        peerId
    }

    private fun validateCandidateConfig(candidate: String, identityBytes: ByteArray): ValidationResult {
        val temp = File(deps.context.cacheDir, "config-candidate.toml")
        return runCatching {
            temp.parentFile?.mkdirs()
            temp.writeText(candidate)
            deps.tunnelRepository.validateConfigWithIdentity(temp.absolutePath, identityBytes)
        }.getOrElse { ValidationResult(false, it.message) }.also {
            temp.delete()
        }
    }

    private fun validateStep(step: SetupStep, state: SetupWizardState): String? {
        val input = state.input
        return when (step) {
            SetupStep.Mode -> null
            SetupStep.Identity -> {
                val hasStored = deps.identityRepository.hasEncryptedIdentity()
                if (!hasStored && state.importIdentityPath.isBlank() && state.localPublicIdentity.isBlank()) {
                    "Import or generate a private identity to continue"
                } else {
                    null
                }
            }
            SetupStep.Broker -> when {
                input.brokerHost.isBlank() -> "Broker host is required"
                input.brokerPort !in 1..65535 -> "Broker port must be between 1 and 65535"
                else -> null
            }
            SetupStep.Peer -> {
                if (input.remotePeerId.isBlank()) "Remote peer id is required"
                else if (state.importPublicIdentity.isBlank()) "Remote public identity is required"
                else {
                    val validated = deps.tunnelRepository.validatePublicIdentity(state.importPublicIdentity)
                    when {
                        !validated.valid -> validated.message ?: "Invalid remote public identity"
                        validated.peer_id != input.remotePeerId ->
                            "Remote peer ID must match imported public identity peer ID (${validated.peer_id})"
                        else -> null
                    }
                }
            }
            SetupStep.Forwards -> deps.configRepository.validateForwards(deps.configRepository.loadForwards())
                ?: if (deps.configRepository.loadForwards().none { it.enabled }) "Enable at least one forward" else null
            SetupStep.NetworkPolicy -> null
            SetupStep.Review -> {
                validateStep(SetupStep.Identity, state)
                    ?: validateStep(SetupStep.Broker, state)
                    ?: validateStep(SetupStep.Peer, state)
                    ?: validateStep(SetupStep.Forwards, state)
                    ?: state.identityPeerId?.let { identityPeerId ->
                        if (identityPeerId != input.localPeerId) {
                            "Local peer ID must match private identity peer ID ($identityPeerId)"
                        } else {
                            null
                        }
                    }
            }
        }
    }

    private fun repositoryError(current: SetupWizardState, message: String) {
        _state.value = current.copy(errorMessage = SensitiveDataRedactor.redactText(message), saveResult = null)
    }
}

class ForwardsViewModel(private val deps: AppDependencies) : ViewModel() {
    val status: StateFlow<TunnelStatus> = deps.tunnelRepository.status
    private val _forwards = MutableStateFlow(deps.configRepository.loadForwards())
    val forwards: StateFlow<List<ForwardConfig>> = _forwards.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun reload() {
        _forwards.value = deps.configRepository.loadForwards()
    }

    fun saveForward(forward: ForwardConfig) {
        val before = deps.configRepository.loadForwards()
        val result = deps.configRepository.upsertForward(forward)
        if (!result.valid) {
            _message.value = result.message ?: "Forward update failed"
            return
        }
        val sync = regenerateActiveConfig()
        if (!sync.valid) {
            deps.configRepository.saveForwards(before)
            reload()
            _message.value = sync.message ?: "Forward update failed"
            return
        }
        reload()
        _message.value = "Forward saved"
    }

    fun deleteForward(forwardId: String) {
        val before = deps.configRepository.loadForwards()
        deps.configRepository.deleteForward(forwardId)
        val sync = regenerateActiveConfig()
        if (!sync.valid) {
            deps.configRepository.saveForwards(before)
            reload()
            _message.value = sync.message ?: "Forward delete failed"
            return
        }
        reload()
        _message.value = "Forward deleted"
    }

    fun localhostUrl(forward: ForwardConfig): String = "http://${forward.localHost}:${forward.localPort}"

    fun testLocalPort(forward: ForwardConfig) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val resultMessage = runCatching {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress("127.0.0.1", forward.localPort), 1200)
                }
                "Local port test succeeded for 127.0.0.1:${forward.localPort}"
            }.getOrElse {
                "Local port test failed for 127.0.0.1:${forward.localPort}: ${it.message}"
            }
            _message.value = SensitiveDataRedactor.redactText(resultMessage)
        }
    }

    private fun regenerateActiveConfig(): ValidationResult {
        val input = deps.configRepository.loadSetupInput()
        val forwards = deps.configRepository.loadForwards().filter { it.enabled }
        val candidate = deps.configRepository.renderOfferConfig(input, forwards)
        val temp = File(deps.context.cacheDir, "config-forwards-candidate.toml")
        val identity = runCatching { deps.identityRepository.readEncryptedIdentity() }.getOrNull()
        return runCatching {
            temp.parentFile?.mkdirs()
            temp.writeText(candidate)
            val result = if (identity != null && identity.isNotEmpty()) {
                deps.tunnelRepository.validateConfigWithIdentity(temp.absolutePath, identity)
            } else {
                deps.tunnelRepository.validateConfig(temp.absolutePath)
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

    fun refresh(maxEvents: Int = 200) {
        _logs.value = deps.tunnelRepository.recentLogs(maxEvents)
    }

    fun setFilter(level: String) {
        _filter.value = level
    }

    fun filteredLogs(): List<LogEvent> {
        val selected = _filter.value
        return if (selected == "all") _logs.value else _logs.value.filter { it.level.equals(selected, ignoreCase = true) }
    }

    fun clearLogs() {
        _logs.value = emptyList()
    }

    fun exportDiagnostics(path: String, networkStatus: com.phillipchin.webrtctunnel.model.NetworkStatus) {
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

    fun exportDiagnosticsToUri(uri: Uri, networkStatus: com.phillipchin.webrtctunnel.model.NetworkStatus) {
        runCatching {
            val payload = deps.diagnosticsRepository.buildRedactedDiagnosticsPayload(
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
        val payload = deps.diagnosticsRepository.buildRedactedDiagnosticsPayload(
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

class SettingsViewModel(private val deps: AppDependencies) : ViewModel() {
    fun validateConfig(): ValidationResult = deps.tunnelRepository.validateConfig(deps.configRepository.configPath)
}

class NetworkPolicyViewModel(private val deps: AppDependencies) : ViewModel() {
    val networkStatus = combine(deps.networkPolicyManager.status, deps.configRepository.preferences) { _, prefs ->
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

    fun updateState(transform: (ImportExportState) -> ImportExportState) {
        _state.value = transform(_state.value).copy(resultMessage = null)
    }

    fun importConfig() {
        val path = _state.value.configImportPath.trim()
        runCatching {
            val source = java.io.File(path)
            require(source.exists()) { "Config file not found" }
            importConfigContent(source.readText())
        }.onSuccess {
            _state.value = _state.value.copy(resultMessage = "Config imported")
        }.onFailure {
            _state.value = _state.value.copy(resultMessage = it.message ?: "Config import failed")
        }
    }

    fun importConfigFromUri(uri: Uri) {
        runCatching {
            val candidate = deps.context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                ?: error("Unable to read config from selected URI")
            importConfigContent(candidate)
        }.onSuccess {
            _state.value = _state.value.copy(resultMessage = "Config imported")
        }.onFailure {
            _state.value = _state.value.copy(resultMessage = it.message ?: "Config import failed")
        }
    }

    fun importPrivateIdentity() {
        runCatching {
            val privateIdentity = deps.identityRepository
                .readPrivateIdentityFile(_state.value.privateIdentityImportPath.trim())
                .getOrThrow()
            importPrivateIdentityContent(privateIdentity)
        }.onSuccess {
            _state.value = _state.value.copy(resultMessage = "Private identity imported")
        }.onFailure {
            _state.value = _state.value.copy(resultMessage = it.message ?: "Private identity import failed")
        }
    }

    fun importPrivateIdentityFromUri(uri: Uri) {
        runCatching {
            val privateIdentity = deps.context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                ?: error("Unable to read private identity from selected URI")
            importPrivateIdentityContent(privateIdentity)
        }.onSuccess {
            _state.value = _state.value.copy(resultMessage = "Private identity imported")
        }.onFailure {
            _state.value = _state.value.copy(resultMessage = it.message ?: "Private identity import failed")
        }
    }

    fun importPublicIdentity() {
        runCatching {
            importPublicIdentityLine(_state.value.publicIdentityLine)
        }.onSuccess {
            _state.value = _state.value.copy(resultMessage = "Public identity imported")
        }.onFailure {
            _state.value = _state.value.copy(resultMessage = it.message ?: "Public identity import failed")
        }
    }

    fun importPublicIdentityFromUri(uri: Uri) {
        runCatching {
            val value = deps.context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                ?: error("Unable to read public identity from selected URI")
            importPublicIdentityLine(value)
        }.onSuccess {
            _state.value = _state.value.copy(resultMessage = "Public identity imported")
        }.onFailure {
            _state.value = _state.value.copy(resultMessage = it.message ?: "Public identity import failed")
        }
    }

    fun exportConfig(confirmSensitive: Boolean) {
        runCatching {
            require(confirmSensitive) { "Raw config export requires explicit confirmation" }
            val output = java.io.File(_state.value.configExportPath.trim())
            output.parentFile?.mkdirs()
            output.writeText(exportConfigText(confirmSensitive))
        }.onSuccess {
            _state.value = _state.value.copy(resultMessage = "Raw config exported")
        }.onFailure {
            _state.value = _state.value.copy(resultMessage = it.message ?: "Config export failed")
        }
    }

    fun exportConfigToUri(uri: Uri, confirmSensitive: Boolean) {
        runCatching {
            require(confirmSensitive) { "Raw config export requires explicit confirmation" }
            val payload = exportConfigText(confirmSensitive)
            deps.context.contentResolver.openOutputStream(uri, "wt")?.use { stream ->
                stream.write(payload.toByteArray())
            } ?: error("Unable to open destination URI")
        }.onSuccess {
            _state.value = _state.value.copy(resultMessage = "Raw config exported")
        }.onFailure {
            _state.value = _state.value.copy(resultMessage = it.message ?: "Config export failed")
        }
    }

    fun exportPublicIdentity() {
        deps.identityRepository.exportPublicIdentity(_state.value.publicIdentityExportPath.trim())
            .onSuccess { _state.value = _state.value.copy(resultMessage = "Public identity exported") }
            .onFailure { _state.value = _state.value.copy(resultMessage = it.message ?: "Public identity export failed") }
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

    fun exportPrivateIdentity(confirmRisk: Boolean) {
        val current = _state.value
        deps.identityRepository.exportPrivateIdentity(
            outputPath = current.privateIdentityExportPath.trim(),
            confirmRisk = confirmRisk,
        ).onSuccess {
            _state.value = _state.value.copy(resultMessage = "Private identity exported")
        }.onFailure {
            _state.value = _state.value.copy(resultMessage = it.message ?: "Private identity export failed")
        }
    }

    fun exportPrivateIdentityToUri(uri: Uri, confirmRisk: Boolean) {
        runCatching {
            require(confirmRisk) { "Private export requires explicit confirmation" }
            val payload = deps.identityRepository.readEncryptedIdentity()
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

    private fun importConfigContent(candidate: String) {
        val temp = File(deps.context.cacheDir, "config-import-candidate.toml")
        temp.parentFile?.mkdirs()
        temp.writeText(candidate)
        val identity = runCatching { deps.identityRepository.readEncryptedIdentity() }.getOrNull()
        val validation = if (identity != null && identity.isNotEmpty()) {
            deps.tunnelRepository.validateConfigWithIdentity(temp.absolutePath, identity)
        } else {
            deps.tunnelRepository.validateConfig(temp.absolutePath)
        }
        require(validation.valid) { validation.message ?: "Config validation failed" }
        deps.configRepository.writeConfigAtomically(candidate)
        temp.delete()
    }

    private fun importPrivateIdentityContent(privateIdentity: String) {
        val validated = deps.tunnelRepository.validatePrivateIdentity(privateIdentity)
        require(validated.valid) { validated.message ?: "Invalid private identity" }
        deps.identityRepository.storeEncryptedIdentity(
            (validated.canonical_private_identity ?: privateIdentity).toByteArray(),
            validated.canonical_public_identity ?: throw IllegalArgumentException("Missing canonical public identity"),
        )
    }

    private fun importPublicIdentityLine(line: String) {
        val validated = deps.tunnelRepository.validatePublicIdentity(line)
        require(validated.valid) { validated.message ?: "Invalid public identity" }
        deps.identityRepository.appendAuthorizedPublicIdentity(
            validated.canonical_public_identity ?: line.trim(),
        ).getOrThrow()
    }

    private fun exportConfigText(confirmSensitive: Boolean): String {
        require(confirmSensitive) { "Raw config export requires explicit confirmation" }
        return deps.configRepository.readConfig()
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
