package io.github.jcartu.hermex.ui.feature.hermes.sessions

import android.content.ClipData
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import io.github.jcartu.hermex.HermexApplication
import io.github.jcartu.hermex.core.prefs.SettingsRepository
import io.github.jcartu.hermex.model.ProjectSummary
import io.github.jcartu.hermex.model.ServerAccount
import io.github.jcartu.hermex.model.SessionExportFile
import io.github.jcartu.hermex.model.hermes.HermesProject
import io.github.jcartu.hermex.net.hermes.HermesFolderCompletion
import io.github.jcartu.hermex.ui.adaptive.LocalPaneCount
import io.github.jcartu.hermex.ui.components.*
import io.github.jcartu.hermex.ui.feature.hermes.home.HermesHomeHeader
import io.github.jcartu.hermex.ui.feature.sessions.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.ZoneId
import java.util.UUID

/** Home utility rows from HermesSessionListView.swift; each retains the selected Profile. */
enum class HermesHomeUtility(val label: String) { TASKS("Tasks"), KANBAN("Kanban"), SKILLS("Skills"), MEMORY("Memory"), USAGE("Usage") }

/** Port of HermesProjectMove, including the explicit no-files-moved warning (#1052). */
data class HermesProjectMove(val row: HermesListedSession, val name: String, val folder: String, val busy: Boolean) {
    val title: String get() = "Move to $name?"
    val message: String get() = if (busy) "Hermes will work in $folder from now on, including the reply that's running now. Files aren't moved."
        else "Hermes will work in $folder from now on. Files aren't moved."
}

/** Port of HermesSessionListView.swift; reuses SessionsHeader, SessionRow, list groups and the existing dialogs/pickers. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HermesSessionListScreen(
    account: ServerAccount,
    viewModel: HermesSessionListViewModel,
    onOpen: (HermesListedSession) -> Unit,
    onArchived: () -> Unit,
    onUtility: (HermesHomeUtility) -> Unit,
    modifier: Modifier = Modifier,
    selectedKey: String? = null,
    bottomBar: @Composable () -> Unit = {},
    onBack: (() -> Unit)? = null,
    initialQuery: String = "",
    coverRevision: Int = 0,
) {
    val context = LocalContext.current
    val container = remember(context) { (context.applicationContext as HermexApplication).container }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val settingsRepository = remember(context) { SessionListSettingsRepository(sessionListSettingsDataStore(context)) }
    val settings by remember(account.id) { settingsRepository.settings(account.id) }.collectAsStateWithLifecycle(initialValue = SessionListSettings())
    val appSettings by container.settings.settings.collectAsStateWithLifecycle(initialValue = SettingsRepository.Settings())
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current
    val scroll = rememberLazyListState()
    val paneCount = LocalPaneCount.current
    var searchText by rememberSaveable { mutableStateOf(initialQuery) }
    var searchExpanded by rememberSaveable { mutableStateOf(initialQuery.isNotEmpty()) }
    var lane by rememberSaveable { mutableStateOf<String?>(null) }
    var showAllScheduled by rememberSaveable { mutableStateOf(false) }
    var rename by remember { mutableStateOf<HermesListedSession?>(null) }
    var delete by remember { mutableStateOf<HermesListedSession?>(null) }
    var moving by remember { mutableStateOf<HermesListedSession?>(null) }
    var move by remember { mutableStateOf<HermesProjectMove?>(null) }
    var creating by remember { mutableStateOf(false) }
    var creatingFor by remember { mutableStateOf<HermesListedSession?>(null) }
    var renameProject by remember { mutableStateOf<HermesProject?>(null) }
    var deleteProject by remember { mutableStateOf<HermesProject?>(null) }
    val snackbars = remember { SnackbarHostState() }
    var toastJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis() / 1000) }

    LaunchedEffect(initialQuery) { searchText = initialQuery; if (initialQuery.isNotEmpty()) searchExpanded = true }
    LaunchedEffect(coverRevision) {
        rename = null; delete = null; moving = null; move = null; creating = false; creatingFor = null; renameProject = null; deleteProject = null
    }
    LaunchedEffect(viewModel, lifecycle) {
        lifecycle.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.open()
            try { kotlinx.coroutines.awaitCancellation() } finally {
                viewModel.pause()
                if (!lifecycle.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) viewModel.closeAsync()
            }
        }
    }
    DisposableEffect(viewModel) { onDispose { if (viewModel.uiState.value.archived) viewModel.closeAsync() else viewModel.pause() } }
    LaunchedEffect(lifecycle) { lifecycle.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
        SessionListIdleTicker.run(nowEpochSeconds = { System.currentTimeMillis() / 1000 }, emit = { now = it })
    } }
    LaunchedEffect(searchText, state.profile, state.showsAllProfiles) { viewModel.search(searchText) }
    LaunchedEffect(appSettings.showsProjectsEntry, state.projects) {
        if (!appSettings.showsProjectsEntry || (lane != null && state.projects.none { it.projectId == lane })) lane = null
    }
    LaunchedEffect(lane, state.isLive, state.query) { lane?.let { viewModel.fillLane(it) } }
    val rows = state.visibleSessions(lane)
    val scheduledRows = rows.filter { it.summary().isCronSession && it.row.archived != true }
    val ordinary = if (state.archived) rows else rows.filter { !it.summary().isCronSession }
    val groups = sessionListSections(ordinary.map { it.summary() }, ZoneId.systemDefault(), now)
    // Grouping uses shared summaries; resolve back by Profile + stored id, never id alone.
    val byKey = rows.associateBy { it.key }
    fun toast(message: String, undo: (suspend () -> Unit)? = null) {
        toastJob?.cancel()
        toastJob = scope.launch {
            if (snackbars.showSnackbar(message, actionLabel = if (undo != null) "Undo" else null,
                    duration = SnackbarDuration.Short) == SnackbarResult.ActionPerformed) undo?.invoke()
        }
    }
    fun archive(row: HermesListedSession) { scope.launch {
        if (state.archived) { viewModel.unarchive(row) }
        else if (viewModel.archive(row)) toast("Archived") { viewModel.unarchive(row) }
    } }
    val renderRow: @Composable (HermesListedSession) -> Unit = { row ->
        HermesSessionInteractiveRow(row, state, settings, appSettings.showsSessionRowStates,
            paneCount > 1 && selectedKey == row.key, now,
            open = { viewModel.beginViewing(row); onOpen(row) }, unread = { viewModel.toggleUnread(row) },
            pin = { scope.launch { viewModel.setPinned(row, row.row.pinned != true) } },
            rename = { viewModel.clearRenameError(); rename = row },
            duplicate = { scope.launch { viewModel.duplicate(row)?.let(onOpen) } },
            move = { moving = row; scope.launch { viewModel.refreshProjects() } },
            archive = { archive(row) }, delete = { delete = row },
            export = { scope.launch {
                val file = viewModel.export(row) ?: return@launch
                try { shareHermesSession(context, file) } catch (error: Exception) { toast(error.message ?: "Couldn't share the session.") }
            } },
        )
    }
    Box(modifier.fillMaxSize()) {
        PullToRefreshBox(isRefreshing = state.isLoading, onRefresh = viewModel::refresh) {
            LazyColumn(state = scroll, contentPadding = PaddingValues(bottom = 104.dp), modifier = Modifier.fillMaxSize()) {
                item("header") {
                    if (onBack != null) SubScreenTopBar(if (state.archived) "Archived Sessions" else "Sessions", onBack)
                    else HermesHomeHeader(account, onOpenSearch = { searchExpanded = true },
                        isSearchExpanded = searchExpanded, searchText = searchText, onSearchTextChange = { searchText = it },
                        onCloseSearch = { searchText = ""; searchExpanded = false })
                }
                if (state.showsOfflineCache) item("offline") { OfflineCacheBanner() }
                if (state.searchErrorMessage != null) item("search-error") { Text(state.searchErrorMessage.orEmpty(), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(24.dp)) }
                if (state.errorMessage != null && state.sessions.isNotEmpty() && !state.showsOfflineCache) item("page-error") {
                    Text(state.errorMessage.orEmpty(), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
                }
                if (!state.archived && !searchExpanded) {
                    val utilities = listOfNotNull(
                        HermesHomeUtility.TASKS.takeIf { appSettings.showsTasksEntry }, HermesHomeUtility.KANBAN.takeIf { appSettings.showsKanbanEntry },
                        HermesHomeUtility.SKILLS.takeIf { appSettings.showsSkillsEntry }, HermesHomeUtility.MEMORY.takeIf { appSettings.showsMemoryEntry },
                        HermesHomeUtility.USAGE.takeIf { appSettings.showsInsightsEntry })
                    items(utilities, key = { "utility-${it.name}" }) { utility -> ListItem(
                        headlineContent = { Text(utility.label) }, trailingContent = { Icon(Icons.Default.ChevronRight, null) },
                        modifier = Modifier.clickable(enabled = state.profile != null) { onUtility(utility) }) }
                }
                if (!state.archived && !state.showsAllProfiles && appSettings.showsProjectsEntry) {
                    item("projects") { ProjectsSectionHeader(settings.projectsAreExpanded, state.projects.size,
                        onToggle = { scope.launch { settingsRepository.setProjectsAreExpanded(!settings.projectsAreExpanded) } },
                        onAddProject = { if (state.isLive) { viewModel.clearProjectError(); creating = true } }) }
                    if (settings.projectsAreExpanded) {
                        item("all-projects") { ProjectFilterRow("All", null, state.sessions.size, lane == null, { lane = null }, null, null) }
                        items(state.projects, key = { "project-${it.projectId}" }) { project -> ProjectFilterRow(
                            project.name, project.color, project.sessionCount, lane == project.projectId,
                            { lane = project.projectId },
                            if (project.isAutomatic || !state.isLive) null else { { viewModel.clearProjectError(); renameProject = project } },
                            if (project.isAutomatic || !state.isLive) null else { { deleteProject = project } }) }
                    }
                }
                if (!state.archived && settings.showsCronSessions && (state.scheduledTotal > 0 || scheduledRows.isNotEmpty())) {
                    item("scheduled") { ScheduledSectionHeader(state.scheduledTotal, showAllScheduled || settings.scheduledSessionsAreExpanded || searchText.isNotBlank(), searchText.isNotBlank(),
                        { scope.launch { settingsRepository.setScheduledSessionsAreExpanded(!settings.scheduledSessionsAreExpanded) } }) }
                    if (settings.scheduledSessionsAreExpanded || showAllScheduled || searchText.isNotBlank()) {
                        items(if (showAllScheduled || searchText.isNotBlank()) scheduledRows else scheduledRows.take(5), key = { "scheduled-${it.id}" }) { renderRow(it) }
                        if (!showAllScheduled && scheduledRows.size > 5) item("scheduled-all") { TextButton(onClick = { showAllScheduled = true }) { Text("View all") } }
                        if (showAllScheduled && state.hasMoreScheduled && searchText.isBlank()) item("scheduled-more") { TextButton(onClick = { scope.launch { viewModel.loadMore(true) } }, enabled = !state.isLoadingMoreScheduled) { Text("Load more scheduled sessions") } }
                    }
                }
                when {
                    state.isLoading && state.sessions.isEmpty() -> item("loading") { LoadingState(Modifier.height(200.dp), "Loading sessions") }
                    state.errorMessage != null && state.sessions.isEmpty() -> item("error") { ErrorState(state.errorMessage.orEmpty(), Modifier.heightIn(max = 260.dp), onRetry = viewModel::refresh) }
                    ordinary.isEmpty() && scheduledRows.isEmpty() && state.hasMore && searchText.isBlank() -> item("paging-empty") { LoadingState(Modifier.height(180.dp), "Loading sessions") }
                    ordinary.isEmpty() && scheduledRows.isEmpty() -> item("empty") { EmptyState(
                        if (searchText.isNotBlank()) "No matching sessions" else if (state.archived) "No archived sessions" else if (lane != null) "No sessions in this project" else "No sessions yet",
                        Modifier.height(180.dp), if (searchText.isNotBlank()) "Try another search or project filter." else null) }
                    else -> groups.forEach { group ->
                        item("section-${group.id}") { SessionSectionHeader(group.title, group.sessions.size) }
                        items(group.sessions, key = { "row-${it.profile}|${it.sessionId}" }) { summary ->
                            byKey["${summary.profile}|${summary.sessionId}"]?.let { renderRow(it) }
                        }
                    }
                }
                if (state.hasMore && searchText.isBlank() && (lane == null || viewModel.laneIsShort(lane!!))) item("more") {
                    LaunchedEffect(state.pageOffset, state.isLoadingMore, lane) {
                        if (lane == null && !state.isLoadingMore && state.errorMessage == null) viewModel.loadMoreAsync()
                    }
                    TextButton(onClick = { scope.launch { if (lane == null) viewModel.loadMore() else viewModel.fillLane(lane!!) } }, enabled = !state.isLoadingMore, modifier = Modifier.fillMaxWidth()) { Text("Load more") }
                }
                if (!state.archived && !state.isViewingCachedData && !state.showsAllProfiles) item("archived") {
                    ListItem(headlineContent = { Text("Archived Sessions") }, leadingContent = { Icon(Icons.Default.Archive, null) },
                        trailingContent = { Icon(Icons.Default.ChevronRight, null) }, modifier = Modifier.clickable(enabled = state.isLive) { onArchived() })
                }
            }
        }
        if (!state.archived) Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp)) { bottomBar() }
        SnackbarHost(snackbars, Modifier.align(Alignment.BottomCenter).padding(horizontal = 16.dp).padding(bottom = if (state.archived) 16.dp else 84.dp))
    }
    rename?.let { row -> RenameSessionDialog(row.displayTitle, state.isRenamingSession, { rename = null; viewModel.clearRenameError() },
        { title -> scope.launch { if (viewModel.rename(row, title)) rename = null } }, state.renameErrorMessage) }
    delete?.let { row -> ConfirmDeleteSessionDialog({ delete = null }, { delete = null; scope.launch { viewModel.delete(row) } }) }
    moving?.let { row -> MoveToProjectSheet(row.summary(), state.projects.filter { !it.isAutomatic && it.folder != null }.map { ProjectSummary(it.projectId, it.name, it.color) },
        row.key in state.mutating, state.isSavingProject, state.isLoadingProjects,
        onMove = { id -> state.projects.firstOrNull { it.projectId == id }?.let { project ->
            project.folder?.let { move = HermesProjectMove(row, project.name, it, state.attention(row) != null) }; moving = null
        } },
        onCreateProject = { moving = null; creatingFor = row; creating = true; viewModel.clearProjectError() },
        onRefreshProjects = { scope.launch { viewModel.refreshProjects() } }, onDismiss = { moving = null }, offersNoProject = false) }
    move?.let { pending -> AlertDialog(onDismissRequest = { move = null }, title = { Text(pending.title) }, text = { Text(pending.message) },
        confirmButton = { TextButton(onClick = { move = null; scope.launch { viewModel.move(pending.row, pending.folder)?.let { old -> toast("Moved") { viewModel.move(pending.row, old) } } } }) { Text("Move") } },
        dismissButton = { TextButton(onClick = { move = null }) { Text("Cancel") } }) }
    if (creating) {
        var folder by rememberSaveable(creatingFor?.key) { mutableStateOf(creatingFor?.row?.cwd.orEmpty()) }
        var suggestions by remember { mutableStateOf(HermesFolderCompletion.Suggestions()) }
        var folderError by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(folder) { delay(250); folderError = null
            try { suggestions = viewModel.completeFolder(folder) }
            catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
            catch (error: Exception) { suggestions = HermesFolderCompletion.Suggestions(); folderError = error.message }
        }
        ProjectFormDialog("New Project", "", ProjectCreationPalette.defaultColor(state.projects.size).hex, state.isSavingProject, "Create",
            onCancel = { creating = false; creatingFor = null },
            onSave = { name, color -> scope.launch {
                val created = viewModel.createProject(name, color, folder) ?: return@launch
                creatingFor?.takeIf { it.row.cwd != created }?.let { move = HermesProjectMove(it, name, created, state.attention(it) != null) }
                creating = false; creatingFor = null
            } }, errorMessage = state.projectErrorMessage,
            canSave = folder.trim().isNotEmpty(), extraFields = {
                OutlinedTextField(folder, { folder = it }, label = { Text("Working folder on Hermes") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(), enabled = !state.isSavingProject)
                suggestions.folders.take(6).forEach { path -> TextButton(onClick = { folder = path }) { Text(path) } }
                if (suggestions.needsMoreTyping) Text("Type more of the folder name.")
                folderError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            })
    }
    renameProject?.let { project -> ProjectFormDialog("Rename Project", project.name, project.color ?: ProjectCreationPalette.defaultColor(0).hex,
        state.isSavingProject, "Save", { renameProject = null }, { name, color -> scope.launch { if (viewModel.renameProject(project, name, color)) renameProject = null } },
        errorMessage = state.projectErrorMessage) }
    deleteProject?.let { project -> ConfirmDeleteProjectDialog({ deleteProject = null }, { deleteProject = null; scope.launch { viewModel.deleteProject(project) } },
        message = "Sessions stay; only the project is removed.") }
    state.actionErrorMessage?.let { ActionErrorDialog(it, viewModel::clearActionError) }
    if (!creating && renameProject == null) state.projectErrorMessage?.let { ActionErrorDialog(it, viewModel::clearProjectError) }
}

/** Same Profile picker at the floating bar's leading end (HermesSessionListView.profilePicker). */
@Composable
fun HermesProfileFilter(state: HermesSessionListState, select: (String) -> Unit, all: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }, enabled = state.profile != null) { Icon(Icons.Default.FilterList, "Filter Profiles") }
        DropdownMenu(expanded, { expanded = false }) {
            DropdownMenuItem(text = { Text("All Profiles") }, trailingIcon = { if (state.showsAllProfiles) Icon(Icons.Default.Check, null) },
                onClick = { expanded = false; all() })
            (state.profiles.ifEmpty { listOfNotNull(state.profile) }).forEach { profile -> DropdownMenuItem(text = { Text(profile) },
                trailingIcon = { if (!state.showsAllProfiles && state.profile == profile) Icon(Icons.Default.Check, null) },
                onClick = { expanded = false; select(profile) }) }
        }
    }
}

/** Port of SessionExportShareSheet: share an actual JSON file, never an Intent text-size-limited transcript. */
private suspend fun shareHermesSession(context: android.content.Context, export: SessionExportFile) {
    val file = withContext(Dispatchers.IO) {
        // This existing FileProvider cache root is also used by document previews; each export has its own directory.
        val root = File(context.cacheDir, "document_previews/session-exports").apply { mkdirs() }
        val cutoff = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
        root.listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.deleteRecursively() }
        File(File(root, UUID.randomUUID().toString()).apply { mkdirs() }, export.filename).apply { writeBytes(export.data) }
    }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "application/json"; putExtra(Intent.EXTRA_STREAM, uri); putExtra(Intent.EXTRA_SUBJECT, export.filename)
        clipData = ClipData.newRawUri(export.filename, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, export.filename))
}
