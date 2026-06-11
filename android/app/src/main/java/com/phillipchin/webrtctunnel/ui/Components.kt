package com.phillipchin.webrtctunnel.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val Success = Color(0xFF2E7D32)
private val Warning = Color(0xFFF59E0B)
private val Error = Color(0xFFD32F2F)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TunnelTopAppBar(
    title: String,
    navigationIcon: @Composable (() -> Unit)? = null,
) {
    TopAppBar(
        title = { Text(title, style = MaterialTheme.typography.titleSmall) },
        colors =
            TopAppBarDefaults.topAppBarColors(
                containerColor = Color(0xFF061A3D),
                titleContentColor = Color.White,
                navigationIconContentColor = Color.White,
            ),
        navigationIcon = { navigationIcon?.invoke() },
    )
}

@Composable
fun SectionHeader(
    title: String,
    subtitle: String? = null,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Color(0xFF6B7280)) }
    }
}

@Composable
fun StatusCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = { content() })
    }
}

@Composable
fun NetworkStatusCard(content: @Composable () -> Unit) = StatusCard(content = content)

/**
 * Container/content color pair for a status chip. Both are set explicitly so chip
 * text stays readable regardless of theme — never rely on [Surface] to infer a
 * readable content color from an arbitrary custom container color.
 */
data class StatusChipColors(val container: Color, val content: Color)

/** Contrast-safe container/content colors for a forward status chip label. */
fun forwardStatusChipColors(label: String): StatusChipColors =
    when {
        label.contains("listening", ignoreCase = true) ||
            label.contains("connected", ignoreCase = true) ||
            label.contains("serving", ignoreCase = true) ->
            StatusChipColors(Success, Color.White)
        label.contains("error", ignoreCase = true) ||
            label.contains("invalid", ignoreCase = true) ||
            label.contains("attention", ignoreCase = true) ->
            StatusChipColors(Error, Color.White)
        label.contains("paused", ignoreCase = true) ||
            label.contains("starting", ignoreCase = true) ->
            StatusChipColors(Warning, Color(0xFF1F2937))
        // Neutral pair for Stopped / Disabled / Configured and any unknown label.
        else -> StatusChipColors(Color(0xFFE5E7EB), Color(0xFF374151))
    }

@Composable
fun ForwardSummaryRow(
    title: String,
    subtitle: String,
    status: String,
    statusColors: StatusChipColors =
        StatusChipColors(
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.onPrimaryContainer,
        ),
    onClick: (() -> Unit)? = null,
) {
    val rowModifier =
        if (onClick != null) {
            Modifier.fillMaxWidth().clickable(onClick = onClick)
        } else {
            Modifier.fillMaxWidth()
        }
    Row(
        modifier = rowModifier,
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Color(0xFF6B7280))
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Surface(
                shape = RoundedCornerShape(999.dp),
                color = statusColors.container,
                contentColor = statusColors.content,
            ) {
                Text(
                    status,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = statusColors.content,
                )
            }
            if (onClick != null) {
                Text("›", style = MaterialTheme.typography.titleLarge, color = Color(0xFF6B7280))
            }
        }
    }
}

@Composable
fun EmptyStateCard(message: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Text(message, modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun ErrorResolutionCard(
    summary: String,
    fix: String,
    details: String? = null,
    action: @Composable (() -> Unit)? = null,
) {
    var showDetails by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF5F5)),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(summary, color = Error, style = MaterialTheme.typography.titleMedium)
            Text(fix, style = MaterialTheme.typography.bodyMedium)
            details?.takeIf { it.isNotBlank() }?.let {
                OutlinedButton(onClick = { showDetails = !showDetails }) {
                    Text(if (showDetails) "Hide technical details" else "Show technical details")
                }
                if (showDetails) {
                    HorizontalDivider()
                    Text(it, style = MaterialTheme.typography.bodySmall, color = Color(0xFF6B7280))
                }
            }
            action?.invoke()
        }
    }
}

@Composable
fun WizardStepper(
    steps: List<String>,
    currentIndex: Int,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            steps.forEachIndexed { index, _ ->
                val active = index == currentIndex
                val completed = index < currentIndex
                val circleColor =
                    when {
                        active -> MaterialTheme.colorScheme.primary
                        completed -> MaterialTheme.colorScheme.primaryContainer
                        else -> Color(0xFFE5E7EB)
                    }
                Box(
                    modifier =
                        Modifier
                            .weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier =
                                Modifier
                                    .heightIn(min = 32.dp)
                                    .background(circleColor, RoundedCornerShape(999.dp))
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                "${index + 1}",
                                color = if (active) Color.White else Color(0xFF374151),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        if (index < steps.lastIndex) {
                            Box(
                                modifier =
                                    Modifier
                                        .weight(1f)
                                        .heightIn(min = 2.dp)
                                        .padding(horizontal = 4.dp)
                                        .background(if (completed) MaterialTheme.colorScheme.primary else Color(0xFFD1D5DB)),
                            )
                        }
                    }
                }
            }
        }
        Text(
            "Step ${currentIndex + 1} of ${steps.size}: ${steps[currentIndex]}",
            style = MaterialTheme.typography.titleSmall,
        )
    }
}

@Composable
fun SettingsSection(
    title: String,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

const val METERED_WARNING_MESSAGE =
    "WebRTC Tunnel can use significant mobile data and may incur overage charges or " +
        "throttling. Enable cellular or metered use only if you understand and accept this risk."

@Composable
fun MeteredWarningDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Cellular / Metered Data Warning") },
        text = { Text(METERED_WARNING_MESSAGE) },
        confirmButton = { TextButton(onClick = onConfirm) { Text("I understand") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun ScrollableScreenSurface(
    padding: androidx.compose.foundation.layout.PaddingValues,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        verticalArrangement = Arrangement.Top,
        content = content,
    )
}

@Composable
fun DestructiveActionButton(
    text: String,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
    ) {
        Text(text, color = Error)
    }
}

fun stateColorToken(state: String): Color =
    when {
        state.contains("connected", ignoreCase = true) || state.contains("listening", ignoreCase = true) -> Success
        state.contains("paused", ignoreCase = true) || state.contains("starting", ignoreCase = true) -> Warning
        state.contains("error", ignoreCase = true) || state.contains("invalid", ignoreCase = true) -> Error
        else -> Color(0xFF6B7280)
    }
