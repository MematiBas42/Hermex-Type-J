package io.github.jcartu.hermex.data

import io.github.jcartu.hermex.model.ChatMessage
import io.github.jcartu.hermex.model.ContextWindowSnapshot
import io.github.jcartu.hermex.model.JSONValue
import io.github.jcartu.hermex.model.ToolCall
import io.github.jcartu.hermex.model.ToolCallGroup
import io.github.jcartu.hermex.ui.feature.chat.ChatViewModel
import io.github.jcartu.hermex.ui.feature.chat.ReasoningGroup
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class ActiveChatStreamSnapshotStoreTest {
    @get:Rule val temp = TemporaryFolder()
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; explicitNulls = false }
    private fun snapshot() = ActiveChatStreamSnapshot(
        messages = listOf(ChatMessage(role = "user", messageId = "u", content = "hello"),
            ChatMessage(role = "assistant", messageId = "live", content = "Hello world")),
        messagesOffset = 15, displayTitle = "Chat",
        completedToolCallGroups = listOf(ToolCallGroup("group", "prior", listOf(
            ToolCall("done", name = "read", preview = "done", args = null, isCompleted = true, startedAt = 1.0)))),
        completedReasoningGroups = listOf(ReasoningGroup("reason", "prior", "Earlier thinking")),
        liveToolCalls = listOf(ToolCall("tool", "disclosure", "bash", "running", mapOf("cmd" to JSONValue(JsonPrimitive("pwd"))),
            duration = 2.5, isError = false, isCompleted = false, startedAt = 3.0)),
        liveReasoningText = "Thinking now", activeStreamLastEventId = "run:27",
        streamingAssistantMessageId = "live", toolCallAnchorMessageId = "live", reasoningAnchorMessageId = "live",
        contextWindowSnapshot = ContextWindowSnapshot(inputTokens = 23), pinnedLocalNotices = listOf("Queued"),
    )

    @Test fun `a new store restores all reasoning tools anchors and cursor from disk`() = runTest {
        val first = ActiveChatStreamSnapshotStore(temp.root, json, backgroundScope)
        first.save(snapshot(), "https://a", "session", "run")
        first.flush()
        val restored = ActiveChatStreamSnapshotStore(temp.root, json, backgroundScope)
        assertEquals(snapshot(), restored.snapshot("https://a", "session", "run"))
        assertNull(restored.snapshot("https://b", "session", "run"))
        assertNull(restored.snapshot("https://a", "session", "another-run"))
        restored.remove("https://a", "session", "run")
        restored.flush()
        assertNull(ActiveChatStreamSnapshotStore(temp.root, json, backgroundScope).snapshot("https://a", "session", "run"))
    }

    @Test fun `missing optional fields and an unknown future field preserve valid records`() = runTest {
        File(temp.root, "active-stream-snapshots.json").writeText("""[
            {"server":"a","sessionId":"s","streamId":"r","savedAt":${System.currentTimeMillis()},"future":true},
            {"sessionId":"bad"}
        ]""")
        val restored = ActiveChatStreamSnapshotStore(temp.root, json, backgroundScope).snapshot("a", "s", "r")!!
        assertEquals(emptyList<ChatMessage>(), restored.messages)
        assertEquals("", restored.liveReasoningText)
        assertNull(restored.activeStreamLastEventId)
    }

    @Test fun `snapshot merge keeps longer prefix and server identity across steer echo`() {
        val loaded = listOf(ChatMessage(role = "user", messageId = "u", content = "hello"),
            ChatMessage(role = "assistant", messageId = "server", content = "Hello"),
            ChatMessage(role = "user", messageId = "steer", content = "hint", displayKind = "steer"))
        val merged = ChatViewModel.mergingLoadedMessages(loaded, snapshot())
        assertEquals(3, merged.messages.size)
        assertEquals("server", merged.streamingAssistantMessageId)
        assertEquals("Hello world", merged.messages[1].content)
        assertFalse(merged.usedSnapshotMessagesOffset)
    }

    @Test fun `empty server page restores snapshot pagination while unrelated content stays authoritative`() {
        val all = ChatViewModel.mergingLoadedMessages(emptyList(), snapshot())
        assertEquals(snapshot().messages, all.messages)
        assertTrue(all.usedSnapshotMessagesOffset)
        val authoritative = ChatViewModel.mergingLoadedMessages(listOf(ChatMessage(role = "assistant", messageId = "server", content = "Other reply")), snapshot())
        assertEquals("Other reply", authoritative.messages.single().content)
    }
}
