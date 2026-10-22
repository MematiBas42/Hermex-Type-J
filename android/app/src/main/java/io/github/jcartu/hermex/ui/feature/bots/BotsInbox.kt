package io.github.jcartu.hermex.ui.feature.bots

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.ui.graphics.luminance
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.jcartu.hermex.HermexApplication
import io.github.jcartu.hermex.di.AppContainer
import io.github.jcartu.hermex.model.ServerAccount
import io.github.jcartu.hermex.model.ServerKind
import io.github.jcartu.hermex.model.hermes.BotConnection
import io.github.jcartu.hermex.model.hermes.BotGroupRoom
import io.github.jcartu.hermex.model.hermes.BotJSON
import io.github.jcartu.hermex.model.hermes.BotProfile
import io.github.jcartu.hermex.model.hermes.BotTitleFace
import io.github.jcartu.hermex.net.hermes.BotClient
import io.github.jcartu.hermex.ui.adaptive.FirstPaneSplitRow
import io.github.jcartu.hermex.ui.adaptive.LocalPaneCount
import io.github.jcartu.hermex.ui.components.EmptyState
import io.github.jcartu.hermex.ui.feature.chat.hermes.HermesChatPane
import io.github.jcartu.hermex.ui.feature.chat.hermes.HermesTitleFace
import io.github.jcartu.hermex.ui.feature.hermes.connect.HermesConnectionForm
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Port of `BotsInboxView`: the Hermes home's Bots side, or a legacy webui server's pushed
 * Bots utility. All bot entry points use the regular Hermes chat; group rooms alone own a
 * different reader. The ViewModel retains selection, editors and room drafts across posture
 * changes. The home provides its header and overlays its bottom bar over the list only.
 */
@Composable
fun BotsInbox(
    account: ServerAccount,
    modifier: Modifier = Modifier,
    bar: BotsInboxBar? = null,
    header: (@Composable (onOpenSearch: () -> Unit) -> Unit)? = null,
    bottomBar: (@Composable () -> Unit)? = null,
    pendingDestination: BotDestination? = null,
    onDestinationHandled: () -> Unit = {},
    bottomContentPadding: Dp = 88.dp,
) {
    val context = LocalContext.current
    val container = remember(context) { (context.applicationContext as HermexApplication).container }
    val model: BotsInboxHost = viewModel(key = "bots-inbox:${account.id}", factory = viewModelFactory {
        initializer { BotsInboxHost(container, account) }
    })
    val state by model.inbox.state.collectAsStateWithLifecycle()
    val surface by model.surface.collectAsStateWithLifecycle()
    val settings by container.settings.settings.collectAsStateWithLifecycle(initialValue = io.github.jcartu.hermex.core.prefs.SettingsRepository.Settings())
    val lifecycleOwner = LocalLifecycleOwner.current
    val panes = LocalPaneCount.current
    val displaysList = surface.editor == null && surface.creator == null && surface.roomCreator == null && !surface.connectionForm &&
        (panes > 1 || state.selection.isEmpty)

    DisposableEffect(model, lifecycleOwner, displaysList) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START && displaysList) model.inbox.openAsync()
            if (event == Lifecycle.Event.ON_STOP) model.inbox.close()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (displaysList && lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) model.inbox.openAsync()
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            model.inbox.close()
        }
    }
    LaunchedEffect(state.connection?.id) { model.connectionChanged(state.connection) }
    LaunchedEffect(surface.notice) {
        if (surface.notice != null) { kotlinx.coroutines.delay(5_000); model.clearNotice() }
    }
    LaunchedEffect(pendingDestination, state.link, state.connection, state.hasSettled) {
        val destination = pendingDestination ?: return@LaunchedEffect
        if (destination.server != account.id) return@LaunchedEffect
        if (!state.selection.isEmpty || surface.hasOverlay) model.dismissAll()
        if (BotDeepLinkRouter.inboxCanAnswer(state.link, state.connection != null, state.hasSettled)) {
            model.inbox.open(destination)
            model.inbox.current.selection.profile?.let(model.inbox::markSeen)
            onDestinationHandled()
        }
    }

    when {
        surface.connectionForm -> {
            BackHandler { model.dismissOverlay() }
            HermesConnectionForm(container, account, focusesPassword = surface.focusPassword,
                onSaved = { model.inbox.signInSaved(); model.dismissOverlay() }, onDone = model::dismissOverlay, modifier = modifier)
        }
        surface.editor != null -> {
            BackHandler { model.dismissOverlay() }
            BotProfileEditorScreen(requireNotNull(surface.editor), model::dismissOverlay, modifier)
        }
        surface.creator != null -> {
            BackHandler { model.dismissOverlay() }
            BotCreateScreen(requireNotNull(surface.creator), model::dismissOverlay, modifier)
        }
        surface.roomCreator != null -> {
            BackHandler { model.dismissOverlay() }
            BotRoomCreateScreen(requireNotNull(surface.roomCreator), state.avatars,
                onDone = { model.roomCreated(it) }, modifier = modifier, onCancel = model::dismissOverlay)
        }
        else -> {
            val list: @Composable (Modifier) -> Unit = { paneModifier ->
                Column(paneModifier.fillMaxSize()) {
                    surface.notice?.let { Text(it, Modifier.fillMaxWidth().padding(16.dp)) }
                BotsInboxScreen(
                    inbox = model.inbox, state = state,
                    onOpenBot = model::openBot, onOpenRoom = model::openRoom,
                    onEdit = model::edit, onCreate = model::create, onCreateRoom = model::createRoom,
                    onConnection = { model.openConnection(state.needsSignIn) },
                    header = header, bottomBar = bottomBar, bar = bar, bottomContentPadding = bottomContentPadding,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
                }
            }
            val detail: @Composable (Modifier) -> Unit = { paneModifier ->
                val profile = state.selection.profile
                val connection = state.connection
                when {
                    profile != null && connection != null -> key(state.selection.pick) {
                        BotCanonicalChat(account, connection, profile, state.profiles, state.selection.conversation, container,
                            onBack = model::backToInbox, onEdit = { model.edit(it) }, onReplaced = model::chatReplaced,
                            onUpdateSignIn = { model.openConnection(true) }, modifier = paneModifier)
                    }
                    surface.roomReader != null && state.selection.room != null -> {
                        CompositionLocalProvider(LocalPaneCount provides if (panes >= 2) panes - 1 else 1) {
                            BotRoomScreen(requireNotNull(surface.roomReader), state.profiles, state.avatars,
                                onBack = model::backToInbox, onUpdateSignIn = { model.openConnection(true) }, modifier = paneModifier,
                                onOpenBot = model::edit, showsMessageTimestamps = settings.showsAssistantTurnTimestamps,
                                wrapsCodeBlockLines = settings.wrapsCodeBlockLines)
                        }
                    }
                    else -> EmptyState(title = "Select a Bot", message = "Choose a bot or a group chat.", modifier = paneModifier)
                }
            }
            if (panes <= 1) {
                if (state.selection.isEmpty) list(modifier.fillMaxSize())
                else { BackHandler { model.backToInbox() }; detail(modifier.fillMaxSize()) }
            } else {
                FirstPaneSplitRow(modifier = modifier,
                    firstPane = { _, paneModifier -> list(paneModifier) }, remaining = { detail(it) })
            }
        }
    }
}

/** Phone-side ownership for the inbox's destinations, corresponding to `BotsInboxView` state. */
private class BotsInboxHost(private val container: AppContainer, val account: ServerAccount) : ViewModel() {
    data class Surface(
        val editor: BotProfileEditor? = null,
        val creator: BotCreator? = null,
        val roomCreator: BotRoomCreator? = null,
        val roomReader: BotRoomReader? = null,
        val connectionForm: Boolean = false,
        val focusPassword: Boolean = false,
        val notice: String? = null,
    ) {
        val hasOverlay: Boolean get() = editor != null || creator != null || roomCreator != null || connectionForm
    }
    private val _surface = MutableStateFlow(Surface())
    val surface = _surface.asStateFlow()
    private var connectionID: String? = container.botConnectionStore.load(account.id)?.id
    private val records = object : BotInbox.ConnectionRecords {
        override fun load(server: String) = container.botConnectionStore.load(server)
        override fun save(connection: BotConnection, server: String) = container.botConnectionStore.save(connection, server)
    }
    private val makeWire: (BotConnection) -> BotTransport = { saved ->
        BotClientTransport(BotClient(container.hermesConnections.connection(saved, account.id)))
    }
    val inbox = BotInbox(account.id, viewModelScope, records, container.botUnreadStore, container.botRoomOrganizeStore,
        container.botSectionOrderStore, container.botAvatars, makeWire,
        purgeLocalState = { connection, profile -> container.discardBotProfileData(account.id, connection, profile) },
        roomHistory = container.botHistoryCache)

    fun connectionChanged(connection: BotConnection?) {
        if (connectionID == connection?.id) return
        connectionID = connection?.id
        dismissAll()
    }

    fun openBot(profile: BotProfile) {
        inbox.selectionReturn()
        closeRoom()
        inbox.markSeen(profile)
        inbox.select(profile)
    }

    fun openRoom(room: BotGroupRoom, sequence: Int?) {
        val connection = inbox.current.connection ?: return
        inbox.selectionReturn()
        closeRoom()
        val key = requireNotNull(inbox.current.roomKey(account.id, room))
        val reader = BotRoomReader(key, connection, room, viewModelScope, container.botHistoryCache, sequence, makeWire,
            onExpired = { inbox.expireRoom(key); backToInbox() },
            onChanged = { inbox.updateRoom(it, connection.id) }, onDisbanded = { inbox.removeRoom(key); backToInbox() })
        _surface.update { it.copy(roomReader = reader) }
        inbox.selectRoom(key)
    }

    fun edit(profile: BotProfile) {
        val connection = inbox.current.connection ?: return
        val fresh = inbox.current.profiles.firstOrNull { it.id == profile.id } ?: profile
        _surface.value.editor?.close()
        val editor = BotProfileEditor(account.id, connection, fresh, container.botAvatars.image(connection.id, fresh.id),
            records, container.botAvatars, makeWire, viewModelScope, onSaved = {})
        _surface.update { it.copy(editor = editor) }
    }

    fun create(source: BotProfile?) {
        val connection = inbox.current.connection ?: return
        val creator = BotCreator(account.id, connection, inbox.current.profiles, records, makeWire, viewModelScope,
            source = source, onCreated = { inbox.openAsync() })
        _surface.update { it.copy(creator = creator) }
    }

    fun createRoom() {
        val connection = inbox.current.connection ?: return
        val creator = BotRoomCreator(account.id, connection, inbox.current.profiles, viewModelScope, records, makeWire,
            onReconciled = { inbox.reconcileRooms(it, connection.id) })
        _surface.update { it.copy(roomCreator = creator) }
    }

    fun roomCreated(room: BotGroupRoom) {
        val connection = inbox.current.connection ?: return
        dismissOverlay()
        inbox.updateRoom(room, connection.id)
        openRoom(room, null)
    }

    fun openConnection(rejected: Boolean) {
        if (rejected) inbox.current.connection?.let(inbox::noteRejectedSignIn)
        dismissAll()
        if (rejected && account.kind == ServerKind.HERMES) {
            viewModelScope.launch { container.authManager.hermesSignInRejected(account.id) }
        } else _surface.update { it.copy(connectionForm = true, focusPassword = rejected) }
    }

    fun dismissOverlay() {
        _surface.value.editor?.close()
        _surface.value.creator?.close()
        _surface.value.roomCreator?.cancel()
        _surface.update { it.copy(editor = null, creator = null, roomCreator = null, connectionForm = false, focusPassword = false) }
    }

    fun dismissAll() {
        dismissOverlay()
        backToInbox()
    }

    fun backToInbox() {
        inbox.selectionReturn()
        closeRoom()
        inbox.clearSelection()
    }

    fun chatReplaced() {
        backToInbox()
        _surface.update { it.copy(notice = "That conversation is no longer available.") }
    }

    fun clearNotice() = _surface.update { it.copy(notice = null) }

    private fun BotInbox.selectionReturn() { current.selection.profile?.let(::noteReturn) }

    private fun closeRoom() {
        val reader = _surface.value.roomReader ?: return
        _surface.update { it.copy(roomReader = null) }
        viewModelScope.launch(NonCancellable) { reader.close() }
    }

    override fun onCleared() {
        inbox.close()
        dismissOverlay()
        closeRoom()
    }
}

/**
 * Port of `BotsInboxView.chat`: canonical bot target, ordinary Hermes transcript/composer,
 * title/mentions projected from the chat's own fresh roster. No message is sent by navigation.
 */
@Composable
private fun BotCanonicalChat(
    account: ServerAccount,
    connection: BotConnection,
    initialProfile: BotProfile,
    initialRoster: List<BotProfile>,
    linkedRoot: String?,
    container: AppContainer,
    onBack: () -> Unit,
    onEdit: (BotProfile) -> Unit,
    onReplaced: () -> Unit,
    onUpdateSignIn: () -> Unit,
    modifier: Modifier,
) {
    var roster by remember(connection.id, initialProfile.id) { mutableStateOf(initialRoster) }
    var showsRules by remember { mutableStateOf(false) }
    val avatarRevision by container.botAvatars.version.collectAsStateWithLifecycle()
    val dark = androidx.compose.material3.MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val looks = roster.map { it.id to it.look }
    val avatars = remember(connection.id, avatarRevision, looks, dark) {
        val cached = container.botAvatars.images(connection.id)
        roster.associate { profile -> profile.id to (cached[profile.id] ?: renderBotAvatar(profile, dark = dark)) }
    }
    val mentions = remember(roster, initialProfile.id) { BotMentions(roster, initialProfile.id) }
    val references = remember(mentions, avatars) { mentions.chipReferences(avatars) }
    LaunchedEffect(roster, connection.id) {
        val wire = BotClientTransport(BotClient(container.hermesConnections.connection(connection, account.id)))
        try {
            wire.connect()
            container.botAvatars.refresh(roster, connection.id, wire, onUpdate = {})
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // An avatar read cannot make an otherwise readable chat unavailable.
        } finally { withContext(NonCancellable) { wire.close() } }
    }
    Column(modifier) {
        Row(Modifier.fillMaxWidth()) {
            TextButton(onClick = onBack) { Text("Back to Bots") }
            TextButton(onClick = { showsRules = true }) { Text("Chat rules") }
        }
        HermesChatPane(
            account = account, sessionKey = null, newChat = false, botId = initialProfile.id,
            linkedRoot = linkedRoot, onChatReplaced = onReplaced, onUpdateSignIn = onUpdateSignIn,
            onOpenBotEditor = { id -> onEdit(roster.firstOrNull { it.id == id } ?: initialProfile) },
            titleSlot = { rows: List<BotJSON>, face: HermesTitleFace, isConnected: Boolean ->
                LaunchedEffect(rows) {
                    if (rows.isNotEmpty()) roster = rows.mapNotNull(BotProfile::fromJsonOrNull)
                }
                val profile = roster.firstOrNull { it.id == initialProfile.id } ?: initialProfile
                val titleFace = when (face) {
                    HermesTitleFace.RESTING -> BotTitleFace.RESTING
                    HermesTitleFace.WORKING -> BotTitleFace.WORKING
                    HermesTitleFace.WAITING -> BotTitleFace.WAITING
                    HermesTitleFace.FAILED -> BotTitleFace.FAILED
                }
                val turn = if (!isConnected) BotTurnState.UNKNOWN else when (face) {
                    HermesTitleFace.WORKING -> BotTurnState.RUNNING
                    HermesTitleFace.WAITING -> BotTurnState.NEEDS_ATTENTION
                    HermesTitleFace.RESTING, HermesTitleFace.FAILED -> BotTurnState.IDLE
                }
                BotChatTitlePill(
                    profile = profile,
                    onOpen = { onEdit(profile) },
                    avatar = container.botAvatars.image(connection.id, profile.id),
                    face = titleFace,
                    turn = turn,
                )
            },
            mentionAnnotation = mentions::annotation,
            loadMentionSuggestions = { query -> mentions.suggestions(query, avatars) },
            mentionReferences = references,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )
    }
    if (showsRules) BotChatRulesSheet { showsRules = false }
}
