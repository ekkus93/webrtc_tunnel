package com.phillipchin.webrtctunnel.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.phillipchin.webrtctunnel.model.AndroidAppPreferences
import com.phillipchin.webrtctunnel.model.NetworkPolicyStatus
import com.phillipchin.webrtctunnel.model.NetworkType
import com.phillipchin.webrtctunnel.viewmodel.NetworkPolicyViewModel

@Composable
fun NetworkPolicyScreen(
    padding: PaddingValues,
    vm: NetworkPolicyViewModel,
) {
    val status by vm.networkStatus.collectAsStateWithLifecycle(
        initialValue = NetworkPolicyStatus(NetworkType.NoNetwork, false, false, false, false, "No network"),
    )
    val prefs by vm.preferences.collectAsStateWithLifecycle(initialValue = AndroidAppPreferences())
    var showMeteredWarningDialog by remember { mutableStateOf(false) }

    ScrollableScreenSurface(padding) {
        SectionHeader("Network Policy", "Current network and tunnel policy")
        Spacer(Modifier.height(8.dp))
        NetworkPolicyStatusCard {
            Text("Current network: ${mapNetworkTypeLabel(status.networkType)}")
            Text(if (status.isMetered) "High data usage (metered network)" else "Unmetered (Wi-Fi)")
            Text(
                if (status.tunnelAllowed) "Tunnel allowed on this network" else "Tunnel paused on this network",
                color =
                    if (status.tunnelAllowed) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error,
            )
            if (!status.tunnelAllowed) {
                status.blockReason?.let { Text("Reason: $it") }
            }
        }
        Spacer(Modifier.height(12.dp))
        PreferenceSwitch(
            title = "Allow metered/cellular",
            checked = prefs.allowMetered,
            onToggle = { checked ->
                if (checked) showMeteredWarningDialog = true else vm.savePreferences(prefs.copy(allowMetered = false))
            },
        )
        PreferenceSwitch("Resume on unmetered", prefs.resumeOnUnmetered) {
            vm.savePreferences(prefs.copy(resumeOnUnmetered = it))
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
