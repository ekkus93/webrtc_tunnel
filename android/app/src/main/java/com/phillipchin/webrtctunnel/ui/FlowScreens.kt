package com.phillipchin.webrtctunnel.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.phillipchin.webrtctunnel.model.ForwardConfig
import com.phillipchin.webrtctunnel.model.NetworkStatus
import com.phillipchin.webrtctunnel.model.NetworkType
import com.phillipchin.webrtctunnel.viewmodel.SetupStep
import com.phillipchin.webrtctunnel.viewmodel.SetupViewModel
import com.phillipchin.webrtctunnel.viewmodel.SetupWizardState

private const val MAX_PORT = 65535

internal fun suggestNewForwardPort(
    existingForwards: List<ForwardConfig>,
    startPort: Int = 8080,
): Int {
    val usedPorts = existingForwards.filter { it.enabled }.map { it.localPort }.toSet()
    for (port in startPort..MAX_PORT) {
        if (port !in usedPorts) {
            return port
        }
    }
    return startPort
}

internal fun defaultNewForward(existingForwards: List<ForwardConfig>): ForwardConfig =
    ForwardConfig(
        id = "forward_${System.currentTimeMillis()}",
        name = "",
        localHost = "127.0.0.1",
        localPort = suggestNewForwardPort(existingForwards),
        remoteForwardId = "",
        enabled = true,
    )

@Composable
fun SetupWizardScreen(
    padding: PaddingValues,
    vm: SetupViewModel,
    onStartSuccess: () -> Unit = {},
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val forwards by vm.forwards.collectAsStateWithLifecycle()
    val networkStatus by vm.networkStatus.collectAsStateWithLifecycle(
        initialValue = NetworkStatus(NetworkType.NoNetwork, false, false, false, false, "No network"),
    )
    val canAdvance = state.canAdvance
    val clipboard = LocalClipboardManager.current
    val importPublicIdentityLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocument(),
        ) { uri ->
            if (uri != null) {
                vm.importPublicIdentityFromUri(uri)
            }
        }
    val importIdentityLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocument(),
        ) { uri ->
            if (uri != null) {
                vm.importIdentityFromUri(uri)
            }
        }
    var editingForward by remember { mutableStateOf<ForwardEditorState?>(null) }

    ScrollableScreenSurface(padding) {
        SectionHeader("Setup Wizard", "Configure tunnel in 7 guided steps")
        Spacer(Modifier.height(12.dp))
        WizardStepper(
            steps = SetupStep.entries.map { stepLabel(it) },
            currentIndex = state.currentStep.ordinal,
        )
        Spacer(Modifier.height(12.dp))
        when (state.currentStep) {
            SetupStep.Mode -> ModeStepContent()
            SetupStep.Identity ->
                IdentityStepContent(
                    vm = vm,
                    state = state,
                    onImportIdentityFile = { importIdentityLauncher.launch(arrayOf("text/*", "application/toml")) },
                )
            SetupStep.Broker -> BrokerStepContent(vm, state)
            SetupStep.Peer ->
                PeerStepContent(
                    vm = vm,
                    state = state,
                    onPaste = {
                        val text = clipboard.getText()?.text.orEmpty()
                        vm.setImportPublicIdentity(text)
                        vm.validateRemotePublicIdentity()
                    },
                    onImportFile = { importPublicIdentityLauncher.launch(arrayOf("text/*")) },
                )
            SetupStep.Forwards ->
                ForwardsStepContent(
                    forwards,
                    onAdd = { editingForward = beginAddForwardEdit(forwards) },
                    onEdit = { editingForward = beginEditForward(it) },
                    onDelete = vm::deleteForward,
                )
            SetupStep.NetworkPolicy -> PolicyStepContent(vm, state, networkStatus)
            SetupStep.Review -> ReviewStepContent(state, forwards)
        }
        state.brokerTestMessage?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = MaterialTheme.colorScheme.primary)
        }
        state.errorMessage?.let {
            Spacer(Modifier.height(8.dp))
            ErrorResolutionCard(summary = it, fix = "Adjust inputs for this step and try again.")
        }
        state.saveResult?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = Color(color = 0xFF2E7D32))
        }
        Spacer(Modifier.height(12.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = vm::cancel) { Text("Cancel") }
                OutlinedButton(onClick = vm::goBack, enabled = state.currentStep != SetupStep.Mode) { Text("Back") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.currentStep == SetupStep.Broker) {
                    OutlinedButton(onClick = vm::testBrokerConnection) { Text("Test TCP reachability") }
                }
                if (state.currentStep == SetupStep.Review) {
                    OutlinedButton(onClick = vm::saveAndApplyConfig, enabled = canAdvance) { Text("Save") }
                    Button(
                        onClick = { vm.startTunnelFromReview(onStartSuccess) },
                        enabled = canAdvance,
                    ) { Text("Start Tunnel") }
                } else {
                    Button(onClick = vm::goNext, enabled = canAdvance) { Text("Next") }
                }
            }
        }
    }

    editingForward?.let { editor ->
        EditForwardDialog(
            mode = editor.mode,
            initial = editor.draft,
            existingForwards = forwards,
            validateDraft = vm::validateForwardDraft,
            onDismiss = { editingForward = null },
            onSave = { updated ->
                vm.upsertForward(updated)
                editingForward = null
            },
        )
    }
}

private fun stepLabel(step: SetupStep): String =
    when (step) {
        SetupStep.Mode -> "Mode"
        SetupStep.Identity -> "Identity"
        SetupStep.Broker -> "Broker"
        SetupStep.Peer -> "Remote Peer"
        SetupStep.Forwards -> "Forwards"
        SetupStep.NetworkPolicy -> "Network Policy"
        SetupStep.Review -> "Review"
    }

@Composable
private fun ModeStepContent() {
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
private fun IdentityStepContent(
    vm: SetupViewModel,
    state: SetupWizardState,
    onImportIdentityFile: () -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var showRawPathImport by remember { mutableStateOf(false) }
    StatusCard {
        OutlinedTextField(value = state.input.localPeerId, onValueChange = {
            vm.setInput(state.input.copy(localPeerId = it))
        }, label = { Text("Local peer id") }, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onImportIdentityFile, modifier = Modifier.weight(1f)) { Text("Import identity file") }
            OutlinedButton(onClick = vm::generateIdentity, modifier = Modifier.weight(1f)) { Text("Generate identity") }
        }
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
                onClick = vm::importIdentityFromPath,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Import from path") }
        }
        if (state.localPublicIdentity.isNotBlank()) {
            Text("Local public identity:")
            Text(state.localPublicIdentity, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { clipboard.setText(AnnotatedString(state.localPublicIdentity)) },
                    modifier = Modifier.weight(1f),
                ) { Text("Copy Public Key") }
                OutlinedButton(
                    onClick = {
                        val share =
                            android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(android.content.Intent.EXTRA_SUBJECT, "WebRTC Tunnel public identity")
                                putExtra(android.content.Intent.EXTRA_TEXT, state.localPublicIdentity)
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
    }
}

@Composable
private fun BrokerStepContent(
    vm: SetupViewModel,
    state: SetupWizardState,
) {
    StatusCard {
        OutlinedTextField(value = state.input.brokerHost, onValueChange = {
            vm.setInput(state.input.copy(brokerHost = it))
        }, label = { Text("Broker host") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = state.input.brokerPort.toString(), onValueChange = {
                value ->
            vm.setInput(state.input.copy(brokerPort = value.toIntOrNull() ?: 0))
        }, label = { Text("Broker port") }, modifier = Modifier.fillMaxWidth())
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
        OutlinedTextField(
            value = state.input.brokerPassword,
            onValueChange = { vm.setInput(state.input.copy(brokerPassword = it)) },
            label = { Text("Broker password") },
            modifier = Modifier.fillMaxWidth(),
            visualTransformation = PasswordVisualTransformation(),
        )
        OutlinedButton(
            onClick = { vm.setAdvancedExpanded(!state.advancedExpanded) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (state.advancedExpanded) "Hide advanced" else "Show advanced")
        }
        if (state.advancedExpanded) {
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
    }
}

@Composable
private fun PeerStepContent(
    vm: SetupViewModel,
    state: SetupWizardState,
    onPaste: () -> Unit,
    onImportFile: () -> Unit,
) {
    StatusCard {
        OutlinedTextField(value = state.input.remotePeerId, onValueChange = {
            vm.setInput(state.input.copy(remotePeerId = it))
        }, label = { Text("Remote peer id") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = state.importPublicIdentity, onValueChange = vm::setImportPublicIdentity, label = {
            Text("Remote public identity")
        }, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = vm::validateRemotePublicIdentity,
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
private fun ForwardsStepContent(
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
private fun PolicyStepContent(
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
private fun ReviewStepContent(
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
                Text("Remote identity: will be validated at save")
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

internal fun beginAddForwardEdit(existingForwards: List<ForwardConfig>): ForwardEditorState =
    ForwardEditorState(
        mode = ForwardEditorMode.Add,
        draft = defaultNewForward(existingForwards),
    )

internal fun beginEditForward(existingForward: ForwardConfig): ForwardEditorState =
    ForwardEditorState(
        mode = ForwardEditorMode.Edit,
        draft = existingForward,
    )

internal data class ForwardEditorLabels(
    val title: String,
    val action: String,
)

internal fun forwardEditorLabels(mode: ForwardEditorMode): ForwardEditorLabels =
    when (mode) {
        ForwardEditorMode.Add -> ForwardEditorLabels(title = "Add Forward", action = "Add")
        ForwardEditorMode.Edit -> ForwardEditorLabels(title = "Edit Forward", action = "Save")
    }

@Composable
internal fun EditForwardDialog(
    mode: ForwardEditorMode,
    initial: ForwardConfig,
    existingForwards: List<ForwardConfig>,
    validateDraft: (ForwardConfig, List<ForwardConfig>) -> String?,
    onDismiss: () -> Unit,
    onSave: (ForwardConfig) -> Unit,
) {
    var value by remember(initial) { mutableStateOf(initial) }
    var validationError by remember(initial) { mutableStateOf<String?>(null) }
    val labels = forwardEditorLabels(mode)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(labels.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = value.name, onValueChange = {
                    value = value.copy(name = it)
                }, label = { Text("Display name") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = value.localHost, onValueChange = {
                    value = value.copy(localHost = it)
                }, label = { Text("Local host") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = value.localPort.toString(), onValueChange = {
                    value = value.copy(localPort = it.toIntOrNull() ?: 0)
                }, label = { Text("Local port") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = value.remoteForwardId, onValueChange = {
                    value = value.copy(remoteForwardId = it)
                }, label = { Text("Remote forward ID") }, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Enabled")
                    Spacer(Modifier.weight(1f))
                    Switch(checked = value.enabled, onCheckedChange = { value = value.copy(enabled = it) })
                }
                validationError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        confirmButton = {
            Button(onClick = {
                val error = validateDraft(value, existingForwards)
                if (error != null) {
                    validationError = error
                } else {
                    validationError = null
                    onSave(value)
                }
            }) { Text(labels.action) }
        },
    )
}
