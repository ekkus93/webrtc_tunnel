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
                    CommandHandler(this@TunnelLifecycleCoordinator).handle(command)
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

    fun stop() {
        scope.cancel()
    }

    private companion object {
        private const val COMMAND_CAPACITY = 32

        // Command handler logic extracted to reduce coordinator function count
        class CommandHandler(
            private val coordinator: TunnelLifecycleCoordinator
        ) {
            suspend fun handle(command: LifecycleCommand) {
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
            }

            private suspend fun handleStartOffer() {
                if (coordinator.currentServiceState.isTunnelActiveOrStarting()) {
                    coordinator.platformOps.onStatus("Already running or starting")
                    return
                }
                coordinator.generation.incrementAndGet()
                coordinator.nativeStopVerified.set(false)
                coordinator.startupJob = coordinator.scope.launch {
                    // Startup work handled through platform operations
                }
            }

            private suspend fun handlePause() {
                coordinator.mutex.withLock {
                    coordinator.generation.incrementAndGet()
                    coordinator.cancelStartupJobAndJoin()
                    coordinator.platformOps.stopStatusPolling()
                    kotlin.runCatching { coordinator.repository.stop() }.fold(
                        onSuccess = {
                            coordinator.nativeStopVerified.set(true)
                            coordinator.clearTemporaryMeteredAllowance()
                            coordinator.platformOps.onStatus("Tunnel paused")
                        },
                        onFailure = { error ->
                            coordinator.platformOps.onError(
                                error.message ?: "Unable to stop tunnel",
                                stopFailureCode(error)
                            )
                        }
                    )
                }
            }

            private suspend fun handleResume() {
                // Resume logic goes here
            }

            private suspend fun handleStop() {
                coordinator.mutex.withLock {
                    coordinator.generation.incrementAndGet()
                    coordinator.cancelStartupJobAndJoin()
                    coordinator.platformOps.stopStatusPolling()
                    kotlin.runCatching { coordinator.repository.stop() }.fold(
                        onSuccess = {
                            coordinator.nativeStopVerified.set(true)
                            coordinator.nativeRuntimeUncertain.set(false)
                            coordinator.platformOps.onStatus("Tunnel stopped")
                        },
                        onFailure = {
                            coordinator.nativeStopVerified.set(false)
                            coordinator.nativeRuntimeUncertain.set(true)
                            coordinator.pendingPolicyResumeGeneration.set(null)
                            coordinator.platformOps.onError(
                                it.message ?: "Unable to stop tunnel cleanly",
                                stopFailureCode(it)
                            )
                        }
                    )
                }
            }

            private suspend fun handleAllowMeteredSession() {
                coordinator.mutex.withLock {
                    coordinator.allowMeteredForCurrentRun.set(true)
                    coordinator.repository.updateSessionMeteredAllowance(true)
                    coordinator.pausedByPolicyState.set(false)
                }
                handleStartOffer()
            }

            private suspend fun handlePolicyBlocked(reason: String) {
                coordinator.mutex.withLock {
                    coordinator.generation.incrementAndGet()
                    coordinator.cancelStartupJobAndJoin()
                    coordinator.platformOps.stopStatusPolling()
                    kotlin.runCatching { coordinator.repository.stop() }.fold(
                        onSuccess = {
                            coordinator.nativeStopVerified.set(true)
                            coordinator.pausedByPolicyState.set(true)
                            coordinator.repository.setPolicyBlocked(reason)
                            coordinator.platformOps.onStatus(reason)
                        },
                        onFailure = {
                            coordinator.pausedByPolicyState.set(false)
                            coordinator.platformOps.onError(
                                it.message ?: "Failed stopping tunnel after policy block",
                                stopFailureCode(it)
                            )
                        }
                    )
                }
            }

            private suspend fun handlePolicyAllowed() {
                if (coordinator.nativeRuntimeUncertain.get() || !coordinator.pausedByPolicyState.get()) {
                    coordinator.pendingPolicyResumeGeneration.set(null)
                    return
                }
                if (coordinator.startupJob?.isActive == true) {
                    coordinator.pendingPolicyResumeGeneration.set(coordinator.generation.get())
                } else {
                    coordinator.pendingPolicyResumeGeneration.set(null)
                    handleResume()
                }
            }

            private suspend fun handleRetryPolicyResume(expectedGeneration: Long) {
                if (coordinator.nativeRuntimeUncertain.get()) return
                if (coordinator.generation.get() != expectedGeneration) return
                coordinator.pendingPolicyResumeGeneration.set(null)
                handleResume()
            }

            private suspend fun handleStartupCompleted(command: LifecycleCommand.StartupCompleted) {
                if (coordinator.generation.get() != command.generation) return
                coordinator.startupJob = null

                when (val completion = command.completion) {
                    is StartupCompletion.VerifiedSuccess -> {
                        coordinator.pausedByPolicyState.set(false)
                        coordinator.pendingPolicyResumeGeneration.set(null)
                        coordinator.platformOps.publishStatus()
                        coordinator.platformOps.startStatusPolling()
                    }
                    is StartupCompletion.NativeStartFailure -> {
                        coordinator.clearTemporaryMeteredAllowance()
                        val pending = coordinator.pendingPolicyResumeGeneration.getAndSet(null)
                        if (pending == command.generation) {
                            coordinator.submitCommand(LifecycleCommand.RetryPolicyResume(command.generation))
                        } else {
                            coordinator.platformOps.onError(
                                completion.error.message ?: "Unable to start tunnel",
                                "native_start_failed"
                            )
                        }
                    }
                    is StartupCompletion.VerificationFailure -> {
                        cleanupUnverifiedStart(
                            UnverifiedStartContext(
                                completion.error,
                                command.generation,
                                coordinator.generation,
                                coordinator.platformOps::stopStatusPolling,
                                { coordinator.repository.stop() },
                                coordinator.nativeStopVerified,
                                coordinator.nativeRuntimeUncertain,
                                coordinator.platformOps::onError
                            )
                        )
                        coordinator.clearTemporaryMeteredAllowance()
                    }
                    is StartupCompletion.UnexpectedFailure -> {
                        coordinator.clearTemporaryMeteredAllowance()
                        coordinator.platformOps.onError(
                            completion.error.message ?: "Unexpected startup failure",
                            "startup_unexpected_failure"
                        )
                    }
                }
            }
        }
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
