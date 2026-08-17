package io.github.jcartu.hermex.ui.feature.chat

import io.github.jcartu.hermex.model.ChatCancelResponse
import io.github.jcartu.hermex.net.ApiError
import io.github.jcartu.hermex.net.ChatStreamEvent
import io.github.jcartu.hermex.net.HermesApi
import io.github.jcartu.hermex.net.InterimAssistantStreamEvent
import io.github.jcartu.hermex.net.SseChatStream
import io.github.jcartu.hermex.net.TitleStreamEvent
import io.github.jcartu.hermex.net.ToolStreamEvent
import io.github.jcartu.hermex.net.DoneStreamEvent
import io.github.jcartu.hermex.model.ApprovalPendingResponse
import io.github.jcartu.hermex.model.ClarificationPendingResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Port of `ChatStreamLoadPreparation` from `ChatStreamCoordinator.swift`. */
data class ChatStreamLoadPreparation(
    val activeStreamIdBeforeLoad: String?,
    val shouldPrepareSuspendedStreamResume: Boolean,
)

/**
 * Port of `ChatStreamCoordinatorDelegate` (minus the iOS Live Activity and
 * cross-instance snapshot-store hooks, which have no Android counterpart —
 * the ViewModel outlives backgrounding, so "save snapshot" reduces to
 * flushing the pending streamed content).
 */
interface ChatStreamCoordinatorDelegate {
    val streamCoordinatorSessionId: String?
    val streamCoordinatorLatestServerLoadHadAssistantResponseAfterLatestUser: Boolean

    /** Setting null flushes pending streamed content first (Swift setter side effect). */
    var streamCoordinatorStreamingAssistantMessageId: String?

    suspend fun streamCoordinatorLoadMessages()
    fun streamCoordinatorLatestAssistantMessageId(): String?
    fun streamCoordinatorStartAuxiliaryMonitoring()
    fun streamCoordinatorStopAuxiliaryMonitoring(clearPrompt: Boolean)
    fun streamCoordinatorFlushPendingStreamingContent()
    fun streamCoordinatorFlushPinnedLocalNoticesToTranscript()
    fun streamCoordinatorDrainQueuedSlashMessageIfIdle()
    fun streamCoordinatorRefreshCompletedResponseTitleIfNeeded()
    fun streamCoordinatorDidCompleteCurrentResponse(needsTranscriptRefresh: Boolean)
    fun streamCoordinatorDidFinishStream()
    fun streamCoordinatorDidReceiveErrorMessage(message: String)
    fun streamCoordinatorDidReceiveRecoveryError(error: Throwable)
    fun streamCoordinatorDidStartConnection(isReplay: Boolean)
    fun streamCoordinatorDidResetRecoveryState()

    fun streamCoordinatorAppendToken(text: String): Boolean
    fun streamCoordinatorAppendInterimAssistant(payload: InterimAssistantStreamEvent): Boolean
    fun streamCoordinatorAppendReasoning(text: String): Boolean
    fun streamCoordinatorAppendToolCall(payload: ToolStreamEvent): Boolean
    fun streamCoordinatorCompleteToolCall(payload: ToolStreamEvent): Boolean
    fun streamCoordinatorUpdateTitle(payload: TitleStreamEvent): Boolean
    fun streamCoordinatorApplyDone(payload: DoneStreamEvent): Boolean
    fun streamCoordinatorApplyApprovalUpdate(update: ApprovalPendingResponse)
    fun streamCoordinatorApplyClarificationUpdate(update: ClarificationPendingResponse)
    fun streamCoordinatorEnqueuePendingSteerLeftover(text: String): Boolean
}

/**
 * Port of `ChatStreamCoordinator.swift` — owns the chat SSE connection
 * lifecycle: start, suspend-for-background, status-checked reconnect with
 * journal replay, and terminal-frame finalization.
 *
 * `activeStreamId`, `recoveryState`, and `liveTokensPerSecond` live on the
 * shared [ChatUiState] flow; connection-internal flags stay here. The iOS
 * stale-stream watchdog timers (`recoverStaleStreamIfNeeded` and the
 * foreground status poll) are view-driven on iOS and have no Android driver;
 * the equivalent recovery entry points here are [reconnectIfNeeded]
 * (lifecycle ON_START) and the transport-error path, which suspends the
 * connection and immediately runs one status-checked reconnect.
 */
class ChatStreamCoordinator(
    private val api: HermesApi,
    private val sse: SseChatStream,
    private val state: MutableStateFlow<ChatUiState>,
    private val scope: CoroutineScope,
) {
    private var delegate: ChatStreamCoordinatorDelegate? = null
    private var connectionJob: Job? = null

    val activeStreamId: String? get() = state.value.activeStreamId

    var hasCompletedCurrentResponse = false
        private set

    var isConnectionSuspended = false
        private set

    var isReplayConnection = false
        private set

    var lastEventId: String? = null
        private set

    /**
     * Bumped whenever the active run starts or finalizes. Captured before an
     * async transcript load so a concurrent cancel/completion during the load
     * can't be double-finalized (iOS PR #266 review #2).
     */
    private var runGeneration = 0

    fun attach(delegate: ChatStreamCoordinatorDelegate) {
        this.delegate = delegate
    }

    /** Port of `prepareForNewResponse()`. */
    fun prepareForNewResponse() {
        hasCompletedCurrentResponse = false
        isConnectionSuspended = false
        state.update { it.copy(liveTokensPerSecond = null) }
    }

    /** Port of `start(streamID:replayAfterSeq:recoveryState:)`. */
    fun start(
        streamId: String,
        replayAfterSeq: Int? = null,
        recoveryState: ActiveStreamRecoveryState = ActiveStreamRecoveryState.IDLE,
    ) {
        hasCompletedCurrentResponse = false
        runGeneration += 1
        isConnectionSuspended = false
        if (replayAfterSeq == null) {
            lastEventId = null
        }
        state.update { it.copy(activeStreamId = streamId, liveTokensPerSecond = null) }

        markConnectionStarted(isReplay = replayAfterSeq != null, recoveryState = recoveryState)

        connectionJob?.cancel()
        connectionJob = scope.launch {
            sse.connect(streamId, replayAfterSeq).collect { event ->
                handle(event)
            }
        }
        delegate?.streamCoordinatorStartAuxiliaryMonitoring()
    }

    /** Port of `cancelActiveStream()` — throws [ApiError] on request failure. */
    suspend fun cancelActiveStream(): ChatCancelResponse? {
        val streamId = activeStreamId ?: return null

        val response = api.cancelChat(streamId)
        if (activeStreamId != streamId) return response
        if (response.ok == false) return response

        finishStream()
        return response
    }

    /**
     * Port of `suspendActiveStreamConnection()` — drops the SSE connection
     * (keeping [activeStreamId]) so [reconnectIfNeeded] can resume later.
     * Cheap no-op when there is nothing to suspend.
     */
    fun suspendActiveStreamConnection() {
        if (activeStreamId == null || hasCompletedCurrentResponse || isConnectionSuspended) return

        lastEventId = sse.lastEventId ?: lastEventId
        delegate?.streamCoordinatorFlushPendingStreamingContent()
        isConnectionSuspended = true
        stopConnection()
        delegate?.streamCoordinatorStopAuxiliaryMonitoring(clearPrompt = true)
    }

    /** Port of `prepareForSessionLoad()`. */
    fun prepareForSessionLoad(): ChatStreamLoadPreparation {
        state.update { it.copy(liveTokensPerSecond = null) }
        val activeStreamIdBeforeLoad = activeStreamId
        if (activeStreamIdBeforeLoad != null && !hasCompletedCurrentResponse) {
            delegate?.streamCoordinatorFlushPendingStreamingContent()
        }

        return ChatStreamLoadPreparation(
            activeStreamIdBeforeLoad = activeStreamIdBeforeLoad,
            shouldPrepareSuspendedStreamResume = activeStreamId == null || isConnectionSuspended,
        )
    }

    /** Port of `reconcileSessionLoad(loadedActiveStreamID:preparation:usedCacheFallback:)`. */
    fun reconcileSessionLoad(
        loadedActiveStreamId: String?,
        preparation: ChatStreamLoadPreparation,
        usedCacheFallback: Boolean,
    ) {
        hasCompletedCurrentResponse = false
        state.update { it.copy(liveTokensPerSecond = null) }

        if (usedCacheFallback) {
            state.update { it.copy(activeStreamId = null) }
            isConnectionSuspended = false
            delegate?.streamCoordinatorStreamingAssistantMessageId = null
            resetRecoveryState()
            return
        }

        val trimmedLoadedStreamId = loadedActiveStreamId?.trim()
        if (preparation.shouldPrepareSuspendedStreamResume) {
            delegate?.streamCoordinatorStreamingAssistantMessageId = null
            if (!trimmedLoadedStreamId.isNullOrEmpty()) {
                state.update { it.copy(activeStreamId = trimmedLoadedStreamId) }
                delegate?.streamCoordinatorStreamingAssistantMessageId =
                    delegate?.streamCoordinatorLatestAssistantMessageId()
                isConnectionSuspended = true
            } else {
                state.update { it.copy(activeStreamId = null) }
                isConnectionSuspended = false
                resetRecoveryState()
            }
        } else {
            val streamId = if (!trimmedLoadedStreamId.isNullOrEmpty()) {
                trimmedLoadedStreamId
            } else {
                preparation.activeStreamIdBeforeLoad
            }
            if (streamId != null) {
                state.update { it.copy(activeStreamId = streamId) }
                delegate?.streamCoordinatorStreamingAssistantMessageId =
                    delegate?.streamCoordinatorLatestAssistantMessageId()
            }
            isConnectionSuspended = false
        }
    }

    /**
     * Port of `reconnectIfNeeded(modelContext:)`: cheap no-op unless a stream
     * is suspended. `active` → refresh transcript and resume live; otherwise
     * `replay_available` → reconnect with `replay=1&after_seq=<last seen>`
     * (NEVER re-sending the user message); otherwise the run is over →
     * refresh and finalize.
     */
    suspend fun reconnectIfNeeded() {
        val streamId = activeStreamId ?: return
        if (!isConnectionSuspended) return
        val generation = runGeneration

        try {
            val response = api.chatStreamStatus(streamId)
            if (activeStreamId != streamId || !isConnectionSuspended) return

            when {
                response.active == true -> {
                    delegate?.streamCoordinatorLoadMessages()
                    if (activeStreamId != streamId || !isConnectionSuspended) return

                    if (delegate?.streamCoordinatorStreamingAssistantMessageId == null) {
                        delegate?.streamCoordinatorStreamingAssistantMessageId =
                            delegate?.streamCoordinatorLatestAssistantMessageId()
                    }
                    isConnectionSuspended = false
                    start(streamId)
                }

                response.replayAvailable == true -> {
                    val replayAfterSeq = runJournalReplayAfterSeq(lastEventId) ?: 0
                    isConnectionSuspended = false
                    start(streamId, replayAfterSeq = replayAfterSeq)
                }

                else -> {
                    delegate?.streamCoordinatorLoadMessages()
                    // Bail if a concurrent completion/cancel/new run finalized or
                    // replaced this run during the load (canFinalizeRunAfterLoad).
                    if (!canFinalizeRunAfterLoad(streamId, generation)) return

                    // The server reports the run is over. Finalize it instead of
                    // leaving it dangling on "running" (iOS #246).
                    finalizeInactiveStream()
                }
            }
        } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            if ((error as? ApiError)?.indicatesMissingStream == true &&
                activeStreamId == streamId &&
                isConnectionSuspended
            ) {
                delegate?.streamCoordinatorLoadMessages()
                if (!canFinalizeRunAfterLoad(streamId, generation)) return
                finalizeInactiveStream()
                return
            }
            delegate?.streamCoordinatorDidReceiveRecoveryError(error)
        }
    }

    /** Port of `clearReplayConnection()`. */
    fun clearReplayConnection() {
        isReplayConnection = false
    }

    /**
     * Hard reset for a session switch: drop the connection and every
     * stream-scoped flag without running the finish-stream side effects
     * (queued-message drain, notice flush) that belong to the OLD session.
     * iOS never needs this — it builds a fresh ViewModel per session.
     */
    fun resetForSessionSwitch() {
        stopConnection()
        state.update { it.copy(activeStreamId = null, liveTokensPerSecond = null) }
        lastEventId = null
        hasCompletedCurrentResponse = false
        isConnectionSuspended = false
        resetRecoveryState()
    }

    // MARK: - Event handling (port of `handle(_:)`)

    private fun handle(event: ChatStreamEvent) {
        lastEventId = sse.lastEventId ?: lastEventId

        when (event) {
            is ChatStreamEvent.Token -> {
                if (delegate?.streamCoordinatorAppendToken(event.text) == true) markProgress()
            }

            is ChatStreamEvent.InterimAssistant -> {
                if (delegate?.streamCoordinatorAppendInterimAssistant(event.event) == true) markProgress()
            }

            is ChatStreamEvent.Reasoning -> {
                if (delegate?.streamCoordinatorAppendReasoning(event.text) == true) markProgress()
            }

            is ChatStreamEvent.ToolStarted -> {
                if (delegate?.streamCoordinatorAppendToolCall(event.event) == true) markProgress()
            }

            is ChatStreamEvent.ToolCompleted -> {
                if (delegate?.streamCoordinatorCompleteToolCall(event.event) == true) markProgress()
            }

            is ChatStreamEvent.Title -> {
                if (delegate?.streamCoordinatorUpdateTitle(event.event) == true) markProgress()
            }

            is ChatStreamEvent.Metering -> {
                val payload = event.event
                if (payload.sessionId == null || payload.sessionId == delegate?.streamCoordinatorSessionId) {
                    state.update { it.copy(liveTokensPerSecond = payload.displayableTokensPerSecond) }
                }
            }

            is ChatStreamEvent.Done -> {
                val hasCompletedTranscript = delegate?.streamCoordinatorApplyDone(event.event) == true
                completeCurrentResponse(needsTranscriptRefresh = !hasCompletedTranscript)
            }

            is ChatStreamEvent.ApprovalPending -> {
                delegate?.streamCoordinatorApplyApprovalUpdate(event.response)
                markProgress()
            }

            is ChatStreamEvent.ClarificationPending -> {
                delegate?.streamCoordinatorApplyClarificationUpdate(event.response)
                markProgress()
            }

            is ChatStreamEvent.PendingSteerLeftover -> {
                if (delegate?.streamCoordinatorEnqueuePendingSteerLeftover(event.text) == true) markProgress()
            }

            ChatStreamEvent.StreamEnd -> finishStream()

            ChatStreamEvent.Cancelled -> finishStream()

            is ChatStreamEvent.Error -> {
                if (!hasCompletedCurrentResponse) {
                    delegate?.streamCoordinatorDidReceiveErrorMessage(event.message)
                }
                finishStream()
            }

            is ChatStreamEvent.TransportError -> handleTransportError(event.message)

            ChatStreamEvent.Heartbeat -> {
                // A heartbeat proves the transport is alive without semantic
                // progress — drop an already-shown "Checking stream" state.
                if (state.value.recoveryState == ActiveStreamRecoveryState.CHECKING) {
                    state.update { it.copy(recoveryState = ActiveStreamRecoveryState.IDLE) }
                }
            }

            ChatStreamEvent.Ignored -> Unit
        }
    }

    /** Port of `handleTransportError(_:)`: suspend, then one status-checked reconnect. */
    private fun handleTransportError(message: String) {
        state.update { it.copy(liveTokensPerSecond = null) }
        if (activeStreamId == null || hasCompletedCurrentResponse) {
            if (!hasCompletedCurrentResponse) {
                delegate?.streamCoordinatorDidReceiveErrorMessage(message)
            }
            finishStream()
            return
        }

        if (isConnectionSuspended) return

        lastEventId = sse.lastEventId ?: lastEventId
        delegate?.streamCoordinatorFlushPendingStreamingContent()
        isConnectionSuspended = true
        state.update { it.copy(recoveryState = ActiveStreamRecoveryState.RECONNECTING) }
        stopConnection()
        delegate?.streamCoordinatorStopAuxiliaryMonitoring(clearPrompt = true)

        scope.launch { reconnectIfNeeded() }
    }

    // MARK: - Finalization

    private fun completeCurrentResponse(needsTranscriptRefresh: Boolean) {
        runGeneration += 1
        delegate?.streamCoordinatorStopAuxiliaryMonitoring(clearPrompt = true)
        state.update { it.copy(activeStreamId = null, liveTokensPerSecond = null) }
        lastEventId = null
        delegate?.streamCoordinatorStreamingAssistantMessageId = null
        hasCompletedCurrentResponse = true
        delegate?.streamCoordinatorDidCompleteCurrentResponse(needsTranscriptRefresh)
        resetRecoveryState()
    }

    /**
     * Whether this coordinator may still finalize the run captured before an
     * awaited transcript load (port of `canFinalizeRunAfterLoad`).
     */
    private fun canFinalizeRunAfterLoad(streamId: String, capturedGeneration: Int): Boolean {
        if (runGeneration != capturedGeneration) return false
        return activeStreamId == null || activeStreamId == streamId
    }

    /**
     * Port of `finalizeInactiveStream(streamID:)`: the server reports this
     * stream is no longer active — complete from the just-refreshed transcript
     * when an assistant reply surfaced, otherwise finalize as failed.
     */
    private fun finalizeInactiveStream() {
        if (delegate?.streamCoordinatorLatestServerLoadHadAssistantResponseAfterLatestUser == true) {
            completeCurrentResponse(needsTranscriptRefresh = false)
            finishStream()
        } else {
            finishStream()
        }
    }

    /** Port of `finishStream()`. */
    private fun finishStream() {
        runGeneration += 1
        val completedNormally = hasCompletedCurrentResponse
        stopConnection()
        delegate?.streamCoordinatorStopAuxiliaryMonitoring(clearPrompt = true)
        delegate?.streamCoordinatorFlushPinnedLocalNoticesToTranscript()
        state.update { it.copy(activeStreamId = null, liveTokensPerSecond = null) }
        lastEventId = null
        delegate?.streamCoordinatorStreamingAssistantMessageId = null
        hasCompletedCurrentResponse = false
        delegate?.streamCoordinatorDidFinishStream()
        isConnectionSuspended = false
        resetRecoveryState()
        delegate?.streamCoordinatorDrainQueuedSlashMessageIfIdle()
        if (completedNormally) {
            delegate?.streamCoordinatorRefreshCompletedResponseTitleIfNeeded()
        }
    }

    private fun stopConnection() {
        connectionJob?.cancel()
        connectionJob = null
    }

    private fun markConnectionStarted(isReplay: Boolean, recoveryState: ActiveStreamRecoveryState) {
        state.update { it.copy(recoveryState = recoveryState) }
        isReplayConnection = isReplay
        delegate?.streamCoordinatorDidStartConnection(isReplay)
    }

    private fun markProgress() {
        if (state.value.recoveryState != ActiveStreamRecoveryState.IDLE) {
            state.update { it.copy(recoveryState = ActiveStreamRecoveryState.IDLE) }
        }
    }

    private fun resetRecoveryState() {
        state.update { it.copy(recoveryState = ActiveStreamRecoveryState.IDLE) }
        isReplayConnection = false
        delegate?.streamCoordinatorDidResetRecoveryState()
    }

    companion object {
        /**
         * Port of `runJournalReplayAfterSeq(from:)`: the server's SSE `id:` is
         * `<run>:<seq>` (or a bare integer); the replay cursor is the integer
         * after the last `:`.
         */
        fun runJournalReplayAfterSeq(eventId: String?): Int? {
            val trimmed = eventId?.trim()
            if (trimmed.isNullOrEmpty()) return null

            val sequenceText = trimmed.substringAfterLast(':')
            val sequence = sequenceText.toIntOrNull() ?: return null
            return maxOf(0, sequence)
        }
    }
}
