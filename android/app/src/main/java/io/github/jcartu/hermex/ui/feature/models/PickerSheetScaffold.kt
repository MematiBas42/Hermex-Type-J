package io.github.jcartu.hermex.ui.feature.models

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.jcartu.hermex.ui.adaptive.LocalFoldState
import io.github.jcartu.hermex.ui.adaptive.LocalPaneCount

/**
 * Adaptive host for the picker surfaces (iOS `.adaptiveFormPresentation()`):
 *
 * - `LocalPaneCount == 1` (cover screen / phone): a full-height [ModalBottomSheet].
 * - `LocalPaneCount >= 2` (half-fold, tablet, trifold): a centered card dialog
 *   capped at 480dp content width. When vertical hinges cross the window the
 *   card is centered inside the widest hinge-free segment so no interactive
 *   control ever straddles an occlusion strip (AGENTS.md trifold rules).
 *
 * The `content` lambda renders inside a column with the title header already
 * applied; it owns its own scrolling (LazyColumn) and IME insets are handled
 * here via [imePadding].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PickerSheetScaffold(
    title: String,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    if (LocalPaneCount.current == 1) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = sheetState,
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.94f)
                    .imePadding(),
            ) {
                PickerHeader(title = title, onDismiss = onDismiss)
                content()
            }
        }
    } else {
        Dialog(
            onDismissRequest = onDismiss,
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            BoxWithConstraints(
                Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismiss,
                    ),
            ) {
                val density = LocalDensity.current
                val fold = LocalFoldState.current
                val windowWidthPx = constraints.maxWidth.toFloat()
                val (segmentStartPx, segmentWidthPx) = widestHingeFreeSegment(
                    windowWidthPx = windowWidthPx,
                    hingeCentersPx = fold.verticalHingeCenters(),
                )
                val segmentStart: Dp = with(density) { segmentStartPx.toDp() }
                val segmentWidth: Dp = with(density) { segmentWidthPx.toDp() }
                val maxCardHeight: Dp = with(density) { (constraints.maxHeight.toFloat() * 0.88f).toDp() }

                Box(
                    Modifier
                        .offset(x = segmentStart)
                        .width(segmentWidth)
                        .fillMaxHeight(),
                    contentAlignment = Alignment.Center,
                ) {
                    Surface(
                        modifier = Modifier
                            .padding(24.dp)
                            .widthIn(max = 480.dp)
                            .heightIn(max = minOf(640.dp, maxCardHeight))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = {},
                            ),
                        shape = MaterialTheme.shapes.extraLarge,
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        tonalElevation = 6.dp,
                    ) {
                        Column(Modifier.imePadding()) {
                            PickerHeader(title = title, onDismiss = onDismiss)
                            content()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PickerHeader(title: String, onDismiss: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onDismiss) { Text("Done") }
    }
}

/**
 * Splits the window at every vertical hinge center and returns
 * `(startPx, widthPx)` of the widest resulting segment. Pure geometry so it is
 * unit-testable; a hinge-free window returns the full width.
 */
internal fun widestHingeFreeSegment(
    windowWidthPx: Float,
    hingeCentersPx: List<Float>,
): Pair<Float, Float> {
    val centers = hingeCentersPx.filter { it > 0f && it < windowWidthPx }.sorted()
    if (centers.isEmpty() || windowWidthPx <= 0f) return 0f to windowWidthPx

    val edges = listOf(0f) + centers + listOf(windowWidthPx)
    var bestStart = 0f
    var bestWidth = 0f
    for (index in 0 until edges.size - 1) {
        val width = edges[index + 1] - edges[index]
        if (width > bestWidth) {
            bestWidth = width
            bestStart = edges[index]
        }
    }
    return bestStart to bestWidth
}
