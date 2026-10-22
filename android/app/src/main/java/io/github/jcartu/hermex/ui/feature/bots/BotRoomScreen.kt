package io.github.jcartu.hermex.ui.feature.bots

import android.graphics.Bitmap
import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jcartu.hermex.model.hermes.BotGroupRoom
import io.github.jcartu.hermex.model.hermes.BotProfile
import io.github.jcartu.hermex.model.hermes.BotRoomAction
import io.github.jcartu.hermex.model.hermes.BotRoomEvent
import io.github.jcartu.hermex.model.hermes.BotRoomThread
import io.github.jcartu.hermex.model.hermes.BotRoomTranscriptWindow
import io.github.jcartu.hermex.net.hermes.BotApprovalChoice
import io.github.jcartu.hermex.ui.adaptive.HingeAwarePaneRow
import io.github.jcartu.hermex.ui.adaptive.LocalPaneCount
import io.github.jcartu.hermex.ui.feature.chat.TranscriptMarkdown
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.ChronoUnit
import java.util.Date
import java.util.Locale
import java.util.UUID

/** UI callbacks kept outside the pure room content so previews/tests never open a socket. */
data class BotRoomScreenActions(
    val onBack: () -> Unit = {},
    val onSelectThread: (String?) -> Unit = {},
    val onShowProfile: () -> Unit = {},
    val onDraftChange: (String?, String) -> Unit = { _, _ -> },
    val onSend: (String?, Boolean) -> Unit = { _, _ -> },
    val onStop: () -> Unit = {},
    val onAct: (BotRoomAction, BotApprovalChoice?) -> Unit = { _, _ -> },
    val onLoadEarlier: () -> Unit = {},
    val onReconnect: () -> Unit = {},
    val onUpdateSignIn: () -> Unit = {},
    val onDismissCommand: () -> Unit = {},
    val onRename: (String) -> Unit = {},
    val onDisband: () -> Unit = {},
    val onOpenBot: ((BotProfile) -> Unit)? = null,
)

data class BotRoomPresentation(
    val state: BotRoomReader.State,
    val connectionName: String,
    val showsMessageTimestamps: Boolean = false,
    val wrapsCodeBlockLines: Boolean = false,
    val showsWelcome: Boolean,
    val showsComposer: Boolean,
    val showsStop: Boolean,
    val mayStop: Boolean,
    val showsRename: Boolean,
    val showsDisband: Boolean,
    val mayRename: Boolean,
    val mayDisband: Boolean,
    val maySend: (String?) -> Boolean,
    val mayResend: (String?) -> Boolean,
    val mayEditDraft: (String?) -> Boolean,
    val mayAct: (BotRoomAction) -> Boolean,
)

/**
 * Port of `BotRoomView.swift`. The reader remains the sole owner of network state while this
 * screen presents overview/thread/profile at pane counts 1/2/3. No hidden group session is ever
 * opened; every user action delegates to the reader's guarded `groups.*` methods.
 */
@Composable
fun BotRoomScreen(
    reader: BotRoomReader,
    roster: List<BotProfile>,
    avatars: Map<String, Bitmap>,
    onBack: () -> Unit,
    onUpdateSignIn: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenBot: ((BotProfile) -> Unit)? = null,
    showsMessageTimestamps: Boolean = false,
    wrapsCodeBlockLines: Boolean = false,
) {
    val state by reader.state.collectAsStateWithLifecycle()
    val coroutineScope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    val paneCount = LocalPaneCount.current
    val owner = rememberSaveable(reader.key.server, reader.key.connectionID, reader.key.roomID) { UUID.randomUUID().toString() }
    var reconnectRevision by rememberSaveable(reader.key.server, reader.key.connectionID, reader.key.roomID) { mutableIntStateOf(0) }
    var selectedThread by rememberSaveable(reader.key.server, reader.key.connectionID, reader.key.roomID) { mutableStateOf<String?>(null) }
    var showsProfile by rememberSaveable(reader.key.server, reader.key.connectionID, reader.key.roomID) { mutableStateOf(false) }
    BackHandler(enabled = paneCount == 1 && selectedThread != null) { selectedThread = null }

    LaunchedEffect(reader, owner, reconnectRevision) {
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            reader.open(owner = owner, preservingLoadedHistory = true)
        }
    }
    DisposableEffect(reader, lifecycleOwner, owner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> if (reader.current.link == BotRoomReader.Link.IDLE) reconnectRevision += 1
                Lifecycle.Event.ON_STOP -> reader.leaveAsync(owner, preservingLoadedHistory = true)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            reader.leaveAsync(owner, preservingLoadedHistory = true)
        }
    }
    LaunchedEffect(state.events, reader.initialSequence) {
        val target = reader.initialSequence ?: return@LaunchedEffect
        state.events.firstOrNull { it.seq == target }?.threadID?.let { selectedThread = it }
    }
    LaunchedEffect(state.commandMessage, state.errorMessage) {
        if (state.commandMessage == null && state.errorMessage == null) return@LaunchedEffect
        delay(5_000)
        reader.dismissCommandMessage()
    }

    val presentation = BotRoomPresentation(
        state = state,
        connectionName = reader.connection.name,
        showsMessageTimestamps = showsMessageTimestamps,
        wrapsCodeBlockLines = wrapsCodeBlockLines,
        showsWelcome = reader.showsWelcome,
        showsComposer = reader.showsComposer,
        showsStop = reader.showsStop,
        mayStop = reader.mayStop,
        showsRename = reader.showsRename,
        showsDisband = reader.showsDisband,
        mayRename = reader.mayRename,
        mayDisband = reader.mayDisband,
        maySend = reader::maySend,
        mayResend = reader::mayResend,
        mayEditDraft = reader::mayEditDraft,
        mayAct = reader::mayAct,
    )
    val actions = BotRoomScreenActions(
        onBack = {
            if (paneCount == 1 && selectedThread != null) selectedThread = null else onBack()
        },
        onSelectThread = { selectedThread = it },
        onShowProfile = { showsProfile = true },
        onDraftChange = { threadID, text -> reader.setDraft(text, threadID) },
        onSend = { thread, retry -> coroutineScope.launch { reader.send(retry = retry, threadID = thread) } },
        onStop = { coroutineScope.launch { reader.stop() } },
        onAct = { action, choice -> coroutineScope.launch { reader.act(action, choice) } },
        onLoadEarlier = { coroutineScope.launch { reader.loadEarlier() } },
        onReconnect = { reconnectRevision += 1 },
        onUpdateSignIn = onUpdateSignIn,
        onDismissCommand = reader::dismissCommandMessage,
        onRename = { name -> coroutineScope.launch { reader.rename(name) } },
        onDisband = { coroutineScope.launch { reader.disband() } },
        onOpenBot = onOpenBot,
    )

    BotRoomScreenContent(
        presentation = presentation,
        roster = roster,
        avatars = avatars,
        selectedThreadID = selectedThread,
        initialSequence = reader.initialSequence,
        actions = actions,
        modifier = modifier,
    )
    if (showsProfile) {
        BotRoomProfileDialog(
            presentation = presentation,
            roster = roster,
            avatars = avatars,
            actions = actions,
            onDismiss = { showsProfile = false },
        )
    }
}

/** Pure pane-count content: no lifecycle hook and no transport construction. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BotRoomScreenContent(
    presentation: BotRoomPresentation,
    roster: List<BotProfile>,
    avatars: Map<String, Bitmap>,
    selectedThreadID: String?,
    initialSequence: Int?,
    actions: BotRoomScreenActions,
    modifier: Modifier = Modifier,
    paneCount: Int = LocalPaneCount.current,
) {
    val state = presentation.state
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        modifier = Modifier.clickable(onClick = actions.onShowProfile),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        BotRoomAvatars(state.room, roster, avatars, 30.dp)
                        Column {
                            if (selectedThreadID != null && paneCount == 1) {
                                Text("Thread", style = MaterialTheme.typography.labelMedium)
                            }
                            Text(state.room.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = actions.onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = if (selectedThreadID != null && paneCount == 1) "Back to room" else "Back")
                    }
                },
                actions = {
                    IconButton(onClick = actions.onShowProfile) {
                        Icon(Icons.Filled.Info, contentDescription = "Room profile")
                    }
                },
            )
        },
    ) { padding ->
        val body = Modifier.fillMaxSize().padding(padding)
        when {
            paneCount <= 1 -> {
                if (selectedThreadID == null) {
                    RoomOverviewPane(presentation, roster, avatars, initialSequence, true, actions, body.semantics { contentDescription = "room-pane-overview" })
                } else {
                    RoomThreadPane(presentation, roster, avatars, selectedThreadID, initialSequence, true, actions, body.semantics { contentDescription = "room-pane-thread" })
                }
            }
            paneCount == 2 -> HingeAwarePaneRow(2, body) { index, paneModifier ->
                if (index == 0) {
                    RoomOverviewPane(presentation, roster, avatars, initialSequence, selectedThreadID == null, actions, paneModifier.semantics { contentDescription = "room-pane-overview" })
                } else if (selectedThreadID != null) {
                    RoomThreadPane(presentation, roster, avatars, selectedThreadID, initialSequence, true, actions, paneModifier.semantics { contentDescription = "room-pane-thread" })
                } else {
                    RoomSelectionPlaceholder(state.room, roster, avatars, paneModifier.semantics { contentDescription = "room-pane-thread" })
                }
            }
            else -> HingeAwarePaneRow(3, body) { index, paneModifier ->
                when (index) {
                    0 -> BotRoomProfilePane(presentation, roster, avatars, actions, paneModifier.semantics { contentDescription = "room-pane-profile" })
                    1 -> RoomOverviewPane(presentation, roster, avatars, initialSequence, selectedThreadID == null, actions, paneModifier.semantics { contentDescription = "room-pane-overview" })
                    else -> if (selectedThreadID != null) {
                        RoomThreadPane(presentation, roster, avatars, selectedThreadID, initialSequence, true, actions, paneModifier.semantics { contentDescription = "room-pane-thread" })
                    } else {
                        RoomSelectionPlaceholder(state.room, roster, avatars, paneModifier.semantics { contentDescription = "room-pane-thread" })
                    }
                }
            }
        }
    }
}

@Composable
private fun RoomOverviewPane(
    presentation: BotRoomPresentation,
    roster: List<BotProfile>,
    avatars: Map<String, Bitmap>,
    initialSequence: Int?,
    activeComposer: Boolean,
    actions: BotRoomScreenActions,
    modifier: Modifier,
) {
    val state = presentation.state
    val events = remember(state.events, state.threads) {
        (state.events.filter { it.threadID == null } + state.threads.map(BotRoomThread::latest)).sortedBy(BotRoomEvent::seq)
    }
    RoomPaneScaffold(
        modifier = modifier,
        presentation = presentation,
        threadID = null,
        followsLatest = false,
        activeComposer = activeComposer,
        actions = actions,
    ) {
        if (state.foreignAuthority) RoomSystemLine("Managed by another Hermes")
        if (presentation.showsWelcome && state.threads.isEmpty()) {
            BotRoomWelcome(state.room, roster, avatars, presentation.showsComposer)
        }
        BoundedRoomTranscript(
            events = events,
            overview = true,
            hasEarlier = state.hasEarlier,
            loadingEarlier = state.loadingEarlier,
            live = state.link == BotRoomReader.Link.LIVE,
            keepingSequence = initialSequence,
            onLoadEarlier = actions.onLoadEarlier,
        ) { event ->
            val thread = state.threads.firstOrNull { it.latest.seq == event.seq }
            if (thread != null) {
                BotRoomThreadPreview(thread, state.room) { actions.onSelectThread(thread.id) }
            } else {
                BotRoomEventRow(
                    event, state.room, roster, avatars, initialSequence == event.seq,
                    presentation.showsMessageTimestamps, presentation.wrapsCodeBlockLines,
                )
            }
        }
    }
}

@Composable
private fun RoomThreadPane(
    presentation: BotRoomPresentation,
    roster: List<BotProfile>,
    avatars: Map<String, Bitmap>,
    threadID: String,
    initialSequence: Int?,
    activeComposer: Boolean,
    actions: BotRoomScreenActions,
    modifier: Modifier,
) {
    val state = presentation.state
    val thread = state.threads.firstOrNull { it.id == threadID }
    RoomPaneScaffold(
        modifier = modifier,
        presentation = presentation,
        threadID = threadID,
        followsLatest = initialSequence == null,
        activeComposer = activeComposer,
        actions = actions,
    ) {
        if (thread == null || thread.root == null) RoomSystemLine("Earlier messages not loaded")
        BoundedRoomTranscript(
            events = thread?.events.orEmpty(),
            overview = false,
            hasEarlier = state.hasEarlier,
            loadingEarlier = state.loadingEarlier,
            live = state.link == BotRoomReader.Link.LIVE,
            keepingSequence = initialSequence,
            onLoadEarlier = actions.onLoadEarlier,
        ) { event ->
            BotRoomEventRow(
                event, state.room, roster, avatars, initialSequence == event.seq,
                presentation.showsMessageTimestamps, presentation.wrapsCodeBlockLines,
            )
        }
    }
}

@Composable
private fun RoomPaneScaffold(
    modifier: Modifier,
    presentation: BotRoomPresentation,
    threadID: String?,
    followsLatest: Boolean,
    activeComposer: Boolean,
    actions: BotRoomScreenActions,
    transcript: @Composable () -> Unit,
) {
    val state = presentation.state
    val transcriptScroll = rememberScrollState()
    val paneScope = rememberCoroutineScope()
    LaunchedEffect(state.events.lastOrNull()?.seq, threadID, followsLatest) {
        if (!followsLatest) return@LaunchedEffect
        val wasNearBottom = transcriptScroll.value >= transcriptScroll.maxValue - 96
        withFrameNanos { }
        if (wasNearBottom) transcriptScroll.scrollTo(transcriptScroll.maxValue)
    }
    Column(modifier.fillMaxHeight().background(MaterialTheme.colorScheme.background)) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            Column(
                Modifier.fillMaxSize().verticalScroll(transcriptScroll).padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                transcript()
                if (activeComposer) {
                    state.status.actions.forEach { action ->
                        BotRoomActionCard(
                            action = action,
                            room = state.room,
                            connectionName = presentation.connectionName,
                            enabled = presentation.mayAct(action),
                            busy = state.busy && action.id in state.inactiveActions,
                            canStop = presentation.mayStop,
                            onAct = actions.onAct,
                            onStop = actions.onStop,
                        )
                    }
                }
            }
        }
        if (activeComposer && presentation.showsComposer) {
            HorizontalDivider()
            BotRoomComposer(
                room = state.room,
                threadID = threadID,
                draft = state.drafts[threadID.orEmpty()].orEmpty(),
                errorMessage = state.commandMessage ?: state.errorMessage,
                link = state.link,
                needsSignIn = state.needsSignIn,
                blocked = state.status.blocked,
                hasActions = state.status.actions.isNotEmpty(),
                showsStop = presentation.showsStop,
                mayStop = presentation.mayStop,
                maySend = presentation.maySend(threadID),
                mayRetry = presentation.mayResend(threadID),
                mayEdit = presentation.mayEditDraft(threadID),
                onDraftChange = { actions.onDraftChange(threadID, it) },
                onSend = { actions.onSend(threadID, false) },
                onRetry = { actions.onSend(threadID, true) },
                onStop = actions.onStop,
                onReconnect = actions.onReconnect,
                onUpdateSignIn = actions.onUpdateSignIn,
                onDismissError = actions.onDismissCommand,
                onShowRequest = { paneScope.launch { transcriptScroll.animateScrollTo(transcriptScroll.maxValue) } },
            )
        }
    }
}

@Composable
private fun BoundedRoomTranscript(
    events: List<BotRoomEvent>,
    overview: Boolean,
    hasEarlier: Boolean,
    loadingEarlier: Boolean,
    live: Boolean,
    keepingSequence: Int?,
    onLoadEarlier: () -> Unit,
    row: @Composable (BotRoomEvent) -> Unit,
) {
    var window by remember(overview) { mutableStateOf(BotRoomTranscriptWindow()) }
    var waitingForNetwork by remember { mutableStateOf(false) }
    var previousSize by remember { mutableIntStateOf(0) }
    LaunchedEffect(events.firstOrNull()?.seq, events.lastOrNull()?.seq, live) {
        window = window.seed(events, live, overview)
        if (waitingForNetwork && events.size > previousSize) {
            window.showEarlier(events, overview)?.let { window = it }
            waitingForNetwork = false
        }
    }
    val start = window.start(events, keepingSequence, overview)
    val hidden = start > 0
    if (!overview && (hidden || hasEarlier)) {
        TextButton(
            onClick = {
                val revealed = window.showEarlier(events, overview)
                if (revealed != null) window = revealed else {
                    previousSize = events.size
                    waitingForNetwork = true
                    onLoadEarlier()
                }
            },
            enabled = hidden || (!loadingEarlier && live),
            modifier = Modifier,
        ) {
            if (loadingEarlier && !hidden) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            Text("Load earlier")
        }
    }
    val shown = if (events.isEmpty()) emptyList() else events.subList(start, events.size)
    val ordered = if (overview) shown.asReversed() else shown
    ordered.forEach(row)
    if (overview && (hidden || hasEarlier)) {
        TextButton(
            onClick = {
                val revealed = window.showEarlier(events, overview)
                if (revealed != null) window = revealed else {
                    previousSize = events.size
                    waitingForNetwork = true
                    onLoadEarlier()
                }
            },
            enabled = hidden || (!loadingEarlier && live),
            modifier = Modifier,
        ) { Text(if (loadingEarlier && !hidden) "Loading…" else "Load earlier") }
    }
}

@Composable
private fun BotRoomThreadPreview(thread: BotRoomThread, room: BotGroupRoom, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(thread.root?.let { if (it.kind == "message.user") "You" else it.sender(room) } ?: "Thread", style = MaterialTheme.typography.labelMedium)
                thread.latest.timestamp?.let { Text(roomRelativeTime(it), style = MaterialTheme.typography.labelSmall) }
            }
            Text(thread.root?.payload?.get("text")?.text ?: "Earlier messages not loaded", maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                if (thread.root == null) "Loaded replies: ${thread.replyCount}" else "Replies: ${thread.replyCount}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun BotRoomEventRow(
    event: BotRoomEvent,
    room: BotGroupRoom,
    roster: List<BotProfile>,
    avatars: Map<String, Bitmap>,
    highlighted: Boolean,
    showsMessageTimestamps: Boolean,
    wrapsCodeBlockLines: Boolean,
) {
    @Suppress("DEPRECATION") val clipboard = LocalClipboardManager.current
    val text = event.payload["text"].text.orEmpty()
    val copyAction = remember(text, clipboard) {
        botRoomMessageActions(text) { source -> clipboard.setText(AnnotatedString(source)) }.firstOrNull()
    }
    if (event.kind == "message.user") {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
            Surface(
                modifier = Modifier.widthIn(max = 560.dp),
                shape = RoundedCornerShape(18.dp),
                color = if (highlighted) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.primaryContainer,
            ) {
                androidx.compose.foundation.text.selection.SelectionContainer {
                    TranscriptMarkdown(
                        content = text.ifEmpty { " " },
                        wrapCodeLines = wrapsCodeBlockLines,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    )
                }
            }
            RoomMessageFooter(if (showsMessageTimestamps) event.timestamp else null, copyAction)
        }
    } else if (event.kind == "message.member") {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            BotRoomMemberAvatar(event.member(room), roster, avatars, 28.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(event.sender(room), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = if (highlighted) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                    border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    androidx.compose.foundation.text.selection.SelectionContainer {
                        TranscriptMarkdown(
                            content = text.ifEmpty { " " },
                            wrapCodeLines = wrapsCodeBlockLines,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        )
                    }
                }
                RoomMessageFooter(if (showsMessageTimestamps) event.timestamp else null, copyAction)
            }
        }
    } else {
        RoomSystemLine(event.systemText)
    }
}

@Composable
private fun RoomMessageFooter(timestamp: Double?, copyAction: BotRoomMessageAction?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        timestamp?.takeIf { it > 0 && it.isFinite() }?.let {
            Text(roomMessageTime(it), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (copyAction != null) {
            TextButton(onClick = copyAction.perform, enabled = copyAction.enabled) {
                Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(14.dp))
                Text(copyAction.title, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun RoomSystemLine(text: String) {
    if (text.isEmpty()) return
    Text(
        text,
        modifier = Modifier.fillMaxWidth(),
        textAlign = TextAlign.Center,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun BotRoomActionCard(
    action: BotRoomAction,
    room: BotGroupRoom,
    connectionName: String,
    enabled: Boolean,
    busy: Boolean,
    canStop: Boolean,
    onAct: (BotRoomAction, BotApprovalChoice?) -> Unit,
    onStop: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().widthIn(max = 560.dp),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val member = room.members.firstOrNull { it.id == action.id.member }
            Text(listOfNotNull(member?.name, room.name, connectionName).joinToString(" · "), style = MaterialTheme.typography.labelMedium)
            val approval = action.approval
            when {
                approval != null -> {
                    Text("Approval needed", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    approval.command?.let { Text(it, fontFamily = FontFamily.Monospace) }
                    approval.consequence?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (BotApprovalChoice.ONCE in approval.choices) {
                            Button(onClick = { onAct(action, BotApprovalChoice.ONCE) }, enabled = enabled) { Text("Allow once") }
                        }
                        if (BotApprovalChoice.DENY in approval.choices) {
                            TextButton(onClick = { onAct(action, BotApprovalChoice.DENY) }, enabled = enabled) { Text("Deny") }
                        }
                        if (busy) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    }
                }
                action.isRetry -> {
                    Text("A member's attempt needs an explicit retry.")
                    Button(onClick = { onAct(action, null) }, enabled = enabled) { Text("Retry") }
                }
                else -> {
                    Text("Needs attention", style = MaterialTheme.typography.titleMedium)
                    Text("Answer this request in Hermes Desktop on this same connection.")
                }
            }
            if (canStop) TextButton(onClick = onStop) { Icon(Icons.Filled.Stop, null); Text("Stop room") }
        }
    }
}

private data class RoomMention(val tag: String, val name: String)

private fun roomMentions(room: BotGroupRoom, query: String): List<RoomMention> {
    val members = room.members.mapNotNull { member ->
        member.handle?.takeIf { it.isNotEmpty() }?.let { RoomMention(it, member.name) }
    }
    val broadcast = listOf(RoomMention("all", "Everyone"), RoomMention("everyone", "Everyone"))
    return (members + broadcast).filter {
        query.isEmpty() || it.tag.contains(query, ignoreCase = true) || it.name.contains(query, ignoreCase = true)
    }.take(8)
}

private data class RoomMentionTrigger(val start: Int, val end: Int, val query: String)

private fun roomMentionTrigger(value: TextFieldValue): RoomMentionTrigger? {
    val caret = value.selection.start.coerceIn(0, value.text.length)
    var start = caret
    while (start > 0 && !value.text[start - 1].isWhitespace()) start -= 1
    var end = caret
    while (end < value.text.length && !value.text[end].isWhitespace()) end += 1
    val fragment = value.text.substring(start, caret)
    if (!fragment.startsWith("@")) return null
    return RoomMentionTrigger(start, end, fragment.drop(1))
}

@Composable
private fun BotRoomComposer(
    room: BotGroupRoom,
    threadID: String?,
    draft: String,
    errorMessage: String?,
    link: BotRoomReader.Link,
    needsSignIn: Boolean,
    blocked: Boolean,
    hasActions: Boolean,
    showsStop: Boolean,
    mayStop: Boolean,
    maySend: Boolean,
    mayRetry: Boolean,
    mayEdit: Boolean,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onRetry: () -> Unit,
    onStop: () -> Unit,
    onReconnect: () -> Unit,
    onUpdateSignIn: () -> Unit,
    onDismissError: () -> Unit,
    onShowRequest: () -> Unit,
) {
    var fieldValue by remember(threadID) {
        mutableStateOf(TextFieldValue(draft, selection = TextRange(draft.length)))
    }
    var focused by remember(threadID) { mutableStateOf(false) }
    LaunchedEffect(draft) {
        if (fieldValue.text != draft) fieldValue = TextFieldValue(draft, selection = TextRange(draft.length))
    }
    val trigger = remember(fieldValue.text, fieldValue.selection) { roomMentionTrigger(fieldValue) }
    val completions = trigger?.let { roomMentions(room, it.query) }.orEmpty()
    Column(
        Modifier.fillMaxWidth().imePadding().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        when {
            errorMessage != null -> Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.errorContainer) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(errorMessage, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                    TextButton(onClick = onDismissError) { Text("Dismiss") }
                }
            }
            needsSignIn -> TextButton(onClick = onUpdateSignIn, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Update sign-in") }
            link == BotRoomReader.Link.STOPPED -> TextButton(onClick = onReconnect, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Icon(Icons.Filled.Refresh, null); Text("Reconnect")
            }
            mayRetry -> TextButton(onClick = onRetry, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Icon(Icons.Filled.Refresh, null); Text("Retry send")
            }
            link == BotRoomReader.Link.LIVE && blocked -> if (hasActions) {
                TextButton(onClick = onShowRequest, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                    Text("Waiting for your answer")
                }
            } else {
                Text(
                    "Waiting on Hermes Desktop",
                    Modifier.align(Alignment.CenterHorizontally),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
        if (focused && completions.isNotEmpty() && mayEdit) {
            Surface(shape = RoundedCornerShape(14.dp), tonalElevation = 4.dp) {
                Column {
                    completions.forEach { item ->
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                val active = requireNotNull(trigger)
                                val replacement = "@${item.tag} "
                                val nextText = fieldValue.text.replaceRange(active.start, active.end, replacement)
                                val caret = active.start + replacement.length
                                fieldValue = TextFieldValue(nextText, selection = TextRange(caret))
                                onDraftChange(nextText)
                            }.padding(horizontal = 14.dp, vertical = 9.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(item.name)
                            Text("@${item.tag}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = fieldValue,
                onValueChange = { next -> fieldValue = next; onDraftChange(next.text) },
                enabled = mayEdit,
                modifier = Modifier.weight(1f).onFocusChanged { focused = it.isFocused },
                placeholder = { Text(if (threadID == null) "Message ${room.name}" else "Reply in thread") },
                minLines = 1,
                maxLines = 6,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { if (maySend) onSend() }),
            )
            FilledIconButton(
                onClick = if (showsStop) onStop else onSend,
                enabled = if (showsStop) mayStop else maySend,
                modifier = Modifier.size(52.dp),
            ) {
                Icon(if (showsStop) Icons.Filled.Stop else Icons.Filled.Send, contentDescription = if (showsStop) "Stop every bot in this room" else "Send")
            }
        }
    }
}

@Composable
fun BotRoomAvatars(
    room: BotGroupRoom,
    roster: List<BotProfile>,
    avatars: Map<String, Bitmap>,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(-(size * 0.35f))) {
        val members = room.members.take(3)
        if (members.isEmpty()) BotRoomMemberAvatar(null, roster, avatars, size)
        else members.forEach { BotRoomMemberAvatar(it, roster, avatars, size) }
    }
}

@Composable
fun BotRoomMemberAvatar(
    member: BotGroupRoom.Member?,
    roster: List<BotProfile>,
    avatars: Map<String, Bitmap>,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    val profile = roster.firstOrNull { it.id == member?.profile }
    if (profile != null) {
        BotAvatarView(profile, avatars[profile.id], size, BotFaceMotion.Still, modifier = modifier)
    } else {
        Surface(modifier.size(size), shape = CircleShape, color = MaterialTheme.colorScheme.surfaceVariant, border = BorderStroke(1.dp, MaterialTheme.colorScheme.background)) {
            Box(contentAlignment = Alignment.Center) { Icon(Icons.Filled.Person, contentDescription = null, modifier = Modifier.size(size * 0.55f)) }
        }
    }
}

@Composable
private fun BotRoomWelcome(
    room: BotGroupRoom,
    roster: List<BotProfile>,
    avatars: Map<String, Bitmap>,
    showsPrompt: Boolean,
) {
    val accessibilityText = LocalDensity.current.fontScale >= 1.3f
    Column(Modifier.fillMaxWidth().padding(vertical = 28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
        val rows = if (accessibilityText) room.members.map { listOf(it) } else {
            val rowCount = (room.members.size + 2) / 3
            val perRow = if (rowCount == 0) 1 else (room.members.size + rowCount - 1) / rowCount
            room.members.chunked(perRow)
        }
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                row.forEach { member ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.widthIn(max = 110.dp)) {
                        BotRoomMemberAvatar(member, roster, avatars, 48.dp)
                        Text(member.name, maxLines = if (accessibilityText) 2 else 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                    }
                }
            }
        }
        if (showsPrompt) Text("Say something to the group", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun BotRoomInboxRow(
    room: BotGroupRoom,
    roster: List<BotProfile>,
    avatars: Map<String, Bitmap>,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        BotRoomAvatars(room, roster, avatars, 32.dp, Modifier.size(44.dp))
        Text(room.name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        room.updatedAt?.let { Text(roomInboxDateLabel(it), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun RoomSelectionPlaceholder(room: BotGroupRoom, roster: List<BotProfile>, avatars: Map<String, Bitmap>, modifier: Modifier) {
    Box(modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            BotRoomAvatars(room, roster, avatars, 64.dp)
            Text("Select a thread", style = MaterialTheme.typography.titleMedium)
        }
    }
}

private fun roomMessageTime(epochSeconds: Double): String =
    DateFormat.getTimeInstance(DateFormat.SHORT).format(Date((epochSeconds * 1_000).toLong()))

private fun roomRelativeTime(epochSeconds: Double): String = DateUtils.getRelativeTimeSpanString(
    (epochSeconds * 1_000).toLong(),
    System.currentTimeMillis(),
    DateUtils.MINUTE_IN_MILLIS,
    DateUtils.FORMAT_ABBREV_RELATIVE,
).toString()

private fun roomInboxDateLabel(epochSeconds: Double, now: Instant = Instant.now()): String {
    val zone = ZoneId.systemDefault()
    val value = Instant.ofEpochMilli((epochSeconds * 1_000).toLong()).atZone(zone)
    val current = now.atZone(zone)
    return when {
        value.toLocalDate() == current.toLocalDate() ->
            value.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))
        ChronoUnit.DAYS.between(value.toLocalDate(), current.toLocalDate()) in 1L..6L ->
            value.format(DateTimeFormatter.ofPattern("EEEE", Locale.getDefault()))
        else -> value.format(DateTimeFormatter.ofPattern("MMM d", Locale.getDefault()))
    }
}
