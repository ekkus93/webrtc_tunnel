package com.phillipchin.webrtctunnel.viewmodel

import android.content.Intent
import androidx.core.content.ContextCompat
import com.phillipchin.webrtctunnel.TunnelForegroundService
import com.phillipchin.webrtctunnel.data.AppDependencies
import com.phillipchin.webrtctunnel.data.SensitiveDataRedactor
import com.phillipchin.webrtctunnel.model.AndroidAppPreferences
import com.phillipchin.webrtctunnel.model.ForwardConfig
import com.phillipchin.webrtctunnel.model.SetupConfigInput
import com.phillipchin.webrtctunnel.model.ValidationResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket

private const val BROKER_PROBE_TIMEOUT_MS = 2_500

// Carries a save-flow failure plus whether its message is safe to show verbatim.
private class SaveError(
    message: String,
    val redact: Boolean,
) : Exception(message)

/**
 * Read/write access to the shared wizard state, so controllers split out of
 * SetupViewModel can mutate it without each holding the MutableStateFlows.
 */
class WizardStateAccess(
    val state: () -> SetupWizardState,
    val forwards: () -> List<ForwardConfig>,
    val applyState: (SetupWizardState) -> Unit,
    val setForwards: (List<ForwardConfig>) -> Unit,
)

class SetupSaveController(
    private val deps: AppDependencies,
    private val scope: CoroutineScope,
    private val loadPreferences: suspend () -> AndroidAppPreferences,
    private val persistPreferences: suspend (AndroidAppPreferences) -> Unit,
    private val access: WizardStateAccess,
    private val ioDispatcher: CoroutineDispatcher = deps.dispatchers.io,
) {
    fun testBrokerConnection() {
        val current = access.state()
        val host = current.input.brokerHost.trim()
        val port = current.input.brokerPort
        if (host.isBlank() || port !in 1..MAX_PORT) {
            access.applyState(current.copy(brokerTestMessage = "Broker host/port is invalid"))
            return
        }
        scope.launch(ioDispatcher) {
            val message =
                runCatching {
                    Socket().use { socket ->
                        socket.connect(InetSocketAddress(host, port), BROKER_PROBE_TIMEOUT_MS)
                    }
                    "TCP connection to $host:$port succeeded. Full MQTT/TLS auth is confirmed when the tunnel connects."
                }.getOrElse {
                    "TCP connection to $host:$port failed: ${it.message ?: "unknown error"}"
                }
            access.applyState(access.state().copy(brokerTestMessage = SensitiveDataRedactor.redactText(message)))
        }
    }

    fun saveAndApplyConfig() {
        scope.launch { saveAndApplyConfigInternal() }
    }

    fun startTunnelFromReview(onSuccess: (() -> Unit)? = null) {
        scope.launch {
            val saved = saveAndApplyConfigInternal()
            if (!saved) {
                return@launch
            }
            ContextCompat.startForegroundService(
                deps.context,
                Intent(deps.context, TunnelForegroundService::class.java)
                    .setAction(TunnelForegroundService.ACTION_START_OFFER),
            )
            access.applyState(access.state().copy(saveResult = "Tunnel start requested", errorMessage = null))
            onSuccess?.invoke()
        }
    }

    private suspend fun saveAndApplyConfigInternal(): Boolean {
        val current = access.state()
        val input = current.input
        val enabledForwards = access.forwards().filter { it.enabled }
        val outcome =
            runCatching {
                validateStep(deps, SetupStep.Review, current)?.let { saveError(it, redact = false) }
                val identity = resolveSaveIdentity(current)
                if (identity.third != input.localPeerId) {
                    saveError(
                        "Local peer ID must match private identity peer ID (${identity.third})",
                        redact = true,
                    )
                }
                if (current.importPublicIdentity.isNotBlank()) {
                    importPublicIdentity(deps, current.importPublicIdentity, input.remotePeerId)
                        .getOrElse { saveError(it.message ?: "Failed importing public identity", redact = true) }
                }
                val candidate = deps.configRepository.renderOfferConfig(input, enabledForwards)
                val validation =
                    withContext(ioDispatcher) { validateCandidateConfig(deps, candidate, identity.first) }
                if (!validation.valid) {
                    saveError(validation.message ?: "Config validation failed", redact = false)
                }
                persistConfig(candidate, input)
                identity
            }
        return outcome.fold(
            onSuccess = { identity ->
                access.applyState(
                    current.copy(
                        localPublicIdentity = identity.second,
                        identityPeerId = identity.third,
                        errorMessage = null,
                        saveResult = "Configuration saved",
                    ),
                )
                true
            },
            onFailure = { error ->
                val message = error.message ?: "Failed saving configuration"
                val text =
                    if (error is SaveError && !error.redact) message else SensitiveDataRedactor.redactText(message)
                access.applyState(current.copy(errorMessage = text, saveResult = null))
                false
            },
        )
    }

    private suspend fun resolveSaveIdentity(current: SetupWizardState): Triple<ByteArray, String, String> {
        val resolved =
            if (current.importIdentityPath.isNotBlank()) {
                withContext(ioDispatcher) { importPrivateIdentity(deps, current.importIdentityPath) }
                    .getOrElse { saveError(it.message ?: "Failed importing private identity", redact = false) }
            } else {
                resolveStoredIdentity(deps, ioDispatcher)
            }
        if (resolved == null || resolved.first.isEmpty()) {
            saveError("Missing encrypted identity", redact = true)
        }
        return resolved
    }

    private suspend fun persistConfig(
        candidate: String,
        input: SetupConfigInput,
    ) {
        runCatching {
            withContext(ioDispatcher) {
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
}

private fun saveError(
    message: String,
    redact: Boolean,
): Nothing = throw SaveError(message, redact)

private suspend fun resolveStoredIdentity(
    deps: AppDependencies,
    dispatcher: CoroutineDispatcher = deps.dispatchers.io,
): Triple<ByteArray, String, String>? =
    withContext(dispatcher) {
        runCatching {
            val bytes = deps.identityRepository.readPrivateIdentityPlaintext()
            val validated = deps.identityValidation.validatePrivateIdentity(bytes.decodeToString())
            require(validated.valid) { validated.message ?: "Stored private identity is invalid" }
            val peerId = validated.peerId ?: throw IllegalArgumentException("Missing identity peer id")
            val publicIdentity =
                validated.canonicalPublicIdentity ?: deps.identityRepository.readPublicIdentity()
            Triple(bytes, publicIdentity, peerId)
        }.getOrNull()
    }

private fun importPrivateIdentity(
    deps: AppDependencies,
    path: String,
): Result<Triple<ByteArray, String, String>> =
    runCatching {
        val privateIdentity = deps.identityRepository.readPrivateIdentityFile(path).getOrThrow()
        val validated = deps.identityValidation.validatePrivateIdentity(privateIdentity)
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
    deps: AppDependencies,
    line: String,
    expectedRemotePeerId: String,
): Result<String> =
    runCatching {
        val validated = deps.identityValidation.validatePublicIdentity(line)
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
    deps: AppDependencies,
    candidate: String,
    identityBytes: ByteArray,
): ValidationResult {
    val temp = File(deps.context.cacheDir, "config-candidate.toml")
    return runCatching {
        temp.parentFile?.mkdirs()
        temp.writeText(candidate)
        deps.identityValidation.validateConfigWithIdentity(temp.absolutePath, identityBytes)
    }.getOrElse { ValidationResult(false, it.message) }.also {
        temp.delete()
    }
}
