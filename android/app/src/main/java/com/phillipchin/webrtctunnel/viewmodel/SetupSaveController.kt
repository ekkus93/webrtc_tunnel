package com.phillipchin.webrtctunnel.viewmodel

import android.content.Intent
import androidx.core.content.ContextCompat
import com.phillipchin.webrtctunnel.TunnelForegroundService
import com.phillipchin.webrtctunnel.data.AppDependencies
import com.phillipchin.webrtctunnel.data.IdentityReplacement
import com.phillipchin.webrtctunnel.data.SensitiveDataRedactor
import com.phillipchin.webrtctunnel.data.SetupPersistenceCoordinator
import com.phillipchin.webrtctunnel.data.SetupPersistenceRequest
import com.phillipchin.webrtctunnel.data.SetupPersistenceResult
import com.phillipchin.webrtctunnel.data.SetupRollbackStageResult
import com.phillipchin.webrtctunnel.data.createCandidateFile
import com.phillipchin.webrtctunnel.data.deleteCandidateFileSafely
import com.phillipchin.webrtctunnel.model.AndroidAppPreferences
import com.phillipchin.webrtctunnel.model.ForwardConfig
import com.phillipchin.webrtctunnel.model.SetupConfigInput
import com.phillipchin.webrtctunnel.model.ValidationResult
import com.phillipchin.webrtctunnel.security.readPrivateIdentityFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
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
    // FIX6 P0-003: commit the setup save transactionally. Built from the injected preference
    // lambdas (not deps') so preference-persistence test seams still drive the Preferences stage.
    private val persistence =
        SetupPersistenceCoordinator(
            configRepository = deps.configRepository,
            identityRepository = deps.identityRepository,
            loadPreferences = loadPreferences,
            persistPreferences = persistPreferences,
        )

    // P1-005-C: an atomic busy guard, not a check-before-launch read — two rapid saves cannot
    // overlap and clobber each other's candidate/state. A rejected save is visibly reported.
    private val operationMutex = Mutex()

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
        // P1-005-C: reject an overlapping save visibly instead of racing on a mutable busy flag.
        if (!operationMutex.tryLock()) {
            access.applyState(
                access.state().copy(
                    errorMessage = "Configuration save is already in progress",
                    saveResult = null,
                ),
            )
            return false
        }
        try {
            return runSaveAndApply()
        } finally {
            operationMutex.unlock()
        }
    }

    private suspend fun runSaveAndApply(): Boolean {
        // Capture the current state only after the lock is held, so a serialized second save
        // works from fresh state rather than a snapshot taken before the first finished.
        val current = access.state()
        // P0-001-B: rethrow CancellationException rather than folding it into a visible save
        // error (the old enclosing runCatching reported cancellation as a failure). Only real
        // errors become an errorMessage; a cancelled save reports neither success nor failure.
        val outcome =
            try {
                Result.success(validateAndCommit(current))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Result.failure(error)
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

    /**
     * Validate the review state and, if valid, commit the whole save through the transactional
     * coordinator. Throws [SaveError] on any validation/persistence failure and always wipes the
     * plaintext identity buffer. Returns the resolved identity for the success path.
     */
    private suspend fun validateAndCommit(current: SetupWizardState): ResolvedIdentity {
        val input = current.input
        val enabledForwards = access.forwards().filter { it.enabled }
        validateStep(deps, SetupStep.Review, current)?.let { saveError(it, redact = false) }
        // P0-003: resolve/validate identity WITHOUT persisting it — the coordinator performs
        // every persistent mutation atomically below.
        val identity = resolveSaveIdentity(current)
        try {
            if (identity.peerId != input.localPeerId) {
                saveError(
                    "Local peer ID must match private identity peer ID (${identity.peerId})",
                    redact = true,
                )
            }
            val authorizedLine =
                if (current.importPublicIdentity.isNotBlank()) {
                    validatePublicIdentityForImport(deps, current.importPublicIdentity, input.remotePeerId)
                        .getOrElse { saveError(it.message ?: "Failed importing public identity", redact = true) }
                } else {
                    null
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
            commitSetup(input, candidate, identity, authorizedLine)
            return identity
        } finally {
            // Wipe the plaintext identity buffer; only the public id/peer id are used afterward.
            identity.privateIdentity.fill(0)
        }
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

    /**
     * P0-003: commit the whole setup save through the transactional coordinator. The identity
     * is stored only when it came from an import ([ResolvedIdentity.fromImport]); a
     * pre-existing stored identity needs no Identity stage. A failure leaves no partial state:
     * [SetupPersistenceResult.Failed] reports whether rollback fully restored the prior state
     * (setup_persistence_failed) or could not (setup_rollback_incomplete).
     */
    private suspend fun commitSetup(
        input: SetupConfigInput,
        candidate: String,
        identity: ResolvedIdentity,
        authorizedLine: String?,
    ) {
        val existing = loadPreferences()
        val request =
            SetupPersistenceRequest(
                configContents = candidate,
                setupInput = input,
                preferences =
                    existing.copy(
                        allowMetered = input.allowMetered,
                        resumeOnUnmetered = input.resumeOnUnmetered,
                    ),
                replacementIdentity =
                    if (identity.fromImport) {
                        IdentityReplacement(identity.privateIdentity, identity.publicIdentity)
                    } else {
                        null
                    },
                authorizedPublicIdentityToAdd = authorizedLine,
            )
        val result = withContext(ioDispatcher) { persistence.persist(request) }
        if (result is SetupPersistenceResult.Failed) {
            val rollbackIncomplete = result.rollback.any { it is SetupRollbackStageResult.Failure }
            val message =
                if (rollbackIncomplete) {
                    "Saving configuration failed and could not be fully rolled back " +
                        "(setup_rollback_incomplete): ${result.reason}"
                } else {
                    "Saving configuration failed and was rolled back " +
                        "(setup_persistence_failed): ${result.reason}"
                }
            // result.reason is already redacted by the coordinator.
            saveError(message, redact = false)
        }
    }
}

/**
 * P0-002: Named type for resolved identity components.
 * Replaces raw Triple for safer ownership semantics.
 *
 * [fromImport] is true when the identity was resolved from a user-supplied import file and must
 * therefore be persisted by the setup transaction; false when it was read from the already-stored
 * identity (which the transaction leaves in place).
 */
private data class ResolvedIdentity(
    val privateIdentity: ByteArray,
    val publicIdentity: String,
    val peerId: String,
    val fromImport: Boolean,
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
            ResolvedIdentity(bytes, publicIdentity, peerId, fromImport = false)
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

/**
 * P0-003: validate an imported private identity and return its canonical material WITHOUT
 * persisting it. The returned [ResolvedIdentity] is marked [ResolvedIdentity.fromImport] so the
 * setup transaction stores it atomically alongside the config.
 */
private fun importPrivateIdentity(
    deps: AppDependencies,
    path: String,
): Result<ResolvedIdentity> =
    runCatching {
        val privateIdentity = readPrivateIdentityFile(path).getOrThrow()
        val validated = deps.identityValidation.validatePrivateIdentity(privateIdentity)
        require(validated.valid) { validated.message ?: "Invalid private identity" }
        val canonicalPrivate = validated.canonicalPrivateIdentity ?: privateIdentity
        val canonicalPublic =
            validated.canonicalPublicIdentity
                ?: throw IllegalArgumentException("Missing canonical public identity")
        val peerId = validated.peerId ?: throw IllegalArgumentException("Missing canonical peer id")
        ResolvedIdentity(canonicalPrivate.toByteArray(), canonicalPublic, peerId, fromImport = true)
    }

/**
 * P0-003: validate an imported public identity and return the canonical authorized-keys line
 * WITHOUT appending it. The setup transaction appends it atomically (AuthorizedKeys stage).
 */
private fun validatePublicIdentityForImport(
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
        validated.canonicalPublicIdentity ?: line.trim()
    }

private fun validateCandidateConfig(
    deps: AppDependencies,
    candidate: String,
    identityBytes: ByteArray,
): ValidationResult {
    // P1-005: a unique candidate file per validation so two concurrent operations can never
    // share (and clobber) one fixed "config-candidate.toml".
    val temp = createCandidateFile(deps.context.cacheDir, "setup-config-")
    return runCatching {
        temp.writeText(candidate)
        deps.identityValidation.validateConfigWithIdentity(temp.absolutePath, identityBytes)
    }.getOrElse { ValidationResult(false, it.message) }.also {
        // Cleanup failure must not mask the validation result; it is reported separately.
        deleteCandidateFileSafely(temp).onFailure { cleanup ->
            android.util.Log.w(
                "SetupSaveController",
                "Candidate cleanup failed: ${SensitiveDataRedactor.redactText(cleanup.message ?: "unknown")}",
            )
        }
    }
}
