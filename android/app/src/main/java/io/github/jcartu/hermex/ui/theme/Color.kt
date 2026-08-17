package io.github.jcartu.hermex.ui.theme

import androidx.compose.ui.graphics.Color

// Brand palette — mirrors the iOS app's AppTheme/HeaderLogoColor defaults:
// near-black canvas, gold accent (#FFD700), quiet elevated surfaces.
val Gold = Color(0xFFFFD700)
val GoldDim = Color(0xFFE6C200)
val GoldDeep = Color(0xFF8A6D00) // darker gold for light-theme contrast
val CanvasDark = Color(0xFF0B0B0F)
val SurfaceDark = Color(0xFF141419)
val SurfaceElevatedDark = Color(0xFF1C1C23)
val SurfaceHighestDark = Color(0xFF24242D)
val OutlineDark = Color(0xFF3A3A45)
val TextPrimaryDark = Color(0xFFF2F2F7)
val TextSecondaryDark = Color(0xFF9A9AA5)
val CanvasLight = Color(0xFFF7F7FA)
val SurfaceLight = Color(0xFFFFFFFF)
val SurfaceElevatedLight = Color(0xFFF0F0F4)
val SurfaceHighestLight = Color(0xFFE8E8EE)
val OutlineLight = Color(0xFFC7C7CF)
val TextPrimaryLight = Color(0xFF141419)
val TextSecondaryLight = Color(0xFF5A5A66)

val ErrorDark = Color(0xFFFF6B6B)
val ErrorLight = Color(0xFFB3261E)
val SuccessDark = Color(0xFF34C759)
val SuccessLight = Color(0xFF1E7A34)

/** Parses "#RRGGBB" into a Color, or null when malformed. Mirrors Color(hexRGB:) in AppTheme.swift. */
fun parseHexColor(raw: String): Color? {
    val hex = raw.trim().removePrefix("#")
    if (hex.length != 6 || hex.any { !it.isDigit() && it.lowercaseChar() !in 'a'..'f' }) return null
    val value = hex.toLongOrNull(16) ?: return null
    return Color((value and 0xFF0000).toInt() shr 16, ((value and 0x00FF00).toInt() shr 8), (value and 0x0000FF).toInt())
}

/** Darkens a color toward black by [fraction] (0..1). */
fun Color.darkened(fraction: Float): Color {
    val f = fraction.coerceIn(0f, 1f)
    return Color(
        red = red * (1 - f),
        green = green * (1 - f),
        blue = blue * (1 - f),
        alpha = alpha,
    )
}
