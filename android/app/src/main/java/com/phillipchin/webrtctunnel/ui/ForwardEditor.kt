package com.phillipchin.webrtctunnel.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.input.KeyboardType
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
    editor: ForwardEditorState,
    existingForwards: List<ForwardConfig>,
    validateDraft: (ForwardConfig, List<ForwardConfig>) -> String?,
    onDismiss: () -> Unit,
    onSave: (ForwardConfig) -> Unit,
) {
    var value by remember(editor.draft) { mutableStateOf(editor.draft) }
    // Track the port as raw text so invalid input shows an error instead of silently
    // coercing to 0 (which read as a valid-looking value).
    var portText by remember(editor.draft) { mutableStateOf(editor.draft.localPort.toString()) }
    var crossFieldError by remember(editor.draft) { mutableStateOf<String?>(null) }
    val labels = forwardEditorLabels(editor.mode)
    val errors = forwardFieldErrors(value, portText, existingForwards)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(labels.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ForwardEditorFields(
                    value = value,
                    portText = portText,
                    errors = errors,
                    onValueChange = { value = it },
                    onPortChange = { text ->
                        portText = text
                        value = value.copy(localPort = text.toIntOrNull() ?: 0)
                    },
                )
                crossFieldError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        confirmButton = {
            Button(
                enabled = !errors.hasError,
                onClick = {
                    val error = validateDraft(value, existingForwards)
                    if (error != null) {
                        crossFieldError = error
                    } else {
                        crossFieldError = null
                        onSave(value)
                    }
                },
            ) { Text(labels.action) }
        },
    )
}

@Composable
private fun ForwardEditorFields(
    value: ForwardConfig,
    portText: String,
    errors: ForwardFieldErrors,
    onValueChange: (ForwardConfig) -> Unit,
    onPortChange: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = value.name,
            onValueChange = { onValueChange(value.copy(name = it)) },
            label = { Text("Display name") },
            isError = errors.name != null,
            supportingText = errors.name?.let { message -> { Text(message) } },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = value.localHost,
            onValueChange = { onValueChange(value.copy(localHost = it)) },
            label = { Text("Local host") },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = portText,
            onValueChange = { input -> if (input.length <= 5 && input.all(Char::isDigit)) onPortChange(input) },
            label = { Text("Local port") },
            isError = errors.port != null,
            supportingText = errors.port?.let { message -> { Text(message) } },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = value.remoteForwardId,
            onValueChange = { onValueChange(value.copy(remoteForwardId = it)) },
            label = { Text("Remote forward ID") },
            isError = errors.remoteId != null,
            supportingText = errors.remoteId?.let { message -> { Text(message) } },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Enabled")
            Spacer(Modifier.weight(1f))
            Switch(checked = value.enabled, onCheckedChange = { onValueChange(value.copy(enabled = it)) })
        }
    }
}

private const val MAX_PORT = 65535

internal data class ForwardFieldErrors(
    val name: String?,
    val port: String?,
    val remoteId: String?,
) {
    val hasError: Boolean get() = name != null || port != null || remoteId != null
}

/** Per-field, live validation for the forward editor (blank required fields, port range,
 * and a duplicate-port hint against other enabled forwards). The store's `validateForwards`
 * remains the authoritative cross-field gate at save time. */
internal fun forwardFieldErrors(
    draft: ForwardConfig,
    portText: String,
    existingForwards: List<ForwardConfig>,
): ForwardFieldErrors {
    val parsedPort = portText.toIntOrNull()
    val duplicatePort =
        existingForwards.any { it.id != draft.id && it.enabled && it.localPort == parsedPort }
    val portError =
        when {
            portText.isBlank() -> "Local port is required"
            parsedPort == null || parsedPort !in 1..MAX_PORT -> "Port must be between 1 and 65535"
            duplicatePort -> "Port already used by another enabled forward"
            else -> null
        }
    return ForwardFieldErrors(
        name = if (draft.name.isBlank()) "Display name is required" else null,
        port = portError,
        remoteId = if (draft.remoteForwardId.isBlank()) "Remote forward ID is required" else null,
    )
}

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
