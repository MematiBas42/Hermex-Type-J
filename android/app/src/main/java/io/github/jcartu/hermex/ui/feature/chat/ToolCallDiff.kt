package io.github.jcartu.hermex.ui.feature.chat

import io.github.jcartu.hermex.model.JSONValue
import io.github.jcartu.hermex.model.ToolCall

/** One parsed line in a unified diff. Port of `MarkdownDiffCodeBlock.swift`. */
data class MarkdownDiffLine(val text: String, val kind: Kind) {
    enum class Kind { FILE_HEADER, HUNK, ADDED, REMOVED, CONTEXT, METADATA }
}

data class MarkdownDiffDocument(
    val lines: List<MarkdownDiffLine>,
    val additions: Int,
    val deletions: Int,
) {
    val isCollapsible: Boolean get() = lines.size > COLLAPSED_LINE_LIMIT

    fun visibleLines(showingAll: Boolean): List<MarkdownDiffLine> =
        if (showingAll || !isCollapsible) lines else lines.take(COLLAPSED_LINE_LIMIT)

    companion object {
        const val COLLAPSED_LINE_LIMIT = 80
    }
}

/** Defensive unified-diff parser used by tool rows and Markdown diff fences. */
object MarkdownDiffParser {
    private const val MAX_CHARACTERS = 300_000
    private const val MAX_LINES = 12_000

    fun parse(text: String): MarkdownDiffDocument? {
        if (text.isEmpty() || text.length > MAX_CHARACTERS) return null
        val rawLines = rawLines(text)
        if (rawLines.isEmpty() || rawLines.size > MAX_LINES) return null

        var sawHunk = false
        var additions = 0
        var deletions = 0
        var sawDiffSignal = false
        val lines = ArrayList<MarkdownDiffLine>(rawLines.size)
        rawLines.forEach { line ->
            val kind = when {
                line.startsWith("diff --git ") || line.startsWith("index ") ||
                    line.startsWith("new file mode ") || line.startsWith("deleted file mode ") ||
                    line.startsWith("similarity index ") || line.startsWith("rename from ") ||
                    line.startsWith("rename to ") -> {
                    sawDiffSignal = true
                    MarkdownDiffLine.Kind.METADATA
                }
                line.startsWith("@@") -> {
                    sawHunk = true
                    sawDiffSignal = true
                    MarkdownDiffLine.Kind.HUNK
                }
                !sawHunk && (line.startsWith("--- ") || line.startsWith("+++ ")) -> {
                    sawDiffSignal = true
                    MarkdownDiffLine.Kind.FILE_HEADER
                }
                line.startsWith("+") -> {
                    additions += 1
                    sawDiffSignal = true
                    MarkdownDiffLine.Kind.ADDED
                }
                line.startsWith("-") -> {
                    deletions += 1
                    sawDiffSignal = true
                    MarkdownDiffLine.Kind.REMOVED
                }
                else -> MarkdownDiffLine.Kind.CONTEXT
            }
            lines += MarkdownDiffLine(line, kind)
        }
        if (!sawDiffSignal) return null
        return MarkdownDiffDocument(lines, additions, deletions)
    }

    fun looksLikeUnifiedDiff(text: String): Boolean {
        var hasOldHeader = false
        var hasNewHeader = false
        text.lineSequence().forEach { line ->
            hasOldHeader = hasOldHeader || line.startsWith("--- ")
            hasNewHeader = hasNewHeader || line.startsWith("+++ ")
            if (line.startsWith("diff --git ") || line.startsWith("@@ ") || line.startsWith("@@") ||
                (hasOldHeader && hasNewHeader)
            ) return true
        }
        return false
    }

    internal fun rawLines(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        val normalized = text.replace("\r\n", "\n").replace('\r', '\n')
        val withoutFinalBreak = if (normalized.endsWith('\n')) normalized.dropLast(1) else normalized
        return withoutFinalBreak.split('\n')
    }
}

/**
 * A file edit resolved from a write-type tool call. Port of
 * `Features/Chat/ToolCallDiff.swift`.
 */
data class ToolCallDiff(
    val document: MarkdownDiffDocument,
    val source: Source,
    val isTruncated: Boolean,
    val counts: Counts?,
    val consumedArgumentKeys: Set<String>,
    val hidesResult: Boolean,
) {
    enum class Source { RESULT_DIFF, RESULT_TEXT, ARGUMENT_PATCH, ARGUMENT_REPLACEMENT, ARGUMENT_CONTENT }

    data class Counts(val additions: Int, val deletions: Int?)

    companion object {
        private const val SNIPPET_LIMIT = 4_000
        private const val ARGUMENT_CUT_MARKER = "..."
        private const val PAGINATED_NOTICE =
            "\n\n[Tool output truncated in paginated session response;"
        private val CHANGE_ARGUMENT_KEYS = setOf("patch", "diff", "old_string", "new_string", "content")

        fun resolve(toolCall: ToolCall): ToolCallDiff? {
            if (ToolCallSummaryFormatter.kind(toolCall.name) != ToolCallKind.WRITE || toolCall.isError == true) {
                return null
            }

            val result = ResultReading(toolCall.preview)
            if (result.reportsNoChange) return null
            val args = toolCall.args.orEmpty()
            val candidates = buildList<() -> Candidate?> {
                add { result.envelopeDiff?.let { Candidate(Source.RESULT_DIFF, it, isTruncated = false) } }
                add { result.plainDiff?.let { Candidate(Source.RESULT_TEXT, it, result.isCutOff) } }
                if (result.allowsArgumentDiff) {
                    add { patchCandidate(args) }
                    add { replacementCandidate(args) }
                    add { contentCandidate(args) }
                }
            }

            var partial: Candidate? = null
            for (makeCandidate in candidates) {
                val candidate = makeCandidate() ?: continue
                if (!candidate.isTruncated) return from(candidate, args, result.isCutOff)
                if (partial == null) partial = candidate
            }
            return partial?.let { from(it, args, result.isCutOff) }
        }

        private data class Candidate(
            val source: Source,
            val text: String,
            val isTruncated: Boolean,
            val consumedKeys: Set<String>? = null,
            val hasSyntheticHeader: Boolean = false,
            val hasExactCounts: Boolean = true,
        )

        private class ResultReading(preview: String?) {
            var envelopeDiff: String? = null
            var plainDiff: String? = null
            var isCutOff: Boolean = false
            var reportsNoChange: Boolean = false
            var allowsArgumentDiff: Boolean = true

            init {
                preview?.takeIf { it.isNotBlank() }?.let(::readPreview)
            }

            private fun readPreview(preview: String) {
                val noticeIndex = preview.indexOf(PAGINATED_NOTICE).takeIf { it >= 0 }
                val parsed = if (noticeIndex == null) {
                    ToolCallDisplayFormatter.parsedJSONValue(preview)
                } else {
                    null
                }

                val plainText = when {
                    parsed?.objectValue != null -> {
                        read(parsed.objectValue.orEmpty())
                        return
                    }
                    parsed?.string != null -> unescaped(parsed.string.orEmpty())
                    parsed == null -> {
                        isCutOff = noticeIndex != null ||
                            preview.codePointCount(0, preview.length) == SNIPPET_LIMIT
                        noticeIndex?.let { preview.substring(0, it) } ?: preview
                    }
                    else -> null
                }

                val leadingEnvelope = plainText?.let(::leadingEnvelope)
                if (leadingEnvelope != null) {
                    isCutOff = false
                    read(leadingEnvelope)
                    return
                }

                if (plainText != null && MarkdownDiffParser.looksLikeUnifiedDiff(plainText)) {
                    plainDiff = plainText
                } else if (!isCutOff) {
                    // A complete non-envelope result means the requested argument edit did not land.
                    allowsArgumentDiff = false
                }
            }

            private fun read(envelope: Map<String, JSONValue>) {
                reportsNoChange = hasValue(envelope["error"]) ||
                    envelope["success"]?.boolean == false ||
                    envelope["no_change"]?.boolean == true
                envelope["diff"]?.string?.trim()?.takeIf(String::isNotEmpty)?.let {
                    envelopeDiff = unescaped(envelope["diff"]?.string.orEmpty())
                }
            }

            private fun leadingEnvelope(text: String): Map<String, JSONValue>? {
                val firstLine = text.lineSequence().firstOrNull() ?: return null
                return ToolCallDisplayFormatter.parsedJSONValue(firstLine)?.objectValue
            }

            private fun hasValue(value: JSONValue?): Boolean = when {
                value == null || value.isNull -> false
                value.string != null -> value.string?.trim()?.isNotEmpty() == true
                else -> true
            }
        }

        private fun patchCandidate(args: Map<String, JSONValue>): Candidate? {
            for (key in listOf("patch", "diff")) {
                val value = args[key]?.string?.takeIf { it.isNotBlank() } ?: continue
                return Candidate(
                    source = Source.ARGUMENT_PATCH,
                    text = strippingCutMarker(value),
                    isTruncated = isCutArgument(value),
                    consumedKeys = setOf(key),
                )
            }
            return null
        }

        private fun replacementCandidate(args: Map<String, JSONValue>): Candidate? {
            val old = args["old_string"]?.string
            val new = args["new_string"]?.string
            if (old == null && new == null) return null
            val removed = lines(old?.let(::strippingCutMarker).orEmpty()).map { "-$it" }
            val added = lines(new?.let(::strippingCutMarker).orEmpty()).map { "+$it" }
            if (removed.isEmpty() && added.isEmpty()) return null
            val header = "@@ -1,${removed.size} +1,${added.size} @@"
            return Candidate(
                source = Source.ARGUMENT_REPLACEMENT,
                text = (listOf(header) + removed + added).joinToString("\n"),
                isTruncated = old == null || new == null ||
                    old.let(::isCutArgument) || new.let(::isCutArgument),
                consumedKeys = setOfNotNull(
                    "old_string".takeIf(args::containsKey),
                    "new_string".takeIf(args::containsKey),
                ),
                hasSyntheticHeader = true,
                hasExactCounts = !replacesAll(args),
            )
        }

        private fun contentCandidate(args: Map<String, JSONValue>): Candidate? {
            val content = args["content"]?.string ?: return null
            val added = lines(strippingCutMarker(content)).map { "+$it" }
            if (added.isEmpty()) return null
            return Candidate(
                source = Source.ARGUMENT_CONTENT,
                text = (listOf("@@ -1,0 +1,${added.size} @@") + added).joinToString("\n"),
                isTruncated = isCutArgument(content),
                consumedKeys = setOf("content"),
                hasSyntheticHeader = true,
            )
        }

        private fun replacesAll(args: Map<String, JSONValue>): Boolean =
            args["replace_all"]?.boolean == true ||
                args["replace_all"]?.string?.equals("true", ignoreCase = true) == true

        private fun from(
            candidate: Candidate,
            args: Map<String, JSONValue>,
            resultIsCutOff: Boolean,
        ): ToolCallDiff? {
            val text = droppingFinalLineBreak(candidate.text)
            val parsed = MarkdownDiffParser.parse(text) ?: return null
            val document = if (candidate.hasSyntheticHeader) {
                parsed.copy(lines = parsed.lines.drop(1))
            } else {
                parsed
            }
            val counts = if (candidate.isTruncated || !candidate.hasExactCounts) {
                null
            } else {
                Counts(
                    additions = document.additions,
                    deletions = if (candidate.source == Source.ARGUMENT_CONTENT) null else document.deletions,
                )
            }
            val usesResult = candidate.consumedKeys == null
            return ToolCallDiff(
                document = document,
                source = candidate.source,
                isTruncated = candidate.isTruncated,
                counts = counts,
                consumedArgumentKeys = candidate.consumedKeys
                    ?: CHANGE_ARGUMENT_KEYS.filterTo(linkedSetOf()) { args[it]?.string != null },
                hidesResult = usesResult || resultIsCutOff,
            )
        }

        private fun unescaped(text: String): String =
            if ('\n' in text || '\r' in text) text else ToolCallDisplayFormatter.normalizedDisplayString(text)

        private fun isCutArgument(value: String): Boolean =
            value.codePointCount(0, value.length) == SNIPPET_LIMIT + ARGUMENT_CUT_MARKER.length &&
                value.endsWith(ARGUMENT_CUT_MARKER)

        private fun strippingCutMarker(value: String): String =
            if (isCutArgument(value)) value.dropLast(ARGUMENT_CUT_MARKER.length) else value

        private fun lines(text: String): List<String> = MarkdownDiffParser.rawLines(droppingFinalLineBreak(text))

        private fun droppingFinalLineBreak(text: String): String = when {
            text.endsWith("\r\n") -> text.dropLast(2)
            text.endsWith('\n') || text.endsWith('\r') -> text.dropLast(1)
            else -> text
        }
    }
}
