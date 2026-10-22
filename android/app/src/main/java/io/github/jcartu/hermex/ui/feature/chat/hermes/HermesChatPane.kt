package io.github.jcartu.hermex.ui.feature.chat.hermes

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.window.layout.FoldingFeature
import coil.ImageLoader
import io.github.jcartu.hermex.HermexApplication
import io.github.jcartu.hermex.core.media.AssistantSpeaker
import io.github.jcartu.hermex.core.media.DocumentPreview
import io.github.jcartu.hermex.model.ChatMessage
import io.github.jcartu.hermex.model.ServerAccount
import io.github.jcartu.hermex.model.hermes.*
import io.github.jcartu.hermex.net.hermes.BotApprovalChoice
import io.github.jcartu.hermex.net.hermes.BotAttachmentUpload
import io.github.jcartu.hermex.net.hermes.HermesWorkspaceContext
import io.github.jcartu.hermex.ui.adaptive.HingeAwarePaneRow
import io.github.jcartu.hermex.ui.adaptive.LocalFoldState
import io.github.jcartu.hermex.ui.adaptive.LocalPaneCount
import io.github.jcartu.hermex.ui.components.InAppBrowser
import io.github.jcartu.hermex.ui.feature.chat.*
import io.github.jcartu.hermex.ui.feature.git.hermes.HermesGitWorkspaceRoute
import io.github.jcartu.hermex.ui.feature.git.hermes.HermesGitNavigation
import io.github.jcartu.hermex.ui.feature.workspace.hermes.HermesWorkspaceRoute
import io.github.jcartu.hermex.ui.feature.workspace.hermes.HermesWorkspaceNavigation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Port of `Features/Chat/ChatView.swift` Hermes composition and HermesSessionChat. The screen
 * owns one Hermes ViewModel and reuses ChatTranscript, ChatComposer and the request overlays.
 * Phones/dual panes stack transcript and composer; trifold splits them at the second hinge.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HermesChatPane(
    account: ServerAccount,
    sessionKey: String?,
    newChat: Boolean,
    modifier: Modifier = Modifier,
    profile: String? = null,
    parentKey: String? = null,
    lineageRoot: String? = null,
    botChatRoot: String? = null,
    onOpenSessions: ((profile: String?, query: String) -> Unit)? = null,
    onOpenSession: ((profile: String, key: String) -> Unit)? = null,
    botId: String? = null,
    linkedRoot: String? = null,
    onChatReplaced: (() -> Unit)? = null,
    onOpenBotEditor: ((profile: String) -> Unit)? = null,
    onUpdateSignIn: (() -> Unit)? = null,
    onOpenFiles: ((HermesWorkspaceContext) -> Unit)? = null,
    onOpenGit: ((HermesWorkspaceContext) -> Unit)? = null,
    titleSlot: (@Composable (roster: List<BotJSON>, face: HermesTitleFace, isConnected: Boolean) -> Unit)? = null,
    mentionAnnotation: ((String) -> String)? = null,
    loadMentionSuggestions: (suspend (String) -> List<ComposerMentionSuggestion>)? = null,
    mentionReferences: Map<String, ComposerMentionSuggestion> = emptyMap(),
    autoStartsVoiceInput: Boolean = false,
    onSessionIdentified: ((profile: String, key: String) -> Unit)? = null,
) {
    val context = LocalContext.current
    val container = remember(context) { (context.applicationContext as HermexApplication).container }
    val saved = remember(account.id) { container.botConnectionStore.load(account.id) }
    if (saved == null) {
        Column(modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Sign in to this Hermes host to open the conversation.")
            Button(onClick = { onUpdateSignIn?.invoke() ?: container.authManager.hermesSignInRejected(account.id) }) { Text("Update sign-in") }
        }
        return
    }
    val modelKey = remember(account.id, saved.id, sessionKey, newChat, profile, botId, parentKey, lineageRoot, botChatRoot, linkedRoot) {
        buildString {
            append("hermes-chat:")
            for (part in listOf(account.id, saved.id, sessionKey, newChat.toString(), profile, botId, parentKey, lineageRoot, botChatRoot, linkedRoot)) {
                if (part == null) append("-1:") else append(part.length).append(':').append(part)
            }
        }
    }
    val model: HermesChatViewModel = viewModel(
        key = modelKey,
        factory = HermesChatViewModel.factory(container, context, account, sessionKey, newChat, profile, botId, parentKey, lineageRoot, botChatRoot, linkedRoot),
    )
    val state by model.uiState.collectAsStateWithLifecycle()
    val presentation by model.presentation.collectAsStateWithLifecycle()
    val workspace by model.workspace.collectAsStateWithLifecycle()
    val workspaceGit = remember(model, workspace) { model.workspaceGit { model.uiState.value.isViewingCachedData } }
    LaunchedEffect(model, workspace, workspaceGit) {
        val currentWorkspace = workspace
        if (currentWorkspace == null) {
            HermesWorkspaceNavigation.clear(account.id)
            HermesGitNavigation.clear(account.id)
        } else {
            HermesWorkspaceNavigation.open(currentWorkspace)
            workspaceGit?.let(HermesGitNavigation::open)
        }
    }
    val settings by container.settings.settings.collectAsStateWithLifecycle(initialValue = io.github.jcartu.hermex.core.prefs.SettingsRepository.Settings())
    val turn = presentation.coordinator
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val lifecycle = LocalLifecycleOwner.current
    val imageLoader = remember(context, saved.id) { ImageLoader.Builder(context).crossfade(false).build() }
    val speaker = remember(model) { AssistantSpeaker(context, synthesize = model::speech) }
    val transcriptListState = rememberLazyListState()
    var showWorkers by rememberSaveable { mutableStateOf(false) }
    var expandedBtw by rememberSaveable { mutableStateOf(false) }
    var delegationResult by remember { mutableStateOf<HermesDelegationCompletion?>(null) }
    var showFolder by rememberSaveable { mutableStateOf(false) }
    var selectedFolder by remember { mutableStateOf<String?>(null) }
    var showPersonality by rememberSaveable { mutableStateOf(false) }
    var filesOpen by rememberSaveable { mutableStateOf(false) }
    var gitOpen by rememberSaveable { mutableStateOf(false) }
    var actionError by remember { mutableStateOf<String?>(null) }

    fun perform(block: suspend () -> Unit) {
        scope.launch {
            try { block() }
            catch (error: CancellationException) { throw error }
            catch (error: Throwable) { actionError = error.message ?: "The host could not complete that request." }
        }
    }
    SideEffect { model.mentionAnnotation = mentionAnnotation }
    DisposableEffect(model, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) model.resumeForForeground()
            if (event == Lifecycle.Event.ON_STOP) model.suspendForBackground()
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer); model.suspendForBackground(); speaker.release(); imageLoader.shutdown() }
    }
    val sharedImport by container.sharedImports.pending.collectAsStateWithLifecycle()
    LaunchedEffect(sharedImport?.id, model, newChat, botId) {
        if (newChat && botId == null) {
            sharedImport?.let { if (model.applySharedImport(it)) container.sharedImports.acknowledge(it.id) }
        }
    }
    LaunchedEffect(presentation.navigation) {
        when (val navigation = presentation.navigation) {
            is HermesChatNavigation.Session -> if (onOpenSession != null) onOpenSession(navigation.profile, navigation.key) else model.openSession(navigation.profile, navigation.key)
            is HermesChatNavigation.Sessions -> onOpenSessions?.invoke(navigation.profile, navigation.query)
            null -> Unit
        }
        if (presentation.navigation != null) model.navigationConsumed()
    }
    LaunchedEffect(presentation.identified) {
        presentation.identified?.let { (profile, key) -> onSessionIdentified?.invoke(profile, key); model.identificationConsumed() }
    }
    LaunchedEffect(turn?.engine?.linkedRootIsStale) { if (turn?.engine?.linkedRootIsStale == true) onChatReplaced?.invoke() }
    val onOpenWeb: (String) -> Unit = { InAppBrowser.open(context, it) }
    val openFile: (String) -> Unit = { path -> perform {
        model.attachmentData(path)?.let { bytes ->
            val result = DocumentPreview.open(context, path.substringAfterLast('/'), bytes = bytes)
            if (result is io.github.jcartu.hermex.core.media.DocumentPreviewOpenResult.Rejected) actionError = result.message
        }
    } }
    val toolPaneCount = maxOf(1, LocalPaneCount.current - 1)
    if (filesOpen && workspace != null) {
        BackHandler { filesOpen = false }
        Column(modifier.fillMaxSize()) {
            TextButton(onClick = { filesOpen = false }) { Text("Back to chat") }
            CompositionLocalProvider(LocalPaneCount provides toolPaneCount) {
                HermesWorkspaceRoute(workspace!!, model.http, Modifier.weight(1f))
            }
        }
        return
    }
    if (gitOpen) {
        val client = workspaceGit
        if (client != null) {
            BackHandler { gitOpen = false }
            Column(modifier.fillMaxSize()) {
                TextButton(onClick = { gitOpen = false }) { Text("Back to chat") }
                CompositionLocalProvider(LocalPaneCount provides toolPaneCount) {
                    HermesGitWorkspaceRoute(client)
                }
            }
            return
        }
    }
    val face = when {
        turn?.engine?.connectionState != HermesConversation.ConnectionState.CONNECTED -> HermesTitleFace.RESTING
        turn?.requests?.isWaiting == true -> HermesTitleFace.WAITING
        state.isStreaming -> HermesTitleFace.WORKING
        turn?.activity?.failure != null -> HermesTitleFace.FAILED
        else -> HermesTitleFace.RESTING
    }
    val requests = turn?.requests
    val request = requests?.onScreen
    val subject = if (turn?.policy == HermesChatPolicy.BOT_CHAT) HermesRequestSubject.BOT else HermesRequestSubject.HERMES
    val copy: (String) -> Unit = { clipboard.setText(AnnotatedString(it)) }
    val settled = turn?.activity?.settledPlan
    val settledAfter = settled?.afterRenderID(state.transcriptMessages)

    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    if (titleSlot != null) titleSlot(turn?.settings?.roster.orEmpty(), face, turn?.engine?.connectionState == HermesConversation.ConnectionState.CONNECTED)
                    else if (turn?.policy == HermesChatPolicy.BOT_CHAT) {
                        TextButton(onClick = { onOpenBotEditor?.invoke(model.profile) }, enabled = onOpenBotEditor != null) { Text(model.profile, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    } else Text(state.displayTitle.ifBlank { "New chat" }, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (workspace?.isLocal == true) {
                    IconButton(onClick = { val value = workspace ?: return@IconButton; if (onOpenFiles != null) onOpenFiles(value) else filesOpen = true }) { Icon(Icons.Outlined.Folder, "Files") }
                    IconButton(onClick = { val value = workspace ?: return@IconButton; if (onOpenGit != null) onOpenGit(value) else gitOpen = true }) { Icon(Icons.Outlined.Source, "Git") }
                }
                if (turn != null && !state.isViewingCachedData && turn.activity.delegatedWork.activeCount > 0) HermesDelegatedWorkButton(turn.activity.delegatedWork.activeCount, onClick = { showWorkers = true; perform { turn.activity.delegatedWork.refresh() } })
                var menu by remember { mutableStateOf(false) }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "Chat actions") }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem(text = { Text("Reconnect") }, onClick = { menu = false; model.retryLoad() })
                        if (turn?.policy?.offersHistoryActions == true) {
                            DropdownMenuItem(text = { Text("Fork conversation") }, enabled = !state.isStreaming, onClick = { menu = false; model.branch() })
                            DropdownMenuItem(text = { Text("Duplicate conversation") }, enabled = !state.isStreaming, onClick = { menu = false; model.duplicate() })
                            DropdownMenuItem(text = { Text("Sessions") }, onClick = { menu = false; onOpenSessions?.invoke(model.profile, "") })
                        }
                    }
                }
            }
            HermesChatPane(
                state = state,
                composerActions = model,
                modifier = Modifier.weight(1f),
                transcript = { paneModifier ->
                    key(turn?.engine?.generation) {
                        ChatTranscript(
                            state = state, listState = transcriptListState, attachmentUrlResolver = model::attachmentUrl,
                            attachmentDataResolver = model::attachmentData, imageLoader = imageLoader, onListen = speaker::speak,
                            onLoadOlder = model::loadOlderMessages, onRetry = model::retryLoad, modifier = paneModifier,
                            workspaceRoot = state.selectedWorkspacePath, onOpenWeb = onOpenWeb, onOpenWorkspaceFile = openFile,
                            onAskHermex = model::askAboutSelection, hapticsEnabled = settings.hapticsEnabled,
                            resolveRemoteImages = true,
                            leadingContent = if (presentation.forkParent != null || turn?.compaction?.anchorMessageID == null && turn?.compaction != null) ({
                                presentation.forkParent?.let { parent -> TextButton(onClick = { onOpenSession?.invoke(model.profile, parent.id) ?: model.openSession(model.profile, parent.id) }) { Text("Forked from ${parent.title ?: parent.id}") } }
                                turn?.compaction?.takeIf { it.anchorMessageID == null }?.let { MarkerMessageCard(ChatMarkerMessageClassifier.compressionReference(it.referenceText)) }
                            }) else null,
                            rowContent = { row, bubble ->
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    val completion = HermesDelegationCompletion.from(row.message)
                                    if (completion != null) HermesDelegationCompletionCard(completion, onOpen = { delegationResult = completion }) else bubble()
                                    if (turn?.policy?.offersHistoryActions == true && !state.isStreaming && row.message.rowID != null) {
                                        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                                            var actionsOpen by remember(row.message.id) { mutableStateOf(false) }
                                            Box {
                                                IconButton(onClick = { actionsOpen = true }, modifier = Modifier.size(30.dp)) { Icon(Icons.Outlined.MoreHoriz, "Message actions", Modifier.size(18.dp)) }
                                                DropdownMenu(actionsOpen, { actionsOpen = false }) {
                                                    if (model.editablePrompt(row.message) != null) {
                                                        if (row.message.role == "user") DropdownMenuItem(text = { Text("Edit message") }, onClick = { actionsOpen = false; model.edit(row.message) })
                                                        DropdownMenuItem(text = { Text("Regenerate response") }, onClick = { actionsOpen = false; model.regenerate(row.message) })
                                                    }
                                                    DropdownMenuItem(text = { Text("Fork from here") }, onClick = { actionsOpen = false; model.branch(row.message) })
                                                }
                                            }
                                        }
                                    }
                                    if (row.renderID == settledAfter && settled != null) HermesPlanRowView(settled.plan, copy)
                                    turn?.compaction?.takeIf { it.anchorMessageID == row.message.messageId }?.let { MarkerMessageCard(ChatMarkerMessageClassifier.compressionReference(it.referenceText)) }
                                }
                            },
                            trailingContent = if (turn != null) ({
                                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    turn.sideTasks.backgroundTasks.forEach { HermesBackgroundTaskCard(it) }
                                    HermesTurnOutcomeRow(turn.activity.failure, turn.activity.notice, turn.activity.retryTarget != null,
                                        !state.isStreaming && !state.isStartingChat && !state.isViewingCachedData && turn.engine.connectionState == HermesConversation.ConnectionState.CONNECTED,
                                        model::retryFailedTurn, { onOpenWeb(it.toString()) })
                                    requests?.withdrawal?.let { Text(it.message, style = MaterialTheme.typography.bodySmall) }
                                }
                            }) else null,
                        )
                    }
                },
                composer = { paneModifier ->
                    Column(paneModifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (turn?.needsSignIn == true) TextButton(onClick = { onUpdateSignIn?.invoke() ?: container.authManager.hermesSignInRejected(account.id) }) { Text("Update sign-in") }
                        ChatActiveRunStatusRow(state, model::stopStream, Modifier.fillMaxWidth().padding(horizontal = 12.dp), waitingForInput = requests?.isWaiting == true)
                        turn?.activity?.pinnedPlan?.let { HermesPlanStrip(it, {}, copy, Modifier.padding(horizontal = 12.dp)) }
                        if (request != null && requests != null && request !is BotPendingRequest.Approval) {
                            BotPendingRequestCard(request = request, subject = subject, isEnabled = requests.mayAnswer,
                                canStop = state.isStreaming, isAnswering = requests.answeringRequestID != null,
                                resolution = requests.onScreenResolution, onApprove = model::answerApproval,
                                onAnswer = model::answerQuestion, onSkip = model::skipQuestion, onCredential = model::answerCredential,
                                onStop = model::stopStream, onConnection = model::answerConnection, modifier = Modifier.padding(horizontal = 12.dp))
                            requests.errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp)) }
                        }
                        turn?.sideTasks?.btw?.let { btw ->
                            if (request != null) HermesBtwBar(btw, { expandedBtw = true }, turn.sideTasks::closeBtw, Modifier.padding(horizontal = 12.dp))
                            else HermesBtwCard(btw, 220.dp, { expandedBtw = true }, turn.sideTasks::closeBtw, Modifier.padding(horizontal = 12.dp))
                        }
                        state.contextWindow?.let { ContextWindowIndicator(it, Modifier.align(Alignment.End).padding(horizontal = 12.dp)) }
                        if (requests?.approvalBypass == true) TextButton(enabled = requests.mayTurnOffApprovalBypass, onClick = { perform { requests.turnOffApprovalBypass() } }) { Text("Approvals bypassed${if (requests.mayTurnOffApprovalBypass) " · Ask again" else " by the host"}") }
                        state.queuedMessagesReceipt?.let { Text(it, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 16.dp)) }
                        if (turn?.isUploadingAttachments == true) TextButton(onClick = model::cancelUpload) { Text("Cancel upload") }
                        turn?.settings?.pendingModel?.let { pending -> Text("${pending.displayName} switches after this response", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 16.dp)) }
                        ChatComposer(
                            state = state, actions = model, draftTokens = state.draftTokens, onDraftTokensChange = model::updateDraftTokens,
                            loadFileSuggestions = model::completeFilePaths, recallLastUserMessage = model::recallLastUserMessage,
                            hapticsEnabled = settings.hapticsEnabled, attachmentCacheNamespace = state.serverCacheNamespace,
                            uploadAttachmentWithPreview = model::uploadAttachment, importVoiceNote = model::importVoiceNote,
                            loadMentionSuggestions = loadMentionSuggestions, mentionReferences = mentionReferences,
                            slashScope = turn?.slashCommands?.scope ?: SlashCommandScope.WEBUI,
                            hostSlashCompletion = turn?.slashCommands?.completion,
                            onCompleteHostSlashArgument = { turn?.slashCommands?.complete(it) },
                            reasoningLevels = turn?.settings?.effortLevels,
                            allowedSendModes = if (state.pendingAttachments.isEmpty()) null else setOf(ComposerSendModeChoice.QUEUE, ComposerSendModeChoice.STOP_AND_SEND),
                            profileSelectionEnabled = turn?.policy?.picksProfile == true,
                            onOpenFolder = { showFolder = true }, onSelectFolder = { selectedFolder = it },
                            configurationTrailingContent = { enabled ->
                                turn?.settings?.fast?.let { fast -> FilterChip(selected = fast, onClick = { perform { turn.settings.selectFast(!fast) } }, enabled = enabled && turn.settings.mayChangeFast, label = { Text("Fast") }) }
                                AssistChip(onClick = { showPersonality = true }, enabled = enabled, label = { Text("Personality") })
                                turn?.settings?.sentEffort?.let { Text("Host sends $it", style = MaterialTheme.typography.labelSmall) }
                            },
                            goalControl = { if (turn != null) HermesGoalMenu(turn.sideTasks, model::goalCommand) },
                            maximumAttachmentBytes = BotAttachmentUpload.MAXIMUM_FILE_BYTES,
                            autoStartsVoiceInput = autoStartsVoiceInput,
                            onSendMode = { choice -> model.send(when (choice) {
                                ComposerSendModeChoice.QUEUE -> SendMode.QUEUE
                                ComposerSendModeChoice.STEER -> SendMode.STEER
                                ComposerSendModeChoice.STOP_AND_SEND -> SendMode.STOP_AND_SEND
                            }) },
                        )
                    }
                },
            )
        }
        if (request is BotPendingRequest.Approval && requests != null) {
            val approval = request.request
            ApprovalRequestOverlay(
                content = approval.overlayContent(requests.approvalCount.coerceAtLeast(1)),
                isResponding = requests.answeringRequestID != null, errorMessage = requests.errorMessage,
                onChoice = { model.answerApproval(BotApprovalChoice.valueOf(it.name)) }, onSkipAll = model::skipApprovals,
                identity = approval.requestID, isEnabled = requests.mayAnswer,
                showSkipAll = BotApprovalChoice.ONCE in approval.choices,
            )
        }
        if (expandedBtw && turn?.sideTasks?.btw != null) {
            BackHandler { expandedBtw = false }
            HermesBtwFullScreen(turn.sideTasks.btw!!, state.displayTitle, null, { expandedBtw = false }, { expandedBtw = false; turn.sideTasks.closeBtw() }, Modifier.fillMaxSize())
        }
    }
    if (presentation.stopConfirmation) AlertDialog(onDismissRequest = model::cancelStop,
        title = { Text("Stop this response?") }, text = { Text("The queued prompt and open requests will be discarded. This also stops work started in Desktop.") },
        confirmButton = { TextButton(onClick = model::confirmStop) { Text("Stop") } }, dismissButton = { TextButton(onClick = model::cancelStop) { Text("Keep working") } })
    presentation.rewind?.let { action ->
        var text by rememberSaveable(action.rowID) { mutableStateOf(action.text) }
        AlertDialog(onDismissRequest = model::cancelRewind, title = { Text(if (action.editing) "Edit message" else "Regenerate response?") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("This cuts the conversation before this prompt and sends it again. The removed replies${if (action.laterPrompts) " and later messages" else ""} cannot be restored.")
                if (action.editing) OutlinedTextField(text, { text = it }, minLines = 3, maxLines = 8)
            } }, confirmButton = { TextButton(enabled = text.isNotBlank(), onClick = { model.confirmRewind(text) }) { Text(if (action.editing) "Save and send" else "Regenerate") } },
            dismissButton = { TextButton(onClick = model::cancelRewind) { Text("Cancel") } })
    }
    turn?.settings?.confirmation?.let { confirmation -> AlertDialog(onDismissRequest = turn.settings::cancelConfirmation,
        title = { Text("Change model?") }, text = { Text(confirmation.message) },
        confirmButton = { TextButton(onClick = { perform { turn.settings.confirm() } }) { Text("Change model") } },
        dismissButton = { TextButton(onClick = turn.settings::cancelConfirmation) { Text("Cancel") } }) }
    turn?.settings?.pendingPersonality?.let { personality -> AlertDialog(onDismissRequest = turn.settings::cancelPersonality,
        title = { Text("Change Profile personality?") }, text = { Text("This writes $personality as the default for ${model.profile} and changes this chat too.") },
        confirmButton = { TextButton(onClick = { perform { turn.settings.setPersonality(personality) } }) { Text("Change") } },
        dismissButton = { TextButton(onClick = turn.settings::cancelPersonality) { Text("Cancel") } }) }
    if (showWorkers && turn != null) {
        val work = turn.activity.delegatedWork
        HermesDelegatedWorkView(HermesDelegatedWorkViewState.from(work), { showWorkers = false }, { perform { work.refresh() } },
            { perform { work.loadTail(it) } }, work::hideTail, work::prepareInterrupt, { perform { work.interrupt(it) } })
    }
    delegationResult?.let { HermesDelegationResultsSheet(it, { delegationResult = null }, copy) }
    if (showPersonality && turn != null) {
        var options by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
        LaunchedEffect(turn.engine.generation) { try { options = turn.settings.personalities() } catch (error: Exception) { if (error is CancellationException) throw error; actionError = error.message } }
        ModalBottomSheet(onDismissRequest = { showPersonality = false }) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp)) {
                Text("Personality", style = MaterialTheme.typography.titleLarge)
                (listOf("none" to "No personality") + options).forEach { (name, description) ->
                    TextButton(onClick = { showPersonality = false; turn.settings.askPersonality(name) }) { Column(Modifier.fillMaxWidth()) { Text(name); if (description.isNotBlank()) Text(description, style = MaterialTheme.typography.bodySmall) } }
                }
            }
        }
    }
    if (showFolder) {
        var query by rememberSaveable { mutableStateOf("") }
        var folders by remember { mutableStateOf<List<String>>(emptyList()) }
        LaunchedEffect(query) { try { folders = if (query.isEmpty()) model.folderChoices() else model.completeFolder(query) } catch (error: Exception) { if (error is CancellationException) throw error; actionError = error.message } }
        ModalBottomSheet(onDismissRequest = { showFolder = false }) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Working folder", style = MaterialTheme.typography.titleLarge)
                OutlinedTextField(query, { query = it }, label = { Text("Host folder") }, modifier = Modifier.fillMaxWidth())
                folders.forEach { folder -> TextButton(onClick = { selectedFolder = folder; showFolder = false }) { Text(folder) } }
            }
        }
    }
    selectedFolder?.let { folder -> AlertDialog(onDismissRequest = { selectedFolder = null }, title = { Text("Change working folder?") },
        text = { Text("Hermes will work in $folder from now on. No files move.") },
        confirmButton = { TextButton(enabled = !state.isStreaming, onClick = { selectedFolder = null; model.selectWorkspace(folder) }) { Text("Move") } },
        dismissButton = { TextButton(onClick = { selectedFolder = null }) { Text("Cancel") } }) }
    actionError?.let { error -> AlertDialog(onDismissRequest = { actionError = null }, title = { Text("Request not completed") }, text = { Text(error) }, confirmButton = { TextButton(onClick = { actionError = null }) { Text("Close") } }) }
}

/**
 * State-injected layout seam, shared by the real screen and Robolectric pane-count fixtures.
 * The callers supply presentation only; transcript and composer remain the existing reusable
 * composables. The ViewModel and saveable scroll state are never keyed by posture.
 */
@Composable
fun HermesChatPane(
    state: ChatUiState,
    composerActions: ChatComposerActions,
    modifier: Modifier = Modifier,
    transcript: (@Composable (Modifier) -> Unit)? = null,
    composer: (@Composable (Modifier) -> Unit)? = null,
) {
    val listState = rememberLazyListState()
    val transcriptRegion: @Composable (Modifier) -> Unit = transcript ?: { paneModifier ->
        ChatTranscript(state, listState, { null }, null, {}, {}, {}, paneModifier)
    }
    val composerRegion: @Composable (Modifier) -> Unit = composer ?: { paneModifier ->
        Column(paneModifier) {
            ChatActiveRunStatusRow(state, composerActions::stopStream)
            ChatComposer(state, composerActions, maximumAttachmentBytes = BotAttachmentUpload.MAXIMUM_FILE_BYTES)
        }
    }
    if (LocalPaneCount.current >= 3) {
        HingeAwarePaneRow(paneCount = 2, modifier = modifier) { index, paneModifier ->
            if (index == 0) transcriptRegion(paneModifier)
            else Box(paneModifier.imePadding()) {
                composerRegion(Modifier.align(Alignment.TopCenter).widthIn(max = 480.dp).fillMaxWidth().padding(top = 8.dp).verticalScroll(rememberScrollState()))
            }
        }
    } else {
        val hinge = LocalFoldState.current.features.firstOrNull { it.orientation == FoldingFeature.Orientation.HORIZONTAL && it.state == FoldingFeature.State.HALF_OPENED }
        var top by remember { mutableFloatStateOf(0f) }
        val density = LocalDensity.current
        Column(modifier.fillMaxSize().imePadding().onGloballyPositioned { top = it.positionInWindow().y }) {
            if (hinge == null) {
                transcriptRegion(Modifier.weight(1f).fillMaxWidth())
                composerRegion(Modifier.fillMaxWidth())
            } else {
                transcriptRegion(Modifier.fillMaxWidth().height(with(density) { (hinge.bounds.top - top).coerceAtLeast(0f).toDp() }))
                Spacer(Modifier.height(with(density) { hinge.bounds.height().toFloat().toDp() }))
                composerRegion(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()))
            }
        }
    }
}
