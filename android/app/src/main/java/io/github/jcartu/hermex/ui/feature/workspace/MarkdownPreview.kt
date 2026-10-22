package io.github.jcartu.hermex.ui.feature.workspace

/** One safely split slice of a large workspace Markdown document. */
data class MarkdownPreviewChunk(
    val id: Int,
    val text: String,
    val topSpacingDp: Int,
)

/**
 * Android port of `MarkdownPreviewChunker` in `FilePreviewViewModel.swift`.
 * Small files remain one renderer; large files split at blank-line/headline
 * boundaries outside fenced code so a LazyColumn composes only nearby blocks.
 */
object MarkdownPreviewChunker {
    const val TARGET_UTF8_BYTES = 16 * 1024

    fun chunks(content: String): List<MarkdownPreviewChunk>? {
        if (content.toByteArray(Charsets.UTF_8).size <= TARGET_UTF8_BYTES) return null
        val chunks = ArrayList<String>()
        val current = StringBuilder()
        var currentBytes = 0
        var fence: String? = null

        val lines = content.split('\n')
        lines.forEachIndexed { index, line ->
            val lineWithBreak = if (index == lines.lastIndex) line else "$line\n"
            current.append(lineWithBreak)
            currentBytes += lineWithBreak.toByteArray(Charsets.UTF_8).size

            val trimmed = line.trimStart()
            val delimiter = when {
                trimmed.startsWith("```") -> "```"
                trimmed.startsWith("~~~") -> "~~~"
                else -> null
            }
            if (delimiter != null) fence = if (fence == delimiter) null else if (fence == null) delimiter else fence

            val safeBoundary = fence == null && (line.isBlank() || trimmed.startsWith('#'))
            if (currentBytes >= TARGET_UTF8_BYTES && safeBoundary) {
                chunks += current.toString().trimEnd('\n')
                current.clear()
                currentBytes = 0
            }
        }
        if (current.isNotEmpty()) chunks += current.toString().trimEnd('\n')
        if (chunks.size <= 1) return null
        return chunks.mapIndexed { index, text ->
            MarkdownPreviewChunk(index, text, if (index == 0) 0 else seamSpacing(chunks[index - 1], text))
        }
    }

    /** Mirrors the upstream heading/rule/fence seam margins. */
    internal fun seamSpacing(previous: String, next: String): Int {
        val previousLine = previous.lineSequence().map(String::trim).lastOrNull(String::isNotEmpty).orEmpty()
        val nextLine = next.lineSequence().map(String::trim).firstOrNull(String::isNotEmpty).orEmpty()
        val bottom = when {
            previousLine.startsWith("```") || previousLine.startsWith("~~~") -> 12
            isThematicBreak(previousLine) -> 24
            else -> 16
        }
        val top = when {
            nextLine.startsWith('#') || isThematicBreak(nextLine) -> 24
            nextLine.startsWith("```") || nextLine.startsWith("~~~") -> 4
            else -> 0
        }
        return maxOf(bottom, top)
    }

    private fun isThematicBreak(line: String): Boolean {
        val marks = line.filterNot(Char::isWhitespace)
        val mark = marks.firstOrNull() ?: return false
        return marks.length >= 3 && mark in "-*_" && marks.all { it == mark }
    }
}

/** Extracts local Markdown image destinations without treating ordinary links as files. */
object MarkdownImageParser {
    fun destinations(markdown: String): List<String> {
        val result = LinkedHashSet<String>()
        var index = 0
        while (index < markdown.length) {
            val marker = markdown.indexOf("![", index)
            if (marker < 0) break
            val labelEnd = markdown.indexOf("](", marker + 2)
            if (labelEnd < 0) break
            var cursor = labelEnd + 2
            var depth = 1
            var escaped = false
            while (cursor < markdown.length && depth > 0) {
                val character = markdown[cursor]
                if (!escaped) {
                    if (character == '(') depth++
                    if (character == ')') depth--
                }
                escaped = !escaped && character == '\\'
                if (character != '\\') escaped = false
                cursor++
            }
            if (depth == 0) {
                val raw = markdown.substring(labelEnd + 2, cursor - 1).trim()
                val title = Regex("\\s+[\"']").find(raw)
                val destination = raw.substring(0, title?.range?.first ?: raw.length)
                    .trim()
                    .removeSurrounding("<", ">")
                if (destination.isNotEmpty()) result += destination
            }
            index = maxOf(cursor, labelEnd + 2)
        }
        return result.toList()
    }
}
