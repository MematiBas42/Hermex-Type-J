package io.github.jcartu.hermex.ui.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Port of iOS `BottomComposerMaterialFade` (`ChatTranscriptSupportingViews.swift:931`).
 *
 * Provides a translucent gradient fade mask behind the floating composer so that
 * transcript messages smoothly fade out as they scroll underneath the composer
 * and system navigation bar.
 *
 * Stops:
 * - 0.00: Transparent
 * - 0.18: 18% opacity
 * - 0.46: 72% opacity
 * - 1.00: 100% opacity
 */
@Composable
fun BottomComposerMaterialFade(
    composerHeight: Dp,
    modifier: Modifier = Modifier,
) {
    val isDark = isSystemInDarkTheme()
    val fadeHeight = maxOf(96.dp, composerHeight + 34.dp)
    val baseColor = MaterialTheme.colorScheme.surface
    val darkOverlay = if (isDark) Color.Black.copy(alpha = 0.58f) else Color.Transparent

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(fadeHeight)
            .graphicsLayer {
                compositingStrategy = CompositingStrategy.Offscreen
            }
            .drawWithContent {
                drawContent()
                drawRect(
                    brush = Brush.verticalGradient(
                        0.0f to Color.Transparent,
                        0.18f to Color.Black.copy(alpha = 0.18f),
                        0.46f to Color.Black.copy(alpha = 0.72f),
                        1.0f to Color.Black,
                    ),
                    blendMode = BlendMode.DstIn,
                )
            }
            .background(baseColor)
            .then(
                if (isDark) Modifier.background(darkOverlay) else Modifier
            ),
    )
}
