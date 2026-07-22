package com.phillipchin.webrtctunnel.data

import androidx.annotation.CheckResult
import com.phillipchin.webrtctunnel.model.AndroidAppPreferences
import com.phillipchin.webrtctunnel.model.ForwardConfig
import com.phillipchin.webrtctunnel.model.SetupConfigInput
import com.phillipchin.webrtctunnel.security.IdentityRepository
import com.phillipchin.webrtctunnel.security.IdentityRestoreResult
import com.phillipchin.webrtctunnel.security.IdentityRollbackIncompleteException
import com.phillipchin.webrtctunnel.security.IdentityStorageSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Ordered stages of a setup save. Forwards commits before Config, and Config is committed
 * last, so an earlier failure never leaves an active config that references identity/keys/
 * forwards that did not persist.
 */
enum class SetupPersistenceStage {
    Snapshot,
    Identity,
    AuthorizedKeys,
    BrokerSecret,
    SetupInput,
    Preferences,
    Forwards,
    Config,
}

/**
 * A requested change to the managed broker secret (FIX7 P0-004-A). Distinct from "no request"
 * (a `null` [SetupOptionalChanges.brokerSecretChange], which omits the `BrokerSecret` stage
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
 * The three optional identity/security stage triggers a setup save may request, grouped into
 * one parameter object (rather than three separate [SetupPersistenceRequest] constructor
 * parameters) to keep that constructor under detekt's LongParameterList threshold.
 */
class SetupOptionalChanges(
    val replacementIdentity: IdentityReplacement?,
    val authorizedPublicIdentityToAdd: String?,
    val brokerSecretChange: BrokerSecretChange? = null,
)

/**
 * One setup save's intended mutations. A regular class (not a data class) so its
 * [toString] cannot leak [configContents] or the identity material.
 */
class SetupPersistenceRequest(
    val configContents: String,
    val setupInput: SetupConfigInput,
    val preferences: AndroidAppPreferences,
    // FIX8 P0-004-D/F: the full setup-wizard forwards draft (including disabled entries), not
    // just the enabled subset rendered into configContents — the Forwards stage persists this
    // list as the new authoritative ForwardsRepository state. No default: every caller must
    // decide explicitly rather than silently wiping forwards to empty.
    val forwards: List<ForwardConfig>,
    val optionalChanges: SetupOptionalChanges,
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
    private val forwardsRepository: ForwardsRepository,
    private val loadPreferences: suspend () -> AndroidAppPreferences,
    private val persistPreferences: suspend (AndroidAppPreferences) -> Result<Unit>,
) {
    private val mutex = Mutex()

    private class SetupSnapshot(
        val identity: IdentityStorageSnapshot,
        val brokerSecret: ExactFileSnapshot,
        // FIX8 P0-003-E: exact, coherent, mutex-serialized config+setup-input bytes — replaces
        // the previous separate configExisted/configContents/legacy-SetupInputSnapshot fields.
        val files: ConfigFilesSnapshot,
        // FIX8 P0-004-D: exact forwards state (file bytes + list + load state/error) for the
        // Forwards stage's rollback.
        val forwards: ForwardsTransactionSnapshot,
        val preferences: AndroidAppPreferences,
    ) {
        /** Broker secret and setup-input (which can carry the plaintext broker password, spec
         * §3.4) are the only secrets this snapshot holds; identity plaintext never appears here
         * since [IdentityStorageSnapshot] only stores the encrypted/public files. */
        fun wipeSecrets() {
            brokerSecret.wipe()
            files.wipeSecrets()
        }
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

            // FIX8 P0-003-D/CRITICAL-2: `attempted` gains a stage BEFORE its apply runs, so a
            // stage that mutates its destination and only THEN fails (e.g. a post-write
            // verification/cleanup failure) is still rolled back. `applied` (only stages whose
            // apply genuinely returned success) is what a real Success result reports.
            val applied = mutableListOf<SetupPersistenceStage>()
            val attempted = mutableListOf<SetupPersistenceStage>()
            try {
                for (stage in requestedStages(request)) {
                    attempted += stage
                    val result = applyStage(stage, request)
                    if (result.isFailure) {
                        return@withLock SetupPersistenceResult.Failed(
                            failedStage = stage,
                            reason = safeReason(result.exceptionOrNull(), "Failed to persist setup"),
                            // FIX7 P0-004-C: wrapped in NonCancellable so an ordinary-failure
                            // rollback still runs to completion even if the caller's scope is
                            // concurrently cancelled (e.g. the user navigates away) while it runs.
                            rollback = withContext(NonCancellable) { rollback(snapshot, attempted) },
                        )
                    }
                    applied += stage
                }
                SetupPersistenceResult.Success(applied.toList())
            } catch (cancelled: CancellationException) {
                // FIX7 P0-004-D: a cancellation mid-transaction must roll back every attempted
                // stage (including one that was mutating its destination at the moment of
                // cancellation) before propagating — otherwise a cancelled save silently leaves
                // live storage in a partially-mutated state. Rollback runs under NonCancellable
                // so the already-cancelled scope cannot abort it partway through.
                val rollbackResults = withContext(NonCancellable) { rollback(snapshot, attempted) }
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
            if (request.optionalChanges.replacementIdentity != null) add(SetupPersistenceStage.Identity)
            if (request.optionalChanges.authorizedPublicIdentityToAdd != null) add(SetupPersistenceStage.AuthorizedKeys)
            if (request.optionalChanges.brokerSecretChange != null) add(SetupPersistenceStage.BrokerSecret)
            add(SetupPersistenceStage.SetupInput)
            add(SetupPersistenceStage.Preferences)
            add(SetupPersistenceStage.Forwards)
            add(SetupPersistenceStage.Config)
        }

    private suspend fun captureSnapshot(): SetupSnapshot =
        SetupSnapshot(
            identity = identityRepository.captureStorageSnapshot,
            brokerSecret = brokerSecretRepository.captureSnapshot().getOrThrow(),
            files = configRepository.captureFilesSnapshot().getOrThrow(),
            forwards = forwardsRepository.captureForTransaction().getOrThrow(),
            preferences = loadPreferences(),
        )

    @CheckResult
    private suspend fun applyStage(
        stage: SetupPersistenceStage,
        request: SetupPersistenceRequest,
    ): Result<Unit> =
        mutationResult {
            when (stage) {
                SetupPersistenceStage.Identity -> {
                    val identity =
                        requireNotNull(request.optionalChanges.replacementIdentity) {
                            "Identity stage requires a replacement identity"
                        }
                    identityRepository.storeEncryptedIdentity(identity.privateIdentity, identity.publicIdentity)
                }
                SetupPersistenceStage.AuthorizedKeys -> {
                    val line =
                        requireNotNull(request.optionalChanges.authorizedPublicIdentityToAdd) {
                            "AuthorizedKeys stage requires a line"
                        }
                    identityRepository.appendAuthorizedPublicIdentity(line).getOrThrow()
                }
                SetupPersistenceStage.BrokerSecret -> {
                    val change =
                        requireNotNull(request.optionalChanges.brokerSecretChange) {
                            "BrokerSecret stage requires a change"
                        }
                    val password = if (change is BrokerSecretChange.Set) change.password else null
                    brokerSecretRepository.persist(password).getOrThrow()
                }
                SetupPersistenceStage.SetupInput ->
                    configRepository.saveSetupInputAtomically(request.setupInput).getOrThrow()
                SetupPersistenceStage.Preferences -> persistPreferences(request.preferences).getOrThrow()
                SetupPersistenceStage.Forwards ->
                    forwardsRepository.replaceForTransaction(request.forwards).getOrThrow()
                SetupPersistenceStage.Config ->
                    configRepository.writeConfigAtomically(
                        request.configContents,
                    ).getOrThrow()
                SetupPersistenceStage.Snapshot -> Unit
            }
        }

    private suspend fun rollback(
        snapshot: SetupSnapshot,
        attempted: List<SetupPersistenceStage>,
    ): List<SetupRollbackStageResult> =
        attempted.asReversed().map { stage ->
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
            // FIX8 P0-004-E: each stage restores only its own file(s) — Identity the pair,
            // AuthorizedKeys just authorized_keys — instead of both calling the full-triplet
            // restoreStorageSnapshot, so a rollback needing both stages never restores either
            // file twice. FIX7 P0-006-A: both report per-file results instead of throwing on
            // the first failure, so every failed file is visible here.
            SetupPersistenceStage.Identity ->
                restoreIdentityFailures(identityRepository.restoreIdentityPairSnapshot(snapshot.identity))
            SetupPersistenceStage.AuthorizedKeys ->
                restoreIdentityFailures(identityRepository.restoreAuthorizedKeysSnapshot(snapshot.identity))
            SetupPersistenceStage.BrokerSecret ->
                brokerSecretRepository.restore(snapshot.brokerSecret).getOrThrow()
            SetupPersistenceStage.SetupInput ->
                configRepository.restoreSetupInputFileSnapshot(snapshot.files.setupInput).getOrThrow()
            SetupPersistenceStage.Preferences -> persistPreferences(snapshot.preferences).getOrThrow()
            SetupPersistenceStage.Forwards ->
                forwardsRepository.restoreForTransaction(snapshot.forwards).getOrThrow()
            SetupPersistenceStage.Config ->
                configRepository.restoreConfigSnapshot(snapshot.files.config).getOrThrow()
            SetupPersistenceStage.Snapshot -> Unit
        }
    }

    private fun restoreIdentityFailures(results: List<IdentityRestoreResult>) {
        val failures = results.filterIsInstance<IdentityRestoreResult.Failure>()
        if (failures.isNotEmpty()) {
            throw IdentityRollbackIncompleteException(
                "Identity storage rollback incomplete: " +
                    failures.joinToString { "${it.file}: ${it.reason}" },
                null,
            )
        }
    }

    private fun safeReason(
        error: Throwable?,
        fallback: String,
    ): String = SensitiveDataRedactor.redactText(error?.message ?: fallback)
}
