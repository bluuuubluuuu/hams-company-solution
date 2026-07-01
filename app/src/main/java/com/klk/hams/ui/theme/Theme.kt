package com.klk.hams.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

// Single light scheme — field instrument doesn't follow OS dark mode and never
// adopts dynamic color (would replace the forest accent with whatever wallpaper
// shade the OS picked, breaking the GPS-locked / count semantics).
private val FieldColorScheme = lightColorScheme(
    primary           = FieldForest,
    onPrimary         = FieldForestOn,
    secondary         = FieldEarth,
    onSecondary       = FieldEarthOn,
    background        = FieldCream,
    onBackground      = FieldInk,
    surface           = FieldSurface,
    onSurface         = FieldInk,
    surfaceVariant    = FieldCream,
    onSurfaceVariant  = FieldInkSoft,
    outline           = FieldHairline,
    error             = FieldScarlet,
    onError           = FieldForestOn
)

@Composable
fun HAMSTaskRecorderTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = FieldColorScheme,
        typography = Typography,
        content = content
    )
}
