package io.github.jcartu.hermex.ui.feature.settings

import androidx.compose.ui.graphics.Color
import io.github.jcartu.hermex.ui.theme.Gold
import io.github.jcartu.hermex.ui.theme.parseHexColor
import io.github.jcartu.hermex.ui.theme.TokyoNight

/**
 * Port of `HeaderLogoColor` from Config/AppTheme.swift: the accent-color
 * presets Settings offers, plus the normalization/foreground helpers the
 * preview swatches use.
 */
data class HeaderLogoColorPreset(val name: String, val hex: String) {
    val color: Color get() = HeaderLogoColor.color(hex)
}

object HeaderLogoColor {
    const val defaultHex = TokyoNight.DEFAULT_ACCENT_HEX

    val presets = listOf(
        HeaderLogoColorPreset("Tokyo Blue", TokyoNight.DEFAULT_ACCENT_HEX),
        HeaderLogoColorPreset("Tokyo Magenta", "#BB9AF7"),
        HeaderLogoColorPreset("Tokyo Cyan", "#7DCFFF"),
        HeaderLogoColorPreset("Tokyo Green", "#9ECE6A"),
        HeaderLogoColorPreset("Tokyo Orange", "#FF9E64"),
        HeaderLogoColorPreset("Yellow", "#FFD700"),
        HeaderLogoColorPreset("Blue", "#5B7CFF"),
        HeaderLogoColorPreset("Purple", "#AF52DE"),
        HeaderLogoColorPreset("Red", "#FF3B30"),
        HeaderLogoColorPreset("Green", "#34C759"),
        HeaderLogoColorPreset("White", "#FFFFFF"),
    )

    fun normalizedHex(rawValue: String): String? {
        val hex = rawValue.trim().removePrefix("#")
        if (hex.length != 6 || !hex.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
            return null
        }
        return "#${hex.uppercase()}"
    }

    fun color(rawValue: String): Color =
        parseHexColor(normalizedHex(rawValue) ?: defaultHex) ?: Gold

    /**
     * True when the color is light enough that black foreground text reads
     * better than white (relative luminance > 0.62, same as iOS).
     */
    fun prefersDarkForeground(rawValue: String): Boolean {
        val components = rgbComponents(rawValue) ?: return true
        val (red, green, blue) = components
        val luminance = 0.2126 * red + 0.7152 * green + 0.0722 * blue
        return luminance > 0.62
    }

    fun displayName(rawValue: String): String {
        val hex = normalizedHex(rawValue) ?: defaultHex
        return presets.firstOrNull { it.hex == hex }?.name ?: "Custom"
    }

    private fun rgbComponents(rawValue: String): Triple<Double, Double, Double>? {
        val hex = normalizedHex(rawValue) ?: defaultHex
        val value = hex.drop(1).toLongOrNull(16) ?: return null
        return Triple(
            ((value and 0xFF0000) shr 16).toDouble() / 255.0,
            ((value and 0x00FF00) shr 8).toDouble() / 255.0,
            (value and 0x0000FF).toDouble() / 255.0,
        )
    }
}
