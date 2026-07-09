package com.phillipchin.webrtctunnel

import android.app.Notification
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.phillipchin.webrtctunnel.data.ConfigRepository
import com.phillipchin.webrtctunnel.data.IdentityValidationClient
import com.phillipchin.webrtctunnel.data.SensitiveDataRedactor
import com.phillipchin.webrtctunnel.data.StartStatusVerificationException
import com.phillipchin.webrtctunnel.data.StopStatusVerificationException
import com.phillipchin.webrtctunnel.data.TunnelRepository
import com.phillipchin.webrtctunnel.model.NetworkType
import com.phillipchin.webrtctunnel.model.ServiceState
import com.phillipchin.webrtctunnel.model.TunnelMode
import com.phillipchin.webrtctunnel.model.isTunnelActiveOrStarting
import com.phillipchin.webrtctunnel.model.isTunnelRunning
import com.phillipchin.webrtctunnel.network.LocalAddressResolver
import com.phillipchin.webrtctunnel.network.NetworkPolicyManager
import com.phillipchin.webrtctunnel.notification.NotificationController
import com.phillipchin.webrtctunnel.security.IdentityRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

// Control-flow signal: a startup attempt aborted after publishing its own error/state.
private class StartupAborted : Exception()

// Distinguishes an outright native stop failure from a stop that JNI reported as successful
// but whose final state could not be verified as Stopped (P0-003), so TunnelRepository's
// sticky lastCleanupError history can retain both categories. Top-level (not a class member)
// so it doesn't count against this class's function budget for no behavioral reason.
private fun stopFailureCode(error: Throwable): String =
    if (error is StopStatusVerificationException) {
        "stop_status_verification_failed"
    } else {
        "stop_failed"
    }

// P0-001: Ordered lifecycle command processor.
// Bounded capacity — not unlimited (non-negotiable rule).
private const val LIFECYCLE_COMMAND_CAPACITY = 32

// P0-001: Coordinator-owned cleanup for verified-start failure (P0-001).
// Classifies the startup outcome so the coordinator knows whether a verified
// native stop is required.
private sealed interface StartupCompletion {
    data object VerifiedSuccess : StartupCompletion

    data class NativeStartFailure(
        val error: Throwable,
    ) : StartupCompletion

    data class VerificationFailure(
        val error: StartStatusVerificationException,
    ) : StartupCompletion

    data class UnexpectedFailure(
        val error: Throwable,
    ) : StartupCompletion
}

// P0-001: Map a repository start result to a startup completion.
private fun classifyStartupResult(result: Result<Unit>): StartupCompletion =
    result.fold(
        onSuccess = { StartupCompletion.VerifiedSuccess },
        onFailure = { error ->
            if (error is StartStatusVerificationException) {
                StartupCompletion.VerificationFailure(error)
            } else {
                StartupCompletion.NativeStartFailure(error)
            }
        },
    )

// P0-001: Coordinator-owned cleanup for verified-start failure.
// Top-level (not a class member) so it doesn't count against TunnelForegroundService's
// function budget. Takes dependencies as parameters to avoid coupling to the class.
private data class UnverifiedStartContext(
    val originalError: StartStatusVerificationException,
    val startGeneration: Long,
    val lifecycleGeneration: AtomicLong,
    val stopStatusPollingAndJoin: suspend () -> Unit,
    val repositoryStop: suspend () -> Result<Unit>,
    val nativeStopVerified: AtomicBoolean,
    val nativeRuntimeUncertain: AtomicBoolean,
    val publishError: (message: String, code: String) -> Unit,
)

private suspend fun cleanupUnverifiedStart(context: UnverifiedStartContext) {
    if (context.lifecycleGeneration.get() != context.startGeneration) {
        return
    }
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
            // P0-004: Cleanup failure quarantines uncertain native runtime.
            context.nativeRuntimeUncertain.set(true)
            context.publishError(
                buildString {
                    append(
                        context.originalError.message
                            ?: "Native startup could not be verified",
                    )
                    append(". Cleanup also failed: ")
                    append(
                        SensitiveDataRedactor.redactText(
                            cleanupError.message
                                ?: "unknown cleanup failure",
                        ),
                    )
                },
                "start_verification_cleanup_failed",
            )
        },
    )
}

// P0-001: All accepted lifecycle intentions flow through one ordered stream.
// onStartCommand only submits; network policy also only submits.
// The command processor drains in FIFO order, so command execution order
// matches the order Android delivered the intents.
private sealed interface LifecycleCommand {
    data object StartOffer : LifecycleCommand

    data object Pause : LifecycleCommand

    data object Resume : LifecycleCommand

    data object Stop : LifecycleCommand

    data object AllowMeteredSession : LifecycleCommand

    data class PolicyBlocked(
        val reason: String,
    ) : LifecycleCommand

    data object PolicyAllowed : LifecycleCommand

    data class RetryPolicyResume(
        val expectedGeneration: Long,
    ) : LifecycleCommand

    data class StartupCompleted(
        val generation: Long,
        val completion: StartupCompletion,
    ) : LifecycleCommand
}

class TunnelForegroundService
    @JvmOverloads
    constructor(
        private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
        private val defaultDispatcher: CoroutineDispatcher = Dispatchers.Default,
    ) : Service() {
        private val tag = "TunnelForegroundService"
        private lateinit var notifications: NotificationController
        private lateinit var repository: TunnelRepository
        private lateinit var identityValidation: IdentityValidationClient
        private lateinit var configRepository: ConfigRepository
        private lateinit var identityRepository: IdentityRepository
        private lateinit var networkPolicyManager: NetworkPolicyManager
        private lateinit var localAddressResolver: LocalAddressResolver
        private val serviceScope = CoroutineScope(SupervisorJob() + defaultDispatcher)

        // P0-001: ordered command queue.
        private val lifecycleCommands =
            Channel<LifecycleCommand>(
                capacity = LIFECYCLE_COMMAND_CAPACITY,
            )
        private val nextLifecycleSequence = AtomicLong(0)

        private var networkMonitorJob: Job? = null
        private var startupJob: Job? = null
        private var statusPollJob: Job? = null
        private var lastMode: TunnelMode = TunnelMode.Offer

        // internal (not private): P0-001's Robolectric test captures this reference and
        // joins it directly, so it can deterministically wait for a specific stale poll
        // iteration to fully settle (commit or be discarded) before asserting final
        // status, instead of racing on timing.
        internal val statusPollJobForTest: Job?
            get() = statusPollJob

        // internal (not private): P0-004's Robolectric test reads this directly rather
        // than through any new public accessor, matching the "no public mutator" rule.
        // AtomicBoolean (not a plain var) because reads happen from coroutines that never
        // hold lifecycleMutex — the network-policy monitor callback and the status-poll
        // loop below — so a plain Boolean write under the mutex would have no guaranteed
        // visibility to those unsynchronized readers (P1-004).
        internal val pausedByPolicy = AtomicBoolean(false)

        // P0-002: Retains one pending retry intention bound to a lifecycle generation.
        // When a PolicyAllowed arrives while a startup is active, this records the
        // expected generation so the retry can be validated after the current attempt
        // completes. null means no pending retry.
        private val pendingPolicyResumeGeneration =
            java.util.concurrent.atomic.AtomicReference<Long?>(null)

        // P0-004: True when native runtime existence is uncertain after a cleanup/stop
        // failure. Blocks all automatic restart (PolicyAllowed, RetryPolicyResume, auto-resume).
        // Only a verified successful STOP clears the quarantine.
        private val nativeRuntimeUncertain = AtomicBoolean(false)

        // P1-001: AtomicBoolean (not a plain var) because reads happen from coroutines
        // that never hold lifecycleMutex — the network-policy monitor callback and
        // status-poll loop — so a plain Boolean write would have no guaranteed visibility
        // to those unsynchronized readers.
        private val allowMeteredForCurrentRun = AtomicBoolean(false)

        // P0-006: Tracks whether a verified native stop has succeeded. Set to false when
        // a new startup begins, set to true only after repository.stop() returns verified
        // success. onDestroy() checks this to avoid a redundant second native stop.
        private val nativeStopVerified = AtomicBoolean(true)

        // AtomicLong (not a mutex-guarded plain Long): generation checks must be lock-free so
        // an explicit lifecycle transition can cancel-and-join the startup coroutine while
        // holding lifecycleMutex without risking a deadlock against a startup coroutine that
        // might otherwise need the same lock to check its own generation (P0-001).
        private val lifecycleGeneration = AtomicLong(0)
        internal val lifecycleMutex = Mutex()

        // Notification + status-polling slice; accesses the shared lifecycle fields directly.
        private val reporter = StatusReporter()

        // Offer start/pause/stop state machine; accesses the shared lifecycle fields directly.
        // internal (not private): P0-004's Robolectric test drives pauseForPolicy() through
        // this real path rather than a synthetic test-only wrapper function.
        internal val offer = OfferCoordinator()

        // Handles network policy commands separately from dispatchCommand to keep
        // its cyclomatic complexity below the detekt threshold.
        private val policyDispatcher = PolicyDispatcher()

        override fun onCreate() {
            super.onCreate()
            notifications = NotificationController(this)
            notifications.ensureChannels()
            startForeground(NOTIFICATION_ID, reporter.loadingNotification(getString(R.string.service_msg_preparing)))
            val deps = (application as HasAppDependencies).deps
            configRepository = deps.configRepository
            repository = deps.tunnelRepository
            identityValidation = deps.identityValidation
            identityRepository = deps.identityRepository
            networkPolicyManager = deps.networkPolicyManager
            localAddressResolver = deps.localAddressResolver
            repository.updateSessionMeteredAllowance(false)

            // P0-001: command processor drains lifecycle commands in FIFO order.
            // Commands are processed sequentially to maintain ordering guarantees.
            serviceScope.launch {
                for (command in lifecycleCommands) {
                    runCatching { dispatchCommand(command) }
                        .onFailure { error ->
                            if (error is CancellationException) throw error
                            reporter.publishError(
                                message = error.message ?: "Lifecycle command failed",
                                code = "lifecycle_command_failed",
                            )
                        }
                }
            }

            // Network monitor still collects network events, but submits commands
            // through the same ordered queue instead of launching independent coroutines.
            networkMonitorJob =
                serviceScope.launch {
                    networkPolicyManager.monitor(this@TunnelForegroundService).collect { _ ->
                        runCatching {
                            val prefs = withContext(ioDispatcher) { configRepository.preferences.first() }
                            val policy =
                                networkPolicyManager.evaluateWithPolicy(
                                    prefs.allowMetered || allowMeteredForCurrentRun.get(),
                                )
                            repository.updateNetworkStatus(policy)
                            if (policy.networkType == NetworkType.UnmeteredWifi) {
                                // Policy allowed: submit through the ordered queue.
                                submitLifecycleCommand(LifecycleCommand.PolicyAllowed)
                            } else if (!policy.tunnelAllowed) {
                                // Policy blocked: submit through the ordered queue.
                                submitLifecycleCommand(
                                    LifecycleCommand.PolicyBlocked(
                                        policy.blockReason ?: "Tunnel paused: network policy blocks metered/cellular",
                                    ),
                                )
                            }
                        }.onFailure { error ->
                            reporter.publishError(
                                message = error.message ?: "Network policy monitor failed",
                                code = "network_policy_monitor_failed",
                            )
                        }
                    }
                }
        }

        override fun onStartCommand(
            intent: Intent?,
            flags: Int,
            startId: Int,
        ): Int {
            if (intent == null) {
                stopSelf(startId)
                return START_NOT_STICKY
            }
            when (val action = intent.action) {
                ACTION_START_OFFER -> submitLifecycleCommand(LifecycleCommand.StartOffer)
                ACTION_START_ANSWER -> {
                    reporter.publishError(
                        message = "Answer mode is not available on Android",
                        code = "answer_mode_disabled",
                    )
                    stopSelf(startId)
                }
                ACTION_STOP -> submitLifecycleCommand(LifecycleCommand.Stop)
                ACTION_PAUSE -> submitLifecycleCommand(LifecycleCommand.Pause)
                ACTION_RESUME -> submitLifecycleCommand(LifecycleCommand.Resume)
                ACTION_ALLOW_METERED_SESSION ->
                    submitLifecycleCommand(LifecycleCommand.AllowMeteredSession)
                else -> stopSelf(startId)
            }
            return START_NOT_STICKY
        }

        // Dispatches a single lifecycle command to the appropriate coordinator action.
        private suspend fun dispatchCommand(command: LifecycleCommand) {
            when (command) {
                LifecycleCommand.StartOffer -> {
                    // P1-012: Block duplicate starts in transitional states.
                    if (!repository.status.value.serviceState.isTunnelActiveOrStarting()) {
                        offer.startOffer()
                    }
                }
                LifecycleCommand.Pause -> offer.pause()
                LifecycleCommand.Resume -> offer.resume()
                LifecycleCommand.Stop -> offer.stopServiceWork()
                LifecycleCommand.AllowMeteredSession -> offer.allowMeteredForSessionAndStart()
                is LifecycleCommand.PolicyBlocked -> offer.pauseForPolicy(command.reason)
                LifecycleCommand.PolicyAllowed -> policyDispatcher.handlePolicyAllowed()
                is LifecycleCommand.RetryPolicyResume ->
                    policyDispatcher.handleRetryPolicyResume(command.expectedGeneration)
                is LifecycleCommand.StartupCompleted ->
                    dispatchStartupCompleted(command)
            }
        }

        // P0-001: Submit a lifecycle command through the ordered queue.
        private fun submitLifecycleCommand(command: LifecycleCommand) {
            nextLifecycleSequence.incrementAndGet()
            val result = lifecycleCommands.trySend(command)
            if (result.isFailure) {
                reporter.publishError(
                    message = "Unable to queue lifecycle command ${command::class.simpleName}",
                    code = "lifecycle_command_queue_failed",
                )
            }
        }

        // P0-001: Coordinator owns startup completion decisions.
        private suspend fun dispatchStartupCompleted(command: LifecycleCommand.StartupCompleted) {
            if (lifecycleGeneration.get() != command.generation) {
                // Stale completion: a newer lifecycle command superseded this one.
                return
            }
            startupJob = null
            when (val completion = command.completion) {
                is StartupCompletion.VerifiedSuccess -> {
                    // P0-007: Do NOT clear metered allowance on success — it lasts through the run.
                    pausedByPolicy.set(false)
                    pendingPolicyResumeGeneration.set(null)
                    reporter.publishStatus()
                    reporter.startStatusPolling()
                }
                is StartupCompletion.NativeStartFailure -> {
                    // P0-001: Native start failure (repository.start() returned failure).
                    offer.clearTemporaryMeteredAllowance()
                    val pending = pendingPolicyResumeGeneration.getAndSet(null)
                    if (pending == command.generation) {
                        submitLifecycleCommand(
                            LifecycleCommand.RetryPolicyResume(
                                expectedGeneration = command.generation,
                            ),
                        )
                    } else {
                        reporter.publishError(
                            message = completion.error.message ?: "Unable to start tunnel",
                            code = "native_start_failed",
                        )
                    }
                }
                is StartupCompletion.VerificationFailure -> {
                    // P0-001: Verification failure (native succeeded but state not verified).
                    cleanupUnverifiedStart(
                        UnverifiedStartContext(
                            completion.error,
                            command.generation,
                            lifecycleGeneration,
                            reporter::stopStatusPollingAndJoin,
                            { repository.stop() },
                            nativeStopVerified,
                            nativeRuntimeUncertain,
                            reporter::publishError,
                        ),
                    )
                    offer.clearTemporaryMeteredAllowance()
                }
                is StartupCompletion.UnexpectedFailure -> {
                    // P0-001: Unexpected failure (unhandled exception during startup).
                    offer.clearTemporaryMeteredAllowance()
                    reporter.publishError(
                        message = completion.error.message ?: "Unexpected startup failure",
                        code = "startup_unexpected_failure",
                    )
                }
            }
        }

        // Removed: publishError was a thin wrapper; callers use reporter.publishError directly.

        override fun onBind(intent: Intent?): IBinder? = null

        override fun onDestroy() {
            val pendingStop =
                serviceScope.launch {
                    // P0-006: Cancel network monitor and join it before fallback cleanup.
                    val monitorJob = networkMonitorJob
                    networkMonitorJob = null
                    monitorJob?.cancelAndJoin()
                    lifecycleMutex.withLock {
                        lifecycleGeneration.incrementAndGet()
                        cancelStartupJobAndJoinLocked()
                        reporter.stopStatusPollingAndJoin()
                        // Only perform fallback cleanup if native stop was not already verified.
                        if (!nativeStopVerified.get()) {
                            withContext(ioDispatcher) {
                                repository.stop()
                            }.onFailure {
                                reporter.publishError(
                                    message = it.message ?: "Unable to stop tunnel",
                                    code = stopFailureCode(it),
                                )
                            }
                        }
                        pausedByPolicy.set(false)
                        offer.clearTemporaryMeteredAllowance()
                    }
                }
            stopForeground(STOP_FOREGROUND_REMOVE)
            pendingStop.invokeOnCompletion { serviceScope.coroutineContext.cancel() }
            super.onDestroy()
        }

        private fun abortStartup(
            message: String,
            code: String,
            state: ServiceState = ServiceState.Error,
        ): Nothing {
            reporter.publishError(message = message, code = code, state = state)
            throw StartupAborted()
        }

        // Cancels the startup coroutine and waits for it to fully unwind before returning, so
        // the caller (an explicit lifecycle transition, always holding lifecycleMutex here) can
        // safely perform the one authoritative repository.stop() afterward without racing the
        // startup coroutine's own unwind. Safe to call under lifecycleMutex because generation
        // checks are lock-free and no other code the startup coroutine runs acquires this mutex
        // (P0-001).
        private suspend fun cancelStartupJobAndJoinLocked() {
            val job = startupJob
            startupJob = null
            job?.cancelAndJoin()
        }

        // Handles network policy resume commands. Extracted from dispatchCommand to keep
        // its cyclomatic complexity below the detekt threshold.
        private inner class PolicyDispatcher {
            suspend fun handlePolicyAllowed() {
                // P0-004: Quarantine blocks automatic restart.
                if (nativeRuntimeUncertain.get() || !pausedByPolicy.get()) {
                    pendingPolicyResumeGeneration.set(null)
                    return
                }
                if (runCatching { configRepository.preferences.first() }
                        .getOrNull()?.resumeOnUnmetered == true
                ) {
                    if (startupJob?.isActive == true) {
                        pendingPolicyResumeGeneration.set(lifecycleGeneration.get())
                    } else {
                        pendingPolicyResumeGeneration.set(null)
                        offer.resume()
                    }
                }
            }

            suspend fun handleRetryPolicyResume(expectedGeneration: Long) {
                // P0-004: Quarantine blocks automatic restart.
                if (nativeRuntimeUncertain.get()) return
                if (lifecycleGeneration.get() != expectedGeneration) return
                pendingPolicyResumeGeneration.set(null)
                offer.resume()
            }
        }

        // Notification rendering and status polling for the active tunnel.
        inner class StatusReporter {
            fun publishStatus(body: String? = null) {
                val state = repository.status.value.serviceState
                val text =
                    body ?: when (state) {
                        ServiceState.Connected -> getString(R.string.service_body_connected)
                        ServiceState.Serving -> getString(R.string.service_body_serving)
                        ServiceState.Listening -> getString(R.string.service_body_listening)
                        ServiceState.Starting,
                        ServiceState.Connecting,
                        ServiceState.Reconnecting,
                        -> getString(R.string.service_body_connecting)
                        ServiceState.PausedMeteredBlocked -> getString(R.string.service_body_paused_metered)
                        ServiceState.NoNetwork -> getString(R.string.service_body_no_network)
                        ServiceState.Stopping -> getString(R.string.service_body_stopping)
                        ServiceState.Stopped -> getString(R.string.service_body_stopped)
                        ServiceState.Error, ServiceState.ConfigInvalid ->
                            repository.status.value.lastError?.message ?: getString(R.string.notification_title_error)
                    }
                notifications.show(notifications.buildStatusNotification(state, SensitiveDataRedactor.redactText(text)))
            }

            fun publishError(
                message: String,
                code: String = "service_error",
                state: ServiceState = ServiceState.Error,
            ) {
                val redacted = SensitiveDataRedactor.redactText(message)
                repository.setLocalError(code = code, message = redacted, state = state)
                Log.e(tag, redacted)
                notifications.show(notifications.buildStatusNotification(state, redacted))
            }

            fun loadingNotification(body: String): Notification =
                NotificationCompat.Builder(this@TunnelForegroundService, NotificationController.CHANNEL_STATUS)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle(getString(R.string.notification_title_starting))
                    .setContentText(body)
                    .setOngoing(true)
                    .build()

            /**
             * Poll native runtime status while the tunnel is active so the UI and
             * notification reflect changes (e.g. a post-start error) without the user
             * navigating or manually refreshing. Stops when the tunnel leaves an active
             * state or is paused by policy. [TunnelRepository.refreshStatus] independently
             * refuses to resurrect policy-paused states, so a poll racing a policy pause
             * cannot flip the UI back to Connected.
             */
            fun startStatusPolling() {
                if (statusPollJob?.isActive == true) return
                statusPollJob =
                    serviceScope.launch {
                        var lastState = repository.status.value.serviceState
                        var active = true
                        while (active && !pausedByPolicy.get()) {
                            delay(STATUS_POLL_INTERVAL_MS)
                            if (pausedByPolicy.get()) break
                            withContext(ioDispatcher) { runCatching { repository.refreshStatus() } }
                            val state = repository.status.value.serviceState
                            if (state != lastState) {
                                lastState = state
                                publishStatus()
                            }
                            active = state in ACTIVE_STATES
                        }
                    }
            }

            fun stopStatusPolling() {
                statusPollJob?.cancel()
                statusPollJob = null
            }

            /**
             * Cancels the poll job and waits for it to fully finish before returning,
             * so a caller about to commit a lifecycle-changing stop truth (pause,
             * policy pause, service stop, startup cleanup, service destruction) can be
             * sure a stale in-flight refresh can no longer resurrect an older status
             * afterward (P0-001). The poll loop never acquires `lifecycleMutex`, so
             * joining it while holding that mutex cannot deadlock.
             */
            suspend fun stopStatusPollingAndJoin() {
                val job = statusPollJob
                statusPollJob = null
                job?.cancelAndJoin()
            }
        }

        // Offer-mode start plus pause/stop transitions, guarded by the lifecycle generation.
        inner class OfferCoordinator {
            suspend fun startOffer() {
                var generation = 0L
                lifecycleMutex.withLock {
                    if (startupJob?.isActive == true) {
                        reporter.publishStatus(getString(R.string.service_msg_already_starting))
                        return
                    }
                    val current = repository.status.value.serviceState
                    // P1-012: Block duplicate starts in transitional states too.
                    if (current.isTunnelActiveOrStarting()) {
                        reporter.publishStatus(getString(R.string.service_msg_already_running))
                        return
                    }
                    generation = lifecycleGeneration.incrementAndGet()
                    nativeStopVerified.set(false)
                    startupJob =
                        serviceScope.launch {
                            doStartOffer(generation)
                        }
                }
            }

            private suspend fun doStartOffer(startGeneration: Long) {
                lastMode = TunnelMode.Offer
                startForeground(
                    NOTIFICATION_ID,
                    reporter.loadingNotification(getString(R.string.service_msg_starting_tunnel)),
                )
                val identity =
                    try {
                        prepareOfferIdentity()
                    } catch (_: StartupAborted) {
                        return
                    }
                runOfferStart(identity, startGeneration)
            }

            // Loads + validates prerequisites for an offer start. Returns the private identity
            // bytes, or throws StartupAborted after publishing the appropriate state/error.
            private suspend fun prepareOfferIdentity(): ByteArray {
                val prefs = withContext(ioDispatcher) { configRepository.preferences.first() }
                val policy =
                    networkPolicyManager.evaluateWithPolicy(
                        prefs.allowMetered || allowMeteredForCurrentRun.get(),
                    )
                repository.updateNetworkStatus(policy)
                if (!policy.tunnelAllowed) {
                    // P1-013: Signal that startup was blocked before native start.
                    // This allows a later PolicyAllowed event to trigger a resume.
                    pausedByPolicy.set(true)
                    repository.setPolicyBlocked(policy.blockReason ?: "Tunnel blocked by current network policy")
                    reporter.publishStatus(policy.blockReason ?: "Tunnel blocked by network policy")
                    throw StartupAborted()
                }
                val identity =
                    withContext(ioDispatcher) {
                        runCatching { identityRepository.readPrivateIdentityPlaintext() }
                    }
                        .getOrElse {
                            abortStartup("Unable to decrypt private identity: ${it.message}", "identity_decrypt_failed")
                        }
                // P0-008: Ownership transfer — identity is wiped if preparation fails.
                var transferred = false
                try {
                    // Apply the user's chosen ICE mode and inject the active network's IPv4
                    // (ConnectivityManager/LinkProperties) as the vnet_mux host candidate before
                    // validating/starting, so a strict vnet_mux start fails loudly rather than
                    // silently dropping to native ICE.
                    withContext(ioDispatcher) {
                        configRepository.prepareActiveConfigForStart(
                            prefs.androidIceMode,
                            localAddressResolver.currentIpv4(),
                        )
                    }
                    val validation =
                        withContext(ioDispatcher) {
                            identityValidation.validateConfigWithIdentity(configRepository.configPath, identity)
                        }
                    if (!validation.valid) {
                        abortStartup(
                            validation.message ?: "Config validation failed",
                            "config_validation_failed",
                            ServiceState.ConfigInvalid,
                        )
                    }
                    transferred = true
                    return identity
                } finally {
                    if (!transferred) {
                        // Preparation failed — wipe the plaintext identity.
                        identity.fill(0)
                    }
                }
            }

            // Starts the tunnel under the lifecycle-generation guard: aborts if a newer start
            // superseded this one (before or after the native start) or if it was cancelled.
            // P0-001: Performs work/classification only, submits StartupCompleted to the
            // coordinator. The coordinator owns all completion decisions.
            private suspend fun runOfferStart(
                identity: ByteArray,
                startGeneration: Long,
            ) {
                // The native start copies the identity across JNI, so the plaintext buffer is
                // wiped once start returns (or any early exit), even on failure/cancellation.
                try {
                    if (lifecycleGeneration.get() != startGeneration) return
                    val completion =
                        runCatching {
                            // No catch for CancellationException here: if this coroutine is cancelled
                            // (e.g. by an explicit pause/stop/onDestroy), that cancelling lifecycle
                            // transition already owns the resulting native cleanup via
                            // cancelStartupJobAndJoinLocked() + its own repository.stop() call. Letting
                            // the exception unwind here (through the `finally` below) avoids a second,
                            // independent, racing repository.stop() call from this coroutine (P0-001).
                            val result =
                                withContext(ioDispatcher) {
                                    repository.start(TunnelMode.Offer, configRepository.configPath, identity)
                                }
                            if (lifecycleGeneration.get() != startGeneration) {
                                // The lifecycle transition that advanced generation owns cleanup; no
                                // second, independent stop call here (P0-001).
                                throw StartupAborted()
                            }
                            classifyStartupResult(result)
                        }.fold(
                            onSuccess = { it },
                            onFailure = { error ->
                                when (error) {
                                    is CancellationException -> throw error
                                    is StartupAborted -> return
                                    else -> StartupCompletion.UnexpectedFailure(error)
                                }
                            },
                        )
                    submitLifecycleCommand(
                        LifecycleCommand.StartupCompleted(
                            generation = startGeneration,
                            completion = completion,
                        ),
                    )
                } finally {
                    identity.fill(0)
                }
            }

            // P1-001: AllowMeteredSession is now one ordered lifecycle command.
            // The handler performs: set allowance, update repository, begin startup
            // within one command processing step.
            suspend fun allowMeteredForSessionAndStart() {
                lifecycleMutex.withLock {
                    allowMeteredForCurrentRun.set(true)
                    repository.updateSessionMeteredAllowance(true)
                    pausedByPolicy.set(false)
                }
                startOffer()
            }

            suspend fun resume() {
                when (lastMode) {
                    TunnelMode.Offer -> startOffer()
                    TunnelMode.Answer ->
                        reporter.publishError(
                            message = "Answer mode is not available on Android",
                            code = "answer_mode_disabled",
                        )
                }
            }

            suspend fun pause() {
                lifecycleMutex.withLock {
                    lifecycleGeneration.incrementAndGet()
                    cancelStartupJobAndJoinLocked()
                    reporter.stopStatusPollingAndJoin()
                    withContext(ioDispatcher) { repository.stop() }
                        .fold(
                            onSuccess = {
                                // P1-011: Set nativeStopVerified true after verified successful pause.
                                nativeStopVerified.set(true)
                                clearTemporaryMeteredAllowance()
                                reporter.publishStatus(getString(R.string.service_msg_paused))
                            },
                            onFailure = {
                                reporter.publishError(
                                    message = it.message ?: "Unable to stop tunnel",
                                    code = stopFailureCode(it),
                                )
                            },
                        )
                }
            }

            suspend fun pauseForPolicy(reason: String) {
                lifecycleMutex.withLock {
                    lifecycleGeneration.incrementAndGet()
                    cancelStartupJobAndJoinLocked()
                    reporter.stopStatusPollingAndJoin()
                    withContext(ioDispatcher) { repository.stop() }
                        .fold(
                            onSuccess = {
                                // P1-011: Set nativeStopVerified true after verified successful policy pause.
                                nativeStopVerified.set(true)
                                pausedByPolicy.set(true)
                                repository.setPolicyBlocked(reason)
                                reporter.publishStatus(reason)
                            },
                            onFailure = {
                                // The tunnel did not stop cleanly, so this must never be
                                // reported as the normal policy-paused state. Force false
                                // unconditionally rather than restoring a stale prior
                                // value, so a retry/reevaluation path stays open.
                                pausedByPolicy.set(false)
                                reporter.publishError(
                                    message = it.message ?: "Failed stopping tunnel after policy block",
                                    code = stopFailureCode(it),
                                )
                            },
                        )
                }
            }

            suspend fun stopServiceWork() {
                lifecycleMutex.withLock {
                    lifecycleGeneration.incrementAndGet()
                    cancelStartupJobAndJoinLocked()
                    reporter.stopStatusPollingAndJoin()
                    val stopResult = withContext(ioDispatcher) { repository.stop() }
                    pausedByPolicy.set(false)
                    clearTemporaryMeteredAllowance()
                    stopResult.fold(
                        onSuccess = {
                            // P0-005: Stop success path.
                            nativeStopVerified.set(true)
                            nativeRuntimeUncertain.set(false)
                            notifications.show(
                                notifications.buildStatusNotification(ServiceState.Stopped, "Tunnel stopped"),
                            )
                            stopForeground(STOP_FOREGROUND_REMOVE)
                            stopSelf()
                        },
                        onFailure = {
                            // P0-005: Stop failure path — remain alive and foreground.
                            nativeStopVerified.set(false)
                            nativeRuntimeUncertain.set(true)
                            pendingPolicyResumeGeneration.set(null)
                            reporter.publishError(
                                message = it.message ?: "Unable to stop tunnel cleanly",
                                code = stopFailureCode(it),
                            )
                            // Service remains foreground; user can retry STOP.
                        },
                    )
                }
            }

            // Clears the temporary metered allowance so a future run starts fresh.
            fun clearTemporaryMeteredAllowance() {
                allowMeteredForCurrentRun.set(false)
                repository.updateSessionMeteredAllowance(false)
            }
        }

        companion object {
            const val ACTION_START_OFFER = "com.phillipchin.webrtctunnel.action.START_OFFER"
            const val ACTION_START_ANSWER = "com.phillipchin.webrtctunnel.action.START_ANSWER"
            const val ACTION_STOP = "com.phillipchin.webrtctunnel.action.STOP"
            const val ACTION_PAUSE = "com.phillipchin.webrtctunnel.action.PAUSE"
            const val ACTION_RESUME = "com.phillipchin.webrtctunnel.action.RESUME"
            const val ACTION_ALLOW_METERED_SESSION = "com.phillipchin.webrtctunnel.action.ALLOW_METERED_SESSION"
            const val NOTIFICATION_ID = NotificationController.NOTIFICATION_ID
            private const val STATUS_POLL_INTERVAL_MS = 1_500L
            private val ACTIVE_STATES =
                setOf(
                    ServiceState.Starting,
                    ServiceState.Connecting,
                    ServiceState.Reconnecting,
                    ServiceState.Connected,
                    ServiceState.Listening,
                    ServiceState.Serving,
                )
        }
    }
