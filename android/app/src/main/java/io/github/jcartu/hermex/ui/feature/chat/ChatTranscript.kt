package io.github.jcartu.hermex.ui.feature.chat

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.CheckCircle
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import coil.ImageLoader
import io.github.jcartu.hermex.model.ToolCallGroup
import io.github.jcartu.hermex.ui.components.EmptyState
import io.github.jcartu.hermex.ui.components.ErrorState
import io.github.jcartu.hermex.ui.components.LoadingState
import io.github.jcartu.hermex.ui.theme.SuccessDark
import io.github.jcartu.hermex.ui.theme.SuccessLight
import kotlinx.coroutines.launch

/**
 * Port of `ChatTranscriptView.swift` — the scrolling message list of the chat
 * pane. Rows come from [ChatUiState.transcriptMessages]; completed reasoning
 * and tool-call groups interleave above their anchor assistant message, the
 * live turn's blocks render under the streaming message (or at the end when
 * no anchor row exists yet).
 *
 * Auto-scroll consults [ChatScrollPolicy]: the app owns follow-bottom intent
 * until the reader scrolls away by hand, and a short cooldown after any user
 * interaction prevents streaming layout growth from yanking the viewport.
 */
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
) {
    when {
        state.isLoading && state.messages.isEmpty() && state.clarificationPrompt == null ->
            LoadingState(modifier = modifier, label = "Loading messages")

        state.errorMessage != null && state.messages.isEmpty() && state.clarificationPrompt == null ->
            ErrorState(
                message = state.errorMessage,
                modifier = modifier,
                retryLabel = "Try Again",
                onRetry = onRetry,
            )

        state.messages.isEmpty() && state.clarificationPrompt == null ->
            EmptyState(
                title = "No messages yet",
                message = "Send a message to start the conversation.",
                modifier = modifier,
            )

        else -> TranscriptList(
            state = state,
            listState = listState,
            attachmentUrlResolver = attachmentUrlResolver,
            imageLoader = imageLoader,
            onListen = onListen,
            onLoadOlder = onLoadOlder,
            modifier = modifier,
        )
    }
}

@Composable
private fun TranscriptList(
    state: ChatUiState,
    listState: LazyListState,
    attachmentUrlResolver: suspend (path: String) -> String?,
    imageLoader: ImageLoader?,
    onListen: (String) -> Unit,
    onLoadOlder: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val reduceMotion = rememberReduceMotion()
    val isStreamingNow by rememberUpdatedState(state.isStreaming)

    // App-owned follow-bottom intent, separate from user-owned manual
    // scrolling (see ChatScrollPolicy). Reset per session.
    var shouldFollowLatest by remember(state.sessionId) { mutableStateOf(true) }
    var cooldownUntil by remember { mutableStateOf<Long?>(null) }
    var programmaticScroll by remember { mutableStateOf(false) }
    var hasUnseenContent by remember { mutableStateOf(false) }
    var didInitialAnchor by remember(state.sessionId) { mutableStateOf(false) }

    fun distanceFromBottomDp(): Float {
        val info = listState.layoutInfo
        if (info.totalItemsCount == 0) return 0f
        val last = info.visibleItemsInfo.lastOrNull() ?: return 0f
        if (last.index < info.totalItemsCount - 1) return Float.MAX_VALUE
        val px = (last.offset + last.size - info.viewportEndOffset).coerceAtLeast(0)
        return with(density) { px.toDp().value }
    }

    suspend fun scrollToLatest(animated: Boolean) {
        val target = listState.layoutInfo.totalItemsCount - 1
        if (target < 0) return
        programmaticScroll = true
        try {
            if (animated && !reduceMotion) {
                listState.animateScrollToItem(target)
            } else {
                listState.scrollToItem(target)
            }
        } finally {
            programmaticScroll = false
        }
    }

    val isScrolledNearBottom by remember {
        derivedStateOf { ChatScrollPolicy.isNearBottom(distanceFromBottomDp(), isStreamingNow) }
    }
    val showsScrollToBottomButton by remember {
        derivedStateOf {
            ChatScrollPolicy.showsScrollToBottomButton(
                isScrolledNearBottom = isScrolledNearBottom,
                hasActiveStream = isStreamingNow,
                shouldFollowLatestMessage = shouldFollowLatest,
            )
        }
    }

    // Track scroll metrics → follow-latest intent (port of ChatView.updateScrollMetrics).
    LaunchedEffect(listState) {
        snapshotFlow { Pair(distanceFromBottomDp(), listState.isScrollInProgress) }
            .collect { (distance, scrolling) ->
                val interacting = scrolling && !programmaticScroll
                val near = ChatScrollPolicy.isNearBottom(distance, isStreamingNow)
                if (interacting) {
                    cooldownUntil = ChatScrollPolicy.cooldownDeadline(System.currentTimeMillis())
                }
                shouldFollowLatest = ChatScrollPolicy.resolveFollowLatest(
                    isNearBottom = near,
                    isUserInteracting = interacting,
                    current = shouldFollowLatest,
                )
                if (near) hasUnseenContent = false
            }
    }

    // Existing transcripts enter at their latest content (initial anchor).
    LaunchedEffect(state.sessionId, state.transcriptMessages.isNotEmpty()) {
        if (!didInitialAnchor && state.transcriptMessages.isNotEmpty()) {
            scrollToLatest(animated = false)
            didInitialAnchor = true
        }
    }

    // New/removed rows: follow to the latest content unless the reader owns
    // the scroll position (older pages prepend with stable keys, so the
    // viewport stays anchored on the previously-first row automatically).
    LaunchedEffect(state.transcriptMessages.size) {
        if (!didInitialAnchor) return@LaunchedEffect
        if (shouldFollowLatest) scrollToLatest(animated = true) else hasUnseenContent = true
    }

    fun isAutoScrollPausedNow(): Boolean = ChatScrollPolicy.isAutoScrollPaused(
        isUserInteracting = listState.isScrollInProgress && !programmaticScroll,
        cooldownUntilMillis = cooldownUntil,
        nowMillis = System.currentTimeMillis(),
    )

    // Streamed content growth keeps the transcript bottom-pinned while the
    // app owns follow intent and no manual scroll is settling.
    LaunchedEffect(state.streamingContentTrigger) {
        if (state.streamingContentTrigger == 0 || !didInitialAnchor) return@LaunchedEffect
        if (shouldFollowLatest && !isAutoScrollPausedNow()) {
            scrollToLatest(animated = true)
        } else if (!shouldFollowLatest) {
            hasUnseenContent = true
        }
    }

    LaunchedEffect(state.responseCompletionTrigger) {
        if (state.responseCompletionTrigger == 0 || !didInitialAnchor) return@LaunchedEffect
        if (shouldFollowLatest && !isAutoScrollPausedNow()) {
            scrollToLatest(animated = true)
        } else if (!shouldFollowLatest) {
            hasUnseenContent = true
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        if (state.isViewingCachedData) {
            OfflineCacheBanner()
        }

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            val direction = if (state.rtlChatLayoutEnabled) LayoutDirection.Rtl else LayoutDirection.Ltr
            CompositionLocalProvider(LocalLayoutDirection provides direction) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (state.canLoadOlderMessages) {
                        item(key = "load-older", contentType = "load-older") {
                            LoadOlderMessagesButton(
                                isLoading = state.isLoadingOlderMessages,
                                onClick = {
                                    // Reading older content: release follow intent
                                    // first so the prepend cannot yank to the bottom
                                    // (port of ChatView.loadOlderMessages).
                                    shouldFollowLatest = false
                                    onLoadOlder()
                                },
                            )
                        }
                    }

                    items(
                        state.transcriptMessages,
                        key = { it.renderID },
                        contentType = { "message" },
                    ) { transcriptMessage ->
                        TranscriptMessageBlock(
                            transcriptMessage = transcriptMessage,
                            state = state,
                            attachmentUrlResolver = attachmentUrlResolver,
                            imageLoader = imageLoader,
                            onListen = onListen,
                        )
                    }

                    // Loose completed blocks whose anchor message is not displayed.
                    if (state.showsThinkingAndToolCards) {
                        state.completedReasoningGroups
                            .filter { it.anchorMessageID == null }
                            .forEach { group ->
                                item(key = "loose-reasoning-${group.id}", contentType = "reasoning") {
                                    ReasoningBlock(
                                        text = group.text,
                                        startExpanded = state.thinkingCardsStartExpanded,
                                    )
                                }
                            }
                        state.completedToolCallGroups
                            .filter { it.anchorMessageID == null }
                            .forEach { group ->
                                item(key = "loose-tools-${group.id}", contentType = "tools") {
                                    ToolCallGroupCard(
                                        group = group,
                                        startExpanded = state.toolCardsStartExpanded,
                                    )
                                }
                            }
                    }

                    // The live turn's blocks trail the transcript until the
                    // streaming assistant message row exists to anchor them.
                    val liveAnchorId = state.streamingAssistantMessageId
                    val hasAnchoredRow = liveAnchorId != null &&
                        state.transcriptMessages.any { it.anchorID == liveAnchorId }
                    if (state.isStreaming && state.showsThinkingAndToolCards && !hasAnchoredRow) {
                        if (state.liveReasoningText.isNotBlank()) {
                            item(key = "live-reasoning", contentType = "reasoning") {
                                ReasoningBlock(
                                    text = state.liveReasoningText,
                                    startExpanded = state.thinkingCardsStartExpanded,
                                    isStreaming = true,
                                )
                            }
                        }
                        if (state.liveToolCalls.isNotEmpty()) {
                            item(key = "live-tools", contentType = "tools") {
                                ToolCallGroupCard(
                                    group = ToolCallGroup.live(
                                        anchorMessageID = liveAnchorId,
                                        toolCalls = state.liveToolCalls,
                                    ),
                                    startExpanded = state.toolCardsStartExpanded,
                                )
                            }
                        }
                    }

                    if (state.isStreaming && state.recoveryState != ActiveStreamRecoveryState.IDLE) {
                        item(key = "stream-recovery", contentType = "recovery") {
                            StreamRecoveryStatusPill(state.recoveryState)
                        }
                    }

                    if (shouldShowAssistantTypingIndicator(state)) {
                        item(key = "typing-indicator", contentType = "typing") {
                            AssistantTypingIndicator()
                        }
                    }

                    if (state.pinnedLocalNotices.isNotEmpty()) {
                        item(key = "pinned-notices", contentType = "notices") {
                            PinnedLocalNoticeRows(state.pinnedLocalNotices)
                        }
                    }

                    item(key = "bottom-anchor", contentType = "anchor") {
                        Spacer(Modifier.fillMaxWidth().height(1.dp))
                    }
                }
            }

            androidx.compose.animation.AnimatedVisibility(
                visible = showsScrollToBottomButton,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 12.dp),
            ) {
                ScrollToBottomButton(
                    showsNewContentBadge = hasUnseenContent,
                    onClick = {
                        shouldFollowLatest = true
                        cooldownUntil = null
                        hasUnseenContent = false
                        scope.launch { scrollToLatest(animated = true) }
                    },
                )
            }
        }
    }
}

/**
 * One transcript row plus its anchored reasoning/tool-call blocks (port of
 * `ChatTranscriptMessageBlock`). Cards render above the message content, as
 * on iOS: thinking and tool activity happened before the answer text.
 */
@Composable
private fun TranscriptMessageBlock(
    transcriptMessage: TranscriptMessage,
    state: ChatUiState,
    attachmentUrlResolver: suspend (path: String) -> String?,
    imageLoader: ImageLoader?,
    onListen: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val anchorId = transcriptMessage.anchorID
    val message = transcriptMessage.message
    val isLiveAnchor = state.isStreaming && state.streamingAssistantMessageId == anchorId
    val isStreamingRow = state.isStreaming &&
        message.role == "assistant" &&
        state.streamingAssistantMessageId != null &&
        message.messageId == state.streamingAssistantMessageId

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (state.showsThinkingAndToolCards) {
            state.completedReasoningGroups
                .filter { it.anchorMessageID == anchorId }
                .forEach { group ->
                    ReasoningBlock(text = group.text, startExpanded = state.thinkingCardsStartExpanded)
                }

            if (isLiveAnchor && state.liveReasoningText.isNotBlank()) {
                ReasoningBlock(
                    text = state.liveReasoningText,
                    startExpanded = state.thinkingCardsStartExpanded,
                    isStreaming = true,
                )
            }

            state.completedToolCallGroups
                .filter { it.anchorMessageID == anchorId }
                .forEach { group ->
                    ToolCallGroupCard(group = group, startExpanded = state.toolCardsStartExpanded)
                }

            if (isLiveAnchor && state.liveToolCalls.isNotEmpty()) {
                ToolCallGroupCard(
                    group = ToolCallGroup.live(
                        anchorMessageID = anchorId,
                        toolCalls = state.liveToolCalls,
                    ),
                    startExpanded = state.toolCardsStartExpanded,
                )
            }
        }

        // Compaction marker messages render as collapsible cards, never as
        // user bubbles (matching the web UI and iOS).
        val classification = ChatMarkerMessageClassifier.classification(message)
        if (classification != null) {
            MarkerMessageCard(classification = classification)
        } else {
            MessageBubble(
                message = message,
                state = state,
                isStreaming = isStreamingRow,
                liveTokensPerSecond = if (isStreamingRow) state.liveTokensPerSecond else null,
                attachmentUrlResolver = attachmentUrlResolver,
                imageLoader = imageLoader,
                onListen = onListen,
            )
        }
    }
}

/**
 * Port of `ChatTranscriptDisplaySettings.shouldShowAssistantTypingIndicator`:
 * a breathing dot fills the gap between send and the first visible response
 * content.
 */
private fun shouldShowAssistantTypingIndicator(state: ChatUiState): Boolean {
    if (!state.isStreaming || state.isCancellingStream) return false
    if (state.streamingAssistantMessageId != null) return false
    if (state.clarificationPrompt != null) return false
    if (!state.showsThinkingAndToolCards) return true
    if (state.liveReasoningText.isNotBlank()) return false
    return state.liveToolCalls.isEmpty()
}

@Composable
private fun AssistantTypingIndicator(modifier: Modifier = Modifier) {
    val reduceMotion = rememberReduceMotion()
    val dotColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)

    if (reduceMotion) {
        Box(
            modifier = modifier
                .padding(start = 4.dp, top = 8.dp, bottom = 8.dp)
                .size(16.dp)
                .alpha(0.75f)
                .background(dotColor, CircleShape),
        )
        return
    }

    val transition = rememberInfiniteTransition(label = "typing-indicator")
    val breathing by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 700),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "breathing",
    )

    Box(
        modifier = modifier
            .padding(start = 4.dp, top = 8.dp, bottom = 8.dp)
            .size(16.dp)
            .scale(0.86f + 0.30f * breathing)
            .alpha(0.55f + 0.40f * breathing)
            .background(dotColor, CircleShape),
    )
}

@Composable
private fun LoadOlderMessagesButton(
    isLoading: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceVariant,
            border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.32f)),
        ) {
            Row(
                modifier = Modifier
                    .clickable(enabled = !isLoading, onClick = onClick)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(12.dp),
                        strokeWidth = 1.5.dp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Icon(
                        imageVector = Icons.Rounded.ArrowUpward,
                        contentDescription = null,
                        modifier = Modifier.size(12.dp),
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Text(
                    text = if (isLoading) "Loading older messages" else "Load older messages",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
            }
        }
    }
}

/** Port of `ChatOfflineCacheBanner` (iOS system-orange tint, no theme slot). */
@Composable
private fun OfflineCacheBanner(modifier: Modifier = Modifier) {
    val orange = Color(0xFFFF9500)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(orange.copy(alpha = 0.12f))
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = Icons.Rounded.WifiOff,
            contentDescription = null,
            tint = orange,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = "Offline — viewing cached version",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = orange,
        )
    }
}

/** Port of `StreamRecoveryStatusView`. */
@Composable
private fun StreamRecoveryStatusPill(
    recoveryState: ActiveStreamRecoveryState,
    modifier: Modifier = Modifier,
) {
    val label = when (recoveryState) {
        ActiveStreamRecoveryState.IDLE -> "Stream active"
        ActiveStreamRecoveryState.CHECKING -> "Checking stream"
        ActiveStreamRecoveryState.RECONNECTING -> "Reconnecting stream"
    }

    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceVariant,
            border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(12.dp),
                    strokeWidth = 1.5.dp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

/** Port of `PinnedLocalNoticeStack`: one bordered row per pending notice. */
@Composable
private fun PinnedLocalNoticeRows(notices: List<String>, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        notices.forEach { notice ->
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.CheckCircle,
                        contentDescription = null,
                        tint = if (isSystemInDarkTheme()) SuccessDark else SuccessLight,
                        modifier = Modifier.size(17.dp),
                    )
                    Text(
                        text = notice,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun ScrollToBottomButton(
    showsNewContentBadge: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        Surface(
            onClick = onClick,
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceVariant,
            border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.32f)),
            shadowElevation = 6.dp,
            modifier = Modifier.size(36.dp),
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                Icon(
                    imageVector = Icons.Rounded.ArrowDownward,
                    contentDescription = "Scroll to latest message",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(16.dp),
                )
            }
        }

        if (showsNewContentBadge) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(9.dp)
                    .background(MaterialTheme.colorScheme.primary, CircleShape),
            )
        }
    }
}
