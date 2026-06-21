package com.phillipchin.webrtctunnel.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.phillipchin.webrtctunnel.R
import com.phillipchin.webrtctunnel.model.ForwardConfig
import com.phillipchin.webrtctunnel.model.TunnelStatus
import com.phillipchin.webrtctunnel.viewmodel.ForwardsViewModel

@Composable
fun ForwardsScreen(
    padding: PaddingValues,
    vm: ForwardsViewModel,
    onOpenDetails: (String) -> Unit,
) {
    val forwards by vm.forwards.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val isBusy by vm.isBusy.collectAsStateWithLifecycle()
    var showAddDialog by remember { mutableStateOf(false) }
    LazyColumn(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SectionHeader("Forwards", "Manage local forwards")
                IconButton(onClick = { showAddDialog = true }, enabled = !isBusy) {
                    Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.cd_add_forward))
                }
            }
        }
        if (forwards.isEmpty()) {
            item { EmptyStateCard("No forwards configured. Tap + to add one.") }
        } else {
            items(forwards, key = { it.id }) { forward ->
                ForwardListRow(forward = forward, status = status, onClick = { onOpenDetails(forward.id) })
            }
        }
    }
    if (showAddDialog) {
        EditForwardDialog(
            editor = ForwardEditorState(ForwardEditorMode.Add, defaultNewForward(forwards)),
            existingForwards = forwards,
            validateDraft = vm::validateForwardDraft,
            onDismiss = { showAddDialog = false },
            onSave = {
                vm.saveForward(it)
                showAddDialog = false
            },
        )
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
    val isBusy by vm.isBusy.collectAsStateWithLifecycle()
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
        ForwardStatusDetailCard(forward = forward, runtime = runtime)
        Spacer(Modifier.height(12.dp))
        ForwardDetailActions(
            forward = forward,
            busy = isBusy,
            onTestPort = { vm.testLocalPort(forward) },
            onToggleEnabled = { vm.saveForward(forward.copy(enabled = !forward.enabled)) },
        )
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = { showEditDialog = true },
            enabled = !isBusy,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Edit") }
        Spacer(Modifier.height(8.dp))
        DestructiveActionButton("Delete Forward", enabled = !isBusy) { showDeleteDialog = true }
    }

    if (showDeleteDialog && forward != null) {
        DeleteForwardDialog(
            forward = forward,
            onConfirm = {
                vm.deleteForward(forward.id)
                showDeleteDialog = false
                onDeleteAndReturn()
            },
            onDismiss = { showDeleteDialog = false },
        )
    }
    if (showEditDialog && forward != null) {
        EditForwardDialog(
            editor = ForwardEditorState(ForwardEditorMode.Edit, forward),
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
private fun ForwardListRow(
    forward: ForwardConfig,
    status: TunnelStatus,
    onClick: () -> Unit,
) {
    StatusCard {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clickable { onClick() },
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(forward.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    "${forward.localHost}:${forward.localPort} -> ${forward.remoteForwardId}",
                    style = MaterialTheme.typography.bodySmall,
                )
                val runtime = status.forwards.firstOrNull { it.id == forward.id }
                val stateLabel =
                    mapForwardListenLabel(
                        runtime?.listenState?.name ?: if (forward.enabled) "configured" else "disabled",
                    )
                Text(stateLabel, color = stateColorToken(stateLabel))
            }
            Text("›", style = MaterialTheme.typography.titleLarge)
        }
    }
}

@Composable
private fun ForwardStatusDetailCard(
    forward: ForwardConfig,
    runtime: com.phillipchin.webrtctunnel.model.ForwardStatus?,
) {
    StatusCard {
        Text("Status: ${runtime?.listenState ?: if (forward.enabled) "Configured" else "Disabled"}")
        Text("Local address: ${localForwardAddress(forward)}")
        Text("Remote forward ID: ${forward.remoteForwardId}")
        runtime?.lastError?.let { Text("Last error: $it", color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun ForwardDetailActions(
    forward: ForwardConfig,
    busy: Boolean,
    onTestPort: () -> Unit,
    onToggleEnabled: () -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val localAddress = localForwardAddress(forward)
    val browserUrl = browserUrlForForward(forward)
    val canOpenBrowser = isBrowserOpenable(forward)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val copyLabel = if (canOpenBrowser) "Copy URL" else "Copy address"
        val copyValue = if (canOpenBrowser) browserUrl else localAddress
        OutlinedButton(
            onClick = { clipboard.setText(AnnotatedString(copyValue)) },
            modifier = Modifier.weight(1f),
        ) {
            Icon(Icons.Default.ContentCopy, contentDescription = null)
            Spacer(Modifier.size(4.dp))
            Text(copyLabel)
        }
        if (canOpenBrowser) {
            OutlinedButton(
                onClick = {
                    val intent =
                        Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse(browserUrl),
                        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                },
                modifier = Modifier.weight(1f),
            ) {
                Icon(
                    Icons.Default.OpenInBrowser,
                    contentDescription = stringResource(R.string.cd_open_in_browser, forward.name),
                )
                Text("Open Browser")
            }
        }
    }
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onTestPort, enabled = !busy, modifier = Modifier.weight(1f)) {
            Text("Test Local Port")
        }
        OutlinedButton(onClick = onToggleEnabled, enabled = !busy, modifier = Modifier.weight(1f)) {
            Text(if (forward.enabled) "Disable" else "Enable")
        }
    }
}

@Composable
private fun DeleteForwardDialog(
    forward: ForwardConfig,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete forward?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("This removes \"${forward.name}\" from configuration. This cannot be undone.")
                Text(
                    "${forward.localHost}:${forward.localPort} -> ${forward.remoteForwardId}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Delete", color = MaterialTheme.colorScheme.error)
            }
        },
    )
}
