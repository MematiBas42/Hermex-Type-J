package io.github.jcartu.hermex.ui.feature.hermes.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.jcartu.hermex.core.navigation.*
import io.github.jcartu.hermex.di.AppContainer
import io.github.jcartu.hermex.model.ServerAccount
import io.github.jcartu.hermex.model.hermes.BotFailure
import io.github.jcartu.hermex.net.hermes.*
import io.github.jcartu.hermex.ui.adaptive.FirstPaneSplitRow
import io.github.jcartu.hermex.ui.adaptive.LocalPaneCount
import io.github.jcartu.hermex.ui.components.EmptyState
import io.github.jcartu.hermex.ui.feature.bots.*
import io.github.jcartu.hermex.ui.feature.chat.hermes.HermesChatPane
import io.github.jcartu.hermex.ui.feature.hermes.sessions.*
import io.github.jcartu.hermex.ui.feature.insights.*
import io.github.jcartu.hermex.ui.feature.kanban.*
import io.github.jcartu.hermex.ui.feature.memory.*
import io.github.jcartu.hermex.ui.feature.sessions.SubScreenTopBar
import io.github.jcartu.hermex.ui.feature.skills.*
import io.github.jcartu.hermex.ui.feature.tasks.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** The chat's durable opening identity, not a runtime; kept through folds and the home side switch. */
private data class HermesHomeChat(
    val key: String?, val profile: String, val parent: String? = null, val root: String? = null,
    val botRoot: String? = null, val voice: Boolean = false,
    val id: String = java.util.UUID.randomUUID().toString(),
) {
    companion object {
        val saver = listSaver<HermesHomeChat?, String>(
            save = { it?.let { row -> listOf(row.key.orEmpty(), row.profile, row.parent.orEmpty(), row.root.orEmpty(), row.botRoot.orEmpty(), row.voice.toString(), row.id) } ?: emptyList() },
            restore = { if (it.isEmpty()) null else HermesHomeChat(it[0].ifEmpty { null }, it[1], it[2].ifEmpty { null }, it[3].ifEmpty { null }, it[4].ifEmpty { null }, it[5].toBoolean(), it.getOrNull(6) ?: java.util.UUID.randomUUID().toString()) },
        )
    }
}

/**
 * Port of HermesHome.swift + HermesServerHome's two retained sides (#1108/#1251). The Sessions
 * VM is server-scoped and kept above the switch; BotsInbox retains its own VM and marks. Only
 * the first hinge-aligned pane holds the floating bar; HermesChatPane owns the other two.
 */
@Composable
fun HermesHome(account: ServerAccount, container: AppContainer, onOpenSettings: () -> Unit, modifier: Modifier = Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val saved = remember(account.id) { container.botConnectionStore.load(account.id) }
    // AuthManager owns missing-record routing: a signed-out server gets its sign-in form, never an inbox.
    if (saved == null) {
        io.github.jcartu.hermex.ui.feature.onboarding.OnboardingRoute(container, savedServer = account)
        return
    }
    val connection = remember(account.id, saved) { container.hermesConnections.connection(saved, account.id) }
    val sessions: HermesSessionListViewModel = viewModel(key = "hermes-sessions-${account.id}", factory = viewModelFactory { initializer {
        HermesSessionListViewModel(account.id, saved.address, { BotClientSessionWire(BotClient(connection)) },
            SavedHermesListPreferences(context.applicationContext, account.id), container.hermesCacheStore)
    } })
    val state by sessions.uiState.collectAsStateWithLifecycle()
    var tab by rememberSaveable(account.id) { mutableStateOf(HermesHomeTab.SESSIONS) }
    var chat by rememberSaveable(account.id, stateSaver = HermesHomeChat.saver) { mutableStateOf<HermesHomeChat?>(null) }
    var detail by rememberSaveable(account.id) { mutableStateOf<String?>(null) }
    var query by rememberSaveable(account.id) { mutableStateOf("") }
    var covers by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current
    val paneCount = LocalPaneCount.current
    val sides = rememberSaveableStateHolder()
    val snackbar = remember { SnackbarHostState() }
    val pending by HermesSessionLookup.pending.collectAsStateWithLifecycle()
    val newChat by HermesSessionLookup.pendingNewChat.collectAsStateWithLifecycle()
    val pendingBot by BotLinkMailbox.pending.collectAsStateWithLifecycle()
    var botFilter by remember { mutableStateOf<(@Composable () -> Unit)>({}) }
    var botNewChat by remember { mutableStateOf<(@Composable () -> Unit)>({}) }
    val botBar = remember { object : BotsInboxBar {
        override fun setFilter(content: @Composable () -> Unit) { botFilter = content }
        override fun setNewChat(content: @Composable () -> Unit) { botNewChat = content }
    } }
    fun bringForward() { chat = null; detail = null; covers++ }
    fun openRow(row: HermesListedSession) {
        if (row.row.isBotChat) {
            tab = HermesHomeTab.BOTS
            BotLinkMailbox.post(BotDestination(account.id, saved.id, row.profile, null))
        } else {
            chat?.key?.let { old -> state.sessions.firstOrNull { it.row.id == old && it.profile == chat?.profile }?.let(sessions::noteReturn) }
            detail = null
            chat = HermesHomeChat(row.row.id, row.profile, row.row.parentSessionID, row.row.identity, row.row.botChatRoot)
            sessions.beginViewing(row)
        }
    }
    fun closeChat() {
        chat?.key?.let { old -> state.sessions.firstOrNull { it.row.id == old && it.profile == chat?.profile }?.let(sessions::noteReturn) }
        chat = null; detail = null
        sessions.refresh()
    }
    LaunchedEffect(tab) { if (tab == HermesHomeTab.SESSIONS) sessions.open() else sessions.close() }
    DisposableEffect(lifecycle, sessions) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) scope.launch { sessions.close() }
            else if (event == Lifecycle.Event.ON_START && tab == HermesHomeTab.SESSIONS && (paneCount > 1 || (chat == null && detail == null))) sessions.refresh()
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer); sessions.closeAsync() }
    }
    LaunchedEffect(pendingBot) { if (pendingBot?.serverId == account.id) { tab = HermesHomeTab.BOTS; bringForward() } }
    LaunchedEffect(pending) {
        val destination = pending?.takeIf { it.serverId == account.id } ?: return@LaunchedEffect
        tab = HermesHomeTab.SESSIONS; bringForward()
        val wire = BotClientSessionWire(BotClient(connection))
        try {
            wire.connect()
            val outcome = HermesSessionResolver.resolve(destination.key, destination.profile, wire, destination.opensParent)
            if (HermesSessionLookup.pending.value != destination) return@LaunchedEffect
            bringForward()
            HermesSessionLookup.consume(destination)
            when (outcome) {
                is HermesSessionResolver.Outcome.Found -> openRow(outcome.session)
                HermesSessionResolver.Outcome.Gone -> {
                    val fallback = MovedWebuiRoute.fallback(destination)
                    if (fallback != null) container.deepLinks.submit(HermexDeepLink.webuiPushUri(fallback.serverId, fallback.sessionId, true))
                    else snackbar.showSnackbar("That chat is no longer on ${account.displayName.ifBlank { saved.address.host }}.")
                }
                HermesSessionResolver.Outcome.Ambiguous -> snackbar.showSnackbar("That chat is in more than one Profile. Open it from Sessions.")
                HermesSessionResolver.Outcome.Room -> Unit
            }
        } catch (cancel: CancellationException) { throw cancel }
        catch (error: Throwable) {
            if (error != BotFailure.Rejected(401) && error != BotFailure.SignInExpired && error != BotFailure.SignInChanged) HermesSessionLookup.consume(destination)
        } finally { wire.close() }
    }
    LaunchedEffect(newChat?.id) {
        if (newChat?.serverId == account.id) { tab = HermesHomeTab.SESSIONS; bringForward(); sessions.refresh() }
    }
    LaunchedEffect(newChat?.id, state.isLive, state.profile) {
        val request = newChat?.takeIf { it.serverId == account.id } ?: return@LaunchedEffect
        if (!state.isLive || state.isViewingCachedData) return@LaunchedEffect
        val profile = request.profile ?: state.profile ?: return@LaunchedEffect
        bringForward()
        chat = HermesHomeChat(null, profile, voice = request.autoStartsVoiceInput)
        HermesSessionLookup.consumeNewChat(request)
    }
    val bottom: @Composable () -> Unit = {
        HermesHomeBar(tab, { tab = it }, filter = {
            if (tab == HermesHomeTab.BOTS) botFilter() else HermesProfileFilter(state,
                select = { scope.launch { sessions.selectProfile(it) } }, all = { scope.launch { sessions.showAllProfiles() } })
        }, newChat = {
            if (tab == HermesHomeTab.BOTS) botNewChat() else IconButton(
                onClick = { state.profile?.let { detail = null; chat = HermesHomeChat(null, it) } },
                enabled = state.isLive && !state.isViewingCachedData && state.profile != null,
            ) { Icon(Icons.Default.Edit, "New Session") }
        })
    }
    val list: @Composable (Modifier) -> Unit = { column ->
        HermesSessionListScreen(account, sessions, ::openRow, onArchived = { detail = "ARCHIVED"; chat = null },
            onUtility = { detail = it.name; chat = null }, modifier = column,
            selectedKey = chat?.key?.let { "${chat?.profile}|$it" }, bottomBar = bottom, initialQuery = query, coverRevision = covers)
    }
    val detailContent: @Composable (Modifier) -> Unit = { column ->
        val opened = chat
        if (opened != null) {
            key(opened.id) {
            HermesChatPane(account, opened.key, newChat = opened.key == null, modifier = column,
                profile = opened.profile, parentKey = opened.parent, lineageRoot = opened.root, botChatRoot = opened.botRoot,
                onOpenSessions = { profile, text ->
                    closeChat(); query = text
                    profile?.let { scope.launch { sessions.selectProfile(it) } }
                }, onOpenSession = { profile, key -> HermesSessionLookup.open(account.id, key, profile) },
                autoStartsVoiceInput = opened.voice,
                onSessionIdentified = { profile, key -> if (chat == opened) chat = opened.copy(key = key, profile = profile, voice = false) })
            }
        } else if (detail != null) {
            Column(column.fillMaxSize()) {
                val title = if (detail == "ARCHIVED") "Archived Sessions" else HermesHomeUtility.valueOf(detail!!).label
                if (detail != "ARCHIVED") SubScreenTopBar(title, ::closeChat)
                val profile = state.profile
                if (profile != null) when (detail) {
                    "ARCHIVED" -> {
                        val archive: HermesSessionListViewModel = viewModel(key = "hermes-archive-${account.id}-$profile", factory = viewModelFactory { initializer {
                            HermesSessionListViewModel(account.id, saved.address, { BotClientSessionWire(BotClient(connection)) },
                                SavedHermesListPreferences(context.applicationContext, account.id), initialProfile = profile, archived = true)
                        } })
                        HermesSessionListScreen(account, archive, ::openRow, {}, {}, onBack = ::closeChat)
                    }
                    "TASKS" -> TasksRoute(remember(profile) { HermesCronDataClient(connection, profile) }, newTaskProfile = profile)
                    "KANBAN" -> KanbanRoute(remember(connection) { HermesKanbanDataSource(connection) })
                    "SKILLS" -> SkillsRoute(remember(profile) { HermesSkillsDataClient(HermesSkillsClient(connection, profile)) }, profile)
                    "MEMORY" -> MemoryRoute(remember(profile) { HermesMemoryDataClient(connection, profile) }, profile)
                    "USAGE" -> InsightsRoute(remember(profile) { HermesInsightsDataClient(HermesInsightsClient(connection, profile)) }, profile = profile)
                }
            }
        } else EmptyState("Select a Chat", column, "Choose a session from the sidebar or start a new chat.")
    }
    CompositionLocalProvider(LocalHermesHomeSettings provides onOpenSettings) {
        Box(modifier.fillMaxSize().statusBarsPadding()) {
            sides.SaveableStateProvider(tab.name) {
                HermesHomeSurface(tab, chat != null || detail != null, onBack = ::closeChat,
                    bots = { BotsInbox(account, bar = botBar,
                        header = { open -> HermesHomeHeader(account, onOpenSearch = open) }, bottomBar = bottom,
                        pendingDestination = pendingBot?.takeIf { it.serverId == account.id }, onDestinationHandled = BotLinkMailbox::consume) },
                    sessions = list, detail = detailContent)
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(horizontal = 16.dp, vertical = 88.dp))
        }
    }
}

/** The real home layout, factored from its host-bound state for deterministic pane-count rendering. */
@Composable
fun HermesHomeSurface(
    tab: HermesHomeTab,
    hasDetail: Boolean,
    onBack: () -> Unit,
    bots: @Composable () -> Unit,
    sessions: @Composable (Modifier) -> Unit,
    detail: @Composable (Modifier) -> Unit,
) {
    when (tab) {
        HermesHomeTab.BOTS -> bots()
        HermesHomeTab.SESSIONS -> if (LocalPaneCount.current == 1) {
            if (!hasDetail) sessions(Modifier.fillMaxSize())
            else { BackHandler(onBack = onBack); detail(Modifier.fillMaxSize()) }
        } else FirstPaneSplitRow(firstPane = { _, column -> sessions(column) }, remaining = detail)
    }
}
