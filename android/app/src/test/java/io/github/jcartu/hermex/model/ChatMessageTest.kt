package io.github.jcartu.hermex.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatMessageTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
        encodeDefaults = false
    }

    private fun decode(raw: String): ChatMessage =
        json.decodeFromString(ChatMessage.serializer(), raw)

    @Test
    fun `decodes full snake_case wire message and ignores unknown fields`() {
        val message = decode(
            """
            {
              "role": "assistant",
              "content": "Hello there",
              "timestamp": 1723900000.25,
              "message_id": "msg-1",
              "name": "hermes",
              "tool_call_id": "call-1",
              "tool_use_id": "use-1",
              "tool_calls": [{"id": "call-1", "function": {"name": "bash"}}],
              "reasoning": "thinking...",
              "_turn_tps": 41.5,
              "some_future_field": {"nested": true},
              "another_unknown": [1, 2, 3]
            }
            """.trimIndent(),
        )

        assertEquals("assistant", message.role)
        assertEquals("Hello there", message.content)
        assertEquals(1723900000.25, message.timestamp!!, 0.0)
        assertEquals("msg-1", message.messageId)
        assertEquals("hermes", message.name)
        assertEquals("call-1", message.toolCallId)
        assertEquals("use-1", message.toolUseId)
        assertEquals(1, message.toolCalls!!.size)
        assertEquals("call-1", message.toolCalls!![0]["id"]?.stringValue)
        assertEquals("thinking...", message.reasoning)
        assertEquals(41.5, message.turnTps!!, 0.0)
        assertNull(message.contentParts)
        assertEquals("msg-1", message.id)
    }

    @Test
    fun `_ts wins over timestamp`() {
        val message = decode("""{"role":"user","content":"x","_ts":5.0,"timestamp":9.0}""")
        assertEquals(5.0, message.timestamp!!, 0.0)
    }

    @Test
    fun `content parts array falls back to joined text content`() {
        val message = decode(
            """
            {
              "role": "assistant",
              "content": [
                {"type": "text", "text": "Hello "},
                {"type": "tool_use", "id": "t1", "name": "bash", "input": {}},
                "raw tail"
              ],
              "message_id": "msg-2"
            }
            """.trimIndent(),
        )

        assertEquals("Hello raw tail", message.content)
        assertEquals(3, message.contentParts!!.size)
        assertEquals("tool_use", message.contentParts!![1]["type"]?.stringValue)
    }

    @Test
    fun `content parts with no text yield null content`() {
        val message = decode(
            """{"role":"assistant","content":[{"type":"tool_use","id":"t1"}]}""",
        )
        assertNull(message.content)
        assertEquals(1, message.contentParts!!.size)
    }

    @Test
    fun `object content becomes compact json string`() {
        val message = decode("""{"role":"assistant","content":{"kind":"card","n":1}}""")
        assertEquals("""{"kind":"card","n":1}""", message.content)
        assertNull(message.contentParts)
    }

    @Test
    fun `lossy scalars coerce role and timestamp`() {
        val message = decode("""{"role":123,"content":456,"timestamp":"1700000000.5"}""")
        assertEquals("123", message.role)
        assertEquals("456", message.content)
        assertEquals(1700000000.5, message.timestamp!!, 0.0)
    }

    @Test
    fun `bare string attachment decodes as name`() {
        val message = decode(
            """{"role":"user","content":"hi","attachments":["voice-note.m4a"]}""",
        )
        assertEquals(listOf(MessageAttachment(name = "voice-note.m4a")), message.attachments)
    }

    @Test
    fun `one malformed attachment does not drop the rest`() {
        val message = decode(
            """
            {
              "role": "user",
              "content": "hi",
              "attachments": [
                {"name": "a.png", "path": "/up/a.png", "mime": "image/png", "size": 10, "is_image": true},
                42,
                {"filename": "b.txt"}
              ]
            }
            """.trimIndent(),
        )

        val attachments = message.attachments!!
        assertEquals(2, attachments.size)
        assertEquals("a.png", attachments[0].name)
        assertEquals("/up/a.png", attachments[0].path)
        assertEquals(true, attachments[0].isImage)
        // `filename` alternate key maps onto name.
        assertEquals("b.txt", attachments[1].name)
    }

    @Test
    fun `attached files marker infers attachments when none are present`() {
        val message = decode(
            """{"role":"user","content":"look at this\n\n[Attached files: /srv/uploads/photo.png, notes.txt]"}""",
        )

        val attachments = message.attachments!!
        assertEquals(2, attachments.size)
        assertEquals("photo.png", attachments[0].name)
        assertEquals("/srv/uploads/photo.png", attachments[0].path)
        assertEquals(true, attachments[0].isImage)
        assertEquals("notes.txt", attachments[1].name)
        assertNull(attachments[1].path)
        assertEquals(false, attachments[1].isImage)
    }

    @Test
    fun `marker enriches decoded attachments that lack a path`() {
        val message = decode(
            """
            {
              "role": "user",
              "content": "here\n\n[Attached files: /srv/uploads/photo.png]",
              "attachments": [{"name": "photo.png", "mime": "image/png"}]
            }
            """.trimIndent(),
        )

        val attachment = message.attachments!!.single()
        assertEquals("photo.png", attachment.name)
        assertEquals("/srv/uploads/photo.png", attachment.path)
        assertEquals("image/png", attachment.mime)
        assertEquals(true, attachment.isImage)
    }

    @Test
    fun `contentWithoutAttachedFilesMarker strips the marker suffix`() {
        assertEquals(
            "look at this",
            MessageAttachment.contentWithoutAttachedFilesMarker(
                "look at this\n\n[Attached files: /srv/uploads/photo.png]",
            ),
        )
        // Non-whitespace after the bracket means no marker.
        assertEquals(
            "x [Attached files: a.png] trailing",
            MessageAttachment.contentWithoutAttachedFilesMarker("x [Attached files: a.png] trailing"),
        )
    }

    @Test
    fun `classifier treats tool-result-only user message as non-boundary`() {
        val visibleUser = ChatMessage(role = "user", content = "question", messageId = "u1")
        val toolResultOnly = ChatMessage(role = "user", content = "   ", messageId = "u2")
        val assistant = ChatMessage(role = "assistant", content = "answer", messageId = "a1")

        assertTrue(TranscriptTurnClassifier.isUserTurnBoundary(visibleUser))
        assertFalse(TranscriptTurnClassifier.isUserTurnBoundary(toolResultOnly))
        assertTrue(TranscriptTurnClassifier.isToolResultOnlyMessage(toolResultOnly))

        val keys = TranscriptTurnClassifier.assistantTurnKeysByAnchorID(
            listOf(visibleUser, assistant, toolResultOnly),
        )
        assertEquals(mapOf("a1" to "turn:user:0"), keys)
    }

    @Test
    fun `classifier anchor id falls back to raw index with offset`() {
        val message = ChatMessage(role = "assistant", content = "x")
        assertEquals("raw:12", TranscriptTurnClassifier.anchorID(message, index = 2, messageOffset = 10))

        val current = TranscriptTurnClassifier.currentTurnAssistantAnchorIDs(
            listOf(
                ChatMessage(role = "user", content = "q", messageId = "u1"),
                ChatMessage(role = "assistant", content = "a"),
                ChatMessage(role = "assistant", content = "b", messageId = "a2"),
            ),
        )
        assertEquals(listOf("raw:1", "a2"), current)
    }
}
