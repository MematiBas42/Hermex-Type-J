package io.github.jcartu.hermex.ui.feature.chat

import io.github.jcartu.hermex.model.ChatMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptTurnFoldingTest {
    @Test
    fun `settled activity folds behind worked row using server duration`() {
        val messages = listOf(
            user("u1", 100.0),
            assistant("a1", "", 105.0),
            assistant("a2", "Done.", 172.0),
        )
        val folds = derive(
            messages = messages,
            activity = setOf("a1"),
            duration = { message -> if (message.messageId == "a2") 72.3 else null },
        )

        assertEquals(1, folds.folds.size)
        val fold = folds.folds.single()
        assertEquals("turn:user:0", fold.turnKey)
        assertEquals("row:1", fold.hostRenderID)
        assertEquals("Worked for 1m 12s", fold.label.title)
        val host = folds.rowState("row:1", emptySet())
        assertEquals(true, host?.hidesActivity)
        assertEquals(false, host?.hidesBubble)
        assertEquals(fold, host?.fold)
        assertNull(folds.rowState("row:2", emptySet())?.fold)
    }

    @Test
    fun `interim replies fold while first and last remain visible`() {
        val messages = listOf(
            user("u1", 100.0),
            assistant("a1", "Looking.", 105.0),
            assistant("a2", "Still looking.", 110.0),
            assistant("a3", "Done.", 120.0),
        )
        val folds = derive(messages, emptySet())

        assertFalse(folds.rowState("row:1", emptySet())?.hidesBubble ?: true)
        assertTrue(folds.rowState("row:2", emptySet())?.hidesBubble == true)
        assertFalse(folds.rowState("row:3", emptySet())?.hidesBubble ?: true)
        val expanded = folds.rowState("row:2", setOf("turn:user:0"))
        assertFalse(expanded?.hidesBubble ?: true)
        assertTrue(expanded?.isExpanded == true)
    }

    @Test
    fun `active turn does not fold and timestamp fallback computes elapsed`() {
        val messages = listOf(
            user("u1", 100.0),
            assistant("a1", "", 105.0),
            assistant("a2", "Done.", 130.0),
        )
        val settled = derive(messages, setOf("a1"))
        assertEquals("Worked for 30s", settled.folds.single().label.title)

        val active = derive(messages, setOf("a1"), isStreamActive = true)
        assertTrue(active.folds.isEmpty())
    }

    @Test
    fun `cancelled outcome changes label and supplies elapsed`() {
        val messages = listOf(
            user("u1", null),
            assistant("a1", "", null),
            assistant("a2", "Partial", null),
        )
        val folds = derive(
            messages,
            setOf("a1"),
            outcome = FoldRunOutcome(
                turnKey = "turn:user:0",
                startedAt = 10.0,
                endedAt = 18.9,
                ending = FoldRunOutcome.Ending.CANCELLED,
            ),
        )
        assertEquals("You stopped after 8s", folds.folds.single().label.title)
    }

    private fun derive(
        messages: List<ChatMessage>,
        activity: Set<String>,
        isStreamActive: Boolean = false,
        outcome: FoldRunOutcome? = null,
        duration: (ChatMessage) -> Double? = { null },
    ): TranscriptTurnFolds {
        val rows = messages.mapIndexed { index, message ->
            TranscriptMessage(index, "row:$index", message.messageId ?: "row:$index", message)
        }
        return TranscriptTurnFolds.derive(
            transcriptMessages = rows,
            messages = messages,
            messageOffset = 0,
            activityAnchorIDs = activity,
            rendersBubble = { !it.content.isNullOrBlank() },
            isStreamActive = isStreamActive,
            streamingAssistantMessageID = null,
            latestRunOutcome = outcome,
            durationForMessage = duration,
        )
    }

    private fun user(id: String, timestamp: Double?) =
        ChatMessage(role = "user", content = "Do it", timestamp = timestamp, messageId = id)

    private fun assistant(id: String, text: String, timestamp: Double?) =
        ChatMessage(role = "assistant", content = text, timestamp = timestamp, messageId = id)
}
