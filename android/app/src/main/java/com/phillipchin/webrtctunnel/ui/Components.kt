package com.phillipchin.webrtctunnel.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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

internal val Success = Color(color = 0xFF2E7D32)
internal val Warning = Color(color = 0xFFF59E0B)
internal val Error = Color(color = 0xFFD32F2F)

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
                containerColor = Color(color = 0xFF061A3D),
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
        subtitle?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
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
            StatusChipColors(Warning, Color(color = 0xFF1F2937))
        // Neutral pair for Stopped / Disabled / Configured and any unknown label.
        else -> StatusChipColors(Color(color = 0xFFE5E7EB), Color(color = 0xFF374151))
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
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
                Text(
                    "›",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
        colors = CardDefaults.cardColors(containerColor = Color(color = 0xFFFFF5F5)),
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
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            action?.invoke()
        }
    }
}

fun stateColorToken(state: String): Color =
    when {
        state.contains("connected", ignoreCase = true) ||
            state.contains("listening", ignoreCase = true) ||
            state.contains("running", ignoreCase = true) -> Success
        state.contains("paused", ignoreCase = true) || state.contains("starting", ignoreCase = true) -> Warning
        state.contains("error", ignoreCase = true) || state.contains("invalid", ignoreCase = true) -> Error
        else -> Color(color = 0xFF6B7280)
    }
