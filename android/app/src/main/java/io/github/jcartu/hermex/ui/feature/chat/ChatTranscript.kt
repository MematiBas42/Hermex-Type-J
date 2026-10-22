package io.github.jcartu.hermex.ui.feature.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import coil.ImageLoader
import io.github.jcartu.hermex.model.ChatMessage
import io.github.jcartu.hermex.model.ToolCallGroup
import io.github.jcartu.hermex.ui.components.EmptyState
import io.github.jcartu.hermex.ui.components.ErrorState
import io.github.jcartu.hermex.ui.components.LoadingState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

private val MaximumReadingWidth = 768.dp

/** Scrolling transcript port of `ChatTranscriptView.swift`. */
@Composable
fun ChatTranscript(
    state: ChatUiState,
    listState: LazyListState,
    attachmentUrlResolver: suspend (path: String) -> String?,
    imageLoader: ImageLoader?,
    onListen: (String) -> Unit,
    onLoadOlder: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    attachmentDataResolver: suspend (path: String) -> ByteArray? = { null },
    workspaceRoot: String? = null,
    onOpenWeb: (String) -> Unit = {},
    onOpenWorkspaceFile: (String) -> Unit = {},
    onAskHermex: (String) -> Unit = {},
    hapticsEnabled: Boolean = true,
    leadingContent: (@Composable () -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null,
    rowContent: (@Composable (TranscriptMessage, @Composable () -> Unit) -> Unit)? = null,
    resolveRemoteImages: Boolean = false,
) {
    when {
        state.isLoading && state.messages.isEmpty() -> LoadingState(modifier, "Loading messages")
        state.errorMessage != null && state.messages.isEmpty() -> ErrorState(
            message = state.errorMessage,
            modifier = modifier,
            retryLabel = "Try again",
            onRetry = onRetry,
        )
        state.messages.isEmpty() && leadingContent == null && trailingContent == null -> EmptyState(
            title = "No messages yet",
            message = "Send a message to start the conversation.",
            modifier = modifier,
        )
        else -> TranscriptList(
            state = state,
            listState = listState,
            attachmentUrlResolver = attachmentUrlResolver,
            attachmentDataResolver = attachmentDataResolver,
            workspaceRoot = workspaceRoot,
            imageLoader = imageLoader,
            onListen = onListen,
            onLoadOlder = onLoadOlder,
            onOpenWeb = onOpenWeb,
            onOpenWorkspaceFile = onOpenWorkspaceFile,
            onAskHermex = onAskHermex,
            hapticsEnabled = hapticsEnabled,
            leadingContent = leadingContent,
            trailingContent = trailingContent,
            rowContent = rowContent,
            resolveRemoteImages = resolveRemoteImages,
            modifier = modifier,
        )
    }
}

@Composable
private fun TranscriptList(
    state: ChatUiState,
    listState: LazyListState,
    attachmentUrlResolver: suspend (String) -> String?,
    attachmentDataResolver: suspend (String) -> ByteArray?,
    workspaceRoot: String?,
    imageLoader: ImageLoader?,
    onListen: (String) -> Unit,
    onLoadOlder: () -> Unit,
    onOpenWeb: (String) -> Unit,
    onOpenWorkspaceFile: (String) -> Unit,
    onAskHermex: (String) -> Unit,
    hapticsEnabled: Boolean,
    modifier: Modifier,
    leadingContent: (@Composable () -> Unit)?,
    trailingContent: (@Composable () -> Unit)?,
    rowContent: (@Composable (TranscriptMessage, @Composable () -> Unit) -> Unit)?,
    resolveRemoteImages: Boolean,
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val reduceMotion = rememberReduceMotion()
    val isStreamingNow by rememberUpdatedState(state.isStreaming)
    var shouldFollowLatest by remember(state.sessionId) { mutableStateOf(true) }
    var programmaticScroll by remember { mutableStateOf(false) }
    var hasUnseenContent by remember { mutableStateOf(false) }
    var didInitialAnchor by remember(state.sessionId) { mutableStateOf(false) }
    var expandedTurnKeys by rememberSaveable(state.sessionId) { mutableStateOf(setOf<String>()) }

    val activityAnchorIDs = remember(
        state.completedReasoningGroups,
        state.completedToolCallGroups,
        state.showsThinkingAndToolCards,
    ) {
        if (!state.showsThinkingAndToolCards) emptySet() else buildSet {
            state.completedReasoningGroups.mapNotNullTo(this) { it.anchorMessageID }
            state.completedToolCallGroups.mapNotNullTo(this) { it.anchorMessageID }
        }
    }
    val foldOutcome = remember(state.latestRunOutcome) {
        state.latestRunOutcome?.let { outcome ->
            FoldRunOutcome(
                turnKey = outcome.turnKey,
                startedAt = outcome.startedAt,
                endedAt = outcome.endedAt,
                ending = when (outcome.ending) {
                    ChatRunEnding.COMPLETED -> FoldRunOutcome.Ending.COMPLETED
                    ChatRunEnding.CANCELLED -> FoldRunOutcome.Ending.CANCELLED
                    ChatRunEnding.FAILED -> FoldRunOutcome.Ending.FAILED
                },
            )
        }
    }
    val folds = remember(
        state.transcriptMessages,
        state.messages,
        state.messagesOffset,
        activityAnchorIDs,
        state.isStreaming,
        state.streamingAssistantMessageId,
        foldOutcome,
    ) {
        TranscriptTurnFolds.derive(
            transcriptMessages = state.transcriptMessages,
            messages = state.messages,
            messageOffset = state.messagesOffset,
            activityAnchorIDs = activityAnchorIDs,
            rendersBubble = ::rendersTranscriptBubble,
            isStreamActive = state.isStreaming,
            streamingAssistantMessageID = state.streamingAssistantMessageId,
            latestRunOutcome = foldOutcome,
            durationForMessage = { it.turnDuration },
        )
    }
    val terminalReplies = remember(
        state.transcriptMessages,
        state.messages,
        state.messagesOffset,
        state.isStreaming,
        state.streamingAssistantMessageId,
    ) {
        TranscriptTurnFolds.terminalReplyRenderIDs(
            state.transcriptMessages,
            state.messages,
            state.messagesOffset,
            ::rendersTranscriptBubble,
            state.isStreaming,
            state.streamingAssistantMessageId,
        )
    }

    fun distanceFromBottomDp(): Float {
        val info = listState.layoutInfo
        if (info.totalItemsCount == 0) return 0f
        val last = info.visibleItemsInfo.lastOrNull() ?: return 0f
        if (last.index < info.totalItemsCount - 1) return Float.MAX_VALUE
        return with(density) { (last.offset + last.size - info.viewportEndOffset).coerceAtLeast(0).toDp().value }
    }

    suspend fun scrollToLatest(animated: Boolean) {
        val target = listState.layoutInfo.totalItemsCount - 1
        if (target < 0) return
        programmaticScroll = true
        try {
            if (animated && !reduceMotion) listState.animateScrollToItem(target) else listState.scrollToItem(target)
        } finally {
            programmaticScroll = false
        }
    }

    val nearBottom by remember {
        derivedStateOf { ChatScrollPolicy.isNearBottom(distanceFromBottomDp(), isStreamingNow) }
    }
    val showScrollButton by remember {
        derivedStateOf {
            ChatScrollPolicy.showsScrollToBottomButton(nearBottom, isStreamingNow, shouldFollowLatest)
        }
    }

    LaunchedEffect(listState) {
        snapshotFlow { distanceFromBottomDp() to listState.isScrollInProgress }.collect { (distance, scrolling) ->
            val interacting = scrolling && !programmaticScroll
            val near = ChatScrollPolicy.isNearBottom(distance, isStreamingNow)
            shouldFollowLatest = ChatScrollPolicy.resolveFollowLatest(near, interacting, shouldFollowLatest)
            if (near) hasUnseenContent = false
        }
    }
    LaunchedEffect(state.sessionId, state.transcriptMessages.isNotEmpty()) {
        if (!didInitialAnchor && state.transcriptMessages.isNotEmpty()) {
            scrollToLatest(false)
            didInitialAnchor = true
        }
    }
    LaunchedEffect(state.transcriptMessages.size, state.streamingContentTrigger, state.responseCompletionTrigger) {
        if (!didInitialAnchor) return@LaunchedEffect
        if (shouldFollowLatest) scrollToLatest(true) else hasUnseenContent = true
    }

    Column(modifier.fillMaxSize()) {
        if (state.isViewingCachedData) OfflineCacheBanner()
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val direction = if (state.rtlChatLayoutEnabled) LayoutDirection.Rtl else LayoutDirection.Ltr
            CompositionLocalProvider(LocalLayoutDirection provides direction) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    leadingContent?.let { content -> item("leading-content") { CenteredReadingColumn { content() } } }
                    if (state.canLoadOlderMessages) {
                        item("load-older", "load-older") {
                            CenteredReadingColumn {
                                LoadOlderMessagesButton(state.isLoadingOlderMessages) {
                                    shouldFollowLatest = false
                                    onLoadOlder()
                                }
                            }
                        }
                    }
                    items(state.transcriptMessages, { it.renderID }, { "message" }) { row ->
                        FreshTranscriptRow(row.message.timestamp, row.message.role == "user", reduceMotion) {
                            CenteredReadingColumn {
                                val bubble: @Composable () -> Unit = {
                                TranscriptMessageBlock(
                                    transcriptMessage = row,
                                    state = state,
                                    foldState = folds.rowState(row.renderID, expandedTurnKeys),
                                    isTerminalReply = row.renderID in terminalReplies,
                                    onToggleTurnFold = { turnKey ->
                                        expandedTurnKeys = if (turnKey in expandedTurnKeys) {
                                            expandedTurnKeys - turnKey
                                        } else {
                                            expandedTurnKeys + turnKey
                                        }
                                    },
                                    attachmentUrlResolver = attachmentUrlResolver,
                                    attachmentDataResolver = attachmentDataResolver,
                                    workspaceRoot = workspaceRoot,
                                    imageLoader = imageLoader,
                                    onListen = onListen,
                                    onOpenWeb = onOpenWeb,
                                    onOpenWorkspaceFile = onOpenWorkspaceFile,
                                    onAskHermex = onAskHermex,
                                    hapticsEnabled = hapticsEnabled,
                                    resolveRemoteImages = resolveRemoteImages,
                                )
                                }
                                if (rowContent == null) bubble() else rowContent(row, bubble)
                            }
                        }
                    }
                    if (state.showsThinkingAndToolCards) {
                        state.completedReasoningGroups.filter { it.anchorMessageID == null }.forEach { group ->
                            item("loose-reasoning-${group.id}") {
                                CenteredReadingColumn {
                                    ReasoningBlock(group.text, startExpanded = state.thinkingCardsStartExpanded, hapticsEnabled = hapticsEnabled)
                                }
                            }
                        }
                        state.completedToolCallGroups.filter { it.anchorMessageID == null }.forEach { group ->
                            item("loose-tools-${group.id}") {
                                CenteredReadingColumn {
                                    ToolCallGroupCard(group, startExpanded = state.toolCardsStartExpanded, hapticsEnabled = hapticsEnabled)
                                }
                            }
                        }
                    }
                    val liveAnchor = state.streamingAssistantMessageId
                    val hasAnchor = liveAnchor != null && state.transcriptMessages.any { it.anchorID == liveAnchor }
                    if (state.isStreaming && state.showsThinkingAndToolCards && !hasAnchor) {
                        if (state.liveReasoningText.isNotBlank()) {
                            item("live-reasoning") {
                                CenteredReadingColumn {
                                    ReasoningBlock(
                                        state.liveReasoningText,
                                        startExpanded = state.thinkingCardsStartExpanded,
                                        isStreaming = true,
                                        hapticsEnabled = hapticsEnabled,
                                    )
                                }
                            }
                        }
                        if (state.liveToolCalls.isNotEmpty()) {
                            item("live-tools") {
                                CenteredReadingColumn {
                                    ToolCallGroupCard(
                                        ToolCallGroup.live(liveAnchor, state.liveToolCalls),
                                        startExpanded = state.toolCardsStartExpanded,
                                        isLive = true,
                                        hapticsEnabled = hapticsEnabled,
                                    )
                                }
                            }
                        }
                    }
                    if (state.isStreaming && state.recoveryState != ActiveStreamRecoveryState.IDLE &&
                        !(state.recoveryState == ActiveStreamRecoveryState.CHECKING && state.isStreamHeartbeatFresh)
                    ) {
                        item("stream-recovery") { CenteredReadingColumn { StreamRecoveryStatusPill(state.recoveryState) } }
                    }
                    if (shouldShowAssistantTypingIndicator(state)) {
                        item("typing") { CenteredReadingColumn { AssistantTypingIndicator() } }
                    }
                    val workingStartedAt = state.activeRunStartedAt
                    if (state.isStreaming && workingStartedAt != null && !state.isCancellingStream && state.clarificationPrompt == null) {
                        item("working-for") { CenteredReadingColumn { ChatWorkingRow(workingStartedAt) } }
                    }
                    if (state.pinnedLocalNotices.isNotEmpty()) {
                        item("notices") { CenteredReadingColumn { PinnedLocalNoticeRows(state.pinnedLocalNotices) } }
                    }
                    trailingContent?.let { content -> item("trailing-content") { CenteredReadingColumn { content() } } }
                    item("bottom-anchor") { Spacer(Modifier.fillMaxWidth().height(1.dp)) }
                }
            }
            androidx.compose.animation.AnimatedVisibility(
                visible = showScrollButton,
                enter = if (reduceMotion) androidx.compose.animation.EnterTransition.None else fadeIn(),
                exit = if (reduceMotion) androidx.compose.animation.ExitTransition.None else fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp),
            ) {
                ScrollToBottomButton(hasUnseenContent) {
                    shouldFollowLatest = true
                    hasUnseenContent = false
                    scope.launch { scrollToLatest(true) }
                }
            }
        }
    }
}

@Composable
private fun CenteredReadingColumn(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        Box(Modifier.fillMaxWidth().widthIn(max = MaximumReadingWidth)) { content() }
    }
}

@Composable
private fun TranscriptMessageBlock(
    transcriptMessage: TranscriptMessage,
    state: ChatUiState,
    foldState: TranscriptTurnFoldRowState?,
    isTerminalReply: Boolean,
    onToggleTurnFold: (String) -> Unit,
    attachmentUrlResolver: suspend (String) -> String?,
    attachmentDataResolver: suspend (String) -> ByteArray?,
    workspaceRoot: String?,
    imageLoader: ImageLoader?,
    onListen: (String) -> Unit,
    onOpenWeb: (String) -> Unit,
    onOpenWorkspaceFile: (String) -> Unit,
    onAskHermex: (String) -> Unit,
    hapticsEnabled: Boolean,
    resolveRemoteImages: Boolean,
) {
    val message = transcriptMessage.message
    val anchor = transcriptMessage.anchorID
    val liveAnchor = state.isStreaming && state.streamingAssistantMessageId == anchor
    val streamingRow = state.isStreaming && message.role == "assistant" &&
        message.messageId == state.streamingAssistantMessageId
    val haptics = LocalHapticFeedback.current

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        foldState?.fold?.let { fold ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        if (hapticsEnabled) haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                        onToggleTurnFold(fold.turnKey)
                    }
                    .padding(horizontal = 8.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    fold.label.title,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Icon(
                    if (foldState.isExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    if (foldState.isExpanded) "Hide turn activity" else "Show turn activity",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        if (foldState?.hidesActivity != true && state.showsThinkingAndToolCards) {
            state.completedReasoningGroups.filter { it.anchorMessageID == anchor }.forEach { group ->
                ReasoningBlock(group.text, startExpanded = state.thinkingCardsStartExpanded, hapticsEnabled = hapticsEnabled)
            }
            if (liveAnchor && state.liveReasoningText.isNotBlank()) {
                ReasoningBlock(
                    state.liveReasoningText,
                    startExpanded = state.thinkingCardsStartExpanded,
                    isStreaming = true,
                    hapticsEnabled = hapticsEnabled,
                )
            }
            state.completedToolCallGroups.filter { it.anchorMessageID == anchor }.forEach { group ->
                ToolCallGroupCard(group, startExpanded = state.toolCardsStartExpanded, hapticsEnabled = hapticsEnabled)
            }
            if (liveAnchor && state.liveToolCalls.isNotEmpty()) {
                ToolCallGroupCard(
                    ToolCallGroup.live(anchor, state.liveToolCalls),
                    startExpanded = state.toolCardsStartExpanded,
                    isLive = true,
                    hapticsEnabled = hapticsEnabled,
                )
            }
        }
        if (foldState?.hidesBubble != true && rendersTranscriptBubble(message)) {
            val classification = ChatMarkerMessageClassifier.classification(message)
            if (classification != null) {
                MarkerMessageCard(classification)
            } else {
                Column(
                    horizontalAlignment = if (message.role == "user") Alignment.End else Alignment.Start,
                    verticalArrangement = Arrangement.spacedBy(if (message.role == "user") 4.dp else 5.dp),
                ) {
                    MessageBubble(
                        message = message,
                        state = state,
                        isStreaming = streamingRow,
                        liveTokensPerSecond = state.liveTokensPerSecond.takeIf { streamingRow },
                        attachmentUrlResolver = attachmentUrlResolver,
                        attachmentDataResolver = attachmentDataResolver,
                        workspaceRoot = workspaceRoot,
                        imageLoader = imageLoader,
                        onListen = onListen,
                        onOpenWeb = onOpenWeb,
                        onOpenWorkspaceFile = onOpenWorkspaceFile,
                        onAskHermex = onAskHermex,
                        hapticsEnabled = hapticsEnabled,
                        resolveRemoteImages = resolveRemoteImages,
                    )
                    if (!message.isSteerMessage && (!message.content.isNullOrBlank() || !message.attachments.isNullOrEmpty())) {
                        MessageMetaRow(message, streamingRow, isTerminalReply, hapticsEnabled)
                    }
                }
            }
        }
    }
}

private fun rendersTranscriptBubble(message: ChatMessage): Boolean {
    if (ChatMarkerMessageClassifier.classification(message) != null) return true
    if (!message.content.isNullOrBlank()) return true
    return !message.attachments.isNullOrEmpty()
}

@Composable
private fun MessageMetaRow(
    message: ChatMessage,
    isStreaming: Boolean,
    isTerminalReply: Boolean,
    hapticsEnabled: Boolean,
) {
    val isUser = message.role == "user"
    val time = AssistantTurnTimestampFormatter.shortTime(message.timestamp)
    val text = message.content.orEmpty()
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        if (!isUser && text.isNotBlank()) TranscriptCopyButton(text, hapticsEnabled)
        if (time != null) {
            Text(
                time,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
        if (isUser && text.isNotBlank()) TranscriptCopyButton(text, hapticsEnabled)
    }
}

@Composable
private fun FreshTranscriptRow(
    timestamp: Double?,
    isUser: Boolean,
    reduceMotion: Boolean,
    content: @Composable () -> Unit,
) {
    val fresh = timestamp?.takeIf(Double::isFinite)?.let {
        abs(System.currentTimeMillis() / 1_000.0 - it) < 3.0
    } == true
    val alpha = remember(timestamp) { Animatable(if (fresh && !reduceMotion) 0f else 1f) }
    LaunchedEffect(timestamp, fresh, reduceMotion) {
        if (fresh && !reduceMotion) alpha.animateTo(1f, tween(220)) else alpha.snapTo(1f)
    }
    Box(Modifier.fillMaxWidth().alpha(alpha.value)) { content() }
}

private fun shouldShowAssistantTypingIndicator(state: ChatUiState): Boolean {
    if (!state.isStreaming || state.isCancellingStream || state.streamingAssistantMessageId != null) return false
    if (state.clarificationPrompt != null) return false
    return !state.showsThinkingAndToolCards || (state.liveReasoningText.isBlank() && state.liveToolCalls.isEmpty())
}

@Composable
private fun AssistantTypingIndicator() {
    val reduceMotion = rememberReduceMotion()
    val color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)
    if (reduceMotion) {
        Box(Modifier.padding(4.dp).size(16.dp).alpha(0.75f).background(color, CircleShape))
    } else {
        val transition = rememberInfiniteTransition(label = "typing")
        val breathing by transition.animateFloat(
            0f,
            1f,
            infiniteRepeatable(tween(700), RepeatMode.Reverse),
            label = "typing-alpha",
        )
        Box(
            Modifier.padding(4.dp).size(16.dp)
                .scale(0.86f + 0.3f * breathing)
                .alpha(0.55f + 0.4f * breathing)
                .background(color, CircleShape),
        )
    }
}

@Composable
private fun ChatWorkingRow(startedAt: Double) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(startedAt) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.78f),
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf(1f, 0.8f, 0.6f).forEach { opacity ->
                    Box(Modifier.size(4.dp).alpha(opacity).background(MaterialTheme.colorScheme.onSurfaceVariant, CircleShape))
                }
            }
            Text(
                "Working for ${ChatWorkingElapsedFormatter.label(startedAt, now / 1_000.0)}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LoadOlderMessagesButton(isLoading: Boolean, onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceVariant,
            border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Row(
                Modifier.clickable(enabled = !isLoading, onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (isLoading) CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp)
                else Icon(Icons.Rounded.ArrowUpward, null, modifier = Modifier.size(12.dp))
                Text(if (isLoading) "Loading older messages" else "Load older messages", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun OfflineCacheBanner() {
    val orange = Color(0xFFFF9500)
    Row(
        Modifier.fillMaxWidth().background(orange.copy(alpha = 0.12f)).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Icons.Rounded.WifiOff, null, tint = orange, modifier = Modifier.size(16.dp))
        Text("Offline — viewing cached version", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = orange)
    }
}

@Composable
private fun StreamRecoveryStatusPill(state: ActiveStreamRecoveryState) {
    val label = when (state) {
        ActiveStreamRecoveryState.IDLE -> "Stream active"
        ActiveStreamRecoveryState.CHECKING -> "Checking stream"
        ActiveStreamRecoveryState.RECONNECTING -> "Reconnecting stream"
        ActiveStreamRecoveryState.WAITING_FOR_NETWORK -> "Waiting for network"
    }
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp)
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PinnedLocalNoticeRows(notices: List<String>) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        notices.forEach { notice ->
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Rounded.CheckCircle, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(17.dp))
                    Text(notice, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun ScrollToBottomButton(showsBadge: Boolean, onClick: () -> Unit) {
    Box {
        Surface(
            onClick = onClick,
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceVariant,
            border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
            shadowElevation = 6.dp,
            modifier = Modifier.size(36.dp),
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.ArrowDownward, "Scroll to latest message", modifier = Modifier.size(16.dp))
            }
        }
        if (showsBadge) Box(Modifier.align(Alignment.TopEnd).size(9.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
    }
}
