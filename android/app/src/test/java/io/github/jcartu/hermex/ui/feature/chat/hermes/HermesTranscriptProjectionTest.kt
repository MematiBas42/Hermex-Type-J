package io.github.jcartu.hermex.ui.feature.chat.hermes

import io.github.jcartu.hermex.model.ChatMessage
import io.github.jcartu.hermex.model.JSONValue
import io.github.jcartu.hermex.model.MessageAttachment
import io.github.jcartu.hermex.model.hermes.BotJSON
import io.github.jcartu.hermex.model.hermes.HermesBranchCount
import io.github.jcartu.hermex.model.hermes.HermesTranscriptHistory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression coverage ported from `HermesTranscriptPagingTests.swift` and `HermesBranchDuplicateTests.swift`. */
class HermesTranscriptProjectionTest {
    @Test
    fun `history pages join by position and repeated row ids appear once`() {
        var history = HermesTranscriptHistory().mergingNewest(
            fresh = (101..200).map(::alternating),
            reachedStart = false,
        )
        assertEquals(100, history.nextOffset)
        assertTrue(history.hasOlder)

        val first = history.prependingOlder((3..102).map(::alternating))
        history = first.history
        assertTrue(first.added)
        assertEquals(198, history.nextOffset)
        assertEquals((3..200).toList(), history.rows.mapNotNull { HermesTranscriptHistory.id(it) })

        val second = history.prependingOlder(listOf(alternating(1), alternating(2), alternating(2)))
        history = second.history
        assertTrue(second.added)
        assertFalse(history.hasOlder)
        assertEquals((1..200).toList(), history.rows.mapNotNull { HermesTranscriptHistory.id(it) })
    }

    @Test
    fun `history and projection preserve compaction display order rather than sorting ids`() {
        val summary = "[CONTEXT COMPACTION — REFERENCE ONLY] Earlier turns were compacted into the summary below.\n" +
            "## Historical Task Snapshot\nThe user asked for the logs.\n\n" +
            "--- END OF CONTEXT SUMMARY — respond to the message below, not the summary above ---"
        val rows = listOf(
            row(29, "user", "First question"),
            row(4, "assistant", "First answer.", mapOf("active" to BotJSON.number(0))),
            row(5, "user", "Second question", mapOf("active" to BotJSON.number(0))),
            row(6, "assistant", "Second answer.", mapOf("active" to BotJSON.number(0))),
            row(
                32,
                "assistant",
                summary,
                mapOf(
                    "display_kind" to BotJSON.string("hidden"),
                    "_compressed_summary" to BotJSON.bool(true),
                ),
            ),
            row(33, "user", "Third question"),
            row(34, "assistant", "Third answer."),
        )
        val history = HermesTranscriptHistory().mergingNewest(rows, reachedStart = true)
        assertEquals(listOf(29, 4, 5, 6, 32, 33, 34), history.rows.mapNotNull { HermesTranscriptHistory.id(it) })

        val projected = HermesTranscriptProjection.project(history.rows, root = "tip")
        assertEquals(listOf(29, 4, 5, 6, 33, 34), projected.messages.mapNotNull(ChatMessage::rowID))
        assertEquals(listOf(false, true, true, true, false, false), projected.messages.map(ChatMessage::isCompacted))
        assertEquals("tip/row-6", projected.compaction?.anchorMessageID)
        assertEquals(
            "## Historical Task Snapshot\nThe user asked for the logs.",
            projected.compaction?.referenceText,
        )
    }

    @Test
    fun `tool result pairs with declaration and reasoning anchors before the next message`() {
        val output = "{\"output\":\"a.txt\\nb.txt\",\"exit_code\":0}"
        val call = BotJSON.objectOf(
            "id" to BotJSON.string("call_2"),
            "type" to BotJSON.string("function"),
            "function" to BotJSON.objectOf(
                "name" to BotJSON.string("terminal"),
                "arguments" to BotJSON.string("{\"command\":\"ls\"}"),
            ),
        )
        val projected = HermesTranscriptProjection.project(
            rows = listOf(
                row(1, "user", "List the files"),
                row(
                    2,
                    "assistant",
                    "Let me check.",
                    mapOf(
                        "reasoning" to BotJSON.string("Look first."),
                        "tool_calls" to BotJSON.array(listOf(call)),
                    ),
                ),
                row(
                    3,
                    "tool",
                    output,
                    mapOf(
                        "tool_call_id" to BotJSON.string("call_2"),
                        "tool_name" to BotJSON.string("wrong fallback"),
                    ),
                ),
                row(4, "assistant", "Two files."),
            ),
            root = "tip",
        )

        assertEquals(listOf("List the files", "Let me check.", "Two files."), projected.messages.map(ChatMessage::content))
        assertEquals(1, projected.toolCallGroups.size)
        val toolGroup = projected.toolCallGroups.single()
        assertEquals("tip/row-4", toolGroup.anchorMessageID)
        assertEquals("tip/row-3/tools", toolGroup.id)
        val tool = toolGroup.toolCalls.single()
        assertEquals("terminal", tool.name)
        assertEquals(output, tool.preview)
        assertEquals(mapOf("command" to JSONValue.string("ls")), tool.args)
        assertTrue(tool.isCompleted)
        assertEquals("tip/row-2", projected.reasoningGroups.single().anchorMessageID)
        assertEquals("Look first.", projected.reasoningGroups.single().text)
    }

    @Test
    fun `hidden rows and gateway notices disappear while skills show their invocation`() {
        val skill = "[IMPORTANT: The user has invoked the \"demo-skill\" skill, indicating they want you to follow its " +
            "instructions. The full skill content is loaded below.]\n\n---\nname: demo-skill\n---\nSay hello.\n\n" +
            "[Skill directory: <skills>/demo-skill]\n\n" +
            "The user has provided the following instruction alongside the skill invocation: do   it\n\n" +
            "[Runtime note: Reply briefly.]"
        val projected = HermesTranscriptProjection.project(
            rows = listOf(
                row(1, "user", "Seed instructions", mapOf("display_kind" to BotJSON.string("hidden"))),
                row(2, "user", "[System: The model was switched to fast.]"),
                row(3, "user", skill),
                row(4, "assistant", "Hello."),
            ),
            root = "tip",
        )

        assertEquals(listOf(3, 4), projected.messages.mapNotNull(ChatMessage::rowID))
        assertEquals(listOf("/demo-skill do it", "Hello."), projected.messages.map(ChatMessage::content))
        assertEquals("skill_invocation", projected.messages.first().displayKind)
        assertNull(projected.messages.last().displayKind)
    }

    @Test
    fun `user projection hides only a valid trailing bot mention annotation`() {
        val original = " @research please  "
        val sent = original + mentionAnnotation()
        val projected = HermesTranscriptProjection.project(
            rows = listOf(
                row(1, "user", sent),
                row(2, "assistant", sent),
            ),
            root = "tip",
        )

        assertEquals(listOf(original, sent), projected.messages.map(ChatMessage::content))
    }

    @Test
    fun `displayed user references become chips before mention suffix is removed`() {
        val path = "/tmp/123E4567-E89B-12D3-A456-426614174000-plan.pdf"
        val content = "Read this" + mentionAnnotation() + "\n\n@file:`$path`\n\n" +
            "--- Attached Context ---\nprivate host content"
        val existing = MessageAttachment(name = "already.txt", path = "/tmp/already.txt")
        val shown = HermesTranscriptProjection.displayed(
            ChatMessage(role = "user", content = content, attachments = listOf(existing), messageId = "one"),
        )

        assertEquals("Read this", shown.content)
        assertEquals(2, shown.attachments?.size)
        assertEquals(existing, shown.attachments?.first())
        assertEquals("plan.pdf", shown.attachments?.last()?.name)
        assertEquals(path, shown.attachments?.last()?.path)
        assertEquals(false, shown.attachments?.last()?.isImage)
    }

    @Test
    fun `delegation completion with visible content opens a turn but steer does not`() {
        val prompt = ChatMessage(role = "user", content = "Run it")
        val steer = ChatMessage(role = "user", content = "Use tabs", displayKind = ChatMessage.STEER_DISPLAY_KIND)
        val delivery = ChatMessage(role = "delegation_completion", content = "All done")
        val reply = ChatMessage(role = "assistant", content = "Done")

        assertEquals(
            listOf(true, false, true, false),
            listOf(prompt, steer, delivery, reply).map(HermesTranscriptProjection::isTurnBoundary),
        )
    }

    @Test
    fun `branch count follows host visible history across tools and compaction`() {
        val imageContent = BotJSON.array(
            listOf(
                BotJSON.objectOf(
                    "type" to BotJSON.string("text"),
                    "text" to BotJSON.string(" "),
                ),
                BotJSON.objectOf(
                    "type" to BotJSON.string("image_url"),
                    "image_url" to BotJSON.objectOf("url" to BotJSON.string("a.png")),
                ),
            ),
        )
        val imageRow = BotJSON.objectOf(
            "id" to BotJSON.number(12),
            "role" to BotJSON.string("user"),
            "content" to imageContent,
        )
        val rows = listOf(
            row(7, "user", "Question 1"),
            row(8, "assistant", "Let me run a quick check."),
            row(9, "tool", "{\"output\":\"1\"}"),
            row(3, "assistant", "The command returned: 1.", mapOf("active" to BotJSON.number(0))),
            row(4, "user", "Question 2", mapOf("active" to BotJSON.number(0))),
            row(5, "assistant", " ", mapOf("active" to BotJSON.number(0))),
            row(
                10,
                "assistant",
                "[CONTEXT COMPACTION — REFERENCE ONLY] Earlier turns were compacted.",
                mapOf(
                    "display_kind" to BotJSON.string("hidden"),
                    "_compressed_summary" to BotJSON.bool(true),
                ),
            ),
            row(11, "user", "Question 3"),
            imageRow,
        )

        assertEquals(2, HermesBranchCount.count(through = 8, rows = rows))
        assertEquals(3, HermesBranchCount.count(through = 3, rows = rows))
        assertEquals(6, HermesBranchCount.count(through = 11, rows = rows))
        assertEquals(7, HermesBranchCount.count(through = 12, rows = rows))
        assertNull(HermesBranchCount.count(through = 9, rows = rows))
        assertNull(HermesBranchCount.count(through = 99, rows = rows))
    }

    private fun mentionAnnotation(): String =
        "\n\n[@mentions resolved from the Bot Mode roster — the user is referring to: " +
            "@research = agent profile \"Research\". If they want one of these agents contacted, compose your own message " +
            "and send it with your message_agent tool (agents on other connected machines are reachable too — the Desktop " +
            "relays it); never forward the user’s text verbatim. If this session has no message_agent tool, agent messaging " +
            "is unavailable here — say so.]"

    private fun alternating(id: Int): BotJSON =
        if (id % 2 == 0) row(id, "assistant", "Answer $id") else row(id, "user", "Question $id")

    private fun row(
        id: Int,
        role: String,
        content: String,
        extra: Map<String, BotJSON> = emptyMap(),
    ): BotJSON {
        val fields = mutableMapOf(
            "id" to BotJSON.number(id),
            "role" to BotJSON.string(role),
            "content" to BotJSON.string(content),
            "timestamp" to BotJSON.number(1_790_000_000.0 + id),
            "active" to BotJSON.number(1),
        )
        fields.putAll(extra)
        return BotJSON.objectOf(fields)
    }
}
