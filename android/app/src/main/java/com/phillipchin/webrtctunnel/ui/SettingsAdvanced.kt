package com.phillipchin.webrtctunnel.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.phillipchin.webrtctunnel.model.AndroidAppPreferences
import com.phillipchin.webrtctunnel.viewmodel.SettingsViewModel
import kotlinx.coroutines.launch

@Composable
internal fun SettingsAdvancedSection(
    prefs: AndroidAppPreferences,
    vm: SettingsViewModel,
    onOpenSetup: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    SettingsSection("Advanced") {
        AppOutlinedButton(
            onClick = { vm.savePreferences(prefs.copy(advancedSettingsEnabled = !prefs.advancedSettingsEnabled)) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (prefs.advancedSettingsEnabled) "Hide advanced settings" else "Show advanced settings") }
        if (prefs.advancedSettingsEnabled) {
            PreferenceSwitch(
                "Enable debug logs",
                prefs.debugLogsEnabled,
            ) { vm.savePreferences(prefs.copy(debugLogsEnabled = it)) }
            IceModeSetting(
                selected = prefs.androidIceMode,
                onSelect = { vm.savePreferences(prefs.copy(androidIceMode = it)) },
            )
            AppOutlinedButton(
                onClick = onOpenSetup,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Change topic prefix (re-runs setup)") }
            AppOutlinedButton(
                onClick = onOpenSetup,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Change local bind address (re-runs setup)") }
            Text(
                "Answer mode (accepting connections from peers) needs a broker on this device and " +
                    "isn't supported on Android. This app runs in Offer (client) mode only.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            AppOutlinedButton(
                onClick = { clipboard.setText(AnnotatedString(vm.statusJson())) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Copy status JSON") }
            AppOutlinedButton(
                onClick = { scope.launch { clipboard.setText(AnnotatedString(vm.redactedConfig())) } },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Copy redacted config") }
        }
    }
}

@Composable
private fun IceModeSetting(
    selected: String,
    onSelect: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Connection (ICE) mode", style = MaterialTheme.typography.bodyLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("native", "vnet_mux").forEach { mode ->
                FilterChip(
                    selected = selected == mode,
                    onClick = { onSelect(mode) },
                    label = { Text(mode) },
                )
            }
        }
        Text(
            "Use native to reach a peer on a different network (over the internet). Use vnet_mux " +
                "to reach a peer on this same Wi-Fi. Takes effect the next time you start the tunnel.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
