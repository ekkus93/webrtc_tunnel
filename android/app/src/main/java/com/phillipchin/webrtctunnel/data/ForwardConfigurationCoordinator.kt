package com.phillipchin.webrtctunnel.data

import com.phillipchin.webrtctunnel.model.ForwardConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Stages of a forward-activation commit. Config is committed last so an earlier failure never
 * leaves an active config referencing a forwards list that did not persist.
 */
enum class ForwardConfigurationStage {
    Forwards,
    Config,
}

sealed interface ForwardConfigurationRollbackStageResult {
    data class Success(val stage: ForwardConfigurationStage) : ForwardConfigurationRollbackStageResult

    /** A restore was deliberately not applied because a mutation from outside this
     * transaction committed after this transaction captured its snapshot — reverting
     * would silently discard that newer, legitimate change. */
    data class Skipped(
        val stage: ForwardConfigurationStage,
        val reason: String,
    ) : ForwardConfigurationRollbackStageResult

    data class Failure(
        val stage: ForwardConfigurationStage,
        val reason: String,
    ) : ForwardConfigurationRollbackStageResult
}

sealed interface ForwardConfigurationResult {
    data object Success : ForwardConfigurationResult

    data class Failed(
        val failedStage: ForwardConfigurationStage,
        val reason: String,
        val rollback: List<ForwardConfigurationRollbackStageResult>,
    ) : ForwardConfigurationResult
}

/** Thrown (as a suppressed exception on the propagating [CancellationException]) when a
 * cancelled forward-activation's rollback could not fully restore one stage. */
class ForwardConfigurationRollbackException(
    val stage: ForwardConfigurationStage,
    message: String,
) : Exception(message)

/**
 * FIX8 P0-005-D: commits a validated forward list and its rendered config atomically.
 *
 * Callers must build and validate the proposed forwards list, and render+native-validate the
 * candidate config, entirely in memory/against an isolated candidate file BEFORE calling
 * [apply] (P0-005-C) — this coordinator only ever applies already-validated inputs, exactly
 * like [SetupPersistenceCoordinator] does not itself validate.
 *
 * Captures the exact prior state of both resources before the first mutation, applies Forwards
 * then Config, stops at the first failure, and rolls every already-attempted stage back in
 * reverse order — continuing after an individual rollback failure and reporting each outcome.
 */
class ForwardConfigurationCoordinator(
    private val forwardsRepository: ForwardsRepository,
    private val configRepository: ConfigRepository,
) {
    private val mutex = Mutex()

    private class ForwardConfigurationSnapshot(
        val forwards: ForwardsTransactionSnapshot,
        val config: ExactFileSnapshot,
    )

    suspend fun apply(
        forwards: List<ForwardConfig>,
        configContents: String,
    ): ForwardConfigurationResult =
        mutex.withLock {
            val snapshot =
                try {
                    captureSnapshot()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    return@withLock ForwardConfigurationResult.Failed(
                        failedStage = ForwardConfigurationStage.Forwards,
                        reason = safeReason(error, "Failed to capture forward configuration snapshot"),
                        rollback = emptyList(),
                    )
                }

            val attempted = mutableListOf<ForwardConfigurationStage>()
            val applied = mutableListOf<ForwardConfigurationStage>()
            try {
                for (stage in STAGES) {
                    attempted += stage
                    val result = applyStage(stage, forwards, configContents)
                    if (result.isFailure) {
                        return@withLock ForwardConfigurationResult.Failed(
                            failedStage = stage,
                            reason = safeReason(result.exceptionOrNull(), "Failed to apply forward configuration"),
                            rollback = withContext(NonCancellable) { rollback(snapshot, attempted, applied) },
                        )
                    }
                    applied += stage
                }
                ForwardConfigurationResult.Success
            } catch (cancelled: CancellationException) {
                val rollbackResults = withContext(NonCancellable) { rollback(snapshot, attempted, applied) }
                rollbackResults.filterIsInstance<ForwardConfigurationRollbackStageResult.Failure>().forEach { failure ->
                    cancelled.addSuppressed(ForwardConfigurationRollbackException(failure.stage, failure.reason))
                }
                throw cancelled
            }
        }

    private suspend fun captureSnapshot(): ForwardConfigurationSnapshot =
        ForwardConfigurationSnapshot(
            forwards = forwardsRepository.captureForTransaction().getOrThrow(),
            config = configRepository.captureFilesSnapshot().getOrThrow().config,
        )

    private suspend fun applyStage(
        stage: ForwardConfigurationStage,
        forwards: List<ForwardConfig>,
        configContents: String,
    ): Result<Unit> =
        mutationResult {
            when (stage) {
                ForwardConfigurationStage.Forwards -> forwardsRepository.replaceForTransaction(forwards).getOrThrow()
                ForwardConfigurationStage.Config -> configRepository.writeConfigAtomically(configContents).getOrThrow()
            }
        }

    private suspend fun rollback(
        snapshot: ForwardConfigurationSnapshot,
        attempted: List<ForwardConfigurationStage>,
        applied: List<ForwardConfigurationStage>,
    ): List<ForwardConfigurationRollbackStageResult> =
        attempted.asReversed().map { stage ->
            try {
                restoreStage(stage, snapshot, stageWasApplied = stage in applied)
                ForwardConfigurationRollbackStageResult.Success(stage)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (mismatch: ForwardsRevisionMismatchException) {
                ForwardConfigurationRollbackStageResult.Skipped(stage, safeReason(mismatch, "Forwards changed again"))
            } catch (error: Exception) {
                ForwardConfigurationRollbackStageResult.Failure(stage, safeReason(error, "Rollback failed"))
            }
        }

    private suspend fun restoreStage(
        stage: ForwardConfigurationStage,
        snapshot: ForwardConfigurationSnapshot,
        stageWasApplied: Boolean,
    ) {
        when (stage) {
            ForwardConfigurationStage.Forwards ->
                forwardsRepository.restoreForTransaction(snapshot.forwards, stageWasApplied).getOrThrow()
            ForwardConfigurationStage.Config -> configRepository.restoreConfigSnapshot(snapshot.config).getOrThrow()
        }
    }

    private fun safeReason(
        error: Throwable?,
        fallback: String,
    ): String = SensitiveDataRedactor.redactText(error?.message ?: fallback)

    private companion object {
        val STAGES = listOf(ForwardConfigurationStage.Forwards, ForwardConfigurationStage.Config)
    }
}
