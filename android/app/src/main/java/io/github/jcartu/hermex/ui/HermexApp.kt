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
import io.github.jcartu.hermex.core.prefs.ServerConfig
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
        is AuthState.LoggedOut -> OnboardingRoute(container, savedServer = state.saved)
        is AuthState.LoggedIn -> MainShell(container)
    }
}

@Composable
private fun MainShell(container: AppContainer) {
    val fold: State<FoldState> = rememberFoldState()
    val destinationStateHolder = rememberSaveableStateHolder()
    val windowWidthDp: Int = rememberWindowWidthDp()
    val paneCount: Int = fold.value.trifoldPaneCount(windowWidthDp)
    val navigation: AppNavigationViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val selected = navigation.selectedDestination
    val sharedImport by container.sharedImports.pending.collectAsStateWithLifecycle()
    val pendingDeepLink by container.deepLinks.pending.collectAsStateWithLifecycle()
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
    CompositionLocalProvider(
        LocalFoldState provides fold.value,
        LocalPaneCount provides paneCount,
    ) {
        val destinationContent: @Composable (HermexDestination) -> Unit = { destination ->
            when (destination) {
                HermexDestination.CHATS -> ChatsArea(navigation)
                HermexDestination.KANBAN -> KanbanRoute(
                    initialBoardSlug = navigation.kanbanBoardSlug,
                    initialCardId = navigation.kanbanCardId,
                    onDeepLinkConsumed = navigation::consumeKanbanTarget,
                )
                HermexDestination.TASKS -> TasksRoute()
                HermexDestination.FILES -> WorkspaceRoute()
                HermexDestination.MORE -> MoreRoute(navigation)
            }
        }

        if (paneCount == 1) {
            Scaffold(
                bottomBar = {
                    NavigationBar {
                        HermexDestination.entries.forEach { destination ->
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
private fun ChatsArea(navigation: AppNavigationViewModel) {
    val paneCount = LocalPaneCount.current
    val selectedSessionId = navigation.selectedSessionId
    val newChat = navigation.isNewChat

    // Hardware-keyboard chat shortcuts (HermexCommands port): bubbles key
    // events from any focused child in the chats area; the sessions list
    // provides the action set via LocalSessionListShortcutActions.
    val shortcutActions = LocalSessionListShortcutActions.current

    Box(Modifier.fillMaxSize().onKeyEvent { handleSessionListShortcutKey(it, shortcutActions) }) {
    if (paneCount == 1) {
        if (selectedSessionId == null && !newChat) {
            SessionsListPane(
                onSessionSelected = { summary -> navigation.selectSession(summary.id) },
                onNewChat = { request ->
                    navigation.startNewChat(request.projectId, request.profileName)
                },
                onOpenSettings = navigation::openSettings,
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
private fun MoreRoute(navigation: AppNavigationViewModel) {
    val paneCount = LocalPaneCount.current
    var entry by rememberSaveable { mutableStateOf<MoreEntry?>(null) }
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
    val content: @Composable (MoreEntry?) -> Unit = { current ->
        when (current) {
            MoreEntry.SKILLS -> SkillsRoute()
            MoreEntry.GIT -> GitWorkspaceRoute(
                initialSessionId = navigation.gitSessionId,
                onDeepLinkConsumed = navigation::consumeGitTarget,
            )
            MoreEntry.MEMORY -> MemoryRoute()
            MoreEntry.INSIGHTS -> InsightsRoute()
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
        MoreMenu(onSelect = { entry = it })
    } else {
        Row(Modifier.fillMaxSize()) {
            MoreMenu(onSelect = { entry = it }, modifier = Modifier.fillMaxWidth(0.28f), highlight = entry)
            Box(Modifier.fillMaxSize()) { content(entry) }
        }
    }
}

@Composable
private fun MoreMenu(
    onSelect: (MoreEntry) -> Unit,
    modifier: Modifier = Modifier,
    highlight: MoreEntry? = null,
) {
    Column(modifier = modifier.padding(vertical = 8.dp)) {
        MoreEntry.entries.forEach { item ->
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
