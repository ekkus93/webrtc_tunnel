package com.phillipchin.webrtctunnel.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val Colors =
    lightColorScheme(
        primary = Color(0xFF08245C),
        onPrimary = Color(0xFFFFFFFF),
        secondary = Color(0xFF1D4ED8),
        tertiary = Color(0xFF2E7D32),
        background = Color(0xFFF6F8FB),
        onBackground = Color(0xFF111827),
        surface = Color(0xFFFFFFFF),
        onSurface = Color(0xFF111827),
        error = Color(0xFFD32F2F),
        outline = Color(0xFFE5E7EB),
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
fun WebRtcTunnelTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Colors, typography = AppTypography, content = content)
}
