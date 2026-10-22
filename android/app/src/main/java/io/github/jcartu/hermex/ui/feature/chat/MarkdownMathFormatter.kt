package io.github.jcartu.hermex.ui.feature.chat

/**
 * Readable LaTeX fallback ported from `MarkdownMathFormatter*.swift`.
 *
 * Android has no math dependency in the approved catalog, so supported LaTeX
 * is normalized to Unicode while preserving surrounding Markdown byte-for-byte.
 * Inline expressions become inline code; display expressions use a fenced block.
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

    fun renderedText(latex: String): String {
        var rendered = latex.trim()
            .replace("\\(", "")
            .replace("\\)", "")
            .replace("\\[", "")
            .replace("\\]", "")
        rendered = replaceAligned(rendered)
        rendered = replaceCases(rendered)
        rendered = replaceMatrices(rendered)
        rendered = replaceOneGroupCommand(rendered, "\\boxed") { renderedText(it) }
        rendered = replaceTwoGroupCommand(rendered, "\\frac") { numerator, denominator ->
            val top = renderedText(numerator)
            val bottom = renderedText(denominator)
            if (isSimpleMathText(top) && isSimpleMathText(bottom)) "$top/$bottom" else "($top)/($bottom)"
        }
        rendered = replaceOneGroupCommand(rendered, "\\sqrt") {
            val value = renderedText(it)
            if (isSimpleMathText(value)) "√$value" else "√($value)"
        }
        rendered = replaceOneGroupCommand(rendered, "\\mathcal") {
            renderedText(it).map { character -> MATHCAL_CHARACTERS[character] ?: character.toString() }.joinToString("")
        }
        for (command in listOf("\\mathbf", "\\mathrm", "\\operatorname", "\\text")) {
            rendered = replaceOneGroupCommand(rendered, command) { renderedText(it) }
        }
        rendered = replaceOneGroupCommand(rendered, "\\hat") {
            val value = renderedText(it)
            HATTED_CHARACTERS[value.singleOrNull()]?.toString() ?: "$value\u0302"
        }
        for ((command, replacement) in KNOWN_COMMANDS) {
            rendered = rendered.replace(command, replacement)
        }
        rendered = replaceScripts(rendered)
        rendered = rendered
            .replace("\\qquad", " ")
            .replace("\\quad", " ")
            .replace("\\!", "")
            .replace("\\\\", "\n")
            .replace("\\,", " ")
            .replace("\\;", " ")
            .replace("\\ ", " ")
            .replace("{", "")
            .replace("}", "")
        return normalizeWhitespace(rendered)
    }

    private fun replaceAligned(value: String): String {
        fun rendered(body: String): String = body.split("\\\\")
            .map { renderedText(it.replace("&", "")) }
            .filter(String::isNotEmpty)
            .joinToString("\n")
        return replaceEnvironment(
            replaceEnvironment(value, "aligned", ::rendered),
            "align",
            ::rendered,
        )
    }

    private fun replaceCases(value: String): String =
        replaceEnvironment(value, "cases") { body ->
            val rows = body.split("\\\\")
                .map { renderedText(it.replace("&", "")) }
                .filter(String::isNotEmpty)
            when (rows.size) {
                0 -> ""
                1 -> "{ ${rows[0]}"
                else -> rows.mapIndexed { index, row ->
                    val bracket = when (index) {
                        0 -> "⎧"
                        rows.lastIndex -> "⎩"
                        else -> "⎨"
                    }
                    "$bracket $row"
                }.joinToString("\n")
            }
        }

    private fun replaceMatrices(value: String): String {
        var result = value
        result = replaceEnvironment(result, "pmatrix") { renderMatrix(it, parentheses = true) }
        result = replaceEnvironment(result, "bmatrix") { renderMatrix(it, parentheses = false) }
        return result
    }

    private fun renderMatrix(body: String, parentheses: Boolean): String {
        val rows = body.split("\\\\")
            .map { row -> row.split('&').map { renderedText(it).trim() } }
            .filter(List<String>::isNotEmpty)
        if (rows.isEmpty()) return ""
        val columns = rows.maxOf(List<String>::size)
        val widths = IntArray(columns) { column ->
            rows.maxOf { row -> row.getOrNull(column)?.length ?: 0 }
        }
        return rows.mapIndexed { rowIndex, row ->
            val (left, right) = matrixBrackets(parentheses, rowIndex, rows.size)
            val cells = (0 until columns).joinToString("  ") { column ->
                row.getOrElse(column) { "" }.padEnd(widths[column])
            }
            "$left $cells $right"
        }.joinToString("\n")
    }

    private fun matrixBrackets(parentheses: Boolean, row: Int, count: Int): Pair<String, String> {
        if (count == 1) return if (parentheses) "(" to ")" else "[" to "]"
        return if (parentheses) {
            when (row) {
                0 -> "⎛" to "⎞"
                count - 1 -> "⎝" to "⎠"
                else -> "⎜" to "⎟"
            }
        } else {
            when (row) {
                0 -> "⎡" to "⎤"
                count - 1 -> "⎣" to "⎦"
                else -> "⎢" to "⎥"
            }
        }
    }

    private fun replaceEnvironment(
        value: String,
        name: String,
        transform: (String) -> String,
    ): String {
        val begin = "\\begin{$name}"
        val end = "\\end{$name}"
        var result = value
        while (true) {
            val start = result.indexOf(begin)
            if (start < 0) return result
            val bodyStart = start + begin.length
            val finish = result.indexOf(end, bodyStart)
            if (finish < 0) return result
            result = result.replaceRange(start, finish + end.length, transform(result.substring(bodyStart, finish)))
        }
    }

    private fun replaceOneGroupCommand(
        value: String,
        command: String,
        transform: (String) -> String,
    ): String {
        var result = StringBuilder(value.length)
        var index = 0
        while (index < value.length) {
            if (!value.startsWith(command, index)) {
                result.append(value[index++])
                continue
            }
            val groupStart = skipSpaces(value, index + command.length)
            val group = parseGroup(value, groupStart)
            if (group == null) {
                result.append(value[index++])
                continue
            }
            result.append(transform(group.first))
            index = group.second
        }
        return result.toString()
    }

    private fun replaceTwoGroupCommand(
        value: String,
        command: String,
        transform: (String, String) -> String,
    ): String {
        val result = StringBuilder(value.length)
        var index = 0
        while (index < value.length) {
            if (!value.startsWith(command, index)) {
                result.append(value[index++])
                continue
            }
            val first = parseGroup(value, skipSpaces(value, index + command.length))
            val second = first?.let { parseGroup(value, skipSpaces(value, it.second)) }
            if (first == null || second == null) {
                result.append(value[index++])
                continue
            }
            result.append(transform(first.first, second.first))
            index = second.second
        }
        return result.toString()
    }

    private fun replaceScripts(value: String): String {
        val result = StringBuilder(value.length)
        var index = 0
        while (index < value.length) {
            val marker = value[index]
            if (marker != '^' && marker != '_') {
                result.append(marker)
                index += 1
                continue
            }
            val start = index + 1
            val script = when {
                start >= value.length -> null
                value[start] == '{' -> parseGroup(value, start)
                value[start] == '\\' -> {
                    var end = start + 1
                    while (end < value.length && value[end].isLetter()) end += 1
                    if (end == start + 1 && end < value.length) value[end].toString() to end + 1
                    else value.substring(start, end) to end
                }
                else -> value[start].toString() to start + 1
            }
            if (script == null) {
                result.append(marker)
                index += 1
                continue
            }
            val rendered = renderedText(script.first)
            result.append(if (marker == '^') mapSuperscript(rendered) else mapSubscript(rendered))
            index = script.second
        }
        return result.toString()
    }

    private fun mapSuperscript(value: String): String {
        val mapped = value.map { SUPERSCRIPT_CHARACTERS[it] ?: it }.joinToString("")
        return if (mapped == value) "^$value" else mapped
    }

    private fun mapSubscript(value: String): String {
        val mapped = value.map { SUBSCRIPT_CHARACTERS[it] ?: it }.joinToString("")
        return if (mapped == value) "_$value" else mapped
    }

    private fun parseGroup(value: String, start: Int): Pair<String, Int>? {
        if (start !in value.indices || value[start] != '{') return null
        var depth = 0
        var index = start
        while (index < value.length) {
            if (value[index] == '{' && !isEscaped(value, index)) {
                depth += 1
            } else if (value[index] == '}' && !isEscaped(value, index)) {
                depth -= 1
                if (depth == 0) return value.substring(start + 1, index) to index + 1
            }
            index += 1
        }
        return null
    }

    private fun isEscaped(value: String, index: Int): Boolean {
        var cursor = index - 1
        var count = 0
        while (cursor >= 0 && value[cursor] == '\\') {
            count += 1
            cursor -= 1
        }
        return count % 2 == 1
    }

    private fun skipSpaces(value: String, start: Int): Int {
        var index = start
        while (index < value.length && value[index].isWhitespace()) index += 1
        return index
    }

    private fun isSimpleMathText(value: String): Boolean =
        value.none { it.isWhitespace() || it in "+-*/=<>|·" }

    private fun normalizeWhitespace(value: String): String = value
        .lineSequence()
        .map { line ->
            line.replace(Regex("[ \\t]+"), " ")
                .replace(Regex("∂\\s+"), "∂")
                .trim()
        }
        .joinToString("\n")
        .trim()

    private fun inlineCode(value: String): String {
        val delimiter = "`".repeat(maxOf(1, longestRun(value, '`') + 1))
        val needsPadding = value.startsWith('`') || value.endsWith('`') ||
            value.startsWith(' ') || value.endsWith(' ')
        return if (needsPadding) "$delimiter $value $delimiter" else "$delimiter$value$delimiter"
    }

    private fun fencedCode(value: String): String {
        val delimiter = "`".repeat(maxOf(3, longestRun(value, '`') + 1))
        return "${delimiter}latex\n$value\n$delimiter"
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

    private val KNOWN_COMMANDS = linkedMapOf(
        "\\Leftrightarrow" to "⇔", "\\Rightarrow" to "⇒", "\\rightarrow" to "→",
        "\\leftarrow" to "←", "\\varepsilon" to "ε", "\\operatorname" to "",
        "\\left" to "", "\\right" to "", "\\Bigl" to "", "\\Bigr" to "",
        "\\bigl" to "", "\\bigr" to "", "\\Big" to "", "\\big" to "",
        "\\infty" to "∞", "\\partial" to "∂", "\\nabla" to "∇",
        "\\epsilon" to "ε", "\\lambda" to "λ", "\\sigma" to "σ",
        "\\theta" to "θ", "\\alpha" to "α", "\\beta" to "β",
        "\\gamma" to "γ", "\\delta" to "δ", "\\rho" to "ρ", "\\Phi" to "Φ",
        "\\phi" to "φ", "\\mu" to "μ", "\\pi" to "π", "\\sum" to "∑",
        "\\prod" to "∏", "\\int" to "∫", "\\times" to "×", "\\cdot" to "·",
        "\\div" to "÷", "\\lVert" to "‖", "\\rVert" to "‖", "\\Vert" to "‖",
        "\\ldots" to "…", "\\cdots" to "⋯", "\\approx" to "≈",
        "\\neq" to "≠", "\\ne" to "≠", "\\leq" to "≤", "\\le" to "≤",
        "\\geq" to "≥", "\\ge" to "≥", "\\pm" to "±", "\\mp" to "∓",
        "\\mid" to "|", "\\top" to "⊤", "\\bot" to "⊥", "\\to" to "→",
        "\\log" to "log", "\\ln" to "ln", "\\sin" to "sin", "\\cos" to "cos",
        "\\tan" to "tan", "\\arg" to "arg", "\\min" to "min", "\\max" to "max",
        "\\lim" to "lim", "\\exp" to "exp", "\\det" to "det",
    ).entries.sortedByDescending { it.key.length }.map { it.key to it.value }

    private val SUPERSCRIPT_CHARACTERS = mapOf(
        '0' to '⁰', '1' to '¹', '2' to '²', '3' to '³', '4' to '⁴',
        '5' to '⁵', '6' to '⁶', '7' to '⁷', '8' to '⁸', '9' to '⁹',
        '+' to '⁺', '-' to '⁻', '=' to '⁼', '(' to '⁽', ')' to '⁾',
        'a' to 'ᵃ', 'b' to 'ᵇ', 'c' to 'ᶜ', 'd' to 'ᵈ', 'e' to 'ᵉ',
        'f' to 'ᶠ', 'g' to 'ᵍ', 'h' to 'ʰ', 'i' to 'ⁱ', 'j' to 'ʲ',
        'k' to 'ᵏ', 'l' to 'ˡ', 'm' to 'ᵐ', 'n' to 'ⁿ', 'o' to 'ᵒ',
        'p' to 'ᵖ', 'r' to 'ʳ', 's' to 'ˢ', 't' to 'ᵗ', 'u' to 'ᵘ',
        'v' to 'ᵛ', 'w' to 'ʷ', 'x' to 'ˣ', 'y' to 'ʸ', 'z' to 'ᶻ',
    )

    private val SUBSCRIPT_CHARACTERS = mapOf(
        '0' to '₀', '1' to '₁', '2' to '₂', '3' to '₃', '4' to '₄',
        '5' to '₅', '6' to '₆', '7' to '₇', '8' to '₈', '9' to '₉',
        '+' to '₊', '-' to '₋', '=' to '₌', '(' to '₍', ')' to '₎',
        'a' to 'ₐ', 'e' to 'ₑ', 'h' to 'ₕ', 'i' to 'ᵢ', 'j' to 'ⱼ',
        'k' to 'ₖ', 'l' to 'ₗ', 'm' to 'ₘ', 'n' to 'ₙ', 'o' to 'ₒ',
        'p' to 'ₚ', 'r' to 'ᵣ', 's' to 'ₛ', 't' to 'ₜ', 'u' to 'ᵤ',
        'v' to 'ᵥ', 'x' to 'ₓ',
    )

    private val HATTED_CHARACTERS = mapOf(
        'a' to "â", 'e' to "ê", 'i' to "î", 'o' to "ô", 'u' to "û",
        'A' to "Â", 'E' to "Ê", 'I' to "Î", 'O' to "Ô", 'U' to "Û",
        'x' to "x̂", 'y' to "ŷ", 'z' to "ẑ",
    )

    private val MATHCAL_CHARACTERS = mapOf(
        'A' to "𝒜", 'B' to "ℬ", 'C' to "𝒞", 'D' to "𝒟", 'E' to "ℰ",
        'F' to "ℱ", 'G' to "𝒢", 'H' to "ℋ", 'I' to "ℐ", 'J' to "𝒥",
        'K' to "𝒦", 'L' to "ℒ", 'M' to "ℳ", 'N' to "𝒩", 'O' to "𝒪",
        'P' to "𝒫", 'Q' to "𝒬", 'R' to "ℛ", 'S' to "𝒮", 'T' to "𝒯",
        'U' to "𝒰", 'V' to "𝒱", 'W' to "𝒲", 'X' to "𝒳", 'Y' to "𝒴",
        'Z' to "𝒵",
    )
}
