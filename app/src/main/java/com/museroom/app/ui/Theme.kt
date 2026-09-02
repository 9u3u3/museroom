package com.museroom.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Light = lightColorScheme(
    primary = Color(0xFF5B3FD6),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFEFEAFD),
    onPrimaryContainer = Color(0xFF221258),
    background = Color(0xFFFBFAFC),
    onBackground = Color(0xFF16131F),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF16131F),
    surfaceVariant = Color(0xFFF3F1F7),
    onSurfaceVariant = Color(0xFF4A4459),
    outline = Color(0xFFCFC8DE),
    outlineVariant = Color(0xFFE4E0EC),
    error = Color(0xFFA81E17),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFA78BFA),
    onPrimary = Color(0xFF1B1035),
    primaryContainer = Color(0xFF272040),
    onPrimaryContainer = Color(0xFFE4DAFF),
    background = Color(0xFF131118),
    onBackground = Color(0xFFF2EFF7),
    surface = Color(0xFF1B1823),
    onSurface = Color(0xFFF2EFF7),
    surfaceVariant = Color(0xFF232030),
    onSurfaceVariant = Color(0xFFB8B1C7),
    outline = Color(0xFF423C55),
    outlineVariant = Color(0xFF2E2A3B),
    error = Color(0xFFFF9086),
)

@Composable
fun MuseroomTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) Dark else Light,
        content = content,
    )
}
