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
import com.phillipchin.webrtctunnel.data.TunnelRepository
import com.phillipchin.webrtctunnel.model.AndroidAppPreferences
import com.phillipchin.webrtctunnel.model.NetworkType
import com.phillipchin.webrtctunnel.model.ServiceState
import com.phillipchin.webrtctunnel.model.TunnelMode
import com.phillipchin.webrtctunnel.model.isTunnelRunning
import com.phillipchin.webrtctunnel.network.LocalAddressResolver
import com.phillipchin.webrtctunnel.network.NetworkPolicyManager
import com.phillipchin.webrtctunnel.notification.NotificationController
import com.phillipchin.webrtctunnel.security.IdentityRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
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
        private var allowMeteredForCurrentRun: Boolean = false

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
            networkMonitorJob =
                serviceScope.launch {
                    networkPolicyManager.monitor(this@TunnelForegroundService).collect { _ ->
                        val prefs = withContext(ioDispatcher) { configRepository.preferences.first() }
                        val policy = evaluatePolicy(prefs)
                        repository.updateNetworkStatus(policy)
                        if (policy.networkType == NetworkType.UnmeteredWifi) {
                            if (pausedByPolicy.get() && prefs.resumeOnUnmetered) {
                                pausedByPolicy.set(false)
                                serviceScope.launch { offer.resume() }
                            }
                        } else if (!policy.tunnelAllowed) {
                            val current = repository.status.value.serviceState
                            // A Listening tunnel is still running and must pause on a policy block.
                            if (current.isTunnelRunning()) {
                                serviceScope.launch {
                                    offer.pauseForPolicy(
                                        policy.blockReason ?: "Tunnel paused: network policy blocks metered/cellular",
                                    )
                                }
                            }
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
            when (intent.action) {
                ACTION_START_OFFER -> serviceScope.launch { offer.startOffer() }
                ACTION_START_ANSWER -> {
                    publishError(
                        message = "Answer mode is not available on Android",
                        code = "answer_mode_disabled",
                    )
                    stopSelf(startId)
                }
                ACTION_STOP -> {
                    serviceScope.launch { offer.stopServiceWork() }
                }
                ACTION_PAUSE -> serviceScope.launch { offer.pause() }
                ACTION_RESUME -> serviceScope.launch { offer.resume() }
                ACTION_ALLOW_METERED_SESSION -> serviceScope.launch { offer.allowMeteredForSessionAndStart() }
                else -> stopSelf(startId)
            }
            return START_NOT_STICKY
        }

        private fun isCurrentGeneration(startGeneration: Long): Boolean = lifecycleGeneration.get() == startGeneration

        private fun abortStartup(
            message: String,
            code: String,
            state: ServiceState = ServiceState.Error,
        ): Nothing {
            publishError(message = message, code = code, state = state)
            throw StartupAborted()
        }

        private fun publishError(
            message: String,
            code: String = "service_error",
            state: ServiceState = ServiceState.Error,
        ) = reporter.publishError(message = message, code = code, state = state)

        override fun onBind(intent: Intent?): IBinder? = null

        override fun onDestroy() {
            networkMonitorJob?.cancel()
            val pendingStop =
                serviceScope.launch {
                    lifecycleMutex.withLock {
                        lifecycleGeneration.incrementAndGet()
                        cancelStartupJobAndJoinLocked()
                        reporter.stopStatusPollingAndJoin()
                        withContext(ioDispatcher) {
                            repository.stop()
                        }.onFailure {
                            publishError(
                                message = it.message ?: "Unable to stop tunnel",
                                code = "stop_failed",
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

        private fun evaluatePolicy(prefs: AndroidAppPreferences) =
            networkPolicyManager.evaluateWithPolicy(prefs.allowMetered || allowMeteredForCurrentRun)

        private fun clearTemporaryMeteredAllowance() {
            allowMeteredForCurrentRun = false
            repository.updateSessionMeteredAllowance(false)
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
                    // Listening/Serving/Connected all mean a run is already up — don't start again.
                    if (current.isTunnelRunning()) {
                        reporter.publishStatus(getString(R.string.service_msg_already_running))
                        return
                    }
                    generation = lifecycleGeneration.incrementAndGet()
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
                val policy = evaluatePolicy(prefs)
                repository.updateNetworkStatus(policy)
                if (!policy.tunnelAllowed) {
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
                // Apply the user's chosen ICE mode and inject the active network's IPv4
                // (ConnectivityManager/LinkProperties) as the vnet_mux host candidate before
                // validating/starting, so a strict vnet_mux start fails loudly rather than
                // advertising a stale address or silently dropping to native ICE.
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
                return identity
            }

            // Starts the tunnel under the lifecycle-generation guard: aborts if a newer start
            // superseded this one (before or after the native start) or if it was cancelled.
            private suspend fun runOfferStart(
                identity: ByteArray,
                startGeneration: Long,
            ) {
                // The native start copies the identity across JNI, so the plaintext buffer is
                // wiped once start returns (or any early exit), even on failure/cancellation.
                try {
                    if (!isCurrentGeneration(startGeneration)) {
                        return
                    }
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
                    if (!isCurrentGeneration(startGeneration)) {
                        // The lifecycle transition that advanced generation owns cleanup; no
                        // second, independent stop call here (P0-001).
                        return
                    }
                    result.onSuccess {
                        pausedByPolicy.set(false)
                        reporter.publishStatus()
                        reporter.startStatusPolling()
                    }.onFailure {
                        reporter.publishError(
                            message = it.message ?: "Unable to start tunnel",
                            code = "native_start_failed",
                        )
                    }
                } finally {
                    identity.fill(0)
                }
            }

            suspend fun allowMeteredForSessionAndStart() {
                lifecycleMutex.withLock {
                    allowMeteredForCurrentRun = true
                    repository.updateSessionMeteredAllowance(true)
                    pausedByPolicy.set(false)
                }
                startOffer()
            }

            private fun startAnswer() {
                reporter.publishError(
                    message = "Answer mode is not available on Android",
                    code = "answer_mode_disabled",
                )
            }

            suspend fun resume() {
                when (lastMode) {
                    TunnelMode.Offer -> startOffer()
                    TunnelMode.Answer -> startAnswer()
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
                                reporter.publishStatus(getString(R.string.service_msg_paused))
                            },
                            onFailure = {
                                reporter.publishError(
                                    message = it.message ?: "Unable to stop tunnel",
                                    code = "stop_failed",
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
                                    code = "stop_failed",
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
                            notifications.show(
                                notifications.buildStatusNotification(ServiceState.Stopped, "Tunnel stopped"),
                            )
                        },
                        onFailure = {
                            // The service still stops itself below, but must not claim a
                            // clean tunnel stop it didn't actually achieve.
                            reporter.publishError(
                                message = it.message ?: "Unable to stop tunnel cleanly",
                                code = "stop_failed",
                            )
                        },
                    )
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
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
