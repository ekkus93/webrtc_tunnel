package com.phillipchin.webrtctunnel.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val LightColors =
    lightColorScheme(
        primary = Color(color = 0xFF08245C),
        onPrimary = Color(color = 0xFFFFFFFF),
        secondary = Color(color = 0xFF1D4ED8),
        tertiary = Color(color = 0xFF2E7D32),
        background = Color(color = 0xFFF6F8FB),
        onBackground = Color(color = 0xFF111827),
        surface = Color(color = 0xFFFFFFFF),
        onSurface = Color(color = 0xFF111827),
        onSurfaceVariant = Color(color = 0xFF6B7280),
        error = Color(color = 0xFFD32F2F),
        outline = Color(color = 0xFFE5E7EB),
    )

private val DarkColors =
    darkColorScheme(
        primary = Color(color = 0xFF93B4FF),
        onPrimary = Color(color = 0xFF06122B),
        secondary = Color(color = 0xFF93B4FF),
        tertiary = Color(color = 0xFF81C784),
        background = Color(color = 0xFF0E1116),
        onBackground = Color(color = 0xFFE5E7EB),
        surface = Color(color = 0xFF161A20),
        onSurface = Color(color = 0xFFE5E7EB),
        onSurfaceVariant = Color(color = 0xFF9CA3AF),
        error = Color(color = 0xFFEF5350),
        outline = Color(color = 0xFF374151),
    )

private val AppTypography =
    Typography(
        titleLarge = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold),
        titleMedium = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.SemiBold),
        titleSmall = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Medium),
        bodyLarge = TextStyle(fontSize = 16.sp),
        bodyMedium = TextStyle(fontSize = 14.sp),
        bodySmall = TextStyle(fontSize = 12.sp),
        labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
    )

@Composable
fun WebRtcTunnelTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = AppTypography,
        content = content,
    )
}
