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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.phillipchin.webrtctunnel.R
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
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var paused by remember { mutableStateOf(false) }

    // Collect network status for export
    val networkStatus by networkVm.networkStatus.collectAsStateWithLifecycle(
        initialValue = NetworkStatus(NetworkType.NoNetwork, false, false, false, false, "No network"),
    )

    val diagnosticsCreateDocumentLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.CreateDocument("text/plain"),
        ) { uri -> uri?.let { vm.exportDiagnosticsToUri(it, networkStatus) } }

    val state = collectLogsScreenState(vm, networkVm)

    val copyLogs = {
        clipboard.setText(AnnotatedString(redactedLogsText(state.visibleLogs)))
        vm.onLogsCopied()
    }
    val exportDiagnostics = {
        diagnosticsCreateDocumentLauncher.launch("webrtc_diagnostics_redacted.txt")
    }
    val shareDiagnostics: () -> Unit = {
        scope.launch {
            val share =
                Intent.createChooser(
                    vm.diagnosticsShareIntent(networkStatus),
                    "Share diagnostics",
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(share)
        }
    }

    LaunchedEffect(paused) {
        while (!paused) {
            vm.refresh()
            delay(LOG_REFRESH_INTERVAL_MS)
        }
    }

    LogsScreenContent(
        padding = padding,
        state = state,
        paused = paused,
        actions =
            LogsScreenActions(
                onTogglePause = { paused = !paused },
                onClearLogs = vm::clearLogs,
                onCopyLogs = copyLogs,
                onExport = exportDiagnostics,
                onShare = shareDiagnostics,
                onFilterSelect = vm::setFilter,
            ),
    )
}

/**
 * Logs screen UI content.
 */
@Composable
private fun LogsScreenContent(
    padding: PaddingValues,
    state: LogsScreenState,
    paused: Boolean,
    actions: LogsScreenActions,
) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(16.dp))
        SectionHeader("Logs", "Redacted runtime events")
        if (state.logsError != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = "⚠ Logs error: ${state.logsError.message}",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Spacer(Modifier.height(8.dp))
        LogFilterChips(filter = state.filter, onSelect = actions.onFilterSelect)
        Spacer(Modifier.height(8.dp))
        LogActionsRow(
            paused = paused,
            onTogglePause = actions.onTogglePause,
            onClear = actions.onClearLogs,
            menu =
                LogMenuActions(
                    onCopy = actions.onCopyLogs,
                    onExport = actions.onExport,
                    onShare = actions.onShare,
                ),
        )
        Spacer(Modifier.height(8.dp))
        LogList(
            visibleLogs = state.visibleLogs,
            debugHidden = state.debugHidden,
            filter = state.filter,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * Logs screen state collected from ViewModels.
 */
@Composable
private fun collectLogsScreenState(
    vm: LogsViewModel,
    networkVm: NetworkPolicyViewModel,
): LogsScreenState {
    val filter by vm.filter.collectAsStateWithLifecycle()
    val prefs by networkVm.preferences.collectAsStateWithLifecycle(initialValue = AndroidAppPreferences())
    val logs by vm.filteredLogs.collectAsStateWithLifecycle()
    val logsError by vm.logsError.collectAsStateWithLifecycle(initialValue = null)

    val visibleLogs = logs.filterNot { it.level.equals("debug", true) && !prefs.debugLogsEnabled }
    val debugHidden = logs.any { it.level.equals("debug", true) && !prefs.debugLogsEnabled }

    return LogsScreenState(
        filter = filter,
        logsError = logsError,
        visibleLogs = visibleLogs,
        debugHidden = debugHidden,
    )
}

/**
 * Logs screen state data class.
 */
private data class LogsScreenActions(
    val onTogglePause: () -> Unit,
    val onClearLogs: () -> Unit,
    val onCopyLogs: () -> Unit,
    val onExport: () -> Unit,
    val onShare: () -> Unit,
    val onFilterSelect: (String) -> Unit,
)

private data class LogsScreenState(
    val filter: String,
    val logsError: com.phillipchin.webrtctunnel.model.TunnelError?,
    val visibleLogs: List<LogEvent>,
    val debugHidden: Boolean,
)

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
        AppOutlinedButton(onClick = onTogglePause, modifier = Modifier.weight(1f)) {
            Text(if (paused) "Resume Logs" else "Pause Logs")
        }
        AppOutlinedButton(onClick = onClear, modifier = Modifier.weight(1f)) { Text("Clear Logs") }
        IconButton(onClick = { showActionsMenu = true }) {
            Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.cd_log_actions_menu))
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
    filter: String,
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
            val emptyMessage =
                if (filter == "all") "No logs yet." else "No ${filter.uppercase()} logs match this filter."
            item { EmptyStateCard(emptyMessage) }
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
            "debug" -> MaterialTheme.colorScheme.onSurfaceVariant
            else -> MaterialTheme.colorScheme.onSurface
        }
    StatusCard {
        Text(
            formatLogTimestamp(event.unixMs),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(event.level.uppercase(), color = levelColor, style = MaterialTheme.typography.labelLarge)
        Text(SensitiveDataRedactor.redactText(event.message))
    }
}

private fun redactedLogsText(logs: List<LogEvent>): String =
    logs
        .map(SensitiveDataRedactor::redactLogEvent)
        .joinToString("\n") { "${it.unixMs} ${it.level} ${it.message}" }
