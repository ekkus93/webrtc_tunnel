package com.phillipchin.webrtctunnel.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.phillipchin.webrtctunnel.data.SensitiveDataRedactor
import com.phillipchin.webrtctunnel.model.AndroidAppPreferences
import com.phillipchin.webrtctunnel.model.ForwardConfig
import com.phillipchin.webrtctunnel.model.ForwardStatus
import com.phillipchin.webrtctunnel.model.NetworkStatus
import com.phillipchin.webrtctunnel.model.NetworkType
import com.phillipchin.webrtctunnel.model.ServiceState
import com.phillipchin.webrtctunnel.model.TunnelMode
import com.phillipchin.webrtctunnel.model.TunnelStatus
import com.phillipchin.webrtctunnel.viewmodel.ForwardsViewModel
import com.phillipchin.webrtctunnel.viewmodel.HomeViewModel
import com.phillipchin.webrtctunnel.viewmodel.ImportExportViewModel
import com.phillipchin.webrtctunnel.viewmodel.LogsViewModel
import com.phillipchin.webrtctunnel.viewmodel.NetworkPolicyViewModel
import com.phillipchin.webrtctunnel.viewmodel.SettingsViewModel
import com.phillipchin.webrtctunnel.ui.ForwardEditorMode
import java.util.Locale

private data class HomeStatusUi(val title: String, val description: String)

private fun mapStatusUi(status: TunnelStatus): HomeStatusUi = when (status.serviceState) {
    ServiceState.Stopped -> HomeStatusUi("Stopped", "Tunnel service is not running.")
    ServiceState.Starting, ServiceState.Connecting, ServiceState.Reconnecting -> {
        HomeStatusUi("Starting", "Starting tunnel and waiting for peer connectivity.")
    }
    ServiceState.Connected -> HomeStatusUi("Connected", "Tunnel is active and ready to use.")
    ServiceState.Listening, ServiceState.Serving -> HomeStatusUi("Listening", "Tunnel is active and waiting for local use.")
    ServiceState.PausedMeteredBlocked -> HomeStatusUi("Paused", "Cellular/metered network blocked.")
    ServiceState.NoNetwork -> HomeStatusUi("No network", "Connect to Wi-Fi to start the tunnel.")
    ServiceState.ConfigInvalid -> HomeStatusUi("Configuration needs attention", "Open setup to fix configuration.")
    ServiceState.Stopping -> HomeStatusUi("Stopping", "Stopping tunnel service.")
    ServiceState.Error -> HomeStatusUi("Error", "Tunnel encountered an error.")
}

private fun isBrowserOpenable(forward: ForwardConfig): Boolean {
    val name = "${forward.name} ${forward.remoteForwardId}".lowercase()
    val httpLikePorts = setOf(80, 8080, 8000, 3000, 5000, 5173, 7860, 11434)
    if (forward.localPort in httpLikePorts) return true
    return listOf("http", "web", "api", "llama", "ollama").any { token -> name.contains(token) }
}

private fun mapNetworkTypeLabel(networkType: NetworkType): String = when (networkType) {
    NetworkType.UnmeteredWifi -> "Wi-Fi"
    NetworkType.MeteredWifi -> "Metered Wi-Fi"
    NetworkType.Cellular -> "Cellular"
    NetworkType.NoNetwork -> "No network"
    NetworkType.Unknown -> "Unknown"
}

private fun mapForwardListenLabel(state: String): String = when (state.lowercase()) {
    "listening" -> "Listening"
    "stopped" -> "Stopped"
    "error" -> "Error"
    "disabled" -> "Disabled"
    "paused" -> "Paused"
    "configured" -> "Configured"
    else -> state
}

private fun formatUptime(seconds: Long): String {
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    val secs = seconds % 60
    return String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, secs)
}

private fun ForwardStatus.toConfig(): ForwardConfig = ForwardConfig(
    id = id,
    name = name,
    localHost = localHost,
    localPort = localPort,
    remoteForwardId = remoteForwardId,
    enabled = enabled,
)

@Composable
private fun HomeStatusIcon(title: String) {
    val (icon, tint) = when {
        title.equals("Connected", ignoreCase = true) || title.equals("Listening", ignoreCase = true) ->
            Icons.Filled.CheckCircle to stateColorToken(title)
        title.equals("Error", ignoreCase = true) || title.contains("attention", ignoreCase = true) ->
            Icons.Filled.Warning to stateColorToken(title)
        else -> Icons.Filled.Info to stateColorToken(title)
    }
    Icon(icon, contentDescription = "Tunnel status", tint = tint, modifier = Modifier.size(40.dp))
}

@Composable
private fun NetworkTypeIcon(networkType: NetworkType) {
    val (icon, description) = when (networkType) {
        NetworkType.UnmeteredWifi, NetworkType.MeteredWifi -> Icons.Filled.Wifi to "Wi-Fi network"
        NetworkType.Cellular -> Icons.Filled.SignalCellularAlt to "Cellular network"
        NetworkType.NoNetwork -> Icons.Filled.WifiOff to "No network"
        NetworkType.Unknown -> Icons.Filled.Info to "Unknown network"
    }
    Icon(icon, contentDescription = description, tint = Color(0xFF6B7280))
}

@Composable
fun HomeScreen(
    padding: PaddingValues,
    vm: HomeViewModel,
    onOpenSetup: () -> Unit,
    onOpenLogs: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val status by vm.status.collectAsStateWithLifecycle()
    val configuredForwards by vm.configuredForwards.collectAsStateWithLifecycle()
    val statusUi = mapStatusUi(status)
    val context = LocalContext.current
    val browserForward = (configuredForwards + status.forwards.map { it.toConfig() }).firstOrNull { isBrowserOpenable(it) }
    val displayedForwards = configuredForwards.map { config ->
        val runtime = status.forwards.firstOrNull { it.id == config.id }
        config to runtime
    }
    var showMeteredWarningDialog by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { vm.refreshForwards() }
    ScrollableScreenSurface(padding) {
        SectionHeader("WebRTC Tunnel", "Current runtime state and quick actions")
        Spacer(Modifier.height(12.dp))
        StatusCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                HomeStatusIcon(statusUi.title)
                Column {
                    Text(
                        statusUi.title,
                        color = stateColorToken(statusUi.title),
                        style = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
                    )
                    Text(statusUi.description)
                }
            }
            Text("Mode: ${if (status.mode == TunnelMode.Offer) "Offer (client)" else "Answer (server)"}")
            Text("Remote peer: ${status.remotePeerId ?: "-"}")
            Text("Active sessions: ${status.activeSessionCount}")
            status.uptimeSeconds?.let { Text("Uptime: ${formatUptime(it)}") }
        }
        Spacer(Modifier.height(12.dp))
        NetworkStatusCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                NetworkTypeIcon(status.networkStatus.networkType)
                Text("Network", style = MaterialTheme.typography.titleMedium)
            }
            Text("Type: ${mapNetworkTypeLabel(status.networkStatus.networkType)}")
            Text(if (status.networkStatus.isMetered) "Metered" else "Unmetered")
            Text(if (status.networkStatus.tunnelAllowed) "Tunnel allowed" else "Tunnel blocked")
            status.networkStatus.blockReason?.let { Text("Reason: $it") }
            if (status.allowMeteredForCurrentSession) {
                Text("Metered override: active for this app run")
            }
        }
        Spacer(Modifier.height(12.dp))
        StatusCard {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Forwards (${configuredForwards.size})", style = MaterialTheme.typography.titleMedium)
                IconButton(onClick = onOpenSetup) {
                    Icon(Icons.Filled.Add, contentDescription = "Add forward")
                }
            }
            if (configuredForwards.isEmpty()) {
                EmptyStateCard("No forwards configured.")
            } else {
                displayedForwards.forEach { (forward, runtime) ->
                    ForwardSummaryRow(
                        title = forward.name,
                        subtitle = "${forward.localHost}:${forward.localPort} -> ${forward.remoteForwardId}",
                        status = mapForwardListenLabel(runtime?.listenState?.name ?: if (forward.enabled) "configured" else "disabled"),
                    )
                }
            }
        }
        status.lastError?.let { err ->
            Spacer(Modifier.height(12.dp))
            ErrorResolutionCard(
                summary = err.message,
                fix = "Open logs for details, then fix setup or broker/network settings and retry.",
                details = err.details,
                action = { OutlinedButton(onClick = onOpenLogs) { Text("View Logs") } },
            )
        }
        Spacer(Modifier.height(12.dp))
        HomeActionRow(
            status = status,
            onStart = { vm.startTunnel(TunnelMode.Offer) },
            onStop = vm::stopTunnel,
            onOpenSetup = onOpenSetup,
            onOpenLogs = onOpenLogs,
            onOpenSettings = onOpenSettings,
            onAllowMeteredTemporary = { showMeteredWarningDialog = true },
            onOpenBrowser = browserForward?.let {
                {
                    val url = "http://127.0.0.1:${it.localPort}"
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            },
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
}

@Composable
private fun HomeActionRow(
    status: TunnelStatus,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onOpenSetup: () -> Unit,
    onOpenLogs: () -> Unit,
    onOpenSettings: () -> Unit,
    onAllowMeteredTemporary: () -> Unit,
    onOpenBrowser: (() -> Unit)? = null,
) {
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
                OutlinedButton(onClick = onAllowMeteredTemporary, modifier = Modifier.weight(1f)) { Text("Allow This Session") }
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
            ServiceState.Stopping -> OutlinedButton(onClick = onStop, modifier = Modifier.weight(1f)) { Text("Stop") }
        }
    }
}

@Composable
fun ForwardsScreen(padding: PaddingValues, vm: ForwardsViewModel, onOpenDetails: (String) -> Unit) {
    val forwards by vm.forwards.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            SectionHeader("Forwards", "Manage local forwards")
            Spacer(Modifier.height(12.dp))
            message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        }
        if (forwards.isEmpty()) {
            item { EmptyStateCard("No forwards configured. Add one in Setup Wizard.") }
        } else {
            items(forwards) { forward ->
                StatusCard {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpenDetails(forward.id) },
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(forward.name, style = MaterialTheme.typography.titleMedium)
                            Text("${forward.localHost}:${forward.localPort} -> ${forward.remoteForwardId}", style = MaterialTheme.typography.bodySmall)
                            val runtime = status.forwards.firstOrNull { it.id == forward.id }
                            Text(runtime?.listenState?.name ?: if (forward.enabled) "Configured" else "Disabled", color = stateColorToken(runtime?.listenState?.name ?: "disabled"))
                        }
                        Text("›", style = MaterialTheme.typography.titleLarge)
                    }
                }
            }
        }
    }
}

@Composable
fun ForwardDetailsScreen(
    padding: PaddingValues,
    vm: ForwardsViewModel,
    forwardId: String,
    onDeleteAndReturn: () -> Unit,
) {
    val forwards by vm.forwards.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showEditDialog by remember { mutableStateOf(false) }
    val forward = forwards.firstOrNull { it.id == forwardId }
    val runtime = status.forwards.firstOrNull { it.id == forwardId }

    ScrollableScreenSurface(padding) {
        if (forward == null) {
            EmptyStateCard("Forward not found.")
            return@ScrollableScreenSurface
        }
        SectionHeader(forward.name, "Forward details")
        Spacer(Modifier.height(12.dp))
        StatusCard {
            Text("Status: ${runtime?.listenState ?: if (forward.enabled) "Configured" else "Disabled"}")
            Text("Local address: ${forward.localHost}:${forward.localPort}")
            val url = vm.localhostUrl(forward)
            Text("Local URL: $url")
            Text("Remote forward_id: ${forward.remoteForwardId}")
            runtime?.lastError?.let { Text("Last error: $it", color = MaterialTheme.colorScheme.error) }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { clipboard.setText(AnnotatedString(vm.localhostUrl(forward))) }, modifier = Modifier.weight(1f)) {
            Icon(Icons.Default.ContentCopy, contentDescription = "Copy ${forward.name} local URL")
                Spacer(Modifier.height(0.dp))
                Text("Copy URL")
            }
            if (isBrowserOpenable(forward)) {
                OutlinedButton(
                    onClick = {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(vm.localhostUrl(forward))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(intent)
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.OpenInBrowser, contentDescription = "Open ${forward.name} local URL in browser")
                    Text("Open Browser")
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { vm.testLocalPort(forward) }, modifier = Modifier.weight(1f)) { Text("Test Local Port") }
            OutlinedButton(onClick = { vm.saveForward(forward.copy(enabled = !forward.enabled)) }, modifier = Modifier.weight(1f)) {
                Text(if (forward.enabled) "Disable" else "Enable")
            }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = { showEditDialog = true }, modifier = Modifier.fillMaxWidth()) { Text("Edit") }
        Spacer(Modifier.height(8.dp))
        DestructiveActionButton("Delete Forward") { showDeleteDialog = true }
        message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
    }

    if (showDeleteDialog && forward != null) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete forward?") },
            text = { Text("This removes ${forward.name} from configuration.") },
            dismissButton = { TextButton(onClick = { showDeleteDialog = false }) { Text("Cancel") } },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.deleteForward(forward.id)
                        showDeleteDialog = false
                        onDeleteAndReturn()
                    },
                ) { Text("Delete") }
            },
        )
    }
    if (showEditDialog && forward != null) {
        EditForwardDialog(
            mode = ForwardEditorMode.Edit,
            initial = forward,
            existingForwards = forwards,
            validateDraft = vm::validateForwardDraft,
            onDismiss = { showEditDialog = false },
            onSave = {
                vm.saveForward(it)
                showEditDialog = false
            },
        )
    }
}

@Composable
fun LogsScreen(padding: PaddingValues, vm: LogsViewModel, networkVm: NetworkPolicyViewModel) {
    val context = LocalContext.current
    val filter by vm.filter.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val prefs by networkVm.preferences.collectAsStateWithLifecycle(initialValue = AndroidAppPreferences())
    val networkStatus by networkVm.networkStatus.collectAsStateWithLifecycle(
        initialValue = NetworkStatus(NetworkType.NoNetwork, false, false, false, false, "No network"),
    )
    val clipboard = LocalClipboardManager.current
    var paused by remember { mutableStateOf(false) }
    var showActionsMenu by remember { mutableStateOf(false) }
    val diagnosticsCreateDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri -> if (uri != null) vm.exportDiagnosticsToUri(uri, networkStatus) }
    LaunchedEffect(paused) { if (!paused) vm.refresh() }
    val logs = vm.filteredLogs()
    val copyLogs = {
        val text = logs
            .map(SensitiveDataRedactor::redactLogEvent)
            .joinToString("\n") { "${it.unixMs} ${it.level} ${it.message}" }
        clipboard.setText(AnnotatedString(text))
    }
    val exportDiagnostics = { diagnosticsCreateDocumentLauncher.launch("webrtc_diagnostics_redacted.txt") }
    val shareDiagnostics = {
        val share = Intent.createChooser(
            vm.diagnosticsShareIntent(networkStatus),
            "Share diagnostics",
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(share)
    }

    ScrollableScreenSurface(padding) {
        SectionHeader("Logs", "Redacted runtime events")
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("all", "info", "warn", "error", "debug").forEach { level ->
                FilterChip(selected = filter == level, onClick = { vm.setFilter(level) }, label = { Text(level.uppercase()) })
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { paused = !paused }, modifier = Modifier.weight(1f)) {
                Text(if (paused) "Resume Logs" else "Pause Logs")
            }
            OutlinedButton(onClick = vm::clearLogs, modifier = Modifier.weight(1f)) { Text("Clear Logs") }
            IconButton(onClick = { showActionsMenu = true }) {
                Icon(Icons.Default.MoreVert, contentDescription = "Open log actions")
            }
            DropdownMenu(expanded = showActionsMenu, onDismissRequest = { showActionsMenu = false }) {
                DropdownMenuItem(
                    text = { Text("Copy Logs") },
                    onClick = {
                        showActionsMenu = false
                        copyLogs()
                    },
                )
                DropdownMenuItem(
                    text = { Text("Export Diagnostics") },
                    onClick = {
                        showActionsMenu = false
                        exportDiagnostics()
                    },
                )
                DropdownMenuItem(
                    text = { Text("Share Diagnostics") },
                    onClick = {
                        showActionsMenu = false
                        shareDiagnostics()
                    },
                )
            }
        }
        message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        Spacer(Modifier.height(8.dp))
        val visibleLogs = logs.filterNot { it.level.equals("debug", true) && !prefs.debugLogsEnabled }
        val debugHidden = logs.any { it.level.equals("debug", true) && !prefs.debugLogsEnabled }
        if (visibleLogs.isEmpty() && !debugHidden) {
            EmptyStateCard("No logs available.")
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (debugHidden) {
                    EmptyStateCard("Debug logs are hidden. Enable Debug logs in Advanced to see them.")
                }
                visibleLogs.forEach { event ->
                    val levelColor = when (event.level.lowercase()) {
                        "warn" -> Color(0xFFF59E0B)
                        "error" -> Color(0xFFD32F2F)
                        "debug" -> Color(0xFF6B7280)
                        else -> MaterialTheme.colorScheme.onSurface
                    }
                    StatusCard {
                        Text("${event.unixMs}", style = MaterialTheme.typography.bodySmall, color = Color(0xFF6B7280))
                        Text(event.level.uppercase(), color = levelColor, style = MaterialTheme.typography.labelLarge)
                        Text(SensitiveDataRedactor.redactText(event.message))
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsScreen(
    padding: PaddingValues,
    vm: SettingsViewModel,
    onOpenSetup: () -> Unit,
    onOpenLogs: () -> Unit,
    onOpenNetworkPolicy: () -> Unit,
    onOpenImportExport: () -> Unit,
) {
    val prefs by vm.preferences.collectAsStateWithLifecycle(initialValue = AndroidAppPreferences())
    val uiState by vm.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val publicIdentity = uiState.publicIdentity
    val hasPublicIdentity = !publicIdentity.isNullOrBlank()
    var showMeteredWarningDialog by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { vm.refreshPublicIdentity() }
    ScrollableScreenSurface(padding) {
        SectionHeader("Settings", "Tunnel and app behavior")
        Spacer(Modifier.height(12.dp))
        SettingsSection("Tunnel") {
            PreferenceSwitch("Start tunnel when app opens", prefs.startTunnelWhenAppOpens) {
                vm.savePreferences(prefs.copy(startTunnelWhenAppOpens = it))
            }
            PreferenceSwitch("Resume tunnel when Wi-Fi returns", prefs.resumeOnUnmetered) {
                vm.savePreferences(prefs.copy(resumeOnUnmetered = it))
            }
            OutlinedButton(onClick = onOpenSetup, modifier = Modifier.fillMaxWidth()) { Text("Run setup wizard again") }
        }
        Spacer(Modifier.height(12.dp))
        SettingsSection("Network Policy") {
            PreferenceSwitch("Allow cellular / metered data", prefs.allowMetered) {
                if (it) {
                    showMeteredWarningDialog = true
                } else {
                    vm.savePreferences(prefs.copy(allowMetered = false))
                }
            }
            OutlinedButton(onClick = onOpenNetworkPolicy, modifier = Modifier.fillMaxWidth()) { Text("Open network policy details") }
        }
        Spacer(Modifier.height(12.dp))
        SettingsSection("Configuration") {
            OutlinedButton(onClick = onOpenImportExport, modifier = Modifier.fillMaxWidth()) { Text("Import / Export") }
            OutlinedButton(onClick = { vm.validateConfig() }, modifier = Modifier.fillMaxWidth()) { Text("Validate configuration") }
            OutlinedButton(onClick = { vm.resetConfiguration() }, modifier = Modifier.fillMaxWidth()) { Text("Reset configuration") }
        }
        Spacer(Modifier.height(12.dp))
        SettingsSection("Identity") {
            Text(publicIdentity ?: "No local public identity found.", style = MaterialTheme.typography.bodySmall)
            uiState.publicIdentityLoadError?.let { error ->
                Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        clipboard.setText(AnnotatedString(publicIdentity.orEmpty()))
                    },
                    modifier = Modifier.weight(1f),
                    enabled = hasPublicIdentity,
                ) { Text("Copy identity") }
                OutlinedButton(
                    onClick = {
                        val share = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_SUBJECT, "WebRTC Tunnel public identity")
                            putExtra(Intent.EXTRA_TEXT, publicIdentity)
                        }
                        context.startActivity(Intent.createChooser(share, "Share public identity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    },
                    modifier = Modifier.weight(1f),
                    enabled = hasPublicIdentity,
                ) { Text("Share identity") }
            }
            OutlinedButton(onClick = onOpenImportExport, modifier = Modifier.fillMaxWidth()) { Text("Import / Export identity") }
        }
        Spacer(Modifier.height(12.dp))
        SettingsSection("Diagnostics") {
            OutlinedButton(
                onClick = { clipboard.setText(AnnotatedString(vm.statusJson())) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Copy status JSON") }
            OutlinedButton(
                onClick = { clipboard.setText(AnnotatedString(vm.redactedConfigOrEmpty())) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Copy redacted config") }
            OutlinedButton(onClick = onOpenLogs, modifier = Modifier.fillMaxWidth()) { Text("Open logs / export diagnostics") }
            OutlinedButton(
                onClick = {
                    val share = Intent.createChooser(vm.diagnosticsShareIntent(), "Share diagnostics")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(share)
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Share diagnostics") }
        }
        Spacer(Modifier.height(12.dp))
        SettingsSection("Advanced") {
            OutlinedButton(
                onClick = { vm.savePreferences(prefs.copy(advancedSettingsEnabled = !prefs.advancedSettingsEnabled)) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (prefs.advancedSettingsEnabled) "Hide advanced settings" else "Show advanced settings") }
            if (prefs.advancedSettingsEnabled) {
                PreferenceSwitch("Enable debug logs", prefs.debugLogsEnabled) { vm.savePreferences(prefs.copy(debugLogsEnabled = it)) }
                OutlinedButton(onClick = onOpenSetup, modifier = Modifier.fillMaxWidth()) { Text("Edit custom topic prefix") }
                OutlinedButton(onClick = onOpenSetup, modifier = Modifier.fillMaxWidth()) { Text("Configure non-localhost bind (advanced)") }
                Text("Answer mode: Not available in Android v1", style = MaterialTheme.typography.bodySmall, color = Color(0xFF6B7280))
            }
        }
        Spacer(Modifier.height(12.dp))
        SettingsSection("About") {
            Text("Rust WebRTC Tunnel Android", style = MaterialTheme.typography.bodyMedium)
            Text("Version 0.1", style = MaterialTheme.typography.bodySmall, color = Color(0xFF6B7280))
        }
    }
    if (showMeteredWarningDialog) {
        MeteredWarningDialog(
            onConfirm = {
                vm.savePreferences(prefs.copy(allowMetered = true))
                showMeteredWarningDialog = false
            },
            onDismiss = { showMeteredWarningDialog = false },
        )
    }
}

@Composable
fun NetworkPolicyScreen(padding: PaddingValues, vm: NetworkPolicyViewModel) {
    val status by vm.networkStatus.collectAsStateWithLifecycle(
        initialValue = NetworkStatus(NetworkType.NoNetwork, false, false, false, false, "No network"),
    )
    val prefs by vm.preferences.collectAsStateWithLifecycle(initialValue = AndroidAppPreferences())
    var showMeteredWarningDialog by remember { mutableStateOf(false) }

    ScrollableScreenSurface(padding) {
        SectionHeader("Network Policy", "Current network and tunnel policy")
        Spacer(Modifier.height(8.dp))
        NetworkStatusCard {
            Text("Current network: ${status.networkType}")
            Text(if (status.isMetered) "Metered" else "Unmetered")
            Text(if (status.tunnelAllowed) "Tunnel allowed" else "Tunnel blocked")
            Text("Reason: ${status.blockReason ?: "None"}")
            Text("Unknown network is blocked.")
        }
        Spacer(Modifier.height(12.dp))
        PreferenceSwitch(
            title = "Allow metered/cellular",
            checked = prefs.allowMetered,
            onToggle = { checked ->
                if (checked) showMeteredWarningDialog = true else vm.savePreferences(prefs.copy(allowMetered = false))
            },
        )
        PreferenceSwitch("Resume on unmetered", prefs.resumeOnUnmetered) { vm.savePreferences(prefs.copy(resumeOnUnmetered = it)) }
    }

    if (showMeteredWarningDialog) {
        MeteredWarningDialog(
            onConfirm = {
                vm.savePreferences(prefs.copy(allowMetered = true))
                showMeteredWarningDialog = false
            },
            onDismiss = { showMeteredWarningDialog = false },
        )
    }
}

@Composable
fun ImportExportScreen(padding: PaddingValues, vm: ImportExportViewModel) {
    val context = LocalContext.current
    val state by vm.state.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    var showPrivateExportWarning by remember { mutableStateOf(false) }
    var showRawConfigExportWarning by remember { mutableStateOf(false) }
    var showAdvanced by remember { mutableStateOf(false) }
    var rawConfigExportViaPicker by remember { mutableStateOf(false) }
    val openTextDocumentLauncher = rememberLauncherForActivityResult(contract = ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importConfigFromUri(uri)
    }
    val openPrivateIdentityLauncher = rememberLauncherForActivityResult(contract = ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importPrivateIdentityFromUri(uri)
    }
    val openPublicIdentityLauncher = rememberLauncherForActivityResult(contract = ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importPublicIdentityFromUri(uri)
    }
    val exportConfigLauncher = rememberLauncherForActivityResult(contract = ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) vm.exportConfigToUri(uri, confirmSensitive = true)
    }
    val exportPublicIdentityLauncher = rememberLauncherForActivityResult(contract = ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) vm.exportPublicIdentityToUri(uri)
    }
    val exportPrivateIdentityLauncher = rememberLauncherForActivityResult(contract = ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) vm.exportPrivateIdentityToUri(uri, confirmRisk = true)
    }

    ScrollableScreenSurface(padding) {
        SectionHeader("Import / Export", "Use Android document picker and share actions")
        Spacer(Modifier.height(8.dp))
        SettingsSection("Primary actions") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { openTextDocumentLauncher.launch(arrayOf("text/*", "application/toml")) }, modifier = Modifier.weight(1f)) { Text("Import config") }
                OutlinedButton(onClick = { rawConfigExportViaPicker = true; showRawConfigExportWarning = true }, modifier = Modifier.weight(1f)) { Text("Export config") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { openPrivateIdentityLauncher.launch(arrayOf("text/*")) }, modifier = Modifier.weight(1f)) { Text("Import identity") }
                OutlinedButton(onClick = { showPrivateExportWarning = true }, modifier = Modifier.weight(1f)) { Text("Export private identity") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { openPublicIdentityLauncher.launch(arrayOf("text/*")) }, modifier = Modifier.weight(1f)) { Text("Import public identity") }
                OutlinedButton(onClick = { exportPublicIdentityLauncher.launch("identity-public.txt") }, modifier = Modifier.weight(1f)) { Text("Export public identity") }
                IconButton(onClick = {
                    runCatching {
                        val payload = vm.publicIdentityForShare()
                        val intent = Intent.createChooser(
                            Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_SUBJECT, "WebRTC Tunnel public identity")
                                putExtra(Intent.EXTRA_TEXT, payload)
                            },
                            "Share public identity",
                        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(intent)
                    }
                }) {
                    Icon(Icons.Default.Share, contentDescription = "Share public identity")
                }
                IconButton(onClick = {
                    runCatching { clipboard.setText(AnnotatedString(vm.publicIdentityForShare())) }
                }) {
                    Icon(Icons.Default.ContentCopy, contentDescription = "Copy public identity")
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = { showAdvanced = !showAdvanced }, modifier = Modifier.fillMaxWidth()) {
            Text(if (showAdvanced) "Hide Advanced paths" else "Show Advanced paths")
        }
        if (showAdvanced) {
            Spacer(Modifier.height(8.dp))
            SettingsSection("Advanced (developer/debug)") {
                OutlinedTextField(
                    value = state.configImportPath,
                    onValueChange = { value -> vm.updateState { it.copy(configImportPath = value) } },
                    label = { Text("Config import path") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(onClick = vm::importConfig, modifier = Modifier.fillMaxWidth()) { Text("Import config path") }
                OutlinedTextField(value = state.privateIdentityImportPath, onValueChange = { value -> vm.updateState { it.copy(privateIdentityImportPath = value) } }, label = { Text("Private identity import path") }, modifier = Modifier.fillMaxWidth())
                Button(onClick = vm::importPrivateIdentity, modifier = Modifier.fillMaxWidth()) { Text("Import identity path") }
                OutlinedTextField(value = state.publicIdentityLine, onValueChange = { value -> vm.updateState { it.copy(publicIdentityLine = value) } }, label = { Text("Remote public identity line") }, modifier = Modifier.fillMaxWidth())
                Button(onClick = vm::importPublicIdentity, modifier = Modifier.fillMaxWidth()) { Text("Import public identity line") }
            }
        }
        Spacer(Modifier.height(8.dp))
        state.resultMessage?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
    }

    if (showRawConfigExportWarning) {
        AlertDialog(
            onDismissRequest = { showRawConfigExportWarning = false },
            title = { Text("Raw Config Export Warning") },
            text = { Text("Config export may include sensitive operational details. Continue only if required.") },
            dismissButton = { TextButton(onClick = { showRawConfigExportWarning = false }) { Text("Cancel") } },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (rawConfigExportViaPicker) exportConfigLauncher.launch("p2ptunnel-config.toml") else vm.exportConfig(confirmSensitive = true)
                        showRawConfigExportWarning = false
                    },
                ) { Text("Export") }
            },
        )
    }
    if (showPrivateExportWarning) {
        AlertDialog(
            onDismissRequest = { showPrivateExportWarning = false },
            title = { Text("Private Identity Export Warning") },
            text = { Text("Anyone with this file can impersonate this device. Export only if you understand this risk.") },
            dismissButton = { TextButton(onClick = { showPrivateExportWarning = false }) { Text("Cancel") } },
            confirmButton = {
                TextButton(onClick = {
                    exportPrivateIdentityLauncher.launch("identity-private.toml")
                    showPrivateExportWarning = false
                }) { Text("Export") }
            },
        )
    }
}

@Composable
private fun PreferenceSwitch(title: String, checked: Boolean, onToggle: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onToggle)
    }
}

@Composable
fun ScreenSurface(padding: PaddingValues, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(16.dp),
        verticalArrangement = Arrangement.Top,
        content = content,
    )
}
