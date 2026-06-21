package com.phillipchin.webrtctunnel.ui

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.phillipchin.webrtctunnel.BuildConfig
import com.phillipchin.webrtctunnel.model.AndroidAppPreferences
import com.phillipchin.webrtctunnel.viewmodel.SettingsUiState
import com.phillipchin.webrtctunnel.viewmodel.SettingsViewModel
import kotlinx.coroutines.launch

private const val IDENTITY_DISPLAY_MAX = 28
private const val IDENTITY_PREFIX_CHARS = 16
private const val IDENTITY_SUFFIX_CHARS = 8

private fun truncateIdentity(key: String): String =
    if (key.length > IDENTITY_DISPLAY_MAX) {
        "${key.take(IDENTITY_PREFIX_CHARS)}…${key.takeLast(IDENTITY_SUFFIX_CHARS)}"
    } else {
        key
    }

data class SettingsNavActions(
    val onOpenSetup: () -> Unit,
    val onOpenLogs: () -> Unit,
    val onOpenNetworkPolicy: () -> Unit,
    val onOpenImportExport: () -> Unit,
)

@Composable
fun SettingsScreen(
    padding: PaddingValues,
    vm: SettingsViewModel,
    nav: SettingsNavActions,
) {
    val prefs by vm.preferences.collectAsStateWithLifecycle(initialValue = AndroidAppPreferences())
    val uiState by vm.uiState.collectAsStateWithLifecycle()
    var showResetConfirmDialog by remember { mutableStateOf(false) }
    ScrollableScreenSurface(padding) {
        SectionHeader("Settings", "Tunnel and app behavior")
        Spacer(Modifier.height(12.dp))
        SettingsTunnelSection(onOpenSetup = nav.onOpenSetup)
        Spacer(Modifier.height(12.dp))
        SettingsNetworkPolicySection(
            allowMetered = prefs.allowMetered,
            resumeOnUnmetered = prefs.resumeOnUnmetered,
            onOpenNetworkPolicy = nav.onOpenNetworkPolicy,
        )
        Spacer(Modifier.height(12.dp))
        SettingsConfigurationSection(vm = vm, uiState = uiState, onReset = { showResetConfirmDialog = true })
        Spacer(Modifier.height(12.dp))
        SettingsIdentitySection(
            uiState = uiState,
            onRetryIdentity = vm::refreshPublicIdentity,
            onOpenImportExport = nav.onOpenImportExport,
        )
        Spacer(Modifier.height(12.dp))
        SettingsDiagnosticsSection(vm = vm, onOpenLogs = nav.onOpenLogs)
        Spacer(Modifier.height(12.dp))
        SettingsAdvancedSection(prefs = prefs, vm = vm, onOpenSetup = nav.onOpenSetup)
        Spacer(Modifier.height(12.dp))
        SettingsAboutSection()
    }
    if (showResetConfirmDialog) {
        ResetConfigDialog(
            onConfirm = {
                vm.resetConfiguration()
                showResetConfirmDialog = false
            },
            onDismiss = { showResetConfirmDialog = false },
        )
    }
}

@Composable
private fun SettingsTunnelSection(onOpenSetup: () -> Unit) {
    SettingsSection("Tunnel") {
        OutlinedButton(onClick = onOpenSetup, modifier = Modifier.fillMaxWidth()) { Text("Run setup wizard again") }
    }
}

@Composable
private fun SettingsNetworkPolicySection(
    allowMetered: Boolean,
    resumeOnUnmetered: Boolean,
    onOpenNetworkPolicy: () -> Unit,
) {
    // Read-only summary; the canonical editable controls live in NetworkPolicyScreen.
    SettingsSection("Network Policy") {
        Text(
            "Cellular / metered: ${if (allowMetered) "Allowed" else "Blocked"}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "Wi-Fi resume: ${if (resumeOnUnmetered) "On" else "Off"}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(onClick = onOpenNetworkPolicy, modifier = Modifier.fillMaxWidth()) {
            Text("Open network policy details")
        }
    }
}

@Composable
private fun SettingsConfigurationSection(
    vm: SettingsViewModel,
    uiState: SettingsUiState,
    onReset: () -> Unit,
) {
    SettingsSection("Configuration") {
        OutlinedButton(
            onClick = { vm.validateConfig() },
            enabled = !uiState.isValidatingConfig,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (uiState.isValidatingConfig) "Validating…" else "Validate configuration") }
        // Hide a previous result while a fresh validation is running so it can't be misread as current.
        if (!uiState.isValidatingConfig) {
            uiState.configValidationMessage?.let { message ->
                val messageColor =
                    if (uiState.configValid == true) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    }
                Text(message, style = MaterialTheme.typography.bodySmall, color = messageColor)
            }
        }
        DestructiveActionButton("Reset configuration") { onReset() }
    }
}

@Composable
private fun SettingsIdentitySection(
    uiState: SettingsUiState,
    onRetryIdentity: () -> Unit,
    onOpenImportExport: () -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val publicIdentity = uiState.publicIdentity
    val hasPublicIdentity = !publicIdentity.isNullOrBlank()
    SettingsSection("Identity") {
        Text(
            if (publicIdentity != null) truncateIdentity(publicIdentity) else "No local public identity found.",
            style = MaterialTheme.typography.bodySmall,
        )
        uiState.publicIdentityLoadError?.let { error ->
            Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = onRetryIdentity, modifier = Modifier.fillMaxWidth()) { Text("Retry") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { clipboard.setText(AnnotatedString(publicIdentity.orEmpty())) },
                modifier = Modifier.weight(1f),
                enabled = hasPublicIdentity,
            ) { Text("Copy identity") }
            OutlinedButton(
                onClick = {
                    val share =
                        Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_SUBJECT, "WebRTC Tunnel public identity")
                            putExtra(Intent.EXTRA_TEXT, publicIdentity)
                        }
                    context.startActivity(
                        Intent.createChooser(
                            share,
                            "Share public identity",
                        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                },
                modifier = Modifier.weight(1f),
                enabled = hasPublicIdentity,
            ) { Text("Share identity") }
        }
        OutlinedButton(
            onClick = onOpenImportExport,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Import / Export identity") }
    }
}

@Composable
private fun SettingsDiagnosticsSection(
    vm: SettingsViewModel,
    onOpenLogs: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    SettingsSection("Diagnostics") {
        OutlinedButton(
            onClick = onOpenLogs,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Open logs / export diagnostics") }
        OutlinedButton(
            onClick = {
                scope.launch {
                    val share =
                        Intent.createChooser(vm.diagnosticsShareIntent(), "Share diagnostics")
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(share)
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Share diagnostics") }
    }
}

@Composable
private fun SettingsAdvancedSection(
    prefs: AndroidAppPreferences,
    vm: SettingsViewModel,
    onOpenSetup: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    SettingsSection("Advanced") {
        OutlinedButton(
            onClick = { vm.savePreferences(prefs.copy(advancedSettingsEnabled = !prefs.advancedSettingsEnabled)) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (prefs.advancedSettingsEnabled) "Hide advanced settings" else "Show advanced settings") }
        if (prefs.advancedSettingsEnabled) {
            PreferenceSwitch(
                "Enable debug logs",
                prefs.debugLogsEnabled,
            ) { vm.savePreferences(prefs.copy(debugLogsEnabled = it)) }
            OutlinedButton(
                onClick = onOpenSetup,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Change topic prefix (re-runs setup)") }
            OutlinedButton(
                onClick = onOpenSetup,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Change local bind address (re-runs setup)") }
            Text(
                "Answer mode (accepting connections from peers) needs a broker on this device and " +
                    "isn't supported on Android. This app runs in Offer (client) mode only.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                onClick = { clipboard.setText(AnnotatedString(vm.statusJson())) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Copy status JSON") }
            OutlinedButton(
                onClick = { scope.launch { clipboard.setText(AnnotatedString(vm.redactedConfigOrEmpty())) } },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Copy redacted config") }
        }
    }
}

@Composable
private fun SettingsAboutSection() {
    SettingsSection("About") {
        Text("Rust WebRTC Tunnel Android", style = MaterialTheme.typography.bodyMedium)
        Text(
            "Version ${BuildConfig.VERSION_NAME}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ResetConfigDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Reset configuration?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("This clears all saved configuration: broker, remote peer, and forwards.")
                Text(
                    "This cannot be undone. Your device identity key is kept.",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Keep configuration") } },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Reset", color = MaterialTheme.colorScheme.error) }
        },
    )
}
