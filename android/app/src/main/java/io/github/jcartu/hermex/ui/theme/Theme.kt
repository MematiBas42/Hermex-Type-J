package io.github.jcartu.hermex.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf

private fun darkScheme(accent: androidx.compose.ui.graphics.Color): ColorScheme = darkColorScheme(
    primary = accent,
    onPrimary = CanvasDark,
    primaryContainer = accent.darkened(0.55f),
    onPrimaryContainer = accent,
    secondary = TextSecondaryDark,
    onSecondary = CanvasDark,
    secondaryContainer = SurfaceHighestDark,
    onSecondaryContainer = TextPrimaryDark,
    tertiary = GoldDim,
    onTertiary = CanvasDark,
    background = CanvasDark,
    onBackground = TextPrimaryDark,
    surface = SurfaceDark,
    onSurface = TextPrimaryDark,
    surfaceVariant = SurfaceElevatedDark,
    onSurfaceVariant = TextSecondaryDark,
    surfaceDim = CanvasDark,
    surfaceBright = SurfaceHighestDark,
    surfaceContainerLowest = Color(0xFF0F0F14),
    surfaceContainerLow = SurfaceDark,
    surfaceContainer = SurfaceElevatedDark,
    surfaceContainerHigh = SurfaceHighestDark,
    surfaceContainerHighest = Color(0xFF2B2B35),
    inverseSurface = TextPrimaryDark,
    inverseOnSurface = CanvasDark,
    outline = OutlineDark,
    outlineVariant = Color(0xFF2A2A33),
    error = ErrorDark,
    onError = CanvasDark,
    errorContainer = ErrorDark.copy(alpha = 0.15f).compositeOverThis(CanvasDark),
    onErrorContainer = ErrorDark,
)

private fun lightScheme(accent: androidx.compose.ui.graphics.Color): ColorScheme = lightColorScheme(
    primary = accent.darkened(0.45f),
    onPrimary = androidx.compose.ui.graphics.Color.White,
    primaryContainer = accent.darkened(0.72f),
    onPrimaryContainer = accent.darkened(0.5f),
    secondary = TextSecondaryLight,
    onSecondary = androidx.compose.ui.graphics.Color.White,
    secondaryContainer = SurfaceHighestLight,
    onSecondaryContainer = TextPrimaryLight,
    tertiary = GoldDeep,
    onTertiary = androidx.compose.ui.graphics.Color.White,
    background = CanvasLight,
    onBackground = TextPrimaryLight,
    surface = SurfaceLight,
    onSurface = TextPrimaryLight,
    surfaceContainerHighest = Color(0xFFDDDDE4),
    onSurfaceVariant = TextSecondaryLight,
    surfaceDim = SurfaceElevatedLight,
    surfaceBright = androidx.compose.ui.graphics.Color.White,
    surfaceContainerLowest = androidx.compose.ui.graphics.Color.White,
    surfaceContainerLow = androidx.compose.ui.graphics.Color.White,
    surfaceContainer = SurfaceElevatedLight,
    surfaceContainerHigh = SurfaceHighestLight,
    inverseSurface = TextPrimaryLight,
    inverseOnSurface = CanvasLight,
    outline = OutlineLight,
    outlineVariant = Color(0xFFDDDDE4),
    error = ErrorLight,
    onError = androidx.compose.ui.graphics.Color.White,
    errorContainer = ErrorLight.copy(alpha = 0.12f).compositeOverThis(CanvasLight),
    onErrorContainer = ErrorLight,
)

private fun androidx.compose.ui.graphics.Color.compositeOverThis(base: androidx.compose.ui.graphics.Color) =
    androidx.compose.ui.graphics.lerp(base, this, alpha).copy(alpha = 1f)

/** Which colour family the app draws with. The user's accent tints primary actions in either. */
enum class Palette(val displayName: String, val defaultAccentHex: String) {
    TOKYO_NIGHT("Tokyo Night", TokyoNight.DEFAULT_ACCENT_HEX),
    CLASSIC("Classic", "#FFD700"),
    ;

    companion object {
        val DEFAULT = TOKYO_NIGHT

        fun fromName(raw: String?): Palette = entries.firstOrNull { it.name == raw } ?: DEFAULT
    }
}

/**
 * The app theme. [palette] picks the colour family (Tokyo Night by default);
 * [accentHex] is the user-selectable Header Logo Color from Settings and
 * tints primary actions exactly like the iOS app's PrimaryActionTintSettings.
 */
@Composable
fun HermexTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    palette: Palette = Palette.DEFAULT,
    accentHex: String = palette.defaultAccentHex,
    content: @Composable () -> Unit,
) {
    val accent = parseHexColor(accentHex) ?: parseHexColor(palette.defaultAccentHex) ?: Gold
    val scheme = when (palette) {
        Palette.TOKYO_NIGHT -> if (darkTheme) tokyoNightDarkScheme(accent) else tokyoNightLightScheme(accent)
        Palette.CLASSIC -> if (darkTheme) darkScheme(accent) else lightScheme(accent)
    }
    MaterialTheme(
        colorScheme = scheme,
        typography = HermexTypography,
        content = content,
    )
}
