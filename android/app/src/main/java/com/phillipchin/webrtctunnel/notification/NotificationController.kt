package com.phillipchin.webrtctunnel.notification

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.phillipchin.webrtctunnel.MainActivity
import com.phillipchin.webrtctunnel.model.ServiceState

class NotificationController(
    private val context: Context,
    private val sdkIntProvider: () -> Int = { Build.VERSION.SDK_INT },
    private val notificationsAllowedProvider: () -> Boolean = {
        sdkIntProvider() < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    },
    notifyAction: ((Int, android.app.Notification) -> Unit)? = null,
) {
    companion object {
        private const val TAG = "NotificationController"
        const val CHANNEL_STATUS = "tunnel_status"
        const val CHANNEL_ERRORS = "tunnel_errors"
        const val NOTIFICATION_ID = 1001
    }

    private fun notifyWithManager(
        id: Int,
        notification: android.app.Notification,
    ) {
        // Explicit runtime check so Android lint can verify POST_NOTIFICATIONS is held
        // (pre-Tiramisu this permission is install-granted, so the check passes there).
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        NotificationManagerCompat.from(context).notify(id, notification)
    }

    private val notifyAction: (Int, android.app.Notification) -> Unit =
        notifyAction ?: { id, notification -> notifyWithManager(id, notification) }

    fun ensureChannels() {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val status = NotificationChannel(CHANNEL_STATUS, "Tunnel Status", NotificationManager.IMPORTANCE_LOW)
        val errors = NotificationChannel(CHANNEL_ERRORS, "Tunnel Errors", NotificationManager.IMPORTANCE_HIGH)
        manager.createNotificationChannels(listOf(status, errors))
    }

    fun buildStatusNotification(
        state: ServiceState,
        body: String,
    ): android.app.Notification {
        val openIntent =
            PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val action =
            PendingIntent.getService(
                context,
                1,
                Intent(context, com.phillipchin.webrtctunnel.TunnelForegroundService::class.java).apply {
                    action = com.phillipchin.webrtctunnel.TunnelForegroundService.ACTION_STOP
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val title =
            when (state) {
                ServiceState.PausedMeteredBlocked -> "WebRTC Tunnel paused"
                ServiceState.Error, ServiceState.ConfigInvalid -> "WebRTC Tunnel error"
                else -> "WebRTC Tunnel running"
            }
        return NotificationCompat.Builder(context, CHANNEL_STATUS)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body)
            .setContentIntent(openIntent)
            .addAction(android.R.drawable.ic_media_pause, "Stop", action)
            .setOngoing(true)
            .build()
    }

    fun show(notification: android.app.Notification) {
        if (!notificationsAllowedProvider()) {
            return
        }

        runCatching {
            notifyAction(NOTIFICATION_ID, notification)
        }.onFailure { error ->
            Log.w(TAG, "Unable to show notification", error)
        }
    }
}
