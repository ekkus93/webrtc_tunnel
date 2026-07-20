package com.phillipchin.webrtctunnel.data

import com.phillipchin.webrtctunnel.model.AndroidAppPreferences
import com.phillipchin.webrtctunnel.model.SetupConfigInput
import com.phillipchin.webrtctunnel.security.IdentityRepository
import com.phillipchin.webrtctunnel.security.IdentityStorageSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Ordered stages of a setup save. Config is committed last so an earlier failure never
 * leaves an active config that references identity/keys that did not persist.
 */
enum class SetupPersistenceStage {
    Snapshot,
    Identity,
    AuthorizedKeys,
    BrokerSecret,
    SetupInput,
    Preferences,
    Config,
}

/**
 * A requested change to the managed broker secret (FIX7 P0-004-A). Distinct from "no request"
 * (a `null` [SetupPersistenceRequest.brokerSecretChange], which omits the `BrokerSecret` stage
 * entirely — e.g. an "advanced" externally-managed password file is in effect and the managed
 * secret must not be touched): [Remove] still requests the stage so an intentionally-cleared
 * password reliably deletes any stale managed secret rather than leaving it orphaned on disk.
 */
sealed interface BrokerSecretChange {
    data class Set(val password: String) : BrokerSecretChange

    data object Remove : BrokerSecretChange
}

/** Thrown (as a suppressed exception on the propagating [CancellationException]) when a
 * cancelled setup save's rollback could not fully restore one stage (FIX7 P0-004-D). Carries
 * enough detail for a caller to surface the one required cancellation diagnostic without
 * converting the cancellation itself into a normal success/failure outcome. */
class SetupRollbackException(
    val stage: SetupPersistenceStage,
    message: String,
) : Exception(message)

/**
 * A private identity to store as part of a setup save. Plaintext, wiped by its owner in a
 * `finally`. A non-data class with no [toString] override so the bytes never reach a
 * data-class `toString`, a log, or an assertion message (FIX6 §7.2).
 */
class IdentityReplacement(
    val privateIdentity: ByteArray,
    val publicIdentity: String,
)

/**
 * One setup save's intended mutations. A regular class (not a data class) so its
 * [toString] cannot leak [configContents] or the identity material.
 */
class SetupPersistenceRequest(
    val configContents: String,
    val setupInput: SetupConfigInput,
    val preferences: AndroidAppPreferences,
    val replacementIdentity: IdentityReplacement?,
    val authorizedPublicIdentityToAdd: String?,
    val brokerSecretChange: BrokerSecretChange? = null,
)

sealed interface SetupRollbackStageResult {
    data class Success(val stage: SetupPersistenceStage) : SetupRollbackStageResult

    data class Failure(
        val stage: SetupPersistenceStage,
        val reason: String,
    ) : SetupRollbackStageResult
}

sealed interface SetupPersistenceResult {
    data class Success(val stages: List<SetupPersistenceStage>) : SetupPersistenceResult

    data class Failed(
        val failedStage: SetupPersistenceStage,
        val reason: String,
        val rollback: List<SetupRollbackStageResult>,
    ) : SetupPersistenceResult
}

/**
 * Commits a validated setup save transactionally (FIX6 P0-003 / INV-007).
 *
 * Captures the exact prior state of every resource before the first mutation, applies
 * stages in a fixed order with config last, stops at the first failed stage, and rolls
 * every already-committed stage back in reverse order — continuing after an individual
 * rollback failure and reporting each outcome. Success is returned only if every requested
 * stage committed.
 *
 * The coordinator does not validate; callers validate first and build a
 * [SetupPersistenceRequest] describing only what should change.
 */
class SetupPersistenceCoordinator(
    private val configRepository: ConfigRepository,
    private val identityRepository: IdentityRepository,
    private val brokerSecretRepository: BrokerSecretRepository,
    private val loadPreferences: suspend () -> AndroidAppPreferences,
    private val persistPreferences: suspend (AndroidAppPreferences) -> Result<Unit>,
) {
    private val mutex = Mutex()

    private class SetupSnapshot(
        val identity: IdentityStorageSnapshot,
        val brokerSecret: ExactFileSnapshot,
        val setupInput: SetupInputSnapshot,
        val configExisted: Boolean,
        val configContents: String,
        val preferences: AndroidAppPreferences,
    ) {
        /** Broker secret bytes are the only secret this snapshot holds; identity plaintext never
         * appears here since [IdentityStorageSnapshot] only stores the encrypted/public files. */
        fun wipeSecrets() = brokerSecret.wipe()
    }

    suspend fun persist(request: SetupPersistenceRequest): SetupPersistenceResult =
        mutex.withLock {
            val snapshot =
                try {
                    captureSnapshot()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    return@withLock SetupPersistenceResult.Failed(
                        failedStage = SetupPersistenceStage.Snapshot,
                        reason = safeReason(error, "Failed to capture setup snapshot"),
                        rollback = emptyList(),
                    )
                }

            val committed = mutableListOf<SetupPersistenceStage>()
            try {
                for (stage in requestedStages(request)) {
                    val result = applyStage(stage, request)
                    if (result.isFailure) {
                        return@withLock SetupPersistenceResult.Failed(
                            failedStage = stage,
                            reason = safeReason(result.exceptionOrNull(), "Failed to persist setup"),
                            // FIX7 P0-004-C: wrapped in NonCancellable so an ordinary-failure
                            // rollback still runs to completion even if the caller's scope is
                            // concurrently cancelled (e.g. the user navigates away) while it runs.
                            rollback = withContext(NonCancellable) { rollback(snapshot, committed) },
                        )
                    }
                    committed += stage
                }
                SetupPersistenceResult.Success(committed.toList())
            } catch (cancelled: CancellationException) {
                // FIX7 P0-004-D: a cancellation mid-transaction must roll back every stage
                // already committed before propagating — otherwise a cancelled save silently
                // leaves live storage in a partially-mutated state. Rollback runs under
                // NonCancellable so the already-cancelled scope cannot abort it partway through.
                val rollbackResults = withContext(NonCancellable) { rollback(snapshot, committed) }
                rollbackResults.filterIsInstance<SetupRollbackStageResult.Failure>().forEach { failure ->
                    cancelled.addSuppressed(SetupRollbackException(failure.stage, failure.reason))
                }
                throw cancelled
            } finally {
                snapshot.wipeSecrets()
            }
        }

    private fun requestedStages(request: SetupPersistenceRequest): List<SetupPersistenceStage> =
        buildList {
            if (request.replacementIdentity != null) add(SetupPersistenceStage.Identity)
            if (request.authorizedPublicIdentityToAdd != null) add(SetupPersistenceStage.AuthorizedKeys)
            if (request.brokerSecretChange != null) add(SetupPersistenceStage.BrokerSecret)
            add(SetupPersistenceStage.SetupInput)
            add(SetupPersistenceStage.Preferences)
            add(SetupPersistenceStage.Config)
        }

    private suspend fun captureSnapshot(): SetupSnapshot =
        SetupSnapshot(
            identity = identityRepository.captureStorageSnapshot(),
            brokerSecret = brokerSecretRepository.captureSnapshot().getOrThrow(),
            setupInput = captureSetupInputSnapshot(configRepository.setupInputFileForSnapshot),
            configExisted = configRepository.configFileExists,
            configContents = configRepository.readConfig(),
            preferences = loadPreferences(),
        )

    private suspend fun applyStage(
        stage: SetupPersistenceStage,
        request: SetupPersistenceRequest,
    ): Result<Unit> =
        mutationResult {
            when (stage) {
                SetupPersistenceStage.Identity -> {
                    val identity =
                        requireNotNull(request.replacementIdentity) { "Identity stage requires a replacement identity" }
                    identityRepository.storeEncryptedIdentity(identity.privateIdentity, identity.publicIdentity)
                }
                SetupPersistenceStage.AuthorizedKeys -> {
                    val line =
                        requireNotNull(request.authorizedPublicIdentityToAdd) { "AuthorizedKeys stage requires a line" }
                    identityRepository.appendAuthorizedPublicIdentity(line).getOrThrow()
                }
                SetupPersistenceStage.BrokerSecret -> {
                    val change =
                        requireNotNull(request.brokerSecretChange) { "BrokerSecret stage requires a change" }
                    val password = if (change is BrokerSecretChange.Set) change.password else null
                    brokerSecretRepository.persist(password).getOrThrow()
                }
                SetupPersistenceStage.SetupInput -> configRepository.saveSetupInput(request.setupInput)
                SetupPersistenceStage.Preferences -> persistPreferences(request.preferences).getOrThrow()
                SetupPersistenceStage.Config ->
                    configRepository.writeConfigAtomically(
                        request.configContents,
                    ).getOrThrow()
                SetupPersistenceStage.Snapshot -> Unit
            }
        }

    private suspend fun rollback(
        snapshot: SetupSnapshot,
        committed: List<SetupPersistenceStage>,
    ): List<SetupRollbackStageResult> =
        committed.asReversed().map { stage ->
            try {
                restoreStage(stage, snapshot)
                SetupRollbackStageResult.Success(stage)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                SetupRollbackStageResult.Failure(stage, safeReason(error, "Rollback failed"))
            }
        }

    private suspend fun restoreStage(
        stage: SetupPersistenceStage,
        snapshot: SetupSnapshot,
    ) {
        when (stage) {
            // The identity storage snapshot is holistic (all three files), so restoring it
            // reverts both the identity pair and authorized_keys; each stage's restore is
            // idempotent.
            SetupPersistenceStage.Identity, SetupPersistenceStage.AuthorizedKeys ->
                identityRepository.restoreStorageSnapshot(snapshot.identity)
            SetupPersistenceStage.BrokerSecret ->
                brokerSecretRepository.restore(snapshot.brokerSecret).getOrThrow()
            SetupPersistenceStage.SetupInput ->
                restoreSetupInputSnapshot(configRepository.setupInputFileForSnapshot, snapshot.setupInput)
            SetupPersistenceStage.Preferences -> persistPreferences(snapshot.preferences).getOrThrow()
            SetupPersistenceStage.Config ->
                if (snapshot.configExisted) {
                    configRepository.writeConfigAtomically(snapshot.configContents).getOrThrow()
                } else {
                    configRepository.deleteConfigFileForTransactionalReset().getOrThrow()
                }
            SetupPersistenceStage.Snapshot -> Unit
        }
    }

    private fun safeReason(
        error: Throwable?,
        fallback: String,
    ): String = SensitiveDataRedactor.redactText(error?.message ?: fallback)
}
