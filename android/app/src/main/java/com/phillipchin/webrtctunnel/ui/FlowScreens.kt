package com.phillipchin.webrtctunnel.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.phillipchin.webrtctunnel.model.AndroidAppPreferences
import com.phillipchin.webrtctunnel.model.NetworkStatus
import com.phillipchin.webrtctunnel.model.NetworkType
import com.phillipchin.webrtctunnel.model.SetupConfigInput
import com.phillipchin.webrtctunnel.viewmodel.SetupStep
import com.phillipchin.webrtctunnel.viewmodel.SetupViewModel

@Composable
fun SetupWizardScreen(padding: PaddingValues, vm: SetupViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val networkStatus by vm.networkStatus.collectAsStateWithLifecycle(
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
        initialValue = AndroidAppPreferences(),
    )
    var showMeteredWarningDialog by remember { mutableStateOf(false) }
    val input = state.input
    val forwards = vm.loadSavedForwards()
    val clipboard = LocalClipboardManager.current

    fun updateInput(update: SetupConfigInput) = vm.setInput(update)

    ScreenSurface(padding) {
        Text("Setup Wizard", style = MaterialTheme.typography.headlineSmall)
        Text("Step: ${state.currentStep}")
        Spacer(Modifier.height(8.dp))

        when (state.currentStep) {
            SetupStep.Mode -> {
                Text("Android v1 supports offer mode only.")
            }
            SetupStep.Identity -> {
                OutlinedTextField(
                    value = state.importIdentityPath,
                    onValueChange = vm::setImportIdentityPath,
                    label = { Text("Private identity import path") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = vm::generateIdentity) { Text("Generate identity") }
                    if (state.localPublicIdentity.isNotBlank()) {
                        TextButton(onClick = { clipboard.setText(AnnotatedString(state.localPublicIdentity)) }) {
                            Text("Copy local public identity")
                        }
                    }
                }
                if (state.localPublicIdentity.isNotBlank()) {
                    Text("Local public identity: ${state.localPublicIdentity}")
                }
            }
            SetupStep.Broker -> {
                OutlinedTextField(
                    value = input.brokerHost,
                    onValueChange = { updateInput(input.copy(brokerHost = it)) },
                    label = { Text("MQTT broker host") },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = input.brokerPort.toString(),
                    onValueChange = { value ->
                        updateInput(input.copy(brokerPort = value.toIntOrNull() ?: input.brokerPort))
                    },
                    label = { Text("MQTT broker port") },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = input.brokerUsername,
                    onValueChange = { updateInput(input.copy(brokerUsername = it)) },
                    label = { Text("Broker username") },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = input.brokerPasswordFile,
                    onValueChange = { updateInput(input.copy(brokerPasswordFile = it)) },
                    label = { Text("Broker password file (optional)") },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = input.topicPrefix,
                    onValueChange = { updateInput(input.copy(topicPrefix = it)) },
                    label = { Text("Topic prefix") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("TLS uses broker host with system default roots when CA file is omitted.")
            }
            SetupStep.Peer -> {
                OutlinedTextField(
                    value = input.localPeerId,
                    onValueChange = { updateInput(input.copy(localPeerId = it)) },
                    label = { Text("Local peer id") },
                    readOnly = state.identityPeerId != null,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (state.identityPeerId != null) {
                    Text("Local peer ID is locked to imported/generated identity: ${state.identityPeerId}")
                }
                OutlinedTextField(
                    value = input.remotePeerId,
                    onValueChange = { updateInput(input.copy(remotePeerId = it)) },
                    label = { Text("Remote peer id") },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = state.importPublicIdentity,
                    onValueChange = vm::setImportPublicIdentity,
                    label = { Text("Remote public identity line") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("Remote public identity is validated against the remote peer ID.")
                if (state.remoteIdentityPeerId != null) {
                    Text("Imported remote identity peer ID: ${state.remoteIdentityPeerId}")
                }
            }
            SetupStep.Forwards -> {
                Text("Configured forwards: ${forwards.size}")
                forwards.forEach { forward ->
                    Text("${forward.name}: ${forward.localHost}:${forward.localPort} -> ${forward.remoteForwardId}")
                }
                Text("Manage forwards in the Forwards tab.")
            }
            SetupStep.NetworkPolicy -> {
                Text("Current network: ${networkStatus.networkType} (${if (networkStatus.isMetered) "metered" else "unmetered"})")
                Text("Allowed by default: ${networkStatus.allowedByDefault}")
                Text("Allowed by user policy: ${networkStatus.allowedByUserPolicy}")
                Text("Tunnel allowed now: ${networkStatus.tunnelAllowed}")
                Text("Blocked reason: ${networkStatus.blockReason ?: "None"}")
                Text("Unknown network is always blocked for safety.")
                Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Text("Allow metered/cellular")
                    Switch(
                        checked = input.allowMetered,
                        onCheckedChange = { checked ->
                            if (checked && prefs.showMeteredWarning) {
                                showMeteredWarningDialog = true
                            } else {
                                updateInput(input.copy(allowMetered = checked))
                            }
                        },
                    )
                }
                Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Text("Resume on unmetered")
                    Switch(
                        checked = input.resumeOnUnmetered,
                        onCheckedChange = { checked -> updateInput(input.copy(resumeOnUnmetered = checked)) },
                    )
                }
            }
            SetupStep.Review -> {
                Text("Mode: offer")
                Text("Local identity: ${state.identityPeerId ?: input.localPeerId}")
                if (state.localPublicIdentity.isNotBlank()) {
                    Text("Local public identity: ${state.localPublicIdentity}")
                }
                Text("Remote peer: ${input.remotePeerId}")
                Text("Remote public identity: ${if (state.importPublicIdentity.isBlank()) "not set" else state.importPublicIdentity}")
                Text("Broker: ${input.brokerHost}:${input.brokerPort}")
                Text("Enabled forwards: ${forwards.count { it.enabled }}")
                Text("Allow metered: ${input.allowMetered}")
                Text("Resume on unmetered: ${input.resumeOnUnmetered}")
                Text("Identity import: ${if (state.importIdentityPath.isBlank()) "existing encrypted identity" else state.importIdentityPath}")
            }

        }

        if (showMeteredWarningDialog) {
            AlertDialog(
                onDismissRequest = { showMeteredWarningDialog = false },
                title = { Text("Cellular / Metered Data Warning") },
                text = {
                    Text(
                        "WebRTC Tunnel may use significant data and may trigger carrier charges or throttling. Enable metered/cellular only if you understand and accept this risk.",
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            updateInput(input.copy(allowMetered = true))
                            showMeteredWarningDialog = false
                        },
                    ) { Text("I understand") }
                },
                dismissButton = {
                    TextButton(onClick = { showMeteredWarningDialog = false }) { Text("Cancel") }
                },
            )
        }

        Spacer(Modifier.height(8.dp))
        state.errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        state.saveResult?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = vm::goBack) { Text("Back") }
            Button(onClick = vm::goNext) { Text("Next") }
            if (state.currentStep == SetupStep.Review) {
                Button(onClick = vm::saveAndApplyConfig) { Text("Save") }
                Button(onClick = vm::startTunnelFromReview) { Text("Start Tunnel") }
            }
        }
    }
}
