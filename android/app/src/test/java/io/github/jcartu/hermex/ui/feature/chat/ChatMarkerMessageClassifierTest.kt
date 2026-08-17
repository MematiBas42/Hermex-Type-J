package io.github.jcartu.hermex.ui.feature.chat

import io.github.jcartu.hermex.model.ChatMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatMarkerMessageClassifierTest {

    @Test
    fun `bracketed context compaction prefix is classified`() {
        val classification = ChatMarkerMessageClassifier.classification(
            message(role = "user", content = "[Context compaction] Summary of earlier conversation…"),
        )

        assertEquals(ChatMarkerMessageKind.CONTEXT_COMPACTION, classification?.kind)
        assertEquals("Context compaction", classification?.title)
        assertEquals(MarkerMessageIconHint.COMPACTION, classification?.iconHint)
        assertEquals("[Context compaction] Summary of earlier conversation…", classification?.detail)
    }

    @Test
    fun `unbracketed compaction prefix matches assistant messages`() {
        val classification = ChatMarkerMessageClassifier.classification(
            message(role = "assistant", content = "Context compaction: the prior history was summarized."),
        )

        assertEquals(ChatMarkerMessageKind.CONTEXT_COMPACTION, classification?.kind)
    }

    @Test
    fun `compaction matching is case insensitive and trims leading whitespace`() {
        val classification = ChatMarkerMessageClassifier.classification(
            message(role = "user", content = "  \n\t[CONTEXT COMPACTION] details  "),
        )

        assertEquals(ChatMarkerMessageKind.CONTEXT_COMPACTION, classification?.kind)
        assertEquals("[CONTEXT COMPACTION] details", classification?.detail)
    }

    @Test
    fun `preserved task list is user-only and marker line is removed from detail`() {
        val content =
            "[Your active task list was preserved across context compression]\n1. First task\n2. Second task"
        val classification = ChatMarkerMessageClassifier.classification(message("user", content))

        assertEquals(ChatMarkerMessageKind.PRESERVED_TASK_LIST, classification?.kind)
        assertEquals("Preserved task list", classification?.title)
        assertEquals(MarkerMessageIconHint.TASK_LIST, classification?.iconHint)
        assertEquals("1. First task\n2. Second task", classification?.detail)
        assertNull(ChatMarkerMessageClassifier.classification(message("assistant", content)))
    }

    @Test
    fun `preserved task marker tolerates case and surrounding whitespace`() {
        val classification = ChatMarkerMessageClassifier.classification(
            message(
                role = "user",
                content = "   [YOUR ACTIVE TASK LIST WAS PRESERVED ACROSS CONTEXT COMPRESSION] tasks  ",
            ),
        )

        assertEquals(ChatMarkerMessageKind.PRESERVED_TASK_LIST, classification?.kind)
        assertEquals("tasks", classification?.detail)
    }

    @Test
    fun `tool and missing roles never classify`() {
        val marker = "[context compaction] details"

        assertNull(ChatMarkerMessageClassifier.classification(message("tool", marker)))
        assertNull(ChatMarkerMessageClassifier.classification(message(null, marker)))
    }

    @Test
    fun `plain user and assistant messages do not classify`() {
        assertNull(
            ChatMarkerMessageClassifier.classification(
                message("user", "Hey, can you check the build?"),
            ),
        )
        assertNull(
            ChatMarkerMessageClassifier.classification(
                message("assistant", "The build is green."),
            ),
        )
        assertNull(
            ChatMarkerMessageClassifier.classification(
                message("user", "Context windows are interesting — explain compaction."),
            ),
        )
    }

    @Test
    fun `empty content does not classify`() {
        assertNull(ChatMarkerMessageClassifier.classification(message("user", null)))
    }

    @Test
    fun `context text helper mirrors literal prefix matching`() {
        assertEquals(true, ChatMarkerMessageClassifier.isContextCompactionText(" Context compaction: summary"))
        assertEquals(false, ChatMarkerMessageClassifier.isContextCompactionText("context windows"))
        assertEquals(false, ChatMarkerMessageClassifier.isContextCompactionText(null))
    }

    @Test
    fun `compression reference is synthesized rather than classified`() {
        val classification = ChatMarkerMessageClassifier.compressionReference("Earlier summary")

        assertEquals(ChatMarkerMessageKind.COMPRESSION_REFERENCE, classification.kind)
        assertEquals("Context compaction", classification.title)
        assertEquals("Earlier summary", classification.detail)
        assertEquals(MarkerMessageIconHint.REFERENCE, classification.iconHint)
    }

    private fun message(role: String?, content: String?): ChatMessage = ChatMessage(
        role = role,
        content = content,
        messageId = "test-id",
    )
}
