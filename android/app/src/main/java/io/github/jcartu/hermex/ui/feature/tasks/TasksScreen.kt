package io.github.jcartu.hermex.ui.feature.tasks

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.jcartu.hermex.model.CronJob
import io.github.jcartu.hermex.model.CronJobEditorDraft
import io.github.jcartu.hermex.model.CronRecentCompletion
import io.github.jcartu.hermex.ui.adaptive.HingeAwarePaneRow
import io.github.jcartu.hermex.ui.adaptive.LocalPaneCount
import io.github.jcartu.hermex.ui.components.EmptyState
import io.github.jcartu.hermex.ui.components.ErrorState
import io.github.jcartu.hermex.ui.components.LoadingState
import kotlinx.coroutines.launch

private const val MAX_PANE_CONTENT_WIDTH_DP = 560

/** Rows "Ran Recently" shows before it needs a "Show all" row. */
private const val COMPACT_RECENT_RUN_COUNT = 3

/**
 * A server's scheduled Tasks, rebuilt as an agenda: status filters, per-row
 * actions (run now / pause / resume / delete with confirmations), and recent
 * runs across all tasks.
 * Port of `Features/Tasks/TasksView.swift` + `TasksViewModel.swift`.
 *
 * Adaptive: 1 pane → list with full-screen detail + back; 2 panes → list |
 * detail; 3 panes → list | detail | run history.
 */
@Composable
fun TasksRoute(client: CronDataClient, modifier: Modifier = Modifier) {
    val tasksViewModel: TasksViewModel = viewModel(
        factory = viewModelFactory { initializer { TasksViewModel(client) } },
    )
    val state by tasksViewModel.uiState.collectAsStateWithLifecycle()
    val paneCount = LocalPaneCount.current
    val scope = rememberCoroutineScope()

    var selectedJobKey by rememberSaveable { mutableStateOf<String?>(null) }
    val selectedJob = state.jobs.firstOrNull { jobKey(it) == selectedJobKey }

    var showCreateSheet by rememberSaveable { mutableStateOf(false) }
    var jobPendingDeletion by remember { mutableStateOf<CronJob?>(null) }
    var jobPendingRunConfirmation by remember { mutableStateOf<CronJob?>(null) }
    var isShowingAllRecentRuns by rememberSaveable { mutableStateOf(false) }

    val listPane: @Composable (Modifier) -> Unit = { paneModifier ->
        TasksListPane(
            state = state,
            selectedJobKey = if (paneCount == 1) null else selectedJobKey,
            onSelect = { selectedJobKey = jobKey(it) },
            onFilterChange = tasksViewModel::setFilter,
            onRefresh = tasksViewModel::load,
            onNewTask = {
                tasksViewModel.clearActionError()
                showCreateSheet = true
            },
            onRunNow = { job ->
                if (client.cronFeatures.runNowResumes(job)) {
                    jobPendingRunConfirmation = job
                } else {
                    scope.launch { tasksViewModel.runNow(job) }
                }
            },
            onTogglePauseResume = { job ->
                scope.launch {
                    if (job.isLive) tasksViewModel.pause(job) else tasksViewModel.resume(job)
                }
            },
            onDelete = { job -> jobPendingDeletion = job },
            isShowingAllRecentRuns = isShowingAllRecentRuns,
            onToggleAllRecentRuns = { isShowingAllRecentRuns = !isShowingAllRecentRuns },
            onRecentRunClick = { completion ->
                tasksViewModel.job(completion)?.let { job ->
                    selectedJobKey = jobKey(job)
                }
            },
            modifier = paneModifier,
        )
    }

    val detailPane: @Composable (Modifier, (() -> Unit)?) -> Unit = { paneModifier, onBack ->
        if (selectedJob != null) {
            TaskDetailRoute(
                job = selectedJob,
                runningElapsed = state.runningElapsed(selectedJob),
                client = client,
                onMutation = tasksViewModel::apply,
                onJobStartedRunning = { selectedJob.jobId?.let { _ -> tasksViewModel.load() } },
                onJobPaused = { selectedJob.jobId?.let { _ -> tasksViewModel.load() } },
                onDeleted = { selectedJobKey = null },
                onBack = onBack,
                modifier = paneModifier,
            )
        } else {
            EmptyState(
                title = "Select a task",
                message = "Choose a scheduled job to see its details.",
                modifier = paneModifier,
            )
        }
    }

    val historyPane: @Composable (Modifier) -> Unit = { paneModifier ->
        if (selectedJob != null) {
            TaskRunHistoryPane(
                jobKey = jobKey(selectedJob),
                client = client,
                modifier = paneModifier,
            )
        } else {
            EmptyState(
                title = "Run History",
                message = "Select a task to see its runs.",
                modifier = paneModifier,
            )
        }
    }

    if (paneCount == 1) {
        if (selectedJob != null) {
            detailPane(modifier) { selectedJobKey = null }
        } else {
            listPane(modifier)
        }
    } else {
        HingeAwarePaneRow(paneCount = paneCount, modifier = modifier) { index, paneModifier ->
            when (index) {
                0 -> listPane(paneModifier)
                1 -> detailPane(paneModifier, null)
                else -> historyPane(paneModifier)
            }
        }
    }

    if (showCreateSheet) {
        CronJobEditorSheet(
            title = "New Task",
            initialDraft = CronJobEditorDraft(),
            saveTitle = "Create",
            isSaving = state.isMutating,
            errorMessage = state.actionErrorMessage,
            deliveryOptions = state.deliveryOptions,
            client = client,
            onDismiss = { showCreateSheet = false },
            onSave = { draft ->
                val didCreate = tasksViewModel.create(draft)
                if (didCreate) showCreateSheet = false
                didCreate
            },
        )
    }

    jobPendingDeletion?.let { job ->
        AlertDialog(
            onDismissRequest = { jobPendingDeletion = null },
            title = { Text("Delete Task?") },
            text = { Text("\u201C${job.displayName}\u201D will be removed from the Hermes server.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        jobPendingDeletion = null
                        scope.launch { tasksViewModel.delete(job) }
                    },
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { jobPendingDeletion = null }) { Text("Cancel") }
            },
        )
    }

    jobPendingRunConfirmation?.let { job ->
        AlertDialog(
            onDismissRequest = { jobPendingRunConfirmation = null },
            title = { Text("Run Now?") },
            text = { Text("This also resumes the Task.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        jobPendingRunConfirmation = null
                        scope.launch { tasksViewModel.runNow(job) }
                    },
                ) {
                    Text("Run Now")
                }
            },
            dismissButton = {
                TextButton(onClick = { jobPendingRunConfirmation = null }) { Text("Cancel") }
            },
        )
    }

    // Shown once the create sheet has closed on the error it reported.
    if (!showCreateSheet) {
        state.actionErrorMessage?.let { message ->
            AlertDialog(
                onDismissRequest = tasksViewModel::clearActionError,
                title = { Text("Could Not Update Task") },
                text = { Text(message) },
                confirmButton = {
                    TextButton(onClick = tasksViewModel::clearActionError) { Text("OK") }
                },
            )
        }
    }

    state.saveWarning?.let { warning ->
        AlertDialog(
            onDismissRequest = tasksViewModel::clearSaveWarning,
            title = { Text("Saved") },
            text = { Text(warning) },
            confirmButton = {
                TextButton(onClick = tasksViewModel::clearSaveWarning) { Text("OK") }
            },
        )
    }
}

/** Stable selection key: prefers the server job id, then the name. */
private fun jobKey(job: CronJob): String = job.jobId ?: job.name ?: job.displayName

// MARK: - List pane

@Composable
private fun TasksListPane(
    state: TasksUiState,
    selectedJobKey: String?,
    onSelect: (CronJob) -> Unit,
    onFilterChange: (TaskFilter) -> Unit,
    onRefresh: () -> Unit,
    onNewTask: () -> Unit,
    onRunNow: (CronJob) -> Unit,
    onTogglePauseResume: (CronJob) -> Unit,
    onDelete: (CronJob) -> Unit,
    isShowingAllRecentRuns: Boolean,
    onToggleAllRecentRuns: () -> Unit,
    onRecentRunClick: (CronRecentCompletion) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier
                .fillMaxSize()
                .widthIn(max = MAX_PANE_CONTENT_WIDTH_DP.dp)
                .padding(horizontal = 16.dp),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Tasks", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onNewTask, enabled = !state.isMutating) {
                    Icon(Icons.Filled.Add, contentDescription = "New Task")
                }
                if (state.isLoading) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    IconButton(onClick = onRefresh) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                    }
                }
            }

            when {
                state.isLoading && state.jobs.isEmpty() -> LoadingState(label = "Loading tasks...")
                state.errorMessage != null && state.jobs.isEmpty() -> ErrorState(
                    message = state.errorMessage,
                    retryLabel = "Try Again",
                    onRetry = onRefresh,
                )
                state.jobs.isEmpty() -> EmptyState(
                    title = "No Tasks",
                    message = "Scheduled jobs from the Hermes server will appear here.",
                )
                else -> {
                    FilterPicker(
                        selected = state.filter,
                        counts = TaskFilter.entries.associateWith { filter ->
                            TaskAgenda.count(filter, state.jobs, state.runningJobs.keys)
                        },
                        onSelect = onFilterChange,
                    )

                    val sections = TaskAgenda.sections(
                        jobs = state.jobs,
                        runningJobIds = state.runningJobs.keys,
                        filter = state.filter,
                        now = java.time.Instant.now(),
                    )

                    val listState = rememberLazyListState()

                    LazyColumn(
                        state = listState,
                        contentPadding = PaddingValues(vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        state.schedulerStallAge?.let { age ->
                            item {
                                SchedulerNotice(ageSeconds = age)
                            }
                        }

                        if (state.recentRuns.isNotEmpty()) {
                            item {
                                RecentRunsHeader()
                            }
                            val truncated = state.recentRuns.size > COMPACT_RECENT_RUN_COUNT && !isShowingAllRecentRuns
                            val visibleRuns = if (truncated) {
                                state.recentRuns.take(COMPACT_RECENT_RUN_COUNT)
                            } else {
                                state.recentRuns
                            }
                            items(visibleRuns.size) { index ->
                                RecentRunRow(
                                    completion = visibleRuns[index],
                                    onClick = { onRecentRunClick(visibleRuns[index]) },
                                )
                            }
                            if (state.recentRuns.size > COMPACT_RECENT_RUN_COUNT) {
                                item {
                                    TextButton(onClick = onToggleAllRecentRuns) {
                                        Text(
                                            if (truncated) "Show All (${state.recentRuns.size})" else "Show Less",
                                            style = MaterialTheme.typography.bodyMedium,
                                        )
                                    }
                                }
                            }
                            item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
                        }

                        if (sections.isEmpty()) {
                            item {
                                EmptyState(
                                    title = "No Matching Tasks",
                                    message = "No tasks match this filter.",
                                )
                            }
                        }

                        sections.forEach { section ->
                            item {
                                Text(
                                    section.group.title,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                                )
                            }
                            items(section.jobs.size) { index ->
                                val job = section.jobs[index]
                                AgendaRow(
                                    job = job,
                                    group = section.group,
                                    runningElapsed = state.runningElapsed(job),
                                    selected = selectedJobKey == jobKey(job),
                                    isPendingAction = state.pendingActionJobIds.contains(job.jobId),
                                    onSelect = { onSelect(job) },
                                    onRunNow = { onRunNow(job) },
                                    onTogglePauseResume = { onTogglePauseResume(job) },
                                    onDelete = { onDelete(job) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FilterPicker(
    selected: TaskFilter,
    counts: Map<TaskFilter, Int>,
    onSelect: (TaskFilter) -> Unit,
) {
    SingleChoiceSegmentedButtonRow(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
    ) {
        TaskFilter.entries.forEachIndexed { index, filter ->
            SegmentedButton(
                selected = selected == filter,
                onClick = { onSelect(filter) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = TaskFilter.entries.size),
            ) {
                Text(filter.title(count = counts[filter] ?: 0), maxLines = 1)
            }
        }
    }
}

/** One line when a Hermes host's scheduler has stopped ticking (#1040), above everything else. */
@Composable
private fun SchedulerNotice(ageSeconds: Double) {
    val stalled = stalledText(ageSeconds)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            Icons.Filled.WarningAmber,
            contentDescription = null,
            tint = PausedOrange,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            "Scheduled Tasks aren't running. The host's scheduler hasn't checked in for $stalled.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

private fun stalledText(ageSeconds: Double): String {
    val totalMinutes = (ageSeconds / 60).toLong()
    val days = totalMinutes / (24 * 60)
    val hours = (totalMinutes % (24 * 60)) / 60
    val minutes = totalMinutes % 60
    return when {
        days > 0 -> "${days}d ${hours}h"
        hours > 0 -> "${hours}h ${minutes}m"
        else -> "${minutes}m"
    }
}

@Composable
private fun RecentRunsHeader() {
    Text(
        "Ran Recently",
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
    )
}

@Composable
private fun RecentRunRow(completion: CronRecentCompletion, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        TaskRecentRunRowView(completion = completion)
    }
}

@Composable
private fun AgendaRow(
    job: CronJob,
    group: TaskAgendaGroup,
    runningElapsed: Double?,
    selected: Boolean,
    isPendingAction: Boolean,
    onSelect: () -> Unit,
    onRunNow: () -> Unit,
    onTogglePauseResume: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    Surface(
        onClick = onSelect,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.surfaceContainerHigh
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Box(
                Modifier
                    .weight(1f)
                    .padding(start = 12.dp, top = 8.dp, bottom = 8.dp),
            ) {
                CronJobRowView(
                    job = job,
                    group = group,
                    runningElapsed = runningElapsed,
                )
            }
            Box {
                IconButton(
                    onClick = { menuExpanded = true },
                    enabled = !isPendingAction,
                ) {
                    if (isPendingAction) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Filled.MoreVert, contentDescription = "Task Actions")
                    }
                }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    DropdownMenuItem(
                        text = { Text("Run Now") },
                        leadingIcon = { Icon(Icons.Filled.PlayArrow, contentDescription = null) },
                        enabled = job.jobId != null && !isPendingAction,
                        onClick = {
                            menuExpanded = false
                            onRunNow()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(if (job.isLive) "Pause" else "Resume") },
                        leadingIcon = {
                            Icon(
                                if (job.isLive) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                contentDescription = null,
                            )
                        },
                        enabled = job.jobId != null && !isPendingAction,
                        onClick = {
                            menuExpanded = false
                            onTogglePauseResume()
                        },
                    )
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                        leadingIcon = {
                            Icon(Icons.Filled.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                        },
                        enabled = job.jobId != null && !isPendingAction,
                        onClick = {
                            menuExpanded = false
                            onDelete()
                        },
                    )
                }
            }
        }
    }
}

// MARK: - Trifold history pane

/** Trifold third pane: the selected task's run history on its own panel. */
@Composable
private fun TaskRunHistoryPane(
    jobKey: String,
    client: CronDataClient,
    modifier: Modifier = Modifier,
) {
    // The detail pane owns the authoritative history state; this pane shows a
    // read-only view of the same history by sharing the keyed ViewModel.
    val jobsViewModel: TasksViewModel = androidx.lifecycle.viewmodel.compose.viewModel(
        factory = viewModelFactory { initializer { TasksViewModel(client) } },
    )
    val jobsState by jobsViewModel.uiState.collectAsStateWithLifecycle()
    val job = jobsState.jobs.firstOrNull { jobKey(it) == jobKey }

    if (job == null) return

    val detail: TaskDetailViewModel = viewModel(
        key = "taskDetail:$jobKey",
        factory = viewModelFactory {
            initializer {
                TaskDetailViewModel(
                    initialJob = job,
                    runningElapsed = jobsState.runningElapsed(job),
                    client = client,
                )
            }
        },
    )
    val detailState by detail.uiState.collectAsStateWithLifecycle()

    Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier
                .fillMaxSize()
                .widthIn(max = MAX_PANE_CONTENT_WIDTH_DP.dp)
                .padding(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Run History",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = detail::loadHistory) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Refresh history")
                }
            }
            if (!detailState.isHistoryUnavailable) {
                TaskRunHistorySection(
                    runs = detailState.runs,
                    total = detailState.runTotal,
                    isLoading = detailState.isLoadingHistory,
                    isLoadingMore = detailState.isLoadingMoreRuns,
                    canLoadMore = detailState.canLoadMoreRuns,
                    remainingCount = detailState.remainingRunCount,
                    errorMessage = detailState.historyErrorMessage,
                    outcome = detail::outcome,
                    selectRun = detail::loadRunOutput,
                    retry = detail::loadHistory,
                    loadMore = detail::loadMoreRuns,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
