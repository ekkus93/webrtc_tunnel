package com.phillipchin.webrtctunnel.viewmodel

import android.content.Intent
import androidx.core.content.ContextCompat
import com.phillipchin.webrtctunnel.TunnelForegroundService
import com.phillipchin.webrtctunnel.data.AppDependencies
import com.phillipchin.webrtctunnel.data.CandidateCleanupException
import com.phillipchin.webrtctunnel.data.ConfigurationAdmission
import com.phillipchin.webrtctunnel.data.ConfigurationOperation
import com.phillipchin.webrtctunnel.data.IdentityReplacement
import com.phillipchin.webrtctunnel.data.SensitiveDataRedactor
import com.phillipchin.webrtctunnel.data.SetupPersistenceCoordinator
import com.phillipchin.webrtctunnel.data.SetupPersistenceRequest
import com.phillipchin.webrtctunnel.data.SetupPersistenceResult
import com.phillipchin.webrtctunnel.data.SetupRollbackStageResult
import com.phillipchin.webrtctunnel.data.ValidationWorkspaceRenderInputs
import com.phillipchin.webrtctunnel.data.renderOfferConfigForValidationWorkspace
import com.phillipchin.webrtctunnel.data.resolveBrokerPasswordPath
import com.phillipchin.webrtctunnel.data.withSetupValidationWorkspace
import com.phillipchin.webrtctunnel.model.AndroidAppPreferences
import com.phillipchin.webrtctunnel.model.ForwardConfig
import com.phillipchin.webrtctunnel.model.SetupConfigInput
import com.phillipchin.webrtctunnel.security.readPrivateIdentityFile
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
    // FIX6 P0-003: commit the setup save transactionally. Built from the injected preference
    // lambdas (not deps') so preference-persistence test seams still drive the Preferences stage.
    private val persistence =
        SetupPersistenceCoordinator(
            configRepository = deps.configRepository,
            identityRepository = deps.identityRepository,
            loadPreferences = loadPreferences,
            persistPreferences = persistPreferences,
        )

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
        // FIX7 P0-001-C: admission is the single cross-feature coordinator, not a local mutex —
        // an overlapping config import/forward mutation/reset must also be rejected, not just
        // another setup save. A rejected save is visibly and durably reported.
        return when (
            val admission =
                deps.configurationMutationCoordinator.tryRun(ConfigurationOperation.SetupSave) {
                    runSaveAndApply()
                }
        ) {
            is ConfigurationAdmission.Completed -> admission.value
            is ConfigurationAdmission.Busy -> {
                access.applyState(
                    access.state().copy(
                        errorMessage =
                            "Another configuration operation is already in progress: ${admission.active}",
                        saveResult = null,
                    ),
                )
                false
            }
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
     * Validate the review state in an isolated workspace and, if valid, commit the whole save
     * through the transactional coordinator. FIX7 P0-003-D: validation never mutates live
     * identity/authorized_keys/broker-secret/setup-input/preferences/config storage — it resolves
     * inputs in memory, renders a candidate against an isolated workspace copy, and only on
     * success builds one [SetupPersistenceRequest] committed through [persistence] exactly once.
     * Throws [SaveError] on any validation/persistence failure and always wipes the plaintext
     * identity buffer. Returns the resolved identity for the success path.
     */
    private suspend fun validateAndCommit(current: SetupWizardState): ResolvedIdentity {
        val input = current.input
        val enabledForwards = access.forwards().filter { it.enabled }
        validateStep(deps, SetupStep.Review, current)?.let { saveError(it, redact = false) }
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
            validateInIsolatedWorkspace(input, enabledForwards, authorizedLine, identity.privateIdentity, prefs)
            // Validation used an isolated workspace copy of authorized_keys/broker-secret paths
            // (P0-003-C); the commit candidate below references the real (about-to-be-committed)
            // live paths instead — the workspace is already deleted by this point.
            val commitCandidate =
                deps.configRepository.renderOfferConfig(
                    input = input,
                    forwards = enabledForwards,
                    debugLogs = prefs.debugLogsEnabled,
                    androidIceMode = prefs.androidIceMode,
                    brokerPasswordPath = resolveBrokerPasswordPath(input, deps.brokerSecretRepository.path),
                )
            commitSetup(input, commitCandidate, identity, authorizedLine)
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
     * FIX7 P0-003-C: renders a candidate referencing an isolated workspace copy of
     * `authorized_keys` (the live file merged with [authorizedLine], never the live file itself)
     * and, if a new plaintext broker password was entered, a workspace copy of the broker secret
     * — then validates that candidate. Workspace cleanup failure after an otherwise-successful
     * validation must still block the commit (P0-002 cleanup composition throws
     * [CandidateCleanupException] in exactly that case), surfaced here as `candidate_cleanup_failed`.
     */
    private suspend fun validateInIsolatedWorkspace(
        input: SetupConfigInput,
        forwards: List<ForwardConfig>,
        authorizedLine: String?,
        identityBytes: ByteArray,
        prefs: AndroidAppPreferences,
    ) {
        val includeBrokerPassword = input.brokerPasswordFile.isBlank() && input.brokerPassword.isNotBlank()
        val validation =
            try {
                withContext(ioDispatcher) {
                    withSetupValidationWorkspace(deps.context.cacheDir, includeBrokerPassword) { workspace ->
                        workspace.authorizedKeysFile.writeText(mergedAuthorizedKeys(deps, authorizedLine))
                        val brokerPasswordPath =
                            if (workspace.brokerPasswordFile != null) {
                                workspace.brokerPasswordFile.writeText(input.brokerPassword)
                                workspace.brokerPasswordFile.absolutePath
                            } else {
                                resolveBrokerPasswordPath(input, deps.brokerSecretRepository.path)
                            }
                        val candidate =
                            renderOfferConfigForValidationWorkspace(
                                input = input,
                                forwards = forwards,
                                render =
                                    ValidationWorkspaceRenderInputs(
                                        filesDir = deps.context.filesDir,
                                        preferences = prefs,
                                        brokerPasswordPath = brokerPasswordPath,
                                        authorizedKeysPath = workspace.authorizedKeysFile.absolutePath,
                                    ),
                            )
                        workspace.candidateFile.writeText(candidate)
                        deps.identityValidation.validateConfigWithIdentity(
                            workspace.candidateFile.absolutePath,
                            identityBytes,
                        )
                    }
                }
            } catch (cleanupFailure: CandidateCleanupException) {
                saveError(
                    "Setup validation workspace cleanup failed (candidate_cleanup_failed): " +
                        (cleanupFailure.cause?.message ?: "unknown cleanup failure"),
                    redact = true,
                )
            }
        if (!validation.valid) {
            saveError(validation.message ?: "Config validation failed", redact = false)
        }
    }

    /**
     * FIX7 P0-003-D/P0-003-B: commits the setup save. The broker secret is persisted directly
     * here (not yet its own coordinator stage with rollback — that upgrade is P0-004-A's scope),
     * positioned before the coordinator so the config committed next can reference it. Identity
     * and authorized-key mutations now flow through the coordinator's existing Identity/
     * AuthorizedKeys stages (previously bypassed by writing them live pre-validation) — no
     * outer identity snapshot/restore is needed here because nothing is written live until this
     * point, and the coordinator captures its own snapshot and rolls back on failure.
     */
    private suspend fun commitSetup(
        input: SetupConfigInput,
        candidate: String,
        identity: ResolvedIdentity,
        authorizedLine: String?,
    ) {
        if (input.brokerPasswordFile.isBlank() && input.brokerPassword.isNotBlank()) {
            deps.brokerSecretRepository.persist(input.brokerPassword).getOrElse { error ->
                saveError(
                    "Failed to persist broker password: " +
                        SensitiveDataRedactor.redactText(error.message ?: "unknown error"),
                    redact = true,
                )
            }
        }
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

/** FIX7 P0-003-C: the live `authorized_keys` content merged with [authorizedLine] (if any),
 * mirroring `IdentityRepository.appendAuthorizedPublicIdentity`'s own dedupe-and-sort merge —
 * without touching the live file itself. Used to populate the isolated validation workspace. */
private fun mergedAuthorizedKeys(
    deps: AppDependencies,
    authorizedLine: String?,
): String {
    val liveFile = File(deps.context.filesDir, "authorized_keys")
    val existing =
        if (liveFile.exists()) {
            liveFile.readLines().map { it.trim() }.filter { it.isNotEmpty() }.toMutableSet()
        } else {
            mutableSetOf()
        }
    if (authorizedLine != null) {
        existing.add(authorizedLine)
    }
    return existing.toList().sorted().joinToString("\n")
}

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
        } catch (_: Exception) {
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
