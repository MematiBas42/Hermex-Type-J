package io.github.jcartu.hermex.ui.feature.chat

import io.github.jcartu.hermex.model.ApprovalPendingResponse
import io.github.jcartu.hermex.model.ChatCancelResponse
import io.github.jcartu.hermex.model.ClarificationPendingResponse
import io.github.jcartu.hermex.net.ApiError
import io.github.jcartu.hermex.net.ChatStreamEvent
import io.github.jcartu.hermex.net.DoneStreamEvent
import io.github.jcartu.hermex.net.HermesApi
import io.github.jcartu.hermex.net.InterimAssistantStreamEvent
import io.github.jcartu.hermex.net.SseChatStream
import io.github.jcartu.hermex.net.TitleStreamEvent
import io.github.jcartu.hermex.net.ToolStreamEvent
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Port of `ChatStreamCoordinatorTiming` (seconds → millis). */
data class ChatStreamCoordinatorTiming(
    val checkingMillis: Long,
    val reconnectMillis: Long,
    val runningToolReconnectMillis: Long,
    val statusPollCooldownMillis: Long,
    /**
     * Transport quieter than this is treated as provably alive; must sit above
     * the server's ~5s SSE heartbeat cadence and below [reconnectMillis] (#227).
     */
    val transportFreshMillis: Long,
    /** Cadence of the stale-stream watchdog tick (iOS drives it from the view's timer). */
    val watchdogTickMillis: Long = 1_000,
) {
    companion object {
        val standard = ChatStreamCoordinatorTiming(
            checkingMillis = 5_000,
            reconnectMillis = 18_000,
            runningToolReconnectMillis = 25_000,
            statusPollCooldownMillis = 4_000,
            transportFreshMillis = 12_000,
        )
    }
}

/** Port of `ChatStreamReconnectBackoff`: three retries at 1s, 2s and 4s after the initial probe (#537). */
object ChatStreamReconnectBackoff {
    val delaysMillis: List<Long> = listOf(1_000, 2_000, 4_000)
    val maxProbeAttempts: Int = delaysMillis.size + 1
}

/** Port of `ChatStreamLoadPreparation` from `ChatStreamCoordinator.swift`. */
data class ChatStreamLoadPreparation(
    val activeStreamIdBeforeLoad: String?,
    val shouldPrepareSuspendedStreamResume: Boolean,
)

/** Port of `ChatStreamSnapshotRestoreResult`: whether a run's snapshot was adopted, and the SSE cursor it carried. */
data class ChatStreamSnapshotRestoreResult(
    val didRestoreSnapshot: Boolean,
    val lastEventId: String?,
) {
    companion object {
        val notRestored = ChatStreamSnapshotRestoreResult(didRestoreSnapshot = false, lastEventId = null)
    }
}

/** Port of `ChatRunEnding`: span of a finished run and how it ended (epoch seconds). */
data class ChatRunEndingRecord(
    val startedAt: Double,
    val endedAt: Double,
    val ending: ChatRunEnding,
)

/**
 * Port of `ChatStreamCoordinatorDelegate` (minus the iOS Live Activity
 * hooks). The snapshot trio mirrors Swift exactly: the coordinator asks the
 * view model to save the live run at every boundary it may lose the
 * connection (suspend, transport error, stale reconnect, session reload),
 * to restore it when a load adopts the same run, and to drop it once the run
 * ends.
 */
interface ChatStreamCoordinatorDelegate {
    val streamCoordinatorSessionId: String?
    val streamCoordinatorHasRunningLiveToolCall: Boolean
    val streamCoordinatorHasPendingPrompt: Boolean
    val streamCoordinatorLatestServerLoadHadAssistantResponseAfterLatestUser: Boolean

    /** Setting null flushes pending streamed content first (Swift setter side effect). */
    var streamCoordinatorStreamingAssistantMessageId: String?

    suspend fun streamCoordinatorLoadMessages()
    fun streamCoordinatorLatestAssistantMessageId(): String?
    fun streamCoordinatorStartAuxiliaryMonitoring()
    fun streamCoordinatorStopAuxiliaryMonitoring(clearPrompt: Boolean)
    fun streamCoordinatorSaveSnapshotIfNeeded()
    fun streamCoordinatorRestoreSnapshotIfAvailable(streamId: String): ChatStreamSnapshotRestoreResult
    fun streamCoordinatorRemoveSnapshot(streamId: String?)
    fun streamCoordinatorFlushPinnedLocalNoticesToTranscript()
    fun streamCoordinatorDrainQueuedSlashMessageIfIdle()
    fun streamCoordinatorRefreshCompletedResponseTitleIfNeeded()
    fun streamCoordinatorDidCompleteCurrentResponse(needsTranscriptRefresh: Boolean)
    fun streamCoordinatorDidFinishStream()
    fun streamCoordinatorDidReceiveErrorMessage(message: String)
    fun streamCoordinatorDidReceiveRecoveryError(error: Throwable)
    fun streamCoordinatorDidConfirmRecovery()
    /**
     * [isReplay] is true only when a full-history replay may repeat restored
     * content and the delegate should arm prefix deduplication. A journal
     * suffix resumed after a trusted positive cursor reports false.
     */
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
 * lifecycle: start, suspend-for-background, single-flight status-checked
 * reconnect with journal replay and bounded retries, the offline wait
 * (#869), the stale-stream watchdog (#227), the terminal-content fence
 * (#288) and the run-start / run-ending bookkeeping the transcript folds on.
 *
 * `activeStreamId`, `recoveryState`, `isStreamHeartbeatFresh`,
 * `liveTokensPerSecond`, `activeRunStartedAt` live on the shared [ChatUiState]
 * flow; connection-internal flags stay here. Everything runs on [scope]'s
 * main dispatcher, the Kotlin equivalent of the Swift `@MainActor`.
 */
class ChatStreamCoordinator(
    private val api: HermesApi,
    private val sse: SseChatStream,
    private val state: MutableStateFlow<ChatUiState>,
    private val scope: CoroutineScope,
    private val timing: ChatStreamCoordinatorTiming = ChatStreamCoordinatorTiming.standard,
    private val isNetworkAvailable: () -> Boolean = { true },
    private val reconnectDelay: suspend (Long) -> Unit = { delay(it) },
    private val now: () -> Long = System::currentTimeMillis,
    private val enableWatchdog: Boolean = true,
) {
    private var delegate: ChatStreamCoordinatorDelegate? = null
    private var connectionJob: Job? = null
    private var watchdogJob: Job? = null

    val activeStreamId: String? get() = state.value.activeStreamId

    var hasCompletedCurrentResponse = false
        private set

    var isConnectionSuspended = false
        private set

    /**
     * Whether the current connection can repeat the restored response prefix.
     * The name is retained for the delegate/VM contract; a replay transport
     * resumed after a trusted positive cursor deliberately leaves this false.
     */
    var isReplayConnection = false
        private set

    var lastEventId: String? = null
        private set

    /** The last run's span and how it ended; cleared when the next run starts. */
    var latestRunEnding: ChatRunEndingRecord? = null
        private set

    /**
     * Terminal-content fence (#288 review): set when the response completes and
     * NOT cleared by finishStream, so content queued after `done → streamEnd`
     * still cannot reach the transcript. Cleared only by the next run start /
     * new-response preparation / session load.
     */
    private var isTerminalContentFenceActive = false

    /**
     * Per-run one-shot teardown owner. The first caller of finishStream owns
     * teardown; later terminal events are ignored so delegate finish, queue
     * drain and title-refresh side effects cannot repeat.
     */
    private var isTransportFinished = false

    private var lastProgressMillis: Long? = null
    private var lastTransportActivityMillis: Long? = null
    private var lastRecoveryStatusCheckMillis: Long? = null
    private var hasInMemorySnapshotForActiveStream = false

    /**
     * Foreground activation and view appearance can both request recovery for
     * the same suspended stream. One shared reconnect task keeps callers from
     * duplicating status checks or transcript loads; identity and generation
     * fence late work from a replacement run.
     */
    private data class ReconnectTask(
        val id: UUID,
        val streamId: String,
        val runGeneration: Int,
        val job: Job,
    )

    private var reconnectTask: ReconnectTask? = null
    private var reconnectTranscriptLoadTaskId: UUID? = null

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
        isTerminalContentFenceActive = false
        isTransportFinished = false
        isConnectionSuspended = false
        setLiveTokensPerSecondIfChanged(null)
        hasInMemorySnapshotForActiveStream = false
        invalidateReconnectTask()
    }

    /**
     * Port of `start(streamID:replayAfterSeq:recoveryState:runStartedAt:)`.
     * [runStartedAt] (epoch seconds) is the run's real start when the caller
     * knows it — the server's `pending_started_at` from `/api/chat/start`, else
     * the local send time. Reconnects and replays pass null: they rejoin the
     * same stream, whose start is already recorded.
     */
    fun start(
        streamId: String,
        replayAfterSeq: Int? = null,
        recoveryState: ActiveStreamRecoveryState = ActiveStreamRecoveryState.IDLE,
        runStartedAt: Double? = null,
    ) {
        hasCompletedCurrentResponse = false
        isTerminalContentFenceActive = false
        isTransportFinished = false
        setLiveTokensPerSecondIfChanged(null)
        runGeneration += 1
        invalidateReconnectTask()
        setActiveStreamId(streamId)
        seedActiveRunStart(runStartedAt)
        hasInMemorySnapshotForActiveStream = false
        isConnectionSuspended = false
        if (replayAfterSeq == null) {
            lastEventId = null
        }

        val usesJournalReplay = replayAfterSeq != null
        val allowReplayDedup = replayAfterSeq != null && replayAfterSeq <= 0
        markConnectionStarted(
            isReplay = usesJournalReplay,
            allowReplayDedup = allowReplayDedup,
            recoveryState = recoveryState,
        )

        connectionJob?.cancel()
        connectionJob = scope.launch {
            sse.connect(streamId, replayAfterSeq).collect { event ->
                handle(event)
            }
        }
        startWatchdog()
        delegate?.streamCoordinatorStartAuxiliaryMonitoring()
    }

    /** Port of `cancelActiveStream()` — throws [ApiError] on request failure. */
    suspend fun cancelActiveStream(): ChatCancelResponse? {
        val streamId = activeStreamId ?: return null

        val response = api.cancelChat(streamId)
        if (activeStreamId != streamId) return response
        if (response.ok == false) return response

        finishStream(ending = ChatRunEnding.CANCELLED)
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
        delegate?.streamCoordinatorSaveSnapshotIfNeeded()
        hasInMemorySnapshotForActiveStream = true
        isConnectionSuspended = true
        stopConnection()
        delegate?.streamCoordinatorStopAuxiliaryMonitoring(clearPrompt = true)
    }

    /** Port of `prepareForSessionLoad()`. */
    fun prepareForSessionLoad(): ChatStreamLoadPreparation {
        setLiveTokensPerSecondIfChanged(null)
        val activeStreamIdBeforeLoad = activeStreamId
        if (activeStreamIdBeforeLoad != null && !hasCompletedCurrentResponse) {
            delegate?.streamCoordinatorSaveSnapshotIfNeeded()
        }

        return ChatStreamLoadPreparation(
            activeStreamIdBeforeLoad = activeStreamIdBeforeLoad,
            shouldPrepareSuspendedStreamResume = activeStreamId == null || isConnectionSuspended,
        )
    }

    /** Port of `shouldPreserveLocalOptimisticMessages(for:loadedActiveStreamID:)`. */
    fun shouldPreserveLocalOptimisticMessages(
        preparation: ChatStreamLoadPreparation,
        loadedActiveStreamId: String?,
    ): Boolean {
        val before = preparation.activeStreamIdBeforeLoad ?: return false
        // A same-stream `start()` (foreground reconnect / replay) bumps
        // `runGeneration` without replacing the server run; stream identity is
        // the successor fence.
        if (activeStreamId != before) return false
        return loadedActiveStreamId?.trim() == before
    }

    /**
     * Port of `reconcileSessionLoad(loadedActiveStreamID:preparation:usedCacheFallback:runStartedAt:)`.
     * [runStartedAt] is when the loaded session says its in-flight turn started
     * (`pending_started_at`, else the latest user message's timestamp), so
     * returning to a session never restarts its "Working for" counter.
     */
    fun reconcileSessionLoad(
        loadedActiveStreamId: String?,
        preparation: ChatStreamLoadPreparation,
        usedCacheFallback: Boolean,
        runStartedAt: Double? = null,
    ) {
        hasCompletedCurrentResponse = false
        if (!loadedActiveStreamId.isNullOrBlank()) isTerminalContentFenceActive = false
        isTransportFinished = false
        setLiveTokensPerSecondIfChanged(null)
        try {
            if (usedCacheFallback) {
                setActiveStreamId(null)
                hasInMemorySnapshotForActiveStream = false
                isConnectionSuspended = false
                delegate?.streamCoordinatorStreamingAssistantMessageId = null
                resetRecoveryState()
                return
            }

            val trimmedLoadedStreamId = loadedActiveStreamId?.trim()
            if (preparation.shouldPrepareSuspendedStreamResume) {
                delegate?.streamCoordinatorStreamingAssistantMessageId = null
                if (!trimmedLoadedStreamId.isNullOrEmpty()) {
                    setActiveStreamId(trimmedLoadedStreamId)
                    seedActiveRunStart(runStartedAt)
                    delegate?.streamCoordinatorStreamingAssistantMessageId =
                        delegate?.streamCoordinatorLatestAssistantMessageId()
                    isConnectionSuspended = true
                    restoreLoadedStreamSnapshot(trimmedLoadedStreamId, preparation)
                } else {
                    setActiveStreamId(null)
                    hasInMemorySnapshotForActiveStream = false
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
                    setActiveStreamId(streamId)
                    seedActiveRunStart(runStartedAt)
                    delegate?.streamCoordinatorStreamingAssistantMessageId =
                        delegate?.streamCoordinatorLatestAssistantMessageId()
                    restoreLoadedStreamSnapshot(streamId, preparation)
                    if (delegate?.streamCoordinatorStreamingAssistantMessageId == null) {
                        delegate?.streamCoordinatorStreamingAssistantMessageId =
                            delegate?.streamCoordinatorLatestAssistantMessageId()
                    }
                }
                isConnectionSuspended = false
            }
        } finally {
            invalidateReconnectTaskIfItDoesNotMatchCurrentStream()
        }
    }

    /**
     * Port of `reconnectIfNeeded(modelContext:)`: cheap no-op unless a stream
     * is suspended. Shares one in-flight reconnect across callers, so
     * foreground activation and view appearance never race each other (1.6.0).
     */
    suspend fun reconnectIfNeeded() {
        val streamId = activeStreamId ?: return
        if (!isConnectionSuspended) return
        reconnectTask?.let { existing ->
            existing.job.join()
            return
        }

        val reconnectTaskId = UUID.randomUUID()
        val reconnectGeneration = runGeneration
        val job = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            performReconnectIfNeeded(
                reconnectTaskId = reconnectTaskId,
                streamId = streamId,
                runGeneration = reconnectGeneration,
            )
            if (reconnectTask?.id == reconnectTaskId) {
                reconnectTask = null
            }
        }
        reconnectTask = ReconnectTask(
            id = reconnectTaskId,
            streamId = streamId,
            runGeneration = reconnectGeneration,
            job = job,
        )
        job.start()
        job.join()
    }

    /**
     * Port of `networkPathDidChange(modelContext:)` (#869): a suspended stream
     * retries at once through the single-flight reconnect, so a flapping path
     * joins one attempt. A live stream that was waiting drops back to idle, and
     * the stale-stream watchdog decides whether to probe.
     */
    suspend fun networkPathDidChange() {
        if (isConnectionSuspended) {
            reconnectIfNeeded()
        } else if (state.value.recoveryState == ActiveStreamRecoveryState.WAITING_FOR_NETWORK) {
            setRecoveryStateIfChanged(ActiveStreamRecoveryState.IDLE)
        }
    }

    private suspend fun performReconnectIfNeeded(
        reconnectTaskId: UUID,
        streamId: String,
        runGeneration: Int,
    ) {
        for (attempt in 0 until ChatStreamReconnectBackoff.maxProbeAttempts) {
            if (!reconnectTaskIsCurrent(reconnectTaskId, streamId, runGeneration)) return
            // Offline: end the attempt uncounted and stay suspended; the
            // network's return starts a fresh one.
            if (!networkAllowsStatusProbe()) return
            val runStartedAt = state.value.activeRunStartedAt

            try {
                val response = api.chatStreamStatus(streamId)
                if (!reconnectTaskIsCurrent(reconnectTaskId, streamId, runGeneration)) return
                delegate?.streamCoordinatorDidConfirmRecovery()

                when {
                    response.active == true -> {
                        val completedLoad = loadMessagesForReconnect(reconnectTaskId)
                        if (!completedLoad || !reconnectTaskIsCurrent(reconnectTaskId, streamId, runGeneration)) return

                        if (delegate?.streamCoordinatorStreamingAssistantMessageId == null) {
                            restoreSnapshotIfAvailable(streamId)
                        }
                        if (delegate?.streamCoordinatorStreamingAssistantMessageId == null) {
                            delegate?.streamCoordinatorStreamingAssistantMessageId =
                                delegate?.streamCoordinatorLatestAssistantMessageId()
                        }
                        // Resume from the last event this process rendered (#599): a
                        // cursorless attach can replay the run from the start and
                        // duplicate text already on screen. A cold process with no
                        // snapshot asks the journal for the whole run so the loaded
                        // partial transcript fills in before `done`.
                        val replayAfterSeq: Int? = when {
                            response.replayAvailable != true -> null
                            runJournalReplayAfterSeq(lastEventId) != null -> runJournalReplayAfterSeq(lastEventId)
                            hasInMemorySnapshotForActiveStream || lastEventId != null -> null
                            else -> 0
                        }
                        isConnectionSuspended = false
                        start(streamId, replayAfterSeq = replayAfterSeq)
                    }

                    response.replayAvailable == true -> {
                        val replayAfterSeq = runJournalReplayAfterSeq(lastEventId) ?: 0
                        isConnectionSuspended = false
                        start(streamId, replayAfterSeq = replayAfterSeq)
                    }

                    else -> {
                        val completedLoad = loadMessagesForReconnect(reconnectTaskId)
                        if (!completedLoad ||
                            !reconnectTaskOwnsFinalization(reconnectTaskId, streamId, runGeneration) ||
                            !canFinalizeRunAfterLoad(streamId, runGeneration)
                        ) {
                            return
                        }
                        // #246: the server reports the run is over. Finalize it instead
                        // of re-arming and leaving it dangling on "running".
                        finalizeInactiveStream(streamId, runStartedAt)
                    }
                }
                return
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                if ((error as? ApiError)?.indicatesMissingStream == true &&
                    reconnectTaskIsCurrent(reconnectTaskId, streamId, runGeneration)
                ) {
                    val completedLoad = loadMessagesForReconnect(reconnectTaskId)
                    if (!completedLoad ||
                        !reconnectTaskOwnsFinalization(reconnectTaskId, streamId, runGeneration) ||
                        !canFinalizeRunAfterLoad(streamId, runGeneration)
                    ) {
                        return
                    }
                    finalizeInactiveStream(streamId, runStartedAt)
                    return
                }

                val isTransientFailure = isTransientReconnectFailure(error)
                // The probe failed because the network went away: wait for it
                // instead of spending the rest of the budget on an error (#869).
                if (isTransientFailure && !isNetworkAvailable()) {
                    if (reconnectTaskIsCurrent(reconnectTaskId, streamId, runGeneration)) {
                        setRecoveryStateIfChanged(ActiveStreamRecoveryState.WAITING_FOR_NETWORK)
                    }
                    return
                }

                val canRetry = isTransientFailure && attempt < ChatStreamReconnectBackoff.delaysMillis.size
                if (!canRetry) {
                    if (!reconnectTaskIsCurrent(reconnectTaskId, streamId, runGeneration)) return
                    delegate?.streamCoordinatorDidReceiveRecoveryError(error)
                    return
                }

                reconnectDelay(ChatStreamReconnectBackoff.delaysMillis[attempt])
            }
        }
    }

    /**
     * Port of `refreshTranscriptIfCompleted(streamID:)`: finishes the run from
     * the server's transcript when the server says it ended and its reply is
     * saved; otherwise leaves the live SSE to finish it. Used by the foreground
     * refresh and by a steer that found the run ended.
     */
    suspend fun refreshTranscriptIfCompleted(expectedStreamId: String) {
        if (activeStreamId != expectedStreamId || isConnectionSuspended) return
        val generation = runGeneration

        try {
            val response = api.chatStreamStatus(expectedStreamId)
            if (activeStreamId != expectedStreamId || isConnectionSuspended) return
            delegate?.streamCoordinatorDidConfirmRecovery()
            if (response.active != false) return

            delegate?.streamCoordinatorLoadMessages()
            if (!canFinalizeRunAfterLoad(expectedStreamId, generation)) return

            if (delegate?.streamCoordinatorLatestServerLoadHadAssistantResponseAfterLatestUser != true) {
                // Foreground safety net: the live SSE is still connected and owns
                // completion, so a status poll that briefly reports inactive must
                // not finalize the run — keep waiting for the real `done`.
                setActiveStreamId(expectedStreamId)
                isConnectionSuspended = false
                return
            }

            completeResponseFromRefreshedTranscriptAndFinishStream(expectedStreamId)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            // The primary SSE path owns visible stream errors; a failed status
            // poll must not interrupt it.
        }
    }

    /**
     * Port of `recoverStaleStreamIfNeeded(now:)`: the watchdog tick. Heartbeats
     * within `transportFreshMillis` keep the chip hidden and skip status polls
     * (#227); a genuinely silent transport escalates to a status check and,
     * past the reconnect interval, a replay reconnect.
     */
    suspend fun recoverStaleStreamIfNeeded(nowMillis: Long = now()) {
        val streamId = activeStreamId
        if (streamId == null || isConnectionSuspended || hasCompletedCurrentResponse) {
            setRecoveryStateIfChanged(ActiveStreamRecoveryState.IDLE)
            return
        }

        if (delegate?.streamCoordinatorHasPendingPrompt == true) {
            setRecoveryStateIfChanged(ActiveStreamRecoveryState.IDLE)
            return
        }

        val reconnectInterval = if (delegate?.streamCoordinatorHasRunningLiveToolCall == true) {
            timing.runningToolReconnectMillis
        } else {
            timing.reconnectMillis
        }
        val lastProgress = lastProgressMillis
        if (lastProgress == null) {
            val lastTransportActivity = lastTransportActivityMillis
            if (lastTransportActivity == null || nowMillis - lastTransportActivity < reconnectInterval) {
                setRecoveryStateIfChanged(ActiveStreamRecoveryState.IDLE)
                return
            }

            if (!networkAllowsStatusProbe()) return
            setRecoveryStateIfChanged(ActiveStreamRecoveryState.CHECKING)
            lastRecoveryStatusCheckMillis = nowMillis
            recoverStaleStream(streamId, forceReconnect = true)
            return
        }

        val elapsed = nowMillis - lastProgress
        if (elapsed < timing.checkingMillis) {
            setRecoveryStateIfChanged(ActiveStreamRecoveryState.IDLE)
            return
        }

        val transportElapsed = nowMillis - (lastTransportActivityMillis ?: lastProgress)
        if (transportElapsed < timing.transportFreshMillis) {
            // #227: heartbeats prove the connection is alive during a
            // semantically quiet window (model thinking / slow tool call), so
            // stay idle and skip status polls.
            setRecoveryStateIfChanged(ActiveStreamRecoveryState.IDLE)
            return
        }

        if (!networkAllowsStatusProbe()) return
        setRecoveryStateIfChanged(ActiveStreamRecoveryState.CHECKING)
        val shouldForceReconnect = transportElapsed >= reconnectInterval
        if (!shouldForceReconnect && !shouldPollStatus(nowMillis)) return

        lastRecoveryStatusCheckMillis = nowMillis
        recoverStaleStream(streamId, forceReconnect = shouldForceReconnect)
    }

    /** Port of `markProgress(now:)`. */
    fun markProgress(nowMillis: Long = now()) {
        delegate?.streamCoordinatorDidConfirmRecovery()
        lastProgressMillis = nowMillis
        lastTransportActivityMillis = nowMillis
        lastRecoveryStatusCheckMillis = null
        setRecoveryStateIfChanged(ActiveStreamRecoveryState.IDLE)
        refreshHeartbeatFreshness(nowMillis)
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
        stopWatchdog()
        invalidateReconnectTask()
        setActiveStreamId(null)
        setLiveTokensPerSecondIfChanged(null)
        latestRunEnding = null
        state.update { it.copy(latestRunOutcome = null) }
        lastEventId = null
        hasCompletedCurrentResponse = false
        isTerminalContentFenceActive = false
        isTransportFinished = false
        isConnectionSuspended = false
        hasInMemorySnapshotForActiveStream = false
        resetRecoveryState()
    }

    // MARK: - Event handling (port of `handle(_:)`)

    private fun handle(event: ChatStreamEvent) {
        // Terminal-content fence (#288): once the response has completed, late
        // content must never reach the transcript. Title and metering are
        // session metadata and still pass; a settled completion is never
        // re-ended by late error/cancel.
        if (isTerminalContentFenceActive) {
            when (event) {
                is ChatStreamEvent.Title -> {
                    if (delegate?.streamCoordinatorUpdateTitle(event.event) == true) markProgress()
                }

                is ChatStreamEvent.Metering -> {
                    val payload = event.event
                    if (payload.sessionId == null || payload.sessionId == delegate?.streamCoordinatorSessionId) {
                        setLiveTokensPerSecondIfChanged(payload.displayableTokensPerSecond)
                    }
                }

                is ChatStreamEvent.Done, ChatStreamEvent.Heartbeat, ChatStreamEvent.Ignored -> Unit

                // Settled completion wins: tear down exactly once without
                // repeating delegate finish/drain/title-refresh side effects.
                is ChatStreamEvent.TransportError, ChatStreamEvent.Cancelled,
                is ChatStreamEvent.Error, ChatStreamEvent.StreamEnd,
                -> finishStream()

                is ChatStreamEvent.Token, is ChatStreamEvent.InterimAssistant, is ChatStreamEvent.Reasoning,
                is ChatStreamEvent.ToolStarted, is ChatStreamEvent.ToolCompleted,
                is ChatStreamEvent.ApprovalPending, is ChatStreamEvent.ClarificationPending,
                is ChatStreamEvent.PendingSteerLeftover,
                -> Unit
            }
            return
        }

        lastEventId = sse.lastEventId ?: lastEventId
        val nowMillis = now()
        lastTransportActivityMillis = nowMillis
        refreshHeartbeatFreshness(nowMillis)

        when (event) {
            is ChatStreamEvent.Token -> {
                if (delegate?.streamCoordinatorAppendToken(event.text) == true) markProgress(nowMillis)
            }

            is ChatStreamEvent.InterimAssistant -> {
                if (delegate?.streamCoordinatorAppendInterimAssistant(event.event) == true) markProgress(nowMillis)
            }

            is ChatStreamEvent.Reasoning -> {
                if (delegate?.streamCoordinatorAppendReasoning(event.text) == true) markProgress(nowMillis)
            }

            is ChatStreamEvent.ToolStarted -> {
                if (delegate?.streamCoordinatorAppendToolCall(event.event) == true) markProgress(nowMillis)
            }

            is ChatStreamEvent.ToolCompleted -> {
                if (delegate?.streamCoordinatorCompleteToolCall(event.event) == true) markProgress(nowMillis)
            }

            is ChatStreamEvent.Title -> {
                if (delegate?.streamCoordinatorUpdateTitle(event.event) == true) markProgress(nowMillis)
            }

            is ChatStreamEvent.Metering -> {
                val payload = event.event
                if (payload.sessionId == null || payload.sessionId == delegate?.streamCoordinatorSessionId) {
                    setLiveTokensPerSecondIfChanged(payload.displayableTokensPerSecond)
                }
            }

            is ChatStreamEvent.Done -> {
                val hasCompletedTranscript = delegate?.streamCoordinatorApplyDone(event.event) == true
                completeCurrentResponse(needsTranscriptRefresh = !hasCompletedTranscript)
            }

            is ChatStreamEvent.ApprovalPending -> {
                delegate?.streamCoordinatorApplyApprovalUpdate(event.response)
                markProgress(nowMillis)
            }

            is ChatStreamEvent.ClarificationPending -> {
                delegate?.streamCoordinatorApplyClarificationUpdate(event.response)
                markProgress(nowMillis)
            }

            is ChatStreamEvent.PendingSteerLeftover -> {
                if (delegate?.streamCoordinatorEnqueuePendingSteerLeftover(event.text) == true) markProgress(nowMillis)
            }

            ChatStreamEvent.StreamEnd -> finishStream()

            ChatStreamEvent.Cancelled -> finishStream(ending = ChatRunEnding.CANCELLED)

            is ChatStreamEvent.Error -> {
                if (!hasCompletedCurrentResponse) {
                    delegate?.streamCoordinatorDidReceiveErrorMessage(event.message)
                }
                finishStream(ending = ChatRunEnding.FAILED)
            }

            is ChatStreamEvent.TransportError -> handleTransportError(event.message)

            ChatStreamEvent.Heartbeat -> {
                delegate?.streamCoordinatorDidConfirmRecovery()
                // #227: a heartbeat proves the transport is alive without carrying
                // semantic progress — drop an already-shown "Checking stream" state
                // immediately. Never demote RECONNECTING; the reconnect flow owns it.
                if (state.value.recoveryState == ActiveStreamRecoveryState.CHECKING) {
                    setRecoveryStateIfChanged(ActiveStreamRecoveryState.IDLE)
                }
            }

            ChatStreamEvent.Ignored -> Unit
        }
    }

    /** Port of `handleTransportError(_:)`: suspend, then the single-flight status-checked reconnect. */
    private fun handleTransportError(message: String) {
        // webui closes the socket right after `cancel` or `error`, and the
        // reader reports that close; a finished run has nothing left to fail.
        if (isTransportFinished) return
        setLiveTokensPerSecondIfChanged(null)
        if (activeStreamId == null || hasCompletedCurrentResponse) {
            if (!hasCompletedCurrentResponse) {
                delegate?.streamCoordinatorDidReceiveErrorMessage(message)
            }
            finishStream(ending = if (hasCompletedCurrentResponse) ChatRunEnding.COMPLETED else ChatRunEnding.FAILED)
            return
        }

        if (isConnectionSuspended) return

        lastEventId = sse.lastEventId ?: lastEventId
        delegate?.streamCoordinatorSaveSnapshotIfNeeded()
        hasInMemorySnapshotForActiveStream = true
        isConnectionSuspended = true
        stopConnection()
        delegate?.streamCoordinatorStopAuxiliaryMonitoring(clearPrompt = true)

        scope.launch { reconnectIfNeeded() }
    }

    /**
     * Parks recovery in `WAITING_FOR_NETWORK` and returns false while the
     * device is offline, so no status probe is sent (#869). Online, it lifts
     * an earlier wait so a failing probe can't leave that label behind.
     */
    private fun networkAllowsStatusProbe(): Boolean {
        if (!isNetworkAvailable()) {
            setRecoveryStateIfChanged(ActiveStreamRecoveryState.WAITING_FOR_NETWORK)
            return false
        }
        if (state.value.recoveryState == ActiveStreamRecoveryState.WAITING_FOR_NETWORK) {
            setRecoveryStateIfChanged(ActiveStreamRecoveryState.IDLE)
        }
        return true
    }

    private fun shouldPollStatus(nowMillis: Long): Boolean {
        val last = lastRecoveryStatusCheckMillis ?: return true
        return nowMillis - last >= timing.statusPollCooldownMillis
    }

    private suspend fun recoverStaleStream(expectedStreamId: String, forceReconnect: Boolean) {
        if (activeStreamId != expectedStreamId || isConnectionSuspended) return
        val generation = runGeneration
        val runStartedAt = state.value.activeRunStartedAt

        try {
            val response = api.chatStreamStatus(expectedStreamId)
            if (activeStreamId != expectedStreamId || isConnectionSuspended) return
            delegate?.streamCoordinatorDidConfirmRecovery()

            if (response.active == false) {
                delegate?.streamCoordinatorLoadMessages()
                if (!canFinalizeRunAfterLoad(expectedStreamId, generation) || isConnectionSuspended) return
                finalizeInactiveStream(expectedStreamId, runStartedAt)
                return
            }

            // If the state changed mid-flight (a heartbeat or real progress
            // demoted it to IDLE), the transport just proved itself alive —
            // don't resurrect the chip or churn a live connection.
            if (state.value.recoveryState != ActiveStreamRecoveryState.CHECKING || !forceReconnect) return

            reconnectStaleStream(expectedStreamId, usesReplay = response.replayAvailable == true)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            if ((error as? ApiError)?.indicatesMissingStream == true &&
                activeStreamId == expectedStreamId &&
                !isConnectionSuspended
            ) {
                delegate?.streamCoordinatorLoadMessages()
                if (!canFinalizeRunAfterLoad(expectedStreamId, generation) || isConnectionSuspended) return
                finalizeInactiveStream(expectedStreamId, runStartedAt)
                return
            }

            if (state.value.recoveryState != ActiveStreamRecoveryState.CHECKING ||
                !forceReconnect ||
                activeStreamId != expectedStreamId ||
                isConnectionSuspended
            ) {
                return
            }

            reconnectStaleStream(expectedStreamId, usesReplay = true)
        }
    }

    private fun reconnectStaleStream(streamId: String, usesReplay: Boolean) {
        if (activeStreamId != streamId || isConnectionSuspended) return

        lastEventId = sse.lastEventId ?: lastEventId
        val replayAfterSeq = if (usesReplay) runJournalReplayAfterSeq(lastEventId) ?: 0 else null
        delegate?.streamCoordinatorSaveSnapshotIfNeeded()
        setRecoveryStateIfChanged(ActiveStreamRecoveryState.RECONNECTING)
        stopConnection()
        delegate?.streamCoordinatorStopAuxiliaryMonitoring(clearPrompt = true)
        start(streamId, replayAfterSeq = replayAfterSeq, recoveryState = ActiveStreamRecoveryState.RECONNECTING)
    }

    // MARK: - Finalization

    private fun completeCurrentResponse(needsTranscriptRefresh: Boolean, completedStreamId: String? = null) {
        runGeneration += 1
        invalidateReconnectTask()
        delegate?.streamCoordinatorRemoveSnapshot(activeStreamId ?: completedStreamId)
        delegate?.streamCoordinatorStopAuxiliaryMonitoring(clearPrompt = true)
        recordRunEndingIfRunning(ChatRunEnding.COMPLETED)
        setActiveStreamId(null)
        hasInMemorySnapshotForActiveStream = false
        lastEventId = null
        setLiveTokensPerSecondIfChanged(null)
        delegate?.streamCoordinatorStreamingAssistantMessageId = null
        hasCompletedCurrentResponse = true
        isTerminalContentFenceActive = true
        delegate?.streamCoordinatorDidCompleteCurrentResponse(needsTranscriptRefresh)
        resetRecoveryState()
    }

    private fun completeResponseFromRefreshedTranscriptAndFinishStream(completedStreamId: String?) {
        completeCurrentResponse(needsTranscriptRefresh = false, completedStreamId = completedStreamId)
        delegate?.streamCoordinatorRemoveSnapshot(completedStreamId)
        finishStream()
    }

    /**
     * Whether this coordinator may still finalize the run captured before an
     * awaited transcript load (port of `canFinalizeRunAfterLoad`): bail when a
     * concurrent completion/cancel/new run bumped the generation, or when a
     * different run is now active. A run reconciled to null still passes.
     */
    private fun canFinalizeRunAfterLoad(streamId: String, capturedGeneration: Int): Boolean {
        if (runGeneration != capturedGeneration) return false
        return activeStreamId == null || activeStreamId == streamId
    }

    /**
     * Port of `finalizeInactiveStream(streamID:runStartedAt:)`: the server
     * reports this stream is no longer active — complete from the just-refreshed
     * transcript when an assistant reply surfaced, otherwise finalize as failed.
     * [runStartedAt] was captured before the awaited load, so a failure is
     * recorded against it even when the load cleared the live run (#862).
     */
    private fun finalizeInactiveStream(streamId: String?, runStartedAt: Double?) {
        if (delegate?.streamCoordinatorLatestServerLoadHadAssistantResponseAfterLatestUser == true) {
            completeResponseFromRefreshedTranscriptAndFinishStream(streamId)
        } else {
            if (state.value.activeRunStartedAt == null && runStartedAt != null) {
                latestRunEnding = ChatRunEndingRecord(startedAt = runStartedAt, endedAt = nowSeconds(), ending = ChatRunEnding.FAILED)
            }
            finishStream(ending = ChatRunEnding.FAILED)
        }
    }

    /** Port of `finishStream(ending:)`. */
    private fun finishStream(ending: ChatRunEnding = ChatRunEnding.COMPLETED) {
        if (isTransportFinished) return
        isTransportFinished = true
        runGeneration += 1
        invalidateReconnectTask()
        val completedNormally = hasCompletedCurrentResponse
        recordRunEndingIfRunning(ending)
        stopConnection()
        stopWatchdog()
        delegate?.streamCoordinatorStopAuxiliaryMonitoring(clearPrompt = true)
        delegate?.streamCoordinatorFlushPinnedLocalNoticesToTranscript()
        delegate?.streamCoordinatorRemoveSnapshot(activeStreamId)
        setActiveStreamId(null)
        hasInMemorySnapshotForActiveStream = false
        lastEventId = null
        setLiveTokensPerSecondIfChanged(null)
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

    /**
     * Port of `seedActiveRunStart(_:now:)`: adopts a server-known start for the
     * active run. A future-dated seed (clock skew) is clamped to now, and the
     * earliest start known for this stream wins, so a re-seed can only move the
     * start earlier — never forward onto a fresh now.
     */
    private fun seedActiveRunStart(startedAt: Double?, nowSeconds: Double = nowSeconds()) {
        if (activeStreamId == null || startedAt == null) return
        val clamped = minOf(startedAt, nowSeconds)
        val current = state.value.activeRunStartedAt
        val next = if (current == null) clamped else minOf(current, clamped)
        if (current != next) state.update { it.copy(activeRunStartedAt = next) }
    }

    /**
     * Records the run's span once. `completeCurrentResponse` already cleared the
     * run start for a normal completion, so a later teardown event cannot
     * overwrite that ending.
     */
    private fun recordRunEndingIfRunning(ending: ChatRunEnding) {
        val startedAt = state.value.activeRunStartedAt ?: return
        latestRunEnding = ChatRunEndingRecord(startedAt = startedAt, endedAt = nowSeconds(), ending = ending)
    }

    /**
     * Mirrors the Swift `activeStreamID.didSet`: a new stream stamps the
     * discovery instant as the run start and clears the previous ending; a
     * cleared stream clears the run start.
     */
    private fun setActiveStreamId(streamId: String?) {
        val previous = state.value.activeStreamId
        if (previous == streamId) return
        if (streamId != null) latestRunEnding = null
        state.update {
            it.copy(
                activeStreamId = streamId,
                activeRunStartedAt = if (streamId == null) null else nowSeconds(),
            )
        }
    }

    private fun stopConnection() {
        // A suspended connection is recovered by the foreground/network trigger,
        // not a live-transport watchdog. Keeping it ticking erased the offline
        // wait state and kept a backgrounded chat doing work indefinitely.
        stopWatchdog()
        connectionJob?.cancel()
        connectionJob = null
    }

    private fun startWatchdog() {
        if (!enableWatchdog) return
        watchdogJob?.cancel()
        watchdogJob = scope.launch {
            while (isActive) {
                delay(timing.watchdogTickMillis)
                if (activeStreamId == null) break
                refreshHeartbeatFreshness(now())
                recoverStaleStreamIfNeeded()
            }
        }
    }

    private fun stopWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = null
        if (state.value.isStreamHeartbeatFresh) state.update { it.copy(isStreamHeartbeatFresh = false) }
    }

    private fun refreshHeartbeatFreshness(nowMillis: Long) {
        val lastActivity = lastTransportActivityMillis
        val fresh = activeStreamId != null && !isConnectionSuspended &&
            lastActivity != null && nowMillis - lastActivity < timing.transportFreshMillis
        if (state.value.isStreamHeartbeatFresh != fresh) state.update { it.copy(isStreamHeartbeatFresh = fresh) }
    }

    private fun markConnectionStarted(
        isReplay: Boolean,
        allowReplayDedup: Boolean,
        recoveryState: ActiveStreamRecoveryState,
    ) {
        val startedAt = now()
        lastProgressMillis = if (isReplay) startedAt else null
        lastTransportActivityMillis = startedAt
        lastRecoveryStatusCheckMillis = null
        setRecoveryStateIfChanged(recoveryState)
        refreshHeartbeatFreshness(startedAt)
        isReplayConnection = allowReplayDedup
        delegate?.streamCoordinatorDidStartConnection(allowReplayDedup)
    }

    private suspend fun loadMessagesForReconnect(reconnectTaskId: UUID): Boolean {
        if (reconnectTask?.id != reconnectTaskId) return false
        reconnectTranscriptLoadTaskId = reconnectTaskId
        delegate?.streamCoordinatorLoadMessages()
        if (reconnectTranscriptLoadTaskId != reconnectTaskId) return false
        reconnectTranscriptLoadTaskId = null
        return true
    }

    private fun reconnectTaskIsCurrent(reconnectTaskId: UUID, streamId: String, runGeneration: Int): Boolean {
        val task = reconnectTask ?: return false
        return task.id == reconnectTaskId &&
            task.streamId == streamId &&
            task.runGeneration == runGeneration &&
            this.runGeneration == runGeneration &&
            activeStreamId == streamId &&
            isConnectionSuspended
    }

    private fun reconnectTaskOwnsFinalization(reconnectTaskId: UUID, streamId: String, runGeneration: Int): Boolean {
        val task = reconnectTask ?: return false
        if (task.id != reconnectTaskId || task.streamId != streamId ||
            task.runGeneration != runGeneration || this.runGeneration != runGeneration
        ) {
            return false
        }
        return activeStreamId == streamId || activeStreamId == null
    }

    private fun invalidateReconnectTask() {
        val task = reconnectTask
        reconnectTask = null
        reconnectTranscriptLoadTaskId = null
        task?.job?.cancel()
    }

    private fun invalidateReconnectTaskIfItDoesNotMatchCurrentStream() {
        val task = reconnectTask
        if (task == null || task.runGeneration != runGeneration) {
            invalidateReconnectTask()
            return
        }
        if (activeStreamId == null && reconnectTranscriptLoadTaskId == task.id) return
        if (!isConnectionSuspended || activeStreamId != task.streamId) {
            invalidateReconnectTask()
        }
    }

    /** Port of `restoreLoadedStreamSnapshot`: a different run never inherits the old run's cursor (#599). */
    private fun restoreLoadedStreamSnapshot(streamId: String, preparation: ChatStreamLoadPreparation) {
        if (preparation.activeStreamIdBeforeLoad == streamId) {
            restoreSnapshotIfAvailable(streamId)
            return
        }
        lastEventId = null
        hasInMemorySnapshotForActiveStream = restoreSnapshotIfAvailable(streamId)
    }

    private fun restoreSnapshotIfAvailable(streamId: String): Boolean {
        val result = delegate?.streamCoordinatorRestoreSnapshotIfAvailable(streamId)
            ?: ChatStreamSnapshotRestoreResult.notRestored
        if (lastEventId == null) lastEventId = result.lastEventId
        return result.didRestoreSnapshot
    }

    private fun setRecoveryStateIfChanged(next: ActiveStreamRecoveryState) {
        if (state.value.recoveryState == next) return
        state.update { it.copy(recoveryState = next) }
    }

    private fun setLiveTokensPerSecondIfChanged(next: Double?) {
        if (state.value.liveTokensPerSecond == next) return
        state.update { it.copy(liveTokensPerSecond = next) }
    }

    private fun resetRecoveryState() {
        setRecoveryStateIfChanged(ActiveStreamRecoveryState.IDLE)
        lastProgressMillis = null
        lastTransportActivityMillis = null
        lastRecoveryStatusCheckMillis = null
        isReplayConnection = false
        if (state.value.isStreamHeartbeatFresh) state.update { it.copy(isStreamHeartbeatFresh = false) }
        delegate?.streamCoordinatorDidResetRecoveryState()
    }

    private fun nowSeconds(): Double = now() / 1_000.0

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

        /**
         * Port of `isTransientReconnectFailure(_:)`: transient tunnel/connectivity
         * failures worth retrying inside the reconnect budget. Auth, decoding,
         * and ordinary HTTP errors surface once (#537).
         */
        fun isTransientReconnectFailure(error: Throwable): Boolean = when (error) {
            is ApiError.Network -> isTransientTransportFailure(error.underlying)
            is ApiError.Http -> error.statusCode in setOf(502, 503, 504)
            is ApiError -> false
            else -> isTransientTransportFailure(error)
        }

        private fun isTransientTransportFailure(error: Throwable): Boolean = when (error) {
            is SocketTimeoutException, is ConnectException, is NoRouteToHostException,
            is UnknownHostException, is SocketException,
            -> true
            is InterruptedIOException -> true
            else -> false
        }
    }
}
