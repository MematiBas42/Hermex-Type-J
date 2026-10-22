package io.github.jcartu.hermex.ui.feature.chat

import io.github.jcartu.hermex.model.JSONValue
import io.github.jcartu.hermex.model.ToolCall
import java.io.File
import java.util.Locale

/** Port of `Features/Chat/ToolCallSummaryFormatter.swift`. */
enum class ToolCallKind { SHELL, READ, WRITE, SEARCH, WEB, LIST, SKILL, MEMORY, DELEGATE, UNKNOWN }

enum class ToolCallLogStatus { SUCCESS, FAILURE, RUNNING, INTERRUPTED }

data class ToolCallLogRow(
    val kind: ToolCallKind,
    val summary: String,
    val detail: String?,
    val status: ToolCallLogStatus,
    val diffCounts: ToolCallDiff.Counts?,
) {
    val isFailure: Boolean get() = status == ToolCallLogStatus.FAILURE
    val statusText: String
        get() = when (status) {
            ToolCallLogStatus.SUCCESS -> "Completed"
            ToolCallLogStatus.FAILURE -> "Failed"
            ToolCallLogStatus.RUNNING -> "Running"
            ToolCallLogStatus.INTERRUPTED -> "Interrupted"
        }

    val accessibilityLabel: String
        get() = listOfNotNull(
            summary,
            detail,
            diffCounts?.let { counts ->
                buildString {
                    append(counts.additions)
                    append(if (counts.additions == 1) " line added" else " lines added")
                    counts.deletions?.let {
                        append(", ")
                        append(it)
                        append(if (it == 1) " line removed" else " lines removed")
                    }
                }
            },
            statusText,
        ).joinToString(", ")
}

data class ToolCallLogEntry(val toolCall: ToolCall, val row: ToolCallLogRow) {
    val id: String get() = toolCall.presentationID
}

/**
 * Reduces a tool invocation to the compact log row used by the redesigned transcript.
 * Rules match the upstream web UI labels and `ToolCallSummaryFormatter.swift`.
 */
object ToolCallSummaryFormatter {
    private const val DETAIL_LIMIT = 160

    fun entries(toolCalls: List<ToolCall>, isLive: Boolean): List<ToolCallLogEntry> =
        toolCalls.mapNotNull { toolCall ->
            row(toolCall, isLive)?.let { ToolCallLogEntry(toolCall, it) }
        }

    fun row(toolCall: ToolCall, isLive: Boolean): ToolCallLogRow? {
        val name = nonEmpty(toolCall.name)
        val kind = kind(name)
        val resultText = resultText(toolCall)
        val status = status(toolCall, kind, resultText, isLive)
        val detail = targetDetail(kind, name, toolCall.args) ?: resultText?.let(::firstLine)
        if (status != ToolCallLogStatus.FAILURE && name == null && detail == null) return null

        return ToolCallLogRow(
            kind = kind,
            summary = summary(kind, name),
            detail = detail,
            status = status,
            diffCounts = if (status == ToolCallLogStatus.SUCCESS) ToolCallDiff.resolve(toolCall)?.counts else null,
        )
    }

    fun copyText(toolCall: ToolCall): String = buildList {
        row(toolCall, isLive = false)?.let { row ->
            add(listOfNotNull(row.summary, row.detail).joinToString(" "))
        }
        val content = ToolCallDisplayFormatter.content(toolCall)
        addAll(content.argumentRows.map { "${it.key}: ${it.value}" })
        content.result?.let { add(it.text) }
    }.joinToString("\n")

    fun kind(rawName: String?): ToolCallKind {
        val name = normalizedName(rawName)
        if (name.isEmpty()) return ToolCallKind.UNKNOWN
        return when {
            name == "subagent_progress" || name == "delegate_task" -> ToolCallKind.DELEGATE
            "skill" in name -> ToolCallKind.SKILL
            "memory" in name -> ToolCallKind.MEMORY
            listOf("terminal", "shell", "command", "process").any(name::contains) || name == "execute_code" ->
                ToolCallKind.SHELL
            listOf("read", "view", "open").any(name::contains) || name == "vision_analyze" ->
                ToolCallKind.READ
            "list" in name || name == "todo" -> ToolCallKind.LIST
            listOf("web", "fetch", "curl", "extract", "browse", "navigate").any(name::contains) ->
                ToolCallKind.WEB
            listOf("search", "grep", "find").any(name::contains) -> ToolCallKind.SEARCH
            listOf("write", "patch", "edit").any(name::contains) -> ToolCallKind.WRITE
            else -> ToolCallKind.UNKNOWN
        }
    }

    fun shortToolName(rawName: String?): String {
        val name = nonEmpty(rawName) ?: return "Tool"
        if (name.startsWith("mcp__")) {
            val parts = name.split("__").filter(String::isNotEmpty)
            if (parts.size > 1) return parts.drop(1).joinToString("/")
        }
        if (name.startsWith("mcp.")) {
            val parts = name.split('.').filter(String::isNotEmpty)
            if (parts.size > 1) return parts.drop(1).joinToString("/")
        }
        return name
    }

    fun changedFilesPreview(args: Map<String, JSONValue>): String? {
        val paths = LinkedHashSet<String>()
        fun append(value: JSONValue?) {
            string(value)?.let(paths::add)
        }
        listOf("path", "file_path", "file", "target", "destination", "name")
            .forEach { append(args[it]) }
        args["paths"]?.arrayValue?.forEach(::append)
        args["edits"]?.arrayValue?.forEach { edit -> append(edit.objectValue?.get("path")) }
        val first = paths.firstOrNull() ?: return null
        val remaining = paths.size - 1
        val firstName = basename(first)
        return compacted(if (remaining > 0) "$firstName +$remaining more" else firstName)
    }

    fun unwrappingShellWrapper(command: String): String {
        val trimmed = command.trim()
        val split = trimmed.indexOfFirst(Char::isWhitespace)
        if (split < 0) return command
        val executable = trimmed.substring(0, split)
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .lowercase(Locale.ROOT)
            .removeSuffix(".exe")
        val rest = trimmed.substring(split).trim()
        if (rest.isEmpty()) return command
        val regex = when (executable) {
            "pwsh", "powershell" -> Regex("(?:^|\\s)-command\\s+", RegexOption.IGNORE_CASE)
            "cmd" -> Regex("(?:^|\\s)/c\\s+", RegexOption.IGNORE_CASE)
            "bash", "sh", "zsh" -> Regex("(?:^|\\s)-(?:l)?c\\s+", RegexOption.IGNORE_CASE)
            else -> return command
        }
        val match = regex.find(rest) ?: return command
        val inner = rest.substring(match.range.last + 1).trim()
        if (inner.isEmpty()) return command
        val unquoted = if (
            inner.length >= 2 &&
            inner.first() == inner.last() &&
            (inner.first() == '\'' || inner.first() == '"')
        ) {
            inner.substring(1, inner.lastIndex).trim()
        } else {
            inner
        }
        return unquoted.ifEmpty { command }
    }

    fun strippingTrailingExitCode(text: String): Pair<String?, Int?> {
        val trimmed = text.trim()
        val match = TRAILING_EXIT_CODE.matchEntire(trimmed)
            ?: return nonEmpty(trimmed) to null
        return nonEmpty(match.groupValues[1].trim()) to match.groupValues[2].toIntOrNull()
    }

    fun looksLikeFailure(text: String): Boolean {
        val (output, exitCode) = strippingTrailingExitCode(text)
        if (exitCode != null) return exitCode != 0
        val firstLine = output.orEmpty().lineSequence().firstOrNull()?.trim()?.lowercase(Locale.ROOT)
            ?: return false
        if (firstLine.startsWith("error:")) return true
        if (FAILURE_PHRASES.any(firstLine::contains)) return true
        if ("cannot find path" in firstLine && "because it does not exist" in firstLine) return true
        return "is not recognized" in firstLine && "the term '" in firstLine
    }

    private fun summary(kind: ToolCallKind, name: String?): String = when (kind) {
        ToolCallKind.SHELL -> "Ran"
        ToolCallKind.READ -> "Read"
        ToolCallKind.WRITE -> "Updated"
        ToolCallKind.SEARCH -> "Searched"
        ToolCallKind.WEB -> "Checked"
        ToolCallKind.LIST -> "Listed"
        ToolCallKind.SKILL -> "Loaded"
        ToolCallKind.MEMORY -> "Saved"
        ToolCallKind.DELEGATE -> "Delegated"
        ToolCallKind.UNKNOWN -> shortToolName(name)
    }

    private fun targetDetail(
        kind: ToolCallKind,
        name: String?,
        args: Map<String, JSONValue>?,
    ): String? {
        if (args.isNullOrEmpty()) return null
        return when (kind) {
            ToolCallKind.SHELL -> firstString(args, "cmd", "command")
                ?.let(::unwrappingShellWrapper)?.let(::compacted)
            ToolCallKind.SKILL -> firstString(args, "name", "skill")?.let(::compacted)
            ToolCallKind.MEMORY -> firstString(args, "target", "name", "action")?.let(::compacted)
            ToolCallKind.READ -> firstString(args, "path", "file_path", "file", "target", "name")
                ?.let { path ->
                    val fileName = basename(path)
                    if (normalizedName(name) == "read_file") {
                        readRange(args)?.let { "$it · $fileName" } ?: fileName
                    } else {
                        fileName
                    }
                }?.let(::compacted)
            ToolCallKind.WRITE -> changedFilesPreview(args)
            ToolCallKind.SEARCH, ToolCallKind.WEB ->
                firstString(args, "query", "pattern", "url", "uri")?.let(::compacted)
            ToolCallKind.LIST, ToolCallKind.DELEGATE, ToolCallKind.UNKNOWN ->
                firstString(
                    args,
                    "cmd", "command", "path", "file_path", "file", "uri", "url",
                    "query", "pattern", "dir", "task", "name",
                )?.let(::compacted)
        }
    }

    private fun readRange(args: Map<String, JSONValue>): String? {
        val offset = integer(args["offset"])?.takeIf { it > 0 } ?: return null
        val limitValue = args["limit"] ?: return "L$offset"
        val limit = integer(limitValue)?.takeIf { it > 0 } ?: return null
        if (limit == 1L) return "L$offset"
        val end = runCatching { Math.addExact(offset, limit - 1) }.getOrNull() ?: return null
        return "L$offset-$end"
    }

    private fun status(
        toolCall: ToolCall,
        kind: ToolCallKind,
        resultText: String?,
        isLive: Boolean,
    ): ToolCallLogStatus {
        when (toolCall.isError) {
            true -> return ToolCallLogStatus.FAILURE
            false -> Unit
            null -> {
                val envelopeFailure = ToolCallDisplayFormatter.envelopeReportsFailure(toolCall.preview)
                if (envelopeFailure == true) return ToolCallLogStatus.FAILURE
                if (
                    envelopeFailure == null && resultText != null &&
                    kind != ToolCallKind.SEARCH && kind != ToolCallKind.WEB &&
                    looksLikeFailure(resultText)
                ) return ToolCallLogStatus.FAILURE
            }
        }
        if (toolCall.isCompleted) return ToolCallLogStatus.SUCCESS
        return if (isLive) ToolCallLogStatus.RUNNING else ToolCallLogStatus.INTERRUPTED
    }

    private fun resultText(toolCall: ToolCall): String? =
        ToolCallDisplayFormatter.resultDisplay(toolCall.preview, toolCall.name)?.text

    private fun firstLine(text: String): String? {
        val stripped = strippingTrailingExitCode(text).first ?: text
        return stripped.lineSequence().mapNotNull(::compacted).firstOrNull()
    }

    private fun compacted(text: String): String? {
        val collapsed = text.split(Regex("\\s+")).filter(String::isNotEmpty).joinToString(" ")
        if (collapsed.isEmpty()) return null
        return if (collapsed.length <= DETAIL_LIMIT) collapsed else collapsed.take(DETAIL_LIMIT - 1) + "…"
    }

    private fun basename(path: String): String =
        path.trim().replace('\\', '/').substringAfterLast('/').ifEmpty { File(path).name.ifEmpty { path.trim() } }

    private fun normalizedName(rawName: String?): String = buildString {
        var pendingUnderscore = false
        for (character in rawName.orEmpty().lowercase(Locale.ROOT)) {
            if (character.isLetterOrDigit() && character.code < 128) {
                if (pendingUnderscore && isNotEmpty()) append('_')
                pendingUnderscore = false
                append(character)
            } else {
                pendingUnderscore = true
            }
        }
    }

    private fun firstString(args: Map<String, JSONValue>, vararg keys: String): String? =
        keys.firstNotNullOfOrNull { string(args[it]) }

    private fun string(value: JSONValue?): String? = value?.string?.let(::nonEmpty)

    private fun integer(value: JSONValue?): Long? {
        value ?: return null
        value.number?.let {
            if (!it.isFinite() || it != kotlin.math.truncate(it)) return null
            if (it < Long.MIN_VALUE.toDouble() || it >= Long.MAX_VALUE.toDouble()) return null
            return it.toLong()
        }
        return value.string?.trim()?.toLongOrNull()
    }

    private fun nonEmpty(value: String?): String? = value?.trim()?.takeIf(String::isNotEmpty)

    private val TRAILING_EXIT_CODE = Regex(
        "^([\\s\\S]*?)(?:\\s*<exited with exit code (\\d+)>)\\s*$",
        RegexOption.IGNORE_CASE,
    )

    private val FAILURE_PHRASES = listOf(
        "file not found", "no files found", "enoent", "no such file or directory", "no such file",
        "commandnotfoundexception", "command not found", "is not recognized as the name of a cmdlet",
        "a parameter cannot be found that matches parameter name",
    )
}
