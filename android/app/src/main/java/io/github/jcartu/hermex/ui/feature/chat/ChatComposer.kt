package io.github.jcartu.hermex.ui.feature.chat

import android.graphics.Bitmap
import android.net.Uri
import android.view.HapticFeedbackConstants
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import io.github.jcartu.hermex.model.PendingAttachment
import io.github.jcartu.hermex.ui.feature.chat.hermes.HermesSlashCompletion
import kotlinx.coroutines.launch

/**
 * Native Android port of `Features/Chat/ChatComposerView.swift`.
 *
 * Optional callbacks isolate the composer-owned UI from ChatViewModel-owned
 * behavior while preserving the old call site. ChatPane supplies them from the
 * final ChatCore API (`send(mode)`, token updates, recall, file suggestions).
 */
@Composable
fun ChatComposer(
    state: ChatUiState,
    actions: ChatComposerActions,
    modifier: Modifier = Modifier,
    draftTokens: List<ComposerToken>? = null,
    onDraftTokensChange: ((List<ComposerToken>) -> Unit)? = null,
    loadFileSuggestions: (suspend (String) -> List<ComposerFileSuggestion>)? = null,
    onSendMode: ((ComposerSendModeChoice) -> Unit)? = null,
    recallLastUserMessage: (() -> String?)? = null,
    hapticsEnabled: Boolean = true,
    attachmentCacheNamespace: String? = null,
    uploadAttachmentWithPreview: ((ByteArray, String, ByteArray?) -> Unit)? = null,
    /** Direct Hermes supplies a Profile-bound host-STT importer; webui keeps [ChatComposerActions.importVoiceNote]. */
    importVoiceNote: ((ByteArray, String) -> Unit)? = null,
    loadMentionSuggestions: (suspend (String) -> List<ComposerMentionSuggestion>)? = null,
    mentionReferences: Map<String, ComposerMentionSuggestion> = emptyMap(),
    slashScope: SlashCommandScope = SlashCommandScope.WEBUI,
    hostSlashCompletion: HermesSlashCompletion? = null,
    onCompleteHostSlashArgument: (suspend (String?) -> Unit)? = null,
    reasoningLevels: List<String>? = null,
    allowedSendModes: Set<ComposerSendModeChoice>? = null,
    profileSelectionEnabled: Boolean = true,
    onOpenFolder: (() -> Unit)? = null,
    onSelectFolder: ((String) -> Unit)? = null,
    configurationTrailingContent: (@Composable (Boolean) -> Unit)? = null,
    goalControl: (@Composable (Boolean) -> Unit)? = null,
    autoStartsVoiceInput: Boolean = false,
    maximumAttachmentBytes: Int = PendingAttachment.maximumUploadBytes,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var activeSheet by remember { mutableStateOf<ChatComposerSelectorSheet?>(null) }
    var showsAttachmentPicker by remember { mutableStateOf(false) }
    var dismissedSendError by remember { mutableStateOf<String?>(null) }
    var dismissedConfigurationError by remember { mutableStateOf<String?>(null) }
    var selection by remember(state.sessionId, attachmentCacheNamespace) {
        mutableStateOf(TextRange(state.draft.length))
    }
    var caretRequestSequence by remember { mutableLongStateOf(0L) }
    var caretRequest by remember { mutableStateOf<ComposerCaretRequest?>(null) }

    val knownSkills = remember(state.skillSuggestions) {
        state.skillSuggestions.associate { it.slashName to it.name }
    }
    // A non-null empty map is significant in Bot Chat: with a settled empty
    // roster, an unknown @word remains plain instead of becoming a file chip.
    val knownBots = remember(mentionReferences, loadMentionSuggestions) {
        mentionReferences.takeIf { loadMentionSuggestions != null }
    }
    val effectiveTokens = remember(state.draft, draftTokens, knownSkills, knownBots) {
        draftTokens?.takeIf { it.toServerText() == state.draft }
            ?: parseComposerTokens(state.draft, knownSkills = knownSkills, knownBots = knownBots)
    }
    val fileController = remember(state.sessionId, state.selectedWorkspacePath, attachmentCacheNamespace) {
        ComposerFileAutocompleteController()
    }
    val mentionController = remember(state.sessionId, attachmentCacheNamespace) {
        ComposerMentionAutocompleteController()
    }
    val trigger = remember(state.draft, selection, slashScope) {
        detectComposerCaretTrigger(
            draft = state.draft,
            selectionStart = selection.start,
            selectionEnd = selection.end,
            slashScope = slashScope,
        )
    }
    val hostSlashArgumentText = remember(trigger, slashScope) {
        trigger?.takeIf { candidate ->
            candidate.kind == ComposerTriggerKind.SLASH &&
                candidate.startsDraft &&
                ParsedSlashQuery(candidate.text, slashScope).isHostArgumentMode
        }?.text
    }
    val currentCompleteHostSlash by rememberUpdatedState(onCompleteHostSlashArgument)

    LaunchedEffect(state.sendErrorMessage) {
        if (state.sendErrorMessage == null) dismissedSendError = null
    }
    LaunchedEffect(state.composerConfigurationErrorMessage) {
        if (state.composerConfigurationErrorMessage == null) dismissedConfigurationError = null
    }
    LaunchedEffect(state.sessionId, hostSlashArgumentText, onCompleteHostSlashArgument != null) {
        currentCompleteHostSlash?.invoke(hostSlashArgumentText)
    }

    fun moveCaret(caret: Int) {
        caretRequestSequence += 1
        caretRequest = ComposerCaretRequest(caretRequestSequence, caret)
        selection = TextRange(caret)
    }

    fun publishDraft(text: String) {
        if (onDraftTokensChange == null) {
            actions.updateDraft(text)
        } else {
            val paths = effectiveTokens.filterIsInstance<ComposerToken.PathChip>()
                .mapTo(linkedSetOf(), ComposerToken.PathChip::path)
            onDraftTokensChange(
                parseComposerTokens(
                    text,
                    knownPaths = paths,
                    knownSkills = knownSkills,
                    knownBots = knownBots,
                    preservingTrailing = effectiveTokens,
                ),
            )
        }
    }

    fun publishTokenCompletion(completion: ComposerCompletion, selectedToken: ComposerToken) {
        val knownPaths = effectiveTokens.filterIsInstance<ComposerToken.PathChip>()
            .mapTo(linkedSetOf(), ComposerToken.PathChip::path)
            .apply {
                if (selectedToken is ComposerToken.PathChip) add(selectedToken.path)
            }
        val completionSkills = knownSkills.toMutableMap().apply {
            if (selectedToken is ComposerToken.SkillChip) {
                put(io.github.jcartu.hermex.model.SlashSkillFormatter.slug(selectedToken.name), selectedToken.name)
            }
        }
        val tokens = parseComposerTokens(
            text = completion.draft,
            knownPaths = knownPaths,
            knownSkills = completionSkills,
            knownBots = knownBots,
            preservingTrailing = effectiveTokens,
        )
        if (onDraftTokensChange != null) onDraftTokensChange(tokens) else actions.updateDraft(completion.draft)
        moveCaret(completion.caret)
    }

    fun applySlashSelection(selected: ComposerAutocompleteSelection) {
        val activeTrigger = trigger?.takeIf { it.kind == ComposerTriggerKind.SLASH } ?: return
        val completion = activeTrigger.applying(selected.replacement, state.draft)
        val token = selected.token
        if (token != null) publishTokenCompletion(completion, token) else {
            publishDraft(completion.draft)
            moveCaret(completion.caret)
        }
    }

    fun applyFileSelection(match: ComposerFileSuggestion) {
        val activeTrigger = trigger?.takeIf { it.kind == ComposerTriggerKind.FILE } ?: return
        val replacement = if (match.isDirectory) "@${match.path.trimEnd('/')}/" else "@${match.path} "
        val completion = activeTrigger.applying(replacement, state.draft)
        if (match.isDirectory) {
            publishDraft(completion.draft)
            moveCaret(completion.caret)
        } else {
            publishTokenCompletion(completion, ComposerToken.PathChip(match.path))
        }
    }

    fun applyMentionSelection(match: ComposerMentionSuggestion) {
        val activeTrigger = trigger?.takeIf { it.kind == ComposerTriggerKind.FILE } ?: return
        val completion = activeTrigger.applying("@${match.tag} ", state.draft)
        publishTokenCompletion(
            completion,
            ComposerToken.BotChip(
                tag = match.tag,
                displayName = match.displayName,
                profile = match.profile,
                avatar = match.avatar,
            ),
        )
    }

    val uploadPreparedAttachment: (PreparedAttachmentResult) -> Unit = { result ->
        when (result) {
            is PreparedAttachmentResult.Success -> {
                val upload = uploadAttachmentWithPreview
                if (upload != null) {
                    upload(result.bytes, result.filename, result.previewData)
                } else {
                    actions.uploadAttachment(result.bytes, result.filename)
                }
            }
            is PreparedAttachmentResult.TooLarge -> actions.setUploadAttachmentError(
                attachmentTooLargeMessage(result),
            )
            is PreparedAttachmentResult.Failure -> actions.setUploadAttachmentError(result.message)
        }
    }
    val cacheNamespace = attachmentCacheNamespace
        ?.takeIf(String::isNotBlank)
        ?: "session:${state.sessionId ?: "new"}"

    fun processPickedUris(uris: List<Uri>) {
        scope.launch {
            for (uri in uris) {
                uploadPreparedAttachment(
                    preparePickedAttachment(context, uri, cacheNamespace, maximumAttachmentBytes),
                )
            }
        }
    }

    fun processCameraBitmap(bitmap: Bitmap) {
        scope.launch {
            uploadPreparedAttachment(prepareCameraAttachment(bitmap, maximumAttachmentBytes))
        }
    }

    val isOfflineReadOnly = state.isReadOnly || state.isViewingCachedData
    val hasSendPayload = state.draft.isNotBlank() || state.pendingAttachments.isNotEmpty()
    val showsStopButton = state.isStreaming && !hasSendPayload
    val actionButtonEnabled = if (showsStopButton) {
        !isOfflineReadOnly && !state.isCancellingStream
    } else {
        !isOfflineReadOnly &&
            hasSendPayload &&
            !state.isStartingChat &&
            !state.isUploadingAttachment &&
            !state.isLoadingComposerConfiguration
    }
    val configurationControlsEnabled = !isOfflineReadOnly &&
        !state.isStartingChat &&
        !state.isStreaming &&
        !state.isLoadingComposerConfiguration
    val attachmentControlsEnabled = configurationControlsEnabled && !state.isUploadingAttachment
    val voiceInputEnabled = attachmentControlsEnabled

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (isOfflineReadOnly) {
            ComposerStatusStrip(
                message = "Reconnect to send messages.",
                isError = false,
                onDismiss = null,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
        }

        state.sendErrorMessage
            ?.takeIf { it != dismissedSendError }
            ?.let { message ->
                ComposerStatusStrip(
                    message = message,
                    isError = true,
                    onDismiss = { dismissedSendError = message },
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
            }

        state.composerConfigurationErrorMessage
            ?.takeIf { it != dismissedConfigurationError }
            ?.let { message ->
                ComposerStatusStrip(
                    message = message,
                    isError = true,
                    onDismiss = { dismissedConfigurationError = message },
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
            }

        SlashCommandAutocomplete(
            state = state,
            actions = actions,
            trigger = trigger,
            fileController = fileController,
            mentionController = mentionController,
            loadFileSuggestions = loadFileSuggestions,
            loadMentionSuggestions = loadMentionSuggestions,
            slashScope = slashScope,
            hostSlashCompletion = hostSlashCompletion,
            reasoningLevels = reasoningLevels,
            hapticsEnabled = hapticsEnabled,
            onSelectSlash = ::applySlashSelection,
            onSelectFile = ::applyFileSelection,
            onSelectMention = ::applyMentionSelection,
            modifier = Modifier.padding(horizontal = 12.dp),
        )

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            tonalElevation = 3.dp,
            shadowElevation = 5.dp,
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                ChatComposerAttachmentStrip(
                    attachments = state.pendingAttachments,
                    isUploading = state.isUploadingAttachment,
                    errorMessage = state.uploadAttachmentErrorMessage,
                    onRemove = actions::removePendingAttachment,
                    onDismissError = { actions.setUploadAttachmentError(null) },
                    modifier = Modifier.padding(top = 4.dp),
                )

                ChatComposerSelectorChips(
                    state = state,
                    enabled = configurationControlsEnabled,
                    profileSelectionEnabled = profileSelectionEnabled,
                    onOpenModel = { activeSheet = ChatComposerSelectorSheet.MODEL },
                    onOpenProfile = {
                        if (profileSelectionEnabled) activeSheet = ChatComposerSelectorSheet.PROFILE
                    },
                    onOpenWorkspace = {
                        if (onOpenFolder != null) onOpenFolder() else activeSheet = ChatComposerSelectorSheet.WORKSPACE
                    },
                    onSelectReasoningEffort = actions::selectReasoningEffort,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    trailingContent = {
                        configurationTrailingContent?.invoke(configurationControlsEnabled)
                        if (goalControl != null) {
                            goalControl(configurationControlsEnabled)
                        } else {
                            GoalControl(enabled = configurationControlsEnabled, actions = actions)
                        }
                    },
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 4.dp, end = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    IconButton(
                        onClick = { showsAttachmentPicker = true },
                        enabled = attachmentControlsEnabled,
                    ) {
                        Icon(Icons.Filled.AttachFile, contentDescription = "Add attachment")
                    }

                    ComposerVoiceInput(
                        draft = state.draft,
                        enabled = voiceInputEnabled,
                        actions = actions,
                        autoStart = autoStartsVoiceInput,
                    )
                    ComposerVoiceNoteButton(
                        enabled = voiceInputEnabled,
                        isProcessing = state.isProcessingVoiceNote,
                        actions = actions,
                        importVoiceNote = importVoiceNote,
                    )

                    ChatComposerTextInput(
                        draft = state.draft,
                        draftTokens = effectiveTokens,
                        enabled = !isOfflineReadOnly,
                        keyboardSendEnabled = actionButtonEnabled && !showsStopButton,
                        requestedCaret = caretRequest,
                        onDraftChange = ::publishDraft,
                        onSelectionChange = { selection = it },
                        onKeyboardSend = { actions.send() },
                        recallLastUserMessage = recallLastUserMessage,
                        modifier = Modifier.weight(1f),
                    )

                    ComposerSendButton(
                        isRunActive = state.isStreaming,
                        hasPayload = hasSendPayload,
                        showsStop = showsStopButton,
                        isBusy = state.isStartingChat || state.isCancellingStream,
                        enabled = actionButtonEnabled,
                        hapticsEnabled = hapticsEnabled,
                        supportsSendModes = onSendMode != null,
                        allowedSendModes = allowedSendModes,
                        onSend = { actions.send() },
                        onStop = actions::stopStream,
                        onSendMode = { mode -> onSendMode?.invoke(mode) },
                    )
                }
            }
        }
    }

    CustomAttachmentPicker(
        visible = showsAttachmentPicker,
        onDismiss = { showsAttachmentPicker = false },
        onUrisPicked = ::processPickedUris,
        onCameraCaptured = ::processCameraBitmap,
        onError = actions::setUploadAttachmentError,
    )

    ChatComposerSelectorSheets(
        state = state,
        actions = actions,
        activeSheet = activeSheet,
        onDismiss = { activeSheet = null },
        onSelectWorkspace = onSelectFolder,
    )
}

private fun attachmentTooLargeMessage(result: PreparedAttachmentResult.TooLarge): String {
    if (result.maximumBytes == PendingAttachment.maximumUploadBytes) {
        return PendingAttachment.uploadTooLargeMessage(result.filename)
    }
    val mebibyte = 1_024 * 1_024
    val maximum = if (result.maximumBytes % mebibyte == 0) {
        "${result.maximumBytes / mebibyte} MB"
    } else {
        "${result.maximumBytes} bytes"
    }
    return "${result.filename} is too large. Attachments must be $maximum or smaller."
}

private enum class ComposerSendButtonVisual { SEND, STOP, BUSY }

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ComposerSendButton(
    isRunActive: Boolean,
    hasPayload: Boolean,
    showsStop: Boolean,
    isBusy: Boolean,
    enabled: Boolean,
    hapticsEnabled: Boolean,
    supportsSendModes: Boolean,
    allowedSendModes: Set<ComposerSendModeChoice>?,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onSendMode: (ComposerSendModeChoice) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val view = LocalView.current
    val choices = composerSendModeChoices(
        isRunActive = isRunActive,
        hasPayload = hasPayload,
        allowedChoices = allowedSendModes,
    )
    val mayOpenMenu = enabled && supportsSendModes && choices.isNotEmpty()
    LaunchedEffect(mayOpenMenu) {
        if (!mayOpenMenu) menuOpen = false
    }
    val visual = when {
        isBusy -> ComposerSendButtonVisual.BUSY
        showsStop -> ComposerSendButtonVisual.STOP
        else -> ComposerSendButtonVisual.SEND
    }

    Box {
        Surface(
            modifier = Modifier
                .size(40.dp)
                .combinedClickable(
                    enabled = enabled,
                    role = Role.Button,
                    hapticFeedbackEnabled = false,
                    onClick = { if (showsStop) onStop() else onSend() },
                    onLongClick = if (mayOpenMenu) {
                        {
                            if (hapticsEnabled) {
                                view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                            }
                            menuOpen = true
                        }
                    } else {
                        null
                    },
                ),
            shape = CircleShape,
            color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
            contentColor = if (enabled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
        ) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                AnimatedContent(
                    targetState = visual,
                    transitionSpec = {
                        (fadeIn() + scaleIn(initialScale = 0.82f)) togetherWith
                            (fadeOut() + scaleOut(targetScale = 0.82f))
                    },
                    label = "composer-send-stop",
                ) { target ->
                    when (target) {
                        ComposerSendButtonVisual.BUSY -> CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = if (enabled) {
                                MaterialTheme.colorScheme.onPrimary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                        ComposerSendButtonVisual.STOP -> Icon(
                            Icons.Filled.Stop,
                            contentDescription = "Stop response",
                        )
                        ComposerSendButtonVisual.SEND -> Icon(
                            Icons.Filled.ArrowUpward,
                            contentDescription = "Send",
                        )
                    }
                }
            }
        }

        DropdownMenu(expanded = menuOpen && mayOpenMenu, onDismissRequest = { menuOpen = false }) {
            choices.forEach { choice ->
                DropdownMenuItem(
                    text = {
                        Text(
                            when (choice) {
                                ComposerSendModeChoice.QUEUE -> "Queue"
                                ComposerSendModeChoice.STEER -> "Steer"
                                ComposerSendModeChoice.STOP_AND_SEND -> "Stop and send"
                            },
                        )
                    },
                    onClick = {
                        menuOpen = false
                        onSendMode(choice)
                    },
                )
            }
        }
    }
}

@Composable
private fun ComposerStatusStrip(
    message: String,
    isError: Boolean,
    onDismiss: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, top = 6.dp, bottom = 6.dp, end = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = message,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = if (isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (onDismiss != null) {
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "Dismiss error",
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
        }
    }
}

@Composable
private fun GoalControl(enabled: Boolean, actions: ChatComposerActions) {
    var menuOpen by remember { mutableStateOf(false) }
    var showsSetGoalDialog by remember { mutableStateOf(false) }
    var goalDraft by remember { mutableStateOf("") }

    fun submitGoal(args: String) {
        actions.updateDraft("/goal $args")
        actions.send()
    }

    Box {
        AssistChip(
            onClick = { menuOpen = true },
            enabled = enabled,
            label = { Text("Goal") },
            leadingIcon = {
                Icon(Icons.Filled.Flag, contentDescription = null, modifier = Modifier.size(18.dp))
            },
        )
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text("Set Goal") },
                onClick = {
                    menuOpen = false
                    showsSetGoalDialog = true
                },
            )
            listOf(
                "Status" to "status",
                "Pause" to "pause",
                "Resume" to "resume",
                "Mark Done" to "done",
                "Clear" to "clear",
                "Stop" to "stop",
            ).forEach { (title, command) ->
                DropdownMenuItem(
                    text = { Text(title) },
                    onClick = {
                        menuOpen = false
                        submitGoal(command)
                    },
                )
            }
        }
    }

    if (showsSetGoalDialog) {
        AlertDialog(
            onDismissRequest = { showsSetGoalDialog = false },
            title = { Text("Set Goal") },
            text = {
                OutlinedTextField(
                    value = goalDraft,
                    onValueChange = { goalDraft = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Describe the goal") },
                    minLines = 3,
                    maxLines = 6,
                )
            },
            dismissButton = {
                TextButton(onClick = { showsSetGoalDialog = false }) { Text("Cancel") }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val submittedGoal = goalDraft.trim()
                        showsSetGoalDialog = false
                        goalDraft = ""
                        submitGoal(submittedGoal)
                    },
                    enabled = goalDraft.isNotBlank(),
                ) { Text("Set") }
            },
        )
    }
}
