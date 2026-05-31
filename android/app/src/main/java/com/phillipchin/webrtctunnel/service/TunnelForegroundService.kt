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

class TunnelForegroundService : Service() {
    private val tag = "TunnelForegroundService"
    private lateinit var notifications: NotificationController
    private lateinit var repository: TunnelRepository
    private lateinit var configRepository: ConfigRepository
    private var lastMode: TunnelMode = TunnelMode.Offer

    override fun onCreate() {
        super.onCreate()
        notifications = NotificationController(this)
        notifications.ensureChannels()
        val deps = (application as HasAppDependencies).deps
        configRepository = deps.configRepository
        repository = deps.tunnelRepository
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_OFFER -> startOffer()
            ACTION_START_ANSWER -> startAnswer()
            ACTION_STOP -> {
                repository.stop()
                stopSelf()
            }
            ACTION_PAUSE -> pause()
            ACTION_RESUME -> resume()
        }
        return START_STICKY
    }

    private fun startOffer() {
        lastMode = TunnelMode.Offer
        startForeground(NOTIFICATION_ID, loadingNotification("Starting tunnel"))
        repository.start(TunnelMode.Offer, configRepository.configPath)
            .onSuccess { publishStatus() }
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
