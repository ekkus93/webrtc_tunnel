package com.phillipchin.webrtctunnel.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

@Composable
fun NotificationPermissionGate() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val context = LocalContext.current
    val hasPermission =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    if (hasPermission) return

    var openDialog by remember { mutableStateOf(true) }
    var denied by remember { mutableStateOf(false) }
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            denied = !granted
            openDialog = !granted
        }
    if (openDialog) {
        NotificationRequestDialog(
            onAllow = { launcher.launch(Manifest.permission.POST_NOTIFICATIONS) },
            onNotNow = {
                denied = true
                openDialog = false
            },
            onDismiss = { openDialog = false },
        )
    }
    if (denied) {
        NotificationsDisabledDialog(
            onOpenAppSettings = {
                val intent =
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", context.packageName, null),
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                denied = false
            },
            onClose = { denied = false },
        )
    }
}

@Composable
private fun NotificationRequestDialog(
    onAllow: () -> Unit,
    onNotNow: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Notification permission") },
        text = {
            Text(
                "Rust WebRTC Tunnel needs notifications so Android can keep the tunnel " +
                    "service visible while it is running in the background.",
            )
        },
        confirmButton = { TextButton(onClick = onAllow) { Text("Allow") } },
        dismissButton = { TextButton(onClick = onNotNow) { Text("Not now") } },
    )
}

@Composable
private fun NotificationsDisabledDialog(
    onOpenAppSettings: () -> Unit,
    onClose: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Notifications are disabled") },
        text = { Text("Background tunnel notifications are required for full foreground-service visibility.") },
        confirmButton = { TextButton(onClick = onOpenAppSettings) { Text("Open Settings") } },
        dismissButton = { TextButton(onClick = onClose) { Text("Close") } },
    )
}
