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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Tunnel lifecycle coordinator.
 *
 * Extracts the core lifecycle coordination logic from TunnelForegroundService,
 * managing ordered command processing, generation tracking, quarantine state,
 * and policy pause state.
 *
 * Platform-specific operations are delegated through [PlatformOperations].
 */
class TunnelLifecycleCoordinator(
    private val repository: TunnelRepository,
    private val platformOps: PlatformOperations,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val commands = Channel<LifecycleCommand>(COMMAND_CAPACITY)
    private val generation = kotlin.concurrent.AtomicLong(0)
    private val mutex = Mutex()

    // State tracking
    private val nativeRuntimeUncertain = kotlin.concurrent.AtomicBoolean(false)
    private val pausedByPolicyState = kotlin.concurrent.AtomicBoolean(false)
    private val allowMeteredForCurrentRun = kotlin.concurrent.AtomicBoolean(false)
    private val nativeStopVerified = kotlin.concurrent.AtomicBoolean(true)
    private val pendingPolicyResumeGeneration =
        kotlin.concurrent.AtomicReference<Long?>(null)

    private var startupJob: Job? = null
    private var currentServiceState: ServiceState = ServiceState.Stopped

    fun currentGeneration(): Long = generation.get()
    fun isQuarantined(): Boolean = nativeRuntimeUncertain.get()
    fun isPausedByPolicy(): Boolean = pausedByPolicyState.get()
    fun nativeStopVerified(): Boolean = nativeStopVerified.get()

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

    fun submitCommand(command: LifecycleCommand) {
        val result = commands.trySend(command)
        if (result.isFailure) {
            platformOps.onError(
                message = "Unable to queue lifecycle command ${command::class.java.simpleName}",
                code = "lifecycle_command_queue_failed"
            )
        }
    }

    private suspend fun cancelStartupJobAndJoin() {
        val job = startupJob
        startupJob = null
        job?.cancelAndJoin()
    }

    private fun clearTemporaryMeteredAllowance() {
        allowMeteredForCurrentRun.set(false)
        repository.updateSessionMeteredAllowance(false)
    }

    fun stop() {
        scope.cancel()
    }

    // Command handlers (extracted for clarity)
    private suspend fun handleStartOffer() {
        if (currentServiceState.isTunnelActiveOrStarting()) {
            platformOps.onStatus("Already running or starting")
            return
        }
        generation.incrementAndGet()
        nativeStopVerified.set(false)
        startupJob = scope.launch {
            // Startup work handled through platform operations
        }
    }

    private suspend fun handlePause() {
        mutex.withLock {
            generation.incrementAndGet()
            cancelStartupJobAndJoin()
            platformOps.stopStatusPolling()
            kotlin.runCatching { repository.stop() }.fold(
                onSuccess = {
                    nativeStopVerified.set(true)
                    clearTemporaryMeteredAllowance()
                    platformOps.onStatus("Tunnel paused")
                },
                onFailure = { error ->
                    platformOps.onError(error.message ?: "Unable to stop tunnel", stopFailureCode(error))
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
            kotlin.runCatching { repository.stop() }.fold(
                onSuccess = {
                    nativeStopVerified.set(true)
                    nativeRuntimeUncertain.set(false)
                    platformOps.onStatus("Tunnel stopped")
                },
                onFailure = {
                    nativeStopVerified.set(false)
                    nativeRuntimeUncertain.set(true)
                    pendingPolicyResumeGeneration.set(null)
                    platformOps.onError(it.message ?: "Unable to stop tunnel cleanly", stopFailureCode(it))
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
            kotlin.runCatching { repository.stop() }.fold(
                onSuccess = {
                    nativeStopVerified.set(true)
                    pausedByPolicyState.set(true)
                    repository.setPolicyBlocked(reason)
                    platformOps.onStatus(reason)
                },
                onFailure = {
                    pausedByPolicyState.set(false)
                    platformOps.onError(it.message ?: "Failed stopping tunnel after policy block", stopFailureCode(it))
                }
            )
        }
    }

    private suspend fun handlePolicyAllowed() {
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
        if (nativeRuntimeUncertain.get()) return
        if (generation.get() != expectedGeneration) return
        pendingPolicyResumeGeneration.set(null)
        handleResume()
    }

    private suspend fun handleStartupCompleted(command: LifecycleCommand.StartupCompleted) {
        if (generation.get() != command.generation) return
        startupJob = null

        when (val completion = command.completion) {
            is StartupCompletion.VerifiedSuccess -> {
                pausedByPolicyState.set(false)
                pendingPolicyResumeGeneration.set(null)
                platformOps.publishStatus()
                platformOps.startStatusPolling()
            }
            is StartupCompletion.NativeStartFailure -> {
                clearTemporaryMeteredAllowance()
                val pending = pendingPolicyResumeGeneration.getAndSet(null)
                if (pending == command.generation) {
                    submitCommand(LifecycleCommand.RetryPolicyResume(command.generation))
                } else {
                    platformOps.onError(completion.error.message ?: "Unable to start tunnel", "native_start_failed")
                }
            }
            is StartupCompletion.VerificationFailure -> {
                cleanupUnverifiedStart(
                    UnverifiedStartContext(
                        completion.error,
                        command.generation,
                        generation,
                        platformOps::stopStatusPolling,
                        { repository.stop() },
                        nativeStopVerified,
                        nativeRuntimeUncertain,
                        platformOps::onError
                    )
                )
                clearTemporaryMeteredAllowance()
            }
            is StartupCompletion.UnexpectedFailure -> {
                clearTemporaryMeteredAllowance()
                platformOps.onError(completion.error.message ?: "Unexpected startup failure", "startup_unexpected_failure")
            }
        }
    }

    companion object {
        private const val COMMAND_CAPACITY = 32
    }
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
    data class StartupCompleted(val generation: Long, val completion: StartupCompletion) : LifecycleCommand
}

/**
 * Platform operations interface for the coordinator.
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
 * Startup completion classification.
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
    if (context.lifecycleGeneration.get() != context.startGeneration) return
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