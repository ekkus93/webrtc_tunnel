package com.phillipchin.webrtctunnel.data

import com.phillipchin.webrtctunnel.model.ServiceState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

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

    // P1-007-A: true once the processor has exited (normally, by cancellation, or because a
    // handler/reporter failure tore it down). Guards restart; the processor's finally closes the
    // channel before flipping this, so a late trySubmit is a benign drop, not acceptance.
    private val stopped = AtomicBoolean(false)

    // P1-002-B: true once [stop] has been called, set BEFORE it cancels the processor job — so
    // the processor's own completion (in [start]'s finally) can tell an expected teardown apart
    // from an unexpected death (a propagated cancellation not caused by our own [stop], a fatal
    // Throwable from a handler, or a throwing [CoordinatorOperations.onError] reporter).
    private val stopRequested = AtomicBoolean(false)

    // P2-001: read-only test signal — true once the command processor has exited (e.g. a handler
    // cancellation tore it down), so tests wait on this deterministic event instead of a sleep.
    internal val isStoppedForTest: Boolean get() = stopped.get()

    /**
     * Starts the command processor. Must be called exactly once, and never after [stop].
     */
    fun start() {
        check(processorJob == null) {
            "Lifecycle coordinator already started"
        }
        check(!stopped.get()) {
            "Lifecycle coordinator cannot be restarted after stop"
        }
        processorJob =
            scope.launch {
                try {
                    processCommands()
                } finally {
                    // P1-007-B / Q14: close command acceptance the moment the processor exits —
                    // whether it completed, was cancelled, or a handler/reporter failure killed
                    // it — so no command is ever accepted without a live processor. Close before
                    // setting the flag so trySubmit's trySend is the single source of truth for
                    // acceptance.
                    commands.close()
                    stopped.set(true)
                    // P1-002-B: an exit nobody requested via [stop] is a processor death, not a
                    // teardown — tell the owner so it can quarantine any possibly-still-active
                    // native runtime instead of silently leaving the service uncontrolled.
                    // Synchronous (not suspend): safe to call here even though this coroutine may
                    // itself be in the middle of being cancelled.
                    if (!stopRequested.get()) {
                        lifecycleOps.onProcessorFailed()
                    }
                }
            }
    }

    /**
     * Stops the command processor by closing the channel and cancelling the processor.
     * Idempotent: safe to call more than once.
     */
    suspend fun stop() {
        // P1-002-B: set BEFORE closing/cancelling, so the finally above can tell this expected
        // teardown apart from an unexpected death.
        stopRequested.set(true)
        stopped.set(true)
        commands.close()
        val job = processorJob
        processorJob = null
        job?.cancelAndJoin()
    }

    /**
     * Submit a lifecycle command. Commands are processed in FIFO order.
     * Critical commands (STOP, PAUSE, StartupCompleted) are never dropped while running.
     *
     * Deliberately non-suspending, and callers must invoke it inline rather than from a
     * coroutine launched per command. [commands] is UNLIMITED so an enqueue can never need
     * to wait, and enqueueing inline is what actually makes execution order match the order
     * the caller accepted the intents: a `launch { submit(cmd) }` per command produces
     * independent coroutines that race to enqueue on a multi-threaded dispatcher, so a
     * later command can overtake an earlier one (e.g. STOP overtaking START) even though
     * the processor itself drains strictly FIFO.
     *
     * Returns false once [stop] has closed the channel, making a late submit during
     * teardown a benign, reportable drop instead of a ClosedSendChannelException thrown
     * into a detached coroutine — [stop] closes the channel before the caller cancels any
     * in-flight startup, so that startup's StartupCompleted can legitimately arrive here
     * after shutdown has begun.
     */
    fun trySubmit(command: LifecycleCommand): Boolean = commands.trySend(command).isSuccess

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
        } catch (error: Exception) {
            // P1-007-C: recoverable exceptions are published and processing continues. A fatal
            // Error is NOT caught here — it propagates, the processor exits, and its finally
            // closes command acceptance rather than normalizing the failure to a lifecycle error.
            // If onError itself throws, that too propagates and stops the processor.
            lifecycleOps.onError(
                SensitiveDataRedactor.redactText(error.message ?: "Lifecycle command failed"),
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

    // P1-002-B: the command processor exited without [TunnelLifecycleCoordinator.stop] having
    // been requested — an unexpected death (propagated cancellation not from our own stop, a
    // fatal Throwable from a handler, or a throwing onError reporter). The native runtime may
    // still be active with nothing left to control it, so the owner must quarantine and stop
    // accepting start/resume, not merely log the loss.
    // A property (not a function): this interface is at detekt's TooManyFunctions threshold.
    val onProcessorFailed: () -> Unit

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
