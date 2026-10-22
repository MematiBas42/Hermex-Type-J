package io.github.jcartu.hermex.model.hermes

import io.github.jcartu.hermex.net.hermes.BotApprovalChoice
import io.github.jcartu.hermex.ui.feature.bots.RoomTestFixture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BotRoomEventTest {
    @Test
    fun `acknowledgement inserts exact event without advancing replay cursor`() {
        var log = BotRoomLog().beginning(0)
        log = log.acknowledge(RoomTestFixture.event(3, threadID = "thread:one"))
        assertEquals(0, log.cursor)

        log = log.applyPage(
            RoomTestFixture.page(
                (1..3).map { RoomTestFixture.event(it, threadID = "thread:one") },
                cursor = 3,
            ),
        )
        assertEquals(3, log.cursor)
        assertEquals(listOf(1, 2, 3), log.events.map { it.seq })
    }

    @Test
    fun `duplicate and unknown events still advance cursor without entering transcript`() {
        var log = BotRoomLog().beginning(403)
        assertEquals(203, log.cursor)
        log = log.applyPage(RoomTestFixture.page(listOf(RoomTestFixture.event(204), RoomTestFixture.event(205, "future.event")), 205))
        log = log.applyPage(
            RoomTestFixture.page(
                listOf(RoomTestFixture.event(204), RoomTestFixture.event(205), RoomTestFixture.event(206, "room.activity")),
                206,
            ),
        )
        assertEquals(listOf(204), log.events.map { it.seq })
        assertEquals(206, log.cursor)
    }

    @Test
    fun `thread projection preserves roots replies and leaves invalid ids unthreaded`() {
        val rows = listOf(
            RoomTestFixture.event(1, threadID = "thread:one"),
            RoomTestFixture.event(2, threadID = "thread:two"),
            RoomTestFixture.event(3, kind = "message.member", threadID = "thread:one"),
            RoomTestFixture.event(4, threadID = "invalid thread"),
        ).mapNotNull(BotRoomEvent::fromJsonOrNull)
        val threads = BotRoomThread.group(rows, hasEarlier = false)
        assertEquals(listOf("thread:one", "thread:two"), threads.map { it.id })
        assertEquals(listOf(1, 3), threads.first().events.map { it.seq })
        assertEquals(1, threads.first().root?.seq)
        assertEquals(1, threads.first().replyCount)
        assertNull(rows.last().threadID)
        assertNull(BotRoomThread.group(rows.drop(2), hasEarlier = true).first().root)
    }

    @Test
    fun `transcript window starts at fifty rows and materializes a search sequence`() {
        val events = (1..300).mapNotNull { BotRoomEvent.fromJsonOrNull(RoomTestFixture.event(it)) }
        var window = BotRoomTranscriptWindow()
        assertEquals(250, window.start(events))
        window = window.seed(events, live = true)
        assertEquals(250, window.start(events))
        assertEquals(39, window.start(events, keepingSequence = 40))
        window = window.reveal(40, events)
        assertEquals(39, window.start(events))
        window = requireNotNull(window.showEarlier(events))
        assertEquals(0, window.start(events))
    }

    @Test
    fun `unknown action is attention only and malformed room approval is deny only`() {
        val unknown = BotRoomAction.from(BotJSON.objectOf("kind" to BotJSON.string("future.action")))
        assertFalse(unknown.isAnswerable)
        assertNull(unknown.call("fixture-room"))

        val malformed = BotRoomAction.from(
            BotJSON.objectOf(
                "kind" to BotJSON.string("approval"),
                "member_id" to BotJSON.string("chief"),
                "task_id" to BotJSON.string("task:1"),
                "execution_generation" to BotJSON.number(1),
                "request_id" to BotJSON.string("approval:1"),
                "approval" to BotJSON.objectOf(
                    "command" to BotJSON.string("echo hello"),
                    "choices" to BotJSON.string("once"),
                ),
            ),
        )
        assertEquals(listOf(BotApprovalChoice.DENY), malformed.approval?.choices)
        assertNull(malformed.call("fixture-room", BotApprovalChoice.ONCE))
        assertTrue(malformed.call("fixture-room", BotApprovalChoice.DENY) != null)
    }

    @Test
    fun `room approval drops permanent choices and accepts once or deny only`() {
        val action = BotRoomAction.from(RoomTestFixture.approval)
        assertEquals(listOf(BotApprovalChoice.ONCE, BotApprovalChoice.DENY), action.approval?.choices)
        assertNull(action.call("fixture-room", BotApprovalChoice.SESSION))
        assertNull(action.call("fixture-room", BotApprovalChoice.ALWAYS))
        assertTrue(action.call("fixture-room", BotApprovalChoice.ONCE) != null)
        assertTrue(action.call("fixture-room", BotApprovalChoice.DENY) != null)
    }
}
