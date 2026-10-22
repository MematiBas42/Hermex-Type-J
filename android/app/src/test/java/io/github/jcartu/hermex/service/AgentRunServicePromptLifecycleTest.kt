package io.github.jcartu.hermex.service

import io.github.jcartu.hermex.model.ApprovalPendingResponse
import io.github.jcartu.hermex.model.ClarificationPendingResponse
import io.github.jcartu.hermex.model.PendingApproval
import io.github.jcartu.hermex.model.PendingClarification
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentRunServicePromptLifecycleTest {
    @Test
    fun `done keeps an unresolved approval until an explicit zero count`() {
        var lifecycle = RunPromptLifecycle()
            .applying(
                ApprovalPendingResponse(
                    pending = PendingApproval(approvalId = "approval-1", description = "Allow command"),
                    pendingCount = 1,
                ),
            )
            .applying(ClarificationPendingResponse(pendingCount = 0))
            .endingWith(BackgroundRunTerminal.COMPLETE)

        assertEquals(PromptResolution.PENDING, lifecycle.approval)
        assertTrue(lifecycle.shouldContinueMonitoring)

        lifecycle = lifecycle.applying(ApprovalPendingResponse())
        assertEquals(PromptResolution.PENDING, lifecycle.approval)
        assertTrue(lifecycle.shouldContinueMonitoring)

        lifecycle = lifecycle.applying(ApprovalPendingResponse(pendingCount = 0))
        assertEquals(PromptResolution.CLEARED, lifecycle.approval)
        assertFalse(lifecycle.shouldContinueMonitoring)
    }

    @Test
    fun `error keeps an unresolved question until an explicit zero count`() {
        var lifecycle = RunPromptLifecycle()
            .applying(ApprovalPendingResponse(pendingCount = 0))
            .applying(
                ClarificationPendingResponse(
                    pending = PendingClarification(clarifyId = "question-1", question = "Which target?"),
                    pendingCount = 1,
                ),
            )
            .endingWith(BackgroundRunTerminal.FAILED)

        assertEquals(PromptResolution.PENDING, lifecycle.question)
        assertTrue(lifecycle.shouldContinueMonitoring)

        lifecycle = lifecycle.applying(ClarificationPendingResponse(pendingCount = 1))
        assertEquals(PromptResolution.PENDING, lifecycle.question)
        assertTrue(lifecycle.shouldContinueMonitoring)

        lifecycle = lifecycle.applying(ClarificationPendingResponse(pendingCount = 0))
        assertEquals(PromptResolution.CLEARED, lifecycle.question)
        assertFalse(lifecycle.shouldContinueMonitoring)
    }

    @Test
    fun `cancellation remains an immediate prompt teardown`() {
        val lifecycle = RunPromptLifecycle()
            .applying(
                ApprovalPendingResponse(
                    pending = PendingApproval(approvalId = "approval-1"),
                    pendingCount = 1,
                ),
            )
            .applying(
                ClarificationPendingResponse(
                    pending = PendingClarification(clarifyId = "question-1"),
                    pendingCount = 1,
                ),
            )
            .endingWith(BackgroundRunTerminal.CANCELLED)

        assertFalse(lifecycle.shouldContinueMonitoring)
    }
}
