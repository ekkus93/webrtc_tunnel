package com.phillipchin.webrtctunnel.viewmodel

import android.content.Intent
import androidx.core.content.ContextCompat
import com.phillipchin.webrtctunnel.TunnelForegroundService
import com.phillipchin.webrtctunnel.data.AppDependencies
import com.phillipchin.webrtctunnel.data.BrokerSecretChange
import com.phillipchin.webrtctunnel.data.CandidateCleanupException
import com.phillipchin.webrtctunnel.data.ConfigurationAdmission
import com.phillipchin.webrtctunnel.data.ConfigurationOperation
import com.phillipchin.webrtctunnel.data.IdentityReplacement
import com.phillipchin.webrtctunnel.data.SensitiveDataRedactor
import com.phillipchin.webrtctunnel.data.SetupOptionalChanges
import com.phillipchin.webrtctunnel.data.SetupPersistenceCoordinator
import com.phillipchin.webrtctunnel.data.SetupPersistenceRequest
import com.phillipchin.webrtctunnel.data.SetupPersistenceResult
import com.phillipchin.webrtctunnel.data.SetupRollbackException
import com.phillipchin.webrtctunnel.data.SetupRollbackStageResult
import com.phillipchin.webrtctunnel.data.ValidationWorkspaceRenderInputs
import com.phillipchin.webrtctunnel.data.renderOfferConfig
import com.phillipchin.webrtctunnel.data.renderOfferConfigForValidationWorkspace
import com.phillipchin.webrtctunnel.data.resolveBrokerPasswordPath
import com.phillipchin.webrtctunnel.data.withSetupValidationWorkspace
import com.phillipchin.webrtctunnel.model.AndroidAppPreferences
import com.phillipchin.webrtctunnel.model.ForwardConfig
import com.phillipchin.webrtctunnel.model.SetupConfigInput
import com.phillipchin.webrtctunnel.security.StoredIdentityMaterial
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

private class StaleSetupOperationException : Exception("Setup operation became stale")

/**
 * Read/write access to the shared wizard state, so controllers split out of
 * SetupViewModel can mutate it without each holding the MutableStateFlows.
 *
 * FIX8 P1-001-A: [operations] is the one shared setup-local admission gate every controller
 * routes its asynchronous actions through — bundled in here (rather than a separate constructor
 * parameter on each controller) since [SetupOperationCoordinator] itself takes no dependency on
 * this class, avoiding a circular construction order. [loadState] lets a controller (final save,
 * navigation) block while the setup baseline has not finished loading (P1-001-B).
 */
internal class WizardStateAccess(
    val state: () -> SetupWizardState,
    val forwards: () -> List<ForwardConfig>,
    val applyState: (SetupWizardState) -> Unit,
    val setForwards: (List<ForwardConfig>) -> Unit,
    val operations: SetupOperationCoordinator,
    val loadState: () -> SetupLoadState,
)

internal class SetupSaveController(
    private val deps: AppDependencies,
    private val scope: CoroutineScope,
    private val loadPreferences: suspend () -> AndroidAppPreferences,
    private val persistPreferences: suspend (AndroidAppPreferences) -> Result<Unit>,
    private val access: WizardStateAccess,
    private val identityDraft: SetupIdentityDraft,
) {
    // Injected via deps (not a Dispatchers.IO literal), so InjectDispatcher is satisfied without
    // adding an 8th constructor parameter (LongParameterList).
    private val ioDispatcher: CoroutineDispatcher = deps.dispatchers.io

    // FIX6 P0-003: commit the setup save transactionally. Built from the injected preference
    // lambdas (not deps') so preference-persistence test seams still drive the Preferences stage.
    private val persistence =
        SetupPersistenceCoordinator(
            configRepository = deps.configRepository,
            identityRepository = deps.identityRepository,
            brokerSecretRepository = deps.brokerSecretRepository,
            forwardsRepository = deps.forwardsRepository,
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
        // FIX7 P1-005-B: the broker TCP probe — explicit cancellation-first try/catch(Exception),
        // never runCatching (which would catch Throwable). The failure message is redacted below.
        scope.launch(ioDispatcher) {
            val message =
                try {
                    Socket().use { socket ->
                        socket.connect(InetSocketAddress(host, port), BROKER_PROBE_TIMEOUT_MS)
                    }
                    "TCP connection to $host:$port succeeded. Full MQTT/TLS auth is confirmed " +
                        "when the tunnel connects."
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    "TCP connection to $host:$port failed: ${error.message ?: "unknown error"}"
                }
            access.applyState(access.state().copy(brokerTestMessage = SensitiveDataRedactor.redactText(message)))
        }
    }

    fun saveAndApplyConfig() {
        scope.launch { saveAndApplyConfigInternal() }
    }

    fun startTunnelFromReview(onSuccess: (() -> Unit)? = null) {
        scope.launch {
            saveAndApplyConfigInternal {
                ContextCompat.startForegroundService(
                    deps.context,
                    Intent(deps.context, TunnelForegroundService::class.java)
                        .setAction(TunnelForegroundService.ACTION_START_OFFER),
                )
                access.applyState(access.state().copy(saveResult = "Tunnel start requested", errorMessage = null))
                onSuccess?.invoke()
            }
        }
    }

    private suspend fun saveAndApplyConfigInternal(onFreshSuccess: (() -> Unit)? = null): Boolean {
        // FIX8 P1-001-B: block while the setup baseline has not finished loading — a save built
        // from an incoherent baseline must never proceed.
        val loadState = access.loadState()
        if (loadState !is SetupLoadState.Ready) {
            access.applyState(
                access.state().copy(errorMessage = setupLoadNotReadyMessage(loadState), saveResult = null),
            )
            return false
        }
        // FIX9 P0-001-F: final save holds setup-local admission and carries the freshness token
        // through validation, global admission, persistence, and optional start-from-review.
        return access.operations.runGuarded(access, SetupDraftOperation.FinalSave) { token ->
            if (!token.isFresh()) {
                return@runGuarded false
            }
            when (
                val admission =
                    deps.configurationMutationCoordinator.tryRun(ConfigurationOperation.SetupSave) {
                        if (token.isFresh()) {
                            runSaveAndApply(token)
                        } else {
                            false
                        }
                    }
            ) {
                is ConfigurationAdmission.Completed -> {
                    val saved = admission.value
                    if (saved && token.isFresh()) {
                        onFreshSuccess?.invoke()
                    }
                    saved && token.isFresh()
                }
                is ConfigurationAdmission.Busy -> {
                    token.publishIfFresh {
                        access.applyState(
                            access.state().copy(
                                errorMessage =
                                    "Another configuration operation is already in progress: ${admission.active}",
                                saveResult = null,
                            ),
                        )
                    }
                    false
                }
            }
        } ?: false
    }

    private suspend fun runSaveAndApply(token: SetupOperationToken): Boolean {
        // Capture the current state only after the lock is held, so a serialized second save
        // works from fresh state rather than a snapshot taken before the first finished.
        val current = access.state()
        if (!token.isFresh()) {
            return false
        }
        val outcome = validateAndCommitSafely(current, token)
        val identity = outcome.getOrNull()
        return if (identity == null) {
            publishSaveFailureIfNeeded(access, token, current, outcome.exceptionOrNull())
            false
        } else {
            publishSaveSuccessIfFresh(access, identityDraft, token, current, identity)
        }
    }

    private suspend fun validateAndCommitSafely(
        current: SetupWizardState,
        token: SetupOperationToken,
    ): Result<ResolvedIdentity> =
        try {
            Result.success(validateAndCommit(current, token))
        } catch (cancelled: CancellationException) {
            reportRollbackIncompleteIfPresent(access, cancelled, current)
            throw cancelled
        } catch (error: Exception) {
            Result.failure(error)
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
    private suspend fun validateAndCommit(
        current: SetupWizardState,
        token: SetupOperationToken,
    ): ResolvedIdentity {
        throwIfStale(token)
        val input = current.input
        val enabledForwards = access.forwards().filter { it.enabled }
        validateStep(deps, SetupStep.Review, current)?.let { saveError(it, redact = false) }
        throwIfStale(token)
        val identity = resolveSaveIdentity()
        try {
            throwIfStale(token)
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
            throwIfStale(token)
            val prefs = deps.configRepository.preferences.first()
            throwIfStale(token)
            validateInIsolatedWorkspace(input, enabledForwards, authorizedLine, identity.privateIdentity, prefs)
            throwIfStale(token)
            // Validation used an isolated workspace copy of authorized_keys/broker-secret paths
            // (P0-003-C); the commit candidate below references the real paths instead.
            val commitCandidate =
                deps.configRepository.renderOfferConfig(
                    input = input,
                    forwards = enabledForwards,
                    debugLogs = prefs.debugLogsEnabled,
                    androidIceMode = prefs.androidIceMode,
                    brokerPasswordPath = resolveBrokerPasswordPath(input, deps.brokerSecretRepository.path),
                )
            throwIfStale(token)
            commitSetup(input, commitCandidate, prefs, access.forwards(), SetupIdentityChange(identity, authorizedLine))
            throwIfStale(token)
            return identity
        } finally {
            // Wipe the plaintext identity buffer; only the public id/peer id are used afterward.
            identity.privateIdentity.fill(0)
        }
    }

    private suspend fun resolveSaveIdentity(): ResolvedIdentity {
        // FIX8 P0-001-D: a wizard-generated/imported identity is resolved ONLY from the
        // draft's save-owned bytes — no import-path re-read / TOCTOU.
        identityDraft.copyForSave()?.let { draft ->
            return ResolvedIdentity(draft.privateIdentity, draft.publicIdentity, draft.peerId, fromImport = true)
        }
        // FIX8 P0-007-C: read the encrypted/public identity pair as one coherent snapshot.
        val material = deps.identityRepository.readStoredIdentityMaterial
        val resolved =
            if (material == null) {
                saveError("Missing encrypted identity", redact = true)
            } else {
                resolveStoredIdentity(deps, ioDispatcher, material)
                    ?: saveError("Stored private key exists but could not be loaded or is invalid", redact = true)
            }
        if (resolved.privateIdentity.isEmpty()) {
            saveError("Missing encrypted identity", redact = true)
        }
        return resolved
    }

    /**
     * FIX7 P0-003-C: renders a candidate referencing an isolated workspace copy of
     * `authorized_keys` and, if a new plaintext broker password was entered, a workspace copy of
     * the broker secret — then validates that candidate.
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
     * FIX7 P0-003-D/P0-004-A: commits the setup save through the transactional coordinator in
     * one call. The broker secret is now its own `BrokerSecret` stage, so a later stage's failure
     * rolls it back along with identity/authorized-keys instead of leaving it committed.
     */
    private suspend fun commitSetup(
        input: SetupConfigInput,
        candidate: String,
        prefs: AndroidAppPreferences,
        forwards: List<ForwardConfig>,
        identityChange: SetupIdentityChange,
    ) {
        val request =
            SetupPersistenceRequest(
                configContents = candidate,
                setupInput = input,
                preferences =
                    prefs.copy(
                        allowMetered = input.allowMetered,
                        resumeOnUnmetered = input.resumeOnUnmetered,
                    ),
                forwards = forwards,
                optionalChanges =
                    SetupOptionalChanges(
                        replacementIdentity =
                            if (identityChange.identity.fromImport) {
                                IdentityReplacement(
                                    identityChange.identity.privateIdentity,
                                    identityChange.identity.publicIdentity,
                                )
                            } else {
                                null
                            },
                        authorizedPublicIdentityToAdd = identityChange.authorizedLine,
                        brokerSecretChange =
                            if (input.brokerPasswordFile.isNotBlank()) {
                                null
                            } else if (input.brokerPassword.isNotBlank()) {
                                BrokerSecretChange.Set(input.brokerPassword)
                            } else {
                                BrokerSecretChange.Remove
                            },
                    ),
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

/** Groups [SetupSaveController.commitSetup]'s two identity-related inputs into one parameter. */
private class SetupIdentityChange(
    val identity: ResolvedIdentity,
    val authorizedLine: String?,
)

private fun throwIfStale(token: SetupOperationToken) {
    if (!token.isFresh()) {
        throw StaleSetupOperationException()
    }
}

private fun saveError(
    message: String,
    redact: Boolean,
): Nothing = throw SaveError(message, redact)

private fun publishSaveSuccessIfFresh(
    access: WizardStateAccess,
    identityDraft: SetupIdentityDraft,
    token: SetupOperationToken,
    current: SetupWizardState,
    identity: ResolvedIdentity,
): Boolean {
    if (!token.isFresh()) {
        return false
    }
    // FIX8 P0-001-D: a committed save clears the draft; a failed save leaves it for retry.
    identityDraft.clear()
    token.publishIfFresh {
        access.applyState(
            current.copy(
                localPublicIdentity = identity.publicIdentity,
                identityPeerId = identity.peerId,
                errorMessage = null,
                saveResult = "Configuration saved",
            ),
        )
    }
    return true
}

private fun publishSaveFailureIfNeeded(
    access: WizardStateAccess,
    token: SetupOperationToken,
    current: SetupWizardState,
    error: Throwable?,
) {
    if (error == null || error is StaleSetupOperationException || !token.isFresh()) {
        return
    }
    token.publishIfFresh {
        access.applyState(
            current.copy(errorMessage = saveFailureText(error), saveResult = null),
        )
    }
}

private fun saveFailureText(error: Throwable): String {
    val message = error.message ?: "Failed saving configuration"
    return if (error is SaveError && !error.redact) {
        message
    } else {
        SensitiveDataRedactor.redactText(message)
    }
}

private fun reportRollbackIncompleteIfPresent(
    access: WizardStateAccess,
    cancelled: CancellationException,
    current: SetupWizardState,
) {
    val incompleteStages =
        cancelled.suppressedExceptions.filterIsInstance<SetupRollbackException>().map { it.stage }
    if (incompleteStages.isEmpty()) {
        return
    }
    access.applyState(
        current.copy(
            errorMessage =
                "Setup was cancelled and could not be fully rolled back " +
                    "(setup_cancelled_rollback_incomplete): $incompleteStages",
            saveResult = null,
        ),
    )
}

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
    material: StoredIdentityMaterial,
): ResolvedIdentity? =
    withContext(dispatcher) {
        var bytes: ByteArray? = null
        var transferred = false
        try {
            bytes = deps.identityRepository.decryptPrivateIdentity(material)
            val validated = deps.identityValidation.validatePrivateIdentity(bytes.decodeToString())
            require(validated.valid) { validated.message ?: "Stored private identity is invalid" }
            val peerId = validated.peerId ?: throw IllegalArgumentException("Missing identity peer id")
            val publicIdentity = validated.canonicalPublicIdentity ?: material.publicIdentity
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
            material.wipe()
        }
    }

/**
 * P0-003: validate an imported public identity and return the canonical authorized-keys line
 * WITHOUT appending it. The setup transaction appends it atomically (AuthorizedKeys stage).
 *
 * FIX7 P1-005-B: explicit cancellation-first try/catch, not runCatching — calls the native
 * validation bridge.
 */
private fun validatePublicIdentityForImport(
    deps: AppDependencies,
    line: String,
    expectedRemotePeerId: String,
): Result<String> =
    try {
        val validated = deps.identityValidation.validatePublicIdentity(line)
        require(validated.valid) { validated.message ?: "Invalid public identity" }
        val peerId = validated.peerId ?: throw IllegalArgumentException("Public identity missing peer ID")
        require(peerId == expectedRemotePeerId) {
            "Remote peer ID must match imported public identity peer ID ($peerId)"
        }
        Result.success(validated.canonicalPublicIdentity ?: line.trim())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Result.failure(error)
    }
