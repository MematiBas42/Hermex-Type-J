package io.github.jcartu.hermex.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

/**
 * Tokyo Night palette (enkia/tokyo-night-vscode-theme, folke/tokyonight.nvim).
 * Dark is the "Night" variant; light is "Day". Hex values are the published
 * palette, unmodified, so the app matches editors and terminals using it.
 */
object TokyoNight {
    // Night
    val Bg = Color(0xFF1A1B26)
    val BgDark = Color(0xFF16161E)
    val BgHighlight = Color(0xFF292E42)
    val TerminalBlack = Color(0xFF414868)
    val Fg = Color(0xFFC0CAF5)
    val FgDark = Color(0xFFA9B1D6)
    val FgGutter = Color(0xFF3B4261)
    val Dark3 = Color(0xFF545C7E)
    val Comment = Color(0xFF565F89)
    val Dark5 = Color(0xFF737AA2)
    val Blue0 = Color(0xFF3D59A1)
    val Blue = Color(0xFF7AA2F7)
    val Cyan = Color(0xFF7DCFFF)
    val Blue1 = Color(0xFF2AC3DE)
    val Blue5 = Color(0xFF89DDFF)
    val Blue7 = Color(0xFF394B70)
    val Magenta = Color(0xFFBB9AF7)
    val Purple = Color(0xFF9D7CD8)
    val Orange = Color(0xFFFF9E64)
    val Yellow = Color(0xFFE0AF68)
    val Green = Color(0xFF9ECE6A)
    val Green1 = Color(0xFF73DACA)
    val Teal = Color(0xFF1ABC9C)
    val Red = Color(0xFFF7768E)
    val Red1 = Color(0xFFDB4B4B)

    // Day
    val DayBg = Color(0xFFE1E2E7)
    val DayBgDark = Color(0xFFD0D5E3)
    val DayBgHighlight = Color(0xFFC4C8DA)
    val DayFg = Color(0xFF3760BF)
    val DayFgDark = Color(0xFF6172B0)
    val DayFgGutter = Color(0xFFA8AECB)
    val DayComment = Color(0xFF848CB5)
    val DayBlue = Color(0xFF2E7DE9)
    val DayCyan = Color(0xFF007197)
    val DayMagenta = Color(0xFF9854F1)
    val DayPurple = Color(0xFF7847BD)
    val DayOrange = Color(0xFFB15C00)
    val DayYellow = Color(0xFF8C6C3E)
    val DayGreen = Color(0xFF587539)
    val DayTeal = Color(0xFF118C74)
    val DayRed = Color(0xFFF52A65)
    val DayTerminalBlack = Color(0xFFA1A6C5)
    val DayDark3 = Color(0xFF8990B3)

    /** The palette's own accent: `blue`, used for primary actions unless the user picks another. */
    const val DEFAULT_ACCENT_HEX = "#7AA2F7"

    /** Near-black text for use on a light accent such as yellow or cyan. */
    val OnAccentDark = BgDark
}

private fun Color.over(base: Color): Color = lerp(base, this, alpha).copy(alpha = 1f)

internal fun tokyoNightDarkScheme(accent: Color): ColorScheme = darkColorScheme(
    primary = accent,
    onPrimary = TokyoNight.OnAccentDark,
    primaryContainer = accent.copy(alpha = 0.22f).over(TokyoNight.Bg),
    onPrimaryContainer = accent,
    secondary = TokyoNight.FgDark,
    onSecondary = TokyoNight.BgDark,
    secondaryContainer = TokyoNight.BgHighlight,
    onSecondaryContainer = TokyoNight.Fg,
    tertiary = TokyoNight.Magenta,
    onTertiary = TokyoNight.BgDark,
    tertiaryContainer = TokyoNight.Magenta.copy(alpha = 0.22f).over(TokyoNight.Bg),
    onTertiaryContainer = TokyoNight.Magenta,
    background = TokyoNight.Bg,
    onBackground = TokyoNight.Fg,
    surface = TokyoNight.Bg,
    onSurface = TokyoNight.Fg,
    surfaceVariant = TokyoNight.BgHighlight,
    onSurfaceVariant = TokyoNight.FgDark,
    surfaceDim = TokyoNight.BgDark,
    surfaceBright = TokyoNight.TerminalBlack,
    surfaceContainerLowest = TokyoNight.BgDark,
    surfaceContainerLow = Color(0xFF1E2030),
    surfaceContainer = Color(0xFF24283B),
    surfaceContainerHigh = TokyoNight.BgHighlight,
    surfaceContainerHighest = Color(0xFF343A55),
    inverseSurface = TokyoNight.Fg,
    inverseOnSurface = TokyoNight.Bg,
    inversePrimary = TokyoNight.Blue0,
    outline = TokyoNight.Dark3,
    outlineVariant = TokyoNight.FgGutter,
    error = TokyoNight.Red,
    onError = TokyoNight.BgDark,
    errorContainer = TokyoNight.Red.copy(alpha = 0.18f).over(TokyoNight.Bg),
    onErrorContainer = TokyoNight.Red,
    scrim = Color.Black,
)

internal fun tokyoNightLightScheme(accent: Color): ColorScheme {
    // Day keeps the user's accent legible on the light canvas by pulling it toward the Day blue.
    val primary = lerp(accent, TokyoNight.DayBlue, 0.35f).darkened(0.1f)
    return lightColorScheme(
        primary = primary,
        onPrimary = Color.White,
        primaryContainer = primary.copy(alpha = 0.16f).over(TokyoNight.DayBg),
        onPrimaryContainer = primary.darkened(0.2f),
        secondary = TokyoNight.DayFgDark,
        onSecondary = Color.White,
        secondaryContainer = TokyoNight.DayBgHighlight,
        onSecondaryContainer = TokyoNight.DayFg,
        tertiary = TokyoNight.DayPurple,
        onTertiary = Color.White,
        tertiaryContainer = TokyoNight.DayPurple.copy(alpha = 0.16f).over(TokyoNight.DayBg),
        onTertiaryContainer = TokyoNight.DayPurple,
        background = TokyoNight.DayBg,
        onBackground = TokyoNight.DayFg,
        surface = TokyoNight.DayBg,
        onSurface = TokyoNight.DayFg,
        surfaceVariant = TokyoNight.DayBgHighlight,
        onSurfaceVariant = TokyoNight.DayFgDark,
        surfaceDim = TokyoNight.DayBgDark,
        surfaceBright = Color(0xFFEEF0F5),
        surfaceContainerLowest = Color(0xFFF2F3F8),
        surfaceContainerLow = Color(0xFFE9EAF0),
        surfaceContainer = TokyoNight.DayBg,
        surfaceContainerHigh = TokyoNight.DayBgDark,
        surfaceContainerHighest = TokyoNight.DayBgHighlight,
        inverseSurface = TokyoNight.Bg,
        inverseOnSurface = TokyoNight.Fg,
        inversePrimary = TokyoNight.Blue,
        outline = TokyoNight.DayDark3,
        outlineVariant = TokyoNight.DayFgGutter,
        error = TokyoNight.DayRed,
        onError = Color.White,
        errorContainer = TokyoNight.DayRed.copy(alpha = 0.14f).over(TokyoNight.DayBg),
        onErrorContainer = TokyoNight.DayRed,
        scrim = Color.Black,
    )
}
