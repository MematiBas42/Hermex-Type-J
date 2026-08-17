package io.github.jcartu.hermex.ui.adaptive

import android.app.Activity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import androidx.window.layout.WindowLayoutInfo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * Fold/posture state for the current window, gathered through androidx.window.
 * Drives the trifold-aware layouts: pane boundaries snap to physical hinges,
 * and the half-opened posture gets a "tent" split.
 */
data class FoldState(
    val features: List<FoldingFeature> = emptyList(),
) {
    val hasFold: Boolean get() = features.isNotEmpty()

    /** True when any hinge is half-opened (book/tent posture). */
    val isHalfOpened: Boolean get() = features.any { it.state == FoldingFeature.State.HALF_OPENED }

    /** True when folded flat with hinges present (fully unfolded trifold). */
    val isFlatWithHinges: Boolean get() = features.isNotEmpty() && features.all { it.state == FoldingFeature.State.FLAT }

    fun verticalHingeCount(): Int = features.count { it.orientation == FoldingFeature.Orientation.VERTICAL }

    /** X positions (px) of vertical hinge centers in window coordinates. */
    fun verticalHingeCenters(): List<Float> =
        features
            .filter { it.orientation == FoldingFeature.Orientation.VERTICAL }
            .map { it.bounds.left + it.bounds.width() / 2f }
            .sorted()

    /** X positions (fraction of window width, 0..1) of vertical hinge lines. */
    fun verticalHingeFractions(windowWidthPx: Float): List<Float> =
        if (windowWidthPx <= 0f) {
            emptyList()
        } else {
            verticalHingeCenters().map { it / windowWidthPx }
        }

    /** Hinge occlusion bounds (physical hinge strips) for spacing content away from them. */
    fun occlusionRects(): List<Rect> =
        features.map {
            Rect(
                it.bounds.left.toFloat(),
                it.bounds.top.toFloat(),
                it.bounds.right.toFloat(),
                it.bounds.bottom.toFloat(),
            )
        }
}

@Composable
fun rememberFoldState(): State<FoldState> {
    val activity = LocalContext.current as? Activity
    val layoutInfoFlow: Flow<WindowLayoutInfo> = remember(activity) {
        if (activity != null) {
            WindowInfoTracker.getOrCreate(activity).windowLayoutInfo(activity)
        } else {
            flowOf(WindowLayoutInfo(emptyList()))
        }
    }
    return remember(layoutInfoFlow) {
        layoutInfoFlow.mapToFoldState()
    }.collectAsStateWithLifecycle(initialValue = FoldState())
}
private fun Flow<WindowLayoutInfo>.mapToFoldState(): Flow<FoldState> =
    this.map { info ->
        FoldState(features = info.displayFeatures.filterIsInstance<FoldingFeature>())
    }

/** Window width in dp — drives compact/medium/expanded decisions alongside WindowSizeClass. */
@Composable
fun rememberWindowWidthDp(): Int = LocalConfiguration.current.screenWidthDp

/**
 * Heuristic pane count for the trifold: 1 panel (cover screen), 2 (half-fold / medium
 * width), 3 (fully unfolded with two hinges, or very wide windows).
 */
fun FoldState.trifoldPaneCount(windowWidthDp: Int): Int = when {
    verticalHingeCount() >= 2 -> 3
    hasFold -> 2
    windowWidthDp >= 1200 -> 3
    windowWidthDp >= 600 -> 2
    else -> 1
}

/** Fold state of the current window; provided by the app shell. */
val LocalFoldState = staticCompositionLocalOf { FoldState() }

/** Number of usable content panes (1 cover, 2 half-fold, 3 unfolded trifold). */
val LocalPaneCount = staticCompositionLocalOf { 1 }
