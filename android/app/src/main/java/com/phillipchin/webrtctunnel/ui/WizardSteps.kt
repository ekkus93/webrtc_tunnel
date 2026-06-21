package com.phillipchin.webrtctunnel.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.phillipchin.webrtctunnel.model.ForwardConfig
import com.phillipchin.webrtctunnel.model.NetworkStatus
import com.phillipchin.webrtctunnel.viewmodel.SetupViewModel
import com.phillipchin.webrtctunnel.viewmodel.SetupWizardState

@Composable
internal fun ModeStepContent() {
    StatusCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Filled.PhoneAndroid, contentDescription = "Offer mode")
            Text("Welcome to WebRTC Tunnel Setup", style = MaterialTheme.typography.titleMedium)
        }
        Text("This wizard will guide you through configuring a secure tunnel to a remote server.")
        Text("Steps: identity → broker → remote peer → forwards → network policy → review.")
        Text(
            "This app operates in Offer (client) mode. Answer (server) mode is not available on Android.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
internal fun IdentityStepContent(
    vm: SetupViewModel,
    state: SetupWizardState,
    onImportIdentityFile: () -> Unit,
) {
    var showRawPathImport by remember { mutableStateOf(false) }
    StatusCard {
        OutlinedTextField(value = state.input.localPeerId, onValueChange = {
            vm.setInput(state.input.copy(localPeerId = it))
        }, label = { Text("Local peer id") }, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = onImportIdentityFile,
                enabled = !state.isBusy,
                modifier = Modifier.weight(1f),
            ) { Text("Import identity file") }
            OutlinedButton(
                onClick = vm.identity::generateIdentity,
                enabled = !state.isBusy,
                modifier = Modifier.weight(1f),
            ) { Text("Generate identity") }
        }
        Text(
            "Import opens the device file picker, or generate a fresh identity for this phone.",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedButton(onClick = { showRawPathImport = !showRawPathImport }, modifier = Modifier.fillMaxWidth()) {
            Text(if (showRawPathImport) "Hide advanced import options" else "Show advanced import options")
        }
        if (showRawPathImport) {
            Text(
                "Enter the full file path if you cannot use the file picker above.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedTextField(
                value = state.importIdentityPath,
                onValueChange = vm::setImportIdentityPath,
                label = { Text("Private identity file path") },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedButton(
                onClick = vm.identity::importIdentityFromPath,
                enabled = !state.isBusy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Import from path") }
        }
        if (state.localPublicIdentity.isNotBlank()) {
            LocalPublicIdentitySection(state.localPublicIdentity)
        }
    }
}

@Composable
private fun LocalPublicIdentitySection(identity: String) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    Text("Local public identity:")
    Text(identity, style = MaterialTheme.typography.bodySmall)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
            onClick = { clipboard.setText(AnnotatedString(identity)) },
            modifier = Modifier.weight(1f),
        ) { Text("Copy Public Key") }
        OutlinedButton(
            onClick = {
                val share =
                    android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(android.content.Intent.EXTRA_SUBJECT, "WebRTC Tunnel public identity")
                        putExtra(android.content.Intent.EXTRA_TEXT, identity)
                    }
                context.startActivity(
                    android.content.Intent.createChooser(
                        share,
                        "Share public identity",
                    ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            },
            modifier = Modifier.weight(1f),
        ) { Text("Share Public Key") }
    }
}

private const val MAX_PORT = 65535

@Composable
internal fun BrokerStepContent(
    vm: SetupViewModel,
    state: SetupWizardState,
) {
    // Track the port as raw text so invalid input shows an error instead of silently
    // coercing to 0 (which read as a valid-looking value).
    var brokerPortText by remember { mutableStateOf(state.input.brokerPort.toString()) }
    val parsedPort = brokerPortText.toIntOrNull()
    val portError =
        when {
            brokerPortText.isBlank() -> "Broker port is required"
            parsedPort == null || parsedPort !in 1..MAX_PORT -> "Port must be between 1 and 65535"
            else -> null
        }
    StatusCard {
        OutlinedTextField(value = state.input.brokerHost, onValueChange = {
            vm.setInput(state.input.copy(brokerHost = it))
        }, label = { Text("Broker host") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(
            value = brokerPortText,
            onValueChange = { input ->
                if (input.length <= 5 && input.all(Char::isDigit)) {
                    brokerPortText = input
                    vm.setInput(state.input.copy(brokerPort = input.toIntOrNull() ?: 0))
                }
            },
            label = { Text("Broker port") },
            isError = portError != null,
            supportingText = portError?.let { message -> { Text(message) } },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Use TLS")
            Spacer(Modifier.weight(1f))
            Switch(
                checked = state.input.brokerUseTls,
                onCheckedChange = { vm.setInput(state.input.copy(brokerUseTls = it)) },
            )
        }
        OutlinedTextField(value = state.input.brokerUsername, onValueChange = {
            vm.setInput(state.input.copy(brokerUsername = it))
        }, label = { Text("Broker username") }, modifier = Modifier.fillMaxWidth())
        BrokerPasswordField(
            value = state.input.brokerPassword,
            onChange = { vm.setInput(state.input.copy(brokerPassword = it)) },
        )
        OutlinedButton(
            onClick = { vm.setAdvancedExpanded(!state.advancedExpanded) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (state.advancedExpanded) "Hide advanced" else "Show advanced")
        }
        if (state.advancedExpanded) {
            BrokerAdvancedFields(vm = vm, state = state)
        }
        OutlinedButton(
            onClick = vm.save::testBrokerConnection,
            enabled = !state.isBusy,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Test TCP reachability") }
    }
}

@Composable
private fun BrokerPasswordField(
    value: String,
    onChange: (String) -> Unit,
) {
    var showPassword by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text("Broker password") },
        modifier = Modifier.fillMaxWidth(),
        visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            IconButton(onClick = { showPassword = !showPassword }) {
                Icon(
                    imageVector = if (showPassword) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                    contentDescription = if (showPassword) "Hide password" else "Show password",
                )
            }
        },
    )
}

@Composable
private fun BrokerAdvancedFields(
    vm: SetupViewModel,
    state: SetupWizardState,
) {
    OutlinedTextField(value = state.input.topicPrefix, onValueChange = {
        vm.setInput(state.input.copy(topicPrefix = it))
    }, label = { Text("Topic prefix") }, modifier = Modifier.fillMaxWidth())
    Text(
        "Change topic prefix only if your broker requires isolation from other users.",
        style = MaterialTheme.typography.bodySmall,
    )
    OutlinedTextField(value = state.input.brokerPasswordFile, onValueChange = {
        vm.setInput(state.input.copy(brokerPasswordFile = it))
    }, label = { Text("Broker password file (advanced)") }, modifier = Modifier.fillMaxWidth())
    Text(
        "Use this instead of the password field if your credentials are stored in a file on device.",
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
internal fun PeerStepContent(
    vm: SetupViewModel,
    state: SetupWizardState,
    onPaste: () -> Unit,
    onImportFile: () -> Unit,
) {
    StatusCard {
        OutlinedTextField(value = state.input.remotePeerId, onValueChange = {
            vm.setInput(state.input.copy(remotePeerId = it))
        }, label = { Text("Remote peer id") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(
            value = state.importPublicIdentity,
            onValueChange = vm::setImportPublicIdentity,
            label = { Text("Remote public identity") },
            supportingText = { Text("Paste the remote peer's public identity (plaintext or TOML), then validate.") },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = vm.identity::validateRemotePublicIdentity,
                enabled = !state.isBusy,
                modifier = Modifier.weight(1f),
            ) { Text("Validate remote identity") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onPaste, modifier = Modifier.weight(1f)) { Text("Paste from clipboard") }
            OutlinedButton(onClick = onImportFile, modifier = Modifier.weight(1f)) { Text("Import from file") }
        }
        Text("The answer side must authorize this phone's public identity.")
    }
}

@Composable
internal fun ForwardsStepContent(
    forwards: List<ForwardConfig>,
    onAdd: () -> Unit,
    onEdit: (ForwardConfig) -> Unit,
    onDelete: (String) -> Unit,
) {
    StatusCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Forward rules", style = MaterialTheme.typography.titleMedium)
            IconButton(onClick = onAdd) { Icon(Icons.Default.Add, contentDescription = "Add forward") }
        }
        if (forwards.isEmpty()) {
            Text("No forwards configured.")
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                forwards.forEach { forward ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(forward.name, style = MaterialTheme.typography.titleSmall)
                            Text("${forward.localHost}:${forward.localPort} -> ${forward.remoteForwardId}")
                        }
                        Row {
                            OutlinedButton(onClick = { onEdit(forward) }) { Text("Edit") }
                            IconButton(onClick = { onDelete(forward.id) }) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete forward ${forward.name}")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun PolicyStepContent(
    vm: SetupViewModel,
    state: SetupWizardState,
    networkStatus: NetworkStatus,
) {
    var showMeteredWarningDialog by remember { mutableStateOf(false) }
    StatusCard {
        Text("Current network: ${mapNetworkTypeLabel(networkStatus.networkType)}")
        Text(if (networkStatus.isMetered) "Metered" else "Unmetered")
        Text(if (networkStatus.tunnelAllowed) "Tunnel allowed" else "Tunnel blocked")
        networkStatus.blockReason?.let { Text("Reason: $it") }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Allow metered / cellular network")
            Spacer(Modifier.weight(1f))
            Switch(
                checked = state.input.allowMetered,
                onCheckedChange = { checked ->
                    if (checked) {
                        showMeteredWarningDialog = true
                    } else {
                        vm.setInput(state.input.copy(allowMetered = false))
                    }
                },
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Resume on unmetered")
            Spacer(Modifier.weight(1f))
            Switch(checked = state.input.resumeOnUnmetered, onCheckedChange = {
                vm.setInput(state.input.copy(resumeOnUnmetered = it))
            })
        }
    }
    if (showMeteredWarningDialog) {
        MeteredWarningDialog(
            onConfirm = {
                vm.setInput(state.input.copy(allowMetered = true))
                showMeteredWarningDialog = false
            },
            onDismiss = { showMeteredWarningDialog = false },
        )
    }
}

@Composable
internal fun ReviewStepContent(
    state: SetupWizardState,
    forwards: List<ForwardConfig>,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        StatusCard {
            Text("Mode", style = MaterialTheme.typography.titleMedium)
            Text("Offer")
        }
        StatusCard {
            Text("Local Identity", style = MaterialTheme.typography.titleMedium)
            Text("Local peer: ${state.input.localPeerId}")
            Text("Public identity: ${if (state.localPublicIdentity.isBlank()) "Not yet set" else "Ready"}")
        }
        StatusCard {
            Text("Remote Peer", style = MaterialTheme.typography.titleMedium)
            Text("Remote peer: ${state.input.remotePeerId}")
            if (state.remoteIdentityPeerId != null) {
                Text("Remote identity: validated (${state.remoteIdentityPeerId})")
            } else {
                Text(
                    "Remote identity: not validated yet — go back and validate to catch typos before saving.",
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        StatusCard {
            Text("Broker", style = MaterialTheme.typography.titleMedium)
            Text("${state.input.brokerHost}:${state.input.brokerPort}")
            Text("TLS: ${if (state.input.brokerUseTls) "Enabled" else "Disabled"}")
            Text("Topic prefix: ${state.input.topicPrefix}")
        }
        StatusCard {
            Text("Network Policy", style = MaterialTheme.typography.titleMedium)
            Text("Allow metered / cellular: ${if (state.input.allowMetered) "Yes" else "No"}")
            Text("Resume on Wi-Fi: ${if (state.input.resumeOnUnmetered) "Yes" else "No"}")
        }
        StatusCard {
            Text("Forwards", style = MaterialTheme.typography.titleMedium)
            Text("Enabled: ${forwards.count { it.enabled }} / ${forwards.size}")
            forwards.filter { it.enabled }.forEach { forward ->
                Text("${forward.localHost}:${forward.localPort} -> ${forward.remoteForwardId}")
            }
        }
    }
}

internal enum class ForwardEditorMode {
    Add,
    Edit,
}

internal data class ForwardEditorState(
    val mode: ForwardEditorMode,
    val draft: ForwardConfig,
)
