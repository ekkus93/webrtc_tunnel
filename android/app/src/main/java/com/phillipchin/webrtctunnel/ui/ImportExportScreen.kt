package com.phillipchin.webrtctunnel.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.phillipchin.webrtctunnel.viewmodel.ImportExportState
import com.phillipchin.webrtctunnel.viewmodel.ImportExportViewModel
import com.phillipchin.webrtctunnel.viewmodel.ImportKind
import kotlinx.coroutines.launch

@Composable
fun ImportExportScreen(
    padding: PaddingValues,
    vm: ImportExportViewModel,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var showPrivateExportWarning by remember { mutableStateOf(false) }
    var showRawConfigExportWarning by remember { mutableStateOf(false) }
    var showAdvanced by remember { mutableStateOf(false) }
    // Importing replaces on-device config/identity, so a picked file is held here and only
    // applied after the user confirms the overwrite.
    var pendingImport by remember { mutableStateOf<Pair<Uri, ImportKind>?>(null) }

    ScrollableScreenSurface(padding) {
        SectionHeader("Import / Export", "Use Android document picker and share actions")
        if (state.isBusy) {
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Config = tunnel settings (TOML). Private identity = this device's key (importing replaces it). " +
                "Public identity = the shareable key your peer authorizes.",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(8.dp))
        ImportExportPrimaryActions(
            vm = vm,
            busy = state.isBusy,
            onImportRequest = { uri, kind -> pendingImport = uri to kind },
            onExportConfigRequest = { showRawConfigExportWarning = true },
            onExportPrivateRequest = { showPrivateExportWarning = true },
        )
        Spacer(Modifier.height(12.dp))
        ImportExportAdvancedToggle(
            state = state,
            vm = vm,
            expanded = showAdvanced,
            onToggle = { showAdvanced = !showAdvanced },
        )
    }

    ImportExportDialogs(
        vm = vm,
        pendingImport = pendingImport,
        onImportHandled = { pendingImport = null },
        exportState =
            ExportDialogState(
                showRawConfig = showRawConfigExportWarning,
                showPrivateIdentity = showPrivateExportWarning,
                onDismissRawConfig = { showRawConfigExportWarning = false },
                onDismissPrivateIdentity = { showPrivateExportWarning = false },
            ),
    )
}

private class ExportDialogState(
    val showRawConfig: Boolean,
    val showPrivateIdentity: Boolean,
    val onDismissRawConfig: () -> Unit,
    val onDismissPrivateIdentity: () -> Unit,
)

@Composable
private fun ImportExportDialogs(
    vm: ImportExportViewModel,
    pendingImport: Pair<Uri, ImportKind>?,
    onImportHandled: () -> Unit,
    exportState: ExportDialogState,
) {
    val exportConfigLauncher =
        rememberLauncherForActivityResult(contract = ActivityResultContracts.CreateDocument("text/plain")) { uri ->
            if (uri != null) vm.exportConfigToUri(uri, confirmSensitive = true)
        }
    val exportPrivateIdentityLauncher =
        rememberLauncherForActivityResult(contract = ActivityResultContracts.CreateDocument("text/plain")) { uri ->
            if (uri != null) vm.exportPrivateIdentityToUri(uri, confirmRisk = true)
        }
    pendingImport?.let { (uri, kind) ->
        ImportConfirmDialog(
            kind = kind,
            onConfirm = {
                vm.importFromUri(uri, kind)
                onImportHandled()
            },
            onDismiss = onImportHandled,
        )
    }
    if (exportState.showRawConfig) {
        RawConfigExportDialog(
            onExport = {
                exportConfigLauncher.launch("p2ptunnel-config.toml")
                exportState.onDismissRawConfig()
            },
            onDismiss = exportState.onDismissRawConfig,
        )
    }
    if (exportState.showPrivateIdentity) {
        PrivateExportDialog(
            onExport = {
                exportPrivateIdentityLauncher.launch("identity-private.toml")
                exportState.onDismissPrivateIdentity()
            },
            onDismiss = exportState.onDismissPrivateIdentity,
        )
    }
}

@Composable
private fun ImportExportAdvancedToggle(
    state: ImportExportState,
    vm: ImportExportViewModel,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    OutlinedButton(onClick = onToggle, modifier = Modifier.fillMaxWidth()) {
        Text(if (expanded) "Hide Advanced paths" else "Show Advanced paths")
    }
    if (expanded) {
        Spacer(Modifier.height(8.dp))
        ImportExportAdvancedSection(state = state, vm = vm)
    }
}

@Composable
private fun ImportExportPrimaryActions(
    vm: ImportExportViewModel,
    busy: Boolean,
    onImportRequest: (Uri, ImportKind) -> Unit,
    onExportConfigRequest: () -> Unit,
    onExportPrivateRequest: () -> Unit,
) {
    val openTextDocumentLauncher =
        rememberLauncherForActivityResult(contract = ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) onImportRequest(uri, ImportKind.Config)
        }
    val openPrivateIdentityLauncher =
        rememberLauncherForActivityResult(contract = ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) onImportRequest(uri, ImportKind.PrivateIdentity)
        }
    val openPublicIdentityLauncher =
        rememberLauncherForActivityResult(contract = ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) onImportRequest(uri, ImportKind.PublicIdentity)
        }
    val exportPublicIdentityLauncher =
        rememberLauncherForActivityResult(contract = ActivityResultContracts.CreateDocument("text/plain")) { uri ->
            if (uri != null) vm.exportPublicIdentityToUri(uri)
        }
    SettingsSection("Primary actions") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                openTextDocumentLauncher.launch(arrayOf("text/*", "application/toml"))
            }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("Import config") }
            OutlinedButton(
                onClick = onExportConfigRequest,
                enabled = !busy,
                modifier = Modifier.weight(1f),
            ) { Text("Export config") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                openPrivateIdentityLauncher.launch(arrayOf("text/*"))
            }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("Import identity") }
            OutlinedButton(
                onClick = onExportPrivateRequest,
                enabled = !busy,
                modifier = Modifier.weight(1f),
            ) { Text("Export private identity") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                openPublicIdentityLauncher.launch(arrayOf("text/*"))
            }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("Import public identity") }
            OutlinedButton(onClick = {
                exportPublicIdentityLauncher.launch("identity-public.txt")
            }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("Export public identity") }
        }
        PublicIdentityShareRow(vm)
    }
}

@Composable
private fun PublicIdentityShareRow(vm: ImportExportViewModel) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
            onClick = {
                scope.launch {
                    runCatching {
                        val payload = vm.publicIdentityForShare()
                        val intent =
                            Intent.createChooser(
                                Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_SUBJECT, "WebRTC Tunnel public identity")
                                    putExtra(Intent.EXTRA_TEXT, payload)
                                },
                                "Share public identity",
                            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(intent)
                    }
                }
            },
            modifier = Modifier.weight(1f),
        ) {
            Icon(Icons.Default.Share, contentDescription = null)
            Spacer(Modifier.size(4.dp))
            Text("Share public identity")
        }
        OutlinedButton(
            onClick = {
                scope.launch { runCatching { clipboard.setText(AnnotatedString(vm.publicIdentityForShare())) } }
            },
            modifier = Modifier.weight(1f),
        ) {
            Icon(Icons.Default.ContentCopy, contentDescription = null)
            Spacer(Modifier.size(4.dp))
            Text("Copy public identity")
        }
    }
}

@Composable
private fun ImportExportAdvancedSection(
    state: ImportExportState,
    vm: ImportExportViewModel,
) {
    // Path-based imports overwrite the same on-device config/identity as the picker flow, so
    // they go through the same overwrite confirmation.
    var pendingImport by remember { mutableStateOf<ImportKind?>(null) }
    pendingImport?.let { kind ->
        ImportConfirmDialog(
            kind = kind,
            onConfirm = {
                when (kind) {
                    ImportKind.Config -> vm.importConfig()
                    ImportKind.PrivateIdentity -> vm.importPrivateIdentity()
                    ImportKind.PublicIdentity -> vm.importPublicIdentity()
                }
                pendingImport = null
            },
            onDismiss = { pendingImport = null },
        )
    }
    SettingsSection("Advanced (file paths)") {
        OutlinedTextField(
            value = state.configImportPath,
            onValueChange = { value -> vm.updateState { it.copy(configImportPath = value) } },
            label = { Text("Config import path") },
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = { pendingImport = ImportKind.Config },
            enabled = !state.isBusy,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Import config path") }
        OutlinedTextField(value = state.privateIdentityImportPath, onValueChange = {
                value ->
            vm.updateState { it.copy(privateIdentityImportPath = value) }
        }, label = { Text("Private identity import path") }, modifier = Modifier.fillMaxWidth())
        Button(
            onClick = { pendingImport = ImportKind.PrivateIdentity },
            enabled = !state.isBusy,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Import identity path") }
        OutlinedTextField(value = state.publicIdentityLine, onValueChange = {
                value ->
            vm.updateState { it.copy(publicIdentityLine = value) }
        }, label = { Text("Remote public identity line") }, modifier = Modifier.fillMaxWidth())
        Button(
            onClick = { pendingImport = ImportKind.PublicIdentity },
            enabled = !state.isBusy,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Import public identity line") }
    }
}

private fun importConfirmText(kind: ImportKind): Pair<String, String> =
    when (kind) {
        ImportKind.Config ->
            "Replace configuration?" to
                "Importing replaces your current tunnel configuration (broker, peer, and forwards). " +
                "This cannot be undone."
        ImportKind.PrivateIdentity ->
            "Replace private identity?" to
                "Importing replaces this device's private identity key. The current key is overwritten " +
                "and cannot be recovered."
        ImportKind.PublicIdentity ->
            "Import public identity?" to
                "This imports a remote public identity into your configuration."
    }

@Composable
private fun ImportConfirmDialog(
    kind: ImportKind,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val (title, message) = importConfirmText(kind)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Import") } },
    )
}

@Composable
private fun RawConfigExportDialog(
    onExport: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Raw Config Export Warning") },
        text = { Text("Config export may include sensitive operational details. Continue only if required.") },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        confirmButton = { TextButton(onClick = onExport) { Text("Export") } },
    )
}

@Composable
private fun PrivateExportDialog(
    onExport: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Private Identity Export Warning") },
        text = {
            Text(
                "Anyone with this file can impersonate this device. Export only if you understand this risk.",
            )
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        confirmButton = { TextButton(onClick = onExport) { Text("Export") } },
    )
}
