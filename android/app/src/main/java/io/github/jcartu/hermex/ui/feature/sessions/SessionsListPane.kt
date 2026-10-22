package io.github.jcartu.hermex.ui.feature.sessions

import android.content.Intent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.jcartu.hermex.HermexApplication
import io.github.jcartu.hermex.core.prefs.ServerConfig
import io.github.jcartu.hermex.core.prefs.SettingsRepository
import io.github.jcartu.hermex.model.ProfileSummary
import io.github.jcartu.hermex.model.ProjectSummary
import io.github.jcartu.hermex.model.SessionExportFormat
import io.github.jcartu.hermex.model.SessionSummary
import io.github.jcartu.hermex.ui.adaptive.LocalPaneCount
import io.github.jcartu.hermex.ui.components.EmptyState
import io.github.jcartu.hermex.ui.components.ErrorState
import io.github.jcartu.hermex.ui.components.LoadingState
import io.github.jcartu.hermex.ui.feature.movetohermes.MoveToHermesBannerSlot
import io.github.jcartu.hermex.ui.feature.settings.AddServerDialog
import io.github.jcartu.hermex.ui.feature.settings.HeaderLogoColor
import io.github.jcartu.hermex.ui.feature.settings.ServerSwitcherMenu
import io.github.jcartu.hermex.ui.theme.parseHexColor
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Internal sub-screens of the sessions pane. */
private enum class SessionsScreen { LIST, ARCHIVED, SCHEDULED }

/**
 * The sessions feature route. Same surface as [SessionsListPane]; exists so the
 * shell can mount the feature by route name.
 */
@Composable
fun SessionsRoute(
    onSessionSelected: (SessionSummary) -> Unit,
    onNewChat: (PendingNewChatRequest) -> Unit,
    modifier: Modifier = Modifier,
    onOpenSettings: () -> Unit = {},
) {
    SessionsListPane(
        onSessionSelected = onSessionSelected,
        onNewChat = onNewChat,
        onOpenSettings = onOpenSettings,
        modifier = modifier,
    )
}

/**
 * Port of `Features/SessionList/SessionListView.swift` (list surface only —
 * chat detail is the chat feature's pane).
 *
 * Adaptive contract: at [LocalPaneCount] 1 this is the full-screen list and a
 * tap notifies the shell via [onSessionSelected]; at 2+ this composable is the
 * list side of a two/three-pane layout — rows show a selected highlight and
 * selection updates the parent in place. Selection, search, and scroll survive
 * pane-count changes (rememberSaveable + ViewModel).
 *
 * @param onNewChat carries the [PendingNewChatRequest] route snapshot: the
 * project filter at tap time so the new chat joins the project the user sees
 * (#875), and the list's selected profile (1.8.0).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionsListPane(
    onSessionSelected: (SessionSummary) -> Unit,
    onNewChat: (PendingNewChatRequest) -> Unit,
    modifier: Modifier = Modifier,
    onOpenSettings: () -> Unit = {},
) {
    val context = LocalContext.current
    val container = remember(context) {
        (context.applicationContext as HermexApplication).container
    }

    val viewModel: SessionListViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                SessionListViewModel(
                    api = container.api,
                    cacheStore = container.cacheStore,
                    serverUrlProvider = { container.serverConfig.current().baseUrl },
                    unreadStore = SessionUnreadStore(sessionListSettingsDataStore(context)),
                )
            }
        },
    )
    val archivedViewModel: ArchivedSessionsViewModel = viewModel(
        factory = viewModelFactory {
            initializer { ArchivedSessionsViewModel(container.api) }
        },
    )

    val serverConfig by container.serverConfig.config
        .collectAsStateWithLifecycle(initialValue = null as ServerConfig?)
    val serverUrl = serverConfig?.baseUrl.orEmpty()

    val settingsRepo = remember(context) {
        SessionListSettingsRepository(sessionListSettingsDataStore(context))
    }
    val settings by remember(serverUrl) { settingsRepo.settings(serverUrl) }
        .collectAsStateWithLifecycle(initialValue = SessionListSettings())

    val appSettings by container.settings.settings
        .collectAsStateWithLifecycle(initialValue = SettingsRepository.Settings())

    var screen by rememberSaveable { mutableStateOf(SessionsScreen.LIST) }

    when (screen) {
        SessionsScreen.LIST -> SessionListScreen(
            viewModel = viewModel,
            settings = settings,
            settingsRepo = settingsRepo,
            appSettings = appSettings,
            serverUrl = serverUrl,
            onSessionSelected = onSessionSelected,
            onNewChat = onNewChat,
            onOpenSettings = onOpenSettings,
            openArchived = { screen = SessionsScreen.ARCHIVED },
            openScheduled = { screen = SessionsScreen.SCHEDULED },
            modifier = modifier,
        )

        SessionsScreen.ARCHIVED -> ArchivedSessionsScreen(
            viewModel = archivedViewModel,
            settings = settings,
            onBack = {
                screen = SessionsScreen.LIST
                viewModel.refreshAsync()
            },
            onSessionSelected = onSessionSelected,
            modifier = modifier,
        )

        SessionsScreen.SCHEDULED -> ScheduledSessionsScreen(
            viewModel = viewModel,
            settings = settings,
            onBack = { screen = SessionsScreen.LIST },
            onSessionSelected = onSessionSelected,
            modifier = modifier,
        )
    }
}

// MARK: - Main list screen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SessionListScreen(
    viewModel: SessionListViewModel,
    settings: SessionListSettings,
    settingsRepo: SessionListSettingsRepository,
    appSettings: SettingsRepository.Settings,
    serverUrl: String,
    onSessionSelected: (SessionSummary) -> Unit,
    onNewChat: (PendingNewChatRequest) -> Unit,
    onOpenSettings: () -> Unit,
    openArchived: () -> Unit,
    openScheduled: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val paneCount = LocalPaneCount.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current

    var searchText by rememberSaveable { mutableStateOf("") }
    var searchIsExpanded by rememberSaveable { mutableStateOf(false) }
    var selectedProjectId by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedSessionId by rememberSaveable { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()

    // Dialog / sheet state (transient — intentionally not saveable).
    var sessionPendingRename by remember { mutableStateOf<SessionSummary?>(null) }
    var sessionPendingDeletion by remember { mutableStateOf<SessionSummary?>(null) }
    var sessionPendingBranch by remember { mutableStateOf<SessionSummary?>(null) }
    var sessionPendingMove by remember { mutableStateOf<SessionSummary?>(null) }
    var sessionPendingProjectCreation by remember { mutableStateOf<SessionSummary?>(null) }
    var isPresentingProjectCreation by remember { mutableStateOf(false) }
    var projectPendingRename by remember { mutableStateOf<ProjectSummary?>(null) }
    var projectPendingDeletion by remember { mutableStateOf<ProjectSummary?>(null) }
    var serverSwitcherExpanded by remember { mutableStateOf(false) }
    var isPresentingAddServer by remember { mutableStateOf(false) }

    val snackbarHostState = remember { SnackbarHostState() }
    var archiveSnackbarJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    var didCompleteInitialLoad by remember { mutableStateOf(false) }
    val foregroundRefresh = remember { SessionListForegroundRefresh() }
    var wasBackgrounded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.refresh()
        didCompleteInitialLoad = true
    }

    // "Session times update while the list is idle" (1.8.0): one minute-aligned
    // tick, running only while resumed (repeatOnLifecycle cancels on pause), so
    // an idle list stays current without re-rendering whole rows.
    var nowEpochSeconds by remember { mutableStateOf(System.currentTimeMillis() / 1000) }
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.RESUMED) {
            SessionListIdleTicker.run(
                nowEpochSeconds = { System.currentTimeMillis() / 1000 },
                emit = { nowEpochSeconds = it },
            )
        }
    }

    // "Sessions refresh on foregrounding" (1.8.0): a background→active return
    // refreshes the rows; a return that lands while a load is running, or before
    // the initial load has finished, is remembered and refreshed once idle
    // (SessionListForegroundRefresh port).
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_PAUSE -> wasBackgrounded = true
                androidx.lifecycle.Lifecycle.Event.ON_RESUME -> {
                    if (wasBackgrounded) {
                        wasBackgrounded = false
                        if (foregroundRefresh.appReturned(
                                didCompleteInitialLoad = didCompleteInitialLoad,
                                isLoading = viewModel.uiState.value.isLoading,
                            )
                        ) {
                            viewModel.refreshAsync()
                        }
                    }
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(state.isLoading, didCompleteInitialLoad) {
        if (foregroundRefresh.consumeIfReady(didCompleteInitialLoad, state.isLoading)) {
            viewModel.refreshAsync()
        }
    }

    val normalizedQuery = searchText.trim().lowercase()
    val isSearchActive = normalizedQuery.isNotEmpty()
    // Mirrors the iOS `.task(id: remoteSearchTaskID)`: re-runs (cancelling the
    // debounce) whenever the query or the offline flag changes.
    LaunchedEffect(normalizedQuery, state.isViewingCachedData) {
        viewModel.searchSessions(searchText)
    }

    // Hiding the Projects section mid-filter would strand the list on one
    // project with no way back (#189), so the filter clears with the section.
    LaunchedEffect(appSettings.showsProjectsEntry) {
        if (!appSettings.showsProjectsEntry) selectedProjectId = null
    }

    val visibility = settings.automatedVisibility
    val groups = state.scheduledSessionGroups(searchText, selectedProjectId, visibility)
    val sections = sessionListSections(
        sessions = groups.ordinary,
        zoneId = java.time.ZoneId.systemDefault(),
        nowEpochSeconds = nowEpochSeconds,
    )

    // The active-row monitor (SessionListView.swift's `.task(id: activeSessionMonitorTaskID)`):
    // one poll every 3 s while visible streaming rows exist; Compose restarts
    // the effect when the id changes and cancels it when the list leaves
    // composition (a compact-width chat covers the list), matching upstream's
    // poll gating.
    val activeSessions = state.visibleActiveSessions(searchText, selectedProjectId, visibility)
    val monitorId = ActiveSessionMonitorTaskId(
        streamIds = SessionListUiState.activeStreamIds(activeSessions),
        hasActiveRows = activeSessions.isNotEmpty(),
        isViewingCachedData = state.isViewingCachedData,
        showsRowStates = appSettings.showsSessionRowStates,
    )
    LaunchedEffect(monitorId) {
        if (!monitorId.shouldPoll) return@LaunchedEffect
        while (true) {
            delay(ActiveSessionMonitorTaskId.POLL_INTERVAL_MILLIS)
            viewModel.refreshActiveSessionStatesIfNeeded(monitorId.streamIds)
        }
    }

    val openSession: (SessionSummary) -> Unit = { session ->
        val previousId = selectedSessionId
        if (previousId != null && previousId != session.sessionId) {
            state.sessions.firstOrNull { it.sessionId == previousId }
                ?.let { viewModel.noteReturn(it) }
        }
        selectedSessionId = session.sessionId
        scope.launch { viewModel.beginViewing(session) }
        onSessionSelected(session)
    }

    // In-app New Chat (Chat button, FAB, Ctrl+N): the route snapshots the
    // project filter at tap time so the new chat joins the project the user
    // sees (#875) and uses the list's selected profile (1.8.0).
    val openNewChatRequest: () -> Unit = {
        onNewChat(
            PendingNewChatRequest(
                projectId = selectedProjectId,
                profileName = state.activeProfileName,
            ),
        )
    }

    val archiveWithUndo: (SessionSummary) -> Unit = { session ->
        scope.launch {
            if (!viewModel.archive(session)) return@launch
            // removeSessionFromNavigation: an archived open chat closes.
            if (selectedSessionId == session.sessionId) selectedSessionId = null
            // "Archived · Undo" after the server confirms (#865). A newer
            // archive replaces the toast; the first session stays in Archived.
            archiveSnackbarJob?.cancel()
            archiveSnackbarJob = scope.launch {
                val result = snackbarHostState.showSnackbar(
                    message = "Archived",
                    actionLabel = "Undo",
                    duration = SnackbarDuration.Short,
                )
                if (result == SnackbarResult.ActionPerformed) {
                    viewModel.unarchive(session)
                }
            }
        }
    }

    val exportSession: (SessionSummary, SessionExportFormat) -> Unit = { session, format ->
        scope.launch {
            val file = viewModel.export(session, format)
            if (file != null) {
                val sendIntent = Intent(Intent.ACTION_SEND).apply {
                    type = if (format == SessionExportFormat.html) "text/html" else "application/json"
                    putExtra(Intent.EXTRA_SUBJECT, file.filename)
                    putExtra(Intent.EXTRA_TEXT, String(file.data, Charsets.UTF_8))
                }
                context.startActivity(Intent.createChooser(sendIntent, file.filename))
            }
        }
    }

    // Hardware-keyboard chat shortcuts (HermexCommands port): the shell's key
    // handler reads this CompositionLocal, the Compose analogue of
    // `focusedSceneValue(\.hermexSceneActions)`.
    val shortcutChats = groups.ordinary
    val currentSelectedSessionId = selectedSessionId
    val shortcutActions = SessionListShortcutActions(
        canCreateNewChat = !state.isViewingCachedData,
        createNewChat = openNewChatRequest,
        searchSessions = { searchIsExpanded = true },
        openChatAtPosition = { position ->
            ChatShortcutNavigation.chatAtPosition(position, shortcutChats)?.let(openSession)
        },
        openAdjacentChat = { offset ->
            ChatShortcutNavigation.adjacentChat(offset, currentSelectedSessionId, shortcutChats)
                ?.let(openSession)
        },
    )

    CompositionLocalProvider(LocalSessionListShortcutActions provides shortcutActions) {
    Box(modifier = modifier.fillMaxSize()) {
        PullToRefreshBox(
            isRefreshing = state.isLoading,
            onRefresh = viewModel::refreshAsync,
            modifier = Modifier.fillMaxSize(),
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .widthIn(max = 560.dp)
                    .fillMaxSize()
                    .align(Alignment.TopCenter),
            ) {
                item(key = "header") {
                    val headerAccent = parseHexColor(appSettings.headerLogoColorHex)
                        ?: MaterialTheme.colorScheme.primary
                    SessionsHeader(
                        logoColor = headerAccent,
                        avatarInitials = sessionAvatarInitials(
                            displayName = appSettings.identityDisplayName,
                            storedInitials = appSettings.identityInitials,
                            fallbackName = serverUrl
                                .substringAfter("://")
                                .substringBefore("/")
                                .substringBefore(":"),
                        ),
                        avatarColor = headerAccent,
                        avatarForeground = if (
                            HeaderLogoColor.prefersDarkForeground(appSettings.headerLogoColorHex)
                        ) {
                            androidx.compose.ui.graphics.Color.Black
                        } else {
                            androidx.compose.ui.graphics.Color.White
                        },
                        isSearchExpanded = searchIsExpanded,
                        searchText = searchText,
                        onSearchTextChange = { searchText = it },
                        onOpenSearch = { searchIsExpanded = true },
                        onCloseSearch = {
                            searchText = ""
                            viewModel.clearSearchResults()
                            searchIsExpanded = false
                        },
                        onOpenSettings = onOpenSettings,
                        onSwitchServer = { serverSwitcherExpanded = true },
                        avatarMenu = {
                            ServerSwitcherMenu(
                                expanded = serverSwitcherExpanded,
                                onDismiss = { serverSwitcherExpanded = false },
                                onAddServer = {
                                    serverSwitcherExpanded = false
                                    isPresentingAddServer = true
                                },
                            )
                        },
                    )
                }

                // Section chrome: "Sessions" + the fork's display-options menu,
                // Archived shortcut and header Chat button (medium/expanded).
                item(key = "sessions-chrome") {
                    SessionsChromeRow(
                        paneCount = paneCount,
                        archivedCount = state.archivedCount,
                        isViewingCachedData = state.isViewingCachedData,
                        isSearching = state.isSearchingRemoteSessions,
                        settings = settings,
                        onToggleSetting = { toggle ->
                            scope.launch {
                                when (toggle) {
                                    SessionListSettingToggle.MESSAGE_COUNT ->
                                        settingsRepo.setShowsMessageCount(!settings.showsMessageCount)
                                    SessionListSettingToggle.WORKSPACE ->
                                        settingsRepo.setShowsWorkspace(!settings.showsWorkspace)
                                    SessionListSettingToggle.CRON ->
                                        settingsRepo.setShowsCronSessions(!settings.showsCronSessions)
                                    SessionListSettingToggle.CLI ->
                                        settingsRepo.setShowsCliSessions(serverUrl, !settings.showsCliSessions)
                                    SessionListSettingToggle.CLAUDE_CODE ->
                                        settingsRepo.setShowsClaudeCodeSessions(
                                            serverUrl,
                                            !settings.showsClaudeCodeSessions,
                                        )
                                    SessionListSettingToggle.SUBAGENTS ->
                                        settingsRepo.setShowsSubagentSessions(!settings.showsSubagentSessions)
                                }
                            }
                        },
                        openArchived = openArchived,
                        onNewChat = openNewChatRequest,
                    )
                }

                item(key = "move-to-hermes-banner") {
                    MoveToHermesBannerSlot(serverId = serverUrl)
                }

                if (state.isViewingCachedData) {
                    item(key = "offline-banner") { OfflineCacheBanner() }
                }

                state.searchErrorMessage?.let { message ->
                    item(key = "search-error") {
                        Text(
                            text = message,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
                        )
                    }
                }

                // Active Profile disclosure (SessionSidebarUtilityRows port, #24:
                // hidden in single-profile mode; #189: hidden by the Settings
                // section-visibility toggle).
                if (!isSearchActive && appSettings.showsActiveProfileEntry && !state.isSingleProfileMode) {
                    item(key = "profiles-header") {
                        ProfilesSectionHeader(
                            isExpanded = settings.profilesAreExpanded,
                            isLoading = state.isLoadingActiveProfile,
                            hasError = state.activeProfileErrorMessage != null,
                            onToggle = {
                                scope.launch {
                                    settingsRepo.setProfilesAreExpanded(!settings.profilesAreExpanded)
                                }
                            },
                        )
                    }

                    if (settings.profilesAreExpanded) {
                        items(
                            count = state.profileOptions.size,
                            key = { "profile-" + state.profileOptions[it].id },
                        ) { index ->
                            val profile = state.profileOptions[index]
                            ActiveProfilePickerRow(
                                profile = profile,
                                isSelected = profile.normalizedName != null &&
                                    profile.normalizedName == state.activeProfileName,
                                isSwitching = state.isSwitchingActiveProfile &&
                                    state.switchingActiveProfileName == profile.normalizedName,
                                onClick = { scope.launch { viewModel.switchActiveProfile(profile) } },
                            )
                        }
                    }
                }

                // Projects disclosure (SessionSidebarUtilityRows / ProjectFilterRow port).
                if (!isSearchActive && appSettings.showsProjectsEntry) {
                    item(key = "projects-header") {
                        ProjectsSectionHeader(
                            isExpanded = settings.projectsAreExpanded,
                            projectCount = state.projects.size,
                            onToggle = {
                                scope.launch {
                                    settingsRepo.setProjectsAreExpanded(!settings.projectsAreExpanded)
                                }
                            },
                            onAddProject = { isPresentingProjectCreation = true },
                        )
                    }

                    if (settings.projectsAreExpanded) {
                        item(key = "projects-all") {
                            ProjectFilterRow(
                                name = "All",
                                colorHex = null,
                                count = state.sessions.count { visibility.shows(it) },
                                isSelected = selectedProjectId == null,
                                onClick = { selectedProjectId = null },
                                onRename = null,
                                onDelete = null,
                            )
                        }
                        items(
                            count = state.projects.size,
                            key = { "project-" + (state.projects[it].projectId ?: it.toString()) },
                        ) { index ->
                            val project = state.projects[index]
                            ProjectFilterRow(
                                name = project.name?.takeIf { it.isNotEmpty() } ?: "Untitled Project",
                                colorHex = project.color,
                                count = state.sessions.count {
                                    visibility.shows(it) && it.projectId == project.projectId
                                },
                                isSelected = selectedProjectId != null &&
                                    selectedProjectId == project.projectId,
                                onClick = { selectedProjectId = project.projectId },
                                onRename = { projectPendingRename = project },
                                onDelete = { projectPendingDeletion = project },
                            )
                        }
                    }
                }

                // Scheduled (cron) sessions disclosure.
                if (groups.showsDisclosure(isSearchActive)) {
                    item(key = "scheduled-header") {
                        ScheduledSectionHeader(
                            totalCount = groups.totalScheduledCount,
                            isExpanded = isSearchActive || settings.scheduledSessionsAreExpanded,
                            isSearchActive = isSearchActive,
                            onToggle = {
                                scope.launch {
                                    settingsRepo.setScheduledSessionsAreExpanded(
                                        !settings.scheduledSessionsAreExpanded,
                                    )
                                }
                            },
                        )
                    }

                    if (isSearchActive || settings.scheduledSessionsAreExpanded) {
                        val displayed = if (isSearchActive) groups.scheduled else groups.scheduledPreview
                        items(
                            count = displayed.size,
                            key = { "scheduled-" + displayed[it].id },
                        ) { index ->
                            val session = displayed[index]
                            SessionInteractiveRow(
                                session = session,
                                state = state,
                                settings = settings,
                                showsRowStates = appSettings.showsSessionRowStates,
                                nowEpochSeconds = nowEpochSeconds,
                                searchText = searchText,
                                isSelected = paneCount >= 2 && session.sessionId == selectedSessionId,
                                isCompact = paneCount == 1,
                                onOpen = { openSession(session) },
                                onOpenForkParent = ForkOrigin.resolveIn(session, state.sessions)?.parent
                                    ?.let { parent -> { openSession(parent) } },
                                onToggleUnread = { scope.launch { viewModel.toggleUnread(session) } },
                                onTogglePin = {
                                    scope.launch { viewModel.setPinned(session.pinned != true, session) }
                                },
                                onRename = { sessionPendingRename = session },
                                onBranch = { sessionPendingBranch = session },
                                onMove = {
                                    sessionPendingMove = session
                                    scope.launch { viewModel.loadProjects() }
                                },
                                onExport = { format -> exportSession(session, format) },
                                onArchive = { archiveWithUndo(session) },
                                onDelete = { sessionPendingDeletion = session },
                            )
                        }

                        if (!isSearchActive && groups.hasAdditionalScheduledSessions) {
                            item(key = "scheduled-view-all") {
                                ViewAllScheduledRow(onClick = openScheduled)
                            }
                        }
                    }
                }

                // Ordinary sessions grouped Pinned / Today / Yesterday / Earlier.
                if (state.isLoading && state.sessions.isEmpty()) {
                    item(key = "loading") { LoadingState(label = "Loading sessions") }
                } else if (state.errorMessage != null && state.sessions.isEmpty()) {
                    item(key = "load-error") {
                        val message = if (state.loadFailedOffline) {
                            "Cannot reach server. Check that your Mac is awake and cloudflared is running."
                        } else {
                            state.errorMessage.orEmpty()
                        }
                        ErrorState(
                            message = message,
                            modifier = Modifier.heightIn(max = 260.dp),
                            onRetry = viewModel::refreshAsync,
                        )
                    }
                } else if (sections.isEmpty() && groups.scheduled.isEmpty()) {
                    item(key = "empty") {
                        val hasFilter = selectedProjectId != null || isSearchActive
                        EmptyState(
                            title = if (hasFilter) "No matching sessions" else "No sessions yet",
                            message = if (hasFilter) {
                                "Try another search or project filter."
                            } else {
                                "Tap Chat to start."
                            },
                            modifier = Modifier.heightIn(max = 260.dp),
                        )
                    }
                } else {
                    sections.forEach { section ->
                        item(key = "section-" + section.id) {
                            SessionSectionHeader(
                                title = section.title,
                                count = section.sessions.size,
                            )
                        }
                        items(
                            count = section.sessions.size,
                            key = { "session-" + section.id + "-" + section.sessions[it].id },
                        ) { index ->
                            val session = section.sessions[index]
                            SessionInteractiveRow(
                                session = session,
                                state = state,
                                settings = settings,
                                showsRowStates = appSettings.showsSessionRowStates,
                                nowEpochSeconds = nowEpochSeconds,
                                searchText = searchText,
                                isSelected = paneCount >= 2 && session.sessionId == selectedSessionId,
                                isCompact = paneCount == 1,
                                onOpen = { openSession(session) },
                                onOpenForkParent = ForkOrigin.resolveIn(session, state.sessions)?.parent
                                    ?.let { parent -> { openSession(parent) } },
                                onToggleUnread = { scope.launch { viewModel.toggleUnread(session) } },
                                onTogglePin = {
                                    scope.launch { viewModel.setPinned(session.pinned != true, session) }
                                },
                                onRename = { sessionPendingRename = session },
                                onBranch = { sessionPendingBranch = session },
                                onMove = {
                                    sessionPendingMove = session
                                    scope.launch { viewModel.loadProjects() }
                                },
                                onExport = { format -> exportSession(session, format) },
                                onArchive = { archiveWithUndo(session) },
                                onDelete = { sessionPendingDeletion = session },
                            )
                        }
                    }
                }

                // Bottom Archived entry (issue #17): hidden while searching, offline,
                // and when the server reports zero archived sessions or omits the count.
                val archivedCount = state.archivedCount ?: 0
                if (!isSearchActive && !state.isViewingCachedData && archivedCount > 0) {
                    item(key = "archived-entry") {
                        ArchivedEntryRow(count = archivedCount, onClick = openArchived)
                    }
                }

                item(key = "bottom-spacer") { Spacer(Modifier.heightIn(min = 104.dp)) }
            }
        }

        // Compact: floating "Chat" button, thumb-reachable (iOS FAB port).
        if (paneCount == 1) {
            // Disabled while offline, mirroring the iOS FAB's cached-data gate.
            val enabled = !state.isViewingCachedData
            ExtendedFloatingActionButton(
                onClick = { if (enabled) openNewChatRequest() },
                icon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                text = { Text("Chat") },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 24.dp, bottom = 22.dp)
                    .alpha(if (enabled) 1f else 0.45f),
            )
        }

        // The archive Undo toast (#865): sits above the Chat button, as wide as
        // the column.
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = 24.dp)
                .padding(bottom = if (paneCount == 1) 88.dp else 22.dp),
        )
    }
    }

    if (isPresentingAddServer) {
        AddServerDialog(
            onDismiss = { isPresentingAddServer = false },
            onAdded = { isPresentingAddServer = false },
        )
    }

    state.activeProfileErrorMessage?.let { message ->
        ActionErrorDialog(message = message, onDismiss = viewModel::clearActiveProfileError)
    }

    // MARK: dialogs

    sessionPendingRename?.let { session ->
        RenameSessionDialog(
            initialTitle = sessionDisplayTitle(session),
            isSaving = state.isRenamingSession,
            onCancel = { sessionPendingRename = null },
            onSave = { title ->
                scope.launch {
                    if (viewModel.rename(session, title)) sessionPendingRename = null
                }
            },
        )
    }

    sessionPendingBranch?.let { session ->
        BranchSessionDialog(
            initialTitle = sessionDisplayTitle(session) + " (copy)",
            isBusy = state.isMutating(session),
            onCancel = { sessionPendingBranch = null },
            onBranch = { title ->
                scope.launch {
                    val duplicated = viewModel.duplicate(session, title)
                    if (duplicated != null) {
                        sessionPendingBranch = null
                        selectedSessionId = duplicated.sessionId
                        onSessionSelected(duplicated)
                    }
                }
            },
        )
    }

    sessionPendingDeletion?.let { session ->
        ConfirmDeleteSessionDialog(
            onCancel = { sessionPendingDeletion = null },
            onDelete = {
                sessionPendingDeletion = null
                scope.launch {
                    if (viewModel.delete(session) && selectedSessionId == session.sessionId) {
                        selectedSessionId = null
                    }
                }
            },
        )
    }

    sessionPendingMove?.let { session ->
        MoveToProjectSheet(
            session = session,
            projects = state.projects,
            isMovingSession = state.isMovingSession,
            isCreatingProject = state.isCreatingProject,
            isLoadingProjects = state.isLoadingProjects,
            onMove = { projectId ->
                sessionPendingMove = null
                scope.launch { viewModel.move(session, projectId) }
            },
            onCreateProject = {
                sessionPendingMove = null
                sessionPendingProjectCreation = session
            },
            onRefreshProjects = { scope.launch { viewModel.loadProjects() } },
            onDismiss = { sessionPendingMove = null },
        )
    }

    sessionPendingProjectCreation?.let { session ->
        ProjectFormDialog(
            title = "New Project",
            initialName = "",
            initialColorHex = ProjectCreationPalette.defaultColor(state.projects.size).hex,
            isSaving = state.isCreatingProject || state.isMovingSession,
            saveLabel = "Create",
            onCancel = { sessionPendingProjectCreation = null },
            onSave = { name, color ->
                scope.launch {
                    if (viewModel.createProject(name, color, session)) {
                        sessionPendingProjectCreation = null
                    }
                }
            },
        )
    }

    if (isPresentingProjectCreation) {
        ProjectFormDialog(
            title = "New Project",
            initialName = "",
            initialColorHex = ProjectCreationPalette.defaultColor(state.projects.size).hex,
            isSaving = state.isCreatingProject,
            saveLabel = "Create",
            onCancel = { isPresentingProjectCreation = false },
            onSave = { name, color ->
                scope.launch {
                    if (viewModel.createEmptyProject(name, color)) {
                        isPresentingProjectCreation = false
                    }
                }
            },
        )
    }

    projectPendingRename?.let { project ->
        ProjectFormDialog(
            title = "Rename Project",
            initialName = project.name.orEmpty(),
            initialColorHex = project.color ?: ProjectCreationPalette.approvedColors.first().hex,
            isSaving = state.isRenamingProject,
            saveLabel = "Save",
            onCancel = { projectPendingRename = null },
            onSave = { name, color ->
                scope.launch {
                    if (viewModel.renameProject(project, name, color)) projectPendingRename = null
                }
            },
        )
    }

    projectPendingDeletion?.let { project ->
        ConfirmDeleteProjectDialog(
            onCancel = { projectPendingDeletion = null },
            onDelete = {
                projectPendingDeletion = null
                scope.launch {
                    if (viewModel.deleteProject(project) && selectedProjectId == project.projectId) {
                        selectedProjectId = null
                    }
                }
            },
        )
    }

    state.actionErrorMessage?.let { message ->
        ActionErrorDialog(message = message, onDismiss = viewModel::clearActionError)
    }
}

// MARK: - Interactive row (tap, long-press menu, compact swipe)

private enum class SessionListSettingToggle {
    MESSAGE_COUNT, WORKSPACE, CRON, CLI, CLAUDE_CODE, SUBAGENTS
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun SessionInteractiveRow(
    session: SessionSummary,
    state: SessionListUiState,
    settings: SessionListSettings,
    showsRowStates: Boolean,
    nowEpochSeconds: Long,
    searchText: String,
    isSelected: Boolean,
    isCompact: Boolean,
    onOpen: () -> Unit,
    onOpenForkParent: (() -> Unit)?,
    onToggleUnread: () -> Unit,
    onTogglePin: () -> Unit,
    onRename: () -> Unit,
    onBranch: () -> Unit,
    onMove: () -> Unit,
    onExport: (SessionExportFormat) -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current

    val hasServerSessionId = !session.sessionId.isNullOrEmpty()
    // Port of SessionRowActionPolicy.
    val offersMutationActions = !session.isSessionReadOnly
    val canMutate = offersMutationActions && !state.isViewingCachedData && hasServerSessionId
    val canExport = !state.isViewingCachedData && hasServerSessionId
    val isMutating = state.isMutating(session)
    val isUnread = state.isUnread(session)
    // The fork's row-states toggle gates the polled label; the unread dot's
    // `attentionState == null` check follows the same gate (a hidden Working
    // label must not also hide the dot).
    val attentionState = if (showsRowStates) {
        SessionRowAttentionState.effectiveAttentionState(
            session = session,
            attentionState = state.attentionStateFor(session),
            isViewingCachedData = state.isViewingCachedData,
        )
    } else {
        null
    }

    val rowContent: @Composable () -> Unit = {
        Box {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .background(
                        color = if (isSelected) {
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                        } else {
                            MaterialTheme.colorScheme.surface
                        },
                        shape = RoundedCornerShape(12.dp),
                    )
                    .combinedClickable(
                        onClick = onOpen,
                        onLongClick = { menuExpanded = true },
                    ),
            ) {
                SessionRow(
                    session = session,
                    showsMessageCount = settings.showsMessageCount,
                    showsWorkspace = settings.showsWorkspace,
                    isViewingCachedData = state.isViewingCachedData,
                    isUnread = isUnread,
                    attentionState = attentionState,
                    searchExcerpt = state.searchExcerptFor(session, searchText),
                    forkOrigin = ForkOrigin.resolveIn(session, state.sessions),
                    onOpenForkParent = onOpenForkParent,
                    nowEpochSeconds = nowEpochSeconds,
                )
            }

            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                val fullTitle = sessionDisplayTitle(session)
                DropdownMenuItem(
                    text = { Text("Copy Full Title") },
                    onClick = {
                        clipboard.setText(AnnotatedString(fullTitle))
                        menuExpanded = false
                    },
                )
                // 1.7.0 unread replies: the row's own read mark.
                DropdownMenuItem(
                    text = { Text(if (isUnread) "Mark as Read" else "Mark as Unread") },
                    enabled = state.canToggleUnread(session),
                    onClick = {
                        menuExpanded = false
                        onToggleUnread()
                    },
                )
                if (offersMutationActions) {
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text(if (session.pinned == true) "Unpin" else "Pin") },
                        enabled = canMutate && !isMutating,
                        onClick = {
                            menuExpanded = false
                            onTogglePin()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Rename") },
                        enabled = !state.isViewingCachedData && !state.isRenamingSession &&
                            hasServerSessionId,
                        onClick = {
                            menuExpanded = false
                            onRename()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Duplicate") },
                        enabled = !state.isViewingCachedData && hasServerSessionId && !isMutating,
                        onClick = {
                            menuExpanded = false
                            onBranch()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Move to Project") },
                        enabled = !state.isViewingCachedData && hasServerSessionId && !isMutating,
                        onClick = {
                            menuExpanded = false
                            onMove()
                        },
                    )
                }
                // Export works for any session the server can see, including
                // read-only and foreign/CLI rows; it only needs a live server
                // session ID.
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text("Export as HTML") },
                    enabled = canExport && !isMutating,
                    onClick = {
                        menuExpanded = false
                        onExport(SessionExportFormat.html)
                    },
                )
                DropdownMenuItem(
                    text = { Text("Export as JSON") },
                    enabled = canExport && !isMutating,
                    onClick = {
                        menuExpanded = false
                        onExport(SessionExportFormat.json)
                    },
                )
                if (offersMutationActions) {
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text("Archive") },
                        enabled = canMutate && !isMutating,
                        onClick = {
                            menuExpanded = false
                            onArchive()
                        },
                    )
                    DropdownMenuItem(
                        text = {
                            Text("Delete", color = MaterialTheme.colorScheme.error)
                        },
                        enabled = canMutate && !isMutating,
                        onClick = {
                            menuExpanded = false
                            onDelete()
                        },
                    )
                }
            }
        }
    }

    if (isCompact && canMutate && !isMutating) {
        val currentTogglePin by rememberUpdatedState(onTogglePin)
        val currentArchive by rememberUpdatedState(onArchive)
        val dismissState = rememberSwipeToDismissBoxState(
            confirmValueChange = { value ->
                when (value) {
                    SwipeToDismissBoxValue.StartToEnd -> currentTogglePin()
                    SwipeToDismissBoxValue.EndToStart -> currentArchive()
                    SwipeToDismissBoxValue.Settled -> Unit
                }
                // Never actually dismiss; the reload animates the change instead.
                false
            },
        )
        SwipeToDismissBox(
            state = dismissState,
            backgroundContent = {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 28.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = if (session.pinned == true) "Unpin" else "Pin",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Icon(
                        imageVector = Icons.Filled.Archive,
                        contentDescription = "Archive",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
        ) {
            rowContent()
        }
    } else {
        rowContent()
    }
}

// MARK: - Header, search, and section chrome

/**
 * The "Sessions" label row under [SessionsHeader]: remote-search spinner (port
 * of `SessionListRowsSection.sessionsHeaderRow`), the fork's display-options
 * menu, the Archived shortcut, and the medium/expanded header Chat button.
 */
@Composable
private fun SessionsChromeRow(
    paneCount: Int,
    archivedCount: Int?,
    isViewingCachedData: Boolean,
    isSearching: Boolean,
    settings: SessionListSettings,
    onToggleSetting: (SessionListSettingToggle) -> Unit,
    openArchived: () -> Unit,
    onNewChat: () -> Unit,
) {
    var filterMenuExpanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = 12.dp, top = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Sessions",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )

        if (isSearching) {
            Spacer(Modifier.width(10.dp))
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
        }

        Spacer(Modifier.weight(1f))

        if ((archivedCount ?: 0) > 0) {
            IconButton(onClick = openArchived) {
                Icon(
                    imageVector = Icons.Filled.Archive,
                    contentDescription = "Archived Sessions",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Box {
            IconButton(onClick = { filterMenuExpanded = true }) {
                Icon(
                    imageVector = Icons.Filled.Tune,
                    contentDescription = "Session display options",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            DropdownMenu(
                expanded = filterMenuExpanded,
                onDismissRequest = { filterMenuExpanded = false },
            ) {
                SettingToggleItem("Message count", settings.showsMessageCount) {
                    onToggleSetting(SessionListSettingToggle.MESSAGE_COUNT)
                }
                SettingToggleItem("Workspace", settings.showsWorkspace) {
                    onToggleSetting(SessionListSettingToggle.WORKSPACE)
                }
                HorizontalDivider()
                SettingToggleItem("Scheduled (cron) sessions", settings.showsCronSessions) {
                    onToggleSetting(SessionListSettingToggle.CRON)
                }
                SettingToggleItem("CLI sessions", settings.showsCliSessions) {
                    onToggleSetting(SessionListSettingToggle.CLI)
                }
                SettingToggleItem("Claude Code sessions", settings.showsClaudeCodeSessions) {
                    onToggleSetting(SessionListSettingToggle.CLAUDE_CODE)
                }
                SettingToggleItem("Subagent sessions", settings.showsSubagentSessions) {
                    onToggleSetting(SessionListSettingToggle.SUBAGENTS)
                }
            }
        }

        // Medium and expanded widths: header New Chat button (the FAB covers compact).
        if (paneCount >= 2) {
            Spacer(Modifier.width(4.dp))
            FilledTonalButton(onClick = onNewChat, enabled = !isViewingCachedData) {
                Icon(Icons.Filled.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Chat")
            }
        }
    }
}

@Composable
private fun SettingToggleItem(label: String, isChecked: Boolean, onToggle: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label) },
        trailingIcon = {
            if (isChecked) Icon(Icons.Filled.Check, contentDescription = "Enabled")
        },
        onClick = onToggle,
    )
}

@Composable
private fun SessionSearchField(
    searchText: String,
    onSearchTextChange: (String) -> Unit,
    isSearching: Boolean,
    onClear: () -> Unit,
) {
    OutlinedTextField(
        value = searchText,
        onValueChange = onSearchTextChange,
        placeholder = { Text("Search sessions") },
        singleLine = true,
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        trailingIcon = {
            when {
                isSearching -> CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                )
                searchText.isNotEmpty() -> IconButton(onClick = onClear) {
                    Icon(Icons.Filled.Clear, contentDescription = "Clear search")
                }
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
internal fun OfflineCacheBanner() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .background(
                MaterialTheme.colorScheme.surfaceVariant,
                RoundedCornerShape(12.dp),
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(
                text = "Offline",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "Showing cached sessions. Actions are disabled until the server is reachable.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SessionSectionHeader(title: String, count: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .padding(top = 20.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.width(8.dp))
        CountBadge(count)
    }
}

@Composable
private fun CountBadge(count: Int) {
    Text(
        text = count.toString(),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

@Composable
private fun ProfilesSectionHeader(
    isExpanded: Boolean,
    isLoading: Boolean,
    hasError: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .clickable(onClick = onToggle)
            .padding(start = 24.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (isExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
            contentDescription = if (isExpanded) {
                "Collapse active profile picker"
            } else {
                "Expand active profile picker"
            },
            // The header tints orange when the last profile action failed.
            tint = if (hasError) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        Spacer(Modifier.width(8.dp))
        Icon(
            imageVector = Icons.Filled.Person,
            contentDescription = null,
            tint = if (hasError) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = "Active Profile",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f),
        )
        if (isLoading) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
        }
    }
}

/**
 * Port of `ActiveProfilePickerRow` (`SessionListComponents.swift`): one profile
 * option — display name, its default model caption, a switching spinner or the
 * selected check.
 */
@Composable
private fun ActiveProfilePickerRow(
    profile: ProfileSummary,
    isSelected: Boolean,
    isSwitching: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .padding(horizontal = 12.dp)
            .background(
                color = if (isSelected) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                } else {
                    MaterialTheme.colorScheme.surface
                },
                shape = RoundedCornerShape(12.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.Person,
            contentDescription = null,
            tint = if (isSelected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = profile.displayName,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = profile.model?.trim()?.takeIf { it.isNotEmpty() }
                    ?: "Default model unavailable",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (isSwitching) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
        } else if (isSelected) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = "Active profile",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

@Composable
private fun ProjectsSectionHeader(
    isExpanded: Boolean,
    projectCount: Int,
    onToggle: () -> Unit,
    onAddProject: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .clickable(onClick = onToggle)
            .padding(start = 24.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (isExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
            contentDescription = if (isExpanded) "Collapse projects" else "Expand projects",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = "Projects",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f),
        )
        if (projectCount > 0) CountBadge(projectCount)
        IconButton(onClick = onAddProject) {
            Icon(
                imageVector = Icons.Filled.Add,
                contentDescription = "New project",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ProjectFilterRow(
    name: String,
    colorHex: String?,
    count: Int,
    isSelected: Boolean,
    onClick: () -> Unit,
    onRename: (() -> Unit)?,
    onDelete: (() -> Unit)?,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val hasMenu = onRename != null || onDelete != null

    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .padding(horizontal = 12.dp)
                .background(
                    color = if (isSelected) {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                    } else {
                        MaterialTheme.colorScheme.surface
                    },
                    shape = RoundedCornerShape(12.dp),
                )
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = { if (hasMenu) menuExpanded = true },
                )
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val dotColor = colorHex?.let(::parseHexColor)
            if (dotColor != null) {
                Box(Modifier.size(12.dp).background(dotColor, CircleShape))
                Spacer(Modifier.width(10.dp))
            }
            Text(
                text = name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            CountBadge(count)
            if (isSelected) {
                Spacer(Modifier.width(8.dp))
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = "Selected",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp),
                )
            }
        }

        if (hasMenu) {
            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                onRename?.let { rename ->
                    DropdownMenuItem(
                        text = { Text("Rename Project") },
                        onClick = {
                            menuExpanded = false
                            rename()
                        },
                    )
                }
                onDelete?.let { delete ->
                    DropdownMenuItem(
                        text = { Text("Delete Project", color = MaterialTheme.colorScheme.error) },
                        onClick = {
                            menuExpanded = false
                            delete()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ScheduledSectionHeader(
    totalCount: Int,
    isExpanded: Boolean,
    isSearchActive: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .clickable(enabled = !isSearchActive, onClick = onToggle)
            .padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (isExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
            contentDescription = if (isExpanded) {
                "Collapse scheduled sessions"
            } else {
                "Expand scheduled sessions"
            },
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = "Scheduled sessions",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f),
        )
        CountBadge(totalCount)
    }
}

@Composable
private fun ViewAllScheduledRow(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.Search,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = "View all",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = Icons.Filled.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ArchivedEntryRow(count: Int, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.Archive,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = "Archived Sessions",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        CountBadge(count)
        Spacer(Modifier.width(8.dp))
        Icon(
            imageVector = Icons.Filled.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// MARK: - Archived screen (toolbar-toggled sub-screen)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ArchivedSessionsScreen(
    viewModel: ArchivedSessionsViewModel,
    settings: SessionListSettings,
    onBack: () -> Unit,
    onSessionSelected: (SessionSummary) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) { viewModel.load() }

    Column(modifier = modifier.fillMaxSize()) {
        SubScreenTopBar(title = "Archived Sessions", onBack = onBack)

        PullToRefreshBox(
            isRefreshing = state.isLoading,
            onRefresh = { scope.launch { viewModel.load() } },
            modifier = Modifier.fillMaxSize(),
        ) {
            when {
                state.isLoading && state.sessions.isEmpty() ->
                    LoadingState(label = "Loading archived sessions")

                state.errorMessage != null && state.sessions.isEmpty() ->
                    ErrorState(
                        message = state.errorMessage.orEmpty(),
                        onRetry = { scope.launch { viewModel.load() } },
                    )

                state.sessions.isEmpty() ->
                    EmptyState(
                        title = "No archived sessions",
                        message = "Sessions you archive appear here.",
                    )

                else -> LazyColumn(
                    modifier = Modifier
                        .widthIn(max = 560.dp)
                        .fillMaxSize()
                        .align(Alignment.TopCenter),
                ) {
                    items(
                        count = state.sessions.size,
                        key = { "archived-" + state.sessions[it].id },
                    ) { index ->
                        val session = state.sessions[index]
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp)
                                .clickable { onSessionSelected(session) },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            SessionRow(
                                session = session,
                                showsMessageCount = settings.showsMessageCount,
                                showsWorkspace = settings.showsWorkspace,
                                isViewingCachedData = false,
                                modifier = Modifier.weight(1f),
                            )
                            if (state.isUnarchiving(session)) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp).padding(end = 2.dp),
                                    strokeWidth = 2.dp,
                                )
                                Spacer(Modifier.width(12.dp))
                            } else {
                                TextButton(
                                    onClick = { scope.launch { viewModel.unarchive(session) } },
                                    enabled = !session.isSessionReadOnly,
                                ) {
                                    Text("Restore")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    state.actionErrorMessage?.let { message ->
        ActionErrorDialog(message = message, onDismiss = viewModel::clearActionError)
    }
}

// MARK: - Scheduled screen (port of ScheduledSessionsView)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScheduledSessionsScreen(
    viewModel: SessionListViewModel,
    settings: SessionListSettings,
    onBack: () -> Unit,
    onSessionSelected: (SessionSummary) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var searchText by rememberSaveable { mutableStateOf("") }

    val sessions = if (settings.showsCronSessions) {
        state.visibleSessions(searchText, selectedProjectId = null)
            .filter { it.isCronSession && it.archived != true }
    } else {
        emptyList()
    }

    Column(modifier = modifier.fillMaxSize()) {
        SubScreenTopBar(title = "Scheduled sessions", onBack = onBack)

        SessionSearchField(
            searchText = searchText,
            onSearchTextChange = { searchText = it },
            isSearching = false,
            onClear = { searchText = "" },
        )

        if (sessions.isEmpty()) {
            EmptyState(
                title = if (searchText.isBlank()) "No sessions yet" else "No matching sessions",
                message = if (searchText.isBlank()) null else "Try another search or project filter.",
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .widthIn(max = 560.dp)
                    .fillMaxSize()
                    .align(Alignment.CenterHorizontally),
            ) {
                items(count = sessions.size, key = { "all-scheduled-" + sessions[it].id }) { index ->
                    val session = sessions[index]
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp)
                            .clickable { onSessionSelected(session) },
                    ) {
                        SessionRow(
                            session = session,
                            showsMessageCount = settings.showsMessageCount,
                            showsWorkspace = settings.showsWorkspace,
                            isViewingCachedData = state.isViewingCachedData,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SubScreenTopBar(title: String, onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
