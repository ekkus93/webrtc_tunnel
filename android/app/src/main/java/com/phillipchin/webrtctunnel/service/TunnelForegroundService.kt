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
import kotlinx.coroutines.runBlocking

class TunnelForegroundService : Service() {
    private val tag = "TunnelForegroundService"
    private lateinit var notifications: NotificationController
    private lateinit var repository: TunnelRepository
    private lateinit var configRepository: ConfigRepository
    private lateinit var networkPolicyManager: NetworkPolicyManager
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var networkMonitorJob: Job? = null
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
        networkPolicyManager = deps.networkPolicyManager
        networkMonitorJob = serviceScope.launch {
            networkPolicyManager.monitor(this@TunnelForegroundService).collect { status ->
                if (status.networkType == NetworkType.UnmeteredWifi) {
                    val prefs = configRepository.preferences.first()
                    if (pausedByPolicy && prefs.resumeOnUnmetered) {
                        pausedByPolicy = false
                        resume()
                    }
                } else if (!networkPolicyManager.allowTunnelOnCurrentNetwork(
                        allowMetered = configRepository.preferences.first().allowMetered
                    )
                ) {
                    val current = repository.status.value.serviceState
                    if (current == ServiceState.Connected || current == ServiceState.Serving) {
                        pausedByPolicy = true
                        repository.stop()
                        repository.setPolicyBlocked("Tunnel paused: network policy blocks metered/cellular")
                        publishStatus("Cellular/metered network blocked")
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
            ACTION_START_OFFER -> startOffer()
            ACTION_START_ANSWER -> {
                publishError("Answer mode is not available in Android v1")
                stopSelf(startId)
            }
            ACTION_STOP -> {
                repository.stop()
                stopSelf()
            }
            ACTION_PAUSE -> pause()
            ACTION_RESUME -> resume()
            else -> stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    private fun startOffer() {
        lastMode = TunnelMode.Offer
        startForeground(NOTIFICATION_ID, loadingNotification("Starting tunnel"))
        val allowMetered = runBlocking { configRepository.preferences.first().allowMetered }
        if (!networkPolicyManager.allowTunnelOnCurrentNetwork(allowMetered)) {
            repository.setPolicyBlocked("Tunnel blocked by current network policy")
            publishStatus("Tunnel blocked by network policy")
            return
        }
        val identity = runCatching { (application as HasAppDependencies).deps.identityRepository.readEncryptedIdentity() }
            .getOrElse {
                publishError("Unable to decrypt private identity: ${it.message}")
                return
            }
        val validation = repository.validateConfigWithIdentity(configRepository.configPath, identity)
        if (!validation.valid) {
            repository.refreshStatus()
            publishError(validation.message ?: "Config validation failed")
            return
        }
        repository.start(TunnelMode.Offer, configRepository.configPath, identity)
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
        repository.stop()
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
        repository.stop()
        networkMonitorJob?.cancel()
        serviceScope.coroutineContext.cancel()
        super.onDestroy()
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
