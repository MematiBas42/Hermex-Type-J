package io.github.jcartu.hermex.ui.feature.chat

/** A protected markdown span or a LaTeX span recognized in chat content. */
sealed interface MarkdownMathSegment {
    data class Markdown(val content: String) : MarkdownMathSegment
    data class InlineMath(val latex: String) : MarkdownMathSegment
    data class DisplayMath(val latex: String) : MarkdownMathSegment
}

/**
 * Splits markdown around inline and display LaTeX while leaving code spans and
 * fenced code blocks opaque. Delimiter behavior mirrors the iOS segmenter.
 */
object MarkdownMathSegmenter {
    fun segments(content: String): List<MarkdownMathSegment> {
        if (content.isEmpty()) return listOf(MarkdownMathSegment.Markdown(content))

        val protected = MarkdownMathProtection.mask(content)
        val result = mutableListOf<MarkdownMathSegment>()
        var cursor = 0
        var index = 0

        while (index < content.length) {
            val display = displayOpening(content, index, protected)
            if (display != null) {
                val closing = closingDisplayDelimiter(content, index + display.openLength, protected, display)
                if (closing != null) {
                    appendMarkdown(content.substring(cursor, index), result)
                    val latex = if (display.includesDelimiters) {
                        content.substring(index, closing.index + closing.length)
                    } else {
                        content.substring(index + display.openLength, closing.index)
                    }.trim()
                    if (latex.isNotEmpty()) result += MarkdownMathSegment.DisplayMath(latex)
                    index = closing.index + closing.length
                    cursor = index
                    continue
                }
            }

            val inline = inlineOpening(content, index, protected)
            if (inline != null) {
                val closingIndex = closingInlineDelimiter(
                    content = content,
                    startIndex = index + inline.openToken.length,
                    protected = protected,
                    delimiter = inline,
                )
                if (closingIndex != null) {
                    val latex = content.substring(index + inline.openToken.length, closingIndex)
                    if (looksLikeMath(latex)) {
                        appendMarkdown(content.substring(cursor, index), result)
                        result += MarkdownMathSegment.InlineMath(latex.trim())
                        index = closingIndex + inline.closeToken.length
                        cursor = index
                        continue
                    }
                }
            }

            index += 1
        }

        appendMarkdown(content.substring(cursor), result)
        return result.ifEmpty { listOf(MarkdownMathSegment.Markdown(content)) }
    }

    private fun appendMarkdown(value: String, result: MutableList<MarkdownMathSegment>) {
        if (value.isEmpty()) return
        val previous = result.lastOrNull()
        if (previous is MarkdownMathSegment.Markdown) {
            result[result.lastIndex] = MarkdownMathSegment.Markdown(previous.content + value)
        } else {
            result += MarkdownMathSegment.Markdown(value)
        }
    }

    private fun closingInlineDelimiter(
        content: String,
        startIndex: Int,
        protected: BooleanArray,
        delimiter: InlineDelimiter,
    ): Int? {
        var index = startIndex
        while (index < content.length) {
            if (content[index] == '\n') return null
            if (delimiter.isClose(content, index, protected)) return index
            index += 1
        }
        return null
    }

    private fun inlineOpening(
        content: String,
        index: Int,
        protected: BooleanArray,
    ): InlineDelimiter? = InlineDelimiter.entries.firstOrNull { it.isOpen(content, index, protected) }

    private fun looksLikeMath(value: String): Boolean {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return false
        if (SINGLE_MATH_TOKEN.matches(trimmed)) return true
        if ('\\' in trimmed || '^' in trimmed || '_' in trimmed) return true
        return BINARY_MATH_EXPRESSION.containsMatchIn(trimmed)
    }

    private fun displayOpening(
        content: String,
        index: Int,
        protected: BooleanArray,
    ): DisplayOpening? {
        if (matches("$$", content, index, protected) && !MarkdownMathProtection.isEscaped(content, index)) {
            return DisplayOpening.Token(openToken = "$$", closeToken = "$$")
        }
        if (matches("\\[", content, index, protected) && !MarkdownMathProtection.isEscaped(content, index)) {
            return DisplayOpening.Token(openToken = "\\[", closeToken = "\\]")
        }
        return environmentOpening(content, index, protected)
    }

    private fun environmentOpening(
        content: String,
        index: Int,
        protected: BooleanArray,
    ): DisplayOpening.Environment? {
        val prefix = "\\begin{"
        if (!matches(prefix, content, index, protected) || MarkdownMathProtection.isEscaped(content, index)) {
            return null
        }

        val nameEnd = content.indexOf('}', startIndex = index + prefix.length)
        if (nameEnd < 0 || !rangeIsUnprotected(index, nameEnd + 1, protected)) return null
        val name = content.substring(index + prefix.length, nameEnd)
        if (name.isEmpty() || !ENVIRONMENT_NAME.matches(name)) return null
        return DisplayOpening.Environment(name = name, openLength = nameEnd + 1 - index)
    }

    private fun closingDisplayDelimiter(
        content: String,
        startIndex: Int,
        protected: BooleanArray,
        delimiter: DisplayOpening,
    ): ClosingDelimiter? {
        return when (delimiter) {
            is DisplayOpening.Token -> {
                var index = startIndex
                while (index < content.length) {
                    if (
                        matches(delimiter.closeToken, content, index, protected) &&
                        !MarkdownMathProtection.isEscaped(content, index)
                    ) {
                        return ClosingDelimiter(index, delimiter.closeToken.length)
                    }
                    index += 1
                }
                null
            }

            is DisplayOpening.Environment -> {
                val openToken = "\\begin{${delimiter.name}}"
                val closeToken = "\\end{${delimiter.name}}"
                var depth = 1
                var index = startIndex
                while (index < content.length) {
                    if (
                        matches(openToken, content, index, protected) &&
                        !MarkdownMathProtection.isEscaped(content, index)
                    ) {
                        depth += 1
                        index += openToken.length
                        continue
                    }
                    if (
                        matches(closeToken, content, index, protected) &&
                        !MarkdownMathProtection.isEscaped(content, index)
                    ) {
                        depth -= 1
                        if (depth == 0) return ClosingDelimiter(index, closeToken.length)
                        index += closeToken.length
                        continue
                    }
                    index += 1
                }
                null
            }
        }
    }

    private fun matches(
        token: String,
        content: String,
        index: Int,
        protected: BooleanArray,
    ): Boolean {
        if (index < 0 || index + token.length > content.length) return false
        if (!rangeIsUnprotected(index, index + token.length, protected)) return false
        return content.regionMatches(index, token, 0, token.length)
    }

    private fun rangeIsUnprotected(start: Int, endExclusive: Int, protected: BooleanArray): Boolean {
        for (index in start until endExclusive) {
            if (protected[index]) return false
        }
        return true
    }

    private enum class InlineDelimiter(val openToken: String, val closeToken: String) {
        DOLLAR("$", "$"),
        PARENS("\\(", "\\)"),
        ;

        fun isOpen(content: String, index: Int, protected: BooleanArray): Boolean {
            if (!matchesToken(openToken, content, index, protected)) return false
            if (MarkdownMathProtection.isEscaped(content, index)) return false
            if (this == DOLLAR) {
                if (index > 0 && content[index - 1] == '$') return false
                if (index + 1 < content.length && content[index + 1] == '$') return false
            }
            return true
        }

        fun isClose(content: String, index: Int, protected: BooleanArray): Boolean {
            if (!matchesToken(closeToken, content, index, protected)) return false
            if (MarkdownMathProtection.isEscaped(content, index)) return false
            if (this == DOLLAR) {
                if (index > 0 && content[index - 1] == '$') return false
                if (index + 1 < content.length && content[index + 1] == '$') return false
            }
            return true
        }

        private fun matchesToken(
            token: String,
            content: String,
            index: Int,
            protected: BooleanArray,
        ): Boolean {
            if (index < 0 || index + token.length > content.length) return false
            for (offset in token.indices) {
                if (protected[index + offset]) return false
            }
            return content.regionMatches(index, token, 0, token.length)
        }
    }

    private sealed interface DisplayOpening {
        val openLength: Int
        val includesDelimiters: Boolean

        data class Token(val openToken: String, val closeToken: String) : DisplayOpening {
            override val openLength: Int = openToken.length
            override val includesDelimiters: Boolean = false
        }

        data class Environment(val name: String, override val openLength: Int) : DisplayOpening {
            override val includesDelimiters: Boolean = true
        }
    }

    private data class ClosingDelimiter(val index: Int, val length: Int)

    private val SINGLE_MATH_TOKEN = Regex("^[A-Za-z](?:_[A-Za-z0-9]+|\\^[A-Za-z0-9]+)?$")
    private val BINARY_MATH_EXPRESSION = Regex("[A-Za-z0-9]\\s*[=<>+\\-*/|]\\s*[A-Za-z0-9]")
    private val ENVIRONMENT_NAME = Regex("[A-Za-z]+\\*?")
}

internal object MarkdownMathProtection {
    fun mask(content: String): BooleanArray {
        val fenced = fencedCodeMask(content)
        val inline = inlineCodeMask(content, fenced)
        return BooleanArray(content.length) { fenced[it] || inline[it] }
    }

    fun isEscaped(content: String, index: Int): Boolean {
        if (index <= 0) return false
        var backslashCount = 0
        var cursor = index - 1
        while (cursor >= 0 && content[cursor] == '\\') {
            backslashCount += 1
            cursor -= 1
        }
        return backslashCount % 2 == 1
    }

    private fun fencedCodeMask(content: String): BooleanArray {
        val mask = BooleanArray(content.length)
        var lineStart = 0
        var fenceStart: Int? = null
        var fenceCharacter: Char? = null

        while (lineStart < content.length) {
            val lineEnd = nextLineEnd(content, lineStart)
            val nextLineStart = if (lineEnd < content.length) lineEnd + 1 else lineEnd
            val opening = fenceOpening(content, lineStart, lineEnd)
            if (opening != null) {
                if (fenceStart != null && opening == fenceCharacter) {
                    mark(mask, fenceStart until nextLineStart)
                    fenceStart = null
                    fenceCharacter = null
                } else if (fenceStart == null) {
                    fenceStart = lineStart
                    fenceCharacter = opening
                }
            }
            lineStart = nextLineStart
        }

        fenceStart?.let { mark(mask, it until content.length) }
        return mask
    }

    private fun inlineCodeMask(content: String, existingMask: BooleanArray): BooleanArray {
        val mask = BooleanArray(content.length)
        var index = 0
        while (index < content.length) {
            if (content[index] != '`' || existingMask[index]) {
                index += 1
                continue
            }

            val runLength = backtickRunLength(content, index)
            val closeIndex = closingBacktickRun(content, index + runLength, runLength, existingMask)
            if (closeIndex != null) {
                mark(mask, index until closeIndex + runLength)
                index = closeIndex + runLength
            } else {
                index += runLength
            }
        }
        return mask
    }

    private fun fenceOpening(content: String, lineStart: Int, lineEnd: Int): Char? {
        var cursor = lineStart
        while (cursor < lineEnd && content[cursor].isWhitespace()) cursor += 1
        if (cursor + 2 >= lineEnd) return null
        val candidate = content[cursor]
        if (candidate != '`' && candidate != '~') return null
        var count = 0
        while (cursor + count < lineEnd && content[cursor + count] == candidate) count += 1
        return candidate.takeIf { count >= 3 }
    }

    private fun closingBacktickRun(
        content: String,
        startIndex: Int,
        length: Int,
        existingMask: BooleanArray,
    ): Int? {
        var index = startIndex
        while (index < content.length) {
            if (
                content[index] == '`' &&
                !existingMask[index] &&
                backtickRunLength(content, index) == length
            ) {
                return index
            }
            index += 1
        }
        return null
    }

    private fun backtickRunLength(content: String, index: Int): Int {
        var count = 0
        while (index + count < content.length && content[index + count] == '`') count += 1
        return count
    }

    private fun nextLineEnd(content: String, startIndex: Int): Int {
        var index = startIndex
        while (index < content.length && content[index] != '\n') index += 1
        return index
    }

    private fun mark(mask: BooleanArray, range: IntRange) {
        for (index in range) {
            if (index in mask.indices) mask[index] = true
        }
    }
}

val List<MarkdownMathSegment>.containsMath: Boolean
    get() = any { it is MarkdownMathSegment.InlineMath || it is MarkdownMathSegment.DisplayMath }
