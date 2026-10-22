package io.github.jcartu.hermex.ui.feature.chat

import io.github.jcartu.hermex.model.ApprovalChoice
import io.github.jcartu.hermex.model.ApprovalPendingResponse
import io.github.jcartu.hermex.model.ApprovalRespondResponse
import io.github.jcartu.hermex.model.ClarificationPendingResponse
import io.github.jcartu.hermex.model.PendingApproval
import io.github.jcartu.hermex.model.SessionYoloResponse
import io.github.jcartu.hermex.net.ChatStreamEvent
import io.github.jcartu.hermex.net.HermesApi
import io.github.jcartu.hermex.net.SseChatStream
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatPendingActionCoordinatorTest {
    private class Fixture(scope: TestScope) {
        val api = mockk<HermesApi>()
        val sse = mockk<SseChatStream>()
        val approvals = MutableSharedFlow<ChatStreamEvent>(extraBufferCapacity = 32)
        val questions = MutableSharedFlow<ChatStreamEvent>(extraBufferCapacity = 32)
        val state = MutableStateFlow(ChatUiState(sessionId = "s", activeStreamId = "run"))
        var suspended = false
        var response = ApprovalPendingResponse(pendingCount = 0)
        val coordinator: ChatPendingActionCoordinator
        init {
            every { sse.connectApprovalStream("s") } returns approvals
            every { sse.connectClarifyStream("s") } returns questions
            coEvery { api.approvalPending("s") } answers { response }
            coEvery { api.clarifyPending("s") } returns ClarificationPendingResponse(pendingCount = 0)
            coEvery { api.sessionYolo("s") } returns SessionYoloResponse(yoloEnabled = false)
            coordinator = ChatPendingActionCoordinator(api, state, scope.backgroundScope, { suspended }, {}, sse,
                ChatPollingIntervals(100, 100, 100))
        }
        fun pending(id: String) = ApprovalPendingResponse(PendingApproval(approvalId = id, command = "command"), 1)
    }

    @Test fun `approval stays visible across chat completion and suspension until server confirms clear`() = runTest {
        val f = Fixture(this)
        f.coordinator.startMonitoring()
        runCurrent()
        f.approvals.emit(ChatStreamEvent.ApprovalPending(f.pending("one")))
        runCurrent()
        f.state.update { it.copy(activeStreamId = null) }
        f.coordinator.stopMonitoringForStreamTransition(clearClarification = true)
        assertEquals("one", f.state.value.approvalPrompt?.pending?.id)
        f.coordinator.applyApprovalUpdate(ApprovalPendingResponse(), "s")
        assertEquals("one", f.state.value.approvalPrompt?.pending?.id)
        f.suspended = true
        f.coordinator.stopMonitoringForStreamTransition(clearClarification = true)
        assertEquals("one", f.state.value.approvalPrompt?.pending?.id)
        f.coordinator.applyApprovalUpdate(ApprovalPendingResponse(pendingCount = 0), "s")
        assertNull(f.state.value.approvalPrompt)
    }

    @Test fun `approval feed drop continues polling past empty snapshot and chat completion 1206`() = runTest {
        val f = Fixture(this)
        f.coordinator.startMonitoring()
        runCurrent()
        f.state.update { it.copy(activeStreamId = null) }
        f.coordinator.stopMonitoringForStreamTransition(true)
        f.approvals.emit(ChatStreamEvent.TransportError("dropped"))
        runCurrent()
        assertNull(f.state.value.approvalPrompt)
        f.response = f.pending("late")
        advanceTimeBy(101)
        runCurrent()
        assertEquals("late", f.state.value.approvalPrompt?.pending?.id)
    }

    @Test fun `late empty poll cannot erase a newer approval frame`() = runTest {
        val f = Fixture(this)
        val deferred = CompletableDeferred<ApprovalPendingResponse>()
        coEvery { f.api.approvalPending("s") } coAnswers { deferred.await() }
        f.coordinator.startMonitoring()
        runCurrent()
        f.approvals.emit(ChatStreamEvent.TransportError("dropped"))
        runCurrent()
        f.coordinator.applyApprovalUpdate(f.pending("newer"), "s")
        deferred.complete(ApprovalPendingResponse(pendingCount = 0))
        runCurrent()
        assertEquals("newer", f.state.value.approvalPrompt?.pending?.id)
    }

    @Test fun `unconfirmed approval response keeps card answerable but confirmed success clears`() = runTest {
        val f = Fixture(this)
        f.response = f.pending("one")
        f.coordinator.applyApprovalUpdate(f.response, "s")
        coEvery { f.api.respondApproval(any(), any(), any()) } returns ApprovalRespondResponse(ok = false)
        assertFalse(f.coordinator.respondToApproval(ApprovalChoice.ONCE))
        assertNotNull(f.state.value.approvalPrompt)
        assertNotNull(f.state.value.approvalErrorMessage)
        assertFalse(f.state.value.isRespondingToApproval)
        coEvery { f.api.respondApproval(any(), any(), any()) } returns ApprovalRespondResponse(staleCleared = true)
        f.response = ApprovalPendingResponse(pendingCount = 0)
        assertTrue(f.coordinator.respondToApproval(ApprovalChoice.DENY))
        assertNull(f.state.value.approvalPrompt)
    }

    @Test fun `opening an idle chat discovers an unanswered server approval`() = runTest {
        val f = Fixture(this)
        f.state.update { it.copy(activeStreamId = null) }
        f.response = f.pending("old-run")
        f.coordinator.startMonitoring()
        f.coordinator.refreshApprovalBypassState()
        runCurrent()
        assertEquals("old-run", f.state.value.approvalPrompt?.pending?.id)
    }
}
