package com.klk.hams.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Field-instrument typography:
//   - Monospace for tabular count digits (Roboto Mono on Android), so digit
//     positions never shift on increment.
//   - Sans-serif for status, labels, dialogs (system Roboto).
// Generous letter-spacing on small all-caps labels for daylight legibility.

private val Mono  = FontFamily.Monospace
private val Sans  = FontFamily.SansSerif

val Typography = Typography(
    // Hero count digit (set explicitly via Modifier in CountScreen — base unused).
    displayLarge = TextStyle(
        fontFamily = Mono, fontWeight = FontWeight.Black,
        fontSize = 144.sp, lineHeight = 144.sp, letterSpacing = (-2).sp
    ),
    // Section headings.
    headlineMedium = TextStyle(
        fontFamily = Sans, fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp, lineHeight = 28.sp, letterSpacing = 0.sp
    ),
    // Status pill values ("82%", "1", "2026-05-06").
    titleMedium = TextStyle(
        fontFamily = Sans, fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp, lineHeight = 22.sp, letterSpacing = 0.2.sp
    ),
    // Button caption ("HOLD 5S — NEW TASK").
    labelLarge = TextStyle(
        fontFamily = Sans, fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp, lineHeight = 20.sp, letterSpacing = 1.5.sp
    ),
    // Status pill captions ("BATTERY", "GPS", "TASK").
    labelMedium = TextStyle(
        fontFamily = Sans, fontWeight = FontWeight.Medium,
        fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 2.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = Sans, fontWeight = FontWeight.Normal,
        fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.3.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = Sans, fontWeight = FontWeight.Normal,
        fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.2.sp
    )
)
