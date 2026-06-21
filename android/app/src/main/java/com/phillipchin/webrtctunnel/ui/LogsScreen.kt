package com.phillipchin.webrtctunnel.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.phillipchin.webrtctunnel.data.SensitiveDataRedactor
import com.phillipchin.webrtctunnel.model.AndroidAppPreferences
import com.phillipchin.webrtctunnel.model.LogEvent
import com.phillipchin.webrtctunnel.model.NetworkStatus
import com.phillipchin.webrtctunnel.model.NetworkType
import com.phillipchin.webrtctunnel.viewmodel.LogsViewModel
import com.phillipchin.webrtctunnel.viewmodel.NetworkPolicyViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val logTimestampFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())

private fun formatLogTimestamp(unixMs: Long): String = logTimestampFormatter.format(Instant.ofEpochMilli(unixMs))

private const val LOG_REFRESH_INTERVAL_MS = 2_000L

@Composable
fun LogsScreen(
    padding: PaddingValues,
    vm: LogsViewModel,
    networkVm: NetworkPolicyViewModel,
) {
    val context = LocalContext.current
    val filter by vm.filter.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val prefs by networkVm.preferences.collectAsStateWithLifecycle(initialValue = AndroidAppPreferences())
    val networkStatus by networkVm.networkStatus.collectAsStateWithLifecycle(
        initialValue = NetworkStatus(NetworkType.NoNetwork, false, false, false, false, "No network"),
    )
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var paused by remember { mutableStateOf(false) }
    val diagnosticsCreateDocumentLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.CreateDocument("text/plain"),
        ) { uri -> if (uri != null) vm.exportDiagnosticsToUri(uri, networkStatus) }
    LaunchedEffect(paused) {
        while (!paused) {
            vm.refresh()
            delay(LOG_REFRESH_INTERVAL_MS)
        }
    }
    val logs by vm.filteredLogs.collectAsStateWithLifecycle()
    val copyLogs = { clipboard.setText(AnnotatedString(redactedLogsText(logs))) }
    val exportDiagnostics = { diagnosticsCreateDocumentLauncher.launch("webrtc_diagnostics_redacted.txt") }
    val shareDiagnostics: () -> Unit = {
        scope.launch {
            val share =
                Intent.createChooser(vm.diagnosticsShareIntent(networkStatus), "Share diagnostics")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(share)
        }
    }

    val visibleLogs = logs.filterNot { it.level.equals("debug", true) && !prefs.debugLogsEnabled }
    val debugHidden = logs.any { it.level.equals("debug", true) && !prefs.debugLogsEnabled }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(16.dp))
        SectionHeader("Logs", "Redacted runtime events")
        Spacer(Modifier.height(8.dp))
        LogFilterChips(filter = filter, onSelect = vm::setFilter)
        Spacer(Modifier.height(8.dp))
        LogActionsRow(
            paused = paused,
            onTogglePause = { paused = !paused },
            onClear = vm::clearLogs,
            menu = LogMenuActions(onCopy = copyLogs, onExport = exportDiagnostics, onShare = shareDiagnostics),
        )
        message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        Spacer(Modifier.height(8.dp))
        LogList(visibleLogs = visibleLogs, debugHidden = debugHidden, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun LogFilterChips(
    filter: String,
    onSelect: (String) -> Unit,
) {
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        listOf("all", "info", "warn", "error", "debug").forEach { level ->
            FilterChip(
                selected = filter == level,
                onClick = { onSelect(level) },
                label = { Text(level.uppercase()) },
            )
        }
    }
}

private data class LogMenuActions(
    val onCopy: () -> Unit,
    val onExport: () -> Unit,
    val onShare: () -> Unit,
)

@Composable
private fun LogActionsRow(
    paused: Boolean,
    onTogglePause: () -> Unit,
    onClear: () -> Unit,
    menu: LogMenuActions,
) {
    var showActionsMenu by remember { mutableStateOf(false) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onTogglePause, modifier = Modifier.weight(1f)) {
            Text(if (paused) "Resume Logs" else "Pause Logs")
        }
        OutlinedButton(onClick = onClear, modifier = Modifier.weight(1f)) { Text("Clear Logs") }
        IconButton(onClick = { showActionsMenu = true }) {
            Icon(Icons.Default.MoreVert, contentDescription = "Open log actions")
        }
        DropdownMenu(expanded = showActionsMenu, onDismissRequest = { showActionsMenu = false }) {
            DropdownMenuItem(
                text = { Text("Copy Logs") },
                onClick = {
                    showActionsMenu = false
                    menu.onCopy()
                },
            )
            DropdownMenuItem(
                text = { Text("Export Diagnostics") },
                onClick = {
                    showActionsMenu = false
                    menu.onExport()
                },
            )
            DropdownMenuItem(
                text = { Text("Share Diagnostics") },
                onClick = {
                    showActionsMenu = false
                    menu.onShare()
                },
            )
        }
    }
}

@Composable
private fun LogList(
    visibleLogs: List<LogEvent>,
    debugHidden: Boolean,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(bottom = 16.dp),
    ) {
        if (debugHidden) {
            item { EmptyStateCard("Debug logs are hidden. Enable Debug logs in Advanced to see them.") }
        }
        if (visibleLogs.isEmpty() && !debugHidden) {
            item { EmptyStateCard("No logs available.") }
        }
        // LogEvent has no unique id and timestamps can collide, so combine the timestamp with
        // the list index for a crash-safe unique key (duplicate keys throw in LazyColumn).
        itemsIndexed(visibleLogs, key = { index, event -> "${event.unixMs}-$index" }) { _, event ->
            LogRow(event)
        }
    }
}

@Composable
private fun LogRow(event: LogEvent) {
    val levelColor =
        when (event.level.lowercase()) {
            "warn" -> Color(color = 0xFFF59E0B)
            "error" -> Color(color = 0xFFD32F2F)
            "debug" -> Color(color = 0xFF6B7280)
            else -> MaterialTheme.colorScheme.onSurface
        }
    StatusCard {
        Text(
            formatLogTimestamp(event.unixMs),
            style = MaterialTheme.typography.bodySmall,
            color = Color(color = 0xFF6B7280),
        )
        Text(event.level.uppercase(), color = levelColor, style = MaterialTheme.typography.labelLarge)
        Text(SensitiveDataRedactor.redactText(event.message))
    }
}

private fun redactedLogsText(logs: List<LogEvent>): String =
    logs
        .map(SensitiveDataRedactor::redactLogEvent)
        .joinToString("\n") { "${it.unixMs} ${it.level} ${it.message}" }
