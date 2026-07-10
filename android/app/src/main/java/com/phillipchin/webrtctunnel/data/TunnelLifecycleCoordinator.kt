package com.phillipchin.webrtctunnel.data

import com.phillipchin.webrtctunnel.model.ServiceState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Tunnel lifecycle coordinator.
 *
 * Extracts the core lifecycle coordination logic from TunnelForegroundService,
 * managing ordered command processing.
 *
 * Lifecycle operations are delegated through [lifecycleOps].
 */
class TunnelLifecycleCoordinator(
    private val lifecycleOps: CoordinatorOperations,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val commands = Channel<LifecycleCommand>(COMMAND_CAPACITY)

    fun startCommandProcessor(): Job {
        return scope.launch {
            for (command in commands) {
                runCatching {
                    handleCommand(command)
                }.onFailure { error ->
                    if (error !is CancellationException) {
                        lifecycleOps.onError(
                            error.message ?: "Lifecycle command failed",
                            "lifecycle_command_failed",
                        )
                    }
                }
            }
        }
    }

    fun submitCommand(command: LifecycleCommand) {
        val result = commands.trySend(command)
        if (result.isFailure) {
            lifecycleOps.onError(
                "Unable to queue lifecycle command ${command::class.java.simpleName}",
                "lifecycle_command_queue_failed",
            )
        }
    }

    private suspend fun handleCommand(command: LifecycleCommand) {
        when (command) {
            LifecycleCommand.StartOffer -> lifecycleOps.startOffer()
            LifecycleCommand.Pause -> lifecycleOps.pause()
            LifecycleCommand.Resume -> lifecycleOps.resume()
            LifecycleCommand.Stop -> lifecycleOps.stop()
            LifecycleCommand.AllowMeteredSession ->
                lifecycleOps.allowMeteredForSessionAndStart()
            is LifecycleCommand.PolicyBlocked ->
                lifecycleOps.pauseForPolicy(command.reason)
            LifecycleCommand.PolicyAllowed -> lifecycleOps.handlePolicyAllowed()
            is LifecycleCommand.RetryPolicyResume ->
                lifecycleOps.handleRetryPolicyResume(command.expectedGeneration)
            is LifecycleCommand.StartupCompleted ->
                lifecycleOps.handleStartupCompleted(command.generation, command.outcome)
        }
    }

    fun stop() {
        scope.cancel()
    }

    private companion object {
        private const val COMMAND_CAPACITY = 32
    }
}

/**
 * Operations the coordinator delegates to for lifecycle command processing.
 * Implementation is provided by TunnelForegroundService.
 */
interface CoordinatorOperations {
    fun onError(
        message: String,
        code: String,
        state: ServiceState = ServiceState.Error,
    )

    suspend fun startOffer()

    suspend fun pause()

    suspend fun resume()

    suspend fun stop()

    suspend fun allowMeteredForSessionAndStart()

    suspend fun pauseForPolicy(reason: String)

    suspend fun handlePolicyAllowed()

    suspend fun handleRetryPolicyResume(expectedGeneration: Long)

    suspend fun handleStartupCompleted(
        generation: Long,
        outcome: StartOutcome,
    )
}

/**
 * Ordered lifecycle commands for the tunnel.
 */
sealed interface LifecycleCommand {
    data object StartOffer : LifecycleCommand

    data object Pause : LifecycleCommand

    data object Resume : LifecycleCommand

    data object Stop : LifecycleCommand

    data object AllowMeteredSession : LifecycleCommand

    data class PolicyBlocked(val reason: String) : LifecycleCommand

    data object PolicyAllowed : LifecycleCommand

    data class RetryPolicyResume(val expectedGeneration: Long) : LifecycleCommand

    data class StartupCompleted(val generation: Long, val outcome: StartOutcome) : LifecycleCommand
}

/**
 * Platform operations for status reporting and service control.
 */
interface PlatformOperations {
    fun onStatus(message: String)

    fun publishStatus()

    fun startStatusPolling()

    fun stopStatusPolling()

    fun serviceStopForeground()

    fun serviceStopSelf()
}

/**
 * Context for unverified start cleanup.
 */
data class UnverifiedStartContext(
    val originalError: StartStatusVerificationException,
    val startGeneration: Long,
    val lifecycleGeneration: AtomicLong,
    val stopStatusPollingAndJoin: suspend () -> Unit,
    val repositoryStop: suspend () -> Result<Unit>,
    val nativeStopVerified: AtomicBoolean,
    val nativeRuntimeUncertain: AtomicBoolean,
    val publishError: (message: String, code: String) -> Unit,
)

/**
 * Helper function to determine stop failure code.
 */
fun stopFailureCode(error: Throwable): String =
    if (error is StopStatusVerificationException) {
        "stop_status_verification_failed"
    } else {
        "stop_failed"
    }

/**
 * Cleanup for unverified start.
 */
suspend fun cleanupUnverifiedStart(context: UnverifiedStartContext) {
    if (context.lifecycleGeneration.get() != context.startGeneration) return
    context.stopStatusPollingAndJoin()
    context.repositoryStop().fold(
        onSuccess = {
            context.nativeStopVerified.set(true)
            context.publishError(
                context.originalError.message ?: "Native startup could not be verified",
                "start_status_verification_failed",
            )
        },
        onFailure = { cleanupError ->
            context.nativeStopVerified.set(false)
            context.nativeRuntimeUncertain.set(true)
            context.publishError(
                buildString {
                    append(context.originalError.message ?: "Native startup could not be verified")
                    append(". Cleanup also failed: ")
                    append(cleanupError.message ?: "unknown cleanup failure")
                },
                "start_verification_cleanup_failed",
            )
        },
    )
}
