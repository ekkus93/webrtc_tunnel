package com.phillipchin.webrtctunnel.data

import androidx.annotation.CheckResult
import com.phillipchin.webrtctunnel.model.SetupConfigInput
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Thrown when snapshot capture fails during transactional reset — [stage] identifies which
 * component's capture actually failed (FIX8 P0-006-A), so a caller never sees a fixed/wrong
 * stage name regardless of which of Config/SetupInput/Forwards could not be read.
 */
class SnapshotCaptureException(
    val stage: ResetStage,
    message: String,
    cause: Throwable?,
) : Exception(message, cause)

/** Thrown (as a suppressed exception on the propagating [CancellationException]) when a
 * cancelled reset's rollback could not fully restore one stage (FIX7 P0-005-C) — mirrors
 * [SetupRollbackException]'s role for [SetupPersistenceCoordinator]. */
class ResetRollbackException(
    val stage: ResetStage,
    message: String,
) : Exception(message)

/**
 * Snapshot of the exact state before a transactional reset begins. Config and setup-input use
 * [ExactFileSnapshot] (FIX7 P0-005-B) so an absent file restores as absent rather than as a
 * default-valued one (CRITICAL-3) — `setup_input.json` can hold a plaintext broker password, so
 * its snapshot bytes are secret-bearing and must be wiped once the transaction finishes (see
 * [ResetSnapshot.wipeSecrets]). Forwards uses the same exact, revision-tracked
 * [ForwardsTransactionSnapshot] every other transaction captures (FIX8 P0-006-A) instead of a
 * plain re-serializable list, so its restore is byte-exact and refuses to overwrite a newer
 * concurrent mutation.
 */
internal class ResetSnapshot(
    val config: ExactFileSnapshot,
    val setupInput: ExactFileSnapshot,
    val forwards: ForwardsTransactionSnapshot,
) {
    fun wipeSecrets() = setupInput.wipe()
}

/**
 * Stages that are reset in order. Rollback proceeds in reverse order.
 */
enum class ResetStage {
    Config,
    SetupInput,
    Forwards,
}

/**
 * Outcome for a single reset stage.
 */
sealed interface ResetStageResult {
    data class Success(val stage: ResetStage) : ResetStageResult

    data class Failure(val stage: ResetStage, val reason: String) : ResetStageResult
}

/**
 * Outcome for a single rollback stage.
 */
sealed interface RollbackStageResult {
    data class Success(val stage: ResetStage) : RollbackStageResult

    data class Failure(val stage: ResetStage, val reason: String) : RollbackStageResult
}

/**
 * Result of a transactional reset operation.
 */
sealed interface ResetResult {
    data class Success(val stages: List<ResetStageResult>) : ResetResult

    data class Failed(
        val failedStage: ResetStage,
        val cause: String,
        val rollback: List<RollbackStageResult>,
    ) : ResetResult
}

/**
 * Transactional configuration reset with real snapshot/restore semantics (P0-001).
 *
 * Captures exact prior state before any mutation. On failure, restores from snapshot rather than
 * re-running reset operations. Every stage is added to the attempted set BEFORE its own apply
 * runs (FIX8 P0-006-C) — a stage that changes its destination and only then reports failure
 * (e.g. a post-move cleanup failure) is still rolled back, not silently skipped. Every rollback
 * stage is reported per-stage.
 */
class TransactionalResetCoordinator(
    private val configRepository: ConfigRepository,
    private val forwardsRepository: ForwardsRepository,
    // Same testability seam as BrokerSecretRepository's readBytes (FIX7 P0-004-F): lets a test
    // observe the exact byte array captureSetupInputFileSnapshot read, to prove the setup-input
    // snapshot is wiped once the transaction finishes.
    private val setupInputReadBytes: (File) -> ByteArray = File::readBytes,
) {
    private val resetMutex = Mutex()

    // FIX8 P1-003-B: a discarded result could silently leave a caller believing a reset
    // succeeded (or rolled back cleanly) when it did not.
    @CheckResult
    suspend fun resetConfiguration(): ResetResult =
        resetMutex.withLock {
            // Step 1: capture exact prior state (P0-001 snapshot)
            val snapshot =
                captureSnapshot().getOrElse { error ->
                    // Snapshot capture failed — abort before any mutation.
                    val stage = (error as? SnapshotCaptureException)?.stage ?: ResetStage.Config
                    return@withLock ResetResult.Failed(
                        failedStage = stage,
                        cause = safeResetReason(error, "Snapshot capture failed"),
                        rollback = emptyList(),
                    )
                }

            // Step 2: perform reset stages in order, stopping on first failure. FIX7 P0-005-C:
            // a cancellation partway through must roll back every already-mutated stage (under
            // NonCancellable) before rethrowing — mirroring SetupPersistenceCoordinator's
            // cancellation handling — rather than silently leaving live storage partially reset.
            try {
                applyStagesAndBuildResult(snapshot)
            } finally {
                snapshot.wipeSecrets()
            }
        }

    // Split out of resetConfiguration() so the cancellation catch below wraps ONLY stage
    // *application* — not the ordinary-failure branch's own rollback call. If that rollback
    // call's own restore throws a (synthetic or real) CancellationException, it must propagate
    // directly rather than being caught here a second time and re-rolled-back.
    private suspend fun applyStagesAndBuildResult(snapshot: ResetSnapshot): ResetResult {
        val attempted = mutableListOf<ResetStage>()
        val applied = mutableListOf<ResetStage>()
        val firstFailure =
            try {
                applyStages(attempted, applied)
            } catch (cancelled: CancellationException) {
                val rollbackResults =
                    withContext(NonCancellable) { rollbackFromSnapshot(snapshot, attempted, applied) }
                rollbackResults.filterIsInstance<RollbackStageResult.Failure>().forEach { failure ->
                    cancelled.addSuppressed(ResetRollbackException(failure.stage, failure.reason))
                }
                throw cancelled
            }
        return if (firstFailure == null) {
            ResetResult.Success(resetStages.map { stage -> ResetStageResult.Success(stage) })
        } else {
            // Wrapped in NonCancellable so this ordinary-failure rollback still runs to
            // completion even if the caller's scope is concurrently cancelled.
            ResetResult.Failed(
                failedStage = firstFailure.stage,
                cause = firstFailure.reason,
                rollback = withContext(NonCancellable) { rollbackFromSnapshot(snapshot, attempted, applied) },
            )
        }
    }

    // FIX8 P0-006-C: each stage is added to `attempted` BEFORE its own apply runs, so a stage
    // that changes its destination and only then reports failure is still rolled back — the
    // same fix already applied to SetupPersistenceCoordinator/ForwardConfigurationCoordinator
    // (P0-003/P0-004/P0-005). `applied` (only stages whose apply genuinely returned success) is
    // what tells a later restore whether this transaction's own Forwards-stage apply advanced
    // ForwardsRepository's revision.
    private suspend fun applyStages(
        attempted: MutableList<ResetStage>,
        applied: MutableList<ResetStage>,
    ): ResetStageResult.Failure? {
        for (stage in resetStages) {
            attempted.add(stage)
            val outcome = resetStage(stage)
            if (outcome is ResetStageResult.Failure) {
                return outcome
            }
            applied.add(stage)
        }
        return null
    }

    private suspend fun resetStage(stage: ResetStage): ResetStageResult =
        when (stage) {
            ResetStage.Config ->
                configRepository.writeConfigAtomically(configRepository.defaultConfigTemplate).fold(
                    onSuccess = { ResetStageResult.Success(ResetStage.Config) },
                    onFailure = { error ->
                        ResetStageResult.Failure(
                            ResetStage.Config,
                            safeResetReason(error, "Failed to reset config"),
                        )
                    },
                )

            ResetStage.SetupInput -> resetSetupInputStage()

            ResetStage.Forwards ->
                forwardsRepository.resetForwards().fold(
                    onSuccess = { ResetStageResult.Success(ResetStage.Forwards) },
                    onFailure = { error ->
                        ResetStageResult.Failure(
                            ResetStage.Forwards,
                            safeResetReason(error, "Failed to reset forwards"),
                        )
                    },
                )
        }

    // FIX8 P0-006-B: saveSetupInputAtomically (Result-returning, mutex-serialized, real atomic
    // replace) instead of the non-atomic saveSetupInput — matching every other authoritative
    // setup-input write in the app.
    private suspend fun resetSetupInputStage(): ResetStageResult =
        configRepository.saveSetupInputAtomically(SetupConfigInput()).fold(
            onSuccess = { ResetStageResult.Success(ResetStage.SetupInput) },
            onFailure = { error ->
                ResetStageResult.Failure(
                    ResetStage.SetupInput,
                    safeResetReason(error, "Failed to reset setup input"),
                )
            },
        )

    // FIX8 P0-006-A: exact-byte capture for all three components, with no parsing — a corrupt
    // setup_input.json is captured/restored as raw bytes and no longer blocks reset (P0-006-D).
    // Each capture is labeled with the stage that actually failed, instead of a hardcoded
    // Config no matter which component's read failed.
    @CheckResult
    private suspend fun captureSnapshot(): Result<ResetSnapshot> =
        try {
            val configSnapshot =
                configRepository.captureConfigSnapshotForReset.getOrElse { error ->
                    throw SnapshotCaptureException(ResetStage.Config, "Failed to capture config snapshot", error)
                }
            val setupInputSnapshot =
                captureSetupInputFileSnapshot(configRepository.setupInputFileForSnapshot, setupInputReadBytes)
                    .getOrElse { error ->
                        throw SnapshotCaptureException(
                            ResetStage.SetupInput,
                            "Failed to capture setup input snapshot",
                            error,
                        )
                    }
            val forwardsSnapshot =
                forwardsRepository.captureForTransaction().getOrElse { error ->
                    throw SnapshotCaptureException(ResetStage.Forwards, "Failed to capture forwards snapshot", error)
                }
            Result.success(ResetSnapshot(configSnapshot, setupInputSnapshot, forwardsSnapshot))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (captureFailure: SnapshotCaptureException) {
            Result.failure(captureFailure)
        }

    // P1-002-C: an explicit loop, so one restore stage throwing is recorded as a Failure and
    // never suppresses the remaining rollback stages (asReversed().map would lose the results
    // already computed and abort the whole rollback).
    private suspend fun rollbackFromSnapshot(
        snapshot: ResetSnapshot,
        attempted: List<ResetStage>,
        applied: List<ResetStage>,
    ): List<RollbackStageResult> {
        val results = mutableListOf<RollbackStageResult>()
        for (stage in attempted.asReversed()) {
            val result =
                try {
                    restoreStage(stage, snapshot, stageWasApplied = stage in applied)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    RollbackStageResult.Failure(stage, safeResetReason(error, "Rollback failed"))
                }
            results += result
        }
        return results
    }

    private suspend fun restoreStage(
        stage: ResetStage,
        snapshot: ResetSnapshot,
        stageWasApplied: Boolean,
    ): RollbackStageResult =
        when (stage) {
            ResetStage.Config -> restoreConfig(snapshot.config)
            ResetStage.SetupInput -> restoreSetupInput(configRepository, snapshot.setupInput)
            ResetStage.Forwards -> restoreForwards(snapshot.forwards, stageWasApplied)
        }

    // FIX8 P0-006-A: byte-exact restore via the same primitive SetupPersistenceCoordinator/
    // ForwardConfigurationCoordinator use for config — unlike a String-converted
    // writeConfigAtomically call, this never round-trips through UTF-8, so non-UTF-8 config
    // bytes survive a snapshot/restore cycle intact.
    private suspend fun restoreConfig(snapshot: ExactFileSnapshot): RollbackStageResult =
        configRepository.restoreConfigSnapshot(snapshot).fold(
            onSuccess = { RollbackStageResult.Success(ResetStage.Config) },
            onFailure = { error ->
                RollbackStageResult.Failure(ResetStage.Config, safeResetReason(error, "Failed to restore config"))
            },
        )

    // FIX8 P0-006-A: byte-exact, revision-checked restore (mirroring every other transaction's
    // Forwards stage) instead of re-saving a plain re-serializable list.
    private suspend fun restoreForwards(
        snapshot: ForwardsTransactionSnapshot,
        stageWasApplied: Boolean,
    ): RollbackStageResult {
        val result = forwardsRepository.restoreForTransaction(snapshot, stageWasApplied)
        return if (result.isSuccess) {
            RollbackStageResult.Success(ResetStage.Forwards)
        } else {
            RollbackStageResult.Failure(
                ResetStage.Forwards,
                safeResetReason(result.exceptionOrNull(), "Failed to restore forwards"),
            )
        }
    }
}

// FIX7 P0-005: top-level (not TransactionalResetCoordinator members) to keep that class under
// detekt's TooManyFunctions threshold — both are self-contained given their explicit parameters.

private suspend fun restoreSetupInput(
    configRepository: ConfigRepository,
    snapshot: ExactFileSnapshot,
): RollbackStageResult {
    val result = configRepository.restoreSetupInputFileSnapshot(snapshot)
    return if (result.isSuccess) {
        RollbackStageResult.Success(ResetStage.SetupInput)
    } else {
        RollbackStageResult.Failure(
            ResetStage.SetupInput,
            safeResetReason(result.exceptionOrNull(), "Failed to restore setup input"),
        )
    }
}

// P1-002-B: single redaction chokepoint for every reset/rollback reason.
private fun safeResetReason(
    error: Throwable?,
    fallback: String,
): String = SensitiveDataRedactor.redactText(error?.message ?: fallback)

/**
 * Reset stages in order.
 */
private val resetStages =
    listOf(
        ResetStage.Config,
        ResetStage.SetupInput,
        ResetStage.Forwards,
    )
