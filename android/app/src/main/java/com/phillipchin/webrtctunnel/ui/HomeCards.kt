package com.phillipchin.webrtctunnel.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phillipchin.webrtctunnel.model.ForwardConfig
import com.phillipchin.webrtctunnel.model.NetworkStatus
import com.phillipchin.webrtctunnel.model.TunnelError
import com.phillipchin.webrtctunnel.model.TunnelMode
import com.phillipchin.webrtctunnel.model.TunnelStatus

@Composable
internal fun TunnelStatusCard(
    status: TunnelStatus,
    statusUi: HomeStatusUi,
    uptimeSeconds: Long?,
) {
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
        Text("Remote peer: ${status.remotePeerId ?: "Not configured"}")
        if (status.mode != TunnelMode.Offer) {
            Text("Active sessions: ${status.activeSessionCount}")
        }
        uptimeSeconds?.let { Text("Uptime: ${formatUptime(it)}") }
    }
}

@Composable
internal fun HomeNetworkCard(
    networkStatus: NetworkStatus,
    allowMeteredForCurrentSession: Boolean,
) {
    NetworkStatusCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NetworkTypeIcon(networkStatus.networkType)
            Text("Network", style = MaterialTheme.typography.titleMedium)
        }
        Text("Type: ${mapNetworkTypeLabel(networkStatus.networkType)}")
        Text(if (networkStatus.isMetered) "Metered" else "Unmetered")
        Text(if (networkStatus.tunnelAllowed) "Tunnel allowed" else "Tunnel blocked")
        networkStatus.blockReason?.let { Text("Reason: $it") }
        if (allowMeteredForCurrentSession) {
            Text("Metered override: active for this app run")
        }
    }
}

@Composable
internal fun HomeForwardsCard(
    configuredForwards: List<ForwardConfig>,
    status: TunnelStatus,
    onAdd: () -> Unit,
    onOpenDetails: (String) -> Unit,
) {
    StatusCard {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Forwards (${configuredForwards.size})", style = MaterialTheme.typography.titleMedium)
            IconButton(onClick = onAdd) {
                Icon(Icons.Filled.Add, contentDescription = "Add forward")
            }
        }
        if (configuredForwards.isEmpty()) {
            EmptyStateCard("No forwards configured.")
        } else {
            configuredForwards.forEach { forward ->
                key(forward.id) {
                    val runtime = status.forwards.firstOrNull { it.id == forward.id }
                    val stateLabel =
                        mapForwardListenLabel(
                            runtime?.listenState?.name ?: if (forward.enabled) "configured" else "disabled",
                        )
                    ForwardSummaryRow(
                        title = forward.name,
                        subtitle = "${forward.localHost}:${forward.localPort} -> ${forward.remoteForwardId}",
                        status = stateLabel,
                        statusColors = forwardStatusChipColors(stateLabel),
                        onClick = { onOpenDetails(forward.id) },
                    )
                }
            }
        }
    }
}

@Composable
internal fun HomeErrorCard(
    error: TunnelError?,
    onOpenLogs: () -> Unit,
) {
    error ?: return
    // Allow dismissing the current error from view; a new/different error re-shows because the
    // remember is keyed on the error identity.
    var dismissed by remember(error) { mutableStateOf(false) }
    if (dismissed) return
    Spacer(Modifier.height(12.dp))
    ErrorResolutionCard(
        summary = error.message,
        fix = "Open logs for details, then fix setup or broker/network settings and retry.",
        details = error.details,
        action = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onOpenLogs) { Text("View Logs") }
                TextButton(onClick = { dismissed = true }) { Text("Dismiss") }
            }
        },
    )
}
