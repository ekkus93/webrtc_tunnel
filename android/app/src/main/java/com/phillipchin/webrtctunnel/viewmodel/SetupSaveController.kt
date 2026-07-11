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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
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
    private val persistPreferences: suspend (AndroidAppPreferences) -> Result<Unit>,
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
                try {
                    if (identity.peerId != input.localPeerId) {
                        saveError(
                            "Local peer ID must match private identity peer ID (${identity.peerId})",
                            redact = true,
                        )
                    }
                    if (current.importPublicIdentity.isNotBlank()) {
                        importPublicIdentity(deps, current.importPublicIdentity, input.remotePeerId)
                            .getOrElse { saveError(it.message ?: "Failed importing public identity", redact = true) }
                    }
                    val prefs = deps.configRepository.preferences.first()
                    val candidate =
                        deps.configRepository.renderOfferConfig(
                            input,
                            enabledForwards,
                            prefs.debugLogsEnabled,
                            prefs.androidIceMode,
                        )
                    val validation =
                        withContext(ioDispatcher) { validateCandidateConfig(deps, candidate, identity.privateIdentity) }
                    if (!validation.valid) {
                        saveError(validation.message ?: "Config validation failed", redact = false)
                    }
                    persistConfig(candidate, input)
                    identity
                } finally {
                    // Wipe the plaintext identity buffer; only the public id/peer id are
                    // used after this point.
                    identity.privateIdentity.fill(0)
                }
            }
        return outcome.fold(
            onSuccess = { identity ->
                access.applyState(
                    current.copy(
                        localPublicIdentity = identity.publicIdentity,
                        identityPeerId = identity.peerId,
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

    private suspend fun resolveSaveIdentity(current: SetupWizardState): ResolvedIdentity {
        val resolved =
            if (current.importIdentityPath.isNotBlank()) {
                withContext(ioDispatcher) { importPrivateIdentity(deps, current.importIdentityPath) }
                    .getOrElse { saveError(it.message ?: "Failed importing private identity", redact = false) }
            } else if (!deps.identityRepository.hasEncryptedIdentity()) {
                // Absence and present-but-unreadable are different states (P1-001/P1-007): only
                // absence may report "missing" — a present identity that fails to load/validate
                // must say so, not tell the user their identity vanished.
                saveError("Missing encrypted identity", redact = true)
            } else {
                resolveStoredIdentity(deps, ioDispatcher)
                    ?: saveError("Stored private key exists but could not be loaded or is invalid", redact = true)
            }
        if (resolved.privateIdentity.isEmpty()) {
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
                ).getOrElse { error ->
                    throw PreferencePersistenceException(
                        error.message
                            ?: "Failed to save preferences",
                        error,
                    )
                }
            }
        }.getOrElse { saveError(it.message ?: "Failed saving configuration", redact = true) }
    }
}

/**
 * Thrown when preference persistence fails during setup save.
 * This allows the caller to distinguish between config write failures and preference write failures.
 */
private class PreferencePersistenceException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/**
 * P0-002: Named type for resolved identity components.
 * Replaces raw Triple for safer ownership semantics.
 */
private data class ResolvedIdentity(
    val privateIdentity: ByteArray,
    val publicIdentity: String,
    val peerId: String,
)

private fun saveError(
    message: String,
    redact: Boolean,
): Nothing = throw SaveError(message, redact)

private suspend fun resolveStoredIdentity(
    deps: AppDependencies,
    dispatcher: CoroutineDispatcher,
): ResolvedIdentity? =
    withContext(dispatcher) {
        var bytes: ByteArray? = null
        var transferred = false
        try {
            bytes = deps.identityRepository.readPrivateIdentityPlaintext()
            val validated = deps.identityValidation.validatePrivateIdentity(bytes.decodeToString())
            require(validated.valid) { validated.message ?: "Stored private identity is invalid" }
            val peerId = validated.peerId ?: throw IllegalArgumentException("Missing identity peer id")
            val publicIdentity =
                validated.canonicalPublicIdentity ?: deps.identityRepository.readPublicIdentity()
            transferred = true
            ResolvedIdentity(bytes, publicIdentity, peerId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            null
        } finally {
            if (!transferred) {
                bytes?.fill(0)
            }
        }
    }

private fun importPrivateIdentity(
    deps: AppDependencies,
    path: String,
): Result<ResolvedIdentity> =
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
        ResolvedIdentity(canonicalPrivate.toByteArray(), canonicalPublic, peerId)
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
