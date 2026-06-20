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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

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
        private var pausedByPolicy: Boolean = false
        private var allowMeteredForCurrentRun: Boolean = false
        private var lifecycleGeneration: Long = 0
        private val lifecycleMutex = Mutex()

        // Notification + status-polling slice; accesses the shared lifecycle fields directly.
        private val reporter = StatusReporter()

        // Offer start/pause/stop state machine; accesses the shared lifecycle fields directly.
        private val offer = OfferCoordinator()

        override fun onCreate() {
            super.onCreate()
            notifications = NotificationController(this)
            notifications.ensureChannels()
            startForeground(NOTIFICATION_ID, reporter.loadingNotification("Preparing tunnel service"))
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
                            if (pausedByPolicy && prefs.resumeOnUnmetered) {
                                pausedByPolicy = false
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

        private suspend fun isCurrentGeneration(startGeneration: Long): Boolean =
            lifecycleMutex.withLock { lifecycleGeneration == startGeneration }

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
            reporter.stopStatusPolling()
            val pendingStop =
                serviceScope.launch {
                    lifecycleMutex.withLock {
                        lifecycleGeneration += 1
                        cancelStartupJobLocked()
                        withContext(ioDispatcher) {
                            repository.stop()
                        }.onFailure {
                            publishError(
                                message = it.message ?: "Unable to stop tunnel",
                                code = "stop_failed",
                            )
                        }
                        pausedByPolicy = false
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

        private fun cancelStartupJobLocked() {
            startupJob?.cancel()
            startupJob = null
        }

        // Notification rendering and status polling for the active tunnel.
        inner class StatusReporter {
            fun publishStatus(body: String? = null) {
                val state = repository.status.value.serviceState
                val text =
                    body ?: when (state) {
                        ServiceState.Connected -> "Tunnel connected"
                        ServiceState.Serving -> "Serving; waiting for a peer"
                        ServiceState.Listening -> "Listening; waiting for a local client"
                        ServiceState.Starting, ServiceState.Connecting, ServiceState.Reconnecting -> "Connecting…"
                        ServiceState.PausedMeteredBlocked -> "Cellular/metered network blocked"
                        ServiceState.NoNetwork -> "No network available"
                        ServiceState.Stopping -> "Stopping…"
                        ServiceState.Stopped -> "Tunnel stopped"
                        ServiceState.Error, ServiceState.ConfigInvalid ->
                            repository.status.value.lastError?.message ?: "Error"
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
                    .setContentTitle("WebRTC Tunnel starting")
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
                        while (active && !pausedByPolicy) {
                            delay(STATUS_POLL_INTERVAL_MS)
                            if (pausedByPolicy) break
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
        }

        // Offer-mode start plus pause/stop transitions, guarded by the lifecycle generation.
        inner class OfferCoordinator {
            suspend fun startOffer() {
                var generation = 0L
                lifecycleMutex.withLock {
                    if (startupJob?.isActive == true) {
                        reporter.publishStatus("Tunnel startup already in progress")
                        return
                    }
                    val current = repository.status.value.serviceState
                    // Listening/Serving/Connected all mean a run is already up — don't start again.
                    if (current.isTunnelRunning()) {
                        reporter.publishStatus("Tunnel already running")
                        return
                    }
                    lifecycleGeneration += 1
                    generation = lifecycleGeneration
                    startupJob =
                        serviceScope.launch {
                            doStartOffer(generation)
                        }
                }
            }

            private suspend fun doStartOffer(startGeneration: Long) {
                lastMode = TunnelMode.Offer
                startForeground(NOTIFICATION_ID, reporter.loadingNotification("Starting tunnel"))
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
                // Inject the active network's IPv4 (ConnectivityManager/LinkProperties) as the
                // vnet_mux host candidate before validating/starting. With no address the field
                // is cleared, so a strict vnet_mux start fails loudly in native rather than
                // advertising a stale address or silently dropping to native ICE.
                withContext(ioDispatcher) {
                    configRepository.refreshAdvertisedAddress(localAddressResolver.currentIpv4())
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
                    val result =
                        try {
                            withContext(ioDispatcher) {
                                repository.start(TunnelMode.Offer, configRepository.configPath, identity)
                            }
                        } catch (_: CancellationException) {
                            withContext(ioDispatcher) { repository.stop() }
                            return
                        }
                    if (!isCurrentGeneration(startGeneration)) {
                        withContext(ioDispatcher) { repository.stop() }
                    } else {
                        result.onSuccess {
                            pausedByPolicy = false
                            reporter.publishStatus()
                            reporter.startStatusPolling()
                        }.onFailure {
                            reporter.publishError(
                                message = it.message ?: "Unable to start tunnel",
                                code = "native_start_failed",
                            )
                        }
                    }
                } finally {
                    identity.fill(0)
                }
            }

            suspend fun allowMeteredForSessionAndStart() {
                lifecycleMutex.withLock {
                    allowMeteredForCurrentRun = true
                    repository.updateSessionMeteredAllowance(true)
                    pausedByPolicy = false
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
                    lifecycleGeneration += 1
                    reporter.stopStatusPolling()
                    cancelStartupJobLocked()
                    withContext(ioDispatcher) {
                        repository.stop()
                    }.onFailure {
                        reporter.publishError(
                            message = it.message ?: "Unable to stop tunnel",
                            code = "stop_failed",
                        )
                    }
                    reporter.publishStatus("Tunnel paused")
                }
            }

            suspend fun pauseForPolicy(reason: String) {
                lifecycleMutex.withLock {
                    lifecycleGeneration += 1
                    pausedByPolicy = true
                    reporter.stopStatusPolling()
                    cancelStartupJobLocked()
                    withContext(ioDispatcher) {
                        repository.stop()
                    }.onFailure {
                        reporter.publishError(
                            message = it.message ?: "Failed stopping tunnel after policy block",
                            code = "stop_failed",
                        )
                    }
                    repository.setPolicyBlocked(reason)
                    reporter.publishStatus(reason)
                }
            }

            suspend fun stopServiceWork() {
                lifecycleMutex.withLock {
                    lifecycleGeneration += 1
                    reporter.stopStatusPolling()
                    cancelStartupJobLocked()
                    withContext(ioDispatcher) {
                        repository.stop()
                    }.onFailure {
                        reporter.publishError(
                            message = it.message ?: "Unable to stop tunnel",
                            code = "stop_failed",
                        )
                    }
                    pausedByPolicy = false
                    clearTemporaryMeteredAllowance()
                    notifications.show(notifications.buildStatusNotification(ServiceState.Stopped, "Tunnel stopped"))
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
