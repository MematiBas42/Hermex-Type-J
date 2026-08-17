package io.github.jcartu.hermex.ui.adaptive

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.abs

class HingeGeometryTest {

    private fun approx(expected: List<Float>, actual: List<Float>) {
        assertEquals(expected.size, actual.size)
        expected.zip(actual).forEach { (e, a) ->
            assertEquals("pane width", e, a, 0.5f)
        }
    }

    @Test
    fun `single pane gets full width regardless of hinges`() {
        val widths = computePaneWidths(0f, 2100f, listOf(700f, 1400f), 1)
        assertEquals(listOf(2100f), widths)
    }

    @Test
    fun `three panes snap to two trifold hinges`() {
        // Unfolded trifold: hinges at 700 and 1400 in window space, row starts at 0.
        val widths = computePaneWidths(0f, 2100f, listOf(700f, 1400f), 3)
        approx(listOf(700f, 700f, 700f), widths)
    }

    @Test
    fun `row offset by a navigation rail still snaps correctly`() {
        // Rail consumes 240px; row starts at x=240 and is 1860px wide.
        // Window hinges at 700/1400 → row-relative 460/1160.
        val widths = computePaneWidths(240f, 1860f, listOf(700f, 1400f), 3)
        approx(listOf(460f, 700f, 700f), widths)
    }

    @Test
    fun `no hinges yields equal widths`() {
        val widths = computePaneWidths(0f, 1800f, emptyList(), 3)
        approx(listOf(600f, 600f, 600f), widths)
    }

    @Test
    fun `two panes use one hinge and split the remainder`() {
        val widths = computePaneWidths(0f, 2000f, listOf(960f), 2)
        approx(listOf(960f, 1040f), widths)
    }

    @Test
    fun `hinge outside the row is ignored`() {
        val widths = computePaneWidths(1000f, 1000f, listOf(400f, 2400f), 2)
        approx(listOf(500f, 500f), widths)
    }

    @Test
    fun `far-from-equal hinge is not used`() {
        // A hinge 10px from the row edge would make a degenerate pane; equal split wins.
        val widths = computePaneWidths(0f, 1000f, listOf(990f), 2)
        approx(listOf(500f, 500f), widths)
    }
}
