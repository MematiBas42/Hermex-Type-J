package io.github.jcartu.hermex.ui.feature.chat

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlin.math.min

/**
 * Port of the streamed-text fade-in effect from `StreamingTextFade.swift` /
 * `StreamingTextFadeRenderer.swift` (issue #213), simplified per the port
 * contract: instead of iOS's per-glyph reveal queue (a custom `TextRenderer`,
 * unavailable to a markdown composable we don't own), the trailing region of
 * the streaming body fades from transparent to solid every time the content
 * grows. Token accumulation itself is untouched — this is display-only.
 *
 * The fade curve and duration are the shipped iOS "Balanced" baseline:
 * 0.35s, quadratic ease-out from 0 opacity.
 */
object StreamingTextFadeDefaults {
    /** `StreamingTextFadeDefaults.Baseline.fadeDuration` (0.35s). */
    const val FADE_DURATION_MILLIS = 350

    /** `StreamingTextFadeDefaults.floorOpacity` — a new region starts invisible. */
    const val FLOOR_OPACITY = 0f

    /** Maximum height of the trailing fade band (~4 lines of body text). */
    val MAX_FADE_BAND_HEIGHT = 96.dp
}

/**
 * Port of `StreamingTextFadeCurve.opacity`: quadratic ease-out from the floor
 * opacity, clamped solid at/after [fadeDurationMillis].
 */
object StreamingTextFadeCurve {
    fun opacity(
        ageMillis: Long,
        fadeDurationMillis: Int = StreamingTextFadeDefaults.FADE_DURATION_MILLIS,
        floorOpacity: Float = StreamingTextFadeDefaults.FLOOR_OPACITY,
    ): Float {
        if (fadeDurationMillis <= 0) return 1f
        if (ageMillis >= fadeDurationMillis) return 1f
        val t = ageMillis.coerceAtLeast(0L).toFloat() / fadeDurationMillis
        val eased = 1f - (1f - t) * (1f - t)
        return floorOpacity + (1f - floorOpacity) * eased
    }
}

/** Quadratic ease-out, matching `StreamingTextFadeCurve`. */
private val QuadraticEaseOut = Easing { fraction -> 1f - (1f - fraction) * (1f - fraction) }

/**
 * True when the system animator duration scale is zero (the Android
 * reduce-motion analog): all chat motion collapses to instant state changes.
 */
@Composable
fun rememberReduceMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        ) == 0f
    }
}

/**
 * Wraps the actively streaming body and fades its trailing region in whenever
 * [text] grows. When [isStreaming] is false (or the content was wholesale
 * replaced/shrunk, or reduce motion is on) the body renders solid.
 */
@Composable
fun StreamingTextFade(
    text: String,
    isStreaming: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val reduceMotion = rememberReduceMotion()
    val reveal = remember { Animatable(1f) }
    var lastLength by remember { mutableIntStateOf(text.length) }

    LaunchedEffect(text, isStreaming, reduceMotion) {
        if (!isStreaming || reduceMotion) {
            lastLength = text.length
            reveal.snapTo(1f)
            return@LaunchedEffect
        }
        val previous = lastLength
        lastLength = text.length
        if (text.length > previous) {
            // Content grew: restart the trailing-region fade. Restarting from
            // the floor matches the iOS behavior of the newest glyphs always
            // beginning invisible in their final layout position.
            reveal.snapTo(StreamingTextFadeDefaults.FLOOR_OPACITY)
            reveal.animateTo(
                targetValue = 1f,
                animationSpec = tween(
                    durationMillis = StreamingTextFadeDefaults.FADE_DURATION_MILLIS,
                    easing = QuadraticEaseOut,
                ),
            )
        } else if (text.length < previous) {
            // Wholesale replacement — never fade pre-existing text.
            reveal.snapTo(1f)
        }
    }

    if (!isStreaming || reduceMotion) {
        Box(modifier) { content() }
        return
    }

    Box(
        modifier
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                val alpha = reveal.value
                if (alpha < 1f && size.height > 0f) {
                    val band = min(
                        size.height,
                        StreamingTextFadeDefaults.MAX_FADE_BAND_HEIGHT.toPx(),
                    )
                    val bandTop = size.height - band
                    // Modulate the destination alpha over the trailing band:
                    // solid at the band's top edge, animating up from the
                    // floor at the streaming edge, so the newest text bleeds
                    // in as a moving gradient rather than popping per flush.
                    drawRect(
                        brush = Brush.verticalGradient(
                            colors = listOf(Color.White, Color.White.copy(alpha = alpha)),
                            startY = bandTop,
                            endY = size.height,
                        ),
                        topLeft = Offset(0f, bandTop),
                        size = Size(size.width, band),
                        blendMode = BlendMode.DstIn,
                    )
                }
            },
    ) {
        content()
    }
}
