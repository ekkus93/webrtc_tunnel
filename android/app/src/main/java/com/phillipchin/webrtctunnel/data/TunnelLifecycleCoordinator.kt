package com.phillipchin.webrtctunnel.data

import com.phillipchin.webrtctunnel.model.ServiceState
import com.phillipchin.webrtctunnel.model.TunnelMode
import com.phillipchin.webrtctunnel.model.isTunnelActiveOrStarting
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Tunnel lifecycle coordinator.
 *
 * Extracts the core lifecycle coordination logic from TunnelForegroundService,
 * managing:
 * - Ordered command processing (FIFO via bounded channel)
 * - Generation tracking (prevents stale completions)
 * - Startup job lifecycle
 * - Quarantine state (blocks auto-restart after cleanup failures)
 * - Policy pause state
 * - Metered allowance tracking
 *
 * Platform-specific operations (notifications, foreground service lifecycle,
 * JNI calls) are delegated through [PlatformOperations].
 */
class TunnelLifecycleCoordinator(
    private val repository: TunnelRepository,
    private val platformOps: PlatformOperations,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)

    // Ordered command queue (bounded capacity)
    private val commands = Channel<LifecycleCommand>(COMMAND_CAPACITY)

    // Generation tracking for stale completion detection
    private val generation = AtomicLong(0)
    private val mutex = Mutex()

    // Quarantine state - blocks auto-restart after cleanup failures
    private val nativeRuntimeUncertain = AtomicBoolean(false)

    // Policy pause state
    private val pausedByPolicyState = AtomicBoolean(false)

    // Metered allowance for current run
    private val allowMeteredForCurrentRun = AtomicBoolean(false)

    // Tracks verified native stop state
    private val nativeStopVerified = AtomicBoolean(true)

    // Retains one pending retry intention bound to a lifecycle generation
    private val pendingPolicyResumeGeneration =
        java.util.concurrent.atomic.AtomicReference<Long?>(null)

    // Startup job tracking
    private var startupJob: Job? = null

    // Current service state for duplicate start prevention
    private var currentServiceState: ServiceState = ServiceState.Stopped

    /**
     * Returns the current generation value.
     */
    fun currentGeneration(): Long = generation.get()

    /**
     * Returns whether the native runtime is uncertain (quarantined).
     */
    fun isQuarantined(): Boolean = nativeRuntimeUncertain.get()

    /**
     * Returns whether the tunnel is paused by policy.
     */
    fun isPausedByPolicy(): Boolean = pausedByPolicyState.get()

    /**
     * Returns whether a verified native stop has succeeded.
     */
    fun nativeStopVerified(): Boolean = nativeStopVerified.get()

    /**
     * Starts the command processor loop. Returns a Job that can be cancelled to stop processing.
     */
    fun startCommandProcessor(): Job {
        return scope.launch {
            for (command in commands) {
                runCatching {
                    when (command) {
                        is LifecycleCommand.StartOffer -> handleStartOffer()
                        is LifecycleCommand.Pause -> handlePause()
                        is LifecycleCommand.Resume -> handleResume()
                        is LifecycleCommand.Stop -> handleStop()
                        is LifecycleCommand.AllowMeteredSession -> handleAllowMeteredSession()
                        is LifecycleCommand.PolicyBlocked -> handlePolicyBlocked(command.reason)
                        is LifecycleCommand.PolicyAllowed -> handlePolicyAllowed()
                        is LifecycleCommand.RetryPolicyResume ->
                            handleRetryPolicyResume(command.expectedGeneration)
                        is LifecycleCommand.StartupCompleted ->
                            handleStartupCompleted(command)
                    }
                }.onFailure { error ->
                    if (error !is CancellationException) {
                        platformOps.onError(error.message ?: "Lifecycle command failed", "lifecycle_command_failed")
                    }
                }
            }
        }
    }

    /**
     * Submits a lifecycle command to the queue.
     */
    fun submitCommand(command: LifecycleCommand) {
        val result = commands.trySend(command)
        if (result.isFailure) {
            platformOps.onError(
                message = "Unable to queue lifecycle command ${command::class.java.simpleName}",
                code = "lifecycle_command_queue_failed"
            )
        }
    }

    /**
     * Cancels the startup job and waits for it to fully unwind.
     */
    private suspend fun cancelStartupJobAndJoin() {
        val job = startupJob
        startupJob = null
        job?.cancelAndJoin()
    }

    /**
     * Clears the temporary metered allowance so a future run starts fresh.
     */
    private fun clearTemporaryMeteredAllowance() {
        allowMeteredForCurrentRun.set(false)
        repository.updateSessionMeteredAllowance(false)
    }

    /**
     * Stops all command processing and cleanup.
     */
    fun stop() {
        scope.cancel()
    }

    private suspend fun handleStartOffer() {
        // Block duplicate starts in transitional states
        if (currentServiceState.isTunnelActiveOrStarting()) {
            platformOps.onStatus("Already running or starting")
            return
        }

        val newGen = generation.incrementAndGet()
        nativeStopVerified.set(false)

        startupJob = scope.launch {
            // Startup work will be handled through platform operations
            // The actual JNI call happens through the repository
        }
    }

    private suspend fun handlePause() {
        mutex.withLock {
            generation.incrementAndGet()
            cancelStartupJobAndJoin()
            platformOps.stopStatusPolling()

            withContext(ioDispatcher) {
                repository.stop()
            }.fold(
                onSuccess = {
                    nativeStopVerified.set(true)
                    clearTemporaryMeteredAllowance()
                    platformOps.onStatus("Tunnel paused")
                },
                onFailure = { error ->
                    platformOps.onError(
                        message = error.message ?: "Unable to stop tunnel",
                        code = stopFailureCode(error)
                    )
                }
            )
        }
    }

    private suspend fun handleResume() {
        // Resume logic goes here
    }

    private suspend fun handleStop() {
        mutex.withLock {
            generation.incrementAndGet()
            cancelStartupJobAndJoin()
            platformOps.stopStatusPolling()

            val stopResult = withContext(ioDispatcher) {
                repository.stop()
            }

            pausedByPolicyState.set(false)
            clearTemporaryMeteredAllowance()

            stopResult.fold(
                onSuccess = {
                    nativeStopVerified.set(true)
                    nativeRuntimeUncertain.set(false)
                    platformOps.onStatus("Tunnel stopped")
                },
                onFailure = {
                    nativeStopVerified.set(false)
                    nativeRuntimeUncertain.set(true)
                    pendingPolicyResumeGeneration.set(null)
                    platformOps.onError(
                        message = it.message ?: "Unable to stop tunnel cleanly",
                        code = stopFailureCode(it)
                    )
                }
            )
        }
    }

    private suspend fun handleAllowMeteredSession() {
        mutex.withLock {
            allowMeteredForCurrentRun.set(true)
            repository.updateSessionMeteredAllowance(true)
            pausedByPolicyState.set(false)
        }
        handleStartOffer()
    }

    private suspend fun handlePolicyBlocked(reason: String) {
        mutex.withLock {
            generation.incrementAndGet()
            cancelStartupJobAndJoin()
            platformOps.stopStatusPolling()

            withContext(ioDispatcher) {
                repository.stop()
            }.fold(
                onSuccess = {
                    nativeStopVerified.set(true)
                    pausedByPolicyState.set(true)
                    repository.setPolicyBlocked(reason)
                    platformOps.onStatus(reason)
                },
                onFailure = {
                    pausedByPolicyState.set(false)
                    platformOps.onError(
                        message = it.message ?: "Failed stopping tunnel after policy block",
                        code = stopFailureCode(it)
                    )
                }
            )
        }
    }

    private suspend fun handlePolicyAllowed() {
        // Quarantine blocks automatic restart
        if (nativeRuntimeUncertain.get() || !pausedByPolicyState.get()) {
            pendingPolicyResumeGeneration.set(null)
            return
        }

        if (startupJob?.isActive == true) {
            pendingPolicyResumeGeneration.set(generation.get())
        } else {
            pendingPolicyResumeGeneration.set(null)
            handleResume()
        }
    }

    private suspend fun handleRetryPolicyResume(expectedGeneration: Long) {
        // Quarantine blocks automatic restart
        if (nativeRuntimeUncertain.get()) return
        if (generation.get() != expectedGeneration) return

        pendingPolicyResumeGeneration.set(null)
        handleResume()
    }

    private suspend fun handleStartupCompleted(command: LifecycleCommand.StartupCompleted) {
        if (generation.get() != command.generation) {
            // Stale completion: a newer lifecycle command superseded this one
            return
        }

        startupJob = null

        when (val completion = command.completion) {
            is StartupCompletion.VerifiedSuccess -> {
                // Do NOT clear metered allowance on success — it lasts through the run
                pausedByPolicyState.set(false)
                pendingPolicyResumeGeneration.set(null)
                platformOps.publishStatus()
                platformOps.startStatusPolling()
            }
            is StartupCompletion.NativeStartFailure -> {
                clearTemporaryMeteredAllowance()
                val pending = pendingPolicyResumeGeneration.getAndSet(null)
                if (pending == command.generation) {
                    submitCommand(
                        LifecycleCommand.RetryPolicyResume(
                            expectedGeneration = command.generation
                        )
                    )
                } else {
                    platformOps.onError(
                        message = completion.error.message ?: "Unable to start tunnel",
                        code = "native_start_failed"
                    )
                }
            }
            is StartupCompletion.VerificationFailure -> {
                cleanupUnverifiedStart(
                    UnverifiedStartContext(
                        originalError = completion.error,
                        startGeneration = command.generation,
                        lifecycleGeneration = generation,
                        stopStatusPollingAndJoin = platformOps::stopStatusPolling,
                        repositoryStop = { repository.stop() },
                        nativeStopVerified = nativeStopVerified,
                        nativeRuntimeUncertain = nativeRuntimeUncertain,
                        publishError = platformOps::onError
                    )
                )
                clearTemporaryMeteredAllowance()
            }
            is StartupCompletion.UnexpectedFailure -> {
                clearTemporaryMeteredAllowance()
                platformOps.onError(
                    message = completion.error.message ?: "Unexpected startup failure",
                    code = "startup_unexpected_failure"
                )
            }
        }
    }

    companion object {
        private const val COMMAND_CAPACITY = 32
    }
}

/**
 * Ordered lifecycle commands for the tunnel.
 * Bounded capacity channel ensures FIFO ordering.
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
    data class StartupCompleted(
        val generation: Long,
        val completion: StartupCompletion
    ) : LifecycleCommand
}

/**
 * Platform operations interface for the coordinator.
 * Abstracts Android-specific operations (notifications, foreground service, etc.)
 * so the coordinator can be tested without the Service framework.
 */
interface PlatformOperations {
    fun onStatus(message: String)
    fun publishStatus()
    fun onError(message: String, code: String, state: ServiceState = ServiceState.Error)
    fun startStatusPolling()
    fun stopStatusPolling()
    fun stopForeground()
    fun stopSelf()
}

/**
 * Startup completion classification for the lifecycle coordinator.
 * Maps repository start results to specific coordinator actions.
 */
sealed interface StartupCompletion {
    data object VerifiedSuccess : StartupCompletion
    data class NativeStartFailure(val error: Throwable) : StartupCompletion
    data class VerificationFailure(val error: StartStatusVerificationException) : StartupCompletion
    data class UnexpectedFailure(val error: Throwable) : StartupCompletion
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
    val publishError: (message: String, code: String) -> Unit
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
    if (context.lifecycleGeneration.get() != context.startGeneration) {
        return
    }
    context.stopStatusPollingAndJoin()
    context.repositoryStop().fold(
        onSuccess = {
            context.nativeStopVerified.set(true)
            context.publishError(
                context.originalError.message ?: "Native startup could not be verified",
                "start_status_verification_failed"
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
                "start_verification_cleanup_failed"
            )
        }
    )
}