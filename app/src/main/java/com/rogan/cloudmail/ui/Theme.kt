package com.rogan.cloudmail.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Blue = Color(0xFF1565C0)
val BlueDark = Color(0xFF0D47A1)

private val LightColors = lightColorScheme(
    primary = Blue, onPrimary = Color.White,
    primaryContainer = Color(0xFFD6E5FA), onPrimaryContainer = BlueDark,
    surface = Color(0xFFF7F9FC), onSurface = Color(0xFF1C1B1F),
    surfaceVariant = Color(0xFFE7EEF6), onSurfaceVariant = Color(0xFF545A62)
)

@Composable
fun CloudMailTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = LightColors, content = content)
}
