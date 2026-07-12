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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.phillipchin.webrtctunnel.model.ForwardConfig
import com.phillipchin.webrtctunnel.model.NetworkPolicyStatus
import com.phillipchin.webrtctunnel.model.NetworkType
import com.phillipchin.webrtctunnel.viewmodel.SetupStep
import com.phillipchin.webrtctunnel.viewmodel.SetupViewModel
import com.phillipchin.webrtctunnel.viewmodel.SetupWizardState

@Composable
fun SetupWizardScreen(
    padding: PaddingValues,
    vm: SetupViewModel,
    onStartSuccess: () -> Unit = {},
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val forwards by vm.forwards.collectAsStateWithLifecycle()
    val networkStatus by vm.networkStatus.collectAsStateWithLifecycle(
        initialValue = NetworkPolicyStatus(NetworkType.NoNetwork, false, false, false, false, "No network"),
    )
    var editingForward by remember { mutableStateOf<ForwardEditorState?>(null) }
    var showCancelConfirm by remember { mutableStateOf(false) }

    ScrollableScreenSurface(padding) {
        SectionHeader("Setup Wizard", "Configure tunnel in 7 guided steps")
        Spacer(Modifier.height(12.dp))
        WizardStepper(
            steps = SetupStep.entries.map { stepLabel(it) },
            currentIndex = state.currentStep.ordinal,
        )
        Spacer(Modifier.height(12.dp))
        WizardStepContent(
            state = state,
            vm = vm,
            forwards = forwards,
            networkStatus = networkStatus,
            onEditForward = { editingForward = it },
        )
        WizardMessages(state)
        Spacer(Modifier.height(12.dp))
        WizardNavigationButtons(
            state = state,
            vm = vm,
            canAdvance = state.canAdvance,
            onStartSuccess = onStartSuccess,
            // Cancel wipes all wizard input, so confirm once the user has entered anything worth losing.
            onCancelRequest = { if (hasWizardProgress(state)) showCancelConfirm = true else vm.cancel() },
        )
    }

    if (showCancelConfirm) {
        CancelSetupDialog(
            onConfirm = {
                vm.cancel()
                showCancelConfirm = false
            },
            onDismiss = { showCancelConfirm = false },
        )
    }

    editingForward?.let { editor ->
        EditForwardDialog(
            editor = editor,
            existingForwards = forwards,
            validateDraft = vm.forwardsEditor::validateForwardDraft,
            onDismiss = { editingForward = null },
            onSave = { updated ->
                vm.forwardsEditor.upsertForward(updated)
                editingForward = null
            },
        )
    }
}

@Composable
private fun WizardStepContent(
    state: SetupWizardState,
    vm: SetupViewModel,
    forwards: List<ForwardConfig>,
    networkStatus: NetworkPolicyStatus,
    onEditForward: (ForwardEditorState?) -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    val importPublicIdentityLauncher =
        rememberLauncherForActivityResult(contract = ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                vm.identity.importPublicIdentityFromUri(uri)
            }
        }
    val importIdentityLauncher =
        rememberLauncherForActivityResult(contract = ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                vm.identity.importIdentityFromUri(uri)
            }
        }
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
                    vm.identity.validateRemotePublicIdentity()
                },
                onImportFile = { importPublicIdentityLauncher.launch(arrayOf("text/*")) },
            )
        SetupStep.Forwards ->
            ForwardsStepContent(
                forwards,
                onAdd = { onEditForward(beginAddForwardEdit(forwards)) },
                onEdit = { onEditForward(beginEditForward(it)) },
                onDelete = vm.forwardsEditor::deleteForward,
            )
        SetupStep.NetworkPolicy -> PolicyStepContent(vm, state, networkStatus)
        SetupStep.Review -> ReviewStepContent(state, forwards)
    }
}

@Composable
private fun WizardMessages(state: SetupWizardState) {
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
}

@Composable
private fun CancelSetupDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Discard setup?") },
        text = { Text("This clears everything you've entered in the wizard. This cannot be undone.") },
        dismissButton = { AppOutlinedButton(onClick = onDismiss) { Text("Keep editing") } },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Discard", color = MaterialTheme.colorScheme.error)
            }
        },
    )
}

@Composable
private fun WizardNavigationButtons(
    state: SetupWizardState,
    vm: SetupViewModel,
    canAdvance: Boolean,
    onStartSuccess: () -> Unit,
    onCancelRequest: () -> Unit,
) {
    val busy = state.isBusy
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppOutlinedButton(onClick = onCancelRequest, enabled = !busy) { Text("Cancel") }
                AppOutlinedButton(
                    onClick = vm::goBack,
                    enabled = state.currentStep != SetupStep.Mode && !busy,
                ) { Text("Back") }
            }
            if (state.currentStep == SetupStep.Review) {
                AppOutlinedButton(onClick = vm.save::saveAndApplyConfig, enabled = canAdvance && !busy) { Text("Save") }
            } else {
                AppFilledButton(onClick = vm::goNext, enabled = canAdvance && !busy) { Text("Next") }
            }
        }
        if (state.currentStep == SetupStep.Review) {
            AppFilledButton(
                onClick = { vm.save.startTunnelFromReview(onStartSuccess) },
                enabled = canAdvance && !busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Start Tunnel") }
        }
    }
}

private fun hasWizardProgress(state: SetupWizardState): Boolean =
    state.currentStep != SetupStep.Mode ||
        state.input.brokerHost.isNotBlank() ||
        state.importPublicIdentity.isNotBlank() ||
        state.localPublicIdentity.isNotBlank() ||
        state.importIdentityPath.isNotBlank()

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
