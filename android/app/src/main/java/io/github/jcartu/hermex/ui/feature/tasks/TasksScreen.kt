package io.github.jcartu.hermex.ui.feature.tasks

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.jcartu.hermex.HermexApplication
import io.github.jcartu.hermex.model.CronJob
import io.github.jcartu.hermex.model.CronJobEditorDraft
import io.github.jcartu.hermex.model.CronJobStatus
import io.github.jcartu.hermex.ui.adaptive.HingeAwarePaneRow
import io.github.jcartu.hermex.ui.adaptive.LocalPaneCount
import io.github.jcartu.hermex.ui.components.EmptyState
import io.github.jcartu.hermex.ui.components.ErrorState
import io.github.jcartu.hermex.ui.components.LoadingState
import io.github.jcartu.hermex.ui.theme.CodeTextStyle
import io.github.jcartu.hermex.ui.theme.SuccessDark
import io.github.jcartu.hermex.ui.theme.SuccessLight
import kotlinx.coroutines.launch

/** Blue used for the "Running" badge — mirrors iOS `.blue`. */
private val RunningBlue = Color(0xFF4A9EFF)

/** Orange used for paused/off badges — mirrors iOS `.orange`. */
private val PausedOrange = Color(0xFFFF9F0A)

private const val MAX_PANE_CONTENT_WIDTH_DP = 520

/**
 * Scheduled tasks (cron jobs). Port of `Features/Tasks/TasksView.swift` +
 * `TaskDetailView.swift`: list with running summary and search, create/edit
 * editor sheet, run/pause/resume/delete actions, and recent output.
 *
 * Adaptive: 1 pane → list with full-screen detail + back; 2 panes → list |
 * detail (in-place selection); 3 panes → list | detail | recent output, with
 * boundaries snapped to the physical hinges by [HingeAwarePaneRow].
 */
@Composable
fun TasksRoute() {
    val container = (LocalContext.current.applicationContext as HermexApplication).container
    val tasksViewModel: TasksViewModel = viewModel(
        factory = viewModelFactory { initializer { TasksViewModel(container.api) } },
    )
    val state by tasksViewModel.uiState.collectAsStateWithLifecycle()
    val paneCount = LocalPaneCount.current
    val scope = rememberCoroutineScope()

    var selectedJobKey by rememberSaveable { mutableStateOf<String?>(null) }
    val selectedJob = state.jobs.firstOrNull { jobKey(it) == selectedJobKey }

    var showCreateSheet by rememberSaveable { mutableStateOf(false) }

    // Keyed per job so switching selection loads that job's output while the
    // ViewModel (and its fetched output) survives fold/unfold pane changes.
    val detailViewModel: TaskDetailViewModel? = selectedJob?.let { job ->
        viewModel<TaskDetailViewModel>(
            key = "taskDetail:${jobKey(job)}",
            factory = viewModelFactory {
                initializer {
                    TaskDetailViewModel(container.api, job.jobId, tasksViewModel::apply)
                }
            },
        )
    }
    val detailState = detailViewModel?.uiState?.collectAsStateWithLifecycle()?.value

    val listPane: @Composable (Modifier) -> Unit = { paneModifier ->
        TasksListPane(
            state = state,
            selectedJobKey = if (paneCount == 1) null else selectedJobKey,
            onSelect = { selectedJobKey = jobKey(it) },
            onSearchTextChange = tasksViewModel::setSearchText,
            onRefresh = tasksViewModel::load,
            onNewTask = {
                tasksViewModel.clearActionError()
                showCreateSheet = true
            },
            modifier = paneModifier,
        )
    }

    val detailPane: @Composable (Modifier, Boolean, (() -> Unit)?) -> Unit =
        { paneModifier, showOutputs, onBack ->
            if (selectedJob != null && detailViewModel != null && detailState != null) {
                TaskDetailPane(
                    job = selectedJob,
                    runningElapsed = state.runningElapsed(selectedJob),
                    detailState = detailState,
                    detailViewModel = detailViewModel,
                    onJobStartedRunning = { selectedJob.jobId?.let(tasksViewModel::markRunning) },
                    onJobPaused = { selectedJob.jobId?.let(tasksViewModel::markStopped) },
                    onDeleted = { selectedJobKey = null },
                    showOutputs = showOutputs,
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

    if (paneCount == 1) {
        if (selectedJob != null) {
            BackHandler { selectedJobKey = null }
            detailPane(Modifier, true) { selectedJobKey = null }
        } else {
            listPane(Modifier)
        }
    } else {
        HingeAwarePaneRow(paneCount = paneCount) { index, paneModifier ->
            when (index) {
                0 -> listPane(paneModifier)
                1 -> detailPane(paneModifier, paneCount < 3, null)
                else -> if (detailViewModel != null && detailState != null) {
                    TaskOutputPane(
                        detailState = detailState,
                        onRefresh = detailViewModel::load,
                        modifier = paneModifier,
                    )
                } else {
                    EmptyState(
                        title = "Task output",
                        message = "Select a task to see its recent output.",
                        modifier = paneModifier,
                    )
                }
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
            onDismiss = { showCreateSheet = false },
            onSave = { draft ->
                scope.launch {
                    if (tasksViewModel.create(draft)) {
                        showCreateSheet = false
                    }
                }
            },
        )
    }
}

/** Stable selection key: prefers the server job id, then the name. */
private fun jobKey(job: CronJob): String =
    job.jobId ?: job.name ?: job.displayName

// MARK: - List pane

@Composable
private fun TasksListPane(
    state: TasksUiState,
    selectedJobKey: String?,
    onSelect: (CronJob) -> Unit,
    onSearchTextChange: (String) -> Unit,
    onRefresh: () -> Unit,
    onNewTask: () -> Unit,
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
                Modifier.fillMaxWidth().padding(top = 12.dp),
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

            OutlinedTextField(
                value = state.searchText,
                onValueChange = onSearchTextChange,
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                singleLine = true,
                placeholder = { Text("Search tasks...") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (state.searchText.isNotEmpty()) {
                        IconButton(onClick = { onSearchTextChange("") }) {
                            Icon(Icons.Filled.Close, contentDescription = "Clear search")
                        }
                    }
                },
            )

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
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Filled.Bolt,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("Running now", style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.weight(1f))
                        Text(
                            state.activeRunningCount.toString(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    HorizontalDivider()

                    val filtered = state.filteredJobs
                    if (filtered.isEmpty()) {
                        EmptyState(
                            title = "No Results",
                            message = "No tasks match \"${state.searchText.trim()}\".",
                        )
                    } else {
                        LazyColumn(
                            state = rememberLazyListState(),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            contentPadding = PaddingValues(vertical = 12.dp),
                        ) {
                            items(filtered.size) { index ->
                                val job = filtered[index]
                                CronJobRow(
                                    job = job,
                                    runningElapsed = state.runningElapsed(job),
                                    selected = selectedJobKey == jobKey(job),
                                    onClick = { onSelect(job) },
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
private fun CronJobRow(
    job: CronJob,
    runningElapsed: Double?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.surfaceContainerHigh
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    job.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                StatusBadge(
                    text = if (runningElapsed == null) job.status.label else "Running",
                    color = statusColor(job.status, isRunning = runningElapsed != null),
                )
            }

            val prompt = job.prompt
            if (!prompt.isNullOrEmpty()) {
                Text(
                    prompt,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                )
            }

            CronJobMetadata(job = job, runningElapsed = runningElapsed, includeExtended = false)
        }
    }
}

// MARK: - Detail pane

@Composable
private fun TaskDetailPane(
    job: CronJob,
    runningElapsed: Double?,
    detailState: TaskDetailUiState,
    detailViewModel: TaskDetailViewModel,
    onJobStartedRunning: () -> Unit,
    onJobPaused: () -> Unit,
    onDeleted: () -> Unit,
    showOutputs: Boolean,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var menuExpanded by remember { mutableStateOf(false) }
    var showEditSheet by rememberSaveable { mutableStateOf(false) }
    var confirmingDelete by rememberSaveable { mutableStateOf(false) }

    val isActionDisabled = detailState.isMutating || job.jobId == null
    val shouldResume = job.status == CronJobStatus.PAUSED || job.status == CronJobStatus.OFF

    Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier
                .fillMaxSize()
                .widthIn(max = MAX_PANE_CONTENT_WIDTH_DP.dp),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (onBack != null) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
                Text(
                    job.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = if (onBack == null) 12.dp else 0.dp),
                )
                if (detailState.isLoading) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    IconButton(onClick = detailViewModel::load) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh output")
                    }
                }
                Box {
                    IconButton(
                        onClick = { menuExpanded = true },
                        enabled = !detailState.isMutating,
                    ) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "Task Actions")
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("Run Now") },
                            leadingIcon = { Icon(Icons.Filled.PlayArrow, contentDescription = null) },
                            enabled = !isActionDisabled,
                            onClick = {
                                menuExpanded = false
                                scope.launch {
                                    if (detailViewModel.runNow()) onJobStartedRunning()
                                }
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(if (shouldResume) "Resume" else "Pause") },
                            leadingIcon = {
                                Icon(
                                    if (shouldResume) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                                    contentDescription = null,
                                )
                            },
                            enabled = !isActionDisabled,
                            onClick = {
                                menuExpanded = false
                                scope.launch {
                                    val ok = if (shouldResume) {
                                        detailViewModel.resume()
                                    } else {
                                        detailViewModel.pause()
                                    }
                                    if (ok && !shouldResume) onJobPaused()
                                }
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Edit") },
                            leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                            enabled = !isActionDisabled,
                            onClick = {
                                menuExpanded = false
                                detailViewModel.clearActionError()
                                showEditSheet = true
                            },
                        )
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                            leadingIcon = {
                                Icon(
                                    Icons.Filled.Delete,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            },
                            enabled = !isActionDisabled,
                            onClick = {
                                menuExpanded = false
                                confirmingDelete = true
                            },
                        )
                    }
                }
            }

            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // Header — name + status badge + prompt.
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.Top) {
                        Text(
                            job.displayName,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            maxLines = 2,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(8.dp))
                        StatusBadge(
                            text = if (runningElapsed == null) job.status.label else "Running",
                            color = statusColor(job.status, isRunning = runningElapsed != null),
                        )
                    }
                    val prompt = job.prompt
                    if (!prompt.isNullOrEmpty()) {
                        Text(
                            prompt,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                // Action status — port of TaskDetailView.actionStatusSection.
                if (detailState.isMutating) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Updating task...",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else if (detailState.actionErrorMessage != null) {
                    Text(
                        detailState.actionErrorMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                CronJobMetadata(job = job, runningElapsed = runningElapsed, includeExtended = true)

                if (showOutputs) {
                    TaskOutputsContent(
                        detailState = detailState,
                        onRetry = detailViewModel::load,
                    )
                }
            }
        }
    }

    if (showEditSheet) {
        CronJobEditorSheet(
            title = "Edit Task",
            initialDraft = CronJobEditorDraft(job),
            saveTitle = "Save",
            isSaving = detailState.isMutating,
            errorMessage = detailState.actionErrorMessage,
            deliveryOptions = detailState.deliveryOptions,
            onDismiss = { showEditSheet = false },
            onSave = { draft ->
                scope.launch {
                    if (detailViewModel.update(draft)) {
                        showEditSheet = false
                    }
                }
            },
        )
    }

    if (confirmingDelete) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text("Delete Task?") },
            text = { Text("This removes the scheduled task from the Hermes server.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmingDelete = false
                        scope.launch {
                            if (detailViewModel.delete()) onDeleted()
                        }
                    },
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmingDelete = false }) { Text("Cancel") }
            },
        )
    }
}

/**
 * Metadata rows shared by the list row and the detail pane. Port of
 * `CronJobMetadataRow` usage in TasksView/TaskDetailView; [includeExtended]
 * adds the detail-only Toasts and Repeat rows.
 */
@Composable
private fun CronJobMetadata(
    job: CronJob,
    runningElapsed: Double?,
    includeExtended: Boolean,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        CronJobMetadataRow("Schedule", job.scheduleText ?: "Not available")
        CronJobMetadataRow("Next", job.nextRunAt?.formatted ?: "Not available")
        CronJobMetadataRow("Last", job.lastRunAt?.formatted ?: "Never")
        if (runningElapsed != null) {
            CronJobMetadataRow("Elapsed", elapsedText(runningElapsed))
        }
        CronJobMetadataRow("Deliver", job.deliver ?: "local")
        job.model?.takeIf { it.isNotEmpty() }?.let { CronJobMetadataRow("Model", it) }
        job.provider?.takeIf { it.isNotEmpty() }?.let { CronJobMetadataRow("Provider", it) }
        job.profile?.takeIf { it.isNotEmpty() }?.let { CronJobMetadataRow("Profile", it) }
        if (includeExtended) {
            job.toastNotifications?.let { CronJobMetadataRow("Toasts", if (it) "On" else "Off") }
            repeatText(job)?.let { CronJobMetadataRow("Repeat", it) }
        }
        job.skills?.takeIf { it.isNotEmpty() }?.let {
            CronJobMetadataRow("Skills", it.joinToString(", "))
        }
        (job.lastError ?: job.lastDeliveryError)?.takeIf { it.isNotEmpty() }?.let {
            CronJobMetadataRow("Error", it, valueColor = MaterialTheme.colorScheme.error)
        }
    }
}

/** Trifold third pane: the recent output log on its own panel. */
@Composable
private fun TaskOutputPane(
    detailState: TaskDetailUiState,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier
                .fillMaxSize()
                .widthIn(max = MAX_PANE_CONTENT_WIDTH_DP.dp),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Recent Output",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                if (detailState.isLoading) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    IconButton(onClick = onRefresh) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh output")
                    }
                }
            }
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 24.dp),
            ) {
                TaskOutputsContent(
                    detailState = detailState,
                    onRetry = onRefresh,
                    showHeading = false,
                )
            }
        }
    }
}

/** Output list shared by the inline detail section and the trifold output pane. */
@Composable
private fun TaskOutputsContent(
    detailState: TaskDetailUiState,
    onRetry: () -> Unit,
    showHeading: Boolean = true,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (showHeading) {
            Text("Recent Output", style = MaterialTheme.typography.titleSmall)
        }
        when {
            detailState.isLoading && detailState.outputs.isEmpty() -> Text(
                "Loading output...",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            detailState.errorMessage != null && detailState.outputs.isEmpty() -> ErrorState(
                message = detailState.errorMessage,
                retryLabel = "Try Again",
                onRetry = onRetry,
                modifier = Modifier.fillMaxWidth().height(180.dp),
            )
            detailState.outputs.isEmpty() -> Text(
                "This task has not produced any output yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            else -> detailState.outputs.forEach { output ->
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        output.filename ?: "Untitled",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    val content = output.content
                    if (!content.isNullOrEmpty()) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            SelectionContainer {
                                Text(
                                    content,
                                    style = CodeTextStyle,
                                    modifier = Modifier.padding(12.dp),
                                )
                            }
                        }
                    } else {
                        Text(
                            "Empty output",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

// MARK: - Shared pieces

@Composable
private fun StatusBadge(text: String, color: Color) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        color = color,
        maxLines = 1,
        modifier = Modifier
            .background(color.copy(alpha = 0.12f), CircleShape)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

@Composable
private fun CronJobMetadataRow(
    title: String,
    value: String,
    valueColor: Color = Color.Unspecified,
) {
    Row(verticalAlignment = Alignment.Top) {
        Text(
            title,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(72.dp),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            color = valueColor,
            maxLines = 2,
            modifier = Modifier.weight(1f),
        )
    }
}

/** Badge colors mirroring iOS: running=blue, active=green, paused/off=orange, error=red, needs attention=yellow/gold. */
@Composable
private fun statusColor(status: CronJobStatus, isRunning: Boolean): Color {
    if (isRunning) return RunningBlue
    return when (status) {
        CronJobStatus.ACTIVE -> if (isSystemInDarkTheme()) SuccessDark else SuccessLight
        CronJobStatus.PAUSED, CronJobStatus.OFF -> PausedOrange
        CronJobStatus.ERROR -> MaterialTheme.colorScheme.error
        CronJobStatus.NEEDS_ATTENTION -> MaterialTheme.colorScheme.tertiary
    }
}

/** Port of the Swift `elapsedText`: "42s" under a minute, "3m 5s" after. */
internal fun elapsedText(elapsed: Double): String {
    if (elapsed < 60) return "${Math.round(elapsed)}s"
    val minutes = (elapsed / 60).toInt()
    val seconds = (elapsed % 60).toInt()
    return "${minutes}m ${seconds}s"
}

/** Human text for CronRepeat, e.g. "3 of 10 runs". */
internal fun repeatText(job: CronJob): String? {
    val repeat = job.repeatInfo ?: return null
    val times = repeat.times
    val completed = repeat.completed
    return when {
        times != null && completed != null -> "$completed of $times runs"
        times != null -> "$times runs"
        completed != null -> "$completed completed"
        else -> null
    }
}
