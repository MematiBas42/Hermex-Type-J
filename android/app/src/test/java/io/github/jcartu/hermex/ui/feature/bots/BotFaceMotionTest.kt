package io.github.jcartu.hermex.ui.feature.bots

import io.github.jcartu.hermex.model.hermes.BotAvatarExpression
import io.github.jcartu.hermex.model.hermes.BotTitleFace
import kotlin.math.ceil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class BotFaceMotionTest {
    @Test
    fun `reduce motion makes every face still`() {
        assertSame(BotFaceMotion.Still, BotFaceMotion.Idle.honoring(reduceMotion = true))
        assertSame(
            BotFaceMotion.Still,
            BotFaceMotion.Working(since = 123.0).honoring(reduceMotion = true),
        )
        assertSame(BotFaceMotion.Idle, BotFaceMotion.Idle.honoring(reduceMotion = false))
    }

    @Test
    fun `blink schedule emits only shut and open edges`() {
        val schedule = BotBlinkSchedule("fixture-bot")
        assertTrue(schedule.period in 3.0..5.0)
        assertTrue(schedule.phase in 0.0..5.0)

        val shut = schedule.phase + schedule.period * 3.0
        assertTrue(schedule.isShut(shut))
        assertTrue(schedule.isShut(shut + BotBlinkSchedule.SHUT_DURATION - 0.001))
        assertFalse(schedule.isShut(shut + BotBlinkSchedule.SHUT_DURATION))

        val entries = schedule.entries(shut, 5)
        assertEquals(shut, entries[0], EPSILON)
        assertEquals(shut + BotBlinkSchedule.SHUT_DURATION, entries[1], EPSILON)
        assertEquals(shut + schedule.period, entries[2], EPSILON)
        assertEquals(shut + schedule.period + BotBlinkSchedule.SHUT_DURATION, entries[3], EPSILON)
        assertEquals(shut + schedule.period * 2.0, entries[4], EPSILON)
    }

    @Test
    fun `starting during blink schedules its pending open edge`() {
        val schedule = BotBlinkSchedule("inside-blink")
        val shut = schedule.phase + schedule.period * 2.0
        val inside = shut + 0.08
        assertTrue(schedule.isShut(inside))
        assertEquals(shut + BotBlinkSchedule.SHUT_DURATION, schedule.nextEdge(inside), EPSILON)
    }

    @Test
    fun `working schedule settles after one beat and then only blinks`() {
        val blink = BotBlinkSchedule("worker")
        val start = 1_000.0
        val schedule = BotWorkingSchedule(start, blink)

        assertTrue(schedule.isContinuouslyAnimating(start))
        assertTrue(schedule.isContinuouslyAnimating(schedule.end - 0.001))
        assertFalse(schedule.isContinuouslyAnimating(schedule.end))
        assertNotEquals(BotFacePose.SettledWorking, schedule.pose(start + 1.0))
        val scheduled = schedule.entries(start, 452)
        assertEquals(450, scheduled.count { it < schedule.end })
        assertEquals(schedule.end, scheduled[450], EPSILON)

        val firstShutAfterEnd = blink.phase +
            ceil((schedule.end - blink.phase) / blink.period) * blink.period
        val shut = if (firstShutAfterEnd < schedule.end) firstShutAfterEnd + blink.period else firstShutAfterEnd
        val open = shut + BotBlinkSchedule.SHUT_DURATION
        assertEquals(
            BotFacePose.SettledWorking.copy(lid = BotFacePose.Blink.lid),
            schedule.pose(shut),
        )
        assertEquals(BotFacePose.SettledWorking, schedule.pose(open))

        val quietSample = open + 0.001
        val next = schedule.nextEntry(quietSample)
        assertEquals(blink.nextEdge(quietSample), next, EPSILON)
        assertTrue("settled work must not retain a frame-rate loop", next - quietSample > 2.7)
    }

    @Test
    fun `working schedule paints the settle boundary exactly once`() {
        val schedule = BotWorkingSchedule(50.0, BotBlinkSchedule("boundary"))
        val next = schedule.nextEntry(schedule.end - BotWorkingSchedule.FRAME_INTERVAL / 2.0)
        assertEquals(schedule.end, next, EPSILON)
        assertFalse(schedule.isContinuouslyAnimating(next))
    }

    @Test
    fun `an unstarted beat is already settled with no frame loop`() {
        val blink = BotBlinkSchedule("pending-approval")
        val schedule = BotWorkingSchedule(BotWorkingBeat().start, blink)
        val now = 1_000.0

        assertFalse(schedule.isContinuouslyAnimating(now))
        assertEquals(BotFacePose.SettledWorking.gazeX, schedule.pose(now).gazeX, EPSILON)
        assertEquals(blink.entries(now, 6), schedule.entries(now, 6))
    }

    @Test
    fun `unknown reconnect neither rearms nor restarts current beat`() {
        val beat = BotWorkingBeat()
        beat.observe(BotTurnState.RUNNING, at = 10.0)
        assertEquals(10.0, beat.start, EPSILON)

        beat.observe(BotTurnState.UNKNOWN, at = 20.0)
        beat.observe(BotTurnState.RUNNING, at = 30.0)
        assertEquals(10.0, beat.start, EPSILON)
    }

    @Test
    fun `attention idle and interruption rearm the next work beat`() {
        val transitions = listOf(
            BotTurnState.NEEDS_ATTENTION,
            BotTurnState.IDLE,
            BotTurnState.INTERRUPTED,
        )
        transitions.forEachIndexed { index, transition ->
            val beat = BotWorkingBeat()
            beat.observe(BotTurnState.RUNNING, at = 1.0)
            beat.observe(transition, at = 2.0)
            val resumed = 10.0 + index
            beat.observe(BotTurnState.RUNNING, at = resumed)
            assertEquals(resumed, beat.start, EPSILON)
        }
    }

    @Test
    fun `explicit foreground rearm starts a new beat but repeated running does not`() {
        val beat = BotWorkingBeat()
        beat.observe(BotTurnState.RUNNING, at = 5.0)
        beat.observe(BotTurnState.RUNNING, at = 6.0)
        assertEquals(5.0, beat.start, EPSILON)

        beat.rearm()
        beat.observe(BotTurnState.RUNNING, at = 7.0)
        assertEquals(7.0, beat.start, EPSILON)
    }

    @Test
    fun `waiting and failed title faces override pinned eyes`() {
        assertEquals(BotAvatarExpression.CURIOUS, BotTitleFace.WAITING.expression)
        assertEquals(BotAvatarExpression.SAD, BotTitleFace.FAILED.expression)
        assertNull(BotTitleFace.WORKING.expression)
        assertNull(BotTitleFace.RESTING.expression)
    }

    @Test
    fun `playful slots are deterministic bounded and keep spins rare`() {
        val first = BotPlayfulSchedule("playful")
        val second = BotPlayfulSchedule("playful")
        repeat(41) { index ->
            val entry = first.entry(index)
            assertEquals(entry, second.entry(index))
            assertTrue(entry.delay in 4.0..9.0)
            if (index > 0 && index % 10 == 0) {
                assertEquals(BotFaceBit.SPIN, entry.bit)
            } else {
                assertNotEquals(BotFaceBit.SPIN, entry.bit)
                assertNotEquals(BotFaceBit.GLANCE_DOWN, entry.bit)
            }
        }
    }

    @Test
    fun `every playful bit begins and ends at exact rest`() {
        BotFaceBit.entries.forEach { bit ->
            assertEquals(BotFacePose.Rest, bit.pose(0.0))
            assertEquals(BotFacePose.Rest, bit.pose(1.0))
            assertEquals(BotFacePose.Rest, bit.pose(-0.1))
            assertEquals(BotFacePose.Rest, bit.pose(1.1))
        }
        assertEquals(1.6, BotFaceBit.GLANCE_DOWN.duration, EPSILON)
        assertEquals(BotFacePose.Blink.lid, BotFaceBit.DOUBLE_BLINK.pose(0.1).lid, EPSILON)
        assertTrue(BotFaceBit.HOP.pose(0.5).lift > 0.0)
    }

    private companion object {
        const val EPSILON = 0.000_001
    }
}
