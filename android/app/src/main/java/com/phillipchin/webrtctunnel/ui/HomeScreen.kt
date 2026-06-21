package com.phillipchin.webrtctunnel.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.phillipchin.webrtctunnel.model.ForwardConfig
import com.phillipchin.webrtctunnel.model.ForwardStatus
import com.phillipchin.webrtctunnel.model.NetworkType
import com.phillipchin.webrtctunnel.model.ServiceState
import com.phillipchin.webrtctunnel.model.TunnelMode
import com.phillipchin.webrtctunnel.model.TunnelStatus
import com.phillipchin.webrtctunnel.model.isTunnelActiveOrStarting
import com.phillipchin.webrtctunnel.viewmodel.ForwardsViewModel
import com.phillipchin.webrtctunnel.viewmodel.HomeViewModel
import kotlinx.coroutines.delay
import java.util.Locale

private const val SECONDS_PER_HOUR = 3600
private const val SECONDS_PER_MINUTE = 60
private const val UPTIME_TICK_MS = 1_000L

// title + description for every ServiceState; map avoids a branch-heavy when that trips
// the cyclomatic-complexity threshold when combined with the appearance when below.
private val statusCopy =
    mapOf(
        ServiceState.Stopped to ("Stopped" to "Tunnel service is not running."),
        ServiceState.Starting to ("Starting" to "Starting tunnel and waiting for peer connectivity."),
        ServiceState.Connecting to ("Starting" to "Starting tunnel and waiting for peer connectivity."),
        ServiceState.Reconnecting to ("Starting" to "Starting tunnel and waiting for peer connectivity."),
        ServiceState.Connected to ("Connected" to "Tunnel is active and ready to use."),
        ServiceState.Listening to ("Running" to "Tunnel is up — waiting for an app to use it."),
        ServiceState.Serving to ("Running" to "Tunnel is up — waiting for an app to use it."),
        ServiceState.PausedMeteredBlocked to (
            "Paused" to "Paused on cellular/metered data. Tap \"Allow This Session\" to use it now."
        ),
        ServiceState.NoNetwork to (
            "No network" to "No network connection. Connect to Wi-Fi or mobile data, then retry."
        ),
        ServiceState.ConfigInvalid to ("Configuration needs attention" to "Open setup to fix configuration."),
        ServiceState.Stopping to ("Stopping" to "Stopping tunnel service."),
        ServiceState.Error to ("Error" to "Tunnel encountered an error."),
    )

internal data class HomeStatusUi(
    val title: String,
    val description: String,
    val titleColor: Color,
    val icon: ImageVector,
) {
    val iconTint: Color get() = titleColor
}

private fun mapStatusUi(status: TunnelStatus): HomeStatusUi {
    data class Appearance(val color: Color, val icon: ImageVector)

    val (color, icon) =
        when (status.serviceState) {
            ServiceState.Stopped, ServiceState.Stopping -> Appearance(Neutral, Icons.Filled.Info)
            ServiceState.Starting, ServiceState.Connecting, ServiceState.Reconnecting ->
                Appearance(Warning, Icons.Filled.Info)
            ServiceState.Connected, ServiceState.Listening, ServiceState.Serving ->
                Appearance(Success, Icons.Filled.CheckCircle)
            ServiceState.PausedMeteredBlocked -> Appearance(Warning, Icons.Filled.Warning)
            ServiceState.NoNetwork -> Appearance(Warning, Icons.Filled.WifiOff)
            ServiceState.ConfigInvalid, ServiceState.Error -> Appearance(Error, Icons.Filled.Warning)
        }
    val (title, description) =
        requireNotNull(statusCopy[status.serviceState]) { "No copy for ${status.serviceState}" }
    return HomeStatusUi(title, description, color, icon)
}

internal fun formatUptime(seconds: Long): String {
    val hours = seconds / SECONDS_PER_HOUR
    val minutes = (seconds % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE
    val secs = seconds % SECONDS_PER_MINUTE
    return String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, secs)
}

private fun ForwardStatus.toConfig(): ForwardConfig =
    ForwardConfig(
        id = id,
        name = name,
        localHost = localHost,
        localPort = localPort,
        remoteForwardId = remoteForwardId,
        enabled = enabled,
    )

@Composable
internal fun HomeStatusIcon(statusUi: HomeStatusUi) {
    Icon(
        imageVector = statusUi.icon,
        contentDescription = "Tunnel status: ${statusUi.title}",
        tint = statusUi.iconTint,
        modifier = Modifier.size(40.dp),
    )
}

@Composable
internal fun NetworkTypeIcon(networkType: NetworkType) {
    val (icon, description) =
        when (networkType) {
            NetworkType.UnmeteredWifi, NetworkType.MeteredWifi -> Icons.Filled.Wifi to "Wi-Fi network"
            NetworkType.Cellular -> Icons.Filled.SignalCellularAlt to "Cellular network"
            NetworkType.NoNetwork -> Icons.Filled.WifiOff to "No network"
            NetworkType.Unknown -> Icons.Filled.Info to "Unknown network"
        }
    Icon(icon, contentDescription = description, tint = MaterialTheme.colorScheme.onSurfaceVariant)
}

data class HomeNavActions(
    val onOpenSetup: () -> Unit,
    val onOpenLogs: () -> Unit,
    val onOpenSettings: () -> Unit,
    val onOpenForwardDetails: (String) -> Unit,
)

@Composable
fun HomeScreen(
    padding: PaddingValues,
    vm: HomeViewModel,
    forwardsVm: ForwardsViewModel,
    nav: HomeNavActions,
) {
    val status by vm.status.collectAsStateWithLifecycle()
    val configuredForwards by vm.configuredForwards.collectAsStateWithLifecycle()
    val statusUi = mapStatusUi(status)
    var showMeteredWarningDialog by remember { mutableStateOf(false) }
    var showAddForwardDialog by remember { mutableStateOf(false) }
    val isRunning = status.serviceState in setOf(ServiceState.Connected, ServiceState.Listening, ServiceState.Serving)
    LaunchedEffect(Unit) { vm.refreshForwards() }
    val displayedUptimeSeconds = rememberDisplayedUptime(status, isRunning)
    ScrollableScreenSurface(padding) {
        SectionHeader("WebRTC Tunnel", "Current runtime state and quick actions")
        Spacer(Modifier.height(12.dp))
        TunnelStatusCard(status = status, statusUi = statusUi, uptimeSeconds = displayedUptimeSeconds)
        Spacer(Modifier.height(12.dp))
        HomeNetworkCard(
            networkStatus = status.networkStatus,
            allowMeteredForCurrentSession = status.allowMeteredForCurrentSession,
        )
        Spacer(Modifier.height(12.dp))
        HomeForwardsCard(
            configuredForwards = configuredForwards,
            status = status,
            onAdd = { showAddForwardDialog = true },
            onOpenDetails = nav.onOpenForwardDetails,
        )
        HomeErrorCard(error = status.lastError, onOpenLogs = nav.onOpenLogs)
        Spacer(Modifier.height(12.dp))
        HomeBottomActions(
            status = status,
            vm = vm,
            nav = nav,
            configuredForwards = configuredForwards,
            onAllowMetered = { showMeteredWarningDialog = true },
        )
    }
    if (showMeteredWarningDialog) {
        MeteredWarningDialog(
            onConfirm = {
                vm.allowMeteredTemporarily()
                showMeteredWarningDialog = false
            },
            onDismiss = { showMeteredWarningDialog = false },
        )
    }
    HomeAddForwardDialog(
        show = showAddForwardDialog,
        configuredForwards = configuredForwards,
        forwardsVm = forwardsVm,
        onRefresh = { vm.refreshForwards() },
        onDismiss = { showAddForwardDialog = false },
    )
}

@Composable
private fun rememberDisplayedUptime(
    status: TunnelStatus,
    isRunning: Boolean,
): Long? {
    var displayed by remember { mutableStateOf(status.uptimeSeconds) }
    LaunchedEffect(isRunning, status.uptimeSeconds) {
        displayed = status.uptimeSeconds
        while (isRunning) {
            delay(UPTIME_TICK_MS)
            displayed = displayed?.let { it + 1L }
        }
    }
    return displayed
}

@Composable
private fun HomeAddForwardDialog(
    show: Boolean,
    configuredForwards: List<ForwardConfig>,
    forwardsVm: ForwardsViewModel,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (!show) return
    EditForwardDialog(
        editor = ForwardEditorState(ForwardEditorMode.Add, defaultNewForward(configuredForwards)),
        existingForwards = configuredForwards,
        validateDraft = forwardsVm::validateForwardDraft,
        onDismiss = onDismiss,
        onSave = {
            forwardsVm.saveForward(it)
            onRefresh()
            onDismiss()
        },
    )
}

@Composable
private fun HomeBottomActions(
    status: TunnelStatus,
    vm: HomeViewModel,
    nav: HomeNavActions,
    configuredForwards: List<ForwardConfig>,
    onAllowMetered: () -> Unit,
) {
    val context = LocalContext.current
    var showStopConfirmDialog by remember { mutableStateOf(false) }
    if (showStopConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showStopConfirmDialog = false },
            title = { Text("Stop tunnel?") },
            text = { Text("This disconnects the tunnel. Any active sessions will be dropped.") },
            dismissButton = { TextButton(onClick = { showStopConfirmDialog = false }) { Text("Keep running") } },
            confirmButton = {
                TextButton(onClick = {
                    vm.stopTunnel()
                    showStopConfirmDialog = false
                }) { Text("Stop", color = MaterialTheme.colorScheme.error) }
            },
        )
    }
    val browserForward =
        (configuredForwards + status.forwards.map { it.toConfig() }).firstOrNull { isBrowserOpenable(it) }
    HomeActionRow(
        status = status,
        actions =
            HomeRowActions(
                onStart = { vm.startTunnel(TunnelMode.Offer) },
                // A long-running or in-progress tunnel is destructive to drop on a stray tap, so
                // confirm first. A paused tunnel stops directly (the user is already leaving it).
                onStop = {
                    if (status.serviceState.isTunnelActiveOrStarting()) {
                        showStopConfirmDialog = true
                    } else {
                        vm.stopTunnel()
                    }
                },
                onOpenSetup = nav.onOpenSetup,
                onOpenLogs = nav.onOpenLogs,
                onOpenSettings = nav.onOpenSettings,
                onAllowMeteredTemporary = onAllowMetered,
                onOpenBrowser =
                    browserForward?.let {
                        {
                            val url = browserUrlForForward(it)
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }
                    },
            ),
    )
}

private data class HomeRowActions(
    val onStart: () -> Unit,
    val onStop: () -> Unit,
    val onOpenSetup: () -> Unit,
    val onOpenLogs: () -> Unit,
    val onOpenSettings: () -> Unit,
    val onAllowMeteredTemporary: () -> Unit,
    val onOpenBrowser: (() -> Unit)? = null,
)

@Composable
private fun HomeActionRow(
    status: TunnelStatus,
    actions: HomeRowActions,
) {
    val onStart = actions.onStart
    val onStop = actions.onStop
    val onOpenSetup = actions.onOpenSetup
    val onOpenLogs = actions.onOpenLogs
    val onOpenSettings = actions.onOpenSettings
    val onAllowMeteredTemporary = actions.onAllowMeteredTemporary
    val onOpenBrowser = actions.onOpenBrowser
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        when (status.serviceState) {
            ServiceState.Stopped -> {
                Button(onClick = onStart, modifier = Modifier.weight(1f)) { Text("Start Tunnel") }
                OutlinedButton(onClick = onOpenSetup, modifier = Modifier.weight(1f)) { Text("Setup") }
            }
            ServiceState.Starting, ServiceState.Connecting, ServiceState.Reconnecting -> {
                OutlinedButton(onClick = onStop, modifier = Modifier.weight(1f)) { Text("Stop") }
                OutlinedButton(onClick = onOpenLogs, modifier = Modifier.weight(1f)) { Text("View Logs") }
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            }
            ServiceState.Connected, ServiceState.Listening, ServiceState.Serving -> {
                OutlinedButton(onClick = onStop, modifier = Modifier.weight(1f)) { Text("Stop Tunnel") }
                OutlinedButton(onClick = onOpenLogs, modifier = Modifier.weight(1f)) { Text("View Logs") }
                onOpenBrowser?.let {
                    OutlinedButton(onClick = it, modifier = Modifier.weight(1f)) { Text("Open URL") }
                }
            }
            ServiceState.PausedMeteredBlocked -> {
                OutlinedButton(onClick = onOpenSettings, modifier = Modifier.weight(1f)) { Text("Settings") }
                OutlinedButton(onClick = onStop, modifier = Modifier.weight(1f)) { Text("Stop") }
                OutlinedButton(
                    onClick = onAllowMeteredTemporary,
                    modifier = Modifier.weight(1f),
                ) { Text("Allow This Session") }
            }
            ServiceState.NoNetwork -> {
                OutlinedButton(onClick = onStart, modifier = Modifier.weight(1f)) { Text("Retry") }
                OutlinedButton(onClick = onOpenSettings, modifier = Modifier.weight(1f)) { Text("Settings") }
            }
            ServiceState.Error -> {
                OutlinedButton(onClick = onStart, modifier = Modifier.weight(1f)) { Text("Retry") }
                OutlinedButton(onClick = onOpenLogs, modifier = Modifier.weight(1f)) { Text("View Logs") }
            }
            ServiceState.ConfigInvalid -> {
                OutlinedButton(onClick = onOpenSetup, modifier = Modifier.weight(1f)) { Text("Open Setup") }
                OutlinedButton(onClick = onOpenLogs, modifier = Modifier.weight(1f)) { Text("View Logs") }
            }
            ServiceState.Stopping -> {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                Text("Stopping…", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
