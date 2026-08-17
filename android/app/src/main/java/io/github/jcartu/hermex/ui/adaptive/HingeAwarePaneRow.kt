package io.github.jcartu.hermex.ui.adaptive

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp

/**
 * Splits [rowWidth] (px) into [paneCount] pane widths so that pane boundaries
 * land on the nearest physical hinge centers (given in window px, offset by
 * [rowStartXInWindow]). When there are more hinges than boundaries, the hinges
 * closest to evenly-divided boundaries win. Falls back to equal widths when no
 * hinges apply.
 *
 * Pure geometry — unit tested in HingeGeometryTest.
 */
fun computePaneWidths(
    rowStartXInWindow: Float,
    rowWidth: Float,
    hingeCentersInWindow: List<Float>,
    paneCount: Int,
): List<Float> {
    require(paneCount >= 1) { "paneCount must be >= 1" }
    if (paneCount == 1 || rowWidth <= 0f) return List(paneCount) { rowWidth / paneCount }


    val hinges = hingeCentersInWindow
        .map { it - rowStartXInWindow } // to row coordinates
        .filter { it in 0f..rowWidth }
        .sorted()

    if (hinges.isEmpty()) return List(paneCount) { rowWidth / paneCount }

    val boundariesWanted = paneCount - 1
    val equalBoundaries = (1 until paneCount).map { it * rowWidth / paneCount }
    val chosen = mutableListOf<Float>()
    val remaining = hinges.toMutableList()
    for (target in equalBoundaries) {
        val nearest = remaining.minByOrNull { kotlin.math.abs(it - target) }
        if (nearest != null && kotlin.math.abs(nearest - target) < rowWidth / (paneCount * 2)) {
            chosen += nearest
            remaining.remove(nearest)
        }
        if (chosen.size == boundariesWanted) break
    }
    // Partial snapping would produce degenerate panes; equal split is the safe fallback.
    if (chosen.size < boundariesWanted) return List(paneCount) { rowWidth / paneCount }

    val boundaries = chosen.sorted()
    val widths = mutableListOf<Float>()
    var start = 0f
    for (boundary in boundaries) {
        widths += boundary - start
        start = boundary
    }
    widths += rowWidth - start
    return widths
}

/**
 * A row of content panes whose boundaries align to the device's vertical
 * hinges (Samsung Galaxy Trifold target: sessions | transcript | composer).
 * Pane boundaries measured in window space via [onGloballyPositioned], so the
 * layout stays correct with rails or other chrome on the left.
 */
@Composable
fun HingeAwarePaneRow(
    paneCount: Int,
    modifier: Modifier = Modifier,
    pane: @Composable (index: Int, Modifier) -> Unit,
) {
    val fold = LocalFoldState.current
    var rowStartX by remember { mutableFloatStateOf(0f) }
    var rowWidth by remember { mutableFloatStateOf(0f) }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .onGloballyPositioned { coordinates ->
                rowStartX = coordinates.positionInWindow().x
                rowWidth = coordinates.size.width.toFloat()
            },
    ) {
        val widths = computePaneWidths(rowStartX, rowWidth, fold.verticalHingeCenters(), paneCount)
        Box(Modifier.fillMaxSize()) {
            var xOffset = 0f
            widths.forEachIndexed { index, widthPx ->
                val start = xOffset
                val paneModifier = Modifier
                    .fillMaxHeight()
                    .width(with(LocalDensity.current) { widthPx.toDp() })
                    .then(Modifier.absoluteOffset(x = with(LocalDensity.current) { start.toDp() }))
                pane(index, paneModifier)
                xOffset += widthPx
            }
        }
    }
}

/**
 * Two-region split for the chats area: the first region (session list) gets
 * exactly the first pane width from [computePaneWidths]; the remaining region
 * (chat, which splits itself further at the next hinge) fills the rest. On the
 * trifold this puts the list|chat boundary on the first hinge and leaves
 * ChatPane to align transcript|composer on the second.
 */
@Composable
fun FirstPaneSplitRow(
    modifier: Modifier = Modifier,
    firstPane: @Composable (Int, Modifier) -> Unit,
    remaining: @Composable (Modifier) -> Unit,
) {
    val fold = LocalFoldState.current
    val paneCount = maxOf(LocalPaneCount.current, 2)
    var rowStartX by remember { mutableFloatStateOf(0f) }
    var rowWidth by remember { mutableFloatStateOf(0f) }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .onGloballyPositioned { coordinates ->
                rowStartX = coordinates.positionInWindow().x
                rowWidth = coordinates.size.width.toFloat()
            },
    ) {
        val firstWidth = computePaneWidths(rowStartX, rowWidth, fold.verticalHingeCenters(), paneCount).first()
        val density = LocalDensity.current
        Box(Modifier.fillMaxSize()) {
            firstPane(
                paneCount,
                Modifier
                    .fillMaxHeight()
                    .width(with(density) { firstWidth.toDp() }),
            )
            remaining(
                Modifier
                    .fillMaxHeight()
                    .absoluteOffset(x = with(density) { firstWidth.toDp() })
                    .width(with(density) { (rowWidth - firstWidth).toDp() }),
            )
        }
    }
}
