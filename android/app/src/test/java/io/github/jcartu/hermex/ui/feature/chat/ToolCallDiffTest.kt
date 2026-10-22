package io.github.jcartu.hermex.ui.feature.chat

import io.github.jcartu.hermex.model.JSONValue
import io.github.jcartu.hermex.model.ToolCall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolCallDiffTest {
    private val unified = """
        --- a/src/App.kt
        +++ b/src/App.kt
        @@ -1,3 +1,4 @@
         class App {
        -    val old = true
        +    val current = true
        +    val added = 1
         }
    """.trimIndent()

    @Test
    fun `result diff parses hunks and exact counts`() {
        val call = call(
            preview = """{"success":true,"diff":${jsonString(unified)}}""",
            args = mapOf("path" to JSONValue.string("src/App.kt")),
        )

        val diff = requireNotNull(ToolCallDiff.resolve(call))
        assertEquals(ToolCallDiff.Source.RESULT_DIFF, diff.source)
        assertEquals(ToolCallDiff.Counts(additions = 2, deletions = 1), diff.counts)
        assertTrue(diff.document.lines.any { it.kind == MarkdownDiffLine.Kind.HUNK })
        assertEquals(2, diff.document.lines.count { it.kind == MarkdownDiffLine.Kind.ADDED })
        assertEquals(1, diff.document.lines.count { it.kind == MarkdownDiffLine.Kind.REMOVED })
        assertTrue(diff.hidesResult)
        assertFalse(diff.isTruncated)
    }

    @Test
    fun `replacement args synthesize removed and added lines`() {
        val diff = requireNotNull(
            ToolCallDiff.resolve(
                call(
                    preview = null,
                    args = mapOf(
                        "path" to JSONValue.string("src/App.kt"),
                        "old_string" to JSONValue.string("one\ntwo"),
                        "new_string" to JSONValue.string("one\nthree\nfour"),
                    ),
                ),
            ),
        )

        assertEquals(ToolCallDiff.Source.ARGUMENT_REPLACEMENT, diff.source)
        assertEquals(ToolCallDiff.Counts(3, 2), diff.counts)
        assertEquals(
            listOf("-one", "-two", "+one", "+three", "+four"),
            diff.document.lines.map { it.text },
        )
        assertEquals(setOf("old_string", "new_string"), diff.consumedArgumentKeys)
    }

    @Test
    fun `write content reports additions with unknown removals`() {
        val diff = requireNotNull(
            ToolCallDiff.resolve(
                call(
                    name = "write_file",
                    preview = """{"bytes_written":12}""",
                    args = mapOf(
                        "path" to JSONValue.string("README.md"),
                        "content" to JSONValue.string("# Title\n\nBody\n"),
                    ),
                ),
            ),
        )

        assertEquals(ToolCallDiff.Counts(3, null), diff.counts)
        assertEquals(listOf("+# Title", "+", "+Body"), diff.document.lines.map { it.text })
    }

    @Test
    fun `malformed and failed input never invent a diff`() {
        assertNull(MarkdownDiffParser.parse("not a diff at all"))
        assertNull(
            ToolCallDiff.resolve(
                call(
                    preview = "Could not find match",
                    args = mapOf(
                        "old_string" to JSONValue.string("old"),
                        "new_string" to JSONValue.string("new"),
                    ),
                ),
            ),
        )
        assertNull(
            ToolCallDiff.resolve(
                call(
                    preview = null,
                    args = mapOf("patch" to JSONValue.string("garbled")),
                    isError = true,
                ),
            ),
        )
    }

    private fun call(
        name: String = "patch",
        preview: String?,
        args: Map<String, JSONValue>,
        isError: Boolean? = false,
    ) = ToolCall(
        id = "fixture",
        name = name,
        preview = preview,
        args = args,
        duration = 0.5,
        isError = isError,
        isCompleted = true,
        startedAt = 0.0,
    )

    private fun jsonString(value: String): String = buildString {
        append('"')
        value.forEach { character ->
            when (character) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(character)
            }
        }
        append('"')
    }
}
