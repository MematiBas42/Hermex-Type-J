package io.github.jcartu.hermex.ui.feature.workspace

/** Token categories drawn by [SourceViewerScreen]. */
enum class SourceTokenKind { KEYWORD, STRING, COMMENT, NUMBER, MARKUP }

/** Half-open character range in one source line. */
data class SourceToken(val start: Int, val endExclusive: Int, val kind: SourceTokenKind)

/** One source row and its one-based line number. */
data class SourceLine(val number: Int, val text: String, val tokens: List<SourceToken>)

/** Tokenized file used by the lazy source surface. */
data class SourceDocument(
    val language: SourceLanguage?,
    val lines: List<SourceLine>,
)

/**
 * Grammars covered by upstream's `SourceHighlightLanguage`. Aliases are resolved
 * from either the server language or the filename extension.
 */
enum class SourceLanguage(
    internal val lineComments: List<String>,
    internal val blockComments: List<Pair<String, String>>,
    internal val quotes: Set<Char>,
    internal val multilineQuotes: Set<String> = emptySet(),
    internal val keywords: Set<String>,
) {
    KOTLIN(
        listOf("//"), listOf("/*" to "*/"), setOf('\'', '"'), setOf("\"\"\""),
        words("as break class continue do else false for fun if in interface is null object package return super this throw true try typealias typeof val var when while by catch constructor delegate dynamic field file finally get import init param property receiver set setparam where actual abstract annotation companion const crossinline data enum expect external final infix inline inner internal lateinit noinline open operator out override private protected public reified sealed suspend tailrec vararg field it"),
    ),
    SWIFT(
        listOf("//"), listOf("/*" to "*/"), setOf('\'', '"'), setOf("\"\"\""),
        words("associatedtype class deinit enum extension fileprivate func import init inout internal let open operator private precedencegroup protocol public rethrows static struct subscript typealias var break case continue default defer do else fallthrough for guard if in repeat return switch where while as Any catch false is nil super self Self throw throws true try await async actor some any"),
    ),
    PYTHON(
        listOf("#"), emptyList(), setOf('\'', '"'), setOf("'''", "\"\"\""),
        words("and as assert async await break class continue def del elif else except False finally for from global if import in is lambda None nonlocal not or pass raise return True try while with yield match case"),
    ),
    JAVASCRIPT(
        listOf("//"), listOf("/*" to "*/"), setOf('\'', '"', '`'),
        keywords = words("await break case catch class const continue debugger default delete do else export extends false finally for function if import in instanceof let new null return static super switch this throw true try typeof undefined var void while with yield async of"),
    ),
    TYPESCRIPT(
        listOf("//"), listOf("/*" to "*/"), setOf('\'', '"', '`'),
        keywords = words("abstract any as asserts async await bigint boolean break case catch class const constructor continue debugger declare default delete do else enum export extends false finally for from function get if implements import in infer instanceof interface is keyof let module namespace never new null number object of override private protected public readonly require return set static string super switch symbol this throw true try type typeof undefined unique unknown var void while with yield"),
    ),
    JSON(
        emptyList(), emptyList(), setOf('"'),
        keywords = setOf("true", "false", "null"),
    ),
    MARKDOWN(
        emptyList(), listOf("<!--" to "-->"), setOf('`'), setOf("```", "~~~"),
        keywords = emptySet(),
    ),
    C_LIKE(
        listOf("//"), listOf("/*" to "*/"), setOf('\'', '"'),
        keywords = words("auto break case char class const continue default do double else enum extern false float for goto if import inline int interface long namespace new null package private protected public register return short signed sizeof static struct switch template this throw true try typedef typename union unsigned using virtual void volatile while"),
    ),
    RUST(
        listOf("//"), listOf("/*" to "*/"), setOf('\'', '"'),
        keywords = words("as async await break const continue crate dyn else enum extern false fn for if impl in let loop match mod move mut pub ref return self Self static struct super trait true type unsafe use where while"),
    ),
    GO(
        listOf("//"), listOf("/*" to "*/"), setOf('\'', '"', '`'),
        keywords = words("break default func interface select case defer go map struct chan else goto package switch const fallthrough if range type continue for import return var true false nil"),
    ),
    RUBY(
        listOf("#"), listOf("=begin" to "=end"), setOf('\'', '"', '`'),
        keywords = words("alias and begin break case class def defined do else elsif end ensure false for if in module next nil not or redo rescue retry return self super then true undef unless until when while yield"),
    ),
    SHELL(
        listOf("#"), emptyList(), setOf('\'', '"', '`'),
        keywords = words("case do done elif else esac fi for function if in select then time until while coproc declare export local readonly return set shift source trap unset"),
    ),
    HTML(
        emptyList(), listOf("<!--" to "-->"), setOf('\'', '"'),
        keywords = emptySet(),
    ),
    CSS(
        listOf("//"), listOf("/*" to "*/"), setOf('\'', '"'),
        keywords = words("important inherit initial revert unset from to and or not only screen print supports media keyframes var calc"),
    ),
    YAML(
        listOf("#"), emptyList(), setOf('\'', '"'),
        keywords = setOf("true", "false", "null", "yes", "no", "on", "off"),
    );

    companion object {
        /** Port of `SourceHighlightLanguage.resolve(path:serverLanguage:)`. */
        fun resolve(path: String, serverLanguage: String? = null): SourceLanguage? {
            val extension = pathExtension(path)
            return sequenceOf(serverLanguage, extension)
                .filterNotNull()
                .map { it.trim().lowercase() }
                .mapNotNull(ALIASES::get)
                .firstOrNull()
        }

        private val ALIASES = buildMap {
            listOf("kotlin", "kt", "kts").forEach { put(it, KOTLIN) }
            listOf("swift").forEach { put(it, SWIFT) }
            listOf("python", "py", "pyi", "pyw", "pyx").forEach { put(it, PYTHON) }
            listOf("javascript", "js", "jsx", "mjs", "cjs").forEach { put(it, JAVASCRIPT) }
            listOf("typescript", "ts", "tsx", "mts", "cts").forEach { put(it, TYPESCRIPT) }
            listOf("json", "jsonc").forEach { put(it, JSON) }
            listOf("markdown", "md", "mdx", "mdown", "mkd").forEach { put(it, MARKDOWN) }
            listOf("c", "h", "cpp", "cc", "cxx", "hpp", "hxx", "java", "cs", "dart").forEach { put(it, C_LIKE) }
            listOf("rust", "rs").forEach { put(it, RUST) }
            listOf("go").forEach { put(it, GO) }
            listOf("ruby", "rb", "rake", "erb").forEach { put(it, RUBY) }
            listOf("bash", "sh", "zsh", "fish").forEach { put(it, SHELL) }
            listOf("html", "htm", "xml", "plist", "svg", "vue", "svelte", "astro").forEach { put(it, HTML) }
            listOf("css", "scss", "sass", "less", "postcss").forEach { put(it, CSS) }
            listOf("yaml", "yml").forEach { put(it, YAML) }
        }
    }
}

/**
 * Dependency-free tokenizer used instead of iOS Highlightr. It recognizes the
 * same practical language family and keeps multiline string/comment state
 * between rows; malformed or unknown syntax remains plain text.
 */
object SourceTokenizer {
    /** Tokenizes [content] once; source rows are then rendered lazily by Compose. */
    fun tokenize(content: String, path: String, serverLanguage: String? = null): SourceDocument {
        val language = SourceLanguage.resolve(path, serverLanguage)
        val rawLines = content.split('\n')
        if (language == null) {
            return SourceDocument(null, rawLines.mapIndexed { index, line -> SourceLine(index + 1, line, emptyList()) })
        }

        var blockEnd: String? = null
        var multilineQuote: String? = null
        val lines = rawLines.mapIndexed { lineIndex, line ->
            val result = ArrayList<SourceToken>()
            var index = 0
            while (index < line.length) {
                val activeBlockEnd = blockEnd
                if (activeBlockEnd != null) {
                    val end = line.indexOf(activeBlockEnd, index)
                    val tokenEnd = if (end < 0) line.length else end + activeBlockEnd.length
                    result += SourceToken(index, tokenEnd, SourceTokenKind.COMMENT)
                    index = tokenEnd
                    if (end >= 0) blockEnd = null
                    continue
                }

                val activeMultilineQuote = multilineQuote
                if (activeMultilineQuote != null) {
                    val end = line.indexOf(activeMultilineQuote, index)
                    val tokenEnd = if (end < 0) line.length else end + activeMultilineQuote.length
                    result += SourceToken(index, tokenEnd, SourceTokenKind.STRING)
                    index = tokenEnd
                    if (end >= 0) multilineQuote = null
                    continue
                }

                val block = language.blockComments.firstOrNull { line.startsWith(it.first, index) }
                if (block != null) {
                    val end = line.indexOf(block.second, index + block.first.length)
                    val tokenEnd = if (end < 0) line.length else end + block.second.length
                    result += SourceToken(index, tokenEnd, SourceTokenKind.COMMENT)
                    if (end < 0) blockEnd = block.second
                    index = tokenEnd
                    continue
                }

                val lineComment = language.lineComments.firstOrNull { marker ->
                    line.startsWith(marker, index) && (language != SourceLanguage.SHELL || index == 0 || line[index - 1].isWhitespace())
                }
                if (lineComment != null) {
                    result += SourceToken(index, line.length, SourceTokenKind.COMMENT)
                    break
                }

                val longQuote = language.multilineQuotes.firstOrNull { line.startsWith(it, index) }
                if (longQuote != null) {
                    val end = line.indexOf(longQuote, index + longQuote.length)
                    val tokenEnd = if (end < 0) line.length else end + longQuote.length
                    result += SourceToken(index, tokenEnd, if (language == SourceLanguage.MARKDOWN) SourceTokenKind.MARKUP else SourceTokenKind.STRING)
                    if (end < 0) multilineQuote = longQuote
                    index = tokenEnd
                    continue
                }

                val character = line[index]
                if (character in language.quotes) {
                    val end = quotedEnd(line, index, character)
                    result += SourceToken(index, end, if (language == SourceLanguage.MARKDOWN) SourceTokenKind.MARKUP else SourceTokenKind.STRING)
                    index = end
                    continue
                }

                if (character.isDigit() || (character == '-' && line.getOrNull(index + 1)?.isDigit() == true)) {
                    val end = numberEnd(line, index)
                    result += SourceToken(index, end, SourceTokenKind.NUMBER)
                    index = end
                    continue
                }

                if (character.isLetter() || character == '_') {
                    var end = index + 1
                    while (end < line.length && (line[end].isLetterOrDigit() || line[end] == '_')) end++
                    if (line.substring(index, end) in language.keywords) {
                        result += SourceToken(index, end, SourceTokenKind.KEYWORD)
                    }
                    index = end
                    continue
                }

                if (language == SourceLanguage.MARKDOWN && isMarkdownMarker(line, index)) {
                    var end = index + 1
                    while (end < line.length && line[end] == character) end++
                    result += SourceToken(index, end, SourceTokenKind.MARKUP)
                    index = end
                    continue
                }
                index++
            }
            SourceLine(lineIndex + 1, line, mergeAdjacent(result))
        }
        return SourceDocument(language, lines)
    }

    private fun quotedEnd(line: String, start: Int, quote: Char): Int {
        var index = start + 1
        var escaped = false
        while (index < line.length) {
            val character = line[index]
            if (!escaped && character == quote) return index + 1
            escaped = !escaped && character == '\\'
            if (character != '\\') escaped = false
            index++
        }
        return line.length
    }

    private fun numberEnd(line: String, start: Int): Int {
        var index = start + 1
        while (index < line.length) {
            val character = line[index]
            val previous = line[index - 1]
            val allowed = character.isDigit() || character in "._xXabcdefABCDEF" ||
                ((character == '+' || character == '-') && (previous == 'e' || previous == 'E')) ||
                character == 'e' || character == 'E'
            if (!allowed) break
            index++
        }
        return index
    }

    private fun isMarkdownMarker(line: String, index: Int): Boolean {
        val marker = line[index]
        return marker in "#*_>[]()-!" && (index == 0 || line[index - 1].isWhitespace() || marker in "[]()")
    }

    private fun mergeAdjacent(tokens: List<SourceToken>): List<SourceToken> {
        if (tokens.size < 2) return tokens
        val result = ArrayList<SourceToken>(tokens.size)
        for (token in tokens) {
            val previous = result.lastOrNull()
            if (previous != null && previous.kind == token.kind && previous.endExclusive == token.start) {
                result[result.lastIndex] = previous.copy(endExclusive = token.endExclusive)
            } else {
                result += token
            }
        }
        return result
    }
}

private fun words(value: String): Set<String> = value.split(' ').filter(String::isNotEmpty).toSet()
