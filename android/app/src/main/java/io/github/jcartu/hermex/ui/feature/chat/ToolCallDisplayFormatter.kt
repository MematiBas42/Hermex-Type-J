package io.github.jcartu.hermex.ui.feature.chat

import io.github.jcartu.hermex.model.JSONValue
import io.github.jcartu.hermex.model.ToolCall
import java.util.Locale
import kotlinx.serialization.json.Json

data class ToolCallDisplayContent(
    val argumentRows: List<ToolCallArgumentDisplay>,
    val result: ToolCallResultDisplay?,
)

data class ToolCallArgumentDisplay(
    val key: String,
    val value: String,
)

data class ToolCallResultDisplay(
    val title: String,
    val text: String,
    val isMonospaced: Boolean,
)

enum class ToolCallVisualStatus { RUNNING, COMPLETED, FAILED }

data class ToolCallStatusDisplay(
    val status: ToolCallVisualStatus,
    val collapsedText: String?,
    val detailText: String,
)

/** Pure display shaping shared by the tool-call and activity-group cards. */
object ToolCallDisplayFormatter {
    fun content(toolCall: ToolCall): ToolCallDisplayContent = ToolCallDisplayContent(
        argumentRows = argumentRows(toolCall.args),
        result = resultDisplay(toolCall.preview, toolCall.name),
    )

    fun displayName(toolCall: ToolCall): String = toolCall.displayName

    fun statusDisplay(toolCall: ToolCall): ToolCallStatusDisplay {
        if (toolCall.isError == true) {
            return ToolCallStatusDisplay(
                status = ToolCallVisualStatus.FAILED,
                collapsedText = "Failed",
                detailText = "Failed",
            )
        }
        if (toolCall.isCompleted) {
            val detail = toolCall.duration?.let { "Completed in ${formatDuration(it)}" } ?: "Completed"
            return ToolCallStatusDisplay(
                status = ToolCallVisualStatus.COMPLETED,
                collapsedText = null,
                detailText = detail,
            )
        }
        return ToolCallStatusDisplay(
            status = ToolCallVisualStatus.RUNNING,
            collapsedText = "Running",
            detailText = "Running",
        )
    }

    fun argumentRows(args: Map<String, JSONValue>?): List<ToolCallArgumentDisplay> =
        args.orEmpty()
            .toSortedMap()
            .map { (key, value) -> ToolCallArgumentDisplay(key, value.toolDisplayText()) }

    fun resultDisplay(preview: String?, toolName: String?): ToolCallResultDisplay? {
        val normalizedPreview = nonEmpty(preview) ?: return null
        val parsedValue = parsedJSONValue(normalizedPreview)
        val parsedText = parsedValue?.let { readableResultText(it, toolName) }
        val resultText = nonEmpty(parsedText) ?: normalizedPreview

        return ToolCallResultDisplay(
            title = "Result",
            text = resultText,
            isMonospaced = isTerminalTool(toolName) || resultText.contains('\n') || parsedText != null,
        )
    }

    private fun readableResultText(value: JSONValue, toolName: String?): String? {
        value.string?.let { return nonEmpty(normalizedDisplayString(it)) }
        value.number?.let { return value.inlineDisplayText() }
        value.boolean?.let { return value.inlineDisplayText() }
        value.objectValue?.let { objectValue ->
            if (isTerminalTool(toolName)) {
                terminalEnvelopeText(objectValue, toolName)?.let { return it }
            }
            commonEnvelopeText(objectValue, toolName, includeObjectFallback = false)?.let { return it }
            return terminalEnvelopeText(objectValue, toolName)
                ?: commonEnvelopeText(objectValue, toolName)
        }
        value.arrayValue?.let { return value.toolDisplayText() }
        return null
    }

    private fun terminalEnvelopeText(
        value: Map<String, JSONValue>,
        toolName: String?,
    ): String? {
        val terminalKeys = setOf("output", "stdout", "stderr", "exit_code", "exitCode", "error")
        if (!isTerminalTool(toolName) && value.keys.none { it in terminalKeys }) return null

        val sections = mutableListOf<String>()
        firstNonEmptyString(listOf("output", "stdout"), value)?.let(sections::add)
        nonEmptyString(value["stderr"])?.let(sections::add)
        errorText(value["error"])?.let { sections += "Error: $it" }

        val exitCode = exitCode(value["exit_code"] ?: value["exitCode"])
        if (exitCode != null && (exitCode != 0L || sections.isEmpty())) {
            sections += "Exit code: $exitCode"
        }
        return nonEmpty(sections.joinToString("\n"))
    }

    private fun commonEnvelopeText(
        value: Map<String, JSONValue>,
        toolName: String?,
        includeObjectFallback: Boolean = true,
    ): String? {
        val preferredKeys = listOf(
            "result",
            "results",
            "preview",
            "content",
            "text",
            "message",
            "summary",
            "data",
            "items",
        )
        for (key in preferredKeys) {
            val item = value[key] ?: continue
            readableEnvelopeValue(item, toolName)?.let { return it }
        }
        errorText(value["error"])?.let { return "Error: $it" }
        if (!includeObjectFallback) return null
        return if (value.isEmpty()) null else JSONValue.objectOf(value).toolDisplayText()
    }

    private fun readableEnvelopeValue(value: JSONValue, toolName: String?): String? {
        value.string?.let { string ->
            val normalized = normalizedDisplayString(string)
            val parsed = parsedJSONValue(normalized)
            if (parsed != null) {
                readableResultText(parsed, toolName)?.let { return it }
            }
            return nonEmpty(normalized)
        }
        value.number?.let { return value.inlineDisplayText() }
        value.boolean?.let { return value.inlineDisplayText() }
        value.objectValue?.let {
            return readableResultText(value, toolName) ?: value.toolDisplayText()
        }
        value.arrayValue?.let {
            return readableTextArrayValue(value) ?: value.toolDisplayText()
        }
        return null
    }

    private fun readableTextArrayValue(value: JSONValue): String? {
        val values = value.arrayValue ?: return null
        if (values.isEmpty()) return null
        val textValues = ArrayList<String>(values.size)
        for (item in values) {
            val objectValue = item.objectValue ?: return null
            val text = nonEmptyString(objectValue["text"])
                ?: nonEmptyString(objectValue["content"])
                ?: nonEmptyString(objectValue["message"])
                ?: return null
            textValues += text
        }
        return nonEmpty(textValues.joinToString("\n"))
    }

    private fun firstNonEmptyString(
        keys: List<String>,
        value: Map<String, JSONValue>,
    ): String? {
        for (key in keys) nonEmptyString(value[key])?.let { return it }
        return null
    }

    private fun nonEmptyString(value: JSONValue?): String? {
        value ?: return null
        value.string?.let { return nonEmpty(normalizedDisplayString(it)) }
        value.number?.let { return value.inlineDisplayText() }
        value.boolean?.let { return value.inlineDisplayText() }
        if (value.objectValue != null || value.arrayValue != null) return nonEmpty(value.toolDisplayText())
        return null
    }

    private fun errorText(value: JSONValue?): String? =
        if (value == null || value.isNull) null else nonEmptyString(value)

    private fun exitCode(value: JSONValue?): Long? {
        value ?: return null
        value.number?.let { number ->
            if (
                !number.isFinite() ||
                number < Long.MIN_VALUE.toDouble() ||
                number >= Long.MAX_VALUE.toDouble()
            ) {
                return null
            }
            return number.toLong()
        }
        value.string?.let { return it.trim().toLongOrNull() }
        return null
    }

    private fun parsedJSONValue(text: String): JSONValue? {
        for (candidate in jsonCandidates(text)) {
            val value = decodedJSONValue(candidate) ?: continue
            return unwrappedNestedJSONValue(value)
        }
        return null
    }

    private fun jsonCandidates(text: String): List<String> {
        val trimmed = text.trim()
        val candidates = mutableListOf(trimmed)
        if (trimmed.contains("\\\"")) {
            val normalizedQuotes = trimmed.replace("\\\"", "\"")
            if (normalizedQuotes != trimmed) candidates += normalizedQuotes
        }
        decodedJSONValue(trimmed)?.string?.let(candidates::add)
        return candidates
    }

    private fun decodedJSONValue(text: String): JSONValue? = runCatching {
        JSONValue(Json.parseToJsonElement(text))
    }.getOrNull()

    private fun unwrappedNestedJSONValue(value: JSONValue): JSONValue {
        var current = value
        repeat(3) {
            val string = current.string ?: return current
            current = decodedJSONValue(string.trim()) ?: return current
        }
        return current
    }

    private fun isTerminalTool(toolName: String?): Boolean {
        val name = toolName?.lowercase(Locale.ROOT).orEmpty()
        return TERMINAL_TOOL_NAMES.any { name.contains(it) }
    }

    private fun nonEmpty(value: String?): String? = value?.trim()?.takeIf { it.isNotEmpty() }

    internal fun normalizedDisplayString(value: String): String = value
        .replace("\r\n", "\n")
        .replace("\\\\r\\\\n", "\n")
        .replace("\\r\\n", "\n")
        .replace("\\\\n", "\n")
        .replace("\\n", "\n")
        .replace("\\\\t", "\t")
        .replace("\\t", "\t")

    private fun JSONValue.inlineDisplayText(): String? {
        string?.let {
            val text = normalizedDisplayString(it)
            return text.takeUnless { value -> value.contains('\n') }
        }
        number?.let { return stringValue }
        boolean?.let { return if (it) "true" else "false" }
        objectValue?.let { return if (it.isEmpty()) "{}" else null }
        arrayValue?.let { return if (it.isEmpty()) "[]" else null }
        return if (isNull) "null" else null
    }

    private fun JSONValue.toolDisplayText(): String = multilineDisplayText(indentation = 0)

    private fun JSONValue.multilineDisplayText(indentation: Int): String {
        val indent = " ".repeat(indentation)
        inlineDisplayText()?.let { return indent + it }

        objectValue?.let { objectValue ->
            return objectValue.toSortedMap().entries.joinToString("\n") { (key, value) ->
                val inline = value.inlineDisplayText()
                if (inline != null) {
                    "$indent$key: $inline"
                } else {
                    "$indent$key:\n${value.multilineDisplayText(indentation + 2)}"
                }
            }
        }
        arrayValue?.let { arrayValue ->
            return arrayValue.joinToString("\n") { item ->
                val inline = item.inlineDisplayText()
                if (inline != null) {
                    "$indent- $inline"
                } else {
                    "$indent-\n${item.multilineDisplayText(indentation + 2)}"
                }
            }
        }
        string?.let { string ->
            return normalizedDisplayString(string)
                .split('\n')
                .joinToString("\n") { indent + it }
        }
        return indent + inlineDisplayText().orEmpty()
    }

    private fun formatDuration(seconds: Double): String =
        String.format(Locale.US, "%.1fs", seconds)

    private val TERMINAL_TOOL_NAMES = listOf("terminal", "shell", "bash", "zsh", "command", "exec")
}
