package io.github.jcartu.hermex.ui.feature.chat

import io.github.jcartu.hermex.model.JSONValue
import io.github.jcartu.hermex.model.ToolCall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolCallDisplayFormatterTest {

    @Test
    fun `missing and whitespace-only names use the model display fallback`() {
        val missing = toolCall(name = null)
        val blank = toolCall(name = "  \n")

        assertEquals("Tool", ToolCallDisplayFormatter.displayName(missing))
        assertEquals("Tool", ToolCallDisplayFormatter.displayName(blank))
    }

    @Test
    fun `argument rows are sorted and nested values are readable`() {
        val rows = ToolCallDisplayFormatter.argumentRows(
            mapOf(
                "query" to JSONValue.string("Hermex"),
                "input" to JSONValue.objectOf(
                    mapOf(
                        "path" to JSONValue.string("Sources"),
                        "options" to JSONValue.objectOf(
                            mapOf("recursive" to JSONValue.bool(true)),
                        ),
                        "patterns" to JSONValue.array(
                            listOf(JSONValue.string("*.swift"), JSONValue.string("*.md")),
                        ),
                    ),
                ),
            ),
        )

        assertEquals(listOf("input", "query"), rows.map { it.key })
        assertEquals(
            """
                options:
                  recursive: true
                path: Sources
                patterns:
                  - *.swift
                  - *.md
            """.trimIndent(),
            rows.first().value,
        )
    }

    @Test
    fun `terminal JSON envelope becomes normalized monospaced output`() {
        val display = ToolCallDisplayFormatter.resultDisplay(
            preview = """{"output":"line one\nline two\n","exit_code":0,"error":null}""",
            toolName = "terminal",
        )

        assertEquals("Result", display?.title)
        assertEquals("line one\nline two", display?.text)
        assertEquals(true, display?.isMonospaced)
    }

    @Test
    fun `escaped terminal JSON envelope is decoded`() {
        val display = ToolCallDisplayFormatter.resultDisplay(
            preview = """{\"output\":\"pwd\n\",\"exit_code\":0,\"error\":null}""",
            toolName = "functions_terminal",
        )

        assertEquals("pwd", display?.text)
        assertEquals(true, display?.isMonospaced)
    }

    @Test
    fun `out of range exit code cannot overflow formatting`() {
        val display = ToolCallDisplayFormatter.resultDisplay(
            preview = """{"output":"done\n","exit_code":1e300,"error":null}""",
            toolName = "terminal",
        )

        assertEquals("done", display?.text)
    }

    @Test
    fun `terminal errors and nonzero exit codes are included`() {
        val display = ToolCallDisplayFormatter.resultDisplay(
            preview = """{"stdout":"partial","stderr":"warning","error":"boom","exitCode":"7"}""",
            toolName = "shell_exec",
        )

        assertEquals("partial\nwarning\nError: boom\nExit code: 7", display?.text)
        assertEquals(true, display?.isMonospaced)
    }

    @Test
    fun `malformed preview is preserved without invented truncation`() {
        val preview = """{"output": "unterminated"""

        val display = ToolCallDisplayFormatter.resultDisplay(preview, "web_search")

        assertEquals(preview, display?.text)
        assertFalse(display?.isMonospaced ?: true)
    }

    @Test
    fun `structured non-terminal results prefer their readable envelope`() {
        val display = ToolCallDisplayFormatter.resultDisplay(
            preview = """{"results":[{"title":"Hermes WebUI","url":"https://example.com","snippet":"Agent UI"}],"exit_code":0,"error":null}""",
            toolName = "web_search",
        )
        val text = display?.text.orEmpty()

        assertTrue(text.contains("title: Hermes WebUI"))
        assertTrue(text.contains("url: https://example.com"))
        assertFalse(text.contains("Exit code: 0"))
        assertFalse(text.contains("{\"title\""))
        assertEquals(true, display?.isMonospaced)
    }

    @Test
    fun `text item arrays are joined one per line`() {
        val display = ToolCallDisplayFormatter.resultDisplay(
            preview = """{"content":[{"text":"first"},{"message":"second"}]}""",
            toolName = "read",
        )

        assertEquals("first\nsecond", display?.text)
    }

    @Test
    fun `empty preview has no result section`() {
        assertNull(ToolCallDisplayFormatter.resultDisplay(" \n ", "terminal"))
        assertNull(ToolCallDisplayFormatter.content(toolCall(preview = null)).result)
    }

    @Test
    fun `status display covers running completed duration and failure precedence`() {
        val running = ToolCallDisplayFormatter.statusDisplay(toolCall(isCompleted = false))
        val completed = ToolCallDisplayFormatter.statusDisplay(
            toolCall(isCompleted = true, duration = 1.24),
        )
        val failed = ToolCallDisplayFormatter.statusDisplay(
            toolCall(isCompleted = true, duration = 0.8, isError = true),
        )

        assertEquals(ToolCallVisualStatus.RUNNING, running.status)
        assertEquals("Running", running.collapsedText)
        assertEquals("Running", running.detailText)
        assertEquals(ToolCallVisualStatus.COMPLETED, completed.status)
        assertNull(completed.collapsedText)
        assertEquals("Completed in 1.2s", completed.detailText)
        assertEquals(ToolCallVisualStatus.FAILED, failed.status)
        assertEquals("Failed", failed.collapsedText)
        assertEquals("Failed", failed.detailText)
    }

    private fun toolCall(
        name: String? = "terminal",
        preview: String? = null,
        args: Map<String, JSONValue>? = null,
        duration: Double? = null,
        isError: Boolean? = null,
        isCompleted: Boolean = false,
    ): ToolCall = ToolCall(
        id = "fixture",
        name = name,
        preview = preview,
        args = args,
        duration = duration,
        isError = isError,
        isCompleted = isCompleted,
        startedAt = 0.0,
    )
}
