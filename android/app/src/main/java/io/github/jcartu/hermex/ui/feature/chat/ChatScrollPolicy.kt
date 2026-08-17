package io.github.jcartu.hermex.ui.feature.chat

/**
 * Port of `ChatScrollPolicy.swift` — pure decision rules for the chat
 * transcript's auto-scroll behavior.
 *
 * The transcript keeps app-owned follow-bottom intent separate from
 * user-owned manual scrolling, and a short cooldown after any user
 * interaction prevents streaming layout growth from yanking the viewport
 * while a manual scroll is still settling.
 *
 * Distances are in dp (the iOS source uses pt, which maps 1:1); times are
 * epoch milliseconds so the rules stay pure-JVM testable.
 */
object ChatScrollPolicy {

    /**
     * Distance (dp) from the bottom within which we treat the transcript as
     * pinned to the latest content while idle.
     */
    const val BOTTOM_DETECTION_THRESHOLD = 80f

    /**
     * Looser bottom threshold while a response is streaming, so small layout
     * jitter from incoming tokens does not flip follow state off.
     */
    const val STREAMING_BOTTOM_DETECTION_THRESHOLD = 160f

    /**
     * Extra distance past the bottom threshold required before the transcript
     * counts as "reading older" (the iOS composer chrome collapse trigger).
     */
    const val READING_OLDER_HYSTERESIS = 64f

    /**
     * How long automatic follow-scroll stays paused after the user last
     * interacted with the scroll view.
     */
    const val USER_SCROLL_COOLDOWN_MILLIS = 250L

    fun bottomThreshold(isStreaming: Boolean): Float =
        if (isStreaming) STREAMING_BOTTOM_DETECTION_THRESHOLD else BOTTOM_DETECTION_THRESHOLD

    fun isNearBottom(distanceFromBottom: Float, isStreaming: Boolean): Boolean =
        distanceFromBottom <= bottomThreshold(isStreaming)

    /**
     * True once the user has scrolled far enough above the bottom that the
     * transcript is in its "reading older" presentation. The hysteresis keeps
     * the state stable when hovering right around the bottom threshold.
     */
    fun shouldEnterReadingOlder(distanceFromBottom: Float, isStreaming: Boolean): Boolean =
        distanceFromBottom > bottomThreshold(isStreaming) + READING_OLDER_HYSTERESIS

    fun cooldownDeadline(nowMillis: Long): Long = nowMillis + USER_SCROLL_COOLDOWN_MILLIS

    /**
     * Automatic follow-scroll is paused while the user is actively touching the
     * scroll view and for a brief cooldown window afterward. Explicit user
     * actions (tapping scroll-to-bottom, sending a message) bypass this.
     */
    fun isAutoScrollPaused(
        isUserInteracting: Boolean,
        cooldownUntilMillis: Long?,
        nowMillis: Long,
    ): Boolean {
        if (isUserInteracting) return true
        if (cooldownUntilMillis == null) return false
        return nowMillis < cooldownUntilMillis
    }

    /**
     * Follow-latest intent transition, ported from `ChatView.updateScrollMetrics`:
     * returning near the bottom re-engages follow; scrolling away by hand
     * releases it; anything else (programmatic settling, streaming growth)
     * leaves the current intent untouched.
     */
    fun resolveFollowLatest(
        isNearBottom: Boolean,
        isUserInteracting: Boolean,
        current: Boolean,
    ): Boolean = when {
        isNearBottom -> true
        isUserInteracting -> false
        else -> current
    }

    /**
     * Scroll-to-bottom FAB visibility, ported from `ChatView.showsScrollToBottomButton`:
     * hidden while pinned near the bottom; while a response streams it stays
     * hidden as long as the app still owns follow-latest intent (auto-scroll
     * will bring the user down anyway).
     */
    fun showsScrollToBottomButton(
        isScrolledNearBottom: Boolean,
        hasActiveStream: Boolean,
        shouldFollowLatestMessage: Boolean,
    ): Boolean = !isScrolledNearBottom && (!hasActiveStream || !shouldFollowLatestMessage)
}
