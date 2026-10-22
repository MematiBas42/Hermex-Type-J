package io.github.jcartu.hermex.ui.feature.sessions

import io.github.jcartu.hermex.model.SessionSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Row state derivation (1.6.0): `SessionRowAttentionState.resolve` precedence
 * and the cached-data fallback rule of `effectiveAttentionState`
 * (`SessionRowView.swift`).
 */
class SessionRowAttentionStateTest {

    private fun streamingSession() = SessionSummary(
        sessionId = "s1",
        title = "Working",
        isStreaming = true,
        activeStreamId = "stream-1",
    )

    @Test
    fun `approval wins over input and working`() {
        val session = streamingSession()
        assertEquals(
            SessionRowAttentionState.APPROVAL,
            SessionRowAttentionState.resolve(
                session,
                hasPendingApproval = true,
                hasPendingClarification = true,
            ),
        )
    }

    @Test
    fun `input wins over working`() {
        val session = streamingSession()
        assertEquals(
            SessionRowAttentionState.INPUT,
            SessionRowAttentionState.resolve(
                session,
                hasPendingApproval = false,
                hasPendingClarification = true,
            ),
        )
    }

    @Test
    fun `streaming with nothing pending is working`() {
        assertEquals(
            SessionRowAttentionState.WORKING,
            SessionRowAttentionState.resolve(
                streamingSession(),
                hasPendingApproval = false,
                hasPendingClarification = false,
            ),
        )
    }

    @Test
    fun `settled session with nothing pending resolves null`() {
        assertNull(
            SessionRowAttentionState.resolve(
                SessionSummary(sessionId = "s2", title = "Done"),
                hasPendingApproval = false,
                hasPendingClarification = false,
            ),
        )
    }

    @Test
    fun `activeStreamId alone counts as streaming`() {
        // isStreaming missing but a stream id present (tolerant decode path).
        val session = SessionSummary(sessionId = "s3", activeStreamId = "stream-9")
        assertEquals(
            SessionRowAttentionState.WORKING,
            SessionRowAttentionState.resolve(session, hasPendingApproval = false, hasPendingClarification = false),
        )
    }

    @Test
    fun `pending flag on a non-streaming session still resolves`() {
        // The caller decides which sessions are worth asking the server about.
        val session = SessionSummary(sessionId = "s4")
        assertEquals(
            SessionRowAttentionState.APPROVAL,
            SessionRowAttentionState.resolve(session, hasPendingApproval = true, hasPendingClarification = false),
        )
    }

    @Test
    fun `polled state wins over the session-local fallback`() {
        val polled = SessionRowAttentionState.effectiveAttentionState(
            session = streamingSession(),
            attentionState = SessionRowAttentionState.INPUT,
            isViewingCachedData = false,
        )
        assertEquals(SessionRowAttentionState.INPUT, polled)
    }

    @Test
    fun `cached rows never claim the agent is working`() {
        // A cached summary keeps whatever isStreaming/activeStreamId it was
        // captured with; offline rows fall back to their relative time.
        val cached = SessionRowAttentionState.effectiveAttentionState(
            session = streamingSession(),
            attentionState = null,
            isViewingCachedData = true,
        )
        assertNull(cached)
    }

    @Test
    fun `labels and accessibility text`() {
        assertEquals("Approval", SessionRowAttentionState.APPROVAL.title)
        assertEquals("Input", SessionRowAttentionState.INPUT.title)
        assertEquals("Working", SessionRowAttentionState.WORKING.title)
        assertEquals("Waiting for approval", SessionRowAttentionState.APPROVAL.accessibilityLabel)
        assertEquals("Needs input", SessionRowAttentionState.INPUT.accessibilityLabel)
        assertEquals("Working", SessionRowAttentionState.WORKING.accessibilityLabel)
    }
}
