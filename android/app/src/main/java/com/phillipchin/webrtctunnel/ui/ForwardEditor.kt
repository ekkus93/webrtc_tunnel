package com.phillipchin.webrtctunnel.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import com.phillipchin.webrtctunnel.model.ForwardConfig

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
