package io.github.jcartu.hermex.ui.feature.chat

/**
 * Prepares chat markdown for the platform renderer by converting recognized
 * math into ordinary markdown code spans. LaTeX renders as source text in v1.
 * Inline math becomes inline code and display math becomes a fenced `latex`
 * block; markdown outside those spans is copied byte-for-byte.
 */
object MarkdownMathFormatter {
    fun format(markdown: String): String {
        val segments = MarkdownMathSegmenter.segments(markdown)
        if (!segments.containsMath) return markdown

        return buildString(markdown.length + 32) {
            var previousWasDisplayMath = false
            for (segment in segments) {
                when (segment) {
                    is MarkdownMathSegment.Markdown -> {
                        if (previousWasDisplayMath && segment.content.isNotEmpty() && segment.content[0] != '\n') {
                            append("\n\n")
                        }
                        append(segment.content)
                        previousWasDisplayMath = false
                    }

                    is MarkdownMathSegment.InlineMath -> {
                        if (previousWasDisplayMath) append("\n\n")
                        append(inlineCode(renderedText(segment.latex)))
                        previousWasDisplayMath = false
                    }

                    is MarkdownMathSegment.DisplayMath -> {
                        if (isNotEmpty()) {
                            when (trailingNewlineCount()) {
                                0 -> append("\n\n")
                                1 -> append('\n')
                            }
                        }
                        append(fencedCode(renderedText(segment.latex)))
                        previousWasDisplayMath = true
                    }
                }
            }
        }
    }

    /** Returns the v1 terminal representation without interpreting LaTeX commands. */
    fun renderedText(latex: String): String = latex.trim()

    private fun inlineCode(latex: String): String {
        val delimiter = "`".repeat(maxOf(1, longestRun(latex, '`') + 1))
        val needsPadding = latex.startsWith('`') || latex.endsWith('`') ||
            latex.startsWith(' ') || latex.endsWith(' ')
        return if (needsPadding) "$delimiter $latex $delimiter" else "$delimiter$latex$delimiter"
    }

    private fun fencedCode(latex: String): String {
        val delimiter = "`".repeat(maxOf(3, longestRun(latex, '`') + 1))
        return "${delimiter}latex\n$latex\n$delimiter"
    }

    private fun longestRun(value: String, character: Char): Int {
        var longest = 0
        var current = 0
        for (item in value) {
            if (item == character) {
                current += 1
                longest = maxOf(longest, current)
            } else {
                current = 0
            }
        }
        return longest
    }

    private fun StringBuilder.trailingNewlineCount(): Int {
        var count = 0
        var index = length - 1
        while (index >= 0 && this[index] == '\n') {
            count += 1
            index -= 1
        }
        return count
    }
}
