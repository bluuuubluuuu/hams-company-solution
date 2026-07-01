package com.klk.hams.ui.theme

import androidx.compose.ui.graphics.Color

// Field-instrument palette — high contrast for daylight, gentle on the eye in glare.
// Background is a warm cream rather than pure white; ink is deep and warm rather than
// dead black. The single accent (forest) carries + / GPS-locked / saved-task semantics.
val FieldCream    = Color(0xFFF5EFE3)   // app background
val FieldSurface  = Color(0xFFFFFAF0)   // raised surfaces (count card, NEW TASK bar)
val FieldInk      = Color(0xFF1F1A14)   // primary text
val FieldInkSoft  = Color(0xFF6B6053)   // secondary text, status pill labels
val FieldHairline = Color(0xFFD9CFBE)   // 1 dp dividers, button borders

val FieldForest   = Color(0xFF2F5D3A)   // accent: + button, GPS locked, "active"
val FieldForestOn = Color(0xFFFFFAF0)   // text on forest accent
val FieldEarth    = Color(0xFF8C5A2B)   // − button (subdued, not destructive)
val FieldEarthOn  = Color(0xFFFFFAF0)   // text on earth

val FieldAmber    = Color(0xFFCB7A1A)   // GPS acquiring/stale, battery warn
val FieldScarlet  = Color(0xFFB23A2C)   // battery critical, errors
val FieldSlate    = Color(0xFF8A8276)   // GPS-off / disabled
