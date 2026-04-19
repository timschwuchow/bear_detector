package com.beardetector.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val LightColorScheme = lightColorScheme(
    primary = BearBrown,
    onPrimary = SurfaceLight,
    primaryContainer = BearBrownLight,
    secondary = SafeGreen,
    error = AlertRed,
    background = BackgroundLight,
    surface = SurfaceLight,
)

@Composable
fun BearDetectorTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LightColorScheme,
        content = content
    )
}
