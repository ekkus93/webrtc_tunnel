package com.phillipchin.webrtctunnel

import android.app.Notification
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.phillipchin.webrtctunnel.data.ConfigRepository
import com.phillipchin.webrtctunnel.data.SensitiveDataRedactor
import com.phillipchin.webrtctunnel.data.TunnelRepository
import com.phillipchin.webrtctunnel.model.AndroidAppPreferences
import com.phillipchin.webrtctunnel.model.NetworkType
import com.phillipchin.webrtctunnel.model.ServiceState
import com.phillipchin.webrtctunnel.model.TunnelMode
import com.phillipchin.webrtctunnel.network.NetworkPolicyManager
import com.phillipchin.webrtctunnel.notification.NotificationController
import com.phillipchin.webrtctunnel.security.IdentityRepository
import kotlinx.coroutines.CancellationException
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

class TunnelForegroundService : Service() {
    private val tag = "TunnelForegroundService"
    private lateinit var notifications: NotificationController
    private lateinit var repository: TunnelRepository
    private lateinit var configRepository: ConfigRepository
    private lateinit var identityRepository: IdentityRepository
    private lateinit var networkPolicyManager: NetworkPolicyManager
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var networkMonitorJob: Job? = null
    private var startupJob: Job? = null
    private var statusPollJob: Job? = null
    private var lastMode: TunnelMode = TunnelMode.Offer
    private var pausedByPolicy: Boolean = false
    private var allowMeteredForCurrentRun: Boolean = false
    private var lifecycleGeneration: Long = 0
    private val lifecycleMutex = Mutex()

    override fun onCreate() {
        super.onCreate()
        notifications = NotificationController(this)
        notifications.ensureChannels()
        startForeground(NOTIFICATION_ID, loadingNotification("Preparing tunnel service"))
        val deps = (application as HasAppDependencies).deps
        configRepository = deps.configRepository
        repository = deps.tunnelRepository
        identityRepository = deps.identityRepository
        networkPolicyManager = deps.networkPolicyManager
        repository.updateSessionMeteredAllowance(false)
        networkMonitorJob =
            serviceScope.launch {
                networkPolicyManager.monitor(this@TunnelForegroundService).collect { _ ->
                    val prefs = withContext(Dispatchers.IO) { configRepository.preferences.first() }
                    val policy = evaluatePolicy(prefs)
                    repository.updateNetworkStatus(policy)
                    if (policy.networkType == NetworkType.UnmeteredWifi) {
                        if (pausedByPolicy && prefs.resumeOnUnmetered) {
                            pausedByPolicy = false
                            serviceScope.launch { resume() }
                        }
                    } else if (!policy.tunnelAllowed) {
                        val current = repository.status.value.serviceState
                        if (current == ServiceState.Connected || current == ServiceState.Serving) {
                            serviceScope.launch {
                                pauseForPolicy(policy.blockReason ?: "Tunnel paused: network policy blocks metered/cellular")
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
            ACTION_START_OFFER -> serviceScope.launch { startOffer() }
            ACTION_START_ANSWER -> {
                publishError(
                    message = "Answer mode is not available on Android",
                    code = "answer_mode_disabled",
                )
                stopSelf(startId)
            }
            ACTION_STOP -> {
                serviceScope.launch { stopServiceWork() }
            }
            ACTION_PAUSE -> serviceScope.launch { pause() }
            ACTION_RESUME -> serviceScope.launch { resume() }
            ACTION_ALLOW_METERED_SESSION -> serviceScope.launch { allowMeteredForSessionAndStart() }
            else -> stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    private suspend fun startOffer() {
        var generation = 0L
        lifecycleMutex.withLock {
            if (startupJob?.isActive == true) {
                publishStatus("Tunnel startup already in progress")
                return
            }
            val current = repository.status.value.serviceState
            if (current == ServiceState.Connected || current == ServiceState.Serving) {
                publishStatus("Tunnel already running")
                return
            }
            lifecycleGeneration += 1
            generation = lifecycleGeneration
            startupJob =
                serviceScope.launch {
                    doStartOffer(generation)
                }
        }
        if (generation == 0L) {
            return
        }
    }

    private suspend fun doStartOffer(startGeneration: Long) {
        lastMode = TunnelMode.Offer
        startForeground(NOTIFICATION_ID, loadingNotification("Starting tunnel"))
        val prefs = withContext(Dispatchers.IO) { configRepository.preferences.first() }
        val policy = evaluatePolicy(prefs)
        repository.updateNetworkStatus(policy)
        if (!policy.tunnelAllowed) {
            repository.setPolicyBlocked(policy.blockReason ?: "Tunnel blocked by current network policy")
            publishStatus(policy.blockReason ?: "Tunnel blocked by network policy")
            return
        }
        val identity =
            withContext(Dispatchers.IO) {
                runCatching { identityRepository.readPrivateIdentityPlaintext() }
            }
                .getOrElse {
                    publishError(
                        message = "Unable to decrypt private identity: ${it.message}",
                        code = "identity_decrypt_failed",
                    )
                    return
                }
        val validation =
            withContext(Dispatchers.IO) {
                repository.validateConfigWithIdentity(configRepository.configPath, identity)
            }
        if (!validation.valid) {
            publishError(
                message = validation.message ?: "Config validation failed",
                code = "config_validation_failed",
                state = ServiceState.ConfigInvalid,
            )
            return
        }
        val stillCurrentBeforeStart = lifecycleMutex.withLock { lifecycleGeneration == startGeneration }
        if (!stillCurrentBeforeStart) {
            return
        }
        val result =
            try {
                withContext(Dispatchers.IO) {
                    repository.start(TunnelMode.Offer, configRepository.configPath, identity)
                }
            } catch (_: CancellationException) {
                withContext(Dispatchers.IO) { repository.stop() }
                return
            }
        val stillCurrent = lifecycleMutex.withLock { lifecycleGeneration == startGeneration }
        if (!stillCurrent) {
            withContext(Dispatchers.IO) { repository.stop() }
            return
        }
        result.onSuccess {
            pausedByPolicy = false
            publishStatus()
            startStatusPolling()
        }.onFailure {
            publishError(
                message = it.message ?: "Unable to start tunnel",
                code = "native_start_failed",
            )
        }
    }

    private suspend fun allowMeteredForSessionAndStart() {
        lifecycleMutex.withLock {
            allowMeteredForCurrentRun = true
            repository.updateSessionMeteredAllowance(true)
            pausedByPolicy = false
        }
        startOffer()
    }

    private fun startAnswer() {
        publishError(
            message = "Answer mode is not available on Android",
            code = "answer_mode_disabled",
        )
    }

    private suspend fun pause() {
        lifecycleMutex.withLock {
            lifecycleGeneration += 1
            stopStatusPolling()
            cancelStartupJobLocked()
            withContext(Dispatchers.IO) {
                repository.stop()
            }.onFailure {
                publishError(
                    message = it.message ?: "Unable to stop tunnel",
                    code = "stop_failed",
                )
            }
            publishStatus("Tunnel paused")
        }
    }

    private suspend fun resume() {
        when (lastMode) {
            TunnelMode.Offer -> startOffer()
            TunnelMode.Answer -> startAnswer()
        }
    }

    private suspend fun pauseForPolicy(reason: String) {
        lifecycleMutex.withLock {
            lifecycleGeneration += 1
            pausedByPolicy = true
            stopStatusPolling()
            cancelStartupJobLocked()
            withContext(Dispatchers.IO) {
                repository.stop()
            }.onFailure {
                publishError(
                    message = it.message ?: "Failed stopping tunnel after policy block",
                    code = "stop_failed",
                )
            }
            repository.setPolicyBlocked(reason)
            publishStatus(reason)
        }
    }

    private fun publishStatus(body: String? = null) {
        val state = repository.status.value.serviceState
        val text =
            body ?: when (state) {
                ServiceState.Connected -> "Connected"
                ServiceState.Serving -> "Serving"
                ServiceState.Listening -> "Listening"
                ServiceState.PausedMeteredBlocked -> "Cellular/metered network blocked"
                ServiceState.NoNetwork -> "No network"
                ServiceState.Error, ServiceState.ConfigInvalid -> repository.status.value.lastError?.message ?: "Error"
                else -> "WebRTC Tunnel running"
            }
        notifications.show(notifications.buildStatusNotification(state, SensitiveDataRedactor.redactText(text)))
    }

    private fun publishError(
        message: String,
        code: String = "service_error",
        state: ServiceState = ServiceState.Error,
    ) {
        val redacted = SensitiveDataRedactor.redactText(message)
        repository.setLocalError(code = code, message = redacted, state = state)
        Log.e(tag, redacted)
        notifications.show(notifications.buildStatusNotification(state, redacted))
    }

    private fun loadingNotification(body: String): Notification =
        NotificationCompat.Builder(this, NotificationController.CHANNEL_STATUS)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("WebRTC Tunnel starting")
            .setContentText(body)
            .setOngoing(true)
            .build()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        networkMonitorJob?.cancel()
        stopStatusPolling()
        val pendingStop =
            serviceScope.launch {
                lifecycleMutex.withLock {
                    lifecycleGeneration += 1
                    cancelStartupJobLocked()
                    withContext(Dispatchers.IO) {
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

    private suspend fun stopServiceWork() {
        lifecycleMutex.withLock {
            lifecycleGeneration += 1
            stopStatusPolling()
            cancelStartupJobLocked()
            withContext(Dispatchers.IO) {
                repository.stop()
            }.onFailure {
                publishError(
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

    /**
     * Poll native runtime status while the tunnel is active so the UI and
     * notification reflect changes (e.g. a post-start error) without the user
     * navigating or manually refreshing. Stops when the tunnel leaves an active
     * state or is paused by policy. [TunnelRepository.refreshStatus] independently
     * refuses to resurrect policy-paused states, so a poll racing a policy pause
     * cannot flip the UI back to Connected.
     */
    private fun startStatusPolling() {
        if (statusPollJob?.isActive == true) return
        statusPollJob =
            serviceScope.launch {
                var lastState = repository.status.value.serviceState
                while (true) {
                    delay(STATUS_POLL_INTERVAL_MS)
                    if (pausedByPolicy) break
                    withContext(Dispatchers.IO) { runCatching { repository.refreshStatus() } }
                    val state = repository.status.value.serviceState
                    if (state != lastState) {
                        lastState = state
                        publishStatus()
                    }
                    if (state !in ACTIVE_STATES) break
                }
            }
    }

    private fun stopStatusPolling() {
        statusPollJob?.cancel()
        statusPollJob = null
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
