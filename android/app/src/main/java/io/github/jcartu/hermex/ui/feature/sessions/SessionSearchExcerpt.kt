package io.github.jcartu.hermex.ui.feature.sessions

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle

/**
 * One content-search excerpt to show under a session row, paired with the
 * query that produced it so the row can bold the matched ranges.
 *
 * Port of `Features/SessionList/SessionSearchExcerpt.swift` (webui path —
 * 1.6.0 "search results show why they matched"). `text` is the server's
 * `match_preview`, normalized by [displayText]: a redacted,
 * whitespace-collapsed window of the matched message, already elided with
 * `...` on either side when it was cut. Servers older than the upstream commit
 * that added `_session_search_preview` omit the field, so no excerpt is built
 * at all.
 */
data class SessionSearchExcerpt(
    val text: String,
    val query: String,
    private val marked: AnnotatedString? = null,
) {
    /**
     * The excerpt with every occurrence of the query bolded.
     *
     * Port of `SessionSearchExcerpt.highlighted`: matching is
     * case-insensitive and the bolded range is the text's own, not the
     * query's length. Diacritics are *not* folded: the server matched the
     * query literally, so folding them here would bold a span the server
     * never treated as the hit. When redaction has replaced the match,
     * nothing is bolded and the plain excerpt still shows.
     */
    fun highlighted(): AnnotatedString {
        marked?.let { return it }
        if (query.isEmpty()) return AnnotatedString(text)

        return buildAnnotatedString {
            var cursor = 0
            while (cursor < text.length) {
                val match = text.indexOf(query, startIndex = cursor, ignoreCase = true)
                if (match < 0) break
                if (match > cursor) append(text.substring(cursor, match))
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                    append(text.substring(match, match + query.length))
                }
                cursor = match + query.length
            }
            if (cursor < text.length) append(text.substring(cursor))
        }
    }

    companion object {
        /** Port of SessionSearchExcerpt(hermesSnippet:query:): strip every FTS marker, bold only marked spans. */
        fun hermesSnippet(snippet: String, query: String): SessionSearchExcerpt {
            val input = displayText(snippet)
            var cursor = 0
            var inMatch = false
            var hasMarks = false
            val marked = buildAnnotatedString {
                while (cursor < input.length) {
                    val open = input.indexOf(">>>", cursor)
                    val close = input.indexOf("<<<", cursor)
                    val next = listOf(open, close).filter { it >= 0 }.minOrNull() ?: input.length
                    val piece = input.substring(cursor, next)
                    if (inMatch) withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(piece) } else append(piece)
                    if (next == input.length) break
                    inMatch = next == open
                    hasMarks = true
                    cursor = next + 3
                }
            }
            return SessionSearchExcerpt(marked.text, query, marked.takeIf { hasMarks })
        }

        /**
         * Port of `SessionSearchExcerpt.displayText(_:)`.
         *
         * The server previews the raw stored message text, so an excerpt taken
         * from a serialized tool result arrives carrying that JSON's escapes:
         * `...0% /\n---\nRAM: 8.0 GB", "exit_code": 0,...`. Those two-character
         * escapes become spaces and runs of whitespace collapse, so the one line
         * reads instead of showing its own punctuation.
         *
         * A Windows path (`C:\new`) is the false positive this accepts. It is
         * worth it for a truncated preview line that is never used as data.
         */
        fun displayText(raw: String): String {
            var text = raw
            for (escape in listOf("\\r\\n", "\\n", "\\r", "\\t")) {
                text = text.replace(escape, " ")
            }
            return text.split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
        }

        /** The row-facing constructor: normalizes the server's preview like Swift's `init(text:query:)`. */
        fun of(text: String, query: String): SessionSearchExcerpt =
            SessionSearchExcerpt(text = displayText(text), query = query)
    }
}
