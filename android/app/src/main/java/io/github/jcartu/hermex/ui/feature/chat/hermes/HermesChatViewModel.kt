package io.github.jcartu.hermex.ui.feature.chat.hermes

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.jcartu.hermex.core.share.SharedImport
import io.github.jcartu.hermex.data.*
import io.github.jcartu.hermex.di.AppContainer
import io.github.jcartu.hermex.model.*
import io.github.jcartu.hermex.model.hermes.*
import io.github.jcartu.hermex.net.hermes.*
import io.github.jcartu.hermex.ui.feature.chat.*
import io.github.jcartu.hermex.ui.feature.hermes.sessions.HermesProfilePreference
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.File
import java.util.UUID

/** Port of the Hermes title-face projection in HermesChatTurnCoordinator.swift. */
enum class HermesTitleFace { RESTING, WORKING, WAITING, FAILED }

/** Presentation events owned by a chat, not by the shared transport or global active server. */
sealed class HermesChatNavigation {
    data class Session(val profile: String, val key: String) : HermesChatNavigation()
    data class Sessions(val profile: String, val query: String) : HermesChatNavigation()
}

/** Immutable invalidation of the controller graph, plus explicit user confirmations. */
data class HermesChatPresentation(
    val revision: Int = 0,
    val coordinator: HermesChatTurnCoordinator? = null,
    val profile: String? = null,
    val stopConfirmation: Boolean = false,
    val rewind: Rewind? = null,
    val navigation: HermesChatNavigation? = null,
    val identified: Pair<String, String>? = null,
    val forkParent: HermesSessionRow? = null,
) {
    data class Rewind(val rowID: Int, val text: String, val editing: Boolean, val laterPrompts: Boolean, val generation: Int, val showsPrompt: Boolean = false)
}

/**
 * Port of the Hermes branches of `Features/Chat/ChatViewModel.swift`. A Hermes-kind screen
 * owns one BotClient handle, one conversation, and server/connection/Profile-scoped drafts.
 * It implements the existing composer interface and publishes the existing transcript state:
 * no webui request participates in this ViewModel.
 */
class HermesChatViewModel(
    private val context: Context,
    private val container: AppContainer,
    val account: ServerAccount,
    val http: HermesConnection,
    private val initialSessionKey: String?,
    private val initialNewChat: Boolean,
    private val initialProfile: String? = null,
    private val botId: String? = null,
    private val parentKey: String? = null,
    private val lineageRoot: String? = null,
    private val botChatRoot: String? = null,
    private val linkedRoot: String? = null,
) : ViewModel(), ChatComposerActions {
    private val wire = BotClientTransport(BotClient(http))
    private val _uiState = MutableStateFlow(ChatUiState(isLoading = true, serverCacheNamespace = "hermes|${account.id}|${http.connection.id}"))
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()
    private val _presentation = MutableStateFlow(HermesChatPresentation())
    val presentation: StateFlow<HermesChatPresentation> = _presentation.asStateFlow()
    private val _workspace = MutableStateFlow<HermesWorkspaceContext?>(null)
    val workspace: StateFlow<HermesWorkspaceContext?> = _workspace.asStateFlow()
    val profile: String get() = _presentation.value.profile ?: initialProfile ?: botId.orEmpty()
    val coordinator: HermesChatTurnCoordinator? get() = _presentation.value.coordinator
    private val drafts = container.cacheStore.chatDraftStore
    private var draftKey: ChatDraftKey? = null
    private var lease: ChatDraftAttachmentLease? = null
    private val uploadMutex = Mutex()
    private var busy = false
    private var opensNew = initialNewChat || initialSessionKey == null && botId == null
    private var acceptedFirstPrompt = false
    private var pendingGoal: String? = null
    private var active = true
    private var restoring = false
    private var uncertain = false
    private var uncertainGeneration = -1
    private var pendingStop: Pair<Int, String>? = null
    private val sentFiles = LinkedHashMap<String, String>()
    private val cacheEpoch = container.hermesCacheStore.hermesCacheEpoch(account.id)
    private var initialization: Job
    private val publicMedia = OkHttpClient.Builder().cookieJar(CookieJar.NO_COOKIES)
        .followRedirects(false).followSslRedirects(false).build()
    private val previewDirectory = File(context.cacheDir, "hermes-chat-${UUID.randomUUID()}")
    private val previewFiles = LinkedHashMap<String, File>()

    var mentionAnnotation: ((String) -> String)? = null
        set(value) { field = value; coordinator?.mentionAnnotation = value }

    init {
        viewModelScope.launch {
            container.settings.settings.collect { settings ->
                _uiState.update { it.copy(
                    showsThinkingAndToolCards = settings.showsThinkingAndToolCards,
                    thinkingCardsStartExpanded = settings.thinkingCardsStartExpanded,
                    toolCardsStartExpanded = settings.toolCardsStartExpanded,
                    hidesAttachmentPaths = true,
                    showsAssistantTurnTimestamps = settings.showsAssistantTurnTimestamps,
                    showsResponseSpeed = settings.showsResponseSpeed,
                    wrapsCodeBlockLines = settings.wrapsCodeBlockLines,
                    rtlChatLayoutEnabled = settings.rtlChatLayoutEnabled,
                ) }
            }
        }
        initialization = viewModelScope.launch {
            try {
                val picked = botId ?: initialProfile ?: HermesProfilePreference.selected(context, account.id)
                    ?: BotJSON.parse(http.data(HermesREST.ProfilesActive))["current"].text?.takeIf(String::isNotEmpty)
                    ?: throw BotFailure.Unsupported
                val target = when {
                    botId != null -> ConversationTarget.CanonicalChat(picked)
                    initialSessionKey != null && !initialNewChat -> ConversationTarget.Session(picked, initialSessionKey)
                    else -> ConversationTarget.New(picked)
                }
                open(target)
            } catch (error: CancellationException) { throw error }
            catch (error: Throwable) { _uiState.update { it.copy(isLoading = false, errorMessage = error.message) } }
        }
    }

    private suspend fun open(target: ConversationTarget, initialContext: Boolean = true) {
        coordinator?.suspendConnection()
        val preferredRoot = if (initialContext) lineageRoot ?: botChatRoot else null
        val knownParent = parentKey.takeIf { initialContext }
        val engine = HermesConversation(account.id, http.connection, target, wire, viewModelScope, linkedRoot.takeIf { initialContext })
        val policy = if (target is ConversationTarget.CanonicalChat || initialContext && botChatRoot != null) HermesChatPolicy.BOT_CHAT else HermesChatPolicy.SESSION
        fun cacheScope(root: String): String = when {
            target is ConversationTarget.CanonicalChat -> HermesCacheStore.hermesBotChatScope(http.connection.id, target.profile, root)
            policy == HermesChatPolicy.BOT_CHAT -> HermesCacheStore.hermesArchivedBotChatScope(http.connection.id, target.profile, root)
            else -> HermesCacheStore.hermesScope(target.profile, root)
        }
        val next = HermesChatTurnCoordinator(engine, _uiState, policy)
        next.mentionAnnotation = mentionAnnotation
        draftKey = when (target) {
            is ConversationTarget.CanonicalChat -> ChatDraftKey.bot(account.id, http.connection.id, target.profile)
            is ConversationTarget.Session -> ChatDraftKey.hermesSession(account.id, http.connection.id, target.profile, preferredRoot ?: target.key)
            is ConversationTarget.New -> ChatDraftKey.hermesSession(account.id, http.connection.id, target.profile, null)
        }
        lease = drafts.makeAttachmentLease(draftKey)
        _presentation.update { it.copy(coordinator = next, profile = target.profile, revision = it.revision + 1, rewind = null, stopConfirmation = false, forkParent = null) }
        next.onChange = { publish() }
        next.onPromptAccepted = {
            if (!acceptedFirstPrompt) {
                acceptedFirstPrompt = true
                if (opensNew) {
                    val from = draftKey
                    val key = engine.root
                    if (from != null && key != null) {
                        val to = ChatDraftKey.hermesSession(account.id, http.connection.id, target.profile, key)
                        drafts.moveDraft(from, to); draftKey = to; lease?.key = to
                        _presentation.update { it.copy(identified = target.profile to key) }
                    }
                }
            }
        }
        next.onHistory = { rows, coverage ->
            val root = preferredRoot ?: engine.root
            if (root != null) viewModelScope.launch(Dispatchers.IO) {
                container.hermesCacheStore.cacheHermesMessages(account.id, cacheScope(root),
                    rows.mapNotNull { row -> row.rowID?.let { HermesCachedRow(it, row) } }, coverage, cacheEpoch)
            }
        }
        next.onRunStarted = {
            engine.storedKey?.let { key -> viewModelScope.launch {
                container.runNotifications.runStarted(account, key, _uiState.value.displayTitle, target.profile)
            } }
        }
        next.onRunFinished = { ending ->
            engine.storedKey?.let { key -> viewModelScope.launch {
                container.runNotifications.runFinished(account, key, _uiState.value.displayTitle,
                    io.github.jcartu.hermex.service.RunNotificationOutcome.valueOf(ending.name), target.profile)
            } }
            pendingGoal?.let { argument -> pendingGoal = null; launchAction { executeGoal(argument) } }
        }
        val notifiedRequests = HashSet<String>()
        next.requests.onOpenChange = {
            val request = next.requests.onScreen
            val key = engine.storedKey
            if (request != null && key != null && notifiedRequests.add(request.requestID)) viewModelScope.launch {
                when (request) {
                    is BotPendingRequest.Approval -> container.runNotifications.approvalNeeded(account, key,
                        _uiState.value.displayTitle, request.request.consequence ?: "Approval required", target.profile)
                    is BotPendingRequest.Question -> container.runNotifications.questionAsked(account, key,
                        _uiState.value.displayTitle, request.request.questions.firstOrNull()?.prompt ?: "Hermes needs more information.", target.profile)
                    else -> Unit
                }
            }
            publish()
        }
        restoreDraft()
        val root = preferredRoot ?: (target as? ConversationTarget.Session)?.key ?: if (target is ConversationTarget.CanonicalChat) {
            withContext(Dispatchers.IO) { container.hermesCacheStore.cachedHermesBotChatRoot(account.id, http.connection.id, target.profile) }
        } else null
        if (root != null) {
            val cached = withContext(Dispatchers.IO) { container.hermesCacheStore.cachedHermesMessages(account.id, cacheScope(root), 500) }
            if (cached.isNotEmpty()) setMessages(cached, cached = true)
        }
        if (active) next.activate()
        if (knownParent != null && engine.connectionState == HermesConversation.ConnectionState.CONNECTED) {
            runCatching { HermesBranchParent.row(engine.storedKey ?: knownParent, target.profile, wire.client) }.getOrNull()?.let { parent ->
                _presentation.update { it.copy(forkParent = parent) }
            }
        }
        publish()
    }

    private fun publish() {
        val turn = coordinator ?: return
        _workspace.value = turn.workspace
        _uiState.update { it.copy(isUploadingAttachment = turn.isUploadingAttachments || restoring,
            isStartingChat = busy || uncertain, selectedProfileTitle = profile) }
        if (uncertain && turn.engine.generation > uncertainGeneration && turn.engine.connectionState == HermesConversation.ConnectionState.CONNECTED) {
            uncertain = false
            draftKey?.let { key -> drafts.setBotSubmissionUncertain(false, key); viewModelScope.launch { drafts.flush() } }
            _uiState.update { it.copy(isStartingChat = busy) }
        }
        _presentation.update { it.copy(revision = it.revision + 1) }
    }

    private suspend fun restoreDraft() {
        val key = draftKey ?: return
        restoring = true
        try {
            val draft = drafts.draft(key) ?: ChatDraft()
            val pending = draft.attachments.map { record ->
                val thumbnail = try {
                    record.file?.let { HermesAttachmentPreparer.thumbnail(drafts.attachmentData(it), record.name) }
                } catch (error: CancellationException) { throw error }
                catch (_: Exception) { null }
                pending(record).copy(thumbnailData = thumbnail)
            }
            if (draftKey != key) return
            _uiState.update { it.copy(draft = draft.text, draftTokens = parseComposerTokens(draft.text), draftQuotes = draft.quotes, pendingAttachments = pending) }
            uncertain = draft.botSubmissionUncertain
            uncertainGeneration = -1
            lease?.files?.addAll(draft.attachments.mapNotNull { it.file })
        } finally { restoring = false; publish() }
    }

    private fun pending(record: ChatDraftAttachment) = PendingAttachment(
        id = record.id, name = record.name, path = "draft:${record.id}", mime = record.mime,
        size = record.size, isImage = record.isImage, draftFileName = record.file,
    )

    private fun records(): List<ChatDraftAttachment> = _uiState.value.pendingAttachments.map {
        ChatDraftAttachment(it.id, it.name, it.mime, it.size, it.isImage, it.draftFileName)
    }

    private fun saveDraft() {
        val key = draftKey ?: return
        val value = _uiState.value
        drafts.setContent(value.draft, value.draftQuotes, key)
        drafts.setAttachments(records(), key)
    }

    override fun updateDraft(text: String) {
        _uiState.update { it.copy(draft = text, draftTokens = parseComposerTokens(text, knownSkills = it.skillSuggestions.associate { skill -> skill.slashName to skill.name }), sendErrorMessage = null, steerFailureMessage = null) }
        saveDraft()
    }
    override fun updateDraftTokens(tokens: List<ComposerToken>) {
        _uiState.update { it.copy(draft = tokens.toServerText(), draftTokens = tokens, sendErrorMessage = null) }; saveDraft()
    }
    override fun removeDraftQuote(id: String) { _uiState.update { it.copy(draftQuotes = it.draftQuotes.filterNot { quote -> quote.id == id }) }; saveDraft() }
    fun askAboutSelection(selection: String) { if (selection.isNotBlank()) { _uiState.update { it.copy(draftQuotes = it.draftQuotes + ComposerQuote(text = selection)) }; saveDraft() } }
    fun recallLastUserMessage(): String? = _uiState.value.messages.lastOrNull { it.role == "user" && !it.isSteerMessage }?.content?.let(BotAttachmentUpload::typedText)

    override fun send(mode: SendMode) {
        if (busy || uncertain || restoring) return
        val turn = coordinator ?: return
        val captured = _uiState.value
        if (captured.draft.isBlank() && captured.pendingAttachments.isEmpty()) return
        if (captured.isViewingCachedData) { retryLoad(); return }
        busy = true; publish()
        viewModelScope.launch {
            try {
                val parsed = SlashCommandExecutor.parse(captured.draft)
                if (parsed != null && captured.pendingAttachments.isEmpty() && mode == SendMode.DEFAULT) {
                    if (executeSlash(parsed.name, parsed.args, captured.draft)) return@launch
                }
                submit(captured, mappedMode(mode, captured.isStreaming, captured.pendingAttachments.isNotEmpty()))
            } catch (error: CancellationException) { throw error }
            catch (error: Throwable) { report(error) }
            finally { busy = false; publish() }
        }
    }

    private suspend fun submit(captured: ChatUiState, mode: BotPromptMode, promptOverride: String? = null, displayText: String? = null) {
        val turn = coordinator ?: throw BotFailure.Stale
        val key = draftKey ?: throw BotFailure.Stale
        val text = promptOverride ?: (captured.draftQuotes.map { quote -> quote.text.lines().joinToString("\n") { "> $it" } } + captured.draft).filter(String::isNotBlank).joinToString("\n\n")
        val files = if (mode.startsTurn) captured.pendingAttachments else emptyList()
        val attachments = files.map { attachment -> HermesChatTurnCoordinator.OutgoingAttachment(attachment.name, attachment.mime, attachment.isImage) {
            drafts.attachmentData(attachment.draftFileName ?: throw BotAttachmentFailure.Unreadable)
        } }
        val outcome = try {
            turn.submit(text, mode, attachments, displayText) {
                drafts.setBotSubmissionUncertain(true, key)
                drafts.flush()
            }
        } catch (error: Throwable) {
            if (error !is HermesChatTurnCoordinator.NotSent && !mode.definitelyRejected(error)) {
                uncertain = true; uncertainGeneration = turn.engine.generation
                turn.recoverAfterLostAnswer()
            } else drafts.setBotSubmissionUncertain(false, key)
            throw error
        }
        if (outcome == BotPromptOutcome.UNKNOWN) {
            uncertain = true; uncertainGeneration = turn.engine.generation
            turn.recoverAfterLostAnswer(); throw BotSettingFailure.UnknownOutcome
        }
        if (outcome == BotPromptOutcome.REJECTED) {
            drafts.setBotSubmissionUncertain(false, key)
            throw IllegalStateException("The host did not accept that message. Your draft is unchanged.")
        }
        // Keep this screen's local sent copies for preview, even after the durable draft releases them.
        val shown = _uiState.value.messages.lastOrNull { it.role == "user" && !it.isSteerMessage }?.attachments.orEmpty()
        files.zip(shown).forEach { (file, attachment) ->
            val path = attachment.path; val copy = file.draftFileName
            if (path != null && copy != null) sentFiles[path] = copy
        }
        val sentIDs = files.mapTo(HashSet()) { it.id }
        val quoteIDs = captured.draftQuotes.mapTo(HashSet()) { it.id }
        _uiState.update { it.copy(
            draft = if (it.draft == captured.draft) "" else it.draft,
            draftTokens = if (it.draft == captured.draft) emptyList() else it.draftTokens,
            draftQuotes = it.draftQuotes.filterNot { quote -> quote.id in quoteIDs },
            pendingAttachments = it.pendingAttachments.filterNot { file -> file.id in sentIDs }, sendErrorMessage = null,
        ) }
        saveDraft()
        draftKey?.let { drafts.setBotSubmissionUncertain(false, it) }
        drafts.flush()
    }

    private suspend fun executeSlash(name: String, argument: String, typed: String, depth: Int = 0): Boolean {
        if (depth > 5) throw IllegalStateException("The host returned a slash-command alias loop.")
        val turn = coordinator ?: throw BotFailure.Stale
        return when (val route = turn.slashCommands.route(name)) {
            is HermesSlashRoute.Refused -> throw IllegalStateException(route.message)
            HermesSlashRoute.Text -> false
            HermesSlashRoute.Held -> throw IllegalStateException("This command cannot change this chat from the host alone.")
            is HermesSlashRoute.Skill -> { applySlashReply(turn.slashCommands.expand(route.skill, argument, typed), typed, depth); true }
            HermesSlashRoute.Host -> { applySlashReply(turn.slashCommands.run(typed), typed, depth); true }
            is HermesSlashRoute.AppOwned -> {
                when (route.command.name) {
                    "new", "clear" -> {
                        consumeTyped(typed)
                        val model = if (route.command.name == "clear") turn.reportedModel else null
                        val folder = if (route.command.name == "clear") turn.cwd else null
                        newChat(profile, folder, model)
                    }
                    "sessions", "resume" -> { _presentation.update { it.copy(navigation = HermesChatNavigation.Sessions(profile, argument)) }; consumeTyped(typed) }
                    "stop" -> { stopStream(); consumeTyped(typed) }
                    "goal" -> {
                        if (_uiState.value.isStreaming && !HermesChatSideTasks.isGoalControl(argument)) {
                            pendingGoal = argument
                            _uiState.update { it.copy(queuedMessagesReceipt = "Goal queued until the current response finishes") }
                        } else executeGoal(argument)
                        consumeTyped(typed)
                    }
                    "btw" -> { require(argument.isNotBlank()) { "Type a side question after /btw." }; check(!turn.sideTasks.isAsking) { "Wait for the current side question to finish." }; turn.sideTasks.ask(argument); consumeTyped(typed) }
                    "background", "bg" -> { require(argument.isNotBlank()) { "Type a task after /background." }; turn.sideTasks.startBackground(argument); consumeTyped(typed) }
                    "title" -> { require(argument.isNotBlank()) { "Type a title after /title." }; val title = turn.slashCommands.rename(argument); _uiState.update { it.copy(displayTitle = title) }; consumeTyped(typed) }
                    "undo" -> { requireIdle(); turn.undo(); consumeTyped(typed) }
                    "retry" -> { requireIdle(); val row = lastRewindablePrompt() ?: throw IllegalStateException("There is no saved text prompt to retry."); prepareRewind(row, row.content.orEmpty(), false); consumeTyped(typed) }
                    "compress", "compact" -> {
                        requireIdle()
                        when (val result = turn.compress(argument.takeIf(String::isNotBlank))) {
                            is HermesChatTurnCoordinator.Compression.Compacted -> { notice(listOfNotNull("Context compressed.", result.headline, result.tokenLine, argument.takeIf(String::isNotBlank)).joinToString("\n\n")); consumeTyped(typed) }
                            is HermesChatTurnCoordinator.Compression.Unchanged -> notice(result.reason ?: "The context was not changed.")
                        }
                    }
                    "branch", "fork" -> { requireIdle(); val key = turn.branch(name = argument.takeIf(String::isNotBlank)); _presentation.update { it.copy(navigation = HermesChatNavigation.Session(profile, key)) }; consumeTyped(typed) }
                    "model" -> { val model = turn.settings.model(argument) ?: throw IllegalStateException("Choose a model from the composer picker."); turn.settings.select(model); consumeTyped(typed) }
                    "reasoning" -> { require(argument in turn.settings.effortLevels) { "Choose a supported reasoning effort from the composer." }; turn.settings.selectEffort(argument); consumeTyped(typed) }
                    "personality" -> { require(argument.isNotBlank()) { "Choose a personality from the composer." }; turn.settings.askPersonality(argument); consumeTyped(typed) }
                    "yolo" -> { turn.requests.toggleApprovalBypass(); consumeTyped(typed) }
                    else -> return false
                }
                true
            }
        }
    }

    private suspend fun applySlashReply(reply: HermesSlashReply, typed: String, depth: Int) {
        when (reply) {
            is HermesSlashReply.Notice -> { notice(reply.text); consumeTyped(typed) }
            is HermesSlashReply.Send -> {
                reply.notice?.let(::notice)
                val before = _uiState.value
                submit(before, if (before.isStreaming) BotPromptMode.QUEUE else BotPromptMode.SEND, reply.message, reply.shown)
            }
            is HermesSlashReply.Prefill -> { reply.notice?.let(::notice); updateDraft(reply.message) }
            is HermesSlashReply.Alias -> {
                val args = SlashCommandExecutor.parse(typed)?.args.orEmpty()
                if (!executeSlash(reply.target, args, "/${reply.target} $args".trimEnd(), depth + 1)) throw BotFailure.Unsupported
                consumeTyped(typed)
            }
        }
    }

    private suspend fun executeGoal(argument: String) {
        val turn = coordinator ?: throw BotFailure.Stale
        when (val reply = turn.sideTasks.dispatchGoal(argument)) {
            is HermesGoalReply.Exec -> notice(reply.output)
            is HermesGoalReply.Send -> {
                reply.notice?.let(::notice)
                val commandOnly = _uiState.value.copy(draft = "", draftQuotes = emptyList(), pendingAttachments = emptyList())
                submit(commandOnly, BotPromptMode.QUEUE, reply.message, reply.display)
            }
        }
    }

    fun goalCommand(argument: String) = launchAction { executeGoal(argument) }
    private fun consumeTyped(typed: String) { if (_uiState.value.draft == typed) updateDraft("") }
    private fun notice(text: String) { if (text.isNotBlank()) _uiState.update { it.copy(pinnedLocalNotices = it.pinnedLocalNotices + text) } }
    private fun requireIdle() { check(_uiState.value.activeStreamId == null) { "Wait for the active response to finish." } }

    override fun stopStream() {
        val turn = coordinator ?: return
        val stream = turn.activeStreamID ?: return
        pendingStop = turn.engine.generation to stream
        if (turn.stopNeedsConfirmation) { _presentation.update { it.copy(stopConfirmation = true) }; return }
        confirmStop()
    }
    fun cancelStop() { pendingStop = null; _presentation.update { it.copy(stopConfirmation = false) } }
    fun confirmStop() {
        val action = pendingStop ?: return
        cancelStop()
        launchAction {
            val turn = coordinator ?: throw BotFailure.Stale
            if (turn.engine.generation != action.first || turn.activeStreamID != action.second) throw BotFailure.Stale
            _uiState.update { it.copy(isCancellingStream = true) }
            try { turn.interrupt(action.second) } catch (error: Throwable) { _uiState.update { it.copy(isCancellingStream = false) }; throw error }
        }
    }
    fun cancelUpload() { coordinator?.cancelAttachmentUpload() }

    fun editablePrompt(message: ChatMessage): ChatMessage? {
        if (coordinator?.policy?.offersHistoryActions != true || _uiState.value.isStreaming) return null
        val rows = _uiState.value.messages
        val index = rows.indexOfFirst { it.id == message.id }
        if (index < 0) return null
        val prompt = rows.take(index + 1).lastOrNull { it.role == "user" && !it.isSteerMessage } ?: return null
        return prompt.takeIf { it.rowID != null && !it.isCompacted && it.attachments.isNullOrEmpty() && !it.content.isNullOrBlank() }
    }
    private fun lastRewindablePrompt() = _uiState.value.messages.lastOrNull { it.role == "user" && !it.isSteerMessage }?.let(::editablePrompt)
    fun edit(message: ChatMessage) { editablePrompt(message)?.let { prepareRewind(it, it.content.orEmpty(), true) } }
    fun regenerate(message: ChatMessage) { editablePrompt(message)?.let { prepareRewind(it, it.content.orEmpty(), false) } }
    private fun prepareRewind(message: ChatMessage, text: String, editing: Boolean) {
        val rowID = message.rowID ?: return
        val rows = _uiState.value.messages
        val index = rows.indexOfFirst { it.id == message.id }
        _presentation.update { it.copy(rewind = HermesChatPresentation.Rewind(rowID, text, editing,
            rows.drop(index + 1).any { row -> row.role == "user" && !row.isSteerMessage }, coordinator?.engine?.generation ?: return)) }
    }
    fun cancelRewind() { _presentation.update { it.copy(rewind = null) } }
    fun confirmRewind(text: String) {
        val action = _presentation.value.rewind ?: return
        if (busy || uncertain) return
        cancelRewind()
        busy = true; publish()
        launchAction {
            try {
                requireIdle()
                val turn = coordinator ?: throw BotFailure.Stale
                if (turn.engine.generation != action.generation) throw BotFailure.Stale
                turn.rewind(action.rowID, text, action.laterPrompts, action.showsPrompt)
            } catch (error: Throwable) {
                if (action.editing) updateDraft(listOf(_uiState.value.draft, text).filter(String::isNotBlank).joinToString("\n\n"))
                if (error is BotSettingFailure.Rejected && error.code == 4018) coordinator?.retryWasRefused(action.rowID)
                if (error !is BotSettingFailure && error !is HermesChatTurnCoordinator.NotSent) coordinator?.recoverAfterLostAnswer()
                throw error
            } finally { busy = false }
        }
    }
    fun retryFailedTurn() {
        val turn = coordinator ?: return
        if (turn.engine.connectionState != HermesConversation.ConnectionState.CONNECTED || _uiState.value.isViewingCachedData || uncertain) return
        val target = turn.activity.retryTarget ?: return
        _presentation.update { it.copy(rewind = HermesChatPresentation.Rewind(target.rowID, target.text, false, false, turn.engine.generation, target.showsPrompt)) }
    }
    fun branch(message: ChatMessage? = null) = launchAction {
        requireIdle()
        val key = coordinator?.branch(message?.rowID) ?: throw BotFailure.Stale
        _presentation.update { it.copy(navigation = HermesChatNavigation.Session(profile, key)) }
    }
    fun duplicate() = launchAction {
        requireIdle()
        val key = coordinator?.engine?.storedKey ?: throw BotFailure.Stale
        val copy = HermesSessionDuplication.duplicate(key, profile, _uiState.value.displayTitle, wire.client)
        _presentation.update { it.copy(navigation = HermesChatNavigation.Session(profile, copy.key)) }
    }
    fun navigationConsumed() { _presentation.update { it.copy(navigation = null) } }
    fun identificationConsumed() { _presentation.update { it.copy(identified = null) } }
    fun openSession(profile: String, key: String) = launchAction {
        saveDraft(); drafts.flush(); opensNew = false; acceptedFirstPrompt = true
        resetTranscript()
        open(ConversationTarget.Session(profile, key), initialContext = false)
    }
    private suspend fun newChat(profile: String, cwd: String? = null, model: HermesCall.Model? = null) {
        saveDraft(); drafts.flush(); opensNew = true; acceptedFirstPrompt = false
        resetTranscript()
        open(ConversationTarget.New(profile, cwd, model), initialContext = false)
    }
    private fun resetTranscript() {
        _uiState.update { current -> ChatUiState(
            serverCacheNamespace = current.serverCacheNamespace, isLoading = true,
            showsThinkingAndToolCards = current.showsThinkingAndToolCards,
            thinkingCardsStartExpanded = current.thinkingCardsStartExpanded,
            toolCardsStartExpanded = current.toolCardsStartExpanded, hidesAttachmentPaths = true,
            showsAssistantTurnTimestamps = current.showsAssistantTurnTimestamps, showsResponseSpeed = current.showsResponseSpeed,
            wrapsCodeBlockLines = current.wrapsCodeBlockLines, rtlChatLayoutEnabled = current.rtlChatLayoutEnabled,
            favoriteModelKeys = current.favoriteModelKeys, recentModelKeys = current.recentModelKeys,
        ) }
    }

    override fun selectModel(option: ModelCatalogOption) = launchAction { coordinator?.settings?.select(option) }
    override fun toggleFavoriteModel(option: ModelCatalogOption) = launchAction { val keys = container.modelFavorites.toggleFavorite(option); _uiState.update { it.copy(favoriteModelKeys = keys) } }
    override fun refreshModelCatalogForPickerOpen() { coordinator?.settings?.refresh() }
    override fun selectReasoningEffort(effort: String) = launchAction { coordinator?.settings?.selectEffort(effort) }
    override fun selectWorkspace(path: String) = launchAction { moveFolder(path) }
    override fun loadWorkspaceSuggestions(prefix: String) = launchAction {
        val choices = if (prefix.isBlank()) folderChoices() else completeFolder(prefix)
        _uiState.update { it.copy(workspaceSuggestions = choices) }
    }
    override fun switchProfile(profile: ProfileSummary, startNewSession: Boolean) = launchAction {
        if (coordinator?.policy?.picksProfile != true) return@launchAction
        val name = profile.name ?: throw BotFailure.Unsupported
        HermesProfilePreference.save(context, account.id, name)
        newChat(name)
    }

    override fun uploadAttachment(bytes: ByteArray, filename: String, previewData: ByteArray?) {
        viewModelScope.launch {
            uploadMutex.withLock {
                try {
                    val prepared = HermesAttachmentPreparer.prepare(bytes, filename)
                    val current = _uiState.value.pendingAttachments
                    HermesAttachmentPreparer.admit(current.size, current.sumOf { it.size?.toLong() ?: 0 }, prepared.data.size.toLong())
                    val key = draftKey ?: throw BotFailure.Stale
                    val lease = lease ?: throw BotFailure.Stale
                    val id = UUID.randomUUID().toString()
                    val file = drafts.stageAttachment(prepared.data, prepared.name, lease, id, BotAttachmentUpload.MAXIMUM_FILE_BYTES)
                    if (draftKey != key) throw BotFailure.Stale
                    val pending = PendingAttachment(id, prepared.name, "draft:$id", prepared.mime, prepared.data.size, prepared.image,
                        previewData ?: prepared.data.takeIf { prepared.image }, file)
                    _uiState.update { it.copy(pendingAttachments = it.pendingAttachments + pending, uploadAttachmentErrorMessage = null) }
                    saveDraft(); drafts.flush()
                } catch (error: CancellationException) { throw error }
                catch (error: Throwable) { setUploadAttachmentError(error.message) }
            }
        }
    }
    override fun removePendingAttachment(id: String) {
        val removed = _uiState.value.pendingAttachments.firstOrNull { it.id == id } ?: return
        _uiState.update { it.copy(pendingAttachments = it.pendingAttachments.filterNot { file -> file.id == id }) }
        saveDraft()
        removed.draftFileName?.let { file -> lease?.files?.remove(file); viewModelScope.launch { drafts.flush(); drafts.deleteAttachmentIfUnreferenced(file) } }
    }
    override fun clearPendingAttachments() { _uiState.value.pendingAttachments.map { it.id }.forEach(::removePendingAttachment) }
    override fun setUploadAttachmentError(message: String?) { _uiState.update { it.copy(uploadAttachmentErrorMessage = message) } }
    override fun importVoiceNote(bytes: ByteArray, filename: String) = launchAction {
        _uiState.update { it.copy(isProcessingVoiceNote = true) }
        try {
            val response = transcribe(bytes)
            response.transcript?.takeIf(String::isNotBlank)?.let { text -> updateDraft(listOf(_uiState.value.draft, text).filter(String::isNotBlank).joinToString("\n")) }
        } finally { _uiState.update { it.copy(isProcessingVoiceNote = false) } }
    }
    suspend fun transcribe(audio: ByteArray): TranscribeResponse = HermesTranscription.transcribe(audio, profile, http)
    suspend fun speech(text: String): ByteArray = wire.speech(text, profile)
    suspend fun attachmentData(path: String): ByteArray? = try {
        val draft = _uiState.value.pendingAttachments.firstOrNull { it.path == path }?.draftFileName
        val local = draft ?: sentFiles[path]
        if (local != null) drafts.attachmentData(local) else readArtifact(path, whole = false)
    } catch (error: CancellationException) { throw error }
    catch (error: Throwable) { report(error); null }

    private suspend fun readArtifact(path: String, whole: Boolean): ByteArray {
        val remote = path.toHttpUrlOrNull()
        val address = http.connection.address
        if (remote != null && (remote.scheme != address.scheme || remote.host != address.host || remote.port != address.port)) {
            if (remote.username.isNotEmpty() || remote.password.isNotEmpty()) throw BotArtifactFailure.InvalidReference
            return BotArtifactDownload.data(publicMedia, Request.Builder().url(remote).get().build(),
                if (whole) null else BotAttachmentUpload.MAXIMUM_FILE_BYTES)
        }
        return coordinator?.attachmentData(path, whole) ?: throw BotFailure.Stale
    }

    /** The shared image/lightbox consumes a file URI; bytes arrive only through the scoped download above. */
    suspend fun attachmentUrl(path: String): String? {
        previewFiles[path]?.takeIf(File::isFile)?.let { return it.toURI().toString() }
        val bytes = attachmentData(path) ?: return null
        val attempt = coordinator?.engine?.generation
        val file = withContext(Dispatchers.IO) {
            check(previewDirectory.isDirectory || previewDirectory.mkdirs()) { "Could not create a private preview." }
            val extension = path.substringBefore('?').substringAfterLast('.', "bin").takeIf { it.length in 1..12 && it.all(Char::isLetterOrDigit) } ?: "bin"
            File(previewDirectory, "${UUID.randomUUID()}.$extension").also { it.writeBytes(bytes) }
        }
        if (attempt != coordinator?.engine?.generation) { withContext(Dispatchers.IO) { file.delete() }; return null }
        previewFiles[path] = file
        return file.toURI().toString()
    }
    suspend fun exportAttachment(path: String): ByteArray = readArtifact(path, whole = true)
    val workspaceFiles: HermesWorkspaceFileClient? get() = workspace.value?.let { HermesWorkspaceFileClient(it, http) }
    fun workspaceGit(showsCachedData: () -> Boolean): HermesGitClient? {
        val own = workspace.value ?: return null
        return HermesGitClient(own, http, writeMessage = ::commitMessage, writeOwner = {
            val generation = coordinator?.engine?.generation ?: throw BotFailure.Stale
            val validate: () -> Unit = {
                val turn = coordinator ?: throw BotFailure.Stale
                if (generation != turn.engine.generation || workspace.value != own || showsCachedData()) throw BotFailure.Stale
                if (!turn.isIdle || busy) throw HermesGitRefusal.TurnRunning
            }
            validate(); validate
        })
    }
    suspend fun commitMessage(diff: String, recent: String, avoid: String?): String {
        val turn = coordinator ?: throw BotFailure.Stale
        return turn.engine.request(HermesCall.CommitMessage(diff, recent, avoid, turn.engine.runtime, profile), turn.engine.generation)["text"].text.orEmpty()
    }
    suspend fun completeFilePaths(query: String): List<ComposerFileSuggestion> {
        val turn = coordinator ?: return emptyList()
        val runtime = turn.engine.runtime ?: return emptyList()
        val reply = turn.engine.request(HermesCall.CompletePath(query.ifEmpty { "." }, runtime, profile, true), turn.engine.generation)
        return fileSuggestions(reply)
    }
    suspend fun folderChoices(): List<String> {
        val turn = coordinator ?: return emptyList()
        val tree = turn.engine.request(HermesCall.ProjectsTree(profile), turn.engine.generation)
        val page = wire.sessionPage(profile, 0, false)
        return HermesFolderCompletion.choices(turn.cwd, HermesProjectTree(tree), page.rows, profile)
    }
    suspend fun completeFolder(word: String): List<String> {
        if (!HermesFolderCompletion.completes(word)) return emptyList()
        val turn = coordinator ?: return emptyList()
        val reply = turn.engine.request(HermesCall.CompleteFolder(word, profile), turn.engine.generation)
        return HermesFolderCompletion.suggestions(reply, word).folders
    }
    suspend fun moveFolder(to: String) {
        val turn = coordinator ?: throw BotFailure.Stale
        val runtime = turn.engine.runtime ?: throw BotFailure.Stale
        val key = turn.engine.storedKey ?: throw BotFailure.Stale
        turn.engine.write(HermesCall.SessionWorkspaceMove(profile, key, to), turn.engine.generation, runtime) { if (!turn.isIdle) throw HermesGitRefusal.TurnRunning }
        // session.info is the authoritative folder; reconnect re-reads it if the host omitted a frame.
        turn.recoverAfterLostAnswer()
    }

    suspend fun applySharedImport(value: SharedImport): Boolean {
        initialization.join()
        val key = draftKey ?: return false
        return try {
            val files = value.attachments.map { attachment ->
                val bytes = withContext(Dispatchers.IO) { attachment.file.readBytes() }
                val prepared = HermesAttachmentPreparer.prepare(bytes, attachment.filename)
                ChatDraftShareFile(prepared.data, prepared.name, prepared.mime, prepared.image)
            }
            drafts.appendShare(value.id, value.text, files, key, BotAttachmentUpload.MAXIMUM_FILE_BYTES) { records ->
                if (records.size > 8 || records.sumOf { it.size?.toLong() ?: 0 } > BotAttachmentUpload.MAXIMUM_TOTAL_BYTES) throw BotAttachmentFailure.Limit
            }
            restoreDraft()
            value.warnings.forEach(::notice)
            true
        } catch (error: CancellationException) { throw error }
        catch (error: Throwable) { report(error); false }
    }

    fun loadOlderMessages() = launchAction { coordinator?.loadOlderHistory() }
    fun retryLoad() = launchAction { coordinator?.activate(); coordinator?.retryHistoryIfFailed() }
    fun resumeForForeground() { active = true; viewModelScope.launch { initialization.join(); coordinator?.activate() } }
    fun suspendForBackground() { active = false; saveDraft(); viewModelScope.launch { drafts.flush(); coordinator?.suspendConnection() } }
    fun answerApproval(choice: BotApprovalChoice) = requestAction { turn, action -> turn.requests.respond(action, choice) }
    fun answerQuestion(answers: List<BotQuestionAnswer>) = requestAction { turn, action -> turn.requests.answerQuestion(action, answers) }
    fun skipQuestion() = requestAction { turn, action -> turn.requests.skipQuestion(action) }
    fun answerCredential(value: String) = requestAction { turn, action -> turn.requests.answerCredential(action, value) }
    fun answerConnection(answer: BotConnectionAnswer) = requestAction { turn, action -> turn.requests.respondToConnection(action, answer) }
    fun skipApprovals() = requestAction { turn, action -> turn.requests.skipApprovals(action) }
    private fun requestAction(block: suspend (HermesChatTurnCoordinator, HermesAnswerAction) -> Unit) {
        val turn = coordinator ?: return
        val action = turn.requests.prepareAnswer() ?: return
        launchAction { block(turn, action) }
    }
    private fun launchAction(block: suspend () -> Unit) { viewModelScope.launch { try { block() } catch (error: CancellationException) { throw error } catch (error: Throwable) { report(error) } finally { publish() } } }
    private fun report(error: Throwable) { val cause = (error as? HermesChatTurnCoordinator.NotSent)?.underlying ?: error; _uiState.update { it.copy(sendErrorMessage = cause.message ?: "The host could not complete that request.") } }
    private fun setMessages(messages: List<ChatMessage>, cached: Boolean) { _uiState.update { it.copy(messages = messages, transcriptMessages = messages.mapIndexed { index, row -> TranscriptMessage(index, "cached:$index", row.id, row) }, isViewingCachedData = cached, isLoading = false) } }
    override fun onCleared() {
        saveDraft()
        val turn = coordinator
        container.appScope.launch {
            drafts.flush()
            withContext(Dispatchers.Main.immediate) { turn?.suspendConnection() }
            withContext(Dispatchers.IO) { previewDirectory.deleteRecursively() }
        }
        super.onCleared()
    }

    companion object {
        /** Port of `Features/Bots/BotFilePathSearch.swift`: only reference-safe host file rows. */
        fun fileSuggestions(reply: BotJSON): List<ComposerFileSuggestion> = reply["items"].list.orEmpty().mapNotNull { item ->
            val text = item["text"].text?.takeIf(String::isNotEmpty) ?: return@mapNotNull null
            val raw = text.removePrefix("@file:").removePrefix("@folder:")
            val path = raw.trimEnd('/')
            if (path.isEmpty() || path.startsWith('@') || path.startsWith('/') || path.any(Char::isWhitespace) || ".." in path.split('/')) return@mapNotNull null
            ComposerFileSuggestion(path, path.substringAfterLast('/'), path.substringBeforeLast('/', ""), item["meta"].text == "dir" || raw.endsWith('/'))
        }

        /** Swift busy send mapping: files can join a fresh/queued turn, never a steer or redirect. */
        fun mappedMode(mode: SendMode, running: Boolean, hasAttachments: Boolean): BotPromptMode {
            if (!running) return BotPromptMode.SEND
            return when (mode) {
                SendMode.DEFAULT -> if (hasAttachments) BotPromptMode.QUEUE else BotPromptMode.STEER
                SendMode.QUEUE -> BotPromptMode.QUEUE
                SendMode.STEER -> if (hasAttachments) BotPromptMode.QUEUE else BotPromptMode.STEER
                SendMode.STOP_AND_SEND -> BotPromptMode.REDIRECT
            }
        }
        fun factory(container: AppContainer, context: Context, account: ServerAccount, sessionKey: String?, newChat: Boolean,
            profile: String? = null, botId: String? = null, parentKey: String? = null, lineageRoot: String? = null,
            botChatRoot: String? = null, linkedRoot: String? = null): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val saved = container.botConnectionStore.load(account.id) ?: throw BotFailure.SignInExpired
                HermesChatViewModel(context.applicationContext, container, account, container.hermesConnections.connection(saved, account.id),
                    sessionKey, newChat, profile, botId, parentKey, lineageRoot, botChatRoot, linkedRoot)
            }
        }
    }
}
