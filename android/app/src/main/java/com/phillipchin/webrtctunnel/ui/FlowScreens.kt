package com.phillipchin.webrtctunnel.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.phillipchin.webrtctunnel.model.SetupConfigInput
import com.phillipchin.webrtctunnel.viewmodel.SetupStep
import com.phillipchin.webrtctunnel.viewmodel.SetupViewModel

@Composable
fun SetupWizardScreen(padding: PaddingValues, vm: SetupViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
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
                OutlinedTextField(
                    value = state.importPublicIdentity,
                    onValueChange = vm::setImportPublicIdentity,
                    label = { Text("Remote public identity line") },
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
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = input.remotePeerId,
                    onValueChange = { updateInput(input.copy(remotePeerId = it)) },
                    label = { Text("Remote peer id") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            SetupStep.Forwards -> {
                Text("Configured forwards: ${forwards.size}")
                forwards.forEach { forward ->
                    Text("${forward.name}: ${forward.localHost}:${forward.localPort} -> ${forward.remoteForwardId}")
                }
                Text("Manage forwards in the Forwards tab.")
            }
            SetupStep.NetworkPolicy -> {
                Text("Allow metered/cellular: ${input.allowMetered}")
                Text("Resume on unmetered: ${input.resumeOnUnmetered}")
                Text("Unknown network is always blocked for safety.")
                Text("Network policy details are in Settings > Network policy.")
            }
            SetupStep.Review -> {
                Text("Mode: offer")
                Text("Broker: ${input.brokerHost}:${input.brokerPort}")
                Text("Local peer: ${input.localPeerId}")
                Text("Remote peer: ${input.remotePeerId}")
                Text("Enabled forwards: ${forwards.count { it.enabled }}")
                Text("Allow metered: ${input.allowMetered}")
                Text("Resume on unmetered: ${input.resumeOnUnmetered}")
                Text("Identity import: ${if (state.importIdentityPath.isBlank()) "existing encrypted identity" else state.importIdentityPath}")
                if (state.localPublicIdentity.isNotBlank()) {
                    Text("Local public identity: ${state.localPublicIdentity}")
                }
            }
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
