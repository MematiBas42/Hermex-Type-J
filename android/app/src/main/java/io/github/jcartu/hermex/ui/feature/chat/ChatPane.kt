package io.github.jcartu.hermex.ui.feature.chat
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.window.layout.FoldingFeature
import coil.ImageLoader
import io.github.jcartu.hermex.HermexApplication
import io.github.jcartu.hermex.core.media.AssistantSpeaker
import io.github.jcartu.hermex.net.SseChatStream
import io.github.jcartu.hermex.ui.adaptive.FoldState
import io.github.jcartu.hermex.ui.adaptive.HingeAwarePaneRow
import io.github.jcartu.hermex.ui.adaptive.LocalFoldState
import io.github.jcartu.hermex.service.AgentRunService
import io.github.jcartu.hermex.ui.adaptive.LocalPaneCount

/**
 * The chat surface (port of `ChatView.swift`'s composition): transcript +
 * composer + streaming + pending-action overlays, adaptive across the
 * trifold's postures.
 *
 * Layout modes:
 * - 1–2 panes: single column — transcript over run-status/context accessories
 *   over composer. In the half-opened book posture the split is pinned to the
 *   horizontal hinge: transcript above the fold, composer below it.
 * - 3 panes (this pane spans two panels beside the sessions list):
 *   [HingeAwarePaneRow] with two regions snapped to the physical hinge —
 *   transcript | composer + run controls + context indicator — sharing one
 *   [ChatViewModel], so state flows live between the regions.
 *
 * Session selection arrives via [initialSessionId] changes; the ViewModel
 * reloads the transcript while preserving the composer draft (iOS deep-link
 * behavior). Scroll position and selection survive pane-count changes:
 * [rememberLazyListState] is saveable and the ViewModel outlives postures.
 */
@Composable
fun ChatPane(
    initialSessionId: String?,
    initialNewChat: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val container = remember(context) {
        (context.applicationContext as HermexApplication).container
    }

    val viewModel: ChatViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                ChatViewModel(
                    api = container.api,
                    sse = SseChatStream(
                        httpClient = container.okHttpClient,
                        json = container.json,
                        api = container.api,
                        customHeaderProvider = { container.customHeaderStore.snapshot() },
                    ),
                    cacheStore = container.cacheStore,
                    serverConfig = container.serverConfig,
                    settings = container.settings,
                    favorites = container.modelFavorites,
                    recents = container.modelRecents,
                    initialSessionId = initialSessionId,
                    initialNewChat = initialNewChat,
                )
            }
        },
    )

    // Outside selection changes reload the transcript; switchToSession is
    // idempotent for the already-active session and preserves the draft.
    LaunchedEffect(initialSessionId, initialNewChat) {
        viewModel.switchToSession(initialSessionId, initialNewChat)
    }

    val sharedImport by container.sharedImports.pending.collectAsStateWithLifecycle()
    LaunchedEffect(sharedImport?.id, viewModel) {
        val value = sharedImport ?: return@LaunchedEffect
        if (viewModel.applySharedImport(value)) {
            container.sharedImports.acknowledge(value.id)
        }
    }

    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val localSettings by container.settings.settings.collectAsStateWithLifecycle(
        initialValue = io.github.jcartu.hermex.core.prefs.SettingsRepository.Settings(),
    )
    val latestState = rememberUpdatedState(state)
    val latestNotificationsEnabled = rememberUpdatedState(localSettings.backgroundRunNotificationsEnabled)
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, viewModel, context) {
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            latestState.value.activeStreamId?.let { AgentRunService.stop(context, it) }
            viewModel.resumeForForeground()
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> {
                    latestState.value.activeStreamId?.let { AgentRunService.stop(context, it) }
                    viewModel.resumeForForeground()
                }
                Lifecycle.Event.ON_STOP -> {
                    val current = latestState.value
                    val permissionGranted =
                        Build.VERSION.SDK_INT < 33 ||
                            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                            PackageManager.PERMISSION_GRANTED
                    if (
                        latestNotificationsEnabled.value &&
                        permissionGranted &&
                        current.activeStreamId != null &&
                        current.sessionId != null
                    ) {
                        io.github.jcartu.hermex.core.logging.HermexLog.d(
                            "Handing active stream to AgentRunService",
                        )
                        AgentRunService.start(
                            context = context,
                            streamId = current.activeStreamId,
                            sessionId = current.sessionId,
                            title = current.displayTitle.ifBlank { "Hermes run" },
                        )
                    }
                    viewModel.suspendStreamForBackground()
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.suspendStreamForBackground()
        }
    }
    val listState = rememberLazyListState()

    // Coil loader sharing the app's OkHttp client, so `/api/file/raw` image
    // requests carry the auth cookies + custom headers.
    val imageLoader = remember(container) {
        ImageLoader.Builder(context)
            .okHttpClient(container.okHttpClient)
            .crossfade(true)
            .build()
    }
    val assistantSpeaker = remember(container) { AssistantSpeaker(context, container.api) }
    DisposableEffect(assistantSpeaker) {
        onDispose { assistantSpeaker.release() }
    }
    val currentSessionId = rememberUpdatedState(state.sessionId)
    val attachmentUrlResolver: suspend (String) -> String? = remember(container) {
        { path ->
            val sessionId = currentSessionId.value
            if (sessionId.isNullOrEmpty()) {
                null
            } else {
                runCatching { container.api.rawFileUrl(sessionId, path).toString() }.getOrNull()
            }
        }
    }

    val paneCount = LocalPaneCount.current
    val fold = LocalFoldState.current

    Box(modifier = modifier.fillMaxSize()) {
        if (paneCount >= 3) {
            TrifoldChatLayout(
                state = state,
                viewModel = viewModel,
                listState = listState,
                imageLoader = imageLoader,
                attachmentUrlResolver = attachmentUrlResolver,
                onListen = assistantSpeaker::speak,
            )
        } else {
            SingleRegionChatLayout(
                state = state,
                viewModel = viewModel,
                listState = listState,
                imageLoader = imageLoader,
                attachmentUrlResolver = attachmentUrlResolver,
                onListen = assistantSpeaker::speak,
                fold = fold,
            )
        }

        state.clarificationPrompt?.let { prompt ->
            ClarificationRequestOverlay(
                prompt = prompt,
                isResponding = state.isRespondingToClarification,
                errorMessage = state.clarificationErrorMessage,
                onSubmit = viewModel::respondToClarification,
            )
        }

        state.approvalPrompt?.let { prompt ->
            ApprovalRequestOverlay(
                prompt = prompt,
                isResponding = state.isRespondingToApproval,
                errorMessage = state.approvalErrorMessage,
                onChoice = viewModel::respondToApproval,
                onSkipAll = viewModel::skipApprovalsForCurrentSession,
            )
        }
    }
}

/**
 * Compact/medium layout: transcript over accessories over composer. In the
 * half-opened book posture the transcript ends at the horizontal hinge and the
 * composer region starts below it, so no interactive control straddles the
 * fold (AGENTS.md posture rule).
 */
@Composable
private fun SingleRegionChatLayout(
    state: ChatUiState,
    viewModel: ChatViewModel,
    listState: LazyListState,
    imageLoader: ImageLoader,
    attachmentUrlResolver: suspend (String) -> String?,
    onListen: (String) -> Unit,
    fold: FoldState,
) {
    val horizontalHinge = fold.features.firstOrNull {
        it.orientation == FoldingFeature.Orientation.HORIZONTAL &&
            it.state == FoldingFeature.State.HALF_OPENED
    }

    var paneTopInWindow by remember { mutableFloatStateOf(0f) }
    val density = LocalDensity.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .onGloballyPositioned { paneTopInWindow = it.positionInWindow().y },
    ) {
        if (horizontalHinge != null) {
            // Book posture: transcript fills exactly up to the hinge top,
            // then a spacer covers the occluded strip.
            val hingeTopDp = with(density) {
                (horizontalHinge.bounds.top - paneTopInWindow).coerceAtLeast(0f).toDp()
            }
            val hingeHeightDp = with(density) {
                horizontalHinge.bounds.height().toFloat().coerceAtLeast(0f).toDp()
            }
            ChatTranscript(
                state = state,
                listState = listState,
                attachmentUrlResolver = attachmentUrlResolver,
                imageLoader = imageLoader,
                onListen = onListen,
                onLoadOlder = viewModel::loadOlderMessages,
                onRetry = viewModel::retryLoad,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(hingeTopDp),
            )
            Spacer(Modifier.height(hingeHeightDp))
            ComposerRegion(
                state = state,
                viewModel = viewModel,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            )
        } else {
            ChatTranscript(
                state = state,
                listState = listState,
                attachmentUrlResolver = attachmentUrlResolver,
                imageLoader = imageLoader,
                onListen = onListen,
                onLoadOlder = viewModel::loadOlderMessages,
                onRetry = viewModel::retryLoad,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            )
            ComposerRegion(
                state = state,
                viewModel = viewModel,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * Trifold layout: two regions whose boundary snaps to a physical hinge —
 * transcript | composer + run controls + context indicator. Both regions read
 * the same ViewModel, so streaming state flows between them live.
 */
@Composable
private fun TrifoldChatLayout(
    state: ChatUiState,
    viewModel: ChatViewModel,
    listState: LazyListState,
    imageLoader: ImageLoader,
    attachmentUrlResolver: suspend (String) -> String?,
    onListen: (String) -> Unit,
) {
    HingeAwarePaneRow(paneCount = 2) { index, paneModifier ->
        when (index) {
            0 -> ChatTranscript(
                state = state,
                listState = listState,
                attachmentUrlResolver = attachmentUrlResolver,
                imageLoader = imageLoader,
                onListen = onListen,
                onLoadOlder = viewModel::loadOlderMessages,
                onRetry = viewModel::retryLoad,
                modifier = paneModifier,
            )

            else -> Box(paneModifier.imePadding()) {
                ComposerRegion(
                    state = state,
                    viewModel = viewModel,
                    // ~480dp readable width, top-aligned beside the transcript.
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .widthIn(max = 480.dp)
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                )
            }
        }
    }
}

/**
 * The composer plus its accessory stack (port of the iOS
 * `composerAccessoryStack`): active-run status row and the context-window
 * indicator sit directly above the composer.
 */
@Composable
private fun ComposerRegion(
    state: ChatUiState,
    viewModel: ChatViewModel,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        ChatActiveRunStatusRow(
            state = state,
            onStop = viewModel::stopStream,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        )
        state.contextWindow?.let { snapshot ->
            ContextWindowIndicator(
                snapshot = snapshot,
                modifier = Modifier
                    .align(Alignment.End)
                    .padding(horizontal = 16.dp, vertical = 2.dp),
            )
        }
        ChatComposer(
            state = state,
            actions = viewModel,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
