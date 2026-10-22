package io.github.jcartu.hermex.ui.feature.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.jcartu.hermex.core.prefs.ServerConfigRepository
import io.github.jcartu.hermex.core.prefs.SettingsRepository
import io.github.jcartu.hermex.data.CacheStore
import io.github.jcartu.hermex.data.ActiveChatStreamSnapshot
import io.github.jcartu.hermex.data.ActiveChatStreamSnapshotStore
import io.github.jcartu.hermex.core.share.SharedImport
import io.github.jcartu.hermex.model.ApprovalChoice
import io.github.jcartu.hermex.model.ApprovalPendingResponse
import io.github.jcartu.hermex.model.BackgroundResult
import io.github.jcartu.hermex.model.ChatMessage
import io.github.jcartu.hermex.model.ClarificationPendingResponse
import io.github.jcartu.hermex.model.ContextWindowSnapshot
import io.github.jcartu.hermex.model.JSONValue
import io.github.jcartu.hermex.model.MessageAttachment
import io.github.jcartu.hermex.model.ModelCatalogGroup
import io.github.jcartu.hermex.model.ModelCatalogOption
import io.github.jcartu.hermex.model.ModelFavoritesStore
import io.github.jcartu.hermex.model.ModelRecentsStore
import io.github.jcartu.hermex.model.PendingAttachment
import io.github.jcartu.hermex.model.ProfileSummary
import io.github.jcartu.hermex.model.SessionDetail
import io.github.jcartu.hermex.model.SessionSummary
import io.github.jcartu.hermex.model.SkillSlashSuggestion
import io.github.jcartu.hermex.model.SlashSkillFormatter
import io.github.jcartu.hermex.model.SubmittedGoal
import io.github.jcartu.hermex.model.ToolCall
import io.github.jcartu.hermex.model.ToolCallGroup
import io.github.jcartu.hermex.model.TranscriptTurnClassifier
import io.github.jcartu.hermex.model.firstMatchingSelection
import io.github.jcartu.hermex.model.formattedCost
import io.github.jcartu.hermex.model.mergingLiveModels
import io.github.jcartu.hermex.net.ApiError
import io.github.jcartu.hermex.net.CacheFallbackPolicy
import io.github.jcartu.hermex.net.DoneStreamEvent
import io.github.jcartu.hermex.net.HermesApi
import io.github.jcartu.hermex.net.InterimAssistantStreamEvent
import io.github.jcartu.hermex.net.SseChatStream
import io.github.jcartu.hermex.net.ChatStreamEvent
import io.github.jcartu.hermex.net.TitleStreamEvent
import io.github.jcartu.hermex.net.ToolStreamEvent
import io.github.jcartu.hermex.ui.feature.models.ReasoningEffortOption
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.jcartu.hermex.di.AppContainer
import io.github.jcartu.hermex.data.ChatDraft
import io.github.jcartu.hermex.data.ChatDraftAttachment
import io.github.jcartu.hermex.data.ChatDraftAttachmentReadFailure
import io.github.jcartu.hermex.data.ChatDraftKey
import io.github.jcartu.hermex.data.ChatDraftSettings
import io.github.jcartu.hermex.data.ChatDraftShareFile
import io.github.jcartu.hermex.data.ChatDraftStore
import io.github.jcartu.hermex.net.ChatSteerOutcome
import io.github.jcartu.hermex.net.NetworkAvailability
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A message queued by /queue, /steer fallback, or /interrupt (Swift `QueuedSlashMessage`). */
private data class QueuedSlashMessage(
    val text: String,
    val attachments: List<PendingAttachment>,
    /** Composer revision whose submission placed this message in the queue. */
    val submittedComposerRevision: Int? = null,
)

/** One immutable composer submission and the ownership of any run it starts. */
private data class ComposerSubmission(
    val id: Long,
    val text: String,
    val quotes: List<ComposerQuote>,
    val attachments: List<PendingAttachment>,
    val revision: Int,
    val generation: Int,
    var sourceKey: ChatDraftKey? = null,
    var ownsRunStart: Boolean = false,
)

private data class SubmittedComposerContent(
    val text: String,
    val quotes: List<ComposerQuote>,
)

/**
 * A definitively rejected submission retained while continuation discovery is
 * only deciding where to navigate. [lastRetainedContent] makes repeated
 * persistence idempotent while the visible composer still holds newer edits.
 */
private data class RejectedComposerSubmission(
    val submitted: SubmittedComposerContent,
    val attachments: List<ChatDraftAttachment>,
    var lastRetainedContent: SubmittedComposerContent? = null,
)

/**
 * Port of `ChatViewModel.swift` (minus the out-of-scope Listen/TTS,
 * voice-note, Live Activity, media-preview, and compression-reference-card
 * regions). One instance drives the whole chat feature: transcript load with
 * cache-first render, optimistic send + SSE streaming through
 * [ChatStreamCoordinator], approval/clarification prompts through
 * [ChatPendingActionCoordinator], attachments through
 * [ChatAttachmentCoordinator], slash commands, /btw side streams, and
 * /background polling.
 *
 * The constructor never loads — `ChatPane` activates the ViewModel with
 * [switchToSession], which is idempotent for the already-active session and
 * preserves the draft + staged attachments across session switches.
 */
class ChatViewModel(
    private val api: HermesApi,
    private val sse: SseChatStream,
    private val cacheStore: CacheStore,
    private val serverConfig: ServerConfigRepository,
    private val settings: SettingsRepository,
    private val favorites: ModelFavoritesStore,
    private val recents: ModelRecentsStore,
    initialSessionId: String?,
    initialNewChat: Boolean,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val drafts: ChatDraftStore = cacheStore.chatDraftStore,
    private val network: NetworkAvailability? = null,
    foregroundEvents: Flow<Int> = emptyFlow(),
    private val onRunStarted: suspend () -> Unit = {},
    private val streamSnapshots: ActiveChatStreamSnapshotStore = cacheStore.activeStreamSnapshots,
    private val attachmentPreview: suspend (ByteArray, String, String, String, java.io.File?) -> ByteArray? = ::regenerateAttachmentPreview,
) : ViewModel(), ChatComposerActions, ChatStreamCoordinatorDelegate {

    private val _uiState = MutableStateFlow(
        ChatUiState(sessionId = nonEmpty(initialSessionId).takeUnless { initialNewChat }),
    )
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()
    private val _sessionReasoningEffort = MutableStateFlow<String?>(null)
    /** Server-vocabulary effort id, scoped to this session (`APIClient+ServerPanels.swift`). */
    val sessionReasoningEffort: StateFlow<String?> = _sessionReasoningEffort.asStateFlow()
    val draftTokens: List<ComposerToken> get() = _uiState.value.draftTokens

    private val pollingIntervals = ChatPollingIntervals.standard

    private val streamCoordinator = ChatStreamCoordinator(
        api = api,
        sse = sse,
        state = _uiState,
        scope = viewModelScope,
        isNetworkAvailable = { network?.isSatisfied != false },
        enableWatchdog = network != null,
    )

    private val pendingActionCoordinator = ChatPendingActionCoordinator(
        api = api,
        state = _uiState,
        scope = viewModelScope,
        isStreamConnectionSuspended = { streamCoordinator.isConnectionSuspended },
        onActionFailed = { message -> _uiState.update { it.copy(sendErrorMessage = message) } },
        pollingIntervals = pollingIntervals,
        sse = sse,
    )

    private val attachmentCoordinator = ChatAttachmentCoordinator(api = api, state = _uiState)

    // MARK: - Session identity (Swift stored properties)

    private var sessionId: String? = nonEmpty(initialSessionId).takeUnless { initialNewChat }
    private var hasActivatedSession = false
    private var isCliSession = false
    private var currentWorkspace: String? = null
    private var currentModel: String? = null
    private var currentModelProvider: String? = null
    private var currentProfile: String? = null
    private var selectedProfileName: String? = null
    private var isSingleProfileMode = false
    private var selectedReasoningEffort: String? = null

    /** `supported_efforts` from `GET /api/reasoning`; null on older servers (issue #18). */
    private var supportedReasoningEfforts: List<String>? = null
    private var supportsReasoningEffort: Boolean? = null

    /** Drops out-of-order `GET /api/reasoning` responses after rapid model switches. */
    private var reasoningGatingFetchToken = 0
    private var pendingExplicitModelPick = false
    private var isLoadingComposerConfiguration = false
    private var needsComposerConfigurationReload = false

    // MARK: - Streaming buffers + replay dedup state

    private val pendingAssistantTokenChunks = mutableListOf<String>()
    private val pendingReasoningChunks = mutableListOf<String>()
    private var pendingStreamingContentFlushJob: Job? = null
    private var toolCallAnchorMessageId: String? = null
    private var reasoningAnchorMessageId: String? = null
    private var activeStreamReplayMatchedPrefixLength = 0
    private var activeStreamReplayMatchedInterimLength = 0
    private var activeStreamReplayMatchedReasoningLength = 0
    private var activeStreamReplayToolMatchIndex = 0
    private var activeStreamReplayPendingToolMatchIndex: Int? = null
    private var latestServerLoadHadAssistantResponseAfterLatestUser = false

    // MARK: - Slash-command side channels

    private val queuedSlashMessages = mutableListOf<QueuedSlashMessage>()
    private var isDrainingQueuedSlashMessage = false
    private var activeBtwStreamId: String? = null
    private var activeBtwMessageId: String? = null
    private var activeBtwQuestion: String? = null
    private var activeBtwAnswer = ""
    private var btwStreamJob: Job? = null
    private val backgroundPromptsByTaskId = mutableMapOf<String, String>()
    private var backgroundPollJob: Job? = null
    private var isRefreshingCompletedResponseTitle = false
    private var hasLoadedSkillSlashSuggestions = false
    private var currentGoal: SubmittedGoal? = null
    private var goalErrorMessage: String? = null

    /** Approval/clarification polling only runs while ChatPane is visible. */
    private var isScreenActive = false
    private var draftKey: ChatDraftKey? = null
    private val attachmentLease = drafts.makeAttachmentLease(null)
    private var draftRecords: List<ChatDraftAttachment> = emptyList()
    private var composerRevision = 0
    private var sessionGeneration = 0
    private var activationJob: Job? = null
    private var newChatProjectId: String? = null
    private var newChatProfileName: String? = null
    private var compressionContinuationId: String? = null
    private val sealedSessions = mutableMapOf<ChatDraftKey, Pair<String?, String>>()
    private var nextComposerSubmissionId = 0L
    private var runStartOwnerId: Long? = null
    private var runStartOwnerGeneration: Int? = null
    private var runStartOwnerRevision: Int? = null
    private val rejectedSubmissions = mutableMapOf<ChatDraftKey, RejectedComposerSubmission>()
    private val transcriptLoadMutex = Mutex()
    private var sendErrorIsFromRecovery = false
    private val composerFileListings = mutableMapOf<String, List<ComposerFileSuggestion>>()

    init {
        streamCoordinator.attach(this)
        if (network != null) viewModelScope.launch {
            network.changes.collect {
                if (isScreenActive) streamCoordinator.networkPathDidChange()
            }
        }
        viewModelScope.launch {
            foregroundEvents.drop(1).collect { if (hasActivatedSession) refreshOnForeground() }
        }

        viewModelScope.launch {
            settings.settings.collect { s ->
                _uiState.update {
                    it.copy(
                        showsThinkingAndToolCards = s.showsThinkingAndToolCards,
                        thinkingCardsStartExpanded = s.thinkingCardsStartExpanded,
                        toolCardsStartExpanded = s.toolCardsStartExpanded,
                        hidesAttachmentPaths = s.hidesAttachmentPaths,
                        showsAssistantTurnTimestamps = s.showsAssistantTurnTimestamps,
                        showsResponseSpeed = s.showsResponseSpeed,
                        wrapsCodeBlockLines = s.wrapsCodeBlockLines,
                        rtlChatLayoutEnabled = s.rtlChatLayoutEnabled,
                    )
                }
            }
        }
        viewModelScope.launch {
            favorites.favoriteKeys.collect { keys ->
                _uiState.update { it.copy(favoriteModelKeys = keys) }
            }
        }
        viewModelScope.launch {
            recents.recentKeys.collect { keys ->
                _uiState.update { it.copy(recentModelKeys = keys) }
            }
        }
    }

    // MARK: - Session activation

    /** Port of `ChatView` draft activation: park the old queue, then restore this server/session's draft. */
    fun switchToSession(
        sessionId: String?,
        newChat: Boolean,
        newChatProjectId: String? = null,
        newChatProfileName: String? = null,
    ) {
        val normalized = nonEmpty(sessionId).takeUnless { newChat }
        if (hasActivatedSession && !newChat && normalized != null && normalized == this.sessionId) return
        parkQueuedMessages()
        persistComposer()
        persistPartialStream()
        activationJob?.cancel()
        sessionGeneration += 1
        val generation = sessionGeneration
        this.newChatProjectId = nonEmpty(newChatProjectId).takeIf { normalized == null }
        this.newChatProfileName = nonEmpty(newChatProfileName).takeIf { normalized == null }
        hasActivatedSession = true
        draftKey = null
        attachmentLease.key = null
        attachmentLease.files.clear()
        attachmentLease.filesByAttachmentId.clear()
        draftRecords = emptyList()
        composerFileListings.clear()
        compressionContinuationId = null
        selectedReasoningEffort = null

        // Tear down everything scoped to the previous session.
        streamCoordinator.resetForSessionSwitch()
        pendingActionCoordinator.stopMonitoring(clearPrompt = true)
        stopBtwStream()
        stopBackgroundPolling(clearTrackedPrompts = true)
        queuedSlashMessages.clear()
        isDrainingQueuedSlashMessage = false
        resetPendingStreamingContentBuffers()
        resetReplayDedupState()
        latestServerLoadHadAssistantResponseAfterLatestUser = false
        isRefreshingCompletedResponseTitle = false
        toolCallAnchorMessageId = null
        reasoningAnchorMessageId = null
        currentWorkspace = null
        currentModel = null
        currentModelProvider = null
        currentProfile = null
        selectedProfileName = null
        isCliSession = false
        pendingExplicitModelPick = false
        currentGoal = null
        goalErrorMessage = null

        this.sessionId = normalized

        _uiState.update {
            it.copy(
                sessionId = normalized,
                displayTitle = "",
                isReadOnly = false,
                messages = emptyList(),
                messagesOffset = 0,
                transcriptMessages = emptyList(),
                canLoadOlderMessages = false,
                isLoadingOlderMessages = false,
                isLoading = false,
                isViewingCachedData = false,
                errorMessage = null,
                sendErrorMessage = null,
                activeStreamId = null,
                isStartingChat = false,
                isCancellingStream = false,
                streamingAssistantMessageId = null,
                liveReasoningText = "",
                completedReasoningGroups = emptyList(),
                liveToolCalls = emptyList(),
                completedToolCallGroups = emptyList(),
                recoveryState = ActiveStreamRecoveryState.IDLE,
                liveTokensPerSecond = null,
                contextWindow = null,
                approvalPrompt = null,
                isRespondingToApproval = false,
                approvalErrorMessage = null,
                clarificationPrompt = null,
                isRespondingToClarification = false,
                clarificationErrorMessage = null,
                pinnedLocalNotices = emptyList(),
                draft = "",
                draftTokens = emptyList(),
                draftQuotes = emptyList(),
                pendingAttachments = emptyList(),
                queuedMessagesReceipt = null,
                steerFailureMessage = null,
                steeringConfirmation = null,
                agentRuntimeStaleFixPrompt = null,
                compressionContinuation = null,
                isCompressionRotated = false,
                forkOrigin = null,
                pendingClearConfirmation = false,
                isClearingConversation = false,
            )
        }

        activationJob = viewModelScope.launch {
            val serverUrl = serverConfig.current().baseUrl.trim()
            if (sessionGeneration != generation) return@launch
            val key = ChatDraftKey(serverUrl, normalized)
            draftKey = key
            attachmentLease.key = key
            _uiState.update { it.copy(serverCacheNamespace = serverUrl) }
            val revision = composerRevision
            val restored = drafts.draft(key)
            if (sessionGeneration != generation) return@launch
            if (restored != null && revision == composerRevision) applyDraft(restored)
            sealedSessions[key]?.let { sealed ->
                compressionContinuationId = sealed.first
                _uiState.update { it.copy(isCompressionRotated = true, sendErrorMessage = sealed.second) }
            }
            if (this@ChatViewModel.sessionId != null) performLoadMessages()
            if (sessionGeneration != generation) return@launch
            loadComposerConfiguration()
            if (sessionGeneration != generation) return@launch
            if (normalized == null && restored?.settings != null) restoreDraftSettings(restored.settings)
            if (draftRecords.isNotEmpty() && ensureSessionForSend() != null && sessionGeneration == generation) {
                restoreDraftAttachments(generation)
            }
            if (isScreenActive) {
                pendingActionCoordinator.startMonitoring()
                pendingActionCoordinator.refreshApprovalBypassState()
            }
            drafts.markUsed(key)
        }
    }

    // MARK: - Transcript loading

    fun loadMessages() {
        viewModelScope.launch { performLoadMessages() }
    }

    fun retryLoad() = loadMessages()

    /** Port of `loadMessages(modelContext:)` — cache-first render, then server reconcile. */
    private suspend fun performLoadMessages() {
        val sessionId = sessionId ?: return

        // Flush before preparing the snapshot: buffered deltas belong to its cursor too.
        val streamLoadPreparation = streamCoordinator.prepareForSessionLoad()
        resetPendingStreamingContentBuffers()
        latestServerLoadHadAssistantResponseAfterLatestUser = false
        _uiState.update { it.copy(isLoading = true, errorMessage = null) }

        val serverUrl = serverConfig.current().baseUrl
        withContext(ioDispatcher) { streamSnapshots.loadIfNeeded() }

        // Cache-first render (#289): on a cold open with a populated cache paint
        // the cached transcript immediately so the loading skeleton never shows.
        val previousMessages = _uiState.value.messages
        val previousMessagesOffset = _uiState.value.messagesOffset
        val cacheFirstPlaceholder: List<ChatMessage> = if (previousMessages.isEmpty()) {
            renderCachedMessagesBeforeReload(serverUrl, sessionId)
        } else {
            emptyList()
        }
        val renderedCacheFirst = cacheFirstPlaceholder.isNotEmpty()

        try {
            val response = api.session(
                id = sessionId,
                includeMessages = true,
                messageLimit = MESSAGE_PAGE_LIMIT,
                // Cold load only: widen the window to renderable-dense so a
                // tool-heavy session opens populated (upstream #3790).
                expandRenderable = true,
            )
            // A concurrent switchToSession replaced the active session while this
            // load was awaiting the network; applying it now would clobber the
            // new session's transcript (iOS builds a fresh VM per session).
            if (this.sessionId != sessionId) return
            val session = response.session
            val loadedMessages = session?.messages ?: emptyList()
            val loadedActiveStreamId = session?.activeStreamId?.trim()

            val cachedMessages = runCatching {
                withContext(ioDispatcher) {
                    cacheStore.cachedMessages(serverUrl, sessionId, limit = MESSAGE_PAGE_LIMIT)
                }
            }.getOrDefault(emptyList())
            val reloadedMessages = mergingLoadedMessages(loadedMessages, cachedMessages)

            applyReloadedMessages(reloadedMessages, session, previousMessages, previousMessagesOffset)
            latestServerLoadHadAssistantResponseAfterLatestUser =
                hasAssistantResponseAfterLatestUser(_uiState.value.messages)

            session?.let { applySessionIdentity(it) }
            _uiState.update {
                it.copy(
                    isViewingCachedData = false,
                    contextWindow = contextWindowSnapshot(session),
                    completedReasoningGroups = emptyList(),
                    liveToolCalls = emptyList(),
                    liveReasoningText = "",
                    pinnedLocalNotices = emptyList(),
                )
            }
            session?.title?.let(::applySessionTitle)
            setCompletedToolCallGroups(
                ToolCallGroup.groups(
                    persistedToolCalls = session?.toolCalls ?: emptyList(),
                    messages = _uiState.value.messages,
                    messageOffset = _uiState.value.messagesOffset,
                ),
            )
            toolCallAnchorMessageId = null
            reasoningAnchorMessageId = null
            cacheCurrentMessagesBlocking(serverUrl, sessionId)

            streamCoordinator.reconcileSessionLoad(
                loadedActiveStreamId = loadedActiveStreamId,
                preparation = streamLoadPreparation,
                usedCacheFallback = false,
                runStartedAt = session?.pendingStartedAt?.takeIf { it > 0 }
                    ?: _uiState.value.messages.lastOrNull { it.role == "user" }?.timestamp,
            )

            // A session opened while its run is still active reports
            // `active_stream_id`; attach to that running stream (replay-aware,
            // NEVER re-sending the user message).
            if (streamCoordinator.isConnectionSuspended && _uiState.value.activeStreamId != null) {
                viewModelScope.launch { streamCoordinator.reconnectIfNeeded() }
            }
        } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            if (this.sessionId != sessionId) return
            latestServerLoadHadAssistantResponseAfterLatestUser = false
            val cachedMessages = if (CacheFallbackPolicy.shouldUseCache(error)) {
                runCatching {
                    withContext(ioDispatcher) {
                        cacheStore.cachedMessages(serverUrl, sessionId, limit = MESSAGE_PAGE_LIMIT)
                    }
                }.getOrDefault(emptyList())
            } else {
                emptyList()
            }

            if (cachedMessages.isNotEmpty()) {
                updateMessages(cachedMessages, messagesOffset = 0, canLoadOlder = false)
                latestServerLoadHadAssistantResponseAfterLatestUser =
                    hasAssistantResponseAfterLatestUser(cachedMessages)
                setCompletedToolCallGroups(emptyList())
                _uiState.update {
                    it.copy(
                        isViewingCachedData = true,
                        contextWindow = null,
                        errorMessage = null,
                        completedReasoningGroups = emptyList(),
                        liveToolCalls = emptyList(),
                        liveReasoningText = "",
                        pinnedLocalNotices = emptyList(),
                        streamingAssistantMessageId = null,
                    )
                }
                toolCallAnchorMessageId = null
                reasoningAnchorMessageId = null
                streamCoordinator.reconcileSessionLoad(
                    loadedActiveStreamId = null,
                    preparation = streamLoadPreparation,
                    usedCacheFallback = true,
                )
            } else {
                if (renderedCacheFirst) {
                    revertCacheFirstPlaceholder(cacheFirstPlaceholder, previousMessages, previousMessagesOffset)
                }
                _uiState.update {
                    it.copy(
                        isViewingCachedData = false,
                        errorMessage = error.message ?: "The transcript failed to load.",
                    )
                }
            }
        } finally {
            _uiState.update { it.copy(isLoading = false) }
        }
    }

    /** Port of `renderCachedMessagesBeforeReload(sessionID:modelContext:)`. */
    private suspend fun renderCachedMessagesBeforeReload(
        serverUrl: String,
        sessionId: String,
    ): List<ChatMessage> {
        val cachedMessages = runCatching {
            withContext(ioDispatcher) {
                cacheStore.cachedMessages(serverUrl, sessionId, limit = MESSAGE_PAGE_LIMIT)
            }
        }.getOrDefault(emptyList())

        if (cachedMessages.isEmpty()) return emptyList()

        updateMessages(cachedMessages, messagesOffset = 0, canLoadOlder = false)
        return cachedMessages
    }

    /** Port of `revertCacheFirstPlaceholder(_:to:previousMessagesOffset:)`. */
    private fun revertCacheFirstPlaceholder(
        placeholder: List<ChatMessage>,
        previousMessages: List<ChatMessage>,
        previousMessagesOffset: Int,
    ) {
        // Only undo the cache-first paint if nothing mutated the transcript since
        // the prime (e.g. an optimistic send during the load window).
        if (_uiState.value.messages != placeholder) return
        updateMessages(previousMessages, previousMessagesOffset, canLoadOlder = previousMessagesOffset > 0)
    }

    /** Port of `loadOlderMessages(modelContext:)` — pagination via `message_before`. */
    fun loadOlderMessages() {
        viewModelScope.launch { performLoadOlderMessages() }
    }

    private suspend fun performLoadOlderMessages(): Boolean {
        val sessionId = sessionId ?: return false
        val current = _uiState.value
        if (current.isLoadingOlderMessages || !current.canLoadOlderMessages) return false
        if (current.messagesOffset <= 0) {
            _uiState.update { it.copy(canLoadOlderMessages = false) }
            return false
        }

        resetPendingStreamingContentBuffers()
        val messageBefore = current.messagesOffset
        _uiState.update { it.copy(isLoadingOlderMessages = true, errorMessage = null) }
        try {
            val response = api.session(
                id = sessionId,
                includeMessages = true,
                messageLimit = MESSAGE_PAGE_LIMIT,
                messageBefore = messageBefore,
            )
            if (this.sessionId != sessionId) return false
            val session = response.session
            if (session == null) {
                _uiState.update { it.copy(canLoadOlderMessages = false) }
                return false
            }

            val olderMessages = session.messages ?: emptyList()
            val mergedMessages = prependingOlderMessages(olderMessages, _uiState.value.messages)
            val didAddMessages = mergedMessages.size > _uiState.value.messages.size
            updateMessages(mergedMessages)
            latestServerLoadHadAssistantResponseAfterLatestUser =
                hasAssistantResponseAfterLatestUser(mergedMessages)
            updateOlderMessagePagination(session, mergedMessages.size)
            applySessionIdentity(session)
            _uiState.update {
                it.copy(
                    isViewingCachedData = false,
                    contextWindow = contextWindowSnapshot(session),
                    completedReasoningGroups = emptyList(),
                )
            }
            session.title?.let(::applySessionTitle)
            setCompletedToolCallGroups(
                ToolCallGroup.groups(
                    persistedToolCalls = session.toolCalls ?: emptyList(),
                    messages = _uiState.value.messages,
                    messageOffset = _uiState.value.messagesOffset,
                ),
            )
            cacheCurrentMessagesBlocking(serverConfig.current().baseUrl, sessionId)
            return didAddMessages
        } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            _uiState.update { it.copy(errorMessage = error.message ?: "Older messages failed to load.") }
            return false
        } finally {
            _uiState.update { it.copy(isLoadingOlderMessages = false) }
        }
    }

    // MARK: - ChatComposerActions

    override fun updateDraft(text: String) {
        composerRevision += 1
        _uiState.update { it.copy(draft = text, draftTokens = parseComposerTokens(text), steerFailureMessage = null) }
        persistComposer()
    }

    override fun updateDraftTokens(tokens: List<ComposerToken>) {
        updateDraft(tokens.toServerText())
        _uiState.update { it.copy(draftTokens = tokens) }
    }

    /** Port of `ChatView.addSelectedPassageToDraft`: selection is explicit quote metadata, not pasted Markdown. */
    fun askAboutSelection(selection: String) {
        if (selection.isEmpty()) return
        composerRevision += 1
        _uiState.update { it.copy(draftQuotes = it.draftQuotes + ComposerQuote(text = selection)) }
        persistComposer()
    }

    override fun removeDraftQuote(id: String) {
        composerRevision += 1
        _uiState.update { it.copy(draftQuotes = it.draftQuotes.filterNot { quote -> quote.id == id }) }
        persistComposer()
    }

    /** Port of `ComposerRecall.lastSentText`: never recalls system deliveries, markers, or synthesized attachment text. */
    fun recallLastUserMessage(): String? = _uiState.value.messages.asReversed().firstNotNullOfOrNull { message ->
        if (message.role != "user" ||
            (message.displayKind != null && message.displayKind != ChatMessage.STEER_DISPLAY_KIND) ||
            ChatMarkerMessageClassifier.classification(message) != null
        ) return@firstNotNullOfOrNull null
        val typed = MessageAttachment.contentWithoutAttachedFilesMarker(message.steerText).trim()
        typed.takeIf { it.isNotEmpty() && !PendingAttachment.isAttachmentOnlyMessageText(it) }
    }

    fun clearSteerFailure() { _uiState.update { it.copy(steerFailureMessage = null) } }
    fun retrySteer() = send(SendMode.STEER)

    /** Port of `ChatView.sendDraftMessage(behavior:)`: capture the submission; never erase newer edits. */
    override fun send(mode: SendMode) {
        val state = _uiState.value
        if (state.isClearingConversation) return
        if (runStartOwnerGeneration == sessionGeneration && runStartOwnerRevision == composerRevision) return
        val submission = ComposerSubmission(
            id = ++nextComposerSubmissionId,
            text = state.draft,
            quotes = state.draftQuotes,
            attachments = state.pendingAttachments,
            revision = composerRevision,
            generation = sessionGeneration,
        )
        // A run-start submission owns the composer before activation or lazy
        // session creation can suspend. Streaming queue/steer submissions stay
        // independent and therefore remain usable once a run is active.
        if (state.activeStreamId == null && !claimRunStart(submission)) return
        clearSteerFailure()
        viewModelScope.launch {
            try {
                activationJob?.join()
                if (sessionGeneration != submission.generation) return@launch
                submission.sourceKey = draftKey
                var successful = false
                if (submission.quotes.isEmpty() && submission.text.trim().startsWith("/")) {
                    val command = SlashCommandExecutor.parse(submission.text)?.command
                    if (command?.handler == SlashCommandHandler.ClientSide(ClientSideAction.CLEAR) &&
                        clearConversationRefusal == null
                    ) {
                        _uiState.update { it.copy(pendingClearConfirmation = true) }
                        return@launch
                    }
                    val result = executeSlashCommandText(submission.text, submission)
                    if (result != SlashCommandExecutionResult.SendAsMessage) {
                        successful = result is SlashCommandExecutionResult.Executed ||
                            result is SlashCommandExecutionResult.OpenedSession
                        handleSlashExecutionResult(result, command)
                        if (successful && composerRevision == submission.revision &&
                            sessionGeneration == submission.generation
                        ) {
                            clearSubmittedComposer()
                        }
                        if (!successful) {
                            retainAndOpenContinuation(
                                submission,
                                submission.sourceKey ?: draftKey,
                                submission.generation,
                            )
                        }
                        return@launch
                    }
                }
                val outbound = ComposerQuote.message(submission.text, submission.quotes)
                if (_uiState.value.activeStreamId != null) {
                    val result = when (mode) {
                        SendMode.DEFAULT, SendMode.STEER -> steerResponseFromSlashCommand(outbound, submission)
                        SendMode.QUEUE -> queueMessageFromSlashCommand(outbound, submission)
                        SendMode.STOP_AND_SEND -> interruptResponseFromSlashCommand(outbound, submission)
                    }
                    successful = result is SlashCommandExecutionResult.Executed
                    if (result is SlashCommandExecutionResult.Unsupported &&
                        _uiState.value.steerFailureMessage == null
                    ) {
                        _uiState.update { it.copy(sendErrorMessage = result.friendlyMessage) }
                    }
                } else {
                    successful = sendMessage(outbound, submission)
                }
                if (sessionGeneration == submission.generation && successful &&
                    composerRevision == submission.revision
                ) {
                    clearSubmittedComposer()
                }
                if (!successful) {
                    retainAndOpenContinuation(
                        submission,
                        submission.sourceKey ?: draftKey,
                        submission.generation,
                    )
                }
                persistComposer()
            } finally {
                releaseRunStart(submission)
            }
        }
    }

    private fun claimRunStart(submission: ComposerSubmission): Boolean {
        if (submission.ownsRunStart) return true
        if (submission.generation != sessionGeneration) return false
        val ownerIsCurrent = runStartOwnerId != null && runStartOwnerGeneration == submission.generation
        if (ownerIsCurrent || (_uiState.value.isStartingChat && runStartOwnerId != submission.id)) return false
        runStartOwnerId = submission.id
        runStartOwnerGeneration = submission.generation
        submission.ownsRunStart = true
        runStartOwnerRevision = submission.revision
        _uiState.update { it.copy(isStartingChat = true) }
        return true
    }

    private fun releaseRunStart(submission: ComposerSubmission) {
        if (runStartOwnerId != submission.id) {
            submission.ownsRunStart = false
            return
        }
        runStartOwnerId = null
        runStartOwnerGeneration = null
        submission.ownsRunStart = false
        runStartOwnerRevision = null
        if (sessionGeneration == submission.generation) {
            _uiState.update { it.copy(isStartingChat = false) }
        }
    }

    private fun clearSubmittedComposer() {
        _uiState.update { it.copy(draft = "", draftTokens = emptyList(), draftQuotes = emptyList()) }
        persistComposer()
    }

    /** Port of `ChatView.handleSlashExecutionResult(_:parsedCommand:)`. */
    private fun handleSlashExecutionResult(
        result: SlashCommandExecutionResult,
        parsedCommand: SlashCommand?,
    ) {
        when (result) {
            is SlashCommandExecutionResult.Executed -> {
                result.message?.let { message ->
                    if (shouldRenderAsLocalNotice(parsedCommand)) {
                        if (_uiState.value.activeStreamId == null) {
                            appendLocalNoticeMessage(message)
                        } else {
                            pinLocalNoticeMessage(message)
                        }
                    } else {
                        appendLocalAssistantMessage(message)
                    }
                }
                // The captured submission's owner consumes the composer only if its revision still matches.
            }

            is SlashCommandExecutionResult.OpenedSession -> {
                clearSubmittedComposer()
                result.session.sessionId?.let { switchToSession(it, newChat = false) }
            }

            is SlashCommandExecutionResult.Unsupported -> {
                _uiState.update { it.copy(sendErrorMessage = result.friendlyMessage) }
            }

            SlashCommandExecutionResult.NeedsSubArg -> {
                _uiState.update { it.copy(sendErrorMessage = "Choose a slash command or continue typing.") }
            }

            SlashCommandExecutionResult.SendAsMessage -> Unit
        }
    }

    private fun shouldRenderAsLocalNotice(command: SlashCommand?): Boolean {
        val handler = command?.handler as? SlashCommandHandler.ServerSide ?: return false
        return handler.action in setOf(
            ServerSideAction.COMPRESS,
            ServerSideAction.QUEUE,
            ServerSideAction.STEER,
            ServerSideAction.INTERRUPT,
            ServerSideAction.BACKGROUND,
        )
    }

    override fun stopStream() {
        viewModelScope.launch { cancelActiveStream() }
    }

    override fun selectModel(option: ModelCatalogOption) {
        viewModelScope.launch { selectComposerModel(option) }
    }

    override fun toggleFavoriteModel(option: ModelCatalogOption) {
        viewModelScope.launch { favorites.toggleFavorite(option) }
    }

    /** Port of `refreshModelCatalogForPickerOpen()` — silent by design. */
    override fun refreshModelCatalogForPickerOpen() {
        viewModelScope.launch {
            runCatching { api.models() }.getOrNull()?.catalogGroups?.takeIf { it.isNotEmpty() }?.let { groups ->
                _uiState.update { it.copy(modelCatalogGroups = groups) }
            }
            runCatching { api.modelsLive() }.getOrNull()?.let { live ->
                _uiState.update { it.copy(modelCatalogGroups = it.modelCatalogGroups.mergingLiveModels(live)) }
            }
            refreshComposerSelectionState()
        }
    }

    override fun selectReasoningEffort(effort: String) = setSessionReasoningEffort(effort)

    /** Port of `selectReasoningEffort`: only the current session's effort is written. */
    fun setSessionReasoningEffort(effort: String) {
        viewModelScope.launch {
            activationJob?.join()
            if (ensureSessionForSend() != null) selectReasoningEffortInternal(effort)
        }
    }

    override fun selectWorkspace(path: String) {
        viewModelScope.launch { selectWorkspacePath(path) }
    }

    override fun loadWorkspaceSuggestions(prefix: String) {
        viewModelScope.launch {
            if (_uiState.value.isViewingCachedData) {
                _uiState.update { s -> s.copy(workspaceSuggestions = s.workspaceRoots.mapNotNull { it.path }) }
                return@launch
            }

            try {
                val response = api.workspaceSuggestions(prefix)
                _uiState.update { it.copy(workspaceSuggestions = response.suggestions ?: emptyList()) }
            } catch (error: Exception) {
                _uiState.update { it.copy(composerConfigurationErrorMessage = error.message) }
            }
        }
    }

    override fun switchProfile(profile: ProfileSummary, startNewSession: Boolean) {
        viewModelScope.launch { switchProfileInternal(profile, startNewSession) }
    }

    override fun uploadAttachment(bytes: ByteArray, filename: String, previewData: ByteArray?) {
        viewModelScope.launch {
            activationJob?.join()
            if (ensureSessionForSend() == null) return@launch
            val generation = sessionGeneration
            val id = UUID.randomUUID().toString()
            var retained: String? = null
            try {
                retained = drafts.stageAttachment(bytes, filename, attachmentLease, id)
                val uploaded = attachmentCoordinator.uploadStandaloneAttachment(bytes, filename, previewData)
                if (uploaded == null || generation != sessionGeneration) {
                    attachmentLease.files.remove(retained)
                    drafts.deleteAttachmentIfUnreferenced(retained)
                    return@launch
                }
                val attachment = uploaded.copy(id = id, draftFileName = retained)
                attachmentLease.filesByAttachmentId[id] = retained
                attachmentCoordinator.restorePendingAttachments(listOf(attachment))
                persistComposer()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                setUploadAttachmentError(error.message)
            } finally {
                attachmentLease.slotIds.remove(id)
            }
        }
    }

    override fun importVoiceNote(bytes: ByteArray, filename: String) {
        viewModelScope.launch {
            _uiState.update {
                it.copy(isProcessingVoiceNote = true, uploadAttachmentErrorMessage = null)
            }
            try {
                if (ensureSessionForSend() == null) return@launch
                val attachment = attachmentCoordinator.uploadAttachment(bytes, filename)
                val transcription = runCatching { api.transcribeAudio(bytes, filename) }
                    .getOrElse { error ->
                        if (attachment != null) {
                            attachmentCoordinator.setUploadAttachmentError(
                                "Audio attached; transcription unavailable: ${error.message ?: "server error"}",
                            )
                        }
                        return@launch
                    }
                val transcript = transcription.transcript?.trim().orEmpty()
                if (transcript.isNotEmpty()) {
                    _uiState.update { state ->
                        val current = state.draft.trim()
                        state.copy(draft = listOf(current, transcript).filter(String::isNotEmpty).joinToString("\n\n"))
                    }
                } else if (!transcription.error.isNullOrBlank()) {
                    attachmentCoordinator.setUploadAttachmentError(
                        "Audio attached; transcription unavailable: ${transcription.error}",
                    )
                }
            } finally {
                _uiState.update { it.copy(isProcessingVoiceNote = false) }
            }
        }
    }

    /**
     * Port of `ChatDraftStore.appendShare` + `SharedImportReservation.moveIntoDraft`:
     * copy every file before atomically committing text, records and the occurrence
     * id. A failed import changes no composer content; the inbox keeps its source.
     * After commit the durable draft owns all files, even if re-upload fails or
     * navigation cancels hydration. Replaying the same occurrence adds nothing.
     */
    suspend fun applySharedImport(shared: SharedImport): Boolean {
        activationJob?.join()
        val key = draftKey ?: return false
        val generation = sessionGeneration
        persistComposer()
        try {
            val previous = drafts.draft(key)
            val files = if (shared.id in previous?.sharedImportIds.orEmpty()) emptyList() else withContext(ioDispatcher) {
                shared.attachments.map { attachment ->
                    if (attachment.file.length() > PendingAttachment.maximumUploadBytes) {
                        throw java.io.IOException(PendingAttachment.uploadTooLargeMessage(attachment.filename))
                    }
                    val bytes = attachment.file.readBytes()
                    val mime = attachment.mimeType ?: java.net.URLConnection.guessContentTypeFromName(attachment.filename)
                        ?: "application/octet-stream"
                    ChatDraftShareFile(bytes, attachment.filename, mime, mime.startsWith("image/"))
                }
            }
            drafts.appendShare(shared.id, shared.text.trim().let { if (it.isEmpty()) it else "$it\n" }, files,
                key, PendingAttachment.maximumUploadBytes)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (generation == sessionGeneration) setUploadAttachmentError(error.message ?: "The shared files could not be saved.")
            return false
        }
        if (generation != sessionGeneration || draftKey != key) return true
        val committed = drafts.draft(key) ?: return false
        if (generation != sessionGeneration || draftKey != key) return true
        applyDraft(committed)
        composerRevision += 1
        _uiState.update { it.copy(uploadAttachmentErrorMessage = shared.warnings.takeIf { notes -> notes.isNotEmpty() }?.joinToString("\n")) }
        if (draftRecords.isNotEmpty() && ensureSessionForSend() != null && generation == sessionGeneration) {
            restoreDraftAttachments(generation)
        }
        return true
    }

    override fun removePendingAttachment(id: String) {
        val removed = _uiState.value.pendingAttachments.filter { it.id == id }
        attachmentCoordinator.removePendingAttachment(id)
        draftRecords = draftRecords.filterNot { it.id == id }
        persistComposer()
        viewModelScope.launch { consumeSentAttachments(removed) }
    }

    override fun clearPendingAttachments() {
        val removed = _uiState.value.pendingAttachments
        attachmentCoordinator.clearPendingAttachments()
        draftRecords = emptyList()
        persistComposer()
        viewModelScope.launch { consumeSentAttachments(removed) }
    }

    override fun setUploadAttachmentError(message: String?) =
        attachmentCoordinator.setUploadAttachmentError(message)

    // MARK: - Pending prompts (pinned API)

    fun respondToApproval(choice: ApprovalChoice) {
        viewModelScope.launch { pendingActionCoordinator.respondToApproval(choice) }
    }

    fun skipApprovalsForCurrentSession() {
        viewModelScope.launch { pendingActionCoordinator.skipApprovalsForCurrentSession() }
    }

    fun respondToClarification(text: String) {
        viewModelScope.launch { pendingActionCoordinator.respondToClarification(text) }
    }

    // MARK: - Stream lifecycle (pinned API)
    /** Lifecycle ON_START hook — resumes stream + auxiliary prompt polling. */
    fun resumeForForeground() {
        isScreenActive = true
        pendingActionCoordinator.startMonitoring()
        viewModelScope.launch {
            activationJob?.join()
            streamCoordinator.reconnectIfNeeded()
            if (isScreenActive) pendingActionCoordinator.refreshApprovalBypassState()
        }
    }

    /** Lifecycle ON_STOP hook — suspends stream and all auxiliary polling. */
    fun suspendStreamForBackground() {
        isScreenActive = false
        parkQueuedMessages()
        persistComposer()
        persistPartialStream()
        streamCoordinator.suspendActiveStreamConnection()
        pendingActionCoordinator.stopMonitoring(clearPrompt = false)
    }

    /** Port of `cancelActiveStream()`. */
    private suspend fun cancelActiveStream(): Boolean {
        if (_uiState.value.activeStreamId == null) return false

        _uiState.update { it.copy(isCancellingStream = true, sendErrorMessage = null) }
        try {
            val response = streamCoordinator.cancelActiveStream() ?: return false
            if (response.ok == false) {
                _uiState.update {
                    it.copy(sendErrorMessage = response.error ?: "The server could not stop the current response.")
                }
                return false
            }
            return true
        } catch (error: Exception) {
            _uiState.update { it.copy(sendErrorMessage = error.message) }
            return false
        } finally {
            _uiState.update { it.copy(isCancellingStream = false) }
        }
    }

    // MARK: - Sending (port of sendMessage/performChatSend)

    /** Port of `sendMessage(_:modelContext:)`. Creates the session lazily in new-chat mode. */
    private suspend fun sendMessage(
        draft: String,
        submission: ComposerSubmission? = null,
    ): Boolean {
        // A streaming submission can reach here after the previous run ended.
        // Claim ownership before any lazy session creation in that case too.
        if (submission != null && !submission.ownsRunStart && !claimRunStart(submission)) return false
        if (_uiState.value.isViewingCachedData) {
            _uiState.update { it.copy(sendErrorMessage = "Reconnect to the server to send a message.") }
            return false
        }

        if (_uiState.value.isCompressionRotated) {
            compressionContinuationId?.let { resolveCompressionContinuation(it, sessionGeneration, currentProfile) }
            return false
        }
        val message = draft.trim()
        if (message.isEmpty() && _uiState.value.pendingAttachments.isEmpty()) return false

        val sessionId = ensureSessionForSend() ?: return false
        val sourceKey = draftKey
        submission?.sourceKey = sourceKey
        val localMessageId = "local-${UUID.randomUUID()}"
        val preparation = attachmentCoordinator.prepareForSend()

        val accepted = performChatSend(
            sessionId = sessionId,
            localMessageId = localMessageId,
            displayContent = message.ifEmpty { preparation.chatMessageText(message) },
            messageForApi = preparation.chatMessageText(draft = message),
            messageAttachments = preparation.messageAttachments,
            apiPayloads = preparation.apiPayloads,
            attachmentsToRestoreOnFailure = preparation.attachments,
            sourceKey = sourceKey,
            submission = submission,
        )
        if (accepted) {
            if (submission != null && sourceKey != null) {
                reconcileAcceptedSubmission(submission, sourceKey, preparation.attachments)
            } else {
                consumeSentAttachments(preparation.attachments)
            }
        }
        return accepted
    }

    /** New-chat mode: create the session on first send (`POST /api/session/new`). */
    private suspend fun ensureSessionForSend(): String? {
        sessionId?.let { return it }
        val generation = sessionGeneration

        try {
            val response = api.createSession(
                workspace = currentWorkspace,
                model = currentModel,
                modelProvider = requestModelProvider,
                profile = newChatProfileName ?: requestProfileName,
                projectId = newChatProjectId,
            )
            if (generation != sessionGeneration) return null
            val session = response.session
            val newSessionId = nonEmpty(session?.sessionId)
            if (newSessionId == null) {
                _uiState.update { it.copy(sendErrorMessage = "The server did not return the new session.") }
                return null
            }

            sessionId = newSessionId
            draftKey?.let { old ->
                val next = ChatDraftKey.session(old.serverId, newSessionId)
                drafts.moveDraft(old, next)
                draftKey = next
                attachmentLease.key = next
            }
            newChatProjectId = null
            newChatProfileName = null
            _uiState.update { it.copy(sessionId = newSessionId) }
            session?.let { applySessionIdentity(it) }
            session?.title?.let(::applySessionTitle)
            return newSessionId
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            if (generation == sessionGeneration) _uiState.update { it.copy(sendErrorMessage = error.message) }
            return null
        }
    }

    /** Port of `performChatSend(...)` — optimistic append + `POST /api/chat` + rollback. */
    private suspend fun performChatSend(
        sessionId: String,
        localMessageId: String,
        displayContent: String,
        messageForApi: String,
        messageAttachments: List<MessageAttachment>,
        apiPayloads: List<kotlinx.serialization.json.JsonElement>?,
        attachmentsToRestoreOnFailure: List<PendingAttachment>,
        sourceKey: ChatDraftKey?,
        submission: ComposerSubmission?,
    ): Boolean {
        val generation = sessionGeneration
        val profile = currentProfile
        val sentAt = currentEpochSeconds()
        _uiState.update { it.copy(isStartingChat = true, sendErrorMessage = null) }
        archiveLiveReasoningIfNeeded()
        archiveLiveToolCallsIfNeeded()
        _uiState.update { it.copy(liveReasoningText = "", liveToolCalls = emptyList()) }
        reasoningAnchorMessageId = null
        toolCallAnchorMessageId = null
        streamCoordinator.prepareForNewResponse()

        val optimisticMessage = ChatMessage(
            role = "user",
            content = displayContent,
            timestamp = currentEpochSeconds(),
            messageId = localMessageId,
            attachments = messageAttachments.ifEmpty { null },
        )
        updateMessages(_uiState.value.messages + optimisticMessage)
        bumpStreamingContentTrigger()
        cacheCurrentMessages()

        try {
            val explicitModelPick = explicitModelPickForChatStart()
            val response = api.startChat(
                sessionId = sessionId,
                message = messageForApi,
                workspace = currentWorkspace,
                model = currentModel,
                modelProvider = requestModelProvider,
                profile = requestProfileName,
                explicitModelPick = explicitModelPick,
                attachments = apiPayloads,
            )

            val streamId = response.streamId
            if (streamId == null) {
                _uiState.update {
                    it.copy(sendErrorMessage = response.error ?: "The server did not return a stream ID.")
                }
                rollbackOptimisticMessage(localMessageId)
                cacheCurrentMessages()
                attachmentCoordinator.restorePendingAttachments(attachmentsToRestoreOnFailure)
                return false
            }

            if (generation != sessionGeneration) return true
            completeExplicitModelPickForChatStart(explicitModelPick)
            streamCoordinator.start(streamId, runStartedAt = response.runStartedAt(sentAt))
            // Durable submission reconciliation is source-keyed in [sendMessage];
            // visible stream attachment stays scoped to the still-current chat.
            // The host already accepted this run; a local offer-storage failure cannot roll back its message.
            runCatching { onRunStarted() }
            return true
        } catch (error: Exception) {
            // Submission-owned cleanup is not scoped to whichever chat is now visible.
            sourceKey?.let { drafts.retainRejectedAttachments(attachmentsToRestoreOnFailure.mapNotNull { it.draftRecord() }, it) }
            if (generation == sessionGeneration) {
                rollbackOptimisticMessage(localMessageId)
                cacheCurrentMessages()
                attachmentCoordinator.restorePendingAttachments(attachmentsToRestoreOnFailure)
            } else if (sourceKey != null) {
                withContext(NonCancellable + ioDispatcher) {
                    val cached = cacheStore.cachedMessages(sourceKey.serverId, sessionId)
                    cacheStore.cacheMessages(cached.filterNot { it.messageId == localMessageId }, sourceKey.serverId, sessionId)
                }
            }
            val apiError = error as? ApiError
            if (apiError is ApiError.Http && apiError.statusCode == 409 && apiError.serverCode == "session_rotated") {
                val target = apiError.sessionRotation?.continuationSessionId
                if (sourceKey != null) sealedSessions[sourceKey] = target to apiError.message
                if (sourceKey != null && submission != null) {
                    retainRejectedSubmissionBeforeLookup(
                        submission = submission,
                        sourceKey = sourceKey,
                        attachments = attachmentsToRestoreOnFailure.mapNotNull { it.draftRecord() },
                    )
                }
                if (generation == sessionGeneration) {
                    compressionContinuationId = target
                    _uiState.update { it.copy(isCompressionRotated = true, sendErrorMessage = apiError.message) }
                    if (target != null) resolveCompressionContinuation(target, generation, profile)
                }
                return false
            }
            if (error is CancellationException) throw error
            if (generation != sessionGeneration) return false
            val conflictingStreamId = apiError?.activeStreamId
            if (conflictingStreamId != null) {
                performLoadMessages()
                streamCoordinatorStreamingAssistantMessageId = TranscriptTurnClassifier
                    .currentTurnAssistantAnchorIDs(_uiState.value.messages, _uiState.value.messagesOffset).firstOrNull()
                streamCoordinator.start(conflictingStreamId)
                return false
            }
            _uiState.update { it.copy(sendErrorMessage = error.message,
                agentRuntimeStaleFixPrompt = apiError?.agentRuntimeStale?.fixPrompt) }
            return false
        } finally {
            if (submission != null) {
                releaseRunStart(submission)
            } else if (generation == sessionGeneration) {
                _uiState.update { it.copy(isStartingChat = false) }
            }
        }
    }

    private fun rollbackOptimisticMessage(id: String) {
        updateMessages(_uiState.value.messages.filterNot { it.messageId == id })
    }

    private fun cacheCurrentMessages() {
        val sessionId = sessionId ?: return
        val messages = _uiState.value.messages
        val server = _uiState.value.serverCacheNamespace
        cacheStore.persistenceScope.launch(ioDispatcher) {
            runCatching { cacheStore.cacheMessages(messages, server, sessionId) }
        }
    }

    private suspend fun cacheCurrentMessagesBlocking(serverUrl: String, sessionId: String) {
        val messages = _uiState.value.messages
        runCatching {
            withContext(ioDispatcher) { cacheStore.cacheMessages(messages, serverUrl, sessionId) }
        }
    }

    // MARK: - Slash command execution

    /** Port of `SlashCommandExecutor.execute(text:viewModel:)`. */
    internal suspend fun executeSlashCommandText(text: String): SlashCommandExecutionResult =
        executeSlashCommandText(text, submission = null)

    private suspend fun executeSlashCommandText(
        text: String,
        submission: ComposerSubmission?,
    ): SlashCommandExecutionResult {
        val parsed = SlashCommandExecutor.parse(text) ?: return SlashCommandExecutionResult.SendAsMessage
        if (parsed.name.isEmpty()) return SlashCommandExecutionResult.NeedsSubArg

        val command = parsed.command
        if (command == null) {
            if (SlashCommandExecutor.isKnownUnsupportedCommand(parsed.name)) {
                return SlashCommandExecutionResult.Unsupported(
                    SlashCommandExecutor.unsupportedMessage(parsed.name),
                )
            }
            if (parsed.name.lowercase() == "skill") {
                return SlashCommandExecutionResult.Unsupported("Use `/skills [query]` to search skills.")
            }
            executeSkillShortcutCommand(parsed.name, parsed.args, submission)?.let { return it }
            // Match WebUI: let the agent/runtime handle unknown, non-blocked slash text.
            return SlashCommandExecutionResult.SendAsMessage
        }

        return executeSlashCommand(command, parsed.args, submission)
    }

    /** Port of `executeSlashCommand(_:args:)`. */
    internal suspend fun executeSlashCommand(
        command: SlashCommand,
        args: String = "",
    ): SlashCommandExecutionResult = executeSlashCommand(command, args, submission = null)

    private suspend fun executeSlashCommand(
        command: SlashCommand,
        args: String,
        submission: ComposerSubmission?,
    ): SlashCommandExecutionResult = when (val handler = command.handler) {
        is SlashCommandHandler.ClientSide -> when (handler.action) {
            ClientSideAction.CLEAR -> clearConversationFromSlashCommand()

            ClientSideAction.STOP -> {
                cancelActiveStream()
                SlashCommandExecutionResult.Executed(message = null)
            }

            ClientSideAction.NEW -> createSessionFromSlashCommand()

            ClientSideAction.HELP -> SlashCommandExecutionResult.Executed(message = SLASH_COMMAND_HELP_TEXT)
        }

        is SlashCommandHandler.ServerSide -> executeServerSideSlashCommand(handler.action, args, submission)

        SlashCommandHandler.Unsupported ->
            SlashCommandExecutionResult.Unsupported(SlashCommandExecutor.unsupportedMessage(command.name))
    }

    private suspend fun executeServerSideSlashCommand(
        action: ServerSideAction,
        args: String,
        submission: ComposerSubmission?,
    ): SlashCommandExecutionResult = when (action) {
        ServerSideAction.MODEL -> switchModelFromSlashCommand(args)
        ServerSideAction.WORKSPACE -> switchWorkspaceFromSlashCommand(args)
        ServerSideAction.REASONING -> switchReasoningFromSlashCommand(args)
        ServerSideAction.TITLE -> renameSessionFromSlashCommand(args)
        ServerSideAction.PERSONALITY -> setPersonalityFromSlashCommand(args)
        ServerSideAction.SKILLS -> searchSkillsFromSlashCommand(args, submission)
        ServerSideAction.BRANCH -> branchSessionFromSlashCommand(args)
        ServerSideAction.UNDO -> undoLastExchangeFromSlashCommand()
        ServerSideAction.RETRY -> retryLastTurnFromSlashCommand()
        ServerSideAction.COMPRESS -> compressSessionFromSlashCommand(args)
        ServerSideAction.QUEUE -> queueMessageFromSlashCommand(args, submission)
        ServerSideAction.STEER -> steerResponseFromSlashCommand(args, submission)
        ServerSideAction.INTERRUPT -> interruptResponseFromSlashCommand(args, submission)
        ServerSideAction.STATUS -> SlashCommandExecutionResult.Executed(message = statusMessageFromSlashCommand())
        ServerSideAction.BTW -> askBtwFromSlashCommand(args)
        ServerSideAction.BACKGROUND -> startBackgroundFromSlashCommand(args)
        ServerSideAction.GOAL -> submitGoalFromSlashCommand(args)
    }

    /** Port of `queueMessageFromSlashCommand(_:)`. */
    private suspend fun queueMessageFromSlashCommand(
        args: String,
        submission: ComposerSubmission? = null,
    ): SlashCommandExecutionResult {
        val message = args.trim()
        if (message.isEmpty()) {
            return SlashCommandExecutionResult.Unsupported("Usage: /queue <message>")
        }

        if (_uiState.value.activeStreamId == null) {
            val sent = sendMessage(message, submission)
            return if (sent) {
                SlashCommandExecutionResult.Executed(message = null)
            } else {
                SlashCommandExecutionResult.Unsupported(
                    _uiState.value.sendErrorMessage ?: "Could not send the queued message.",
                )
            }
        }

        enqueueQueuedSlashMessage(
            message,
            attachmentCoordinator.consumePendingAttachments(),
            submittedComposerRevision = submission?.revision,
        )
        return SlashCommandExecutionResult.Executed(message = null)
    }

    /** Port of `steerResponseFromSlashCommand(_:)` — also the streaming-send default path. */
    private suspend fun steerResponseFromSlashCommand(
        args: String,
        submission: ComposerSubmission? = null,
    ): SlashCommandExecutionResult {
        val message = args.trim()
        if (message.isEmpty()) {
            return SlashCommandExecutionResult.Unsupported("Usage: /steer <message>")
        }

        val sessionId = sessionId
            ?: return SlashCommandExecutionResult.Unsupported("The server did not provide a session ID.")

        if (_uiState.value.activeStreamId == null) {
            val sent = sendMessage(message, submission)
            return if (sent) {
                SlashCommandExecutionResult.Executed(message = null)
            } else {
                SlashCommandExecutionResult.Unsupported(
                    _uiState.value.sendErrorMessage ?: "Could not send the steering message.",
                )
            }
        }

        val steeredStreamId = _uiState.value.activeStreamId
        val generation = sessionGeneration
        val files = _uiState.value.pendingAttachments
        val text = PendingAttachment.steerMessageText(message, files)
        val outcome = try {
            ChatSteerOutcome.classify(api.steerChat(sessionId, text))
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            ChatSteerOutcome.Refused(error as? ApiError ?: ApiError.Network(error))
        }
        if (generation != sessionGeneration) return SlashCommandExecutionResult.Unsupported("The chat changed; your message was not consumed.")
        if (outcome is ChatSteerOutcome.Refused &&
            (_uiState.value.activeStreamId == steeredStreamId || outcome.transportError != null)
        ) {
            val reason = outcome.transportError?.message
            val failure = "Couldn't steer" + (reason?.let { "\n$it" } ?: "")
            _uiState.update { it.copy(steerFailureMessage = failure) }
            return SlashCommandExecutionResult.Unsupported(failure)
        }
        val ids = files.mapTo(HashSet()) { it.id }
        attachmentCoordinator.replacePendingAttachments(_uiState.value.pendingAttachments.filterNot { it.id in ids })
        when (outcome) {
            ChatSteerOutcome.Delivered -> {
                updateMessages(_uiState.value.messages + ChatMessage(role = "user", content = text,
                    timestamp = currentEpochSeconds(), messageId = "local-steer-${UUID.randomUUID()}", displayKind = ChatMessage.STEER_DISPLAY_KIND))
                _uiState.update { it.copy(steeringConfirmation = "Steering hint delivered.") }
                consumeSentAttachments(files)
            }
            ChatSteerOutcome.ServerQueued -> enqueueQueuedSlashMessage(
                message, files, submittedComposerRevision = submission?.revision,
            )
            else -> {
                enqueueQueuedSlashMessage(
                    message, files, atFront = true, submittedComposerRevision = submission?.revision,
                )
                steeredStreamId?.let { streamCoordinator.refreshTranscriptIfCompleted(it) }
            }
        }
        drainQueuedSlashMessageIfIdle()
        return SlashCommandExecutionResult.Executed(message = null)
    }

    /** Port of `interruptResponseFromSlashCommand(_:)`. */
    private suspend fun interruptResponseFromSlashCommand(
        args: String,
        submission: ComposerSubmission? = null,
    ): SlashCommandExecutionResult {
        val message = args.trim()
        if (message.isEmpty()) {
            return SlashCommandExecutionResult.Unsupported("Usage: /interrupt <message>")
        }

        if (_uiState.value.activeStreamId == null) {
            val sent = sendMessage(message, submission)
            return if (sent) {
                SlashCommandExecutionResult.Executed(message = null)
            } else {
                SlashCommandExecutionResult.Unsupported(
                    _uiState.value.sendErrorMessage ?: "Could not send the interrupt message.",
                )
            }
        }

        enqueueQueuedSlashMessage(
            message,
            attachmentCoordinator.consumePendingAttachments(),
            atFront = true,
            submittedComposerRevision = submission?.revision,
        )
        cancelActiveStream()

        if (_uiState.value.activeStreamId != null) {
            return SlashCommandExecutionResult.Executed(
                message = "Could not stop the current response yet, so the interrupt message was queued for the next turn.",
            )
        }

        return SlashCommandExecutionResult.Executed(
            message = "Interrupted the current response and queued your message to send next.",
        )
    }

    /** Port of `statusMessageFromSlashCommand()`. */
    private fun statusMessageFromSlashCommand(): String {
        val state = _uiState.value
        val running = if (state.activeStreamId == null) "No" else "Yes"
        val queued = queuedSlashMessages.size
        val backgroundTasks = backgroundPromptsByTaskId.size
        val profile = selectedProfileName ?: currentProfile ?: "default"
        val workspace = currentWorkspace ?: "Unknown"
        val model = currentModel ?: "Unknown"
        val provider = currentModelProvider ?: providerFromModel(model) ?: "Unknown"
        val messageCount = state.messages.count { it.role != "tool" }
        val tokens = statusTokenLine()

        return """
            |Session status:
            |
            |- Session ID: ${sessionId ?: "Unknown"}
            |- Title: ${state.displayTitle.ifEmpty { "Untitled Session" }}
            |- Model: $model
            |- Provider: $provider
            |- Profile: $profile
            |- Workspace: $workspace
            |- Agent running: $running
            |- Queued messages: $queued
            |- Background tasks: $backgroundTasks
            |- Messages loaded: $messageCount
            |- Tokens: $tokens
        """.trimMargin()
    }

    /** Port of `askBtwFromSlashCommand(_:)`. */
    private suspend fun askBtwFromSlashCommand(args: String): SlashCommandExecutionResult {
        val question = args.trim()
        if (question.isEmpty()) {
            return SlashCommandExecutionResult.Unsupported("Usage: /btw <question>")
        }

        val sessionId = sessionId
            ?: return SlashCommandExecutionResult.Unsupported("The server did not provide a session ID.")

        if (_uiState.value.isViewingCachedData) {
            return SlashCommandExecutionResult.Unsupported("Reconnect to the server to ask a side question.")
        }
        if (isCliSession) {
            return SlashCommandExecutionResult.Unsupported("/btw is available for WebUI sessions only.")
        }
        if (_uiState.value.activeStreamId != null) {
            return SlashCommandExecutionResult.Unsupported("Wait for the current response to finish before using /btw.")
        }
        if (activeBtwStreamId != null) {
            return SlashCommandExecutionResult.Unsupported("Wait for the current /btw answer to finish first.")
        }

        try {
            val response = api.startBtw(sessionId = sessionId, question = question)
            response.error?.takeIf { it.isNotEmpty() }?.let {
                return SlashCommandExecutionResult.Unsupported(it)
            }

            val streamId = response.streamId
            if (streamId.isNullOrEmpty()) {
                return SlashCommandExecutionResult.Unsupported("The server did not return a /btw stream.")
            }

            startBtwStream(streamId = streamId, question = question)
            return SlashCommandExecutionResult.Executed(message = null)
        } catch (error: Exception) {
            return SlashCommandExecutionResult.Unsupported(error.message ?: "Could not start the side question.")
        }
    }

    /** Port of `startBackgroundFromSlashCommand(_:)`. */
    private suspend fun startBackgroundFromSlashCommand(args: String): SlashCommandExecutionResult {
        val prompt = args.trim()
        if (prompt.isEmpty()) {
            return SlashCommandExecutionResult.Unsupported("Usage: /background <prompt>")
        }

        val sessionId = sessionId
            ?: return SlashCommandExecutionResult.Unsupported("The server did not provide a session ID.")

        if (_uiState.value.isViewingCachedData) {
            return SlashCommandExecutionResult.Unsupported("Reconnect to the server to start a background task.")
        }
        if (isCliSession) {
            return SlashCommandExecutionResult.Unsupported("/background is available for WebUI sessions only.")
        }

        try {
            val response = api.startBackground(sessionId = sessionId, prompt = prompt)
            response.error?.takeIf { it.isNotEmpty() }?.let {
                return SlashCommandExecutionResult.Unsupported(it)
            }

            val taskId = response.taskId
            if (taskId.isNullOrEmpty()) {
                return SlashCommandExecutionResult.Unsupported("The server did not return a background task.")
            }

            backgroundPromptsByTaskId[taskId] = prompt
            startBackgroundPollingIfNeeded(parentSessionId = sessionId)
            return SlashCommandExecutionResult.Executed(
                message = "Background task started. I'll add the result here when it completes.",
            )
        } catch (error: Exception) {
            return SlashCommandExecutionResult.Unsupported(error.message ?: "Could not start the background task.")
        }
    }

    /** Port of `submitGoalFromSlashCommand(_:)`. */
    private suspend fun submitGoalFromSlashCommand(args: String): SlashCommandExecutionResult {
        val goalArgs = args.trim()
        val didSubmit = submitGoal(goalArgs.ifEmpty { "status" })
        return if (didSubmit) {
            SlashCommandExecutionResult.Executed(message = null)
        } else {
            SlashCommandExecutionResult.Unsupported(goalErrorMessage ?: "Could not submit the goal command.")
        }
    }

    /** Port of `submitGoal(args:modelContext:)` — `POST /api/goal`. */
    private suspend fun submitGoal(rawArgs: String): Boolean {
        if (_uiState.value.isViewingCachedData) {
            goalErrorMessage = "Reconnect to the server to manage goals."
            _uiState.update { it.copy(sendErrorMessage = goalErrorMessage) }
            return false
        }

        val args = rawArgs.trim()
        if (args.isEmpty()) return false

        val sessionId = sessionId
        if (sessionId == null) {
            goalErrorMessage = "The server did not provide a session ID."
            _uiState.update { it.copy(sendErrorMessage = goalErrorMessage) }
            return false
        }

        if (_uiState.value.activeStreamId != null) {
            goalErrorMessage = "Wait for the current response to finish before changing goals."
            _uiState.update { it.copy(sendErrorMessage = goalErrorMessage) }
            return false
        }

        goalErrorMessage = null
        _uiState.update { it.copy(sendErrorMessage = null) }

        try {
            val response = api.submitGoal(
                sessionId = sessionId,
                args = args,
                workspace = currentWorkspace,
                model = currentModel,
                modelProvider = requestModelProvider,
                profile = requestProfileName,
            )

            currentGoal = response.goal

            if (response.ok == false || response.action?.lowercase() == "error") {
                goalErrorMessage = response.displayMessage ?: "Goal request failed."
                _uiState.update { it.copy(sendErrorMessage = goalErrorMessage) }
                return false
            }

            if (response.kickoffPromptText == null) {
                response.displayMessage?.let(::appendLocalNoticeMessage)
                return true
            }

            return attachGoalKickoffStream(noticeMessage = response.displayMessage)
        } catch (error: Exception) {
            goalErrorMessage = error.message
            _uiState.update { it.copy(sendErrorMessage = goalErrorMessage) }
            return false
        }
    }

    /** Port of `attachGoalKickoffStream(noticeMessage:modelContext:)`. */
    private suspend fun attachGoalKickoffStream(noticeMessage: String?): Boolean {
        performLoadMessages()

        _uiState.value.errorMessage?.let { errorMessage ->
            goalErrorMessage = errorMessage
            _uiState.update { it.copy(sendErrorMessage = errorMessage) }
            return false
        }

        val streamId = _uiState.value.activeStreamId
        if (streamId == null) {
            noticeMessage?.let(::appendLocalNoticeMessage)
            return true
        }

        if (_uiState.value.streamingAssistantMessageId == null) {
            streamCoordinatorStreamingAssistantMessageId = latestAssistantMessageId(_uiState.value.messages)
        }
        noticeMessage?.let(::pinLocalNoticeMessage)

        streamCoordinator.start(streamId)
        return true
    }

    /** Port of `switchModelFromSlashCommand(_:)` — `POST /api/session/update`. */
    private suspend fun switchModelFromSlashCommand(args: String): SlashCommandExecutionResult {
        val requestedModel = args.trim()
        if (requestedModel.isEmpty()) {
            return SlashCommandExecutionResult.Unsupported("Usage: /model <id>")
        }

        val sessionId = sessionId
            ?: return SlashCommandExecutionResult.Unsupported("The server did not provide a session ID.")

        if (!canRunConfigurationSlashCommand("change models")) {
            return SlashCommandExecutionResult.Unsupported(
                _uiState.value.composerConfigurationErrorMessage ?: "Model switching is unavailable.",
            )
        }

        val match = modelOption(matching = requestedModel)
        _uiState.update { it.copy(composerConfigurationErrorMessage = null, sendErrorMessage = null) }

        return try {
            val response = api.updateSession(
                id = sessionId,
                workspace = currentWorkspace,
                model = match?.id ?: requestedModel,
                modelProvider = match?.providerID,
            )

            currentModel = response.session?.model ?: match?.id ?: requestedModel
            currentModelProvider = response.session?.modelProvider ?: match?.providerID ?: currentModelProvider
            currentWorkspace = response.session?.workspace ?: currentWorkspace
            pendingExplicitModelPick = true
            refreshComposerSelectionState()
            refreshReasoningEffortGating()
            SlashCommandExecutionResult.Executed(message = null)
        } catch (error: Exception) {
            _uiState.update { it.copy(composerConfigurationErrorMessage = error.message) }
            SlashCommandExecutionResult.Unsupported(error.message ?: "Model switching failed.")
        }
    }

    /** Port of `switchWorkspaceFromSlashCommand(_:)`. */
    private suspend fun switchWorkspaceFromSlashCommand(args: String): SlashCommandExecutionResult {
        val requestedWorkspace = args.trim()
        if (requestedWorkspace.isEmpty()) {
            return SlashCommandExecutionResult.Unsupported("Usage: /workspace <path>")
        }

        val sessionId = sessionId
            ?: return SlashCommandExecutionResult.Unsupported("The server did not provide a session ID.")

        if (!canRunConfigurationSlashCommand("change workspace")) {
            return SlashCommandExecutionResult.Unsupported(
                _uiState.value.composerConfigurationErrorMessage ?: "Workspace switching is unavailable.",
            )
        }

        val workspace = workspacePath(matching = requestedWorkspace) ?: requestedWorkspace
        _uiState.update { it.copy(composerConfigurationErrorMessage = null, sendErrorMessage = null) }

        return try {
            val response = api.updateSession(
                id = sessionId,
                workspace = workspace,
                model = currentModel,
                modelProvider = currentModelProvider,
            )

            currentWorkspace = response.session?.workspace ?: workspace
            currentModel = response.session?.model ?: currentModel
            currentModelProvider = response.session?.modelProvider ?: currentModelProvider
            _uiState.update { s -> s.copy(workspaceSuggestions = s.workspaceRoots.mapNotNull { it.path }) }
            refreshComposerSelectionState()
            SlashCommandExecutionResult.Executed(message = null)
        } catch (error: Exception) {
            _uiState.update { it.copy(composerConfigurationErrorMessage = error.message) }
            SlashCommandExecutionResult.Unsupported(error.message ?: "Workspace switching failed.")
        }
    }

    /** Port of `switchReasoningFromSlashCommand(_:)`. */
    private suspend fun switchReasoningFromSlashCommand(args: String): SlashCommandExecutionResult {
        val reasoning = args.trim().lowercase()
        if (reasoning.isEmpty()) {
            return SlashCommandExecutionResult.Unsupported(
                "Usage: /reasoning show|hide|none|minimal|low|medium|high|xhigh",
            )
        }

        if (!canRunConfigurationSlashCommand("change reasoning")) {
            return SlashCommandExecutionResult.Unsupported(
                _uiState.value.composerConfigurationErrorMessage ?: "Reasoning changes are unavailable.",
            )
        }

        _uiState.update { it.copy(composerConfigurationErrorMessage = null, sendErrorMessage = null) }
        return try {
            when {
                reasoning in REASONING_DISPLAY_ARGS -> {
                    api.saveReasoningDisplay(reasoning)
                }

                reasoning in REASONING_EFFORT_ARGS -> {
                    val id = ensureSessionForSend() ?: return SlashCommandExecutionResult.Unsupported("The server did not provide a session ID.")
                    val response = api.saveReasoningEffort(reasoning, sessionId = id)
                    selectedReasoningEffort = response.effectiveEffort ?: reasoning
                    refreshComposerSelectionState()
                }

                else -> return SlashCommandExecutionResult.Unsupported("Unknown reasoning level: $reasoning.")
            }
            SlashCommandExecutionResult.Executed(message = null)
        } catch (error: Exception) {
            _uiState.update { it.copy(composerConfigurationErrorMessage = error.message) }
            SlashCommandExecutionResult.Unsupported(error.message ?: "Reasoning changes failed.")
        }
    }

    /** Port of `renameSessionFromSlashCommand(_:)`. */
    private suspend fun renameSessionFromSlashCommand(args: String): SlashCommandExecutionResult {
        val title = args.trim()
        if (title.isEmpty()) {
            val currentTitle = _uiState.value.displayTitle.ifEmpty { "Untitled Session" }
            return SlashCommandExecutionResult.Executed(
                message = "Current title: **$currentTitle**\n\nUse `/title <text>` to rename this session.",
            )
        }

        val sessionId = sessionId
            ?: return SlashCommandExecutionResult.Unsupported("The server did not provide a session ID.")

        if (_uiState.value.activeStreamId != null) {
            return SlashCommandExecutionResult.Unsupported(
                "Wait for the current response to finish before renaming the session.",
            )
        }

        _uiState.update { it.copy(sendErrorMessage = null) }
        return try {
            val response = api.renameSession(id = sessionId, title = title)
            response.error?.let { return SlashCommandExecutionResult.Unsupported(it) }
            applySessionTitle(response.session?.title ?: title)
            SlashCommandExecutionResult.Executed(message = "Title set to **${_uiState.value.displayTitle}**.")
        } catch (error: Exception) {
            SlashCommandExecutionResult.Unsupported(error.message ?: "Renaming failed.")
        }
    }

    /** Port of `setPersonalityFromSlashCommand(_:)`. */
    private suspend fun setPersonalityFromSlashCommand(args: String): SlashCommandExecutionResult {
        val requestedPersonality = args.trim()
        if (requestedPersonality.isEmpty()) {
            return personalityListMessage()
        }

        val sessionId = sessionId
            ?: return SlashCommandExecutionResult.Unsupported("The server did not provide a session ID.")

        if (_uiState.value.activeStreamId != null) {
            return SlashCommandExecutionResult.Unsupported(
                "Wait for the current response to finish before changing personality.",
            )
        }

        val normalized = requestedPersonality.lowercase()
        val name = if (normalized in PERSONALITY_CLEAR_ARGS) "" else requestedPersonality

        _uiState.update { it.copy(sendErrorMessage = null) }
        return try {
            val response = api.setPersonality(sessionId = sessionId, name = name)
            response.error?.let { return SlashCommandExecutionResult.Unsupported(it) }

            if (name.isEmpty() || response.personality == null) {
                SlashCommandExecutionResult.Executed(message = "Personality cleared.")
            } else {
                SlashCommandExecutionResult.Executed(
                    message = "Personality set to **${response.personality ?: name}**.",
                )
            }
        } catch (error: Exception) {
            SlashCommandExecutionResult.Unsupported(error.message ?: "Setting the personality failed.")
        }
    }

    /** Port of `personalityListMessage()`. */
    private suspend fun personalityListMessage(): SlashCommandExecutionResult = try {
        val personalities = api.personalities().personalities ?: emptyList()
        if (personalities.isEmpty()) {
            SlashCommandExecutionResult.Executed(message = "No personalities are configured on the server.")
        } else {
            val list = personalities.mapNotNull { personality ->
                val name = personality.name?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val description = personality.description
                if (!description.isNullOrEmpty()) {
                    "- **$name** - $description"
                } else {
                    "- **$name**"
                }
            }.joinToString(separator = "\n")

            SlashCommandExecutionResult.Executed(
                message = "Available personalities:\n\n$list\n\nUse `/personality <name>` or `/personality none`.",
            )
        }
    } catch (error: Exception) {
        SlashCommandExecutionResult.Unsupported(error.message ?: "Listing personalities failed.")
    }

    /** Port of `searchSkillsFromSlashCommand(_:)`. */
    private suspend fun searchSkillsFromSlashCommand(
        args: String,
        submission: ComposerSubmission?,
    ): SlashCommandExecutionResult = try {
        val suggestions = skillSuggestionsForSlashCommand()
        val invocation = SlashSkillFormatter.invocation(args, suggestions)
        if (invocation != null) {
            val sent = sendMessage(SlashSkillFormatter.messageText(invocation), submission)
            if (sent) {
                SlashCommandExecutionResult.Executed(message = null)
            } else {
                SlashCommandExecutionResult.Unsupported(
                    _uiState.value.sendErrorMessage ?: "Could not send the skill message.",
                )
            }
        } else {
            SlashCommandExecutionResult.Executed(
                message = SlashSkillFormatter.message(suggestions, SlashSkillFormatter.skillQuery(args)),
            )
        }
    } catch (error: Exception) {
        SlashCommandExecutionResult.Unsupported(error.message ?: "Searching skills failed.")
    }

    /** Port of `executeSkillShortcutCommand(name:args:)` — `/skill-<slug> <message>`. */
    internal suspend fun executeSkillShortcutCommand(
        name: String,
        args: String,
    ): SlashCommandExecutionResult? = executeSkillShortcutCommand(name, args, submission = null)

    private suspend fun executeSkillShortcutCommand(
        name: String,
        args: String,
        submission: ComposerSubmission?,
    ): SlashCommandExecutionResult? = try {
        val suggestions = skillSuggestionsForSlashCommand()
        val skill = SlashSkillFormatter.skill(name, suggestions)
        if (skill == null) {
            null
        } else {
            val message = args.trim()
            if (message.isEmpty()) {
                SlashCommandExecutionResult.Executed(message = SlashSkillFormatter.detailMessage(skill))
            } else {
                val commandText = "/${skill.slashName} $message"
                val sent = sendMessage(commandText, submission)
                if (sent) {
                    SlashCommandExecutionResult.Executed(message = null)
                } else {
                    SlashCommandExecutionResult.Unsupported(
                        _uiState.value.sendErrorMessage ?: "Could not send the skill message.",
                    )
                }
            }
        }
    } catch (error: Exception) {
        SlashCommandExecutionResult.Unsupported(error.message ?: "Could not load skills.")
    }

    /** Port of `skillSuggestionsForSlashCommand()` — cached after the first fetch. */
    private suspend fun skillSuggestionsForSlashCommand(): List<SkillSlashSuggestion> {
        if (hasLoadedSkillSlashSuggestions) return _uiState.value.skillSuggestions

        val response = api.skills()
        val suggestions = SlashSkillFormatter.suggestions(response.skills ?: emptyList())
        _uiState.update { it.copy(skillSuggestions = suggestions) }
        hasLoadedSkillSlashSuggestions = true
        return suggestions
    }

    /** Port of `branchSessionFromSlashCommand(_:)`. */
    private suspend fun branchSessionFromSlashCommand(args: String): SlashCommandExecutionResult {
        if (_uiState.value.isViewingCachedData) {
            return SlashCommandExecutionResult.Unsupported("Reconnect to the server to fork a conversation.")
        }
        if (_uiState.value.activeStreamId != null) {
            return SlashCommandExecutionResult.Unsupported("Wait for the current response to finish before forking.")
        }
        val sessionId = sessionId
            ?: return SlashCommandExecutionResult.Unsupported("The server did not provide a session ID.")

        val title = args.trim()
        _uiState.update { it.copy(sendErrorMessage = null) }

        return try {
            val response = api.branchSession(id = sessionId, title = title.ifEmpty { null })

            val forkedSessionId = response.sessionId
                ?: return SlashCommandExecutionResult.Unsupported(
                    response.error ?: "The server did not return the forked session ID.",
                )

            val forkedResponse = api.session(id = forkedSessionId, includeMessages = false, messageLimit = null)
            val forkedSessionDetail = forkedResponse.session
                ?: return SlashCommandExecutionResult.Unsupported("The server did not return the forked session.")

            SlashCommandExecutionResult.OpenedSession(sessionSummaryFrom(forkedSessionDetail))
        } catch (error: Exception) {
            SlashCommandExecutionResult.Unsupported(error.message ?: "Forking failed.")
        }
    }

    /** Port of `createSessionFromSlashCommand()` (/new). */
    private suspend fun createSessionFromSlashCommand(): SlashCommandExecutionResult {
        if (_uiState.value.isViewingCachedData) {
            return SlashCommandExecutionResult.Unsupported("Reconnect to the server to start a new session.")
        }
        if (_uiState.value.activeStreamId != null) {
            return SlashCommandExecutionResult.Unsupported(
                "Wait for the current response to finish before starting a new session.",
            )
        }

        _uiState.update { it.copy(sendErrorMessage = null, composerConfigurationErrorMessage = null) }
        return try {
            val response = api.createSession(
                workspace = currentWorkspace,
                model = currentModel,
                modelProvider = requestModelProvider,
                profile = requestProfileName,
            )

            val session = response.session
                ?: return SlashCommandExecutionResult.Unsupported("The server did not return the new session.")

            SlashCommandExecutionResult.OpenedSession(sessionSummaryFrom(session))
        } catch (error: Exception) {
            SlashCommandExecutionResult.Unsupported(error.message ?: "Creating a session failed.")
        }
    }

    /** Port of `compressSessionFromSlashCommand(_:)` — `POST /api/session/compress`. */
    private suspend fun compressSessionFromSlashCommand(args: String): SlashCommandExecutionResult {
        if (_uiState.value.isViewingCachedData) {
            return SlashCommandExecutionResult.Unsupported("Reconnect to the server to compress context.")
        }
        if (_uiState.value.activeStreamId != null) {
            return SlashCommandExecutionResult.Unsupported(
                "Wait for the current response to finish before compressing context.",
            )
        }
        val sessionId = sessionId
            ?: return SlashCommandExecutionResult.Unsupported("The server did not provide a session ID.")

        val focusTopic = args.trim()
        _uiState.update { it.copy(sendErrorMessage = null) }

        return try {
            val response = api.compressSession(id = sessionId, focusTopic = focusTopic.ifEmpty { null })

            response.error?.let { return SlashCommandExecutionResult.Unsupported(it) }
            val session = response.session
                ?: return SlashCommandExecutionResult.Unsupported("The server did not return the compressed session.")

            updateMessages(session.messages ?: emptyList())
            updateOlderMessagePagination(session, _uiState.value.messages.size)
            applySessionIdentity(session)
            val snapshot = contextWindowSnapshot(session)
            _uiState.update {
                it.copy(
                    isViewingCachedData = false,
                    contextWindow = snapshot?.replacingTokensUsed(response.summary?.compressedTokenEstimate),
                    completedReasoningGroups = emptyList(),
                    liveToolCalls = emptyList(),
                    liveReasoningText = "",
                    streamingAssistantMessageId = null,
                )
            }
            session.title?.let(::applySessionTitle)
            setCompletedToolCallGroups(
                ToolCallGroup.groups(
                    persistedToolCalls = session.toolCalls ?: emptyList(),
                    messages = _uiState.value.messages,
                    messageOffset = _uiState.value.messagesOffset,
                ),
            )
            toolCallAnchorMessageId = null
            reasoningAnchorMessageId = null
            streamCoordinator.prepareForNewResponse()

            val headline = response.summary?.headline?.trim()
            val tokenLine = response.summary?.tokenLine?.trim()
            val focus = response.focusTopic?.trim()
            val details = listOfNotNull(
                headline?.takeIf { it.isNotEmpty() },
                tokenLine?.takeIf { it.isNotEmpty() },
                focus?.takeIf { it.isNotEmpty() }?.let { "Focus: $it" },
            ).joinToString(separator = "\n")

            if (details.isEmpty()) {
                SlashCommandExecutionResult.Executed(message = "Context compressed.")
            } else {
                SlashCommandExecutionResult.Executed(message = "Context compressed.\n\n$details")
            }
        } catch (error: Exception) {
            SlashCommandExecutionResult.Unsupported(error.message ?: "Compressing context failed.")
        }
    }

    /** Port of `undoLastExchangeFromSlashCommand()` — `POST /api/session/undo`. */
    private suspend fun undoLastExchangeFromSlashCommand(): SlashCommandExecutionResult {
        if (_uiState.value.isViewingCachedData) {
            return SlashCommandExecutionResult.Unsupported("Reconnect to the server to undo messages.")
        }
        if (isCliSession) {
            return SlashCommandExecutionResult.Unsupported("Undo is available for WebUI sessions only.")
        }
        if (_uiState.value.activeStreamId != null) {
            return SlashCommandExecutionResult.Unsupported(
                "Wait for the current response to finish before undoing messages.",
            )
        }
        val sessionId = sessionId
            ?: return SlashCommandExecutionResult.Unsupported("The server did not provide a session ID.")

        _uiState.update { it.copy(sendErrorMessage = null) }
        return try {
            val response = api.undoSession(id = sessionId)
            response.error?.let { return SlashCommandExecutionResult.Unsupported(it) }

            performLoadMessages()
            _uiState.value.errorMessage?.let { return SlashCommandExecutionResult.Unsupported(it) }

            SlashCommandExecutionResult.Executed(message = null)
        } catch (error: Exception) {
            SlashCommandExecutionResult.Unsupported(error.message ?: "Undo failed.")
        }
    }

    /** Port of `retryLastTurnFromSlashCommand()` — `POST /api/session/retry` + re-send. */
    private suspend fun retryLastTurnFromSlashCommand(): SlashCommandExecutionResult {
        if (_uiState.value.isViewingCachedData) {
            return SlashCommandExecutionResult.Unsupported("Reconnect to the server to retry messages.")
        }
        if (isCliSession) {
            return SlashCommandExecutionResult.Unsupported("Retry is available for WebUI sessions only.")
        }
        if (_uiState.value.activeStreamId != null) {
            return SlashCommandExecutionResult.Unsupported(
                "Wait for the current response to finish before retrying messages.",
            )
        }
        val sessionId = sessionId
            ?: return SlashCommandExecutionResult.Unsupported("The server did not provide a session ID.")

        _uiState.update { it.copy(isStartingChat = true, sendErrorMessage = null) }
        archiveLiveReasoningIfNeeded()
        archiveLiveToolCallsIfNeeded()
        _uiState.update { it.copy(liveReasoningText = "", liveToolCalls = emptyList()) }
        reasoningAnchorMessageId = null
        toolCallAnchorMessageId = null
        streamCoordinator.prepareForNewResponse()

        try {
            val retryResponse = api.retrySession(id = sessionId)
            retryResponse.error?.let { return SlashCommandExecutionResult.Unsupported(it) }

            val lastUserText = retryResponse.lastUserText?.trim() ?: ""
            if (lastUserText.isEmpty()) {
                return SlashCommandExecutionResult.Unsupported("The server did not return a message to retry.")
            }

            // Post-retry reload keeps the raw msg_limit cap (not a cold load).
            val sessionResponse = api.session(
                id = sessionId,
                includeMessages = true,
                messageLimit = MESSAGE_PAGE_LIMIT,
            )
            val session = sessionResponse.session
            if (session != null) {
                updateMessages(session.messages ?: emptyList())
                updateOlderMessagePagination(session, _uiState.value.messages.size)
                setCompletedToolCallGroups(
                    ToolCallGroup.groups(
                        persistedToolCalls = session.toolCalls ?: emptyList(),
                        messages = _uiState.value.messages,
                        messageOffset = _uiState.value.messagesOffset,
                    ),
                )
                _uiState.update { it.copy(completedReasoningGroups = emptyList()) }
            } else {
                performLoadMessages()
                _uiState.value.errorMessage?.let { return SlashCommandExecutionResult.Unsupported(it) }
            }

            _uiState.update { it.copy(liveToolCalls = emptyList(), liveReasoningText = "") }
            toolCallAnchorMessageId = null
            reasoningAnchorMessageId = null

            val explicitModelPick = explicitModelPickForChatStart()
            val chatResponse = api.startChat(
                sessionId = sessionId,
                message = lastUserText,
                workspace = currentWorkspace,
                model = currentModel,
                modelProvider = requestModelProvider,
                profile = requestProfileName,
                explicitModelPick = explicitModelPick,
            )

            val streamId = chatResponse.streamId
                ?: return SlashCommandExecutionResult.Unsupported(
                    chatResponse.error ?: "The server did not return a stream ID after retrying.",
                )

            completeExplicitModelPickForChatStart(explicitModelPick)
            updateMessages(
                _uiState.value.messages + ChatMessage(
                    role = "user",
                    content = lastUserText,
                    timestamp = currentEpochSeconds(),
                    messageId = "local-${UUID.randomUUID()}",
                ),
            )

            streamCoordinator.start(streamId)
            return SlashCommandExecutionResult.Executed(message = null)
        } catch (error: Exception) {
            return SlashCommandExecutionResult.Unsupported(error.message ?: "Retry failed.")
        } finally {
            _uiState.update { it.copy(isStartingChat = false) }
        }
    }

    /** Port of `canRunConfigurationSlashCommand(_:)`. */
    private fun canRunConfigurationSlashCommand(actionDescription: String): Boolean {
        if (_uiState.value.isViewingCachedData) {
            _uiState.update {
                it.copy(composerConfigurationErrorMessage = "Reconnect to the server to $actionDescription.")
            }
            return false
        }

        if (_uiState.value.activeStreamId != null) {
            _uiState.update {
                it.copy(
                    composerConfigurationErrorMessage =
                        "Wait for the current response to finish before you $actionDescription.",
                )
            }
            return false
        }

        return true
    }

    /** Port of `modelOption(matching:)`. */
    private fun modelOption(matching: String): ModelCatalogOption? {
        val normalizedQuery = matching.lowercase()
        val options = _uiState.value.modelCatalogGroups.flatMap { it.slashAutocompleteModels }

        options.firstOrNull { it.id.lowercase() == normalizedQuery }?.let { return it }

        return options.firstOrNull {
            it.id.lowercase().contains(normalizedQuery) ||
                it.displayName.lowercase().contains(normalizedQuery)
        }
    }

    /** Port of `workspacePath(matching:)`. */
    private fun workspacePath(matching: String): String? {
        val normalizedQuery = matching.lowercase()
        val roots = _uiState.value.workspaceRoots.mapNotNull { root ->
            val path = root.path?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            path to root.name
        }

        roots.firstOrNull { it.first.lowercase() == normalizedQuery }?.let { return it.first }

        return roots.firstOrNull { (path, name) ->
            path.lowercase().contains(normalizedQuery) ||
                name?.lowercase()?.contains(normalizedQuery) == true
        }?.first
    }

    // MARK: - Local messages + queued slash messages

    /** Port of `appendLocalAssistantMessage(_:)`. */
    fun appendLocalAssistantMessage(text: String): String? =
        appendLocalMessage(text, role = "local_assistant", idPrefix = "local-slash")

    /** Port of `appendLocalNoticeMessage(_:)`. */
    fun appendLocalNoticeMessage(text: String): String? =
        appendLocalMessage(text, role = "local_notice", idPrefix = "local-notice")

    /** Port of `pinLocalNoticeMessage(_:)` — shown above the composer until the turn ends. */
    fun pinLocalNoticeMessage(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        _uiState.update { it.copy(pinnedLocalNotices = it.pinnedLocalNotices + trimmed) }
    }

    private fun appendLocalMessage(text: String, role: String, idPrefix: String): String? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null

        val messageId = "$idPrefix-${UUID.randomUUID()}"
        updateMessages(
            _uiState.value.messages + ChatMessage(
                role = role,
                content = trimmed,
                timestamp = currentEpochSeconds(),
                messageId = messageId,
            ),
        )
        bumpStreamingContentTrigger()
        return messageId
    }

    private fun updateLocalMessage(id: String, content: String) {
        val messages = _uiState.value.messages
        val index = messages.indexOfFirst { it.messageId == id }
        if (index < 0) return

        val updated = messages.toMutableList()
        updated[index] = updated[index].copy(content = content)
        updateMessages(updated)
        bumpStreamingContentTrigger()
    }

    private fun flushPinnedLocalNoticesToTranscript() {
        val notices = _uiState.value.pinnedLocalNotices
        if (notices.isEmpty()) return
        _uiState.update { it.copy(pinnedLocalNotices = emptyList()) }
        notices.forEach { appendLocalNoticeMessage(it) }
    }

    private fun enqueueQueuedSlashMessage(
        text: String,
        attachments: List<PendingAttachment>,
        atFront: Boolean = false,
        submittedComposerRevision: Int? = null,
    ): Int {
        val message = QueuedSlashMessage(
            text = text,
            attachments = attachments,
            submittedComposerRevision = submittedComposerRevision,
        )
        if (atFront) {
            queuedSlashMessages.add(0, message)
        } else {
            queuedSlashMessages.add(message)
        }
        _uiState.update { it.copy(queuedMessagesReceipt = "Queued, sends when this run finishes") }
        return queuedSlashMessages.size
    }

    /** Port of `drainQueuedSlashMessageIfIdle()` (incl. the issue-#202 no-retry-loop guard). */
    private fun drainQueuedSlashMessageIfIdle() {
        if (_uiState.value.activeStreamId != null ||
            _uiState.value.isStartingChat ||
            isDrainingQueuedSlashMessage ||
            queuedSlashMessages.isEmpty()
        ) {
            return
        }

        val next = queuedSlashMessages.removeAt(0)
        isDrainingQueuedSlashMessage = true

        viewModelScope.launch {
            val savedAttachments = _uiState.value.pendingAttachments
            attachmentCoordinator.replacePendingAttachments(next.attachments)
            val sent = sendMessage(next.text)
            if (!sent) {
                queuedSlashMessages.add(0, next)
            }
            attachmentCoordinator.replacePendingAttachments(savedAttachments)
            isDrainingQueuedSlashMessage = false
            // Only chain-drain after a successful send — a failed send waits for
            // the next natural trigger instead of tight-looping (issue #202).
            if (sent && _uiState.value.activeStreamId == null) {
                drainQueuedSlashMessageIfIdle()
            }
        }
    }

    /** Port of `clearTranscript()` (/clear). */
    fun clearTranscript() {
        resetPendingStreamingContentBuffers()
        toolCallAnchorMessageId = null
        reasoningAnchorMessageId = null
        _uiState.update {
            it.copy(
                messages = emptyList(),
                messagesOffset = 0,
                transcriptMessages = emptyList(),
                canLoadOlderMessages = false,
                completedToolCallGroups = emptyList(),
                completedReasoningGroups = emptyList(),
                liveToolCalls = emptyList(),
                liveReasoningText = "",
                pinnedLocalNotices = emptyList(),
                streamingAssistantMessageId = null,
                sendErrorMessage = null,
            )
        }
    }

    /** Port of `clearConversationRefusal` in `ChatViewModel.swift`. */
    val clearConversationRefusal: String?
        get() = when {
            _uiState.value.isViewingCachedData -> "Reconnect to the server to clear the conversation."
            isCliSession -> "Clearing the conversation is available for WebUI sessions only."
            _uiState.value.activeStreamId != null -> "Wait for the current response to finish before clearing the conversation."
            _uiState.value.isClearingConversation -> "Wait for the conversation to finish clearing."
            sessionId == null -> "The server did not provide a session ID."
            else -> null
        }

    fun dismissClearConfirmation() { _uiState.update { it.copy(pendingClearConfirmation = false) } }
    fun confirmClearSessionHistory() {
        dismissClearConfirmation()
        clearSessionHistory()
    }

    /** Port of `clearConversationFromSlashCommand`: caller confirms first; server history and offline copy both clear. */
    fun clearSessionHistory() {
        viewModelScope.launch {
            val result = clearConversationFromSlashCommand()
            if (result is SlashCommandExecutionResult.Unsupported) {
                _uiState.update { it.copy(sendErrorMessage = result.friendlyMessage) }
            } else if (_uiState.value.draft.trim() == "/clear") clearSubmittedComposer()
        }
    }

    private suspend fun clearConversationFromSlashCommand(): SlashCommandExecutionResult {
        clearConversationRefusal?.let { return SlashCommandExecutionResult.Unsupported(it) }
        val id = sessionId ?: return SlashCommandExecutionResult.Unsupported("The server did not provide a session ID.")
        val generation = sessionGeneration
        val server = _uiState.value.serverCacheNamespace
        _uiState.update { it.copy(isClearingConversation = true, sendErrorMessage = null) }
        return try {
            val response = api.clearSession(id)
            response.error?.let { return SlashCommandExecutionResult.Unsupported(it) }
            if (generation == sessionGeneration) {
                clearTranscript()
                applySessionTitle(response.session?.title ?: "Untitled Session")
            }
            withContext(ioDispatcher) { cacheStore.cacheMessages(emptyList(), server, id) }
            SlashCommandExecutionResult.Executed(null)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            SlashCommandExecutionResult.Unsupported(error.message ?: "Could not clear the conversation.")
        } finally {
            if (generation == sessionGeneration) _uiState.update { it.copy(isClearingConversation = false) }
        }
    }

    /** Port of `ChatView` draft synchronization; only durable files enter the record. */
    private fun persistComposer() {
        val key = draftKey ?: return
        val state = _uiState.value
        val rejection = rejectedSubmissions[key]
        if (rejection == null) {
            drafts.setContent(state.draft, state.draftQuotes, key)
        } else {
            val retained = rejection.retain(
                SubmittedComposerContent(text = state.draft, quotes = state.draftQuotes),
            )
            drafts.setContent(retained.text, retained.quotes, key)
        }
        val records = state.pendingAttachments.mapNotNull { pending -> pending.draftRecord() }
        // Keep records whose re-upload has not succeeded yet; a send/removal explicitly removes them.
        val seen = records.mapTo(HashSet()) { it.id }
        val retainedRecords = draftRecords.filter { seen.add(it.id) }
        // Continuation discovery is navigation-only. Until it resolves, the
        // rejected submission's files stay owned even as newer work is staged.
        val rejectedRecords = rejection?.attachments.orEmpty().filter { seen.add(it.id) }
        draftRecords = records + retainedRecords + rejectedRecords
        drafts.setAttachments(draftRecords, key)
        drafts.setSettings(ChatDraftSettings(currentModel, currentModelProvider, selectedReasoningEffort,
            requestProfileName, currentWorkspace), key)
    }

    private fun RejectedComposerSubmission.retain(
        current: SubmittedComposerContent,
    ): SubmittedComposerContent {
        if (current == lastRetainedContent) return current
        val recovered = SubmittedComposerContent(
            text = if (current.text.isEmpty() || current.text == submitted.text) {
                submitted.text
            } else {
                listOf(submitted.text, current.text).filter { it.isNotEmpty() }.joinToString("\n\n")
            },
            quotes = submitted.quotes + current.quotes.filter { it !in submitted.quotes },
        )
        lastRetainedContent = recovered
        return recovered
    }

    private fun PendingAttachment.draftRecord(): ChatDraftAttachment? = draftFileName?.let {
        ChatDraftAttachment(id, name, mime, size, isImage, it)
    }

    private fun applyDraft(draft: ChatDraft) {
        draftRecords = draft.attachments
        _uiState.update { it.copy(draft = draft.text, draftTokens = parseComposerTokens(draft.text), draftQuotes = draft.quotes) }
    }

    private suspend fun restoreDraftSettings(snapshot: ChatDraftSettings) {
        val restored = snapshot.normalized()
        if (restored.profileName != null && _uiState.value.profiles.none { it.normalizedName == restored.profileName }) return
        newChatProfileName = newChatProfileName ?: restored.profileName
        selectedProfileName = newChatProfileName ?: selectedProfileName
        currentModel = restored.modelId ?: currentModel
        currentModelProvider = restored.modelProviderId ?: currentModelProvider
        currentWorkspace = restored.workspacePath ?: currentWorkspace
        selectedReasoningEffort = restored.reasoningEffort ?: selectedReasoningEffort
        refreshComposerSelectionState()
    }

    private suspend fun restoreDraftAttachments(generation: Int) {
        for (record in draftRecords.toList()) {
            if (generation != sessionGeneration) return
            if (_uiState.value.pendingAttachments.any { it.id == record.id }) continue
            val file = record.file ?: continue
            val bytes = try { drafts.attachmentData(file) } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (ChatDraftAttachmentReadFailure.classify(error) == ChatDraftAttachmentReadFailure.UNRECOVERABLE) {
                    draftRecords = draftRecords.filterNot { it.id == record.id }
                    draftKey?.let { drafts.removeAttachmentReference(record.id, it) }
                }
                _uiState.update { it.copy(uploadAttachmentErrorMessage = "${record.name}: ${error.message}") }
                continue
            }
            val preview = attachmentPreview(bytes, record.name, record.mime,
                _uiState.value.serverCacheNamespace, drafts.attachmentFile(file))
            if (generation != sessionGeneration) return
            val uploaded = attachmentCoordinator.uploadStandaloneAttachment(bytes, record.name, preview) ?: continue
            if (generation != sessionGeneration) return
            val retained = uploaded.copy(id = record.id, draftFileName = file)
            attachmentLease.filesByAttachmentId[record.id] = file
            attachmentCoordinator.restorePendingAttachments(listOf(retained))
        }
    }

    /** Port of `ChatView.parkQueuedMessages`: leaving a running chat returns the queue to review, not auto-send. */
    private fun parkQueuedMessages() {
        if (queuedSlashMessages.isEmpty()) return
        val queued = queuedSlashMessages.toList()
        queuedSlashMessages.clear()
        val current = _uiState.value
        // A pending queue/interrupt still owns the unchanged composer text. Its
        // queued form replaces that text instead of being appended to itself.
        val queuedCurrentSubmission = queued.any {
            it.submittedComposerRevision != null && it.submittedComposerRevision == composerRevision
        }
        val retainedDraft = current.draft.takeUnless { queuedCurrentSubmission }.orEmpty()
        val retainedQuotes = current.draftQuotes.takeUnless { queuedCurrentSubmission }.orEmpty()
        val text = (queued.map { it.text } + retainedDraft).filter { it.isNotEmpty() }.joinToString("\n\n")
        attachmentCoordinator.restorePendingAttachments(queued.flatMap { it.attachments })
        // Parking is a composer mutation even though it was not a keystroke.
        // Any in-flight submission captured the prior revision and may not clear it.
        composerRevision += 1
        _uiState.update {
            it.copy(
                draft = text,
                draftTokens = parseComposerTokens(text),
                draftQuotes = retainedQuotes,
                queuedMessagesReceipt = null,
            )
        }
        persistComposer()
    }

    private suspend fun consumeSentAttachments(attachments: List<PendingAttachment>) {
        val ids = attachments.mapTo(HashSet()) { it.id }
        draftRecords = draftRecords.filterNot { it.id in ids }
        draftKey?.let { drafts.setAttachments(draftRecords, it) }
        for (attachment in attachments) {
            attachmentLease.slotIds.remove(attachment.id)
            attachmentLease.filesByAttachmentId.remove(attachment.id)
            attachment.draftFileName?.let { file ->
                attachmentLease.files.remove(file)
                drafts.deleteAttachmentIfUnreferenced(file)
            }
        }
    }

    /**
     * The host accepted [submission], so consume only the content and files it
     * carried from its captured draft context. This runs even when navigation
     * has made another composer current.
     */
    private suspend fun reconcileAcceptedSubmission(
        submission: ComposerSubmission,
        sourceKey: ChatDraftKey,
        attachments: List<PendingAttachment>,
    ) {
        if (draftKey == sourceKey &&
            sessionGeneration == submission.generation &&
            composerRevision == submission.revision
        ) {
            // Make stale lifecycle persistence observe the accepted ownership
            // before the durable flush below can suspend.
            _uiState.update { it.copy(draft = "", draftTokens = emptyList(), draftQuotes = emptyList()) }
        }
        val ids = if (attachments.isEmpty()) emptySet() else attachments.mapTo(HashSet()) { it.id }
        val consumedRecords = withContext(NonCancellable) {
            val consumed = drafts.consumeAcceptedSubmission(
                submittedText = submission.text,
                submittedQuotes = submission.quotes,
                submittedAttachmentIds = ids,
                key = sourceKey,
            )
            // The accepted content must not reappear after a process death.
            runCatching { drafts.flush() }
            consumed
        }
        rejectedSubmissions.remove(sourceKey)
        if (attachments.isEmpty()) return
        if (draftKey == sourceKey) {
            draftRecords = draftRecords.filterNot { it.id in ids }
            attachmentCoordinator.replacePendingAttachments(
                _uiState.value.pendingAttachments.filterNot { it.id in ids },
            )
        }

        val files = LinkedHashSet<String>()
        files += consumedRecords.mapNotNull { it.file }
        files += attachments.mapNotNull { it.draftFileName }
        for (attachment in attachments) {
            attachmentLease.slotIds.remove(attachment.id)
            attachmentLease.filesByAttachmentId.remove(attachment.id)
        }
        attachmentLease.files.removeAll(files)
        withContext(NonCancellable) {
            for (file in files) runCatching { drafts.deleteAttachmentIfUnreferenced(file) }
        }
    }

    /**
     * A rotation response definitively rejected the POST. Persist its complete
     * source-owned composer before the continuation GET, which is only a
     * navigation lookup and may be cancelled when the ViewModel is cleared.
     */
    private suspend fun retainRejectedSubmissionBeforeLookup(
        submission: ComposerSubmission,
        sourceKey: ChatDraftKey,
        attachments: List<ChatDraftAttachment>,
    ) {
        val rejected = rejectedSubmissions.getOrPut(sourceKey) {
            RejectedComposerSubmission(
                submitted = SubmittedComposerContent(submission.text, submission.quotes),
                attachments = attachments,
            )
        }
        withContext(NonCancellable) {
            val stored = drafts.draft(sourceKey) ?: ChatDraft()
            val recovered = rejected.retain(
                SubmittedComposerContent(stored.text, stored.quotes),
            )
            drafts.setContent(recovered.text, recovered.quotes, sourceKey)
            drafts.retainRejectedAttachments(rejected.attachments, sourceKey)
            // Do not begin navigation discovery until the rejected input has a
            // durable source draft.
            runCatching { drafts.flush() }
        }
    }

    private fun persistPartialStream() {
        streamCoordinatorSaveSnapshotIfNeeded()
        cacheCurrentMessages()
    }

    /** Port of the scene-active trigger; recovery is single-flight in ChatStreamCoordinator. */
    fun refreshOnForeground() {
        resumeForForeground()
        viewModelScope.launch {
            if (_uiState.value.activeStreamId == null) performLoadMessages()
            else if (!streamCoordinator.isConnectionSuspended) {
                _uiState.value.activeStreamId?.let { streamCoordinator.refreshTranscriptIfCompleted(it) }
            }
        }
    }

    /** Port of `resolveCompressionContinuation`: GET only, on the same server and Profile. */
    private suspend fun resolveCompressionContinuation(id: String, generation: Int, profile: String?) {
        if (ApiError.SessionRotation.of(id) == null || id == sessionId) return
        try {
            val detail = api.session(id, includeMessages = false, messageLimit = null).session ?: return
            if (generation != sessionGeneration || nonEmpty(detail.profile).orDefaultProfile() != nonEmpty(profile).orDefaultProfile()) return
            val target = detail.sessionId ?: return
            if (target == sessionId || ApiError.SessionRotation.of(target) == null) return
            compressionContinuationId = target
            _uiState.update { it.copy(compressionContinuation = SessionSummary(detail)) }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            if (generation == sessionGeneration) _uiState.update { it.copy(sendErrorMessage = error.message) }
        }
    }

    private fun String?.orDefaultProfile(): String = this ?: "default"

    /** Rejected input is restored before handoff; an existing destination draft is never overwritten. */
    private suspend fun retainAndOpenContinuation(
        submission: ComposerSubmission,
        source: ChatDraftKey?,
        generation: Int,
    ) {
        source ?: return
        if (source !in sealedSessions) return
        val rejected = rejectedSubmissions[source] ?: RejectedComposerSubmission(
            submitted = SubmittedComposerContent(submission.text, submission.quotes),
            attachments = submission.attachments.mapNotNull { it.draftRecord() },
        )
        val visible = _uiState.value.takeIf { draftKey == source }
        val stored = if (visible == null) drafts.draft(source) ?: ChatDraft() else null
        val recovered = rejected.retain(
            SubmittedComposerContent(
                text = visible?.draft ?: stored!!.text,
                quotes = visible?.draftQuotes ?: stored!!.quotes,
            ),
        )
        withContext(NonCancellable) {
            drafts.setContent(recovered.text, recovered.quotes, source)
            drafts.retainRejectedAttachments(rejected.attachments, source)
            runCatching { drafts.flush() }
        }
        if (rejectedSubmissions[source] === rejected) rejectedSubmissions.remove(source)
        if (generation != sessionGeneration || draftKey != source) return
        _uiState.update {
            it.copy(
                draft = recovered.text,
                draftTokens = parseComposerTokens(recovered.text),
                draftQuotes = recovered.quotes,
            )
        }
        persistComposer()
        val continuation = _uiState.value.compressionContinuation ?: return
        val target = ChatDraftKey(source.serverId, continuation.sessionId ?: return)
        if (drafts.draft(target)?.isEmpty == false || generation != sessionGeneration) return
        // No suspension between capturing the latest edits and moving the whole draft.
        persistComposer()
        drafts.moveDraft(source, target)
        _uiState.update { it.copy(draft = "", draftTokens = emptyList(), draftQuotes = emptyList(), pendingAttachments = emptyList()) }
        draftRecords = emptyList()
        switchToSession(target.sessionId, newChat = false)
    }

    override fun onCleared() {
        parkQueuedMessages()
        persistComposer()
        persistPartialStream()
        attachmentLease.key = null
        attachmentLease.files.clear()
        attachmentLease.filesByAttachmentId.clear()
        super.onCleared()
    }

    // MARK: - /btw side stream

    /**
     * Port of `startBtwStream(streamID:question:)`. The answer renders as one
     * `local_assistant` message updated in place. Uses the shared [sse]
     * transport; a /btw stream only runs while the main stream is idle (the
     * command guards on `activeStreamID == nil`), so its `Last-Event-ID`
     * bookkeeping cannot clobber a live chat reconnect cursor.
     */
    private fun startBtwStream(streamId: String, question: String) {
        activeBtwStreamId = streamId
        activeBtwQuestion = question
        activeBtwAnswer = ""
        activeBtwMessageId = appendLocalAssistantMessage(
            btwMessageText(question = question, answer = null, isLoading = true),
        )

        btwStreamJob = viewModelScope.launch {
            sse.connect(streamId).collect { event -> handleBtwStreamEvent(event) }
        }
    }

    /** Port of `handleBtwStreamEvent(_:)`. */
    private fun handleBtwStreamEvent(event: ChatStreamEvent) {
        when (event) {
            is ChatStreamEvent.Token -> {
                activeBtwAnswer += event.text
                updateActiveBtwMessage(isLoading = true)
            }

            is ChatStreamEvent.InterimAssistant -> {
                if (event.event.alreadyStreamed == true) return
                val text = event.event.text?.trim().orEmpty()
                if (text.isEmpty()) return
                activeBtwAnswer = if (activeBtwAnswer.trim().isEmpty()) {
                    text
                } else {
                    "$activeBtwAnswer\n\n$text"
                }
                updateActiveBtwMessage(isLoading = true)
            }

            is ChatStreamEvent.Done -> updateActiveBtwMessage(isLoading = false)

            is ChatStreamEvent.ApprovalPending, is ChatStreamEvent.ClarificationPending -> Unit

            ChatStreamEvent.StreamEnd, ChatStreamEvent.Cancelled -> finishBtwStream()

            is ChatStreamEvent.Error -> {
                activeBtwAnswer = "Error: ${event.message}"
                updateActiveBtwMessage(isLoading = false)
                finishBtwStream()
            }

            is ChatStreamEvent.TransportError -> {
                activeBtwAnswer = "Error: ${event.message}"
                updateActiveBtwMessage(isLoading = false)
                finishBtwStream()
            }

            ChatStreamEvent.Heartbeat, ChatStreamEvent.Ignored,
            is ChatStreamEvent.Reasoning, is ChatStreamEvent.ToolStarted, is ChatStreamEvent.ToolCompleted,
            is ChatStreamEvent.Title, is ChatStreamEvent.Metering, is ChatStreamEvent.PendingSteerLeftover,
            -> Unit
        }
    }

    private fun updateActiveBtwMessage(isLoading: Boolean) {
        val messageId = activeBtwMessageId ?: return
        val question = activeBtwQuestion ?: return
        updateLocalMessage(
            id = messageId,
            content = btwMessageText(question = question, answer = activeBtwAnswer, isLoading = isLoading),
        )
    }

    private fun finishBtwStream() {
        btwStreamJob?.cancel()
        btwStreamJob = null
        activeBtwStreamId = null
        activeBtwMessageId = null
        activeBtwQuestion = null
        activeBtwAnswer = ""
    }

    private fun stopBtwStream() = finishBtwStream()

    // MARK: - /background polling

    private fun stopBackgroundPolling(clearTrackedPrompts: Boolean) {
        backgroundPollJob?.cancel()
        backgroundPollJob = null
        if (clearTrackedPrompts) {
            backgroundPromptsByTaskId.clear()
        }
    }

    /** Port of `startBackgroundPollingIfNeeded(parentSessionID:)` — 3s cadence. */
    private fun startBackgroundPollingIfNeeded(parentSessionId: String) {
        if (backgroundPollJob != null) return

        backgroundPollJob = viewModelScope.launch {
            while (backgroundPromptsByTaskId.isNotEmpty()) {
                runCatching { api.backgroundStatus(parentSessionId) }
                    .onSuccess { handleBackgroundResults(it.results ?: emptyList()) }

                if (backgroundPromptsByTaskId.isEmpty()) break
                delay(pollingIntervals.backgroundMillis)
            }
            backgroundPollJob = null
        }
    }

    /** Port of `handleBackgroundResults(_:)`. */
    private fun handleBackgroundResults(results: List<BackgroundResult>) {
        for (result in results) {
            val tracked = result.taskId?.let(backgroundPromptsByTaskId::remove)
            val prompt = tracked
                ?: result.prompt?.takeIf { it.isNotEmpty() }
                ?: "Background task"

            appendLocalAssistantMessage(backgroundResultText(prompt = prompt, answer = result.answer))
        }
    }

    // MARK: - Composer configuration (port of ChatComposerConfigLoader + loadComposerConfiguration)

    private suspend fun loadComposerConfiguration() {
        if (isLoadingComposerConfiguration) {
            needsComposerConfigurationReload = true
            return
        }

        isLoadingComposerConfiguration = true
        _uiState.update { it.copy(isLoadingComposerConfiguration = true, composerConfigurationErrorMessage = null) }
        try {
            do {
                needsComposerConfigurationReload = false
                loadComposerConfigurationOnce()
            } while (needsComposerConfigurationReload)
        } finally {
            isLoadingComposerConfiguration = false
            _uiState.update { it.copy(isLoadingComposerConfiguration = false) }
        }
    }

    private suspend fun loadComposerConfigurationOnce() {
        var configurationError: Exception? = null

        try {
            val profilesResponse = api.profiles()
            var profileOptions = profilesResponse.profiles ?: emptyList()
            isSingleProfileMode = profilesResponse.singleProfileMode ?: false
            selectedProfileName = nonEmpty(newChatProfileName) ?: nonEmpty(currentProfile)
                ?: nonEmpty(profilesResponse.active)
                ?: profilesResponse.effectiveDefaultProfileName

            val sessionProfile = nonEmpty(currentProfile)
            if (sessionProfile != null && nonEmpty(profilesResponse.active) != sessionProfile) {
                val switchResponse = api.switchProfile(name = sessionProfile)
                profileOptions = switchResponse.profiles ?: profileOptions
                selectedProfileName = nonEmpty(switchResponse.active) ?: sessionProfile
                currentProfile = selectedProfileName

                if (currentWorkspace == null) {
                    currentWorkspace = nonEmpty(switchResponse.defaultWorkspace)
                }
                if (currentModel == null) {
                    currentModel = nonEmpty(switchResponse.defaultModel)
                }
            }

            val selectedProfile = profileOptions.firstOrNull {
                it.normalizedName == nonEmpty(selectedProfileName)
            }
            if (currentModel == null) {
                currentModel = nonEmpty(selectedProfile?.model)
            }

            val modelsResponse = api.models()
            val catalogGroups = modelsResponse.catalogGroups
            if (currentModel == null) {
                currentModel = modelsResponse.defaultModel
            }
            if (nonEmpty(currentModelProvider) == null) {
                currentModelProvider = nonEmpty(selectedProfile?.provider)
                    ?: uniqueProvider(currentModel, catalogGroups)
            }

            // Scoped to the resolved model/provider so the gating fields are
            // model-accurate (issue #18).
            val reasoningResponse = api.reasoning(
                model = nonEmpty(currentModel),
                provider = nonEmpty(currentModelProvider),
            )
            selectedReasoningEffort = reasoningResponse.effectiveEffort
            supportedReasoningEfforts = reasoningResponse.normalizedSupportedEfforts
            supportsReasoningEffort = reasoningResponse.supportsReasoningEffort

            val workspaceResponse = api.workspaces()
            val workspaceRoots = workspaceResponse.workspaces ?: emptyList()
            if (currentWorkspace == null) {
                currentWorkspace = workspaceResponse.last ?: workspaceRoots.mapNotNull { it.path }.firstOrNull()
            }

            _uiState.update {
                it.copy(
                    modelCatalogGroups = catalogGroups,
                    workspaceRoots = workspaceRoots,
                    workspaceSuggestions = workspaceRoots.mapNotNull { root -> root.path },
                    profiles = profileOptions,
                )
            }
        } catch (error: Exception) {
            configurationError = error
        }

        val commands = runCatching { api.commands().commands ?: emptyList() }.getOrDefault(emptyList())
        _uiState.update { it.copy(serverCommands = commands) }

        // Skill shortcuts feed the slash autocomplete; failures are non-fatal.
        runCatching { skillSuggestionsForSlashCommand() }

        refreshComposerSelectionState()
        configurationError?.let { error ->
            _uiState.update { it.copy(composerConfigurationErrorMessage = error.message) }
        }
    }

    /** Port of `selectComposerModel(_:)`. */
    private suspend fun selectComposerModel(option: ModelCatalogOption): Boolean {
        if (option.matchesSelection(modelID = currentModel, providerID = currentModelProvider)) return false

        if (_uiState.value.isViewingCachedData) {
            _uiState.update { it.copy(composerConfigurationErrorMessage = "Reconnect to the server to change models.") }
            return false
        }
        if (_uiState.value.activeStreamId != null) {
            _uiState.update {
                it.copy(
                    composerConfigurationErrorMessage =
                        "Wait for the current response to finish before changing models.",
                )
            }
            return false
        }

        val sessionId = sessionId
        if (sessionId == null) {
            // New-chat mode: no session to update yet — the pick applies to the
            // createSession/startChat that follows.
            currentModel = option.id
            currentModelProvider = option.providerID
            pendingExplicitModelPick = true
            refreshComposerSelectionState()
            refreshReasoningEffortGating()
            return true
        }

        _uiState.update { it.copy(composerConfigurationErrorMessage = null) }
        return try {
            val response = api.updateSession(
                id = sessionId,
                workspace = currentWorkspace,
                model = option.id,
                modelProvider = option.providerID,
            )

            currentModel = response.session?.model ?: option.id
            currentModelProvider = response.session?.modelProvider ?: option.providerID
            currentWorkspace = response.session?.workspace ?: currentWorkspace
            pendingExplicitModelPick = true
            refreshComposerSelectionState()
            // The new model's gating replaces the previous model's options (issue #18).
            refreshReasoningEffortGating()
            true
        } catch (error: Exception) {
            _uiState.update { it.copy(composerConfigurationErrorMessage = error.message) }
            false
        }
    }

    /** Port of `refreshReasoningEffortGating()` — stale responses dropped by token. */
    private suspend fun refreshReasoningEffortGating() {
        if (_uiState.value.isViewingCachedData) return

        reasoningGatingFetchToken += 1
        val token = reasoningGatingFetchToken

        val response = runCatching {
            api.reasoning(model = nonEmpty(currentModel), provider = nonEmpty(currentModelProvider))
        }.getOrNull()

        if (token != reasoningGatingFetchToken) return

        if (response == null) {
            supportedReasoningEfforts = null
            supportsReasoningEffort = null
            refreshComposerSelectionState()
            return
        }

        supportedReasoningEfforts = response.normalizedSupportedEfforts
        supportsReasoningEffort = response.supportsReasoningEffort

        val selected = nonEmpty(selectedReasoningEffort)?.lowercase()
        val supported = supportedReasoningEfforts
        if (selected != null && supported != null && selected !in supported) {
            nonEmpty(response.effectiveEffort)?.let { selectedReasoningEffort = it }
        }
        refreshComposerSelectionState()
    }

    /** Port of `selectWorkspacePath(_:)`. */
    private suspend fun selectWorkspacePath(path: String): Boolean {
        val workspace = path.trim()
        if (workspace.isEmpty() || workspace == currentWorkspace) return false

        if (_uiState.value.isViewingCachedData) {
            _uiState.update {
                it.copy(composerConfigurationErrorMessage = "Reconnect to the server to change workspace.")
            }
            return false
        }
        if (_uiState.value.activeStreamId != null) {
            _uiState.update {
                it.copy(
                    composerConfigurationErrorMessage =
                        "Wait for the current response to finish before changing workspace.",
                )
            }
            return false
        }

        val sessionId = sessionId
        if (sessionId == null) {
            currentWorkspace = workspace
            refreshComposerSelectionState()
            return true
        }

        val previousWorkspace = currentWorkspace
        currentWorkspace = workspace
        _uiState.update { it.copy(composerConfigurationErrorMessage = null) }
        return try {
            val response = api.updateSession(
                id = sessionId,
                workspace = workspace,
                model = currentModel,
                modelProvider = currentModelProvider,
            )

            currentWorkspace = response.session?.workspace ?: workspace
            currentModel = response.session?.model ?: currentModel
            currentModelProvider = response.session?.modelProvider ?: currentModelProvider
            refreshComposerSelectionState()
            true
        } catch (error: Exception) {
            currentWorkspace = previousWorkspace
            refreshComposerSelectionState()
            _uiState.update { it.copy(composerConfigurationErrorMessage = error.message) }
            false
        }
    }

    /** Port of `switchProfile(_:startNewSession:)`. */
    private suspend fun switchProfileInternal(profile: ProfileSummary, startNewSession: Boolean) {
        if (_uiState.value.isViewingCachedData) {
            _uiState.update {
                it.copy(composerConfigurationErrorMessage = "Reconnect to the server to change profiles.")
            }
            return
        }
        if (_uiState.value.activeStreamId != null) {
            _uiState.update {
                it.copy(
                    composerConfigurationErrorMessage =
                        "Wait for the current response to finish before changing profiles.",
                )
            }
            return
        }

        val profileName = profile.normalizedName
        if (profileName == null) {
            _uiState.update {
                it.copy(composerConfigurationErrorMessage = "The server did not provide a profile name.")
            }
            return
        }

        if (!startNewSession && isSelectedProfile(profile)) return

        _uiState.update { it.copy(composerConfigurationErrorMessage = null) }
        try {
            val response = api.switchProfile(name = profileName)
            _uiState.update { it.copy(profiles = response.profiles ?: it.profiles) }
            selectedProfileName = response.active ?: profileName
            currentProfile = selectedProfileName

            response.defaultWorkspace?.takeIf { it.isNotEmpty() }?.let { currentWorkspace = it }
            response.defaultModel?.takeIf { it.isNotEmpty() }?.let {
                currentModel = it
                currentModelProvider = nonEmpty(profile.provider)
            }
            pendingExplicitModelPick = false

            loadComposerConfiguration()

            if (!startNewSession) return

            val newSessionResponse = api.createSession(
                workspace = currentWorkspace,
                model = currentModel,
                modelProvider = requestModelProvider,
                profile = requestProfileName,
            )

            val session = newSessionResponse.session
            if (session == null) {
                _uiState.update {
                    it.copy(composerConfigurationErrorMessage = "The server did not return the new profile session.")
                }
                return
            }

            nonEmpty(session.sessionId)?.let { switchToSession(it, newChat = false) }
        } catch (error: Exception) {
            _uiState.update { it.copy(composerConfigurationErrorMessage = error.message) }
        }
    }

    private fun isSelectedProfile(profile: ProfileSummary): Boolean {
        val profileName = profile.normalizedName ?: return false
        return profileName == (nonEmpty(selectedProfileName) ?: nonEmpty(currentProfile))
    }

    /** Port of `selectReasoningEffort(_:)`. */
    private suspend fun selectReasoningEffortInternal(effort: String): Boolean {
        val selectedEffort = effort.trim()
        if (selectedEffort.isEmpty() || selectedEffort == selectedReasoningEffort) return false

        if (_uiState.value.isViewingCachedData) {
            _uiState.update {
                it.copy(composerConfigurationErrorMessage = "Reconnect to the server to change reasoning.")
            }
            return false
        }
        if (_uiState.value.activeStreamId != null) {
            _uiState.update {
                it.copy(
                    composerConfigurationErrorMessage =
                        "Wait for the current response to finish before changing reasoning.",
                )
            }
            return false
        }

        _uiState.update { it.copy(composerConfigurationErrorMessage = null) }
        return try {
            val id = sessionId ?: return false
            val response = api.saveReasoningEffort(selectedEffort, sessionId = id)
            selectedReasoningEffort = response.effectiveEffort ?: selectedEffort
            refreshComposerSelectionState()
            true
        } catch (error: Exception) {
            _uiState.update { it.copy(composerConfigurationErrorMessage = error.message) }
            false
        }
    }

    private val requestProfileName: String?
        get() = nonEmpty(selectedProfileName) ?: nonEmpty(currentProfile)

    private val requestModelProvider: String?
        get() = nonEmpty(currentModelProvider)

    private fun explicitModelPickForChatStart(): Boolean =
        pendingExplicitModelPick && nonEmpty(currentModel) != null

    private fun completeExplicitModelPickForChatStart(explicitModelPick: Boolean) {
        if (explicitModelPick) {
            pendingExplicitModelPick = false
        }
    }

    /** Mirrors the composer-facing selection fields into [ChatUiState]. */
    private fun refreshComposerSelectionState() {
        _sessionReasoningEffort.value = selectedReasoningEffort
        _uiState.update {
            it.copy(
                selectedModelId = currentModel,
                selectedModelProviderId = currentModelProvider,
                selectedModelTitle = selectedModelTitle(),
                selectedWorkspacePath = currentWorkspace,
                selectedProfileTitle = selectedProfileTitle(),
                reasoningEffort = selectedReasoningEffort,
                supportedReasoningEfforts = supportedReasoningEfforts ?: emptyList(),
                showsReasoningEffortControl = ReasoningEffortOption.showsEffortControl(
                    supportsReasoningEffort = supportsReasoningEffort,
                    supportedEfforts = supportedReasoningEfforts,
                ),
            )
        }
        persistComposer()
    }

    /** Port of the `selectedModelTitle` computed property. */
    private fun selectedModelTitle(): String {
        val model = currentModel?.takeIf { it.isNotEmpty() } ?: return "Model"

        val catalogName = _uiState.value.modelCatalogGroups
            .flatMap { it.models }
            .firstMatchingSelection(modelID = model, providerID = currentModelProvider)
            ?.displayName

        return catalogName ?: compactModelTitle(model)
    }

    /** Port of the `selectedProfileTitle` computed property. */
    private fun selectedProfileTitle(): String {
        val profileName = selectedProfileName ?: currentProfile
        if (profileName.isNullOrEmpty()) return "Profile"

        _uiState.value.profiles.firstOrNull { it.name == profileName }?.let { return it.displayName }

        return if (profileName == "default") "Default" else profileName
    }

    // MARK: - Streaming content (tokens/reasoning buffered + 16ms coalesced flush)

    /**
     * One coalesced flush tick. The iOS word-by-word display pacing
     * (`StreamingWordDrain`) is intentionally simplified to flush-everything —
     * token accumulation and dedup semantics are unchanged (buffering happens
     * at append time, mutation only here).
     */
    private fun scheduleStreamingContentFlush() {
        if (pendingStreamingContentFlushJob != null) return

        val expectedSessionId = sessionId
        pendingStreamingContentFlushJob = viewModelScope.launch {
            delay(STREAMING_CONTENT_COALESCING_MILLIS)
            pendingStreamingContentFlushJob = null
            if (sessionId != expectedSessionId) return@launch

            var didMutate = false
            if (flushAssistantTokens()) didMutate = true
            if (flushReasoningChunks()) didMutate = true
            if (didMutate) bumpStreamingContentTrigger()
        }
    }

    private fun cancelPendingStreamingContentFlush() {
        pendingStreamingContentFlushJob?.cancel()
        pendingStreamingContentFlushJob = null
    }

    private fun resetPendingStreamingContentBuffers() {
        cancelPendingStreamingContentFlush()
        pendingAssistantTokenChunks.clear()
        pendingReasoningChunks.clear()
        // Chunks are deduplicated at append time, so the replay matched-prefix
        // counters can reference unflushed content; dropping the buffers makes
        // them stale. Reset only the counters — replay dedup itself stays armed.
        activeStreamReplayMatchedPrefixLength = 0
        activeStreamReplayMatchedReasoningLength = 0
    }

    /** Port of `flushPendingStreamingContent()` — completion paths bypass coalescing. */
    fun flushPendingStreamingContent() {
        cancelPendingStreamingContentFlush()

        var didMutate = false
        if (flushAssistantTokens()) didMutate = true
        if (flushReasoningChunks()) didMutate = true
        if (didMutate) bumpStreamingContentTrigger()
    }

    /** Port of `ensureStreamingAssistantMessage()`. */
    private fun ensureStreamingAssistantMessage(): String {
        _uiState.value.streamingAssistantMessageId?.let { return it }

        val messageId = "stream-${UUID.randomUUID()}"
        _uiState.update { it.copy(streamingAssistantMessageId = messageId) }
        updateMessages(
            _uiState.value.messages + ChatMessage(
                role = "assistant",
                content = "",
                timestamp = currentEpochSeconds(),
                messageId = messageId,
            ),
        )
        return messageId
    }

    /** Port of `appendAssistantToken(_:)` — append-time dedup, coalesced mutation. */
    private fun appendAssistantToken(token: String): Boolean {
        if (token.isEmpty()) return false

        val messageId = ensureStreamingAssistantMessage()
        val flushedContent = _uiState.value.messages
            .firstOrNull { it.messageId == messageId }?.content.orEmpty()
        val effectiveContent = flushedContent + pendingAssistantTokenChunks.joinToString("")
        val remainder = deduplicatedReplayToken(token, effectiveContent)
        if (remainder.isEmpty()) return false

        pendingAssistantTokenChunks.add(remainder)
        scheduleStreamingContentFlush()
        return true
    }

    private fun flushAssistantTokens(): Boolean {
        if (pendingAssistantTokenChunks.isEmpty()) return false

        // Chunks were deduplicated at append time, so flushing is pure concatenation.
        val appendedContent = pendingAssistantTokenChunks.joinToString("")
        pendingAssistantTokenChunks.clear()

        val messageId = ensureStreamingAssistantMessage()
        if (_uiState.value.liveReasoningText.isNotEmpty() && reasoningAnchorMessageId == null) {
            reasoningAnchorMessageId = messageId
        }
        if (_uiState.value.liveToolCalls.isNotEmpty() && toolCallAnchorMessageId == null) {
            toolCallAnchorMessageId = messageId
        }

        val messages = _uiState.value.messages
        val index = messages.indexOfFirst { it.messageId == messageId }
        if (index >= 0) {
            val existing = messages[index]
            val updated = messages.toMutableList()
            updated[index] = existing.copy(content = existing.content.orEmpty() + appendedContent)
            updateMessages(updated)
        } else {
            updateMessages(
                messages + ChatMessage(
                    role = "assistant",
                    content = appendedContent,
                    timestamp = currentEpochSeconds(),
                    messageId = messageId,
                ),
            )
        }
        return true
    }

    /** Port of `appendReasoning(_:)`. */
    private fun appendReasoning(text: String): Boolean {
        if (text.isEmpty()) return false

        ensureStreamingAssistantMessage()
        val effectiveContent = _uiState.value.liveReasoningText + pendingReasoningChunks.joinToString("")
        val (remainder, updatedMatchedLength) = deduplicatedReplayText(
            text,
            existingContent = effectiveContent,
            matchedPrefixLength = activeStreamReplayMatchedReasoningLength,
        )
        activeStreamReplayMatchedReasoningLength = updatedMatchedLength
        if (remainder.isEmpty()) return false

        pendingReasoningChunks.add(remainder)
        scheduleStreamingContentFlush()
        return true
    }

    private fun flushReasoningChunks(): Boolean {
        if (pendingReasoningChunks.isEmpty()) return false

        val appendedText = pendingReasoningChunks.joinToString("")
        pendingReasoningChunks.clear()

        val messageId = ensureStreamingAssistantMessage()
        if (reasoningAnchorMessageId == null) {
            reasoningAnchorMessageId = messageId
        }

        _uiState.update { it.copy(liveReasoningText = it.liveReasoningText + appendedText) }
        return true
    }

    /** Port of `appendInterimAssistant(_:)`. */
    private fun appendInterimAssistant(payload: InterimAssistantStreamEvent): Boolean {
        if (payload.alreadyStreamed == true) return false

        val text = payload.text?.trim().orEmpty()
        if (text.isEmpty()) return false

        flushPendingStreamingContent()

        val streamingAssistantMessageId = _uiState.value.streamingAssistantMessageId
        val messages = _uiState.value.messages
        val index = streamingAssistantMessageId?.let { id -> messages.indexOfFirst { it.messageId == id } } ?: -1
        if (streamingAssistantMessageId != null && index >= 0) {
            val existing = messages[index]
            val currentContent = existing.content.orEmpty()
            val (textToAppend, updatedMatchedLength) = deduplicatedReplayText(
                text,
                existingContent = currentContent,
                matchedPrefixLength = activeStreamReplayMatchedInterimLength,
            )
            activeStreamReplayMatchedInterimLength = updatedMatchedLength
            if (textToAppend.isEmpty()) return false

            val shouldAppendReplaySuffixDirectly = streamCoordinator.isReplayConnection && textToAppend != text
            val shouldUseSeparator = currentContent.trim().isNotEmpty() && !shouldAppendReplaySuffixDirectly
            val separator = if (shouldUseSeparator) "\n\n" else ""
            val updated = messages.toMutableList()
            updated[index] = existing.copy(content = currentContent + separator + textToAppend)
            updateMessages(updated)
            bumpStreamingContentTrigger()
            return true
        }

        return appendAssistantToken(text)
    }

    // MARK: - Live tool calls (incl. replay dedup)

    /** Port of `appendToolCall(_:)`. */
    private fun appendToolCall(payload: ToolStreamEvent): Boolean {
        val messageId = ensureStreamingAssistantMessage()
        if (toolCallAnchorMessageId == null) {
            toolCallAnchorMessageId = messageId
        }

        val duplicateReplayIndex = duplicateReplayToolStartIndex(payload)
        if (duplicateReplayIndex != null) {
            activeStreamReplayPendingToolMatchIndex = duplicateReplayIndex
            return false
        }

        _uiState.update {
            it.copy(
                liveToolCalls = it.liveToolCalls + ToolCall(
                    id = payload.stableId ?: "live-tool-${UUID.randomUUID()}",
                    name = payload.name,
                    preview = payload.preview,
                    args = payload.args?.mapValues { entry -> JSONValue(entry.value) },
                ),
            )
        }
        bumpStreamingContentTrigger()
        return true
    }

    /** Port of `completeToolCall(_:)`. */
    private fun completeToolCall(payload: ToolStreamEvent): Boolean {
        val messageId = ensureStreamingAssistantMessage()
        if (toolCallAnchorMessageId == null) {
            toolCallAnchorMessageId = messageId
        }

        val liveToolCalls = _uiState.value.liveToolCalls
        val duplicateReplayIndex = duplicateReplayToolCompletionIndex(payload)
        if (duplicateReplayIndex != null) {
            val wasAlreadyCompleted = liveToolCalls[duplicateReplayIndex].isCompleted
            activeStreamReplayToolMatchIndex = duplicateReplayIndex + 1
            activeStreamReplayPendingToolMatchIndex = null

            if (wasAlreadyCompleted) return false

            val updated = liveToolCalls.toMutableList()
            updated[duplicateReplayIndex] = applyingCompletionPayload(updated[duplicateReplayIndex], payload)
            _uiState.update { it.copy(liveToolCalls = updated) }
            bumpStreamingContentTrigger()
            return true
        }

        activeStreamReplayPendingToolMatchIndex = null

        val index = liveToolCallCompletionIndex(payload)
        if (index == null) {
            _uiState.update {
                it.copy(
                    liveToolCalls = it.liveToolCalls + ToolCall(
                        id = payload.stableId ?: "live-tool-${UUID.randomUUID()}",
                        name = payload.name,
                        preview = payload.preview,
                        args = payload.args?.mapValues { entry -> JSONValue(entry.value) },
                        duration = payload.duration,
                        isError = payload.isError,
                        isCompleted = true,
                    ),
                )
            }
            bumpStreamingContentTrigger()
            return true
        }

        val updated = liveToolCalls.toMutableList()
        updated[index] = applyingCompletionPayload(updated[index], payload)
        _uiState.update { it.copy(liveToolCalls = updated) }
        bumpStreamingContentTrigger()
        return true
    }

    private fun duplicateReplayToolStartIndex(payload: ToolStreamEvent): Int? {
        if (!streamCoordinator.isReplayConnection) return null

        stableReplayToolIndex(payload)?.let { return it }

        val liveToolCalls = _uiState.value.liveToolCalls
        if (activeStreamReplayToolMatchIndex >= liveToolCalls.size) return null

        val index = activeStreamReplayToolMatchIndex
        return if (matchesReplayToolIdentity(liveToolCalls[index], payload)) index else null
    }

    private fun duplicateReplayToolCompletionIndex(payload: ToolStreamEvent): Int? {
        if (!streamCoordinator.isReplayConnection) return null

        stableReplayToolIndex(payload)?.let { return it }

        val liveToolCalls = _uiState.value.liveToolCalls
        val pendingIndex = activeStreamReplayPendingToolMatchIndex
        if (pendingIndex != null &&
            pendingIndex < liveToolCalls.size &&
            matchesReplayToolIdentity(liveToolCalls[pendingIndex], payload)
        ) {
            return pendingIndex
        }

        if (activeStreamReplayToolMatchIndex >= liveToolCalls.size) return null

        val index = activeStreamReplayToolMatchIndex
        if (!liveToolCalls[index].isCompleted || !matchesReplayToolIdentity(liveToolCalls[index], payload)) {
            return null
        }

        return index
    }

    private fun stableReplayToolIndex(payload: ToolStreamEvent): Int? {
        val stableId = nonEmptyReplayMatchText(payload.stableId) ?: return null
        val index = _uiState.value.liveToolCalls.indexOfFirst { nonEmptyStableToolId(it.id) == stableId }
        return index.takeIf { it >= 0 }
    }

    private fun liveToolCallCompletionIndex(payload: ToolStreamEvent): Int? {
        val liveToolCalls = _uiState.value.liveToolCalls
        val stableId = nonEmptyReplayMatchText(payload.stableId)
        if (stableId != null) {
            val stableIndex = liveToolCalls.indexOfLast { !it.isCompleted && nonEmptyStableToolId(it.id) == stableId }
            if (stableIndex >= 0) return stableIndex
        }

        val index = liveToolCalls.indexOfLast {
            !it.isCompleted && (payload.name == null || it.name == payload.name)
        }
        return index.takeIf { it >= 0 }
    }

    /** Port of the private `ToolCall.matchesReplayToolIdentity(_:)` extension. */
    private fun matchesReplayToolIdentity(toolCall: ToolCall, payload: ToolStreamEvent): Boolean {
        val payloadStableId = nonEmptyReplayMatchText(payload.stableId)
        val stableId = nonEmptyStableToolId(toolCall.id)
        if (payloadStableId != null && stableId != null) {
            return stableId == payloadStableId
        }

        var didCompareStableField = false

        val payloadName = payload.name?.let(::nonEmptyReplayMatchText)
        if (payloadName != null) {
            didCompareStableField = true
            if (toolCall.name?.let(::nonEmptyReplayMatchText) != payloadName) return false
        }

        val payloadArgs = payload.args
        if (payloadArgs != null) {
            didCompareStableField = true
            if (toolCall.args != payloadArgs.mapValues { JSONValue(it.value) }) return false
        }

        if (didCompareStableField) return true

        val payloadPreview = payload.preview?.let(::nonEmptyReplayMatchText) ?: return false
        val preview = toolCall.preview?.let(::nonEmptyReplayMatchText) ?: return false
        return preview == payloadPreview
    }

    /** Port of `ToolCall.applyingCompletionPayload(_:)`. */
    private fun applyingCompletionPayload(toolCall: ToolCall, payload: ToolStreamEvent): ToolCall = ToolCall(
        id = if (nonEmptyStableToolId(toolCall.id) == null) payload.stableId ?: toolCall.id else toolCall.id,
        name = payload.name ?: toolCall.name,
        preview = payload.preview ?: toolCall.preview,
        args = payload.args?.mapValues { JSONValue(it.value) } ?: toolCall.args,
        duration = payload.duration,
        isError = payload.isError,
        isCompleted = true,
        startedAt = toolCall.startedAt,
    )

    // MARK: - Archival + Done handling

    /** Port of `archiveLiveToolCallsIfNeeded()`. */
    private fun archiveLiveToolCallsIfNeeded() {
        val liveToolCalls = _uiState.value.liveToolCalls
        if (liveToolCalls.isEmpty()) return

        setCompletedToolCallGroups(
            _uiState.value.completedToolCallGroups + ToolCallGroup(
                anchorMessageID = toolCallAnchorMessageId,
                toolCalls = liveToolCalls,
            ),
        )
    }

    /** Port of `archiveLiveReasoningIfNeeded()`. */
    private fun archiveLiveReasoningIfNeeded() {
        val liveReasoningText = _uiState.value.liveReasoningText
        if (liveReasoningText.trim().isEmpty()) return

        _uiState.update {
            it.copy(
                completedReasoningGroups = it.completedReasoningGroups + ReasoningGroup(
                    anchorMessageID = reasoningAnchorMessageId,
                    text = liveReasoningText,
                ),
            )
        }
    }

    private fun setCompletedToolCallGroups(groups: List<ToolCallGroup>) {
        if (_uiState.value.completedToolCallGroups == groups) return
        _uiState.update { it.copy(completedToolCallGroups = groups) }
    }

    /** Port of the `streamCoordinatorApplyDone(_:)` delegate body. */
    private fun applyDone(payload: DoneStreamEvent): Boolean {
        flushPendingStreamingContent()
        val currentStreamingAssistantId = _uiState.value.streamingAssistantMessageId
        val hasCompletedTranscript = payload.session?.messages?.isNotEmpty() == true

        payload.session?.let { applyCompletedStreamSession(it) }
        payload.usage?.let { usage -> _uiState.update { it.copy(contextWindow = usage) } }

        val finalTokensPerSecond = payload.usage?.tokensPerSecond
        if (finalTokensPerSecond != null &&
            finalTokensPerSecond.isFinite() &&
            finalTokensPerSecond > 0 &&
            currentStreamingAssistantId != null
        ) {
            val messages = _uiState.value.messages
            val messagesOffset = _uiState.value.messagesOffset
            var index = messages.indexOfFirst { it.messageId == currentStreamingAssistantId }
            if (index < 0) {
                val anchorId = TranscriptTurnClassifier
                    .currentTurnAssistantAnchorIDs(messages, messagesOffset)
                    .lastOrNull()
                if (anchorId != null) {
                    index = messages.indices.firstOrNull { i ->
                        TranscriptTurnClassifier.anchorID(messages[i], i, messagesOffset) == anchorId
                    } ?: -1
                }
            }
            if (index < 0) return hasCompletedTranscript

            val updated = messages.toMutableList()
            updated[index] = updated[index].copy(turnTps = finalTokensPerSecond)
            updateMessages(updated)
        }

        return hasCompletedTranscript
    }

    /** Port of `applyCompletedStreamSession(_:)`. */
    private fun applyCompletedStreamSession(completedSession: SessionDetail) {
        val completedSessionId = completedSession.sessionId
        if (completedSessionId != null && sessionId != null && completedSessionId != sessionId) return

        var didApplyCompletedTranscript = false
        val completedMessages = completedSession.messages
        if (!completedMessages.isNullOrEmpty()) {
            val previousMessages = _uiState.value.messages
            val previousMessagesOffset = _uiState.value.messagesOffset
            val reloadedMessages = mergingLoadedMessages(completedMessages, previousMessages)
            applyReloadedMessages(reloadedMessages, completedSession, previousMessages, previousMessagesOffset)
            didApplyCompletedTranscript = true
        }

        completedSession.title?.let(::applySessionTitle)
        applySessionIdentity(completedSession)
        _uiState.update { it.copy(contextWindow = contextWindowSnapshot(completedSession)) }

        if (didApplyCompletedTranscript || completedSession.toolCalls != null) {
            val messages = _uiState.value.messages
            val messagesOffset = _uiState.value.messagesOffset
            val rebuiltToolCallGroups = ToolCallGroup.groups(
                persistedToolCalls = completedSession.toolCalls ?: emptyList(),
                messages = messages,
                messageOffset = messagesOffset,
            )
            val liveToolCalls = _uiState.value.liveToolCalls
            if (liveToolCalls.isNotEmpty()) {
                val fallbackAnchorMessageId = currentTurnToolCallFallbackAnchorMessageId()
                setCompletedToolCallGroups(
                    ToolCallGroup.coalescingByAssistantTurn(
                        ToolCallGroup.merging(
                            primaryGroups = rebuiltToolCallGroups,
                            fallbackGroups = listOf(
                                ToolCallGroup(
                                    id = "completed-live-tools-${fallbackAnchorMessageId ?: "unanchored"}",
                                    anchorMessageID = fallbackAnchorMessageId,
                                    toolCalls = liveToolCalls,
                                ),
                            ),
                        ),
                        messages,
                        messagesOffset,
                    ),
                )
            } else {
                setCompletedToolCallGroups(rebuiltToolCallGroups)
            }
            _uiState.update { it.copy(liveToolCalls = emptyList()) }
        }

        if (didApplyCompletedTranscript) {
            _uiState.update { it.copy(completedReasoningGroups = emptyList(), liveReasoningText = "") }
            toolCallAnchorMessageId = null
            reasoningAnchorMessageId = null
            bumpStreamingContentTrigger()
        }
        refreshComposerSelectionState()
    }

    /** Port of `currentTurnToolCallFallbackAnchorMessageID()`. */
    private fun currentTurnToolCallFallbackAnchorMessageId(): String? {
        val messages = _uiState.value.messages
        val messagesOffset = _uiState.value.messagesOffset

        toolCallAnchorMessageId?.let { anchor ->
            val anchored = messages.withIndex().any { (index, message) ->
                TranscriptTurnClassifier.anchorID(message, index, messagesOffset) == anchor
            }
            if (anchored) return anchor
        }

        TranscriptTurnClassifier.currentTurnAssistantAnchorIDs(messages, messagesOffset)
            .firstOrNull()?.let { return it }

        val index = messages.indexOfLast { it.role == "assistant" }
        if (index < 0) return null
        return TranscriptTurnClassifier.anchorID(messages[index], index, messagesOffset)
    }

    /** Port of `updateTitle(_:)` — ignores mismatched session ids. */
    private fun updateTitle(payload: TitleStreamEvent): Boolean {
        if (payload.sessionId != null && payload.sessionId != sessionId) return false

        val title = payload.title ?: return false
        applySessionTitle(title)
        return true
    }

    /** Port of `refreshCompletedResponseTitleIfNeeded()`. */
    private fun refreshCompletedResponseTitleIfNeeded() {
        if (isRefreshingCompletedResponseTitle) return
        val sessionId = sessionId ?: return

        isRefreshingCompletedResponseTitle = true
        viewModelScope.launch {
            try {
                runCatching { api.session(id = sessionId, includeMessages = false, messageLimit = null) }
                    .getOrNull()?.session?.title?.let(::applySessionTitle)
            } finally {
                isRefreshingCompletedResponseTitle = false
            }
        }
    }

    private fun applySessionTitle(title: String) {
        _uiState.update { it.copy(displayTitle = displayTitle(title)) }
    }

    private fun applySessionIdentity(session: SessionDetail) {
        if (session.workspace != null && session.workspace != currentWorkspace) composerFileListings.clear()
        val parentId = ForkOrigin.parentSessionId(session.sessionSource, session.parentSessionId)
        _uiState.update { it.copy(forkOrigin = parentId?.let { id -> ForkOrigin(id, null) }) }
        currentWorkspace = session.workspace ?: currentWorkspace
        currentModel = session.model ?: currentModel
        currentModelProvider = session.modelProvider ?: currentModelProvider
        currentProfile = session.profile ?: currentProfile
        session.isCliSession?.let { isCliSession = it }
        _uiState.update { it.copy(isReadOnly = sessionDetailIsReadOnly(session)) }
        refreshComposerSelectionState()
    }

    // MARK: - Replay dedup (port of deduplicatedReplayToken/Text)

    private fun deduplicatedReplayToken(token: String, existingContent: String): String {
        if (!streamCoordinator.isReplayConnection || existingContent.isEmpty()) {
            resetActiveStreamReplayTokenState()
            return token
        }

        val matchedPrefixLength = minOf(activeStreamReplayMatchedPrefixLength, existingContent.length)
        val expectedReplayRemainder = existingContent.substring(matchedPrefixLength)
        if (expectedReplayRemainder.startsWith(token)) {
            activeStreamReplayMatchedPrefixLength = matchedPrefixLength + token.length
            if (activeStreamReplayMatchedPrefixLength >= existingContent.length) {
                resetActiveStreamReplayTokenState()
            }
            return ""
        }

        if (token.startsWith(expectedReplayRemainder)) {
            resetActiveStreamReplayTokenState()
            return token.substring(expectedReplayRemainder.length)
        }

        if (existingContent.endsWith(token) || existingContent.startsWith(token)) {
            resetActiveStreamReplayTokenState()
            return ""
        }

        if (token.startsWith(existingContent)) {
            resetActiveStreamReplayTokenState()
            return token.substring(existingContent.length)
        }

        val maximumOverlap = minOf(existingContent.length, token.length)
        if (maximumOverlap <= 0) {
            resetActiveStreamReplayTokenState()
            return token
        }

        for (overlapLength in maximumOverlap downTo 1) {
            if (existingContent.regionMatches(
                    existingContent.length - overlapLength,
                    token,
                    0,
                    overlapLength,
                )
            ) {
                resetActiveStreamReplayTokenState()
                return token.substring(overlapLength)
            }
        }

        resetActiveStreamReplayTokenState()
        return token
    }

    /** Returns (remainder, updated matchedPrefixLength). */
    private fun deduplicatedReplayText(
        text: String,
        existingContent: String,
        matchedPrefixLength: Int,
    ): Pair<String, Int> {
        if (!streamCoordinator.isReplayConnection || existingContent.isEmpty()) {
            return text to 0
        }

        val matchedLength = minOf(matchedPrefixLength, existingContent.length)
        val expectedReplayRemainder = existingContent.substring(matchedLength)
        if (expectedReplayRemainder.startsWith(text)) {
            var updated = matchedLength + text.length
            if (updated >= existingContent.length) {
                updated = 0
            }
            return "" to updated
        }

        if (text.startsWith(expectedReplayRemainder)) {
            return text.substring(expectedReplayRemainder.length) to 0
        }

        if (existingContent.endsWith(text) || existingContent.startsWith(text)) {
            return "" to 0
        }

        if (text.startsWith(existingContent)) {
            return text.substring(existingContent.length) to 0
        }

        val maximumOverlap = minOf(existingContent.length, text.length)
        if (maximumOverlap <= 0) {
            return text to 0
        }

        for (overlapLength in maximumOverlap downTo 1) {
            if (existingContent.regionMatches(existingContent.length - overlapLength, text, 0, overlapLength)) {
                return text.substring(overlapLength) to 0
            }
        }

        return text to 0
    }

    private fun resetActiveStreamReplayTokenState() {
        streamCoordinator.clearReplayConnection()
        activeStreamReplayMatchedPrefixLength = 0
    }

    private fun resetReplayDedupState() {
        activeStreamReplayMatchedPrefixLength = 0
        activeStreamReplayMatchedInterimLength = 0
        activeStreamReplayMatchedReasoningLength = 0
        activeStreamReplayToolMatchIndex = 0
        activeStreamReplayPendingToolMatchIndex = null
    }

    // MARK: - Transcript state helpers

    private fun updateMessages(
        messages: List<ChatMessage>,
        messagesOffset: Int = _uiState.value.messagesOffset,
        canLoadOlder: Boolean? = null,
    ) {
        _uiState.update {
            it.copy(
                messages = messages,
                messagesOffset = messagesOffset,
                transcriptMessages = transcriptMessages(messages, messagesOffset),
                canLoadOlderMessages = canLoadOlder ?: it.canLoadOlderMessages,
            )
        }
    }

    /** Port of `applyReloadedMessages(_:from:previousMessages:previousMessagesOffset:)`. */
    private fun applyReloadedMessages(
        reloadedMessages: List<ChatMessage>,
        session: SessionDetail?,
        previousMessages: List<ChatMessage>,
        previousMessagesOffset: Int,
    ) {
        val reloadedMessagesOffset = resolvedMessagesOffset(session, reloadedMessages.size)

        val expandedMessages = mergingReloadedMessages(
            reloadedMessages,
            currentMessages = previousMessages,
            currentMessagesOffset = previousMessagesOffset,
            reloadedMessagesOffset = reloadedMessagesOffset,
        )
        if (expandedMessages != null) {
            updateMessages(expandedMessages, previousMessagesOffset, canLoadOlder = previousMessagesOffset > 0)
            return
        }

        updateMessages(reloadedMessages, reloadedMessagesOffset)
        updateOlderMessagePagination(session, reloadedMessages.size)
    }

    /** Port of `updateOlderMessagePagination(from:loadedMessageCount:)`. */
    private fun updateOlderMessagePagination(session: SessionDetail?, loadedMessageCount: Int) {
        val resolvedOffset = resolvedMessagesOffset(session, loadedMessageCount)
        _uiState.update {
            it.copy(
                messagesOffset = resolvedOffset,
                transcriptMessages = transcriptMessages(it.messages, resolvedOffset),
                canLoadOlderMessages = resolvedOffset > 0 || session?.messagesTruncated == true,
            )
        }
    }

    private fun bumpStreamingContentTrigger() {
        _uiState.update { it.copy(streamingContentTrigger = it.streamingContentTrigger + 1) }
    }

    private fun contextWindowSnapshot(session: SessionDetail?): ContextWindowSnapshot? {
        session ?: return null
        return ContextWindowSnapshot(
            contextLength = session.contextLength,
            thresholdTokens = session.thresholdTokens,
            lastPromptTokens = session.lastPromptTokens,
            inputTokens = session.inputTokens,
            outputTokens = session.outputTokens,
            estimatedCost = session.estimatedCost,
        )
    }

    // MARK: - Status helpers

    /** Port of `statusTokenLine()`. */
    private fun statusTokenLine(): String {
        val snapshot = _uiState.value.contextWindow ?: return "Unavailable"

        val input = snapshot.inputTokens ?: 0
        val output = snapshot.outputTokens ?: 0
        val total = input + output
        val cost = snapshot.estimatedCost ?: 0.0

        if (total == 0 && cost == 0.0) {
            return "No token usage available"
        }

        val inputText = formatTokenCount(input)
        val outputText = formatTokenCount(output)
        if (cost <= 0) {
            return "$inputText in / $outputText out"
        }

        return "$inputText in / $outputText out (~${cost.formattedCost()})"
    }

    private fun providerFromModel(model: String): String? {
        val parts = model.split("/", limit = 2)
        if (parts.size < 2) return null
        return parts[0]
    }

    // MARK: - ChatStreamCoordinatorDelegate

    override val streamCoordinatorSessionId: String?
        get() = sessionId
    override val streamCoordinatorHasRunningLiveToolCall: Boolean
        get() = _uiState.value.liveToolCalls.any { !it.isCompleted }
    override val streamCoordinatorHasPendingPrompt: Boolean
        get() = pendingActionCoordinator.hasPendingPrompt

    override val streamCoordinatorLatestServerLoadHadAssistantResponseAfterLatestUser: Boolean
        get() = latestServerLoadHadAssistantResponseAfterLatestUser

    override var streamCoordinatorStreamingAssistantMessageId: String?
        get() = _uiState.value.streamingAssistantMessageId
        set(value) {
            if (value == null) {
                flushPendingStreamingContent()
            }
            _uiState.update { it.copy(streamingAssistantMessageId = value) }
        }

    override suspend fun streamCoordinatorLoadMessages() = performLoadMessages()

    override fun streamCoordinatorLatestAssistantMessageId(): String? =
        latestAssistantMessageId(_uiState.value.messages)

    override fun streamCoordinatorStartAuxiliaryMonitoring() {
        if (isScreenActive) pendingActionCoordinator.startMonitoring()
    }

    override fun streamCoordinatorStopAuxiliaryMonitoring(clearPrompt: Boolean) =
        pendingActionCoordinator.stopMonitoringForStreamTransition(clearClarification = clearPrompt)

    /** Port of `streamCoordinatorSaveSnapshotIfNeeded` + `saveActiveStreamSnapshotIfNeeded`. */
    override fun streamCoordinatorSaveSnapshotIfNeeded() {
        flushPendingStreamingContent()
        val current = _uiState.value
        val session = sessionId ?: return
        val stream = current.activeStreamId ?: return
        if (streamCoordinator.hasCompletedCurrentResponse) return
        streamSnapshots.save(
            ActiveChatStreamSnapshot(
                messages = current.messages,
                messagesOffset = current.messagesOffset,
                displayTitle = current.displayTitle,
                completedToolCallGroups = current.completedToolCallGroups,
                completedReasoningGroups = current.completedReasoningGroups,
                liveToolCalls = current.liveToolCalls,
                liveReasoningText = current.liveReasoningText,
                activeStreamLastEventId = streamCoordinator.lastEventId,
                streamingAssistantMessageId = current.streamingAssistantMessageId,
                toolCallAnchorMessageId = toolCallAnchorMessageId,
                reasoningAnchorMessageId = reasoningAnchorMessageId,
                contextWindowSnapshot = current.contextWindow,
                pinnedLocalNotices = current.pinnedLocalNotices,
            ),
            current.serverCacheNamespace, session, stream,
        )
    }

    /** Port of `restoreActiveStreamSnapshotIfAvailable`: reconcile the live assistant and remap its anchors. */
    override fun streamCoordinatorRestoreSnapshotIfAvailable(streamId: String): ChatStreamSnapshotRestoreResult {
        val current = _uiState.value
        val session = sessionId ?: return ChatStreamSnapshotRestoreResult.notRestored
        val snapshot = streamSnapshots.snapshot(current.serverCacheNamespace, session, streamId)
            ?: return ChatStreamSnapshotRestoreResult.notRestored
        val merge = mergingLoadedMessages(current.messages, snapshot)
        val assistant = merge.streamingAssistantMessageId ?: snapshot.streamingAssistantMessageId
        updateMessages(merge.messages,
            if (merge.usedSnapshotMessagesOffset) snapshot.messagesOffset else current.messagesOffset,
            if (merge.usedSnapshotMessagesOffset) snapshot.messagesOffset > 0 else current.canLoadOlderMessages)
        fun remap(anchor: String?): String? = if (anchor != null && anchor == snapshot.streamingAssistantMessageId) {
            assistant ?: anchor
        } else anchor
        toolCallAnchorMessageId = remap(snapshot.toolCallAnchorMessageId)
        reasoningAnchorMessageId = remap(snapshot.reasoningAnchorMessageId)
        _uiState.update {
            it.copy(
                displayTitle = it.displayTitle.ifEmpty { snapshot.displayTitle },
                completedToolCallGroups = snapshot.completedToolCallGroups,
                completedReasoningGroups = snapshot.completedReasoningGroups,
                liveToolCalls = snapshot.liveToolCalls,
                liveReasoningText = snapshot.liveReasoningText,
                streamingAssistantMessageId = assistant,
                contextWindow = it.contextWindow ?: snapshot.contextWindowSnapshot,
                pinnedLocalNotices = snapshot.pinnedLocalNotices,
            )
        }
        bumpStreamingContentTrigger()
        return ChatStreamSnapshotRestoreResult(true, snapshot.activeStreamLastEventId)
    }

    override fun streamCoordinatorRemoveSnapshot(streamId: String?) {
        val session = sessionId ?: return
        streamSnapshots.remove(_uiState.value.serverCacheNamespace, session, streamId ?: return)
    }

    override fun streamCoordinatorFlushPinnedLocalNoticesToTranscript() = flushPinnedLocalNoticesToTranscript()

    override fun streamCoordinatorDrainQueuedSlashMessageIfIdle() = drainQueuedSlashMessageIfIdle()

    override fun streamCoordinatorRefreshCompletedResponseTitleIfNeeded() = refreshCompletedResponseTitleIfNeeded()

    override fun streamCoordinatorDidCompleteCurrentResponse(needsTranscriptRefresh: Boolean) {
        _uiState.update { it.copy(responseCompletionTrigger = it.responseCompletionTrigger + 1) }
        cacheCurrentMessages()
        if (needsTranscriptRefresh) {
            viewModelScope.launch { performLoadMessages() }
        }
    }

    override fun streamCoordinatorDidFinishStream() {
        flushPendingStreamingContent()
        clearSteerFailure()
        streamCoordinator.latestRunEnding?.let { ending ->
            val index = _uiState.value.messages.indexOfLast { TranscriptTurnClassifier.isUserTurnBoundary(it) }
            val turnKey = if (index < 0) "turn:start" else "turn:user:${_uiState.value.messagesOffset + index}"
            _uiState.update { it.copy(latestRunOutcome = TranscriptTurnRunOutcome(turnKey, ending.startedAt, ending.endedAt, ending.ending)) }
        }
    }

    override fun streamCoordinatorDidReceiveErrorMessage(message: String) {
        _uiState.update { it.copy(sendErrorMessage = message) }
    }

    override fun streamCoordinatorDidReceiveRecoveryError(error: Throwable) {
        sendErrorIsFromRecovery = true
        _uiState.update { it.copy(sendErrorMessage = error.message) }
    }

    override fun streamCoordinatorDidConfirmRecovery() {
        if (!sendErrorIsFromRecovery) return
        sendErrorIsFromRecovery = false
        _uiState.update { it.copy(sendErrorMessage = null) }
    }

    override fun streamCoordinatorDidStartConnection(isReplay: Boolean) = resetReplayDedupState()

    override fun streamCoordinatorDidResetRecoveryState() = resetReplayDedupState()

    override fun streamCoordinatorAppendToken(text: String): Boolean = appendAssistantToken(text)

    override fun streamCoordinatorAppendInterimAssistant(payload: InterimAssistantStreamEvent): Boolean =
        appendInterimAssistant(payload)

    override fun streamCoordinatorAppendReasoning(text: String): Boolean = appendReasoning(text)

    override fun streamCoordinatorAppendToolCall(payload: ToolStreamEvent): Boolean = appendToolCall(payload)

    override fun streamCoordinatorCompleteToolCall(payload: ToolStreamEvent): Boolean = completeToolCall(payload)

    override fun streamCoordinatorUpdateTitle(payload: TitleStreamEvent): Boolean = updateTitle(payload)

    override fun streamCoordinatorApplyDone(payload: DoneStreamEvent): Boolean = applyDone(payload)

    override fun streamCoordinatorApplyApprovalUpdate(update: ApprovalPendingResponse) {
        sessionId?.let { pendingActionCoordinator.applyApprovalUpdate(update, it) }
    }

    override fun streamCoordinatorApplyClarificationUpdate(update: ClarificationPendingResponse) {
        sessionId?.let { pendingActionCoordinator.applyClarificationUpdate(update, it) }
    }

    override fun streamCoordinatorEnqueuePendingSteerLeftover(text: String): Boolean {
        val message = text.trim()
        if (message.isEmpty()) return false

        enqueueQueuedSlashMessage(message, attachments = emptyList())
        appendLocalNoticeMessage(
            "Steering hint was not consumed before the response ended, so it was queued for the next turn.",
        )
        return true
    }

    // MARK: - Static ports (companion)

    /** Port of `FilePathSearch.swift`: one cached directory per query, capped at twenty ranked rows. */
    suspend fun loadComposerFileSuggestions(query: String): List<ComposerFileSuggestion> {
        val id = sessionId ?: return emptyList()
        val generation = sessionGeneration
        val workspace = currentWorkspace
        val directory = if ('/' in query) query.substringBeforeLast('/').ifEmpty { "." } else "."
        if (directory.split('/').contains("..")) return emptyList()
        val needle = query.substringAfterLast('/').lowercase()
        val entries = composerFileListings[directory] ?: try {
            val result = api.directoryList(id, directory).entries.orEmpty().mapNotNull { entry ->
                val name = entry.name ?: return@mapNotNull null
                val path = entry.path ?: if (directory == ".") name else "$directory/$name"
                if (path.any { it.isWhitespace() } || path.split('/').contains("..")) return@mapNotNull null
                ComposerFileSuggestion(path, name, if ('/' in path) path.substringBeforeLast('/') else "", entry.isBrowsableDirectory)
            }
            if (generation != sessionGeneration || workspace != currentWorkspace) return emptyList()
            composerFileListings[directory] = result
            result
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            return emptyList()
        }
        return entries.mapNotNull { row -> composerFileScore(row.name.lowercase(), needle)?.let { row to it } }
            .sortedWith(compareBy<Pair<ComposerFileSuggestion, Int>>({ it.second }, { !it.first.isDirectory }, { it.first.name.lowercase() }))
            .take(20).map { it.first }
    }

    /** Port of `FileTreeSearch.score`: exact, prefix, boundary, substring, then fuzzy subsequence. */
    private fun composerFileScore(value: String, query: String): Int? {
        if (query.isEmpty() || value == query) return 0
        if (value.isEmpty()) return null
        val length = (value.length - query.length).coerceIn(0, 64)
        if (value.startsWith(query)) return 2 + length
        val boundary = listOf('/', '-', '_', '.').mapNotNull { marker ->
            value.indexOf("$marker$query").takeIf { it >= 0 }?.plus(1)
        }.minOrNull()
        if (boundary != null) return 4 + boundary * 2 + length
        val substring = value.indexOf(query)
        if (substring >= 0) return 6 + substring * 2 + length
        var matched = 0
        var first = -1
        var previous = -1
        var gaps = 0
        value.forEachIndexed { index, char ->
            if (char == query[matched]) {
                if (first == -1) first = index
                if (previous != -1) gaps += index - previous - 1
                previous = index
                matched++
                if (matched == query.length) return 100 + first * 2 + gaps * 3 + index - first + 1 - query.length + length
            }
        }
        return null
    }

    companion object {
        /** Manual DI factory shared with ChatPane; all persistence is process-owned, not a disposable screen scope. */
        fun factory(container: AppContainer, initialSessionId: String?, initialNewChat: Boolean): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                ChatViewModel(container.api,
                    SseChatStream(container.okHttpClient, container.json, container.api) { container.customHeaderStore.snapshot() },
                    container.cacheStore, container.serverConfig, container.settings, container.modelFavorites, container.modelRecents,
                    initialSessionId, initialNewChat, network = container.networkMonitor,
                    foregroundEvents = container.foregroundMonitor.foregroundEvents,
                    onRunStarted = { container.notificationOffer.claimForRunStart() })
            }
        }

        data class ActiveStreamMessageMerge(
            val messages: List<ChatMessage>,
            val streamingAssistantMessageId: String?,
            val usedSnapshotMessagesOffset: Boolean,
        )

        /** Port of `mergingLoadedMessages(_:withActiveStreamSnapshot:)` — steer echoes never begin a turn. */
        internal fun mergingLoadedMessages(
            loaded: List<ChatMessage>,
            snapshot: ActiveChatStreamSnapshot,
        ): ActiveStreamMessageMerge {
            if (snapshot.messages.isEmpty()) return ActiveStreamMessageMerge(loaded, latestAssistantMessageId(loaded), false)
            val assistant = snapshot.streamingAssistantMessageId?.let { id -> snapshot.messages.find { it.messageId == id } }
            if (assistant == null) {
                return if (loaded.isEmpty()) ActiveStreamMessageMerge(snapshot.messages, latestAssistantMessageId(snapshot.messages), true)
                else ActiveStreamMessageMerge(loaded, latestAssistantMessageId(loaded), false)
            }
            if (loaded.isEmpty()) return ActiveStreamMessageMerge(snapshot.messages, assistant.messageId, true)
            val userIndex = loaded.indexOfLast(TranscriptTurnClassifier::isUserTurnBoundary)
            val assistantIndex = loaded.indices.reversed().firstOrNull { it > userIndex && loaded[it].role == "assistant" }
            if (assistantIndex != null) {
                val row = loaded[assistantIndex]
                val content = when {
                    row.content.isNullOrEmpty() -> assistant.content
                    assistant.content.isNullOrEmpty() -> row.content
                    row.content.startsWith(assistant.content) -> row.content
                    assistant.content.startsWith(row.content) -> assistant.content
                    else -> row.content
                }
                val merged = loaded.toMutableList()
                merged[assistantIndex] = row.copy(
                    content = content, timestamp = row.timestamp ?: assistant.timestamp,
                    messageId = row.messageId ?: assistant.messageId, name = row.name ?: assistant.name,
                    toolCallId = row.toolCallId ?: assistant.toolCallId, toolUseId = row.toolUseId ?: assistant.toolUseId,
                    toolCalls = row.toolCalls ?: assistant.toolCalls, contentParts = row.contentParts ?: assistant.contentParts,
                    reasoning = row.reasoning ?: assistant.reasoning, attachments = row.attachments ?: assistant.attachments,
                    turnTps = row.turnTps ?: assistant.turnTps,
                )
                return ActiveStreamMessageMerge(merged, merged[assistantIndex].messageId, false)
            }
            val equivalent = loaded.any { row ->
                (assistant.messageId != null && row.messageId == assistant.messageId) ||
                    (!assistant.content.isNullOrBlank() && row.role == assistant.role && row.content?.trim() == assistant.content.trim())
            }
            return ActiveStreamMessageMerge(if (equivalent) loaded else loaded + assistant, assistant.messageId, false)
        }
        private const val MESSAGE_PAGE_LIMIT = 50
        private const val STREAMING_CONTENT_COALESCING_MILLIS = 16L

        private val REASONING_DISPLAY_ARGS = setOf("show", "hide", "on", "off")
        private val REASONING_EFFORT_ARGS = setOf("none", "minimal", "low", "medium", "high", "xhigh")
        private val PERSONALITY_CLEAR_ARGS = setOf("none", "default", "clear")

        private fun currentEpochSeconds(): Double = System.currentTimeMillis() / 1_000.0

        internal fun nonEmpty(value: String?): String? = value?.trim()?.takeIf { it.isNotEmpty() }

        /** Port of `displayTitle(from:)`. */
        internal fun displayTitle(title: String?): String =
            nonEmpty(title) ?: "Untitled Session"

        /** Port of `compactModelTitle(_:)`. */
        internal fun compactModelTitle(modelId: String): String {
            val raw = modelId.substringAfterLast(':')
            val suffix = raw.substringAfterLast('/')
            return Regex("gpt-", RegexOption.IGNORE_CASE).replace(suffix, "GPT-")
        }

        private fun formatTokenCount(value: Int): String = String.format(Locale.US, "%,d", value)

        /** Port of `transcriptMessages(from:messageOffset:)` — the visible transcript rows. */
        internal fun transcriptMessages(
            messages: List<ChatMessage>,
            messageOffset: Int? = null,
        ): List<TranscriptMessage> {
            val offset = maxOf(0, messageOffset ?: 0)
            val transcriptMessages = ArrayList<TranscriptMessage>(messages.size)

            messages.forEachIndexed { loadedIndex, message ->
                if (message.role == "tool") return@forEachIndexed
                if (TranscriptTurnClassifier.isToolResultOnlyMessage(message)) return@forEachIndexed

                val anchorId = TranscriptTurnClassifier.anchorID(message, loadedIndex, messageOffset)
                val absoluteIndex = offset + loadedIndex

                transcriptMessages.add(
                    TranscriptMessage(
                        loadedIndex = loadedIndex,
                        renderID = "transcript:$absoluteIndex",
                        anchorID = anchorId,
                        message = message,
                    ),
                )
            }

            return transcriptMessages
        }

        /** Port of `mergingLoadedMessages(_:withCachedLocalOptimisticMessages:)`. */
        internal fun mergingLoadedMessages(
            loadedMessages: List<ChatMessage>,
            cachedMessages: List<ChatMessage>,
        ): List<ChatMessage> {
            val localUserMessages = cachedMessages.filter { cachedMessage ->
                isLocalOptimisticUserMessage(cachedMessage) &&
                    !loadedMessagesContainEquivalentUserMessage(loadedMessages, cachedMessage)
            }

            if (localUserMessages.isEmpty()) return loadedMessages

            val merged = loadedMessages.toMutableList()
            for (localMessage in localUserMessages) {
                insertLocalOptimisticMessage(localMessage, merged)
            }
            return merged
        }

        /** Port of `prependingOlderMessages(_:to:)`. */
        internal fun prependingOlderMessages(
            olderMessages: List<ChatMessage>,
            currentMessages: List<ChatMessage>,
        ): List<ChatMessage> {
            if (olderMessages.isEmpty()) return currentMessages

            val seenIds = currentMessages.mapTo(HashSet()) { it.id }
            val uniqueOlderMessages = ArrayList<ChatMessage>(olderMessages.size)

            for (message in olderMessages) {
                if (seenIds.add(message.id)) {
                    uniqueOlderMessages.add(message)
                }
            }

            return uniqueOlderMessages + currentMessages
        }

        /** Port of `mergingReloadedMessages(_:intoCurrentMessages:...)`. */
        internal fun mergingReloadedMessages(
            reloadedMessages: List<ChatMessage>,
            currentMessages: List<ChatMessage>,
            currentMessagesOffset: Int,
            reloadedMessagesOffset: Int,
        ): List<ChatMessage>? {
            if (currentMessagesOffset >= reloadedMessagesOffset) return null
            val firstReloadedMessage = reloadedMessages.firstOrNull() ?: return null
            val overlapIndex = currentMessages.indexOfFirst { it.id == firstReloadedMessage.id }
            if (overlapIndex <= 0) return null

            return currentMessages.subList(0, overlapIndex) + reloadedMessages
        }

        /** Port of `resolvedMessagesOffset(from:loadedMessageCount:)`. */
        internal fun resolvedMessagesOffset(session: SessionDetail?, loadedMessageCount: Int): Int {
            session?.messagesOffset?.let { return maxOf(0, it) }

            if (session?.messagesTruncated != true) return 0
            val messageCount = session.messageCount ?: return 0

            return maxOf(0, messageCount - loadedMessageCount)
        }

        /** Port of `hasAssistantResponseAfterLatestUser(in:)`. */
        internal fun hasAssistantResponseAfterLatestUser(messages: List<ChatMessage>): Boolean {
            if (messages.isEmpty()) return false

            val latestUserIndex = messages.indexOfLast { it.role == "user" }
            val startIndex = if (latestUserIndex >= 0) latestUserIndex + 1 else 0

            return messages.subList(startIndex, messages.size).any { message ->
                message.role == "assistant" && hasAssistantResponseContent(message)
            }
        }

        private fun hasAssistantResponseContent(message: ChatMessage): Boolean {
            if (message.content?.trim()?.isNotEmpty() == true) return true
            if (message.reasoning?.trim()?.isNotEmpty() == true) return true
            if (message.toolCalls?.isNotEmpty() == true) return true
            return hasAssistantContentParts(message.contentParts)
        }

        private fun hasAssistantContentParts(parts: List<JSONValue>?): Boolean {
            if (parts == null) return false

            return parts.any { part ->
                part.string?.let { return@any it.trim().isNotEmpty() }

                val obj = part.objectValue ?: return@any false
                when (obj["type"]?.string) {
                    "tool_use", "thinking", "reasoning", "redacted_thinking" -> true
                    "text" -> obj["text"]?.string?.trim()?.isNotEmpty() == true
                    else -> false
                }
            }
        }

        /** Port of `isLocalOptimisticUserMessage(_:)`. */
        internal fun isLocalOptimisticUserMessage(message: ChatMessage): Boolean =
            message.role == "user" && message.messageId?.startsWith("local-") == true

        /** Port of `loadedMessagesContainEquivalentUserMessage(_:localMessage:)`. */
        internal fun loadedMessagesContainEquivalentUserMessage(
            loadedMessages: List<ChatMessage>,
            localMessage: ChatMessage,
        ): Boolean {
            val localContent = normalizedUserMessageContent(localMessage.content)
            val localAttachmentKeys = attachmentKeys(localMessage)

            return loadedMessages.any { loadedMessage ->
                if (loadedMessage.role != "user") return@any false
                if (loadedMessage.messageId == localMessage.messageId) return@any true
                if (normalizedUserMessageContent(loadedMessage.content) != localContent) return@any false

                if (localAttachmentKeys.isNotEmpty()) {
                    val loadedAttachmentKeys = attachmentKeys(loadedMessage)
                    if (loadedAttachmentKeys.isEmpty() ||
                        !loadedAttachmentKeys.containsAll(localAttachmentKeys)
                    ) {
                        return@any false
                    }
                }

                val localTimestamp = localMessage.timestamp ?: return@any true
                val loadedTimestamp = loadedMessage.timestamp ?: return@any true
                loadedTimestamp >= localTimestamp - 300
            }
        }

        /** Port of `insertLocalOptimisticMessage(_:into:)`. */
        internal fun insertLocalOptimisticMessage(
            localMessage: ChatMessage,
            messages: MutableList<ChatMessage>,
        ) {
            if (messages.none { it.role == "user" }) {
                val firstAssistantIndex = messages.indexOfFirst { it.role == "assistant" }
                if (firstAssistantIndex >= 0) {
                    messages.add(firstAssistantIndex, localMessage)
                    return
                }
            }

            val localTimestamp = localMessage.timestamp
            val insertionIndex = if (localTimestamp == null) {
                -1
            } else {
                messages.indexOfFirst { loadedMessage ->
                    val loadedTimestamp = loadedMessage.timestamp ?: return@indexOfFirst false
                    loadedTimestamp > localTimestamp
                }
            }

            if (insertionIndex < 0) {
                messages.add(localMessage)
            } else {
                messages.add(insertionIndex, localMessage)
            }
        }

        internal fun latestAssistantMessageId(messages: List<ChatMessage>): String? =
            messages.lastOrNull { it.role == "assistant" }?.messageId

        private fun normalizedUserMessageContent(content: String?): String {
            content ?: return ""
            return MessageAttachment.contentWithoutAttachedFilesMarker(content).trim()
        }

        private fun attachmentKeys(message: ChatMessage): Set<String> =
            (message.attachments ?: emptyList()).mapNotNullTo(HashSet()) { it.identityKey }

        /** Port of `nonEmptyReplayMatchText`. */
        private fun nonEmptyReplayMatchText(value: String?): String? =
            value?.trim()?.takeIf { it.isNotEmpty() }

        /** Port of `nonEmptyStableToolID`. */
        private fun nonEmptyStableToolId(id: String): String? {
            val stableId = nonEmptyReplayMatchText(id) ?: return null
            if (stableId.startsWith("live-tool-") ||
                stableId.startsWith("message-tool-") ||
                stableId.startsWith("persisted-tool-")
            ) {
                return null
            }
            return stableId
        }

        /** Port of the `SessionSummary.init(from: SessionDetail)` initializer. */
        internal fun sessionSummaryFrom(detail: SessionDetail): SessionSummary = SessionSummary(
            sessionId = detail.sessionId,
            title = detail.title,
            workspace = detail.workspace,
            model = detail.model,
            modelProvider = detail.modelProvider,
            messageCount = detail.messageCount ?: detail.messages?.size,
            createdAt = detail.createdAt,
            updatedAt = detail.updatedAt,
            lastMessageAt = detail.lastMessageAt,
            pinned = detail.pinned,
            archived = detail.archived,
            projectId = detail.projectId,
            profile = detail.profile,
            inputTokens = detail.inputTokens,
            outputTokens = detail.outputTokens,
            estimatedCost = detail.estimatedCost,
            activeStreamId = detail.activeStreamId,
            isCliSession = detail.isCliSession,
            hasPendingUserMessage = if (
                nonEmpty(detail.pendingUserMessage) != null || detail.pendingAttachments?.isNotEmpty() == true
            ) {
                true
            } else {
                null
            },
            pendingStartedAt = detail.pendingStartedAt,
            worktreePath = detail.worktreePath,
            sourceTag = detail.sourceTag,
            rawSource = detail.rawSource,
            sessionSource = detail.sessionSource,
            sourceLabel = detail.sourceLabel,
            parentSessionId = detail.parentSessionId,
            relationshipType = detail.relationshipType,
            readOnly = detail.readOnly,
        )

        /** Read-only detection mirroring `SessionSummary.isSessionReadOnly`. */
        internal fun sessionDetailIsReadOnly(session: SessionDetail): Boolean {
            if (session.readOnly == true || session.isReadOnly == true) return true
            return listOf(session.sourceTag, session.rawSource, session.sessionSource, session.sourceLabel)
                .mapNotNull { nonEmpty(it)?.lowercase() }
                .contains("subagent")
        }

        /** Port of `ChatComposerConfigLoader.uniqueProvider(for:in:)`. */
        internal fun uniqueProvider(modelId: String?, groups: List<ModelCatalogGroup>): String? {
            val id = nonEmpty(modelId) ?: return null
            val providers = groups
                .flatMap { it.slashAutocompleteModels }
                .filter { it.id == id }
                .mapNotNullTo(HashSet()) { nonEmpty(it.providerID) }
            return if (providers.size == 1) providers.first() else null
        }

        /** Port of `btwMessageText(question:answer:isLoading:)`. */
        internal fun btwMessageText(question: String, answer: String?, isLoading: Boolean): String {
            val trimmedAnswer = answer?.trim().orEmpty()
            val body = if (trimmedAnswer.isEmpty()) {
                if (isLoading) "..." else "No answer produced."
            } else {
                trimmedAnswer
            }

            return "**BTW** $question\n\n$body"
        }

        /** Port of `backgroundResultText(prompt:answer:)`. */
        internal fun backgroundResultText(prompt: String, answer: String?): String {
            val trimmedAnswer = answer?.trim().orEmpty()
            val body = trimmedAnswer.ifEmpty { "No answer produced." }
            val summary = if (prompt.length > 80) "${prompt.take(80)}..." else prompt

            return "**Background** $summary\n\n$body"
        }

        /** Port of `slashCommandHelpText`. */
        internal val SLASH_COMMAND_HELP_TEXT: String = """
            |Available mobile commands:
            |
            |`/help` - Show this command list.
            |`/clear` - Clear the local transcript.
            |`/stop` - Stop the current response.
            |`/new` - Open a fresh session.
            |`/model <id>` - Switch this session's model.
            |`/workspace <path>` - Switch this session's workspace.
            |`/reasoning <level>` - Set reasoning display or effort.
            |`/title <text>` - Rename this session.
            |`/personality <name>` - Set or clear this session's personality.
            |`/skills [query]` - Search available skills.
            |`/queue <message>` - Queue a message for the next turn.
            |`/steer <message>` - Steer the active response.
            |`/interrupt <message>` - Stop the active response and send a new message.
            |`/status` - Show session status.
            |`/btw <question>` - Ask a side question without changing this chat.
            |`/background <prompt>` - Run a parallel task and post the result here.
            |`/bg <prompt>` - Alias for `/background`.
            |`/branch [name]` - Fork this conversation.
            |`/fork [name]` - Alias for `/branch`.
            |`/compress [focus]` - Compress this session's context.
            |`/compact [focus]` - Alias for `/compress`.
            |`/undo` - Undo the last exchange.
            |`/retry` - Retry the last turn.
        """.trimMargin()
    }
}
