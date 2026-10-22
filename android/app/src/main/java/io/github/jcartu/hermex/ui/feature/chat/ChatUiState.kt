package io.github.jcartu.hermex.ui.feature.chat

import io.github.jcartu.hermex.model.AgentCommand
import io.github.jcartu.hermex.model.ChatMessage
import io.github.jcartu.hermex.model.ContextWindowSnapshot
import io.github.jcartu.hermex.model.ModelCatalogGroup
import io.github.jcartu.hermex.model.ModelCatalogOption
import io.github.jcartu.hermex.model.ModelFavoriteKey
import io.github.jcartu.hermex.model.PendingApproval
import io.github.jcartu.hermex.model.PendingAttachment
import io.github.jcartu.hermex.model.PendingClarification
import io.github.jcartu.hermex.model.ProfileSummary
import io.github.jcartu.hermex.model.SessionSummary
import io.github.jcartu.hermex.model.SkillSlashSuggestion
import io.github.jcartu.hermex.model.ToolCall
import io.github.jcartu.hermex.model.ToolCallGroup
import io.github.jcartu.hermex.model.WorkspaceRoot
import java.util.UUID

/**
 * Port of the Swift `ActiveStreamRecoveryState` enum from `ChatViewModel.swift`:
 * the reconnect status surfaced by the active-run status row.
 * `WAITING_FOR_NETWORK` parks recovery while the device is offline (#869):
 * "Waiting for network" instead of exhausting the retry budget.
 */
enum class ActiveStreamRecoveryState { IDLE, CHECKING, RECONNECTING, WAITING_FOR_NETWORK }

/**
 * Port of `StreamingSendBehavior` (`Config/AppTheme.swift`) plus the plain
 * tap: what Send does to a running response. `DEFAULT` is the Send While
 * Responding default (steer, `StreamingSendBehavior.storedValue`); a
 * long-press on Send picks one of the others for a single message (1.8.0).
 */
enum class SendMode { DEFAULT, QUEUE, STEER, STOP_AND_SEND }

/**
 * Port of `ComposerQuote` (`ComposerQuote.swift`): a passage the user selected
 * from a completed response ("Ask Hermex about selection", 1.7.0). Quotes stay
 * separate from typed text; on send they become `> ` blockquotes ahead of it.
 */
data class ComposerQuote(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
) {
    /** A single-line label for the chip; the full passage remains in [text]. */
    val preview: String
        get() {
            val collapsed = text.split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
            if (collapsed.length <= 72) return collapsed
            return collapsed.take(71) + "…"
        }

    companion object {
        /** Port of `ComposerQuoteMessageFormatter.message(text:quotes:)`. */
        fun message(text: String, quotes: List<ComposerQuote>): String {
            val quotedPassages = quotes.map { quote ->
                quote.text.split("\n").joinToString("\n") { line -> if (line.isEmpty()) ">" else "> $line" }
            }
            return (quotedPassages + (if (text.isEmpty()) emptyList() else listOf(text))).joinToString("\n\n")
        }
    }
}

/** Port of `TranscriptTurnRunOutcome.Ending` — how a run stopped counting. */
enum class ChatRunEnding { COMPLETED, CANCELLED, FAILED }

/**
 * Port of `TranscriptTurnRunOutcome` (`ChatViewModel.swift`): the latest run's
 * span keyed to the turn it answered, so the transcript can label the settled
 * fold ("Worked for 12s" / "Stopped" / "Failed") and pick which turn stays open.
 * Timestamps are epoch seconds.
 */
data class TranscriptTurnRunOutcome(
    val turnKey: String,
    val startedAt: Double,
    val endedAt: Double,
    val ending: ChatRunEnding,
) {
    val durationSeconds: Double get() = maxOf(0.0, endedAt - startedAt)
}

/**
 * Port of `ForkOrigin` (`ForkOrigin.swift`): where a forked chat came from, for
 * the "Forked from" row at the top of its transcript. Only sessions upstream
 * stamps `session_source: "fork"` with a `parent_session_id` count.
 */
data class ForkOrigin(
    val parentSessionId: String,
    /** The parent as cached/fetched; null means a tap fetches it. */
    val parent: SessionSummary?,
) {
    val title: String
        get() {
            val parent = parent ?: return "Forked from another chat"
            val parentTitle = parent.title?.trim()?.takeIf { it.isNotEmpty() } ?: "Untitled Session"
            return "Forked from $parentTitle"
        }

    companion object {
        /** Port of `ForkOrigin.parentSessionID(of:)`. */
        fun parentSessionId(sessionSource: String?, parentSessionId: String?): String? {
            if (sessionSource?.trim()?.lowercase() != "fork") return null
            return parentSessionId?.trim()?.takeIf { it.isNotEmpty() }
        }
    }
}

/** Port of `ReasoningGroup` from `ChatViewModel.swift`. */
data class ReasoningGroup(
    val id: String = UUID.randomUUID().toString(),
    val anchorMessageID: String?,
    val text: String,
)

/** Port of `TranscriptMessage` from `ChatViewModel.swift` — one visible transcript row. */
data class TranscriptMessage(
    val loadedIndex: Int,
    val renderID: String,
    val anchorID: String,
    val message: ChatMessage,
)

/** Port of `ApprovalPromptState` from `ChatViewModel.swift`. */
data class ApprovalPromptState(
    val sessionId: String,
    val pending: PendingApproval,
    val pendingCount: Int,
) {
    val id: String get() = "$sessionId-${pending.id}"
    val patternKeys: List<String> get() = pending.displayPatternKeys
}

/** Port of `ClarificationPromptState` from `ChatViewModel.swift`. */
data class ClarificationPromptState(
    val sessionId: String,
    val pending: PendingClarification,
    val pendingCount: Int,
) {
    val id: String get() = "$sessionId-${pending.id}"
}

/**
 * Single immutable UI state for the chat screen, exposed by [ChatViewModel]
 * as a `StateFlow<ChatUiState>`. This is the contract between the ViewModel
 * and the transcript/composer/overlay composables: fields may be ADDED, but
 * the ones below must keep their names and types.
 */
data class ChatUiState(
    // Session identity
    val sessionId: String? = null,
    val displayTitle: String = "",
    val isReadOnly: Boolean = false,
    /**
     * The active server's base URL exactly as `ServerConfigRepository.current().baseUrl`
     * returns it ("" before it resolves): the key that keeps caches, drafts and
     * composer thumbnails per server (1.7.0).
     */
    val serverCacheNamespace: String = "",

    // Transcript
    val messages: List<ChatMessage> = emptyList(),
    /** Server-side index of `messages[0]` when older pages exist (pagination + anchor math). */
    val messagesOffset: Int = 0,
    /** Visible rows derived from [messages] (Swift `displayedTranscriptMessages`). */
    val transcriptMessages: List<TranscriptMessage> = emptyList(),
    val canLoadOlderMessages: Boolean = false,
    val isLoadingOlderMessages: Boolean = false,
    val isLoading: Boolean = false,
    /** True when the transcript came from the offline cache instead of the server. */
    val isViewingCachedData: Boolean = false,
    val errorMessage: String? = null,
    val sendErrorMessage: String? = null,

    // Streaming
    /** Non-null while a chat stream is active for this session. */
    val activeStreamId: String? = null,
    val isStartingChat: Boolean = false,
    val isCancellingStream: Boolean = false,
    /** messageId of the assistant message receiving streamed tokens, or null. */
    val streamingAssistantMessageId: String? = null,
    /** Reasoning text of the in-flight turn (renders as a live collapsible block). */
    val liveReasoningText: String = "",
    /** Reasoning blocks of completed turns, anchored to assistant messages. */
    val completedReasoningGroups: List<ReasoningGroup> = emptyList(),
    /** Tool calls of the in-flight turn (live cards under the streaming message). */
    val liveToolCalls: List<ToolCall> = emptyList(),
    /** Tool-call groups of completed turns, anchored to assistant messages. */
    val completedToolCallGroups: List<ToolCallGroup> = emptyList(),
    val recoveryState: ActiveStreamRecoveryState = ActiveStreamRecoveryState.IDLE,
    /**
     * True while the SSE transport is provably alive (an event or heartbeat
     * arrived within `transportFreshInterval`), so the "Checking stream" chip
     * stays hidden during a semantically quiet window (#227).
     */
    val isStreamHeartbeatFresh: Boolean = false,
    /** Live tokens/second from `metering` frames; null when unavailable. */
    val liveTokensPerSecond: Double? = null,
    val contextWindow: ContextWindowSnapshot? = null,
    /** Monotonic counter bumped when a response completes (drives haptics/scroll). */
    val responseCompletionTrigger: Int = 0,
    /** Monotonic counter bumped when streamed content grew (drives auto-scroll). */
    val streamingContentTrigger: Int = 0,
    /**
     * Epoch seconds the current run started: the server's `pending_started_at`,
     * else the latest user message's timestamp, else the local send time. "Working
     * for" counts from here and a same-stream reattach never restarts it.
     */
    val activeRunStartedAt: Double? = null,
    /** How the latest run ended, keyed to the turn it answered; null until a run finishes. */
    val latestRunOutcome: TranscriptTurnRunOutcome? = null,
    /** Receipt shown while messages wait behind the run (Swift `queuedMessagesReceipt`). */
    val queuedMessagesReceipt: String? = null,
    /** "Couldn't steer[\n<reason>]" card with Retry; the run keeps going (1.8.0). */
    val steerFailureMessage: String? = null,
    /** Transient "Steering hint delivered." confirmation. */
    val steeringConfirmation: String? = null,
    /**
     * After an `agent_runtime_stale` 409 (#955): the prompt "Copy fix prompt"
     * puts on the clipboard; null while the update is still running or when
     * the last send error was something else.
     */
    val agentRuntimeStaleFixPrompt: String? = null,
    /**
     * The continuation session a `session_rotated` 409 named, once its Profile
     * was verified and the rejected composer submission restored. The pane opens
     * it (DEVELOPMENT.md § Compression continuation recovery); null otherwise.
     */
    val compressionContinuation: SessionSummary? = null,
    /** True once this session was sealed by compression: Send stays refused. */
    val isCompressionRotated: Boolean = false,
    /** "Forked from …" row data for a `/branch` or Fork From Here child; null otherwise. */
    val forkOrigin: ForkOrigin? = null,
    /** `/clear` was typed: ask before wiping the server-side history. */
    val pendingClearConfirmation: Boolean = false,
    val isClearingConversation: Boolean = false,

    // Pending server prompts
    val approvalPrompt: ApprovalPromptState? = null,
    val isRespondingToApproval: Boolean = false,
    val approvalErrorMessage: String? = null,
    val clarificationPrompt: ClarificationPromptState? = null,
    val isRespondingToClarification: Boolean = false,
    val clarificationErrorMessage: String? = null,

    // Composer
    val draft: String = "",
    /** [draft] parsed into chips (`parseComposerTokens`); the text stays the source of truth. */
    val draftTokens: List<ComposerToken> = emptyList(),
    /** Quoted passages ahead of the typed text ("Ask Hermex about selection"). */
    val draftQuotes: List<ComposerQuote> = emptyList(),
    val pendingAttachments: List<PendingAttachment> = emptyList(),
    val isUploadingAttachment: Boolean = false,
    val isProcessingVoiceNote: Boolean = false,
    val uploadAttachmentErrorMessage: String? = null,
    val selectedModelId: String? = null,
    val selectedModelProviderId: String? = null,
    /** Chip label: model display name, or "Model" when none is selected. */
    val selectedModelTitle: String = "Model",
    val selectedWorkspacePath: String? = null,
    /** Chip label: active profile name, or "Profile" when none is selected. */
    val selectedProfileTitle: String = "Profile",
    val modelCatalogGroups: List<ModelCatalogGroup> = emptyList(),
    val favoriteModelKeys: List<ModelFavoriteKey> = emptyList(),
    val recentModelKeys: List<ModelFavoriteKey> = emptyList(),
    val workspaceRoots: List<WorkspaceRoot> = emptyList(),
    val workspaceSuggestions: List<String> = emptyList(),
    val profiles: List<ProfileSummary> = emptyList(),
    val reasoningEffort: String? = null,
    val supportedReasoningEfforts: List<String> = emptyList(),
    val showsReasoningEffortControl: Boolean = false,
    val isLoadingComposerConfiguration: Boolean = false,
    val composerConfigurationErrorMessage: String? = null,
    /** Server slash commands from `GET /api/commands` (merged into autocomplete). */
    val serverCommands: List<AgentCommand> = emptyList(),
    /** Skill shortcuts for `/skills` + `/skill-<slug>` autocomplete. */
    val skillSuggestions: List<SkillSlashSuggestion> = emptyList(),
    /** Local notices pinned above the composer until the next turn flushes them. */
    val pinnedLocalNotices: List<String> = emptyList(),

    // Transcript display settings (mirrored from SettingsRepository)
    val showsThinkingAndToolCards: Boolean = true,
    val thinkingCardsStartExpanded: Boolean = false,
    val toolCardsStartExpanded: Boolean = false,
    val hidesAttachmentPaths: Boolean = false,
    val showsAssistantTurnTimestamps: Boolean = false,
    val showsResponseSpeed: Boolean = false,
    val wrapsCodeBlockLines: Boolean = false,
    val rtlChatLayoutEnabled: Boolean = false,
) {
    val isStreaming: Boolean get() = activeStreamId != null
}

/**
 * Everything the composer can ask the ViewModel to do. `ChatPane` adapts
 * [ChatViewModel] onto this; the composer composable depends only on this
 * interface (and [ChatUiState]) so the two files compile independently.
 */
interface ChatComposerActions {
    /** Replaces the typed draft; [ChatUiState.draftTokens] is re-derived from it. */
    fun updateDraft(text: String)

    /** Replaces the draft from chip tokens; the draft text becomes `tokens.toServerText()`. */
    fun updateDraftTokens(tokens: List<ComposerToken>)

    /**
     * Send the current draft + quotes + staged attachments. Idle: a plain send.
     * While a run is active, [mode] picks what happens to the running response
     * (port of `ChatViewModel.submitStreamingMessage(_:behavior:)`): `DEFAULT`
     * is the Send While Responding default (steer); `STEER` → `/api/chat/steer`;
     * `QUEUE` → sent when the run finishes; `STOP_AND_SEND` → cancel, then send.
     * Slash commands are parsed and executed first.
     */
    fun send(mode: SendMode = SendMode.DEFAULT)

    /** Cancel the active stream (`GET /api/chat/cancel`). */
    fun stopStream()

    /** Drops one "Ask Hermex about selection" quote chip. */
    fun removeDraftQuote(id: String)

    fun selectModel(option: ModelCatalogOption)
    fun toggleFavoriteModel(option: ModelCatalogOption)

    /** Silent one-shot catalog refresh when the model picker opens. */
    fun refreshModelCatalogForPickerOpen()

    /** Session-scoped reasoning effort (`POST /api/reasoning` with `session_id`, 1.6.0). */
    fun selectReasoningEffort(effort: String)
    fun selectWorkspace(path: String)
    fun loadWorkspaceSuggestions(prefix: String)
    fun switchProfile(profile: ProfileSummary, startNewSession: Boolean)

    /**
     * Upload a picked file; on success it joins [ChatUiState.pendingAttachments].
     * [previewData] (image/PDF/text thumbnail bytes the composer rendered) rides
     * along as `PendingAttachment.thumbnailData` for the strip.
     */
    fun uploadAttachment(bytes: ByteArray, filename: String, previewData: ByteArray? = null)
    fun importVoiceNote(bytes: ByteArray, filename: String)
    fun removePendingAttachment(id: String)
    fun clearPendingAttachments()

    /** Surface a client-side attachment error (e.g. file too large) in the strip. */
    fun setUploadAttachmentError(message: String?)
}
