package io.github.jcartu.hermex.ui.feature.chat

import io.github.jcartu.hermex.model.ApprovalChoice
import io.github.jcartu.hermex.model.ApprovalPendingResponse
import io.github.jcartu.hermex.model.ClarificationPendingResponse
import io.github.jcartu.hermex.net.ApiError
import io.github.jcartu.hermex.net.ChatStreamEvent
import io.github.jcartu.hermex.net.HermesApi
import io.github.jcartu.hermex.net.SseChatStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Port of `ChatPollingIntervals` from `ChatViewModel.swift` (nanoseconds → millis). */
data class ChatPollingIntervals(
    val approvalMillis: Long,
    val clarificationMillis: Long,
    val backgroundMillis: Long,
) {
    companion object {
        val standard = ChatPollingIntervals(approvalMillis = 1_500, clarificationMillis = 1_500, backgroundMillis = 3_000)
    }
}

/**
 * Port of `ChatPendingActionCoordinator.swift`: session-scoped approval and
 * clarification SSE feeds, with polling after a feed drops (#1206). A pending
 * approval belongs to the server, not the chat transport: chat completion
 * does not dismiss it or stop the approval feed, and only a confirmed zero
 * pending count clears a card. Poll replies begun before an SSE update or
 * accepted response cannot resurrect or clear a newer request.
 */
class ChatPendingActionCoordinator(
    private val api: HermesApi,
    private val state: MutableStateFlow<ChatUiState>,
    private val scope: CoroutineScope,
    private val isStreamConnectionSuspended: () -> Boolean,
    private val onActionFailed: (message: String) -> Unit,
    private val sse: SseChatStream,
    private val pollingIntervals: ChatPollingIntervals = ChatPollingIntervals.standard,
) {
    private val approvalPendingBySession = mutableMapOf<String, ApprovalPromptState>()
    private val clarificationPendingBySession = mutableMapOf<String, ClarificationPromptState>()
    private var approvalStreamJob: Job? = null
    private var clarificationStreamJob: Job? = null
    private var approvalPollingJob: Job? = null
    private var clarificationPollingJob: Job? = null
    private var approvalMonitoringSessionId: String? = null
    private var clarificationMonitoringSessionId: String? = null
    private var approvalHadPendingWhileMonitoring = false
    private var approvalStateGeneration = 0
    private var isMonitoringAllowed = false

    var isSessionApprovalBypassEnabled = false
        private set

    val hasPendingPrompt: Boolean
        get() = state.value.approvalPrompt != null || state.value.clarificationPrompt != null
    private val sessionId: String? get() = state.value.sessionId
    private val hasActiveStream: Boolean get() = state.value.activeStreamId != null

    /** Port of `refreshApprovalBypassState`: also discovers approvals that outlive the chat stream/navigation. */
    suspend fun refreshApprovalBypassState() {
        val id = sessionId ?: return
        try {
            val response = api.sessionYolo(id)
            currentCoroutineContext().ensureActive()
            if (sessionId != id) return
            isSessionApprovalBypassEnabled = response.yoloEnabled == true
            if (isSessionApprovalBypassEnabled) {
                approvalStateGeneration += 1
                approvalPendingBySession.remove(id)
                state.update { it.copy(approvalPrompt = null) }
            } else renderApprovalPromptForCurrentSession()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Bypass state is advisory; failure does not prevent discovering a pending request.
        }
        if (sessionId == id && !isSessionApprovalBypassEnabled) refreshApprovalPending(id)
    }

    fun startMonitoring() {
        isMonitoringAllowed = true
        startApprovalMonitoring()
        startClarificationMonitoring()
    }

    fun stopMonitoring(clearPrompt: Boolean) {
        isMonitoringAllowed = false
        stopApprovalMonitoring(clearPrompt)
        stopClarificationMonitoring(clearPrompt)
        if (clearPrompt) isSessionApprovalBypassEnabled = false
    }

    /** Port of `stopMonitoringForStreamTransition`: an approval may arrive after chat completion. */
    fun stopMonitoringForStreamTransition(clearClarification: Boolean) {
        if (isStreamConnectionSuspended()) stopApprovalMonitoring(clearPrompt = false)
        stopClarificationMonitoring(clearPrompt = clearClarification)
    }

    fun applyApprovalUpdate(update: ApprovalPendingResponse, sessionId: String) {
        val pending = update.pending
        if (pending != null && !pending.isEmpty) {
            approvalPendingBySession[sessionId] = ApprovalPromptState(sessionId, pending, maxOf(update.pendingCount ?: 1, 1))
        } else if (update.pendingCount == 0) {
            approvalPendingBySession.remove(sessionId)
        } else {
            // An unknown/malformed payload is not a server-confirmed clear.
            return
        }
        if (sessionId != this.sessionId) return
        approvalStateGeneration += 1
        renderApprovalPromptForCurrentSession()
        if (approvalPendingBySession[sessionId] != null) {
            approvalHadPendingWhileMonitoring = true
            startApprovalMonitoring()
        } else if (!hasActiveStream && approvalMonitoringSessionId == sessionId && approvalHadPendingWhileMonitoring) {
            stopApprovalMonitoring(clearPrompt = false)
        }
    }

    fun applyClarificationUpdate(update: ClarificationPendingResponse, sessionId: String) {
        val pending = update.pending
        if (pending != null && !pending.isEmpty) {
            clarificationPendingBySession[sessionId] = ClarificationPromptState(sessionId, pending, maxOf(update.pendingCount ?: 1, 1))
        } else clarificationPendingBySession.remove(sessionId)
        renderClarificationPromptForCurrentSession()
    }

    /** Port of `respondToApproval`: HTTP 200 with false/missing `ok` leaves the card answerable. */
    suspend fun respondToApproval(choice: ApprovalChoice): Boolean {
        val prompt = state.value.approvalPrompt ?: return false
        if (prompt.sessionId != sessionId) return false
        state.update { it.copy(isRespondingToApproval = true, approvalErrorMessage = null, sendErrorMessage = null) }
        try {
            val response = api.respondApproval(prompt.sessionId, choice, prompt.pending.approvalId)
            if (sessionId != prompt.sessionId) return false
            if (response.ok != true && response.staleCleared != true) {
                state.update { it.copy(approvalErrorMessage = "The server did not accept that response. The request is still waiting.") }
                refreshApprovalPending(prompt.sessionId)
                return false
            }
            clearAnsweredApproval(prompt)
            refreshApprovalPending(prompt.sessionId)
            return true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (sessionId != prompt.sessionId) return false
            if ((error as? ApiError)?.indicatesExpiredPendingPrompt == true) {
                clearAnsweredApproval(prompt)
                onActionFailed("That approval request already expired, so the agent has moved on.")
                refreshApprovalPending(prompt.sessionId)
                return false
            }
            val message = error.message ?: "Could not submit the approval response."
            state.update { it.copy(approvalErrorMessage = message) }
            onActionFailed(message)
            return false
        } finally {
            if (sessionId == prompt.sessionId) state.update { it.copy(isRespondingToApproval = false) }
        }
    }

    private fun clearAnsweredApproval(prompt: ApprovalPromptState) {
        approvalStateGeneration += 1
        if (approvalPendingBySession[prompt.sessionId]?.pending?.id == prompt.pending.id) {
            approvalPendingBySession.remove(prompt.sessionId)
        }
        renderApprovalPromptForCurrentSession()
    }

    suspend fun skipApprovalsForCurrentSession(): Boolean {
        val prompt = state.value.approvalPrompt ?: return false
        if (prompt.sessionId != sessionId) return false
        state.update { it.copy(isRespondingToApproval = true, approvalErrorMessage = null, sendErrorMessage = null) }
        try {
            val response = api.setSessionYolo(prompt.sessionId, enabled = true)
            if (sessionId != prompt.sessionId) return false
            isSessionApprovalBypassEnabled = response.yoloEnabled ?: true
            clearAnsweredApproval(prompt)
            return true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (sessionId != prompt.sessionId) return false
            val message = error.message ?: "Could not skip approvals for this session."
            state.update { it.copy(approvalErrorMessage = message) }
            onActionFailed(message)
            return false
        } finally {
            if (sessionId == prompt.sessionId) state.update { it.copy(isRespondingToApproval = false) }
        }
    }

    suspend fun respondToClarification(responseText: String): Boolean {
        val prompt = state.value.clarificationPrompt ?: return false
        if (prompt.sessionId != sessionId) return false
        val response = responseText.trim()
        if (response.isEmpty()) {
            state.update { it.copy(clarificationErrorMessage = "Enter a response before submitting.") }
            return false
        }
        state.update { it.copy(isRespondingToClarification = true, clarificationErrorMessage = null, sendErrorMessage = null) }
        try {
            api.respondClarification(prompt.sessionId, response, prompt.pending.clarifyId)
            if (sessionId != prompt.sessionId) return false
            clarificationPendingBySession.remove(prompt.sessionId)
            state.update { it.copy(clarificationPrompt = null) }
            refreshClarificationPending(prompt.sessionId)
            return true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (sessionId != prompt.sessionId) return false
            if ((error as? ApiError)?.indicatesExpiredPendingPrompt == true) {
                clarificationPendingBySession.remove(prompt.sessionId)
                state.update { it.copy(clarificationPrompt = null) }
                onActionFailed("That clarification prompt already expired, so the agent has moved on.")
                refreshClarificationPending(prompt.sessionId)
                return false
            }
            val message = error.message ?: "Could not submit the clarification response."
            state.update { it.copy(clarificationErrorMessage = message) }
            onActionFailed(message)
            return false
        } finally {
            if (sessionId == prompt.sessionId) state.update { it.copy(isRespondingToClarification = false) }
        }
    }

    private fun startApprovalMonitoring() {
        val id = sessionId ?: return
        if (!isMonitoringAllowed || isStreamConnectionSuspended() ||
            (!hasActiveStream && approvalPendingBySession[id] == null) || approvalMonitoringSessionId == id) return
        stopApprovalMonitoring(clearPrompt = false)
        approvalMonitoringSessionId = id
        approvalHadPendingWhileMonitoring = approvalPendingBySession[id] != null
        approvalStreamJob = scope.launch(start = CoroutineStart.LAZY) {
            try {
                sse.connectApprovalStream(id).collect { event ->
                    if (approvalMonitoringSessionId == id) when (event) {
                        is ChatStreamEvent.ApprovalPending -> applyApprovalUpdate(event.response, id)
                        is ChatStreamEvent.TransportError, is ChatStreamEvent.Error -> startApprovalFallbackPolling(id)
                        else -> Unit
                    }
                }
                if (isActive && approvalMonitoringSessionId == id) startApprovalFallbackPolling(id)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                startApprovalFallbackPolling(id)
            }
        }.also { it.start() }
    }

    private fun stopApprovalMonitoring(clearPrompt: Boolean) {
        approvalStreamJob?.cancel()
        approvalStreamJob = null
        approvalPollingJob?.cancel()
        approvalPollingJob = null
        approvalMonitoringSessionId = null
        approvalHadPendingWhileMonitoring = false
        // Fence an outstanding poll even when the visible card is retained.
        approvalStateGeneration += 1
        if (!clearPrompt) return
        sessionId?.let { approvalPendingBySession.remove(it) }
        state.update { it.copy(approvalPrompt = null, approvalErrorMessage = null) }
    }

    private fun startApprovalFallbackPolling(id: String) {
        if (approvalMonitoringSessionId != id || approvalPollingJob?.isActive == true) return
        approvalStreamJob?.cancel()
        approvalPollingJob = scope.launch {
            while (isActive && sessionId == id && isMonitoringAllowed && !isStreamConnectionSuspended()) {
                refreshApprovalPending(id)
                // An empty snapshot cannot rule out a later approval (#1206).
                delay(pollingIntervals.approvalMillis)
            }
        }
    }

    private suspend fun refreshApprovalPending(id: String) {
        if (sessionId != id) return
        val generation = ++approvalStateGeneration
        try {
            val response = api.approvalPending(id)
            currentCoroutineContext().ensureActive()
            if (sessionId == id && approvalStateGeneration == generation) applyApprovalUpdate(response, id)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // A degraded-mode polling failure must not dismiss an unanswered card.
        }
    }

    private fun renderApprovalPromptForCurrentSession() {
        val prompt = if (isSessionApprovalBypassEnabled) null else approvalPendingBySession[sessionId]
        state.update { it.copy(approvalPrompt = prompt) }
    }

    private fun startClarificationMonitoring() {
        val id = sessionId ?: return
        if (!isMonitoringAllowed || !hasActiveStream || isStreamConnectionSuspended() || clarificationMonitoringSessionId == id) return
        stopClarificationMonitoring(clearPrompt = false)
        clarificationMonitoringSessionId = id
        clarificationStreamJob = scope.launch(start = CoroutineStart.LAZY) {
            try {
                sse.connectClarifyStream(id).collect { event ->
                    if (clarificationMonitoringSessionId == id) when (event) {
                        is ChatStreamEvent.ClarificationPending -> applyClarificationUpdate(event.response, id)
                        is ChatStreamEvent.ApprovalPending -> if (event.response.pending == null) {
                            applyClarificationUpdate(ClarificationPendingResponse(pendingCount = event.response.pendingCount), id)
                        }
                        is ChatStreamEvent.TransportError, is ChatStreamEvent.Error -> startClarificationFallbackPolling(id)
                        else -> Unit
                    }
                }
                if (isActive && clarificationMonitoringSessionId == id) startClarificationFallbackPolling(id)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                startClarificationFallbackPolling(id)
            }
        }.also { it.start() }
    }

    private fun stopClarificationMonitoring(clearPrompt: Boolean) {
        clarificationStreamJob?.cancel()
        clarificationStreamJob = null
        clarificationPollingJob?.cancel()
        clarificationPollingJob = null
        clarificationMonitoringSessionId = null
        if (!clearPrompt) return
        sessionId?.let { clarificationPendingBySession.remove(it) }
        state.update { it.copy(clarificationPrompt = null, clarificationErrorMessage = null) }
    }

    private fun startClarificationFallbackPolling(id: String) {
        if (clarificationMonitoringSessionId != id || clarificationPollingJob?.isActive == true) return
        clarificationStreamJob?.cancel()
        clarificationPollingJob = scope.launch {
            while (isActive && sessionId == id && hasActiveStream && isMonitoringAllowed && !isStreamConnectionSuspended()) {
                refreshClarificationPending(id)
                delay(pollingIntervals.clarificationMillis)
            }
        }
    }

    private suspend fun refreshClarificationPending(id: String) {
        if (!hasActiveStream || sessionId != id) return
        try {
            val response = api.clarifyPending(id)
            currentCoroutineContext().ensureActive()
            if (hasActiveStream && sessionId == id) applyClarificationUpdate(response, id)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // The web UI also ignores degraded-mode polling failures.
        }
    }

    private fun renderClarificationPromptForCurrentSession() {
        state.update { it.copy(clarificationPrompt = if (hasActiveStream) clarificationPendingBySession[sessionId] else null) }
    }
}
