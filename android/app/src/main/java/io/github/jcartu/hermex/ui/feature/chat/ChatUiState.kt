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
import io.github.jcartu.hermex.model.SkillSlashSuggestion
import io.github.jcartu.hermex.model.ToolCall
import io.github.jcartu.hermex.model.ToolCallGroup
import io.github.jcartu.hermex.model.WorkspaceRoot
import java.util.UUID

/**
 * Port of the Swift `ActiveStreamRecoveryState` enum from `ChatViewModel.swift`:
 * the reconnect status surfaced by the active-run status row.
 */
enum class ActiveStreamRecoveryState { IDLE, CHECKING, RECONNECTING }

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
    /** Live tokens/second from `metering` frames; null when unavailable. */
    val liveTokensPerSecond: Double? = null,
    val contextWindow: ContextWindowSnapshot? = null,
    /** Monotonic counter bumped when a response completes (drives haptics/scroll). */
    val responseCompletionTrigger: Int = 0,
    /** Monotonic counter bumped when streamed content grew (drives auto-scroll). */
    val streamingContentTrigger: Int = 0,

    // Pending server prompts
    val approvalPrompt: ApprovalPromptState? = null,
    val isRespondingToApproval: Boolean = false,
    val approvalErrorMessage: String? = null,
    val clarificationPrompt: ClarificationPromptState? = null,
    val isRespondingToClarification: Boolean = false,
    val clarificationErrorMessage: String? = null,

    // Composer
    val draft: String = "",
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
    fun updateDraft(text: String)

    /**
     * Send the current draft + staged attachments. While a stream is active
     * this steers the running turn instead (Swift steer semantics: fallback
     * responses queue the text). Slash commands are parsed and executed.
     */
    fun send()

    /** Cancel the active stream (`GET /api/chat/cancel`). */
    fun stopStream()

    fun selectModel(option: ModelCatalogOption)
    fun toggleFavoriteModel(option: ModelCatalogOption)

    /** Silent one-shot catalog refresh when the model picker opens. */
    fun refreshModelCatalogForPickerOpen()

    fun selectReasoningEffort(effort: String)
    fun selectWorkspace(path: String)
    fun loadWorkspaceSuggestions(prefix: String)
    fun switchProfile(profile: ProfileSummary, startNewSession: Boolean)

    /** Upload a picked file; on success it joins [ChatUiState.pendingAttachments]. */
    fun uploadAttachment(bytes: ByteArray, filename: String)
    fun importVoiceNote(bytes: ByteArray, filename: String)
    fun removePendingAttachment(id: String)
    fun clearPendingAttachments()

    /** Surface a client-side attachment error (e.g. file too large) in the strip. */
    fun setUploadAttachmentError(message: String?)
}
