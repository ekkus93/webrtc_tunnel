package com.phillipchin.webrtctunnel.data

import com.phillipchin.webrtctunnel.model.ForwardConfig
import com.phillipchin.webrtctunnel.model.SetupConfigInput
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Thrown when snapshot capture fails during transactional reset.
 * Prevents partial mutation by failing before any stage executes.
 */
class SnapshotCaptureException(
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
 * its snapshot bytes are secret-bearing and must be wiped once the transaction finishes
 * (see [ResetSnapshot.wipeSecrets]).
 */
class ResetSnapshot(
    val config: ExactFileSnapshot,
    val setupInput: ExactFileSnapshot,
    val forwards: List<ForwardConfig>,
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
 * Captures exact prior state before any mutation. On failure, restores from snapshot
 * rather than re-running reset operations. Every rollback stage is reported per-stage.
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

    suspend fun resetConfiguration(): ResetResult =
        resetMutex.withLock {
            // Step 1: capture exact prior state (P0-001 snapshot)
            val snapshot =
                captureSnapshot().getOrElse { error ->
                    // Snapshot capture failed — abort before any mutation
                    return@withLock ResetResult.Failed(
                        failedStage = ResetStage.Config,
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
        val mutatedStages = mutableListOf<ResetStage>()
        val firstFailure =
            try {
                applyStages(mutatedStages)
            } catch (cancelled: CancellationException) {
                val rollbackResults = withContext(NonCancellable) { rollbackFromSnapshot(snapshot, mutatedStages) }
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
                rollback = withContext(NonCancellable) { rollbackFromSnapshot(snapshot, mutatedStages) },
            )
        }
    }

    private suspend fun applyStages(mutatedStages: MutableList<ResetStage>): ResetStageResult.Failure? {
        for (stage in resetStages) {
            val outcome = resetStage(stage)
            if (outcome is ResetStageResult.Failure) {
                return outcome
            }
            mutatedStages.add(stage)
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

    // P1-001: explicit try/catch (not runCatching) — mutation paths that can affect
    // persistent state must rethrow cancellation rather than swallow it.
    private fun resetSetupInputStage(): ResetStageResult {
        return try {
            configRepository.saveSetupInput(SetupConfigInput())
            ResetStageResult.Success(ResetStage.SetupInput)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            ResetStageResult.Failure(
                stage = ResetStage.SetupInput,
                reason = safeResetReason(error, "Failed to reset setup input"),
            )
        }
    }

    // P1-002-A: contain every snapshot read so a read failure aborts before any mutation
    // instead of throwing out of the coordinator mid-reset. readConfig()/current() are not
    // Result-returning, so the whole capture is guarded, not just the setup-input read.
    //
    // FIX7 P0-005-A/B: config and setup-input are captured as exact ExactFileSnapshots (bytes +
    // existed) rather than a parsed value, so an absent file restores as absent instead of as a
    // default-valued one (CRITICAL-3). Config still goes through the existing configFileExists/
    // readConfig() seam (unchanged from FIX6/P1-002, so existing read-failure/cancellation fault
    // injection via a ConfigRepository subclass still works) rather than a raw file read, and is
    // wrapped into an ExactFileSnapshot here. The corrupt-JSON-detection contract this
    // coordinator has always had is preserved by still requiring loadSetupInputResult() to parse
    // successfully — that call's *value* is discarded; only its success/failure gates the
    // snapshot.
    private fun captureSnapshot(): Result<ResetSnapshot> =
        try {
            val configExisted = configRepository.configFileExists
            // readConfig() is called unconditionally (not gated on configExisted) so a
            // subclass-injected read failure/cancellation still fires regardless of whether the
            // file happens to exist — matching the pre-P0-005 capture path exactly.
            val configContents = configRepository.readConfig()
            val configSnapshot =
                ExactFileSnapshot(
                    existed = configExisted,
                    bytes = if (configExisted) configContents.toByteArray() else null,
                )
            configRepository.loadSetupInputResult().getOrThrow()
            val setupInputSnapshot =
                captureSetupInputFileSnapshot(configRepository.setupInputFileForSnapshot, setupInputReadBytes)
                    .getOrThrow()
            val forwards = forwardsRepository.current()
            Result.success(ResetSnapshot(configSnapshot, setupInputSnapshot, forwards))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Result.failure(SnapshotCaptureException("Failed to capture reset snapshot", error))
        }

    // P1-002-C: an explicit loop, so one restore stage throwing is recorded as a Failure and
    // never suppresses the remaining rollback stages (asReversed().map would lose the results
    // already computed and abort the whole rollback).
    private suspend fun rollbackFromSnapshot(
        snapshot: ResetSnapshot,
        mutatedStages: List<ResetStage>,
    ): List<RollbackStageResult> {
        val results = mutableListOf<RollbackStageResult>()
        for (stage in mutatedStages.asReversed()) {
            val result =
                try {
                    restoreStage(stage, snapshot)
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
    ): RollbackStageResult =
        when (stage) {
            ResetStage.Config -> restoreConfig(snapshot.config)
            ResetStage.SetupInput -> restoreSetupInput(configRepository, snapshot.setupInput)
            ResetStage.Forwards -> restoreForwards(snapshot.forwards)
        }

    private suspend fun restoreConfig(snapshot: ExactFileSnapshot): RollbackStageResult {
        return if (snapshot.existed) {
            // File existed — restore the exact contents (even if blank/whitespace).
            val contents =
                String(requireNotNull(snapshot.bytes) { "config snapshot bytes are missing" }, Charsets.UTF_8)
            configRepository.writeConfigAtomically(contents).fold(
                onSuccess = { RollbackStageResult.Success(ResetStage.Config) },
                onFailure = { error ->
                    RollbackStageResult.Failure(ResetStage.Config, safeResetReason(error, "Failed to restore config"))
                },
            )
        } else {
            // File was absent — must delete to restore absent state.
            configRepository.deleteConfigFileForTransactionalReset().fold(
                onSuccess = { RollbackStageResult.Success(ResetStage.Config) },
                onFailure = { error ->
                    RollbackStageResult.Failure(ResetStage.Config, safeResetReason(error, "Failed to restore config"))
                },
            )
        }
    }

    private suspend fun restoreForwards(forwards: List<ForwardConfig>): RollbackStageResult {
        // Always restore forwards, even if empty — empty is a valid state that must be persisted
        val result = forwardsRepository.restoreForTransactionalReset(forwards)
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

private fun restoreSetupInput(
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
