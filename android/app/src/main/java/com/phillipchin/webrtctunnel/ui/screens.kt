package com.phillipchin.webrtctunnel.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.phillipchin.webrtctunnel.model.AndroidAppPreferences
import com.phillipchin.webrtctunnel.model.ForwardConfig
import com.phillipchin.webrtctunnel.model.NetworkStatus
import com.phillipchin.webrtctunnel.model.NetworkType
import com.phillipchin.webrtctunnel.model.TunnelMode
import com.phillipchin.webrtctunnel.model.TunnelStatus
import com.phillipchin.webrtctunnel.viewmodel.ForwardsViewModel
import com.phillipchin.webrtctunnel.viewmodel.HomeViewModel
import com.phillipchin.webrtctunnel.viewmodel.ImportExportViewModel
import com.phillipchin.webrtctunnel.viewmodel.LogsViewModel
import com.phillipchin.webrtctunnel.viewmodel.NetworkPolicyViewModel
import com.phillipchin.webrtctunnel.viewmodel.SettingsViewModel
import com.phillipchin.webrtctunnel.viewmodel.SetupViewModel
import android.content.Intent
import android.net.Uri

private fun forwardStatusText(status: TunnelStatus, forwardId: String): String? {
    val runtime = status.forwards.firstOrNull { it.id == forwardId } ?: return null
    return "Runtime: ${runtime.listenState}${runtime.lastError?.let { " (${it})" } ?: ""}"
}

@Composable
fun HomeScreen(padding: PaddingValues, vm: HomeViewModel) {
    val status by vm.status.collectAsStateWithLifecycle()
    ScreenSurface(padding) {
        StatusCard(status)
        Spacer(Modifier.height(12.dp))
        NetworkCard(status)
        Spacer(Modifier.height(12.dp))
        ForwardsCard(status)
        Spacer(Modifier.height(12.dp))
        ActionRow(status, onStart = { vm.startTunnel(TunnelMode.Offer) }, onStop = vm::stopTunnel)
    }
}

@Composable
fun ForwardsScreen(padding: PaddingValues, vm: ForwardsViewModel) {
    val context = LocalContext.current
    val forwards by vm.forwards.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    var editId by remember { mutableStateOf<String?>(null) }
    var name by remember { mutableStateOf("") }
    var localHost by remember { mutableStateOf("127.0.0.1") }
    var localPort by remember { mutableStateOf("8080") }
    var remoteId by remember { mutableStateOf("llama") }
    var enabled by remember { mutableStateOf(true) }

    fun loadForEdit(item: ForwardConfig) {
        editId = item.id
        name = item.name
        localHost = item.localHost
        localPort = item.localPort.toString()
        remoteId = item.remoteForwardId
        enabled = item.enabled
    }

    ScreenSurface(padding) {
        Text("Forwards", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = localHost, onValueChange = { localHost = it }, label = { Text("Local host") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = localPort, onValueChange = { localPort = it.filter { c -> c.isDigit() } }, label = { Text("Local port") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = remoteId, onValueChange = { remoteId = it }, label = { Text("Remote forward id") }, modifier = Modifier.fillMaxWidth())
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            Text("Enabled")
            Switch(checked = enabled, onCheckedChange = { enabled = it })
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    val id = editId ?: name.lowercase().replace(' ', '-').ifBlank { "forward-${System.currentTimeMillis()}" }
                    val port = localPort.toIntOrNull() ?: 0
                    vm.saveForward(
                        ForwardConfig(
                            id = id,
                            name = name.ifBlank { id },
                            localHost = localHost.ifBlank { "127.0.0.1" },
                            localPort = port,
                            remoteForwardId = remoteId.ifBlank { id },
                            enabled = enabled,
                        ),
                    )
                    editId = null
                },
            ) { Text(if (editId == null) "Add forward" else "Update forward") }
            OutlinedButton(onClick = { editId = null; name = ""; localHost = "127.0.0.1"; localPort = "8080"; remoteId = "llama"; enabled = true }) { Text("Clear") }
        }
        Spacer(Modifier.height(12.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(forwards) { forward ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(forward.name, style = MaterialTheme.typography.titleMedium)
                        Text("${forward.localHost}:${forward.localPort} -> ${forward.remoteForwardId}")
                        Text("Enabled: ${forward.enabled}")
                        forwardStatusText(status, forward.id)?.let { Text(it) }
                        TextButton(onClick = { clipboard.setText(AnnotatedString(vm.localhostUrl(forward))) }) { Text("Copy URL") }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = {
                                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(vm.localhostUrl(forward)))
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    context.startActivity(intent)
                                },
                            ) { Text("Open") }
                            OutlinedButton(
                                onClick = {
                                    vm.saveForward(forward.copy(enabled = !forward.enabled))
                                },
                            ) { Text(if (forward.enabled) "Disable" else "Enable") }
                            OutlinedButton(onClick = { vm.testLocalPort(forward) }) { Text("Test Local Port") }
                            OutlinedButton(onClick = { loadForEdit(forward) }) { Text("Edit") }
                            OutlinedButton(onClick = { vm.deleteForward(forward.id) }) { Text("Delete") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun LogsScreen(
    padding: PaddingValues,
    vm: LogsViewModel,
    networkVm: NetworkPolicyViewModel,
) {
    val context = LocalContext.current
    val filter by vm.filter.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val networkStatus by networkVm.networkStatus.collectAsStateWithLifecycle(
        initialValue = NetworkStatus(
            networkType = NetworkType.NoNetwork,
            isMetered = false,
            allowedByDefault = false,
            allowedByUserPolicy = false,
            tunnelAllowed = false,
            blockReason = "No network",
        ),
    )
    val clipboard = LocalClipboardManager.current
    val diagnosticsCreateDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        if (uri != null) {
            vm.exportDiagnosticsToUri(uri, networkStatus)
        }
    }
    LaunchedEffect(Unit) { vm.refresh() }
    val logs = vm.filteredLogs()
    ScreenSurface(padding) {
        Text("Logs", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("all", "error", "warn", "info", "debug").forEach { level ->
                FilterChip(
                    selected = filter == level,
                    onClick = { vm.setFilter(level) },
                    label = { Text(level.uppercase()) },
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = vm::clearLogs) { Text("Clear") }
            OutlinedButton(
                onClick = {
                    val text = logs.joinToString("\n") { "${it.unixMs} ${it.level} ${it.message}" }
                    clipboard.setText(AnnotatedString(text))
                },
            ) { Text("Copy filtered") }
            OutlinedButton(
                onClick = {
                    diagnosticsCreateDocumentLauncher.launch("webrtc_diagnostics_redacted.txt")
                },
            ) { Text("Export diagnostics") }
            OutlinedButton(
                onClick = {
                    val intent = Intent.createChooser(
                        vm.diagnosticsShareIntent(networkStatus),
                        "Share diagnostics (redacted)",
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                },
            ) { Text("Share diagnostics") }
        }
        message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        Spacer(Modifier.height(8.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(logs) { event ->
                Card(Modifier.fillMaxWidth()) {
                    Text("${event.unixMs} ${event.level.uppercase()} ${event.message}", modifier = Modifier.padding(16.dp))
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
    onOpenNetworkPolicy: () -> Unit,
    onOpenImportExport: () -> Unit,
) {
    ScreenSurface(padding) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(12.dp))
        Button(onClick = onOpenSetup, modifier = Modifier.fillMaxWidth()) { Text("Run Setup Wizard") }
        OutlinedButton(onClick = onOpenNetworkPolicy, modifier = Modifier.fillMaxWidth()) { Text("Network policy") }
        OutlinedButton(onClick = onOpenImportExport, modifier = Modifier.fillMaxWidth()) { Text("Import / export") }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = { vm.validateConfig() }, modifier = Modifier.fillMaxWidth()) { Text("Validate config") }
    }
}

@Composable
fun NetworkPolicyScreen(padding: PaddingValues, vm: NetworkPolicyViewModel) {
    val status by vm.networkStatus.collectAsStateWithLifecycle(
        initialValue = NetworkStatus(
            networkType = NetworkType.NoNetwork,
            isMetered = false,
            allowedByDefault = false,
            allowedByUserPolicy = false,
            tunnelAllowed = false,
            blockReason = "No network",
        ),
    )
    val prefs by vm.preferences.collectAsStateWithLifecycle(
        initialValue = AndroidAppPreferences(
            allowMetered = false,
            resumeOnUnmetered = true,
            showMeteredWarning = true,
            startTunnelWhenAppOpens = false,
            debugLogsEnabled = false,
        ),
    )
    var showMeteredWarningDialog by remember { mutableStateOf(false) }

    ScreenSurface(padding) {
        Text("Network Policy", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text("Current network: ${status.networkType} (${if (status.isMetered) "metered" else "unmetered"})")
        Text("Allowed by default: ${status.allowedByDefault}")
        Text("Allowed by user policy: ${status.allowedByUserPolicy}")
        Text("Tunnel allowed now: ${status.tunnelAllowed}")
        Text("Blocked reason: ${status.blockReason ?: "None"}")
        Text("Unknown network stays blocked even with allow-metered enabled.")
        Spacer(Modifier.height(12.dp))
        PreferenceSwitch(
            title = "Allow metered/cellular",
            checked = prefs.allowMetered,
            onToggle = { checked ->
                if (checked && prefs.showMeteredWarning) {
                    showMeteredWarningDialog = true
                } else {
                    vm.savePreferences(prefs.copy(allowMetered = checked))
                }
            },
        )
        PreferenceSwitch(
            title = "Resume on unmetered",
            checked = prefs.resumeOnUnmetered,
            onToggle = { vm.savePreferences(prefs.copy(resumeOnUnmetered = it)) },
        )
        PreferenceSwitch(
            title = "Show warning before enabling metered",
            checked = prefs.showMeteredWarning,
            onToggle = { vm.savePreferences(prefs.copy(showMeteredWarning = it)) },
        )
    }

    if (showMeteredWarningDialog) {
        AlertDialog(
            onDismissRequest = { showMeteredWarningDialog = false },
            title = { Text("Cellular / Metered Data Warning") },
            text = {
                Text(
                    "WebRTC Tunnel can use a large amount of data. Browser traffic, API calls, SSH sessions, downloads, streaming, llama-server usage, or other forwarded traffic may consume your mobile data plan quickly.\n\nYour carrier may charge overage fees, throttle your connection, or suspend service depending on your plan.\n\nThe app developer is not responsible for carrier charges, throttling, overage fees, or data-plan exhaustion caused by your use of this feature.\n\nOnly enable this if you understand the risk and accept responsibility for any data usage or charges.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.savePreferences(prefs.copy(allowMetered = true))
                        showMeteredWarningDialog = false
                    },
                ) { Text("I understand — allow cellular/metered tunnels") }
            },
            dismissButton = {
                TextButton(onClick = { showMeteredWarningDialog = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
fun ImportExportScreen(padding: PaddingValues, vm: ImportExportViewModel) {
    val context = LocalContext.current
    val state by vm.state.collectAsStateWithLifecycle()
    var showPrivateExportWarning by remember { mutableStateOf(false) }
    var showRawConfigExportWarning by remember { mutableStateOf(false) }
    var rawConfigExportViaPicker by remember { mutableStateOf(false) }
    val openTextDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            vm.importConfigFromUri(uri)
        }
    }
    val openPrivateIdentityLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            vm.importPrivateIdentityFromUri(uri)
        }
    }
    val openPublicIdentityLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            vm.importPublicIdentityFromUri(uri)
        }
    }
    val exportConfigLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        if (uri != null) {
            vm.exportConfigToUri(uri, confirmSensitive = true)
        }
    }
    val exportPublicIdentityLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        if (uri != null) {
            vm.exportPublicIdentityToUri(uri)
        }
    }
    val exportPrivateIdentityLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        if (uri != null) {
            vm.exportPrivateIdentityToUri(uri, confirmRisk = true)
        }
    }
    ScreenSurface(padding) {
        Text("Import / Export", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text("Android-safe import/export (SAF):")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { openTextDocumentLauncher.launch(arrayOf("text/*", "application/toml")) }) {
                Text("Import config (picker)")
            }
            OutlinedButton(
                onClick = {
                    rawConfigExportViaPicker = true
                    showRawConfigExportWarning = true
                },
            ) {
                Text("Export config (picker)")
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { openPrivateIdentityLauncher.launch(arrayOf("text/*")) }) {
                Text("Import private identity (picker)")
            }
            OutlinedButton(onClick = { exportPrivateIdentityLauncher.launch("identity-private.toml") }) {
                Text("Export private identity (picker)")
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { openPublicIdentityLauncher.launch(arrayOf("text/*")) }) {
                Text("Import public identity (picker)")
            }
            OutlinedButton(onClick = { exportPublicIdentityLauncher.launch("identity-public.txt") }) {
                Text("Export public identity (picker)")
            }
            OutlinedButton(
                onClick = {
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
                },
            ) { Text("Share public identity") }
        }
        Spacer(Modifier.height(12.dp))
        Text("Debug path fallback (developer only):")
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = state.configImportPath,
            onValueChange = { value -> vm.updateState { it.copy(configImportPath = value) } },
            label = { Text("Config import path") },
            modifier = Modifier.fillMaxWidth(),
        )
        Button(onClick = vm::importConfig, modifier = Modifier.fillMaxWidth()) { Text("Import config") }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = state.privateIdentityImportPath,
            onValueChange = { value -> vm.updateState { it.copy(privateIdentityImportPath = value) } },
            label = { Text("Private identity import path") },
            modifier = Modifier.fillMaxWidth(),
        )
        Button(onClick = vm::importPrivateIdentity, modifier = Modifier.fillMaxWidth()) { Text("Import private identity") }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = state.publicIdentityLine,
            onValueChange = { value -> vm.updateState { it.copy(publicIdentityLine = value) } },
            label = { Text("Remote public identity line") },
            modifier = Modifier.fillMaxWidth(),
        )
        Button(onClick = vm::importPublicIdentity, modifier = Modifier.fillMaxWidth()) { Text("Import remote public identity") }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = state.configExportPath,
            onValueChange = { value -> vm.updateState { it.copy(configExportPath = value) } },
            label = { Text("Config export path") },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedButton(
            onClick = {
                rawConfigExportViaPicker = false
                showRawConfigExportWarning = true
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Export config") }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = state.publicIdentityExportPath,
            onValueChange = { value -> vm.updateState { it.copy(publicIdentityExportPath = value) } },
            label = { Text("Public identity export path") },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedButton(onClick = vm::exportPublicIdentity, modifier = Modifier.fillMaxWidth()) { Text("Export public identity") }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = state.privateIdentityExportPath,
            onValueChange = { value -> vm.updateState { it.copy(privateIdentityExportPath = value) } },
            label = { Text("Private identity export path") },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedButton(onClick = { showPrivateExportWarning = true }, modifier = Modifier.fillMaxWidth()) { Text("Export private identity") }
        Spacer(Modifier.height(8.dp))
        state.resultMessage?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
    }
    if (showRawConfigExportWarning) {
        AlertDialog(
            onDismissRequest = { showRawConfigExportWarning = false },
            title = { Text("Raw Config Export Warning") },
            text = {
                Text(
                    "This config may include broker addresses, usernames, password file paths, peer IDs, local paths, and other operational details.\n\nIt must never include private identity material, but it may still be sensitive.",
                )
            },
            dismissButton = {
                TextButton(onClick = { showRawConfigExportWarning = false }) { Text("Cancel") }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (rawConfigExportViaPicker) {
                            exportConfigLauncher.launch("p2ptunnel-config.toml")
                        } else {
                            vm.exportConfig(confirmSensitive = true)
                        }
                        showRawConfigExportWarning = false
                    },
                ) { Text("Export Raw Config") }
            },
        )
    }
    if (showPrivateExportWarning) {
        AlertDialog(
            onDismissRequest = { showPrivateExportWarning = false },
            title = { Text("Private Identity Export Warning") },
            text = {
                Text(
                    "Anyone with this file can impersonate this phone in your tunnel network.\n\nOnly export it if you understand the risk.",
                )
            },
            dismissButton = {
                TextButton(onClick = { showPrivateExportWarning = false }) { Text("Cancel") }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.exportPrivateIdentity(confirmRisk = true)
                        showPrivateExportWarning = false
                    },
                ) {
                    Text("Export Private Identity")
                }
            },
        )
    }
}

@Composable
private fun PreferenceSwitch(title: String, checked: Boolean, onToggle: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(title)
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

@Composable
private fun StatusCard(status: TunnelStatus) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(status.serviceState.name, style = MaterialTheme.typography.headlineSmall)
            Text("Mode: ${status.mode}")
            Text("Remote peer: ${status.remotePeerId ?: "-"}")
            Text("Active sessions: ${status.activeSessionCount}")
            status.lastError?.let { error ->
                Text("Error: ${error.message}", color = MaterialTheme.colorScheme.error)
                error.details?.takeIf { it.isNotBlank() }?.let { details ->
                    Text("Details: $details", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun NetworkCard(status: TunnelStatus) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(status.networkStatus.networkType.name)
            Text(if (status.networkStatus.tunnelAllowed) "Tunnel allowed" else status.networkStatus.blockReason ?: "Blocked")
        }
    }
}

@Composable
private fun ForwardsCard(status: TunnelStatus) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Forwards (${status.forwards.size})")
            status.forwards.forEach { forward ->
                Text("${forward.name} ${forward.localHost}:${forward.localPort} -> ${forward.remoteForwardId}")
            }
        }
    }
}

@Composable
private fun ActionRow(status: TunnelStatus, onStart: () -> Unit, onStop: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) { Text("Start Tunnel") }
        OutlinedButton(onClick = onStop, modifier = Modifier.fillMaxWidth()) { Text("Stop Tunnel") }
    }
}
