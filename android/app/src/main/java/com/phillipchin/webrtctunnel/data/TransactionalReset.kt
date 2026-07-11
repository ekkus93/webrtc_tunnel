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

            // Step 2: perform reset stages in order, stopping on first failure
            val mutatedStages = mutableListOf<ResetStage>()

            for (stage in resetStages) {
                val outcome = resetStage(stage)
                if (outcome is ResetStageResult.Failure) {
                    // Stop immediately and rollback only the stages that already mutated
                    val rollbackResults = rollbackFromSnapshot(snapshot, mutatedStages)
                    return@withLock ResetResult.Failed(
                        failedStage = stage,
                        cause = outcome.reason,
                        rollback = rollbackResults,
                    )
                }
                mutatedStages.add(stage)
            }

            // All stages succeeded
            val stageResults =
                resetStages.map { stage ->
                    ResetStageResult.Success(stage)
                }
            ResetResult.Success(stageResults)
        }
    }

    private suspend fun resetStage(stage: ResetStage): ResetStageResult =
        when (stage) {
            ResetStage.Config ->
                configRepository.writeConfigAtomically(configRepository.defaultConfigTemplate()).fold(
                    onSuccess = { ResetStageResult.Success(ResetStage.Config) },
                    onFailure = { error ->
                        ResetStageResult.Failure(
                            ResetStage.Config,
                            error.message ?: "unknown",
                        )
                    },
                )

            ResetStage.SetupInput ->
                runCatching {
                    configRepository.saveSetupInput(SetupConfigInput())
                    ResetStageResult.Success(ResetStage.SetupInput)
                }.getOrElse {
                    ResetStageResult.Failure(ResetStage.SetupInput, it.message ?: "unknown")
                }

            ResetStage.Forwards ->
                forwardsRepository.resetForwards().fold(
                    onSuccess = { ResetStageResult.Success(ResetStage.Forwards) },
                    onFailure = { error ->
                        ResetStageResult.Failure(
                            ResetStage.Forwards,
                            error.message ?: "unknown",
                        )
                    },
                )
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
        mutatedStages: List<ResetStage>,
    ): List<RollbackStageResult> {
        return mutatedStages.asReversed().map { stage ->
            when (stage) {
                ResetStage.Config -> restoreConfig(snapshot.configToml)
                ResetStage.SetupInput -> restoreSetupInput(snapshot.setupInput)
                ResetStage.Forwards -> restoreForwards(snapshot.forwards)
            }
        }
    }

    private suspend fun restoreConfig(priorConfig: String?): RollbackStageResult {
        return if (priorConfig != null) {
            configRepository.writeConfigAtomically(priorConfig).fold(
                onSuccess = {
                    RollbackStageResult.Success(ResetStage.Config)
                },
                onFailure = { error ->
                    RollbackStageResult.Failure(
                        ResetStage.Config,
                        error.message ?: "unknown",
                    )
                },
            )
        } else {
            // Config was absent before reset — must delete it
            configRepository.deleteConfigFileForTransactionalReset().fold(
                onSuccess = {
                    RollbackStageResult.Success(ResetStage.Config)
                },
                onFailure = { error ->
                    RollbackStageResult.Failure(
                        ResetStage.Config,
                        error.message ?: "unknown",
                    )
                },
            )
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
        // Always restore forwards, even if empty — empty is a valid state that must be persisted
        val result = forwardsRepository.restoreForTransactionalReset(forwards)
        return if (result.isSuccess) {
            RollbackStageResult.Success(ResetStage.Forwards)
        } else {
            RollbackStageResult.Failure(
                ResetStage.Forwards,
                result.exceptionOrNull()?.message ?: "unknown",
            )
        }
    }
}

/**
 * Reset stages in order.
 */
private val resetStages =
    listOf(
        ResetStage.Config,
        ResetStage.SetupInput,
        ResetStage.Forwards,
    )
