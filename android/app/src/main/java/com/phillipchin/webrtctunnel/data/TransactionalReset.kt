package com.phillipchin.webrtctunnel.data

import com.phillipchin.webrtctunnel.model.SetupConfigInput
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Transactional configuration reset.
 *
 * Provides atomic multi-file reset with rollback capability.
 * Each reset stage is captured independently, and on failure,
 * the coordinator attempts to rollback to the previous state.
 */
class TransactionalResetCoordinator(
    private val configRepository: ConfigRepository,
    private val forwardsRepository: ForwardsRepository,
) {
    private val resetMutex = Mutex()

    /**
     * Performs a transactional reset of the configuration.
     * On partial failure, attempts to rollback to the previous state.
     */
    suspend fun resetConfiguration(): ResetResult {
        return resetMutex.withLock {
            val stageOutcomes = mutableListOf<StageOutcome>()

            // Config stage
            val configOutcome = runCatching {
                configRepository.writeConfigAtomically(configRepository.defaultConfigTemplate())
            }.fold(
                onSuccess = { StageOutcome.Success(Stage.Config) },
                onFailure = { error -> StageOutcome.Failure(Stage.Config, error.message ?: "unknown") }
            )
            stageOutcomes.add(configOutcome)

            // Setup input stage
            val setupOutcome = runCatching {
                configRepository.saveSetupInput(SetupConfigInput())
            }.fold(
                onSuccess = { StageOutcome.Success(Stage.SetupInput) },
                onFailure = { error -> StageOutcome.Failure(Stage.SetupInput, error.message ?: "unknown") }
            )
            stageOutcomes.add(setupOutcome)

            // Forwards stage
            val forwardsOutcome = runCatching {
                forwardsRepository.resetForwards()
            }.fold(
                onSuccess = { StageOutcome.Success(Stage.Forwards) },
                onFailure = { error -> StageOutcome.Failure(Stage.Forwards, error.message ?: "unknown") }
            )
            stageOutcomes.add(forwardsOutcome)

            // Check for any failures
            val failures = stageOutcomes.filterIsInstance<StageOutcome.Failure>()

            if (failures.isEmpty()) {
                ResetResult.Success
            } else {
                // Attempt rollback in reverse order
                for (stageOutcome in stageOutcomes.asReversed()) {
                    if (stageOutcome is StageOutcome.Success) {
                        when (stageOutcome.stage) {
                            Stage.Config -> {
                                configRepository.writeConfigAtomically(configRepository.defaultConfigTemplate())
                            }
                            Stage.SetupInput -> {
                                configRepository.saveSetupInput(SetupConfigInput())
                            }
                            Stage.Forwards -> {
                                forwardsRepository.resetForwards()
                            }
                        }
                    }
                }

                ResetResult.PartialFailure(
                    failedStages = failures.map { it.stage.name to it.message }
                )
            }
        }
    }

    }

/**
 * Reset stages in execution order.
 */
enum class Stage {
    Config,
    SetupInput,
    Forwards
}

/**
 * Outcome for each reset stage.
 */
sealed class StageOutcome {
    data class Success(val stage: Stage) : StageOutcome()
    data class Failure(val stage: Stage, val message: String) : StageOutcome()
}

/**
 * Result of the transactional reset.
 */
sealed class ResetResult {
    data object Success : ResetResult()
    data class PartialFailure(val failedStages: List<Pair<String, String>>) : ResetResult()
}
