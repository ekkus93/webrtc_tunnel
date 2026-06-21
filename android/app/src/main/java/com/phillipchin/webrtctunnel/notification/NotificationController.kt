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
import com.phillipchin.webrtctunnel.R
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
        // POST_NOTIFICATIONS is a runtime permission only on Android 13+ (TIRAMISU);
        // pre-Tiramisu it does not exist as a runtime grant, so notifications must not
        // be gated on it there. On 13+ the inline checkSelfPermission also lets Android
        // lint verify the permission is held before notify().
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
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
                ServiceState.Stopped -> "WebRTC Tunnel stopped"
                ServiceState.Starting,
                ServiceState.Connecting,
                ServiceState.Reconnecting,
                -> "WebRTC Tunnel starting"
                ServiceState.Listening -> "WebRTC Tunnel listening"
                ServiceState.Serving -> "WebRTC Tunnel serving"
                ServiceState.Connected -> "WebRTC Tunnel connected"
                ServiceState.PausedMeteredBlocked, ServiceState.NoNetwork -> "WebRTC Tunnel paused"
                ServiceState.Stopping -> "WebRTC Tunnel stopping"
                ServiceState.Error, ServiceState.ConfigInvalid -> "WebRTC Tunnel error"
            }
        return NotificationCompat.Builder(context, CHANNEL_STATUS)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body)
            .setContentIntent(openIntent)
            .addAction(R.drawable.ic_notification_stop, "Stop", action)
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
