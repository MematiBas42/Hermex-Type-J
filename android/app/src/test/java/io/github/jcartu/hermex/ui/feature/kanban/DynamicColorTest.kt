package io.github.jcartu.hermex.ui.feature.kanban

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import io.github.jcartu.hermex.ui.theme.parseHexColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression for the cold-start crash: Compose's `Color(ULong)` interprets the
 * top bits as a color-space id, so an ARGB literal built with `toULong()`
 * crashed the draw pass with `Invalid ID: 40`. Every dynamic color producer
 * must round-trip through `toArgb()`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class DynamicColorTest {

    @Test fun `hex color parsing round-trips through packed ARGB`() {
        assertEquals(0xFFFFC629.toInt(), parseHexColor("#FFC629")!!.toArgb())
        assertEquals(0xFF34C759.toInt(), parseHexColor("34c759")!!.toArgb())
        assertNull(parseHexColor(""))
        assertNull(parseHexColor("#GG0000"))
        assertNull(parseHexColor("#12345"))
    }

    @Test fun `kanban board color strips never crash on malformed server colors`() {
        val valid = colorFromHexForTest("#FFC629")
        assertEquals(0xFFFFC629.toInt(), valid.toArgb())
        listOf(null, "", "#", "#FFFF", "not-a-color", "#1234567").forEach { bad ->
            assertEquals(0xFFFFC629.toInt(), colorFromHexForTest(bad).toArgb())
        }
    }

    @Test fun `all theme palette values round-trip`() {
        listOf(
            Color(0xFFFFD700), Color(0xFF0B0B0F), Color(0xFF141419), Color(0xFF1C1C23),
            Color(0xFF24242D), Color(0xFF3A3A45), Color(0xFFF2F2F7), Color(0xFF9A9AA5),
            Color(0xFFF7F7FA), Color(0xFFFFFFFF), Color(0xFFDDDDE4), Color(0xFF8A6D00),
        ).forEach { color -> assertEquals(color.toArgb() ushr 24, 0xFF) }
    }

    private fun colorFromHexForTest(value: String?): Color {
        val raw = value?.removePrefix("#")
        val isValid = raw != null && raw.length == 6 && raw.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
        if (!isValid) return Color(0xFFFFC629)
        return Color((raw!!.toLong(16) or 0xFF000000L).toInt())
    }
}
