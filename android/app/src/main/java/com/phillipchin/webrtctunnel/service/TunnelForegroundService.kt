package com.phillipchin.webrtctunnel

import android.app.Notification
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.phillipchin.webrtctunnel.model.ServiceState
import com.phillipchin.webrtctunnel.model.TunnelMode
import com.phillipchin.webrtctunnel.notification.NotificationController
import com.phillipchin.webrtctunnel.data.ConfigRepository
import com.phillipchin.webrtctunnel.security.IdentityRepository
import com.phillipchin.webrtctunnel.data.TunnelRepository
import com.phillipchin.webrtctunnel.model.NetworkType
import com.phillipchin.webrtctunnel.network.NetworkPolicyManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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
    private var lastMode: TunnelMode = TunnelMode.Offer
    private var pausedByPolicy: Boolean = false

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
        networkMonitorJob = serviceScope.launch {
            networkPolicyManager.monitor(this@TunnelForegroundService).collect { status ->
                val prefs = configRepository.preferences.first()
                val policy = networkPolicyManager.evaluateWithPolicy(prefs.allowMetered)
                repository.updateNetworkStatus(policy)
                if (policy.networkType == NetworkType.UnmeteredWifi) {
                    if (pausedByPolicy && prefs.resumeOnUnmetered) {
                        pausedByPolicy = false
                        resume()
                    }
                } else if (!policy.tunnelAllowed) {
                    val current = repository.status.value.serviceState
                    if (current == ServiceState.Connected || current == ServiceState.Serving) {
                        pausedByPolicy = true
                        repository.stop().onFailure {
                            publishError(it.message ?: "Failed stopping tunnel after policy block")
                        }
                        repository.setPolicyBlocked(
                            policy.blockReason ?: "Tunnel paused: network policy blocks metered/cellular",
                        )
                        publishStatus(policy.blockReason ?: "Cellular/metered network blocked")
                    }
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        when (intent.action) {
            ACTION_START_OFFER -> serviceScope.launch { startOffer() }
            ACTION_START_ANSWER -> {
                publishError("Answer mode is not available in Android v1")
                stopSelf(startId)
            }
            ACTION_STOP -> {
                serviceScope.launch { stopServiceWork() }
            }
            ACTION_PAUSE -> pause()
            ACTION_RESUME -> resume()
            else -> stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    private fun startOffer() {
        if (startupJob?.isActive == true) {
            publishStatus("Tunnel startup already in progress")
            return
        }
        val current = repository.status.value.serviceState
        if (current == ServiceState.Connected || current == ServiceState.Serving) {
            publishStatus("Tunnel already running")
            return
        }
        startupJob = serviceScope.launch {
            doStartOffer()
        }
        startupJob?.invokeOnCompletion { startupJob = null }
    }

    private suspend fun doStartOffer() {
        lastMode = TunnelMode.Offer
        startForeground(NOTIFICATION_ID, loadingNotification("Starting tunnel"))
        val prefs = withContext(Dispatchers.IO) { configRepository.preferences.first() }
        val policy = networkPolicyManager.evaluateWithPolicy(prefs.allowMetered)
        repository.updateNetworkStatus(policy)
        if (!policy.tunnelAllowed) {
            repository.setPolicyBlocked(policy.blockReason ?: "Tunnel blocked by current network policy")
            publishStatus(policy.blockReason ?: "Tunnel blocked by network policy")
            return
        }
        val identity = withContext(Dispatchers.IO) {
            runCatching { identityRepository.readEncryptedIdentity() }
        }
            .getOrElse {
                publishError("Unable to decrypt private identity: ${it.message}")
                return
            }
        val validation = withContext(Dispatchers.IO) {
            repository.validateConfigWithIdentity(configRepository.configPath, identity)
        }
        if (!validation.valid) {
            repository.refreshStatus()
            publishError(validation.message ?: "Config validation failed")
            return
        }
        withContext(Dispatchers.IO) {
            repository.start(TunnelMode.Offer, configRepository.configPath, identity)
        }
            .onSuccess {
                pausedByPolicy = false
                publishStatus()
            }
            .onFailure { publishError(it.message ?: "Unable to start tunnel") }
    }

    private fun startAnswer() {
        lastMode = TunnelMode.Answer
        startForeground(NOTIFICATION_ID, loadingNotification("Starting tunnel"))
        repository.start(TunnelMode.Answer, configRepository.configPath)
            .onSuccess { publishStatus() }
            .onFailure { publishError(it.message ?: "Unable to start tunnel") }
    }

    private fun pause() {
        startupJob?.cancel()
        repository.stop().onFailure { publishError(it.message ?: "Unable to stop tunnel") }
        publishStatus("Tunnel paused")
    }

    private fun resume() {
        when (lastMode) {
            TunnelMode.Offer -> startOffer()
            TunnelMode.Answer -> startAnswer()
        }
    }

    private fun publishStatus(body: String? = null) {
        val state = repository.status.value.serviceState
        val text = body ?: when (state) {
            ServiceState.Connected -> "Connected"
            ServiceState.Serving -> "Serving"
            ServiceState.Listening -> "Listening"
            ServiceState.PausedMeteredBlocked -> "Cellular/metered network blocked"
            ServiceState.NoNetwork -> "No network"
            ServiceState.Error -> repository.status.value.lastError?.message ?: "Error"
            else -> "WebRTC Tunnel running"
        }
        notifications.show(notifications.buildStatusNotification(state, text))
    }

    private fun publishError(message: String) {
        Log.e(tag, message)
        notifications.show(notifications.buildStatusNotification(ServiceState.Error, message))
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
        startupJob?.cancel()
        repository.stop()
        networkMonitorJob?.cancel()
        serviceScope.coroutineContext.cancel()
        super.onDestroy()
    }

    private suspend fun stopServiceWork() {
        startupJob?.cancel()
        startupJob = null
        repository.stop().onFailure { publishError(it.message ?: "Unable to stop tunnel") }
        pausedByPolicy = false
        notifications.show(notifications.buildStatusNotification(ServiceState.Stopped, "Tunnel stopped"))
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    companion object {
        const val ACTION_START_OFFER = "com.phillipchin.webrtctunnel.action.START_OFFER"
        const val ACTION_START_ANSWER = "com.phillipchin.webrtctunnel.action.START_ANSWER"
        const val ACTION_STOP = "com.phillipchin.webrtctunnel.action.STOP"
        const val ACTION_PAUSE = "com.phillipchin.webrtctunnel.action.PAUSE"
        const val ACTION_RESUME = "com.phillipchin.webrtctunnel.action.RESUME"
        const val NOTIFICATION_ID = NotificationController.NOTIFICATION_ID
    }
}
