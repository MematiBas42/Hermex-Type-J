package io.github.jcartu.hermex.ui.feature.chat

import io.github.jcartu.hermex.model.ApprovalChoice
import io.github.jcartu.hermex.model.ApprovalPendingResponse
import io.github.jcartu.hermex.model.ClarificationPendingResponse
import io.github.jcartu.hermex.net.ApiError
import io.github.jcartu.hermex.net.HermesApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Port of `ChatPollingIntervals` from `ChatViewModel.swift` (nanoseconds → millis). */
data class ChatPollingIntervals(
    val approvalMillis: Long,
    val clarificationMillis: Long,
    val backgroundMillis: Long,
) {
    companion object {
        val standard = ChatPollingIntervals(
            approvalMillis = 1_500,
            clarificationMillis = 1_500,
            backgroundMillis = 3_000,
        )
    }
}

/**
 * Port of `ChatPendingActionCoordinator.swift` — approval/clarification
 * prompt state for the active session.
 *
 * iOS monitors via dedicated SSE streams with polling as the degraded-mode
 * fallback; the Android transport exposes only the chat stream, so this port
 * monitors by polling `GET /api/approval/pending` / `GET /api/clarify/pending`
 * at [ChatPollingIntervals.standard] while a stream is active (the SSE
 * `approval_pending` / `clarification_pending` frames still apply instantly
 * through [applyApprovalUpdate] / [applyClarificationUpdate]).
 *
 * State lives on the shared [ChatUiState] flow (`approvalPrompt`,
 * `isRespondingToApproval`, `approvalErrorMessage`, and the clarification
 * counterparts); polling-loop failures are ignored like the web UI's.
 */
class ChatPendingActionCoordinator(
    private val api: HermesApi,
    private val state: MutableStateFlow<ChatUiState>,
    private val scope: CoroutineScope,
    private val isStreamConnectionSuspended: () -> Boolean,
    private val onActionFailed: (message: String) -> Unit,
    private val pollingIntervals: ChatPollingIntervals = ChatPollingIntervals.standard,
) {

    private val approvalPendingBySession = mutableMapOf<String, ApprovalPromptState>()
    private val clarificationPendingBySession = mutableMapOf<String, ClarificationPromptState>()
    private var approvalPollingJob: Job? = null
    private var clarificationPollingJob: Job? = null
    private var approvalMonitoringSessionId: String? = null
    private var clarificationMonitoringSessionId: String? = null

    /** Port of `isSessionApprovalBypassEnabled` — set by [skipApprovalsForCurrentSession]. */
    var isSessionApprovalBypassEnabled = false
        private set

    val hasPendingPrompt: Boolean
        get() = state.value.approvalPrompt != null || state.value.clarificationPrompt != null

    private val sessionId: String? get() = state.value.sessionId
    private val hasActiveStream: Boolean get() = state.value.activeStreamId != null

    fun startMonitoring() {
        startApprovalMonitoring()
        startClarificationMonitoring()
    }

    fun stopMonitoring(clearPrompt: Boolean) {
        stopApprovalMonitoring(clearPrompt)
        stopClarificationMonitoring(clearPrompt)
    }

    /** Port of `applyApprovalUpdate(_:sessionID:)`. */
    fun applyApprovalUpdate(update: ApprovalPendingResponse, sessionId: String) {
        val pending = update.pending
        if (pending != null && !pending.isEmpty) {
            approvalPendingBySession[sessionId] = ApprovalPromptState(
                sessionId = sessionId,
                pending = pending,
                pendingCount = maxOf(update.pendingCount ?: 1, 1),
            )
        } else {
            approvalPendingBySession.remove(sessionId)
        }

        renderApprovalPromptForCurrentSession()
    }

    /** Port of `applyClarificationUpdate(_:sessionID:)`. */
    fun applyClarificationUpdate(update: ClarificationPendingResponse, sessionId: String) {
        val pending = update.pending
        if (pending != null && !pending.isEmpty) {
            clarificationPendingBySession[sessionId] = ClarificationPromptState(
                sessionId = sessionId,
                pending = pending,
                pendingCount = maxOf(update.pendingCount ?: 1, 1),
            )
        } else {
            clarificationPendingBySession.remove(sessionId)
        }

        renderClarificationPromptForCurrentSession()
    }

    /** Port of `respondToApproval(_:)`. */
    suspend fun respondToApproval(choice: ApprovalChoice): Boolean {
        val prompt = state.value.approvalPrompt ?: return false
        if (prompt.sessionId != sessionId) return false

        state.update { it.copy(isRespondingToApproval = true, approvalErrorMessage = null, sendErrorMessage = null) }
        try {
            api.respondApproval(
                sessionId = prompt.sessionId,
                choice = choice,
                approvalId = prompt.pending.approvalId,
            )
            approvalPendingBySession.remove(prompt.sessionId)
            state.update { it.copy(approvalPrompt = null) }
            refreshApprovalPending(prompt.sessionId)
            return true
        } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            if ((error as? ApiError)?.indicatesExpiredPendingPrompt == true) {
                // The prompt already expired server-side: dismiss the stale card and
                // explain, instead of leaving a stuck card behind a generic failure.
                approvalPendingBySession.remove(prompt.sessionId)
                state.update { it.copy(approvalPrompt = null) }
                onActionFailed("That approval request already expired, so the agent has moved on.")
                refreshApprovalPending(prompt.sessionId)
                return false
            }

            val message = error.message ?: "Could not submit the approval response."
            state.update { it.copy(approvalErrorMessage = message) }
            onActionFailed(message)
            return false
        } finally {
            state.update { it.copy(isRespondingToApproval = false) }
        }
    }

    /** Port of `skipApprovalsForCurrentSession()` — session-scoped YOLO via `POST /api/session/yolo`. */
    suspend fun skipApprovalsForCurrentSession(): Boolean {
        val prompt = state.value.approvalPrompt ?: return false
        if (prompt.sessionId != sessionId) return false

        state.update { it.copy(isRespondingToApproval = true, approvalErrorMessage = null, sendErrorMessage = null) }
        try {
            val response = api.setSessionYolo(sessionId = prompt.sessionId, enabled = true)
            isSessionApprovalBypassEnabled = response.yoloEnabled ?: true
            approvalPendingBySession.remove(prompt.sessionId)
            state.update { it.copy(approvalPrompt = null) }
            return true
        } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            val message = error.message ?: "Could not skip approvals for this session."
            state.update { it.copy(approvalErrorMessage = message) }
            onActionFailed(message)
            return false
        } finally {
            state.update { it.copy(isRespondingToApproval = false) }
        }
    }

    /** Port of `respondToClarification(_:)`. */
    suspend fun respondToClarification(responseText: String): Boolean {
        val prompt = state.value.clarificationPrompt ?: return false
        if (prompt.sessionId != sessionId) return false

        val response = responseText.trim()
        if (response.isEmpty()) {
            state.update { it.copy(clarificationErrorMessage = "Enter a response before submitting.") }
            return false
        }

        state.update {
            it.copy(isRespondingToClarification = true, clarificationErrorMessage = null, sendErrorMessage = null)
        }
        try {
            api.respondClarification(
                sessionId = prompt.sessionId,
                response = response,
                clarifyId = prompt.pending.clarifyId,
            )
            clarificationPendingBySession.remove(prompt.sessionId)
            state.update { it.copy(clarificationPrompt = null) }
            refreshClarificationPending(prompt.sessionId)
            return true
        } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) throw error
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
            state.update { it.copy(isRespondingToClarification = false) }
        }
    }

    // MARK: - Approval monitoring

    private fun startApprovalMonitoring() {
        val sessionId = sessionId ?: return
        if (!hasActiveStream || approvalMonitoringSessionId == sessionId) return

        stopApprovalMonitoring(clearPrompt = false)
        approvalMonitoringSessionId = sessionId
        approvalPollingJob = scope.launch {
            while (true) {
                if (this@ChatPendingActionCoordinator.sessionId != sessionId ||
                    !hasActiveStream ||
                    isStreamConnectionSuspended()
                ) {
                    break
                }

                refreshApprovalPending(sessionId)
                delay(pollingIntervals.approvalMillis)
            }
        }
    }

    private fun stopApprovalMonitoring(clearPrompt: Boolean) {
        approvalPollingJob?.cancel()
        approvalPollingJob = null
        approvalMonitoringSessionId = null

        if (!clearPrompt) return
        sessionId?.let { approvalPendingBySession.remove(it) }
        state.update { it.copy(approvalPrompt = null, approvalErrorMessage = null) }
    }

    private suspend fun refreshApprovalPending(sessionId: String) {
        if (!hasActiveStream) return

        try {
            applyApprovalUpdate(api.approvalPending(sessionId), sessionId)
        } catch (_: Exception) {
            // The web UI also ignores degraded-mode polling failures.
        }
    }

    private fun renderApprovalPromptForCurrentSession() {
        val sessionId = sessionId
        if (sessionId == null) {
            state.update { it.copy(approvalPrompt = null) }
            return
        }

        val prompt = approvalPendingBySession[sessionId]
        if (!hasActiveStream || isSessionApprovalBypassEnabled || prompt == null) {
            state.update { current ->
                if (current.approvalPrompt?.sessionId == sessionId) {
                    current.copy(approvalPrompt = null)
                } else {
                    current
                }
            }
            return
        }

        state.update { it.copy(approvalPrompt = prompt) }
    }

    // MARK: - Clarification monitoring

    private fun startClarificationMonitoring() {
        val sessionId = sessionId ?: return
        if (!hasActiveStream || clarificationMonitoringSessionId == sessionId) return

        stopClarificationMonitoring(clearPrompt = false)
        clarificationMonitoringSessionId = sessionId
        clarificationPollingJob = scope.launch {
            while (true) {
                if (this@ChatPendingActionCoordinator.sessionId != sessionId ||
                    !hasActiveStream ||
                    isStreamConnectionSuspended()
                ) {
                    break
                }

                refreshClarificationPending(sessionId)
                delay(pollingIntervals.clarificationMillis)
            }
        }
    }

    private fun stopClarificationMonitoring(clearPrompt: Boolean) {
        clarificationPollingJob?.cancel()
        clarificationPollingJob = null
        clarificationMonitoringSessionId = null

        if (!clearPrompt) return
        sessionId?.let { clarificationPendingBySession.remove(it) }
        state.update { it.copy(clarificationPrompt = null, clarificationErrorMessage = null) }
    }

    private suspend fun refreshClarificationPending(sessionId: String) {
        if (!hasActiveStream) return

        try {
            applyClarificationUpdate(api.clarifyPending(sessionId), sessionId)
        } catch (_: Exception) {
            // The web UI also ignores degraded-mode polling failures.
        }
    }

    private fun renderClarificationPromptForCurrentSession() {
        val sessionId = sessionId
        if (sessionId == null) {
            state.update { it.copy(clarificationPrompt = null) }
            return
        }

        val prompt = clarificationPendingBySession[sessionId]
        if (!hasActiveStream || prompt == null) {
            state.update { current ->
                if (current.clarificationPrompt?.sessionId == sessionId) {
                    current.copy(clarificationPrompt = null)
                } else {
                    current
                }
            }
            return
        }

        state.update { it.copy(clarificationPrompt = prompt) }
    }
}
