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

private const val MAX_PORT = 65535
private const val BROKER_PROBE_TIMEOUT_MS = 2_500
private const val LOCAL_PORT_TEST_TIMEOUT_MS = 1200

// Carries a save-flow failure plus whether its message is safe to show verbatim.
// Identity/persist failures are redacted; validation messages are shown as-is.
private class SaveError(
    message: String,
    val redact: Boolean,
) : Exception(message)

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
    private val _configuredForwards = MutableStateFlow(deps.configRepository.loadForwards())
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
        _configuredForwards.value = deps.configRepository.loadForwards()
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

    init {
        loadStoredSetupInput()
        loadStoredIdentity()
        refreshForwards()
    }

    fun validateConfig(): ValidationResult = deps.tunnelRepository.validateConfig(deps.configRepository.configPath)

    fun validateForwardDraft(
        draft: ForwardConfig,
        currentForwards: List<ForwardConfig>,
    ): String? {
        val updated =
            currentForwards.map { if (it.id == draft.id) draft else it }.let { candidates ->
                if (candidates.none { it.id == draft.id }) candidates + draft else candidates
            }
        return deps.configRepository.validateForwards(updated)
    }

    private fun SetupWizardState.withCanAdvance(forwards: List<ForwardConfig>): SetupWizardState {
        return copy(canAdvance = canAdvance(this, forwards))
    }

    private fun canAdvance(
        state: SetupWizardState,
        forwards: List<ForwardConfig>,
    ): Boolean {
        return when (state.currentStep) {
            SetupStep.Mode -> true
            SetupStep.Identity -> state.localPublicIdentity.isNotBlank() || state.importIdentityPath.isNotBlank()
            SetupStep.Broker -> state.input.brokerHost.isNotBlank() && state.input.brokerPort in 1..MAX_PORT
            SetupStep.Peer -> state.input.remotePeerId.isNotBlank() && state.importPublicIdentity.isNotBlank()
            SetupStep.Forwards -> forwards.isNotEmpty() && deps.configRepository.validateForwards(forwards) == null
            SetupStep.NetworkPolicy -> true
            SetupStep.Review -> {
                state.input.brokerHost.isNotBlank() &&
                    state.input.brokerPort in 1..MAX_PORT &&
                    state.input.remotePeerId.isNotBlank() &&
                    state.importPublicIdentity.isNotBlank() &&
                    forwards.isNotEmpty() &&
                    deps.configRepository.validateForwards(forwards) == null
            }
        }
    }

    private fun updateState(transform: (SetupWizardState) -> SetupWizardState) {
        val updated = transform(_state.value)
        _state.value = updated.copy(canAdvance = canAdvance(updated, _forwards.value))
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

    fun importIdentityFromPath() {
        val current = _state.value
        val trimmed = current.importIdentityPath.trim()
        if (trimmed.isBlank()) {
            _state.value =
                current
                    .copy(errorMessage = "Choose an identity file path to import")
                    .withCanAdvance(_forwards.value)
            return
        }
        val resolved =
            runCatching {
                val privateIdentity = deps.identityRepository.readPrivateIdentityFile(trimmed).getOrThrow()
                val validated = deps.tunnelRepository.validatePrivateIdentity(privateIdentity)
                require(validated.valid) { validated.message ?: "Invalid private identity" }
                val peerId = validated.peerId ?: throw IllegalArgumentException("Missing identity peer id")
                val canonicalPublic = validated.canonicalPublicIdentity ?: ""
                peerId to canonicalPublic
            }
        resolved.onSuccess { (peerId, canonicalPublic) ->
            _state.value =
                current.copy(
                    importIdentityPath = trimmed,
                    identityPeerId = peerId,
                    localPublicIdentity = canonicalPublic,
                    input = current.input.copy(localPeerId = peerId),
                    errorMessage = null,
                    saveResult = "Identity imported",
                ).withCanAdvance(_forwards.value)
        }.onFailure {
            _state.value =
                current.copy(
                    identityPeerId = null,
                    localPublicIdentity = "",
                    errorMessage = it.message ?: "Invalid private identity file",
                    saveResult = null,
                ).withCanAdvance(_forwards.value)
        }
    }

    fun importIdentityFromUri(uri: Uri) {
        val current = _state.value
        val resolved =
            runCatching {
                val privateIdentity =
                    deps.context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                        ?: error("Unable to read private identity from selected URI")
                val validated = deps.tunnelRepository.validatePrivateIdentity(privateIdentity)
                require(validated.valid) { validated.message ?: "Invalid private identity" }
                val canonicalPrivate = validated.canonicalPrivateIdentity ?: privateIdentity
                val canonicalPublic = validated.canonicalPublicIdentity ?: ""
                val peerId = validated.peerId ?: throw IllegalArgumentException("Missing identity peer id")
                deps.identityRepository.storeEncryptedIdentity(canonicalPrivate.toByteArray(), canonicalPublic)
                Triple(peerId, canonicalPublic, canonicalPrivate)
            }
        resolved.onSuccess { (peerId, canonicalPublic, _) ->
            _state.value =
                current.copy(
                    identityPeerId = peerId,
                    localPublicIdentity = canonicalPublic,
                    input = current.input.copy(localPeerId = peerId),
                    importIdentityPath = "",
                    errorMessage = null,
                    saveResult = "Identity imported",
                ).withCanAdvance(_forwards.value)
        }.onFailure {
            _state.value =
                current.copy(
                    errorMessage = it.message ?: "Invalid private identity file",
                    saveResult = null,
                ).withCanAdvance(_forwards.value)
        }
    }

    fun validateRemotePublicIdentity() {
        val current = _state.value
        val value = current.importPublicIdentity.trim()
        if (value.isBlank()) {
            _state.value =
                current
                    .copy(remoteIdentityPeerId = null, errorMessage = "Remote public identity is required")
                    .withCanAdvance(_forwards.value)
            return
        }

        val validated = deps.tunnelRepository.validatePublicIdentity(value)
        val updated =
            when {
                !validated.valid ->
                    current.copy(
                        remoteIdentityPeerId = null,
                        errorMessage = validated.message ?: "Invalid remote public identity",
                    )
                validated.peerId == current.input.localPeerId ->
                    current.copy(
                        remoteIdentityPeerId = null,
                        errorMessage = "Remote public identity cannot match local identity",
                    )
                else ->
                    current.copy(
                        importPublicIdentity = validated.canonicalPublicIdentity ?: value,
                        remoteIdentityPeerId = validated.peerId,
                        errorMessage = null,
                        saveResult = "Remote public identity validated",
                    )
            }
        _state.value = updated.withCanAdvance(_forwards.value)
    }

    fun importPublicIdentityFromUri(uri: Uri) {
        runCatching {
            deps.context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                ?: error("Unable to read remote public identity from selected URI")
        }.onSuccess { text ->
            setImportPublicIdentity(text)
            validateRemotePublicIdentity()
        }.onFailure {
            _state.value =
                _state.value
                    .copy(errorMessage = it.message ?: "Failed importing remote public identity")
                    .withCanAdvance(_forwards.value)
        }
    }

    fun generateIdentity() {
        val current = _state.value
        val generated = deps.tunnelRepository.generateIdentity(current.input.localPeerId)
        if (!generated.valid) {
            _state.value =
                current
                    .copy(errorMessage = generated.message ?: "Identity generation failed")
                    .withCanAdvance(_forwards.value)
            return
        }
        val privateIdentity = generated.canonicalPrivateIdentity
        val publicIdentity = generated.canonicalPublicIdentity
        if (privateIdentity.isNullOrBlank() || publicIdentity.isNullOrBlank()) {
            _state.value =
                current
                    .copy(errorMessage = "Identity generation returned incomplete data")
                    .withCanAdvance(_forwards.value)
            return
        }
        deps.identityRepository.storeEncryptedIdentity(privateIdentity.toByteArray(), publicIdentity)
        val peerId = generated.peerId ?: current.input.localPeerId
        _state.value =
            current.copy(
                input = current.input.copy(localPeerId = peerId),
                localPublicIdentity = publicIdentity,
                identityPeerId = peerId,
                errorMessage = null,
                saveResult = "Identity generated",
            ).withCanAdvance(_forwards.value)
    }

    fun goBack() {
        val current = _state.value.currentStep
        val index = steps.indexOf(current)
        if (index > 0) {
            _state.value =
                _state.value
                    .copy(currentStep = steps[index - 1], errorMessage = null)
                    .withCanAdvance(_forwards.value)
        }
    }

    fun cancel() {
        _state.value = SetupWizardState()
        refreshForwards()
    }

    fun goNext() {
        val current = _state.value
        val validationError = validateStep(current.currentStep, current)
        if (validationError != null) {
            _state.value =
                current
                    .copy(errorMessage = validationError)
                    .withCanAdvance(_forwards.value)
            return
        }
        val index = steps.indexOf(current.currentStep)
        if (index < steps.lastIndex) {
            _state.value =
                current
                    .copy(currentStep = steps[index + 1], errorMessage = null)
                    .withCanAdvance(_forwards.value)
        }
    }

    fun canAdvanceFromCurrentStep(): Boolean {
        return _state.value.canAdvance
    }

    fun loadSavedForwards(): List<ForwardConfig> = deps.configRepository.loadForwards()

    fun refreshForwards() {
        _forwards.value = deps.configRepository.loadForwards()
        _state.value = _state.value.withCanAdvance(_forwards.value)
    }

    fun upsertForward(forward: ForwardConfig): ValidationResult {
        val result = deps.configRepository.upsertForward(forward)
        if (!result.valid) {
            _state.value =
                _state.value
                    .copy(errorMessage = result.message ?: "Forward update failed")
                    .withCanAdvance(_forwards.value)
            return result
        }
        refreshForwards()
        _state.value =
            _state.value
                .copy(errorMessage = null, saveResult = "Forward saved")
                .withCanAdvance(_forwards.value)
        return result
    }

    fun deleteForward(forwardId: String) {
        deps.configRepository.deleteForward(forwardId)
        refreshForwards()
        _state.value =
            _state.value
                .copy(errorMessage = null, saveResult = "Forward deleted")
                .withCanAdvance(_forwards.value)
    }

    fun testBrokerConnection() {
        val current = _state.value
        val host = current.input.brokerHost.trim()
        val port = current.input.brokerPort
        if (host.isBlank() || port !in 1..MAX_PORT) {
            _state.value =
                current
                    .copy(brokerTestMessage = "Broker host/port is invalid")
                    .withCanAdvance(_forwards.value)
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val message =
                runCatching {
                    Socket().use { socket ->
                        socket.connect(InetSocketAddress(host, port), BROKER_PROBE_TIMEOUT_MS)
                    }
                    "TCP connection to $host:$port succeeded. Full MQTT/TLS auth is confirmed when the tunnel connects."
                }.getOrElse {
                    "TCP connection to $host:$port failed: ${it.message ?: "unknown error"}"
                }
            _state.value = _state.value.copy(brokerTestMessage = SensitiveDataRedactor.redactText(message))
        }
    }

    fun saveAndApplyConfig() {
        viewModelScope.launch {
            saveAndApplyConfigInternal()
        }
    }

    private suspend fun saveAndApplyConfigInternal(): Boolean {
        val current = _state.value
        val input = current.input
        val forwards = _forwards.value.filter { it.enabled }
        val outcome =
            runCatching {
                validateStep(SetupStep.Review, current)?.let { saveError(it, redact = false) }
                val identity = resolveSaveIdentity(current)
                if (identity.third != input.localPeerId) {
                    saveError(
                        "Local peer ID must match private identity peer ID (${identity.third})",
                        redact = true,
                    )
                }
                if (current.importPublicIdentity.isNotBlank()) {
                    importPublicIdentity(current.importPublicIdentity, input.remotePeerId)
                        .getOrElse { saveError(it.message ?: "Failed importing public identity", redact = true) }
                }
                val candidate = deps.configRepository.renderOfferConfig(input, forwards)
                val validation = withContext(Dispatchers.IO) { validateCandidateConfig(candidate, identity.first) }
                if (!validation.valid) {
                    saveError(validation.message ?: "Config validation failed", redact = false)
                }
                persistConfig(candidate, input)
                identity
            }
        return outcome.fold(
            onSuccess = { identity ->
                _state.value =
                    current.copy(
                        localPublicIdentity = identity.second,
                        identityPeerId = identity.third,
                        errorMessage = null,
                        saveResult = "Configuration saved",
                    ).withCanAdvance(_forwards.value)
                true
            },
            onFailure = { error ->
                // Preserve the per-step redaction map: SaveError carries whether its
                // message is safe to show verbatim; anything else is redacted.
                val message = error.message ?: "Failed saving configuration"
                val text =
                    if (error is SaveError && !error.redact) {
                        message
                    } else {
                        SensitiveDataRedactor.redactText(
                            message,
                        )
                    }
                _state.value = current.copy(errorMessage = text, saveResult = null).withCanAdvance(_forwards.value)
                false
            },
        )
    }

    // Resolves the private identity for save: imported from a file (errors shown
    // verbatim, as before) or the stored encrypted identity (absence redacted).
    private suspend fun resolveSaveIdentity(current: SetupWizardState): Triple<ByteArray, String, String> {
        val resolved =
            if (current.importIdentityPath.isNotBlank()) {
                withContext(Dispatchers.IO) { importPrivateIdentity(current.importIdentityPath) }
                    .getOrElse { saveError(it.message ?: "Failed importing private identity", redact = false) }
            } else {
                resolveStoredIdentity()
            }
        if (resolved == null || resolved.first.isEmpty()) {
            saveError("Missing encrypted identity", redact = true)
        }
        return resolved
    }

    private suspend fun resolveStoredIdentity(): Triple<ByteArray, String, String>? =
        withContext(Dispatchers.IO) {
            runCatching {
                val bytes = deps.identityRepository.readPrivateIdentityPlaintext()
                val validated = deps.tunnelRepository.validatePrivateIdentity(bytes.decodeToString())
                require(validated.valid) { validated.message ?: "Stored private identity is invalid" }
                val peerId = validated.peerId ?: throw IllegalArgumentException("Missing identity peer id")
                val publicIdentity =
                    validated.canonicalPublicIdentity ?: deps.identityRepository.readPublicIdentity()
                Triple(bytes, publicIdentity, peerId)
            }.getOrNull()
        }

    // Persists config/input/preferences off the main thread. Throws SaveError
    // (redacted) on failure.
    private suspend fun persistConfig(
        candidate: String,
        input: SetupConfigInput,
    ) {
        runCatching {
            withContext(Dispatchers.IO) {
                deps.configRepository.writeConfigAtomically(candidate)
                deps.configRepository.saveSetupInput(input)
                val existing = loadPreferences()
                persistPreferences(
                    existing.copy(
                        allowMetered = input.allowMetered,
                        resumeOnUnmetered = input.resumeOnUnmetered,
                    ),
                )
            }
        }.getOrElse { saveError(it.message ?: "Failed saving configuration", redact = true) }
    }

    fun startTunnelFromReview(onSuccess: (() -> Unit)? = null) {
        viewModelScope.launch {
            val saved = saveAndApplyConfigInternal()
            if (!saved) {
                return@launch
            }
            ContextCompat.startForegroundService(
                deps.context,
                Intent(deps.context, TunnelForegroundService::class.java)
                    .setAction(TunnelForegroundService.ACTION_START_OFFER),
            )
            _state.value =
                _state.value
                    .copy(saveResult = "Tunnel start requested", errorMessage = null)
                    .withCanAdvance(_forwards.value)
            onSuccess?.invoke()
        }
    }

    private fun importPrivateIdentity(path: String): Result<Triple<ByteArray, String, String>> =
        runCatching {
            val privateIdentity = deps.identityRepository.readPrivateIdentityFile(path).getOrThrow()
            val validated = deps.tunnelRepository.validatePrivateIdentity(privateIdentity)
            require(validated.valid) { validated.message ?: "Invalid private identity" }
            val canonicalPrivate = validated.canonicalPrivateIdentity ?: privateIdentity
            val canonicalPublic =
                validated.canonicalPublicIdentity
                    ?: throw IllegalArgumentException("Missing canonical public identity")
            val peerId = validated.peerId ?: throw IllegalArgumentException("Missing canonical peer id")
            deps.identityRepository.storeEncryptedIdentity(canonicalPrivate.toByteArray(), canonicalPublic)
            Triple(canonicalPrivate.toByteArray(), canonicalPublic, peerId)
        }

    private fun importPublicIdentity(
        line: String,
        expectedRemotePeerId: String,
    ): Result<String> =
        runCatching {
            val validated = deps.tunnelRepository.validatePublicIdentity(line)
            require(validated.valid) { validated.message ?: "Invalid public identity" }
            val peerId = validated.peerId ?: throw IllegalArgumentException("Public identity missing peer ID")
            require(peerId == expectedRemotePeerId) {
                "Remote peer ID must match imported public identity peer ID ($peerId)"
            }
            deps.identityRepository.appendAuthorizedPublicIdentity(
                validated.canonicalPublicIdentity ?: line.trim(),
            ).getOrThrow()
            peerId
        }

    private fun validateCandidateConfig(
        candidate: String,
        identityBytes: ByteArray,
    ): ValidationResult {
        val temp = File(deps.context.cacheDir, "config-candidate.toml")
        return runCatching {
            temp.parentFile?.mkdirs()
            temp.writeText(candidate)
            deps.tunnelRepository.validateConfigWithIdentity(temp.absolutePath, identityBytes)
        }.getOrElse { ValidationResult(false, it.message) }.also {
            temp.delete()
        }
    }

    private fun validateStep(
        step: SetupStep,
        state: SetupWizardState,
    ): String? {
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
            SetupStep.Broker ->
                when {
                    input.brokerHost.isBlank() -> "Broker host is required"
                    input.brokerPort !in 1..MAX_PORT -> "Broker port must be between 1 and 65535"
                    else -> null
                }
            SetupStep.Peer -> {
                if (input.remotePeerId.isBlank()) {
                    "Remote peer id is required"
                } else if (state.importPublicIdentity.isBlank()) {
                    "Remote public identity is required"
                } else {
                    val validated = deps.tunnelRepository.validatePublicIdentity(state.importPublicIdentity)
                    when {
                        !validated.valid -> validated.message ?: "Invalid remote public identity"
                        validated.peerId == input.localPeerId -> "Remote identity cannot be the same as local identity"
                        validated.peerId != input.remotePeerId ->
                            "Remote peer ID must match imported public identity peer ID (${validated.peerId})"
                        else -> null
                    }
                }
            }
            SetupStep.Forwards ->
                deps.configRepository.validateForwards(deps.configRepository.loadForwards())
                    ?: if (deps.configRepository.loadForwards().none { it.enabled }) {
                        "Enable at least one forward"
                    } else {
                        null
                    }
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

    // Single throw site for save-flow failures, so callers (and detekt's ThrowsCount)
    // see a function call rather than scattered throw statements.
    private fun saveError(
        message: String,
        redact: Boolean,
    ): Nothing = throw SaveError(message, redact)

    private fun loadStoredIdentity() {
        val publicIdentity = deps.identityRepository.readPublicIdentity()
        if (publicIdentity.isNotBlank()) {
            _state.value =
                _state.value
                    .copy(localPublicIdentity = publicIdentity)
                    .withCanAdvance(_forwards.value)
        }
    }

    private fun loadStoredSetupInput() {
        val saved = runCatching { deps.configRepository.loadSetupInput() }.getOrNull() ?: return
        if (saved.brokerHost.isNotBlank() || saved.remotePeerId.isNotBlank()) {
            _state.value =
                _state.value
                    .copy(input = saved)
                    .withCanAdvance(_forwards.value)
        }
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

    fun validateForwardDraft(
        draft: ForwardConfig,
        currentForwards: List<ForwardConfig>,
    ): String? {
        val updated =
            currentForwards.map { if (it.id == draft.id) draft else it }.let { candidates ->
                if (candidates.none { it.id == draft.id }) candidates + draft else candidates
            }
        return deps.configRepository.validateForwards(updated)
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
        val forwards = deps.configRepository.loadForwards().filter { it.enabled }
        val candidate = deps.configRepository.renderOfferConfig(input, forwards)
        val temp = File(deps.context.cacheDir, "config-forwards-candidate.toml")
        val identity = runCatching { deps.identityRepository.readPrivateIdentityPlaintext() }.getOrNull()
        return runCatching {
            temp.parentFile?.mkdirs()
            temp.writeText(candidate)
            val result =
                if (identity != null && identity.isNotEmpty()) {
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

    fun validateConfig(): ValidationResult = deps.tunnelRepository.validateConfig(deps.configRepository.configPath)

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
            deps.configRepository.saveForwards(emptyList())
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
            val candidate =
                deps.context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
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
            val privateIdentity =
                deps.identityRepository
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
            val privateIdentity =
                deps.context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
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
            val value =
                deps.context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
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

    fun exportConfigToUri(
        uri: Uri,
        confirmSensitive: Boolean,
    ) {
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
            .onFailure {
                _state.value = _state.value.copy(resultMessage = it.message ?: "Public identity export failed")
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

    private fun importConfigContent(candidate: String) {
        val temp = File(deps.context.cacheDir, "config-import-candidate.toml")
        temp.parentFile?.mkdirs()
        try {
            temp.writeText(candidate)
            val identity = runCatching { deps.identityRepository.readPrivateIdentityPlaintext() }.getOrNull()
            val validation =
                if (identity != null && identity.isNotEmpty()) {
                    deps.tunnelRepository.validateConfigWithIdentity(temp.absolutePath, identity)
                } else {
                    deps.tunnelRepository.validateConfig(temp.absolutePath)
                }
            require(validation.valid) { validation.message ?: "Config validation failed" }
            deps.configRepository.writeConfigAtomically(candidate)
        } finally {
            temp.delete()
        }
    }

    private fun importPrivateIdentityContent(privateIdentity: String) {
        val validated = deps.tunnelRepository.validatePrivateIdentity(privateIdentity)
        require(validated.valid) { validated.message ?: "Invalid private identity" }
        deps.identityRepository.storeEncryptedIdentity(
            (validated.canonicalPrivateIdentity ?: privateIdentity).toByteArray(),
            validated.canonicalPublicIdentity ?: throw IllegalArgumentException("Missing canonical public identity"),
        )
    }

    private fun importPublicIdentityLine(line: String) {
        val validated = deps.tunnelRepository.validatePublicIdentity(line)
        require(validated.valid) { validated.message ?: "Invalid public identity" }
        deps.identityRepository.appendAuthorizedPublicIdentity(
            validated.canonicalPublicIdentity ?: line.trim(),
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
