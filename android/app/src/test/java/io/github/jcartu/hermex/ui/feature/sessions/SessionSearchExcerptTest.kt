package io.github.jcartu.hermex.ui.feature.sessions

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `SessionSearchExcerpt` (1.6.0 "search results show why they matched"):
 * preview normalization and the case-insensitive query bolding of
 * `SessionSearchExcerpt.swift`.
 */
class SessionSearchExcerptTest {

    @Test
    fun `displayText turns JSON escapes into spaces and collapses whitespace`() {
        // The server previews the raw stored message text: an excerpt from a
        // serialized tool result arrives carrying that JSON's escapes.
        val raw = "…0% /\\n---\\nRAM: 8.0 GB\", \"exit_code\": 0,\\t done\\r\\n end"
        assertEquals(
            "…0% / --- RAM: 8.0 GB\", \"exit_code\": 0, done end",
            SessionSearchExcerpt.displayText(raw),
        )
    }

    @Test
    fun `highlighted bolds every case-insensitive query occurrence`() {
        val excerpt = SessionSearchExcerpt.of(
            text = "Deploy TOKYO nightly, then verify tokyo dashboards",
            query = "tokyo",
        )
        val highlighted = excerpt.highlighted()
        assertEquals("Deploy TOKYO nightly, then verify tokyo dashboards", highlighted.text)

        val boldRanges = highlighted.spanStyles
            .filter { it.item.fontWeight == androidx.compose.ui.text.font.FontWeight.Bold }
            .map { highlighted.text.substring(it.start, it.end) }
        assertEquals(listOf("TOKYO", "tokyo"), boldRanges)
    }

    @Test
    fun `highlighted with empty query or absent match returns the plain excerpt`() {
        val excerpt = SessionSearchExcerpt.of(text = "plain row", query = "")
        assertEquals(0, excerpt.highlighted().spanStyles.size)

        val noMatch = SessionSearchExcerpt.of(text = "plain row", query = "zenith")
        val highlighted = noMatch.highlighted()
        assertEquals("plain row", highlighted.text)
        assertEquals(0, highlighted.spanStyles.size)
    }

    @Test
    fun `the bolded range is the texts own, not the querys length`() {
        // Case-insensitive match keeps the excerpt's casing and span length.
        val excerpt = SessionSearchExcerpt.of(text = "see Hermes-Agent logs", query = "hermes-agent")
        val highlighted = excerpt.highlighted()
        val bold = highlighted.spanStyles.single()
        assertEquals("Hermes-Agent", highlighted.text.substring(bold.start, bold.end))
    }
}
