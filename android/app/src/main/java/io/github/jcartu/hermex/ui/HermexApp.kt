package io.github.jcartu.hermex.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.ViewKanban
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Source
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jcartu.hermex.core.media.DocumentPreviewHost
import io.github.jcartu.hermex.model.ServerAccount
import io.github.jcartu.hermex.core.navigation.AppDeepLink
import io.github.jcartu.hermex.di.AppContainer
import io.github.jcartu.hermex.ui.adaptive.FirstPaneSplitRow
import io.github.jcartu.hermex.ui.adaptive.FoldState
import io.github.jcartu.hermex.ui.adaptive.LocalFoldState
import io.github.jcartu.hermex.ui.adaptive.LocalPaneCount
import io.github.jcartu.hermex.ui.adaptive.rememberFoldState
import io.github.jcartu.hermex.ui.adaptive.rememberWindowWidthDp
import io.github.jcartu.hermex.ui.adaptive.trifoldPaneCount
import io.github.jcartu.hermex.ui.components.LoadingState
import io.github.jcartu.hermex.ui.feature.chat.ChatPane
import io.github.jcartu.hermex.ui.feature.git.GitWorkspaceRoute
import io.github.jcartu.hermex.ui.feature.insights.InsightsRoute
import io.github.jcartu.hermex.ui.feature.memory.MemoryRoute
import io.github.jcartu.hermex.ui.feature.onboarding.AuthManager
import io.github.jcartu.hermex.ui.feature.onboarding.AuthState
import io.github.jcartu.hermex.ui.feature.onboarding.OnboardingRoute
import io.github.jcartu.hermex.ui.feature.kanban.KanbanRoute
import io.github.jcartu.hermex.ui.feature.sessions.LocalSessionListShortcutActions
import io.github.jcartu.hermex.ui.feature.sessions.SessionsListPane
import io.github.jcartu.hermex.ui.feature.sessions.handleSessionListShortcutKey
import io.github.jcartu.hermex.ui.feature.settings.SettingsRoute
import io.github.jcartu.hermex.ui.feature.skills.SkillsRoute
import io.github.jcartu.hermex.ui.feature.tasks.TasksRoute
import io.github.jcartu.hermex.ui.feature.workspace.WorkspaceRoute
import io.github.jcartu.hermex.ui.feature.workspace.WorkspaceFileNavigation

enum class HermexDestination(val label: String, val icon: ImageVector) {
    CHATS("Chats", Icons.AutoMirrored.Filled.Chat),
    KANBAN("Kanban", Icons.Filled.ViewKanban),
    TASKS("Tasks", Icons.Filled.Schedule),
    FILES("Files", Icons.Filled.Folder),
    MORE("More", Icons.Filled.MoreHoriz),
}

/**
 * Root of the app: the auth gate (port of ContentView's authManager.state
 * switch) over the adaptive navigation shell.
 */
@Composable
fun HermexApp(container: AppContainer) {
    val authManager = container.authManager
    val isReady by container.isReady.collectAsStateWithLifecycle()
    LaunchedEffect(authManager, isReady) {
        if (isReady) authManager.restoreSavedServer()
    }
    if (!isReady) {
        LoadingState(label = "Starting Hermex-Type-J")
        return
    }
    val authState: AuthState by authManager.state.collectAsStateWithLifecycle()
    when (val state = authState) {
        AuthState.Unconfigured -> OnboardingRoute(container, savedServer = null)
        is AuthState.LoggedOut -> OnboardingRoute(container, savedServer = state.account)
        is AuthState.LoggedIn -> ServerScopedViewModels(serverId = state.account.id) { MainShell(container) }
    }
}

@Composable
private fun MainShell(container: AppContainer) {
    val fold: State<FoldState> = rememberFoldState()
    val destinationStateHolder = rememberSaveableStateHolder()
    val windowWidthDp: Int = rememberWindowWidthDp()
    val paneCount: Int = fold.value.trifoldPaneCount(windowWidthDp)
    val navigation: AppNavigationViewModel = androidx.lifecycle.viewmodel.compose.viewModel(
        viewModelStoreOwner = LocalHostViewModelStoreOwner.current
            ?: checkNotNull(androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner.current),
    )
    val selected = navigation.selectedDestination
    val sharedImport by container.sharedImports.pending.collectAsStateWithLifecycle()
    val pendingDeepLink by container.deepLinks.pending.collectAsStateWithLifecycle()
    val workspaceFileRequest by WorkspaceFileNavigation.request.collectAsStateWithLifecycle()
    LaunchedEffect(workspaceFileRequest?.id) {
        if (workspaceFileRequest != null) {
            navigation.selectDestination(HermexDestination.FILES)
        }
    }
    val settings by container.settings.settings.collectAsStateWithLifecycle(
        initialValue = io.github.jcartu.hermex.core.prefs.SettingsRepository.Settings(),
    )
    // Section-visibility toggles (iOS `sectionVisibility.tasks|insights`): a
    // hidden destination can't stay selected, so the shell falls back to Chats.
    LaunchedEffect(settings.showsTasksEntry, selected) {
        if (!settings.showsTasksEntry && selected == HermexDestination.TASKS) {
            navigation.selectDestination(HermexDestination.CHATS)
        }
    }
    LaunchedEffect(pendingDeepLink?.first) {
        val pending = pendingDeepLink ?: return@LaunchedEffect
        when (val link = pending.second) {
            AppDeepLink.NewChat -> {
                navigation.selectDestination(HermexDestination.CHATS)
                navigation.startNewChat()
            }
            is AppDeepLink.Session -> {
                navigation.selectDestination(HermexDestination.CHATS)
                navigation.selectSession(link.sessionId)
            }
            is AppDeepLink.Kanban -> navigation.openKanban(link.boardSlug, link.cardId)
            is AppDeepLink.Git -> navigation.openGit(link.sessionId)
        }
        container.deepLinks.acknowledge(pending.first)
    }
    LaunchedEffect(sharedImport?.id) {
        if (sharedImport != null) {
            navigation.selectDestination(HermexDestination.CHATS)
            navigation.startNewChat()
        }
    }
    io.github.jcartu.hermex.ui.feature.settings.NotificationOfferHost(container)
    CompositionLocalProvider(
        LocalFoldState provides fold.value,
        LocalPaneCount provides paneCount,
    ) {
        val destinationContent: @Composable (HermexDestination) -> Unit = { destination ->
            when (destination) {
                HermexDestination.CHATS -> ChatsArea(navigation, container)
                HermexDestination.KANBAN -> KanbanRoute(
                    initialBoardSlug = navigation.kanbanBoardSlug,
                    initialCardId = navigation.kanbanCardId,
                    onDeepLinkConsumed = navigation::consumeKanbanTarget,
                )
                HermexDestination.TASKS -> TasksRoute(
                    client = io.github.jcartu.hermex.ui.feature.tasks.WebuiCronDataClient(container.api),
                )
                HermexDestination.FILES -> WorkspaceRoute(initialSessionId = navigation.selectedSessionId)
                HermexDestination.MORE -> MoreRoute(navigation, container)
            }
        }

        if (paneCount == 1) {
            Scaffold(
                bottomBar = {
                    NavigationBar {
                        HermexDestination.entries.forEach { destination ->
                            if (destination == HermexDestination.TASKS && !settings.showsTasksEntry) {
                                return@forEach
                            }
                            NavigationBarItem(
                                selected = selected == destination,
                                onClick = { navigation.selectDestination(destination) },
                                icon = { Icon(destination.icon, contentDescription = destination.label) },
                                label = { Text(destination.label) },
                            )
                        }
                    }
                },
            ) { padding ->
                Box(Modifier.fillMaxSize().padding(padding)) {
                    DestinationHost(selected, destinationStateHolder, destinationContent)
                }
            }
        } else {
            Row(Modifier.fillMaxSize()) {
                NavigationRail {
                    HermexDestination.entries.forEach { destination ->
                        if (destination == HermexDestination.TASKS && !settings.showsTasksEntry) {
                            return@forEach
                        }
                        NavigationRailItem(
                            selected = selected == destination,
                            onClick = { navigation.selectDestination(destination) },
                            icon = { Icon(destination.icon, contentDescription = destination.label) },
                            label = { Text(destination.label) },
                        )
                    }
                }
                Box(Modifier.fillMaxSize().systemBarsPadding()) {
                    DestinationHost(selected, destinationStateHolder, destinationContent)
                }
            }
        }
        DocumentPreviewHost()
    }
}

/** Keeps each destination's Compose state alive across pane-count changes. */
@Composable
private fun DestinationHost(
    selected: HermexDestination,
    stateHolder: androidx.compose.runtime.saveable.SaveableStateHolder,
    content: @Composable (HermexDestination) -> Unit,
) {
    stateHolder.SaveableStateProvider(selected.name) {
        content(selected)
    }
}

/**
 * Chats: sessions list + chat. One pane: list ↔ chat with back. Two panes:
 * list | chat. Three panes (trifold): list takes the first hinge-aligned
 * region; ChatPane fills the rest and splits transcript | composer at the
 * second hinge itself.
 */
@Composable
private fun ChatsArea(navigation: AppNavigationViewModel, container: AppContainer) {
    val paneCount = LocalPaneCount.current
    val selectedSessionId = navigation.selectedSessionId
    val newChat = navigation.isNewChat

    // Hardware-keyboard chat shortcuts (HermexCommands port): bubbles key
    // events from any focused child in the chats area; the sessions list
    // provides the action set via LocalSessionListShortcutActions.
    val shortcutActions = LocalSessionListShortcutActions.current

    io.github.jcartu.hermex.ui.feature.bots.WebuiBotsHost(container, navigation) { botsEntry ->
    Box(Modifier.fillMaxSize().onKeyEvent { handleSessionListShortcutKey(it, shortcutActions) }) {
    if (paneCount == 1) {
        if (selectedSessionId == null && !newChat) {
            SessionsListPane(
                onSessionSelected = { summary -> navigation.selectSession(summary.id) },
                onNewChat = { request ->
                    navigation.startNewChat(request.projectId, request.profileName)
                },
                onOpenSettings = navigation::openSettings,
                botsEntry = botsEntry,
            )
        } else {
            BackHandler { navigation.showSessionList() }
            ChatPane(
                initialSessionId = selectedSessionId,
                initialNewChat = newChat,
                newChatProjectId = navigation.newChatProjectId,
                newChatProfileName = navigation.newChatProfileName,
            )
        }
    } else {
        FirstPaneSplitRow(
            firstPane = { _, modifier ->
                SessionsListPane(
                    onSessionSelected = { summary -> navigation.selectSession(summary.id) },
                    onNewChat = { request ->
                        navigation.startNewChat(request.projectId, request.profileName)
                    },
                    onOpenSettings = navigation::openSettings,
                    modifier = modifier,
                    botsEntry = botsEntry,
                )
            },
            remaining = { modifier ->
                ChatPane(
                    initialSessionId = selectedSessionId,
                    initialNewChat = newChat,
                    newChatProjectId = navigation.newChatProjectId,
                    newChatProfileName = navigation.newChatProfileName,
                    modifier = modifier,
                )
            },
        )
    }
    }
    }
}

private enum class MoreEntry(val label: String, val icon: ImageVector) {
    SKILLS("Skills", Icons.Filled.Bolt),
    GIT("Git", Icons.Filled.Source),
    MEMORY("Memory", Icons.Filled.Psychology),
    INSIGHTS("Insights", Icons.Filled.Insights),
    SETTINGS("Settings", Icons.Filled.Settings),
}

/**
 * More: Memory, Insights, Settings. Single pane: menu → sub-screen with back.
 * Two+ panes: menu rail column | content side by side.
 */
@Composable
private fun MoreRoute(navigation: AppNavigationViewModel, container: AppContainer) {
    val paneCount = LocalPaneCount.current
    var entry by rememberSaveable { mutableStateOf<MoreEntry?>(null) }
    val settings by container.settings.settings.collectAsStateWithLifecycle(
        initialValue = io.github.jcartu.hermex.core.prefs.SettingsRepository.Settings(),
    )
    LaunchedEffect(navigation.pendingMoreRoute) {
        if (navigation.pendingMoreRoute == "git") {
            entry = MoreEntry.GIT
            if (navigation.gitSessionId == null) navigation.consumeGitTarget()
        }
        // The session-list header avatar routes here (More → Settings, #283).
        if (navigation.pendingMoreRoute == "settings") {
            entry = MoreEntry.SETTINGS
            navigation.consumeMoreRoute()
        }
    }
    // A hidden Usage entry can't stay selected: fall back to the menu.
    LaunchedEffect(settings.showsInsightsEntry, entry) {
        if (!settings.showsInsightsEntry && entry == MoreEntry.INSIGHTS) {
            entry = null
        }
    }
    val content: @Composable (MoreEntry?) -> Unit = { current ->
        when (current) {
            MoreEntry.SKILLS -> SkillsRoute()
            MoreEntry.GIT -> GitWorkspaceRoute(
                initialSessionId = navigation.gitSessionId,
                onDeepLinkConsumed = navigation::consumeGitTarget,
            )
            MoreEntry.MEMORY -> MemoryRoute()
            MoreEntry.INSIGHTS -> InsightsRoute(
                client = io.github.jcartu.hermex.ui.feature.insights.WebuiInsightsDataClient(container.api),
            )
            MoreEntry.SETTINGS -> SettingsRoute(
                onOpenArchived = { navigation.selectDestination(HermexDestination.CHATS) },
            )
            null -> {}
        }
    }

    if (paneCount == 1 && entry != null) {
        val current = entry
        BackHandler { entry = null }
        Column(Modifier.fillMaxSize()) {
            content(current)
        }
    } else if (paneCount == 1) {
        MoreMenu(onSelect = { entry = it }, showsInsights = settings.showsInsightsEntry)
    } else {
        Row(Modifier.fillMaxSize()) {
            MoreMenu(
                onSelect = { entry = it },
                modifier = Modifier.fillMaxWidth(0.28f),
                highlight = entry,
                showsInsights = settings.showsInsightsEntry,
            )
            Box(Modifier.fillMaxSize()) { content(entry) }
        }
    }
}

@Composable
private fun MoreMenu(
    onSelect: (MoreEntry) -> Unit,
    modifier: Modifier = Modifier,
    highlight: MoreEntry? = null,
    showsInsights: Boolean = true,
) {
    Column(modifier = modifier.padding(vertical = 8.dp)) {
        MoreEntry.entries.forEach { item ->
            if (item == MoreEntry.INSIGHTS && !showsInsights) {
                return@forEach
            }
            val selected = highlight == item
            androidx.compose.material3.ListItem(
                headlineContent = { Text(item.label) },
                leadingContent = { Icon(item.icon, contentDescription = item.label) },
                colors = if (selected) {
                    androidx.compose.material3.ListItemDefaults.colors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    )
                } else {
                    androidx.compose.material3.ListItemDefaults.colors()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 2.dp)
                    .clickable { onSelect(item) },
            )
        }
    }
}

/** The Activity's ViewModel store, kept for [AppNavigationViewModel] (its SavedStateHandle) across server switches. */
private val LocalHostViewModelStoreOwner =
    androidx.compose.runtime.staticCompositionLocalOf<androidx.lifecycle.ViewModelStoreOwner?> { null }

/**
 * Port of ContentView's `.id(server)` (`docs/agents/multi-server-state-isolation.md`):
 * the signed-in subtree is rebuilt per active server, and every ViewModel
 * created under it (sessions, chat, settings, …) lives in a store that is
 * cleared when the active server changes, so no screen state carries between
 * servers. The navigation ViewModel stays with the Activity and is returned to
 * the session list on a switch, as a rebuilt iOS tree would be.
 */
@Composable
private fun ServerScopedViewModels(serverId: String, content: @Composable () -> Unit) {
    val host = checkNotNull(androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner.current)
    val owner = remember(serverId) { ServerViewModelStoreOwner(host) }
    androidx.compose.runtime.DisposableEffect(owner) {
        onDispose { owner.viewModelStore.clear() }
    }
    val navigation: AppNavigationViewModel = androidx.lifecycle.viewmodel.compose.viewModel(viewModelStoreOwner = host)
    var previousServerId by rememberSaveable { mutableStateOf(serverId) }
    LaunchedEffect(serverId) {
        if (previousServerId != serverId) {
            previousServerId = serverId
            navigation.selectDestination(HermexDestination.CHATS)
            navigation.showSessionList()
        }
    }
    androidx.compose.runtime.key(serverId) {
        CompositionLocalProvider(
            LocalHostViewModelStoreOwner provides host,
            androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner provides owner,
            content = content,
        )
    }
}

private class ServerViewModelStoreOwner(
    private val host: androidx.lifecycle.ViewModelStoreOwner,
) : androidx.lifecycle.ViewModelStoreOwner, androidx.lifecycle.HasDefaultViewModelProviderFactory {
    override val viewModelStore = androidx.lifecycle.ViewModelStore()

    override val defaultViewModelProviderFactory: androidx.lifecycle.ViewModelProvider.Factory
        get() = (host as? androidx.lifecycle.HasDefaultViewModelProviderFactory)?.defaultViewModelProviderFactory
            ?: androidx.lifecycle.ViewModelProvider.NewInstanceFactory.instance

    override val defaultViewModelCreationExtras: androidx.lifecycle.viewmodel.CreationExtras
        get() = (host as? androidx.lifecycle.HasDefaultViewModelProviderFactory)?.defaultViewModelCreationExtras
            ?: androidx.lifecycle.viewmodel.CreationExtras.Empty
}
