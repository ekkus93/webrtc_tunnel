package com.phillipchin.webrtctunnel.data

import com.phillipchin.webrtctunnel.model.ForwardConfig
import com.phillipchin.webrtctunnel.model.SetupConfigInput
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Snapshot of the exact state before a transactional reset begins.
 * Used to restore prior state on rollback (P0-001).
 */
data class ResetSnapshot(
    val configToml: String?,
    val setupInput: SetupConfigInput,
    val forwards: List<ForwardConfig>,
)

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
) {
    private val resetMutex = Mutex()

    suspend fun resetConfiguration(): ResetResult {
        return resetMutex.withLock {
            // Step 1: capture exact prior state (P0-001 snapshot)
            val snapshot = captureSnapshot()

            // Step 2: perform reset stages in order
            val stageResults = mutableListOf<ResetStageResult>()

            // Config stage — wrap Unit-returning write in Result for error reporting
            val configOutcome: ResetStageResult =
                runCatching {
                    configRepository.writeConfigAtomically(configRepository.defaultConfigTemplate())
                    ResetStageResult.Success(ResetStage.Config)
                }.getOrElse {
                    ResetStageResult.Failure(ResetStage.Config, it.message ?: "unknown")
                }
            stageResults.add(configOutcome)

            // Setup input stage
            val setupResult: ResetStageResult =
                runCatching {
                    configRepository.saveSetupInput(SetupConfigInput())
                    ResetStageResult.Success(ResetStage.SetupInput)
                }.getOrElse {
                    ResetStageResult.Failure(ResetStage.SetupInput, it.message ?: "unknown")
                }
            stageResults.add(setupResult)

            // Forwards stage — explicitly fold the inner Result, no nested runCatching (P0-001)
            val resetForwardsResult = forwardsRepository.resetForwards()
            val forwardsOutcome: ResetStageResult =
                resetForwardsResult.fold(
                    onSuccess = { ResetStageResult.Success(ResetStage.Forwards) },
                    onFailure = { error ->
                        ResetStageResult.Failure(
                            ResetStage.Forwards,
                            error.message ?: "unknown",
                        )
                    },
                )
            stageResults.add(forwardsOutcome)

            // Check for failures
            val firstFailure = stageResults.firstOrNull { it is ResetStageResult.Failure } as? ResetStageResult.Failure
            if (firstFailure == null) {
                return ResetResult.Success(stageResults)
            }

            // Step 3: rollback from snapshot in reverse order
            val rollbackResults = rollbackFromSnapshot(snapshot, stageResults)

            ResetResult.Failed(
                failedStage = firstFailure.stage,
                cause = firstFailure.reason,
                rollback = rollbackResults,
            )
        }
    }

    private fun captureSnapshot(): ResetSnapshot {
        return ResetSnapshot(
            configToml = configRepository.readConfig().takeIf { it.isNotBlank() },
            setupInput = configRepository.loadSetupInputResult().getOrDefault(SetupConfigInput()),
            forwards = forwardsRepository.current(),
        )
    }

    private suspend fun rollbackFromSnapshot(
        snapshot: ResetSnapshot,
        mutatedStages: List<ResetStageResult>,
    ): List<RollbackStageResult> {
        val mutated =
            mutatedStages
                .filterIsInstance<ResetStageResult.Success>()
                .map { it.stage }
        return mutated.asReversed().map { stage ->
            when (stage) {
                ResetStage.Config -> restoreConfig(snapshot.configToml)
                ResetStage.SetupInput -> restoreSetupInput(snapshot.setupInput)
                ResetStage.Forwards -> restoreForwards(snapshot.forwards)
            }
        }
    }

    private suspend fun restoreConfig(priorConfig: String?): RollbackStageResult {
        return if (priorConfig != null) {
            runCatching {
                configRepository.writeConfigAtomically(priorConfig)
                RollbackStageResult.Success(ResetStage.Config)
            }.getOrElse {
                RollbackStageResult.Failure(ResetStage.Config, it.message ?: "unknown")
            }
        } else {
            RollbackStageResult.Success(ResetStage.Config)
        }
    }

    private fun restoreSetupInput(input: SetupConfigInput): RollbackStageResult {
        return runCatching {
            configRepository.saveSetupInput(input)
            RollbackStageResult.Success(ResetStage.SetupInput)
        }.getOrElse {
            RollbackStageResult.Failure(ResetStage.SetupInput, it.message ?: "unknown")
        }
    }

    private suspend fun restoreForwards(forwards: List<ForwardConfig>): RollbackStageResult {
        return if (forwards.isEmpty()) {
            RollbackStageResult.Success(ResetStage.Forwards)
        } else {
            val result = forwardsRepository.save(forwards)
            if (result.isSuccess) {
                RollbackStageResult.Success(ResetStage.Forwards)
            } else {
                RollbackStageResult.Failure(
                    ResetStage.Forwards,
                    result.exceptionOrNull()?.message ?: "unknown",
                )
            }
        }
    }
}
