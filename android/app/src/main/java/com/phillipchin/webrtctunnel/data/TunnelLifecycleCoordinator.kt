package com.phillipchin.webrtctunnel.data

import com.phillipchin.webrtctunnel.model.ServiceState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Lifecycle coordinator that owns the command processor lifetime.
 *
 * P0-003: Service provides the CoroutineScope, so the coordinator cannot outlive the service.
 * Commands are submitted through an unlimited channel to prevent lossy semantics.
 * The processor can be cancelled on teardown.
 */
class TunnelLifecycleCoordinator(
    private val lifecycleOps: CoordinatorOperations,
    private val scope: CoroutineScope,
) {
    private val commands = Channel<LifecycleCommand>(Channel.UNLIMITED)

    private var processorJob: Job? = null

    /**
     * Starts the command processor. Must be called exactly once.
     */
    fun start() {
        check(processorJob == null) {
            "Lifecycle coordinator already started"
        }
        processorJob =
            scope.launch {
                processCommands()
            }
    }

    /**
     * Stops the command processor by closing the channel and cancelling the processor.
     */
    suspend fun stop() {
        commands.close()
        val job = processorJob
        processorJob = null
        job?.cancelAndJoin()
    }

    /**
     * Submit a lifecycle command. Commands are processed in FIFO order.
     * Critical commands (STOP, PAUSE, StartupCompleted) are never dropped.
     */
    suspend fun submit(command: LifecycleCommand) {
        commands.send(command)
    }

    private suspend fun processCommands() {
        for (command in commands) {
            processCommand(command)
        }
    }

    private suspend fun processCommand(command: LifecycleCommand) {
        try {
            handleCommand(command)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            lifecycleOps.onError(
                error.message ?: "Lifecycle command failed",
                "lifecycle_command_failed",
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
sealed class LifecycleCommand {
    object StartOffer : LifecycleCommand()

    object Pause : LifecycleCommand()

    object Resume : LifecycleCommand()

    object Stop : LifecycleCommand()

    object AllowMeteredSession : LifecycleCommand()

    data class PolicyBlocked(val reason: String) : LifecycleCommand()

    object PolicyAllowed : LifecycleCommand()

    data class RetryPolicyResume(val expectedGeneration: Long) : LifecycleCommand()

    data class StartupCompleted(val generation: Long, val outcome: StartOutcome) : LifecycleCommand()
}
