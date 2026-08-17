package io.github.jcartu.hermex.ui.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Decision-table coverage for the `ChatScrollPolicy.swift` port. Boundary
 * values mirror the Swift rules exactly: thresholds are inclusive for
 * near-bottom, exclusive for reading-older, and the cooldown pauses strictly
 * before its deadline.
 */
class ChatScrollPolicyTest {

    // MARK: bottomThreshold

    @Test
    fun `bottom threshold is 80 while idle`() {
        assertEquals(80f, ChatScrollPolicy.bottomThreshold(isStreaming = false))
    }

    @Test
    fun `bottom threshold loosens to 160 while streaming`() {
        assertEquals(160f, ChatScrollPolicy.bottomThreshold(isStreaming = true))
    }

    // MARK: isNearBottom

    @Test
    fun `near bottom is inclusive at the idle threshold`() {
        assertTrue(ChatScrollPolicy.isNearBottom(distanceFromBottom = 0f, isStreaming = false))
        assertTrue(ChatScrollPolicy.isNearBottom(distanceFromBottom = 80f, isStreaming = false))
        assertFalse(ChatScrollPolicy.isNearBottom(distanceFromBottom = 80.1f, isStreaming = false))
    }

    @Test
    fun `near bottom is inclusive at the streaming threshold`() {
        assertTrue(ChatScrollPolicy.isNearBottom(distanceFromBottom = 160f, isStreaming = true))
        assertFalse(ChatScrollPolicy.isNearBottom(distanceFromBottom = 160.1f, isStreaming = true))
    }

    @Test
    fun `distance between idle and streaming thresholds is near bottom only while streaming`() {
        assertFalse(ChatScrollPolicy.isNearBottom(distanceFromBottom = 120f, isStreaming = false))
        assertTrue(ChatScrollPolicy.isNearBottom(distanceFromBottom = 120f, isStreaming = true))
    }

    // MARK: shouldEnterReadingOlder

    @Test
    fun `reading older requires hysteresis past the idle threshold`() {
        // 80 + 64 = 144; strictly greater-than.
        assertFalse(ChatScrollPolicy.shouldEnterReadingOlder(distanceFromBottom = 144f, isStreaming = false))
        assertTrue(ChatScrollPolicy.shouldEnterReadingOlder(distanceFromBottom = 144.1f, isStreaming = false))
    }

    @Test
    fun `reading older requires hysteresis past the streaming threshold`() {
        // 160 + 64 = 224; strictly greater-than.
        assertFalse(ChatScrollPolicy.shouldEnterReadingOlder(distanceFromBottom = 224f, isStreaming = true))
        assertTrue(ChatScrollPolicy.shouldEnterReadingOlder(distanceFromBottom = 224.1f, isStreaming = true))
    }

    // MARK: cooldownDeadline

    @Test
    fun `cooldown deadline is 250ms after now`() {
        assertEquals(1_250L, ChatScrollPolicy.cooldownDeadline(nowMillis = 1_000L))
    }

    // MARK: isAutoScrollPaused

    @Test
    fun `interacting always pauses auto scroll`() {
        assertTrue(
            ChatScrollPolicy.isAutoScrollPaused(
                isUserInteracting = true,
                cooldownUntilMillis = null,
                nowMillis = 0L,
            ),
        )
        assertTrue(
            ChatScrollPolicy.isAutoScrollPaused(
                isUserInteracting = true,
                cooldownUntilMillis = 0L,
                nowMillis = 1_000L,
            ),
        )
    }

    @Test
    fun `no cooldown and no interaction never pauses`() {
        assertFalse(
            ChatScrollPolicy.isAutoScrollPaused(
                isUserInteracting = false,
                cooldownUntilMillis = null,
                nowMillis = 1_000L,
            ),
        )
    }

    @Test
    fun `pending cooldown pauses until its deadline`() {
        assertTrue(
            ChatScrollPolicy.isAutoScrollPaused(
                isUserInteracting = false,
                cooldownUntilMillis = 1_250L,
                nowMillis = 1_249L,
            ),
        )
        // now == deadline: no longer paused (Swift: now < cooldownUntil).
        assertFalse(
            ChatScrollPolicy.isAutoScrollPaused(
                isUserInteracting = false,
                cooldownUntilMillis = 1_250L,
                nowMillis = 1_250L,
            ),
        )
        assertFalse(
            ChatScrollPolicy.isAutoScrollPaused(
                isUserInteracting = false,
                cooldownUntilMillis = 1_250L,
                nowMillis = 2_000L,
            ),
        )
    }

    // MARK: resolveFollowLatest

    @Test
    fun `returning near the bottom re-engages follow regardless of interaction`() {
        assertTrue(ChatScrollPolicy.resolveFollowLatest(isNearBottom = true, isUserInteracting = false, current = false))
        assertTrue(ChatScrollPolicy.resolveFollowLatest(isNearBottom = true, isUserInteracting = true, current = false))
        assertTrue(ChatScrollPolicy.resolveFollowLatest(isNearBottom = true, isUserInteracting = true, current = true))
    }

    @Test
    fun `user scrolling away releases follow`() {
        assertFalse(ChatScrollPolicy.resolveFollowLatest(isNearBottom = false, isUserInteracting = true, current = true))
        assertFalse(ChatScrollPolicy.resolveFollowLatest(isNearBottom = false, isUserInteracting = true, current = false))
    }

    @Test
    fun `away from the bottom without interaction keeps the current intent`() {
        assertTrue(ChatScrollPolicy.resolveFollowLatest(isNearBottom = false, isUserInteracting = false, current = true))
        assertFalse(ChatScrollPolicy.resolveFollowLatest(isNearBottom = false, isUserInteracting = false, current = false))
    }

    // MARK: showsScrollToBottomButton

    @Test
    fun `fab hidden while pinned near the bottom`() {
        assertFalse(
            ChatScrollPolicy.showsScrollToBottomButton(
                isScrolledNearBottom = true,
                hasActiveStream = false,
                shouldFollowLatestMessage = false,
            ),
        )
        assertFalse(
            ChatScrollPolicy.showsScrollToBottomButton(
                isScrolledNearBottom = true,
                hasActiveStream = true,
                shouldFollowLatestMessage = true,
            ),
        )
    }

    @Test
    fun `fab shown when scrolled away with no active stream`() {
        assertTrue(
            ChatScrollPolicy.showsScrollToBottomButton(
                isScrolledNearBottom = false,
                hasActiveStream = false,
                shouldFollowLatestMessage = true,
            ),
        )
    }

    @Test
    fun `fab hidden while streaming with follow intent still owned by the app`() {
        assertFalse(
            ChatScrollPolicy.showsScrollToBottomButton(
                isScrolledNearBottom = false,
                hasActiveStream = true,
                shouldFollowLatestMessage = true,
            ),
        )
    }

    @Test
    fun `fab shown while streaming once the reader scrolled away`() {
        assertTrue(
            ChatScrollPolicy.showsScrollToBottomButton(
                isScrolledNearBottom = false,
                hasActiveStream = true,
                shouldFollowLatestMessage = false,
            ),
        )
    }
}
