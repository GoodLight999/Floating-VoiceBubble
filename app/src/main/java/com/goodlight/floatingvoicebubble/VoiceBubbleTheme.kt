package com.goodlight.floatingvoicebubble

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

@Composable
internal fun VoiceBubbleTheme(content: @Composable () -> Unit) {
    val light = lightColorScheme(
        primary = Color(0xFF4257B2),
        onPrimary = Color.White,
        background = Color(0xFFF7F7F5),
        surface = Color(0xFFFFFFFF),
        surfaceVariant = Color(0xFFEDEEF2),
    )
    val dark = darkColorScheme(
        primary = Color(0xFFAEBBFF),
        background = Color(0xFF111318),
        surface = Color(0xFF191B20),
        surfaceVariant = Color(0xFF24272E),
    )
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) dark else light,
        content = content,
    )
}
