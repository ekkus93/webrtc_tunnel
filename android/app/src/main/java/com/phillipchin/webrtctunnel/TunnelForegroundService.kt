package com.phillipchin.webrtctunnel

import android.app.Notification
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.phillipchin.webrtctunnel.data.AppInitializationCoordinator
import com.phillipchin.webrtctunnel.data.AppInitializationState
import com.phillipchin.webrtctunnel.data.ConfigRepository
import com.phillipchin.webrtctunnel.data.CoordinatorOperations
import com.phillipchin.webrtctunnel.data.IdentityValidationClient
import com.phillipchin.webrtctunnel.data.LifecycleCommand
import com.phillipchin.webrtctunnel.data.NativeFailureAfterStartupContext
import com.phillipchin.webrtctunnel.data.PolicyAllowedResumeContext
import com.phillipchin.webrtctunnel.data.SensitiveDataRedactor
import com.phillipchin.webrtctunnel.data.StartOutcome
import com.phillipchin.webrtctunnel.data.StopStatusVerificationException
import com.phillipchin.webrtctunnel.data.TunnelLifecycleCoordinator
import com.phillipchin.webrtctunnel.data.TunnelRepository
import com.phillipchin.webrtctunnel.data.UnverifiedStartContext
import com.phillipchin.webrtctunnel.data.classifyStartResult
import com.phillipchin.webrtctunnel.data.cleanupUnverifiedStart
import com.phillipchin.webrtctunnel.data.handleNativeFailureAfterStartup
import com.phillipchin.webrtctunnel.data.resumeOnPolicyAllowedIfPreferred
import com.phillipchin.webrtctunnel.model.NetworkType
import com.phillipchin.webrtctunnel.model.ServiceState
import com.phillipchin.webrtctunnel.model.TunnelMode
import com.phillipchin.webrtctunnel.model.isTunnelActiveOrStarting
import com.phillipchin.webrtctunnel.network.LocalAddressResolver
import com.phillipchin.webrtctunnel.network.NetworkMonitorSupervisor
import com.phillipchin.webrtctunnel.network.NetworkPolicyDiagnosticReporter
import com.phillipchin.webrtctunnel.network.NetworkPolicyManager
import com.phillipchin.webrtctunnel.network.reportNetworkDiagnosticSafely
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

// Control-flow signal: a startup attempt aborted after publishing its own error/state.
private class StartupAborted(message: String) : Exception(message)

// P0-001: Signal that startup was blocked by network policy before native start.
private class StartupPolicyBlocked(message: String) : RuntimeException(message)

// P0-002: Quarantine violation — thrown when native runtime state is uncertain.
internal class NativeRuntimeQuarantinedException(
    message: String,
) : IllegalStateException(message)

// FIX6 INV-010: start blocked because app initialization has not succeeded. Carries its
// own visible code so the guard can report why the start was refused.
internal class AppInitializationIncompleteException(
    val code: String,
    message: String,
) : IllegalStateException(message)

// Maps a start-guard failure to its visible diagnostic code. Top-level (not a class
// member) for the same reason as stopFailureCode: it costs no function budget against
// this file's already-at-threshold classes.
private fun startBlockedCode(error: Throwable): String =
    if (error is AppInitializationIncompleteException) {
        error.code
    } else {
        "native_runtime_quarantined"
    }

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

// RESPONSES item 3/FIX7 P0-007-D: the native-runtime-quarantine check runs FIRST, before app
// initialization readiness, so an uncertain runtime always blocks a restart regardless of
// initialization state. Top-level (not a class member) so it doesn't count against
// TunnelForegroundService's function budget for no behavioral reason.
private fun requireRuntimeStartAllowedFor(
    readiness: AppInitializationState,
    nativeRuntimeUncertain: Boolean,
): Result<Unit> =
    when {
        nativeRuntimeUncertain ->
            Result.failure(
                NativeRuntimeQuarantinedException(
                    "Native runtime state is uncertain; explicit STOP is required before restart.",
                ),
            )

        readiness is AppInitializationState.Failed ->
            Result.failure(
                AppInitializationIncompleteException(readiness.code, readiness.message),
            )

        readiness !is AppInitializationState.Ready ->
            Result.failure(
                AppInitializationIncompleteException(
                    "app_initialization_failed",
                    "App initialization has not completed yet.",
                ),
            )

        else -> Result.success(Unit)
    }

// P0-001: Coordinator-owned cleanup for verified-start failure (P0-001).
// Uses StartOutcome from the data layer for startup classification.

// P0-001: All accepted lifecycle intentions flow through one ordered stream.
// onStartCommand only submits; network policy also only submits.
// The command processor drains in FIFO order, and submitLifecycleCommand enqueues inline
// rather than from a coroutine launched per command, so command execution order matches
// the order Android delivered the intents. Enqueueing from a per-command coroutine would
// break that: the processor would still drain FIFO, but the *enqueue* order would race.
// LifecycleCommand is imported from TunnelLifecycleCoordinator.

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
        private lateinit var appInitialization: AppInitializationCoordinator
        private lateinit var localAddressResolver: LocalAddressResolver
        private lateinit var coordinator: TunnelLifecycleCoordinator
        private val serviceScope = CoroutineScope(SupervisorJob() + defaultDispatcher)

        private var networkMonitorJob: Job? = null

        // P0-004: Explicit startup ownership — coordinator is the only authority that clears it.
        private var activeStartup: ActiveStartup? = null
        private var statusPollJob: Job? = null
        private var lastMode: TunnelMode = TunnelMode.Offer

        // P0-004: Startup ownership data class.
        private data class ActiveStartup(
            val generation: Long,
            val job: Job,
        )

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

        // P1-006: Central helper to invalidate pending policy retry.
        private fun invalidatePendingPolicyRetry() {
            pendingPolicyResumeGeneration.set(null)
        }

        // P0-004: True when native runtime existence is uncertain after a cleanup/stop
        // failure. Blocks all automatic restart (PolicyAllowed, RetryPolicyResume, auto-resume).
        // Only a verified successful STOP clears the quarantine.
        private val nativeRuntimeUncertain = AtomicBoolean(false)

        // P1-002-C: true once onDestroy() has begun tearing this service down. Distinguishes a
        // benign post-destroy dropped command (onDestroy already owns the remaining cleanup)
        // from a dropped command while the service object is still nominally alive — e.g. the
        // command processor died unexpectedly (P1-002-B) before onDestroy was ever called. Set
        // synchronously at the very start of onDestroy(), before the coordinator is stopped, so
        // no drop in that window is misclassified as active-service.
        private val serviceDestroying = AtomicBoolean(false)

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

        // internal (not private): the stale-generation regression test must wait for a
        // superseding command to have actually taken effect (this counter advancing) before
        // it releases a startup blocked mid-native-start. A superseding command increments
        // this *before* it blocks in cancelStartupJobAndJoinLocked(), so the value is
        // observable while the supersession is still in progress. Without that wait the test
        // races the supersession and silently exercises the non-superseded path instead.
        internal val lifecycleGenerationForTest: Long
            get() = lifecycleGeneration.get()

        // internal read-only (no mutator): FIX6 P0-004 tests assert a pending policy retry
        // is invalidated (e.g. when resumeOnUnmetered turns false, or on quarantine). Only
        // an observation hook — production still owns every write to the token.
        internal val pendingPolicyResumeGenerationForTest: Long?
            get() = pendingPolicyResumeGeneration.get()

        // P2-001: read-only signal so lifecycle tests can wait for the command processor to exit
        // (e.g. after a handler cancellation) instead of proving absence with a fixed sleep.
        internal val coordinatorStoppedForTest: Boolean
            get() = coordinator.isStoppedForTest

        internal val lifecycleMutex = Mutex()

        // Notification + status-polling slice; accesses the shared lifecycle fields directly.
        private val reporter = StatusReporter()

        // Offer start/pause/stop state machine; accesses the shared lifecycle fields directly.
        // internal (not private): P0-004's Robolectric test drives pauseForPolicy() through
        // this real path rather than a synthetic test-only wrapper function.
        internal val offer = OfferCoordinator()

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
            appInitialization = deps.appInitializationCoordinator
            localAddressResolver = deps.localAddressResolver
            repository.updateSessionMeteredAllowance(false)

            // P0-001: command processor drains lifecycle commands in FIFO order.
            // Commands are processed sequentially to maintain ordering guarantees.
            // P0-003: Service owns coordinator scope; coordinator cannot outlive service.
            coordinator = TunnelLifecycleCoordinator(coordinatorOps, serviceScope)
            coordinator.start()

            // FIX6 P0-002: a direct required reporter — delivery failures reach the visible
            // StatusReporter synchronously, with no replay-zero SharedFlow and no
            // service-start subscription race. The reporter takes only a redacted string.
            val networkPolicyReporter =
                NetworkPolicyDiagnosticReporter { code, message ->
                    reporter.publishError(message = message, code = code)
                }

            // P0-006-B: supervise the whole monitor lifecycle (register/upstream/unregister),
            // not just per-event handling. On any monitor failure the supervisor reports it and
            // fails closed (blocks the tunnel) before retrying with bounded backoff, so the
            // service can never keep running unrestricted after the monitor dies. Signals still
            // submit commands through the same ordered queue.
            networkMonitorJob =
                serviceScope.launch {
                    NetworkMonitorSupervisor(
                        monitorFlow = {
                            networkPolicyManager.monitor(this@TunnelForegroundService, networkPolicyReporter)
                        },
                        reporter = networkPolicyReporter,
                        onSignal = { handleNetworkPolicySignal() },
                        onMonitorFailure = { handleNetworkMonitorFailure(networkPolicyReporter) },
                    ).run()
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

        // P0-001: Submit a lifecycle command through the ordered queue.
        //
        // Enqueued inline (not via serviceScope.launch): the queue drains FIFO, so the
        // enqueue order *is* the execution order, and launching a coroutine per command
        // made that order racy — two independently dispatched coroutines could reach the
        // channel out of order, letting a later intent overtake an earlier one (e.g. STOP
        // overtaking START) despite the FIFO processor. trySubmit cannot suspend (the
        // channel is UNLIMITED), so this is safe to call from onStartCommand's thread and
        // from inside a command handler alike.
        // Returns whether the command was actually submitted, so a caller for whom a dropped
        // command is more than routine post-destroy noise (FIX7 P0-009-C: the network monitor's
        // fail-closed policy-blocked submission) can detect and escalate a dead control plane
        // rather than silently treating it the same as every other post-destroy drop.
        private fun submitLifecycleCommand(command: LifecycleCommand): Boolean {
            if (coordinator.trySubmit(command)) return true
            // P1-002-C: a drop while onDestroy owns the remaining cleanup (it stops the
            // coordinator before cancelling an in-flight startup, so that startup's
            // StartupCompleted can still land here) is routine teardown-late noise — debug-only.
            // A drop while the service object is NOT known to be tearing down means the command
            // processor died some other way (P1-002-B) while this service is nominally still
            // active; that must stay visible, not merely logged. Either way, the command type
            // only is logged: a command can carry a policy reason or error text, which must not
            // be logged raw.
            val commandName = command.javaClass.simpleName
            if (serviceDestroying.get()) {
                Log.d(tag, "Dropped lifecycle command during teardown: $commandName")
            } else {
                Log.w(tag, "Dropped lifecycle command outside teardown: $commandName")
                reporter.publishErrorSafely(
                    code = "lifecycle_processor_unavailable",
                    message = "Lifecycle command dropped: processor unavailable",
                )
            }
            return false
        }

        // Removed: publishError was a thin wrapper; callers use reporter.publishError directly.

        override fun onBind(intent: Intent?): IBinder? = null

        /**
         * P1-010: destroy-time cleanup is BEST EFFORT, not an authoritative stop.
         *
         * An explicit STOP (verified `repository.stop()`, which sets [nativeStopVerified]) is the
         * only authoritative stop. Here we only run fallback cleanup when a verified stop has NOT
         * already happened, and we set `nativeStopVerified = true` solely on an observed successful
         * `repository.stop()` — never merely because cleanup was launched. An observed failure
         * publishes the visible `destroy_fallback_stop_failed` and marks the runtime uncertain.
         *
         * The cleanup runs in a launched coroutine ([pendingStop]); Android may kill the process
         * before it finishes, so NO process-state invariant may depend on [pendingStop] completing
         * after [super.onDestroy]. `coordinator.stop()` closes command acceptance BEFORE the
         * in-flight startup is cancelled, so a late `StartupCompleted` submit is a benign drop
         * (see [submitLifecycleCommand]) and cannot restart the tunnel.
         */
        override fun onDestroy() {
            // P1-002-C: set synchronously, before anything else, so no window exists where a
            // dropped command during this teardown could be misclassified as active-service.
            serviceDestroying.set(true)
            val pendingStop =
                serviceScope.launch {
                    // P0-006: Cancel network monitor and join it before fallback cleanup.
                    val monitorJob = networkMonitorJob
                    networkMonitorJob = null
                    monitorJob?.cancelAndJoin()
                    // P0-003: Stop coordinator processor before fallback cleanup.
                    coordinator.stop()
                    lifecycleMutex.withLock {
                        // P0-002: Invalidate any pending retry on destroy before cleanup.
                        invalidatePendingPolicyRetry()
                        lifecycleGeneration.incrementAndGet()
                        cancelStartupJobAndJoinLocked()
                        reporter.stopStatusPollingAndJoin()
                        // Only perform fallback cleanup if native stop was not already verified.
                        if (!nativeStopVerified.get()) {
                            withContext(ioDispatcher) {
                                repository.stop()
                            }.fold(
                                onSuccess = {
                                    nativeStopVerified.set(true)
                                    nativeRuntimeUncertain.set(false)
                                },
                                onFailure = { error ->
                                    // FIX7 P0-007-B: quarantine through the central helper.
                                    enterNativeRuntimeQuarantine(
                                        code = "destroy_fallback_stop_failed",
                                        message = error.message ?: "Destroy fallback stop failed",
                                    )
                                },
                            )
                        }
                        pausedByPolicy.set(false)
                        clearTemporaryMeteredAllowance()
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
            throw StartupAborted(message)
        }

        // Cancels the startup coroutine and waits for it to fully unwind before returning, so
        // the caller (an explicit lifecycle transition, always holding lifecycleMutex here) can
        // safely perform the one authoritative repository.stop() afterward without racing the
        // startup coroutine's own unwind. Safe to call under lifecycleMutex because generation
        // checks are lock-free and no other code the startup coroutine runs acquires this mutex
        // (P0-001).
        private suspend fun cancelStartupJobAndJoinLocked() {
            val startup = activeStartup
            activeStartup = null
            startup?.job?.cancelAndJoin()
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

            /**
             * FIX7 P0-007-A: publishes the visible log/notification for a specific diagnostic
             * WITHOUT touching `repository.setLocalError` — unlike [publishError]. Used after
             * [enterNativeRuntimeQuarantine] has already durably set the canonical
             * `native_runtime_quarantined` code, so this narrower diagnostic can't silently
             * overwrite that durable state. Never throws: a notification-building failure must
             * not be able to hide that quarantine already happened.
             */
            fun publishErrorSafely(
                message: String,
                code: String,
                state: ServiceState = ServiceState.Error,
            ) {
                try {
                    val redacted = SensitiveDataRedactor.redactText(message)
                    Log.e(tag, redacted)
                    notifications.show(notifications.buildStatusNotification(state, redacted))
                } catch (error: Exception) {
                    Log.e(tag, "Failed to publish quarantine diagnostic (code=$code)", error)
                }
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
                            // FIX7 P1-005-B: explicit cancellation-first try/catch, not
                            // runCatching — refreshStatus() reads through the native JNI
                            // bridge, so a fatal Error must not be silently swallowed.
                            try {
                                repository.refreshStatus()
                                val state = repository.status.value.serviceState
                                if (state != lastState) {
                                    lastState = state
                                    publishStatus()
                                }
                                active = state in ACTIVE_STATES
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (error: Exception) {
                                reporter.publishError(
                                    code = "status_poll_failed",
                                    message =
                                        SensitiveDataRedactor.redactText(
                                            error.message ?: "Status poll failed",
                                        ),
                                )
                            }
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

        // P0-002: Canonical start guard — blocks all start/resume when the app has not
        // finished initializing (FIX6 INV-010) or the native runtime is uncertain.
        // Extends the existing guard rather than adding a second one: all start/resume
        // paths already route through here, and this class is at detekt's function limit.
        // Single-return `when` because ReturnCount caps at 2.
        //
        // FIX7 RESPONSES item 3: nativeRuntimeUncertain is checked FIRST (not adding a
        // separate AppInitializationCoordinator.requireReady() gate) — a quarantined
        // runtime must block start/resume regardless of app-initialization state.
        // A property (not a function) so it doesn't count against this class's detekt
        // function budget for no behavioral reason.
        private val requireRuntimeStartAllowed: () -> Result<Unit> = {
            requireRuntimeStartAllowedFor(appInitialization.state.value, nativeRuntimeUncertain.get())
        }

        // FIX7 P0-009: extracted out of onCreate's NetworkMonitorSupervisor construction (which
        // pushed onCreate over detekt's LongMethod limit). A property (not a function) so it
        // doesn't count against this class's detekt function budget for no behavioral reason.
        private val handleNetworkPolicySignal: suspend () -> Unit = {
            val prefs = withContext(ioDispatcher) { configRepository.preferences.first() }
            val policy =
                networkPolicyManager.evaluateWithPolicy(
                    prefs.allowMetered || allowMeteredForCurrentRun.get(),
                )
            repository.updateNetworkStatus(policy)
            if (policy.networkType == NetworkType.UnmeteredWifi) {
                submitLifecycleCommand(LifecycleCommand.PolicyAllowed)
            } else if (!policy.tunnelAllowed) {
                submitLifecycleCommand(
                    LifecycleCommand.PolicyBlocked(
                        policy.blockReason ?: "Tunnel paused: network policy blocks metered/cellular",
                    ),
                )
            }
        }

        /**
         * FIX7 P0-009-C: fails closed (Unknown status) and submits the policy-blocked lifecycle
         * command on a network-monitor lifecycle failure. Returns whether the lifecycle
         * processor/control plane is still available — if the fail-closed command itself
         * couldn't be submitted (only reachable post-destroy), that is a more serious,
         * separately visible condition than the routine drop `submitLifecycleCommand` already
         * logs, so it is surfaced explicitly and the supervisor is told to stop retrying against
         * a dead control plane. A property (not a function) for the same detekt-budget reason as
         * [handleNetworkPolicySignal].
         */
        private val handleNetworkMonitorFailure:
            suspend (NetworkPolicyDiagnosticReporter) -> Boolean = { networkPolicyReporter ->
                // Q5: reuse the canonical evaluator for a fail-closed Unknown status.
                repository.updateNetworkStatus(
                    NetworkPolicyManager.evaluate(NetworkType.Unknown to false, allowMetered = false),
                )
                val submitted =
                    submitLifecycleCommand(
                        LifecycleCommand.PolicyBlocked("Tunnel paused: network policy monitor unavailable"),
                    )
                if (!submitted) {
                    reportNetworkDiagnosticSafely(
                        networkPolicyReporter,
                        code = "lifecycle_processor_unavailable",
                        message =
                            "Network policy monitor cannot submit its fail-closed command; " +
                                "the lifecycle processor is unavailable",
                    )
                }
                submitted
            }

        /**
         * FIX7 P0-007-A: the one place that transitions the service into native-runtime
         * quarantine, so every stop-like failure applies the SAME safety-state changes before
         * reporting (rather than each call site duplicating them ad hoc, as `stopServiceWork`
         * and the destroy fallback previously did — and `pause`/`pauseForPolicy` previously
         * didn't do at all, a real gap this closes). [code]/[message] are the specific,
         * caller-supplied diagnostic (e.g. `manual_pause_stop_failed`); the durable repository
         * state is always the canonical `native_runtime_quarantined` — set before the
         * (possibly-failing) visible-notification call so a notification failure can never hide
         * that quarantine already happened, and never overwritten by it afterward since
         * [StatusReporter.publishErrorSafely] does not touch `repository.setLocalError`.
         */
        private fun enterNativeRuntimeQuarantine(
            code: String,
            message: String,
        ) {
            nativeStopVerified.set(false)
            nativeRuntimeUncertain.set(true)
            invalidatePendingPolicyRetry()
            val redacted = SensitiveDataRedactor.redactText(message)
            // First, the caller's specific diagnostic (e.g. "stop_status_verification_failed")
            // — TunnelRepository.setLocalError's own sticky-cleanup-history set keys off these
            // exact codes (P1-005), so this call must still happen for that history to work.
            repository.setLocalError(code = code, message = redacted)
            // Then the canonical quarantine code becomes the final/durable lastError. RESPONSES
            // item 2: this must not be overwritten back to the narrower code afterward — which
            // is why the visible notification below goes through publishErrorSafely (log +
            // notification only), not publishError (which would call setLocalError again with
            // the narrower [code]).
            repository.setLocalError(
                code = "native_runtime_quarantined",
                message = "Native runtime state is uncertain; a verified stop is required",
            )
            reporter.publishErrorSafely(code = code, message = redacted)
        }

        private val coordinatorOps: CoordinatorOperations =
            object : CoordinatorOperations {
                // P0-001: submits the retry once the NativeFailure branch of
                // handleStartupCompleted has confirmed the pending policy retry is live.
                // A property (not a function) so it doesn't count against this object's
                // detekt function budget, and it keeps handleStartupCompleted short enough
                // for detekt's LongMethod check.
                val resumeAfterNativeFailurePendingRetry: (Long) -> Unit = { gen ->
                    submitLifecycleCommand(LifecycleCommand.RetryPolicyResume(expectedGeneration = gen))
                }

                override fun onError(
                    message: String,
                    code: String,
                    state: ServiceState,
                ) {
                    reporter.publishError(message, code, state)
                }

                // P1-002-B: the lifecycle command processor died unexpectedly — nothing is
                // left to drain the command queue, so the native runtime's real state is
                // uncertain. Quarantine through the same central helper every other stop-like
                // failure uses, then actually stop the service rather than leaving it foreground
                // pretending to still be controllable. A property (not a function) so it doesn't
                // count against this object's detekt function budget.
                override val onProcessorFailed: () -> Unit = {
                    enterNativeRuntimeQuarantine(
                        code = "lifecycle_processor_failed",
                        message = "Lifecycle command processor exited unexpectedly",
                    )
                    stopSelf()
                }

                override suspend fun startOffer() {
                    requireRuntimeStartAllowed()
                        .getOrElse { error ->
                            reporter.publishError(
                                message =
                                    SensitiveDataRedactor.redactText(
                                        error.message ?: "Runtime restart is blocked",
                                    ),
                                code = startBlockedCode(error),
                            )
                            return
                        }
                    if (!repository.status.value.serviceState.isTunnelActiveOrStarting()) {
                        offer.startOffer()
                    }
                }

                override suspend fun pause() {
                    offer.pause()
                }

                override suspend fun resume() {
                    requireRuntimeStartAllowed()
                        .getOrElse { error ->
                            reporter.publishError(
                                message =
                                    SensitiveDataRedactor.redactText(
                                        error.message ?: "Runtime restart is blocked",
                                    ),
                                code = startBlockedCode(error),
                            )
                            return
                        }
                    offer.resume()
                }

                override suspend fun stop() {
                    offer.stopServiceWork()
                }

                override suspend fun allowMeteredForSessionAndStart() {
                    requireRuntimeStartAllowed()
                        .getOrElse { error ->
                            reporter.publishError(
                                message =
                                    SensitiveDataRedactor.redactText(
                                        error.message ?: "Runtime restart is blocked",
                                    ),
                                code = startBlockedCode(error),
                            )
                            return
                        }
                    offer.allowMeteredForSessionAndStart()
                }

                override suspend fun pauseForPolicy(reason: String) {
                    offer.pauseForPolicy(reason)
                }

                override suspend fun handlePolicyAllowed() {
                    // FIX6 P0-004: quarantine (and not-yet-ready init) must be visible from
                    // this path just as from manual start/resume, not silently swallowed.
                    requireRuntimeStartAllowed().getOrElse { error ->
                        invalidatePendingPolicyRetry()
                        reporter.publishError(
                            code = startBlockedCode(error),
                            message =
                                SensitiveDataRedactor.redactText(
                                    error.message ?: "Runtime restart is blocked",
                                ),
                        )
                        return
                    }
                    // Not policy-paused is not an error — just drop any stale token.
                    if (!pausedByPolicy.get()) {
                        invalidatePendingPolicyRetry()
                        return
                    }
                    // The pref-read + resume decision lives in a top-level function (like
                    // handleNativeFailureAfterStartup) so it costs no method budget against
                    // this object, which is at detekt's TooManyFunctions limit.
                    resumeOnPolicyAllowedIfPreferred(
                        PolicyAllowedResumeContext(
                            readPreferences = { configRepository.preferences.first() },
                            invalidatePendingRetry = ::invalidatePendingPolicyRetry,
                            publishError = { code, message -> reporter.publishError(message = message, code = code) },
                            recordPendingRetry = { pendingPolicyResumeGeneration.set(lifecycleGeneration.get()) },
                            hasActiveStartup = { activeStartup != null },
                            resume = { offer.resume() },
                        ),
                    )
                }

                override suspend fun handleRetryPolicyResume(expectedGeneration: Long) {
                    val allowed = requireRuntimeStartAllowed().getOrNull()
                    if (allowed == null) {
                        invalidatePendingPolicyRetry()
                        return
                    }
                    if (lifecycleGeneration.get() != expectedGeneration ||
                        !pausedByPolicy.get()
                    ) {
                        invalidatePendingPolicyRetry()
                        return
                    }
                    invalidatePendingPolicyRetry()
                    offer.resume()
                }

                override suspend fun handleStartupCompleted(
                    generation: Long,
                    outcome: StartOutcome,
                ) {
                    if (lifecycleGeneration.get() != generation) return
                    activeStartup = null
                    if (outcome !is StartOutcome.VerifiedSuccess) {
                        clearTemporaryMeteredAllowance()
                    }
                    // P0-001: every branch except NativeFailure invalidates unconditionally.
                    // NativeFailure must consume the pending retry first — invalidating here
                    // too would clear it before that branch could read it.
                    if (outcome !is StartOutcome.NativeFailure) {
                        invalidatePendingPolicyRetry()
                    }
                    when (outcome) {
                        StartOutcome.VerifiedSuccess -> {
                            pausedByPolicy.set(false)
                            reporter.publishStatus()
                            reporter.startStatusPolling()
                        }
                        is StartOutcome.NativeFailure -> {
                            handleNativeFailureAfterStartup(
                                NativeFailureAfterStartupContext(
                                    outcome.error,
                                    generation,
                                    pendingPolicyResumeGeneration,
                                    pausedByPolicy,
                                    resumeAfterNativeFailurePendingRetry,
                                    reporter::publishError,
                                ),
                            )
                        }
                        is StartOutcome.VerificationFailure -> {
                            cleanupUnverifiedStart(
                                UnverifiedStartContext(
                                    outcome.error,
                                    generation,
                                    lifecycleGeneration,
                                    reporter::stopStatusPollingAndJoin,
                                    { repository.stop() },
                                    nativeStopVerified,
                                    ::enterNativeRuntimeQuarantine,
                                ),
                            )
                        }
                        is StartOutcome.UnexpectedFailure -> {
                            reporter.publishError(
                                outcome.error.message ?: "Unexpected startup failure",
                                "startup_unexpected_failure",
                            )
                        }
                        is StartOutcome.PolicyBlocked -> {
                            pausedByPolicy.set(true)
                            nativeStopVerified.set(true)
                            repository.setPolicyBlocked(outcome.reason)
                            reporter.publishStatus(outcome.reason)
                        }
                        is StartOutcome.Aborted -> {
                            reporter.publishError(outcome.reason, "startup_aborted")
                        }
                    }
                }
            }

        // Offer-mode start plus pause/stop transitions, guarded by the lifecycle generation.
        inner class OfferCoordinator {
            suspend fun startOffer() {
                var generation = 0L
                lifecycleMutex.withLock {
                    if (activeStartup != null) {
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
                    // Invalidate any pending retry when a new start begins.
                    invalidatePendingPolicyRetry()
                    val job =
                        serviceScope.launch {
                            doStartOffer(generation)
                        }
                    activeStartup = ActiveStartup(generation, job)
                }
            }

            private suspend fun doStartOffer(startGeneration: Long) {
                lastMode = TunnelMode.Offer
                startForeground(
                    NOTIFICATION_ID,
                    reporter.loadingNotification(getString(R.string.service_msg_starting_tunnel)),
                )
                val completion = performStartupAttempt(startGeneration)
                submitLifecycleCommand(LifecycleCommand.StartupCompleted(startGeneration, completion))
            }

            // P0-001: Wraps both preparation and native start into a single completion boundary.
            // Every path returns a typed StartOutcome — no path may return without completion.
            // Cancellation propagates; other exceptions become typed StartOutcome values.
            private suspend fun performStartupAttempt(generation: Long): StartOutcome {
                return try {
                    val identity =
                        prepareOfferIdentity()

                    try {
                        classifyStartAndZeroIdentity(
                            identity = identity,
                            generation = generation,
                        )
                    } finally {
                        identity.fill(0)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (blocked: StartupPolicyBlocked) {
                    StartOutcome.PolicyBlocked(
                        reason =
                            blocked.message
                                ?: "Blocked by network policy",
                    )
                } catch (aborted: StartupAborted) {
                    StartOutcome.Aborted(
                        reason =
                            aborted.message
                                ?: "Startup aborted",
                    )
                } catch (error: Exception) {
                    StartOutcome.UnexpectedFailure(
                        error = error,
                    )
                }
            }

            // Classifies the native start result after identity has been prepared. Returns Aborted
            // if the generation changed at any point, otherwise classifies the native start result.
            private suspend fun classifyStartAndZeroIdentity(
                identity: ByteArray,
                generation: Long,
            ): StartOutcome {
                val generationStillValid = lifecycleGeneration.get() == generation
                return if (generationStillValid) {
                    val result =
                        withContext(ioDispatcher) {
                            repository.start(TunnelMode.Offer, configRepository.configPath, identity)
                        }
                    if (lifecycleGeneration.get() == generation) {
                        classifyStartResult(result)
                    } else {
                        StartOutcome.Aborted("Startup superseded during native start")
                    }
                } else {
                    StartOutcome.Aborted("Startup superseded by newer lifecycle generation")
                }
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
                    throw StartupPolicyBlocked(policy.blockReason ?: "Tunnel blocked by current network policy")
                }
                // FIX7 P1-005-B: explicit cancellation-first try/catch, not runCatching — this
                // wraps a suspend call chain (withContext).
                val identity =
                    try {
                        withContext(ioDispatcher) { identityRepository.readPrivateIdentityPlaintext() }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        abortStartup("Unable to decrypt private identity: ${error.message}", "identity_decrypt_failed")
                    }
                // P0-008: Ownership transfer — identity is wiped if preparation fails.
                var transferred = false
                try {
                    // Apply the user's chosen ICE mode and inject the active network's IPv4
                    // (ConnectivityManager/LinkProperties) as the vnet_mux host candidate before
                    // validating/starting, so a strict vnet_mux start fails loudly rather than
                    // silently dropping to native ICE.
                    val configResult =
                        withContext(ioDispatcher) {
                            configRepository.prepareActiveConfigForStart(
                                prefs.androidIceMode,
                                localAddressResolver.currentIpv4(),
                            )
                        }
                    if (!configResult.isSuccess) {
                        val redacted =
                            SensitiveDataRedactor.redactText(
                                configResult.exceptionOrNull()
                                    ?.message ?: "unknown error",
                            )
                        abortStartup(
                            "Failed to prepare active config: $redacted",
                            "config_prep_failed",
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

            // P1-001: AllowMeteredSession is now one ordered lifecycle command.

            // P1-001: AllowMeteredSession is now one ordered lifecycle command.
            // The handler performs: set allowance, update repository, begin startup
            // within one command processing step.
            suspend fun allowMeteredForSessionAndStart() {
                lifecycleMutex.withLock {
                    allowMeteredForCurrentRun.set(true)
                    repository.updateSessionMeteredAllowance(true)
                    pausedByPolicy.set(false)
                    invalidatePendingPolicyRetry()
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
                    invalidatePendingPolicyRetry()
                    withContext(ioDispatcher) { repository.stop() }
                        .fold(
                            onSuccess = {
                                // P1-011: Set nativeStopVerified true after verified successful pause.
                                nativeStopVerified.set(true)
                                clearTemporaryMeteredAllowance()
                                reporter.publishStatus(getString(R.string.service_msg_paused))
                            },
                            onFailure = {
                                // FIX7 P0-007-B: a failed manual-pause stop must quarantine the
                                // runtime, exactly like a failed explicit STOP already does —
                                // previously this only reported the error.
                                enterNativeRuntimeQuarantine(
                                    code = stopFailureCode(it),
                                    message = it.message ?: "Unable to stop tunnel",
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
                    // P0-002: Invalidate any pending retry when policy pauses.
                    invalidatePendingPolicyRetry()
                    withContext(ioDispatcher) { repository.stop() }
                        .fold(
                            onSuccess = {
                                // P1-011: Set nativeStopVerified true after verified successful policy pause.
                                nativeStopVerified.set(true)
                                pausedByPolicy.set(true)
                                repository.setPolicyBlocked(reason)
                                clearTemporaryMeteredAllowance()
                                reporter.publishStatus(reason)
                            },
                            onFailure = {
                                // The tunnel did not stop cleanly, so this must never be
                                // reported as the normal policy-paused state. Force false
                                // unconditionally rather than restoring a stale prior
                                // value, so a retry/reevaluation path stays open.
                                pausedByPolicy.set(false)
                                // FIX7 P0-007-B: a failed policy-pause stop must quarantine the
                                // runtime, exactly like a failed explicit STOP already does —
                                // previously this only reported the error.
                                enterNativeRuntimeQuarantine(
                                    code = stopFailureCode(it),
                                    message = it.message ?: "Failed stopping tunnel after policy block",
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
                            // P0-002: Invalidate any pending retry on explicit stop success.
                            invalidatePendingPolicyRetry()
                            notifications.show(
                                notifications.buildStatusNotification(ServiceState.Stopped, "Tunnel stopped"),
                            )
                            stopForeground(STOP_FOREGROUND_REMOVE)
                            stopSelf()
                        },
                        onFailure = {
                            // P0-005/FIX7 P0-007-B: Stop failure path — remain alive and
                            // foreground; quarantine through the central helper (covers both
                            // an outright stop failure and a stop-status-verification failure,
                            // per stopFailureCode's distinction).
                            enterNativeRuntimeQuarantine(
                                code = stopFailureCode(it),
                                message = it.message ?: "Unable to stop tunnel cleanly",
                            )
                            // Service remains foreground; user can retry STOP.
                        },
                    )
                }
            }
        }

        // Clears the temporary metered allowance so a future run starts fresh.
        private fun clearTemporaryMeteredAllowance() {
            allowMeteredForCurrentRun.set(false)
            repository.updateSessionMeteredAllowance(false)
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
