package io.github.jcartu.hermex.ui.feature.tasks

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import io.github.jcartu.hermex.model.CronRunHistoryItem
import io.github.jcartu.hermex.ui.components.SectionCard
import io.github.jcartu.hermex.ui.theme.CodeTextStyle
import kotlinx.coroutines.launch

private const val MAX_PANE_CONTENT_WIDTH_DP = 560

/**
 * Task Detail: what the task is, when it runs next, how it is configured, and —
 * the part the list cannot answer — what it has actually been doing.
 * Port of `Features/Tasks/TaskDetailView.swift`.
 */
@Composable
fun TaskDetailRoute(
    job: CronJob,
    runningElapsed: Double?,
    client: CronDataClient,
    onMutation: (CronJobListMutation) -> Unit,
    onJobStartedRunning: () -> Unit,
    onJobPaused: () -> Unit,
    onDeleted: () -> Unit,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val viewModel: TaskDetailViewModel = viewModel(
        key = "taskDetail:${job.jobId ?: job.displayName}",
        factory = viewModelFactory {
            initializer { TaskDetailViewModel(job, runningElapsed, client) }
        },
    )
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    val scope = rememberCoroutineScope()
    var menuExpanded by remember { mutableStateOf(false) }
    var showEditSheet by rememberSaveable { mutableStateOf(false) }
    var confirmingDelete by rememberSaveable { mutableStateOf(false) }
    var confirmingRunNow by rememberSaveable { mutableStateOf(false) }
    var selectedRun by remember { mutableStateOf<CronRunHistoryItem?>(null) }
    var runNowActive by remember { mutableStateOf(false) }

    if (onBack != null) {
        BackHandler(onBack = onBack)
    }

    val features = client.cronFeatures
    val detailJob = state.job
    val isActionDisabled = state.isMutating || detailJob.jobId == null

    fun startRunNow() {
        if (runNowActive) return
        runNowActive = true
        scope.launch {
            val didRun = viewModel.runNow()
            runNowActive = false
            if (didRun) onJobStartedRunning()
        }
    }

    fun requestRunNow() {
        if (features.runNowResumes(detailJob)) {
            confirmingRunNow = true
        } else {
            startRunNow()
        }
    }

    fun togglePauseResume() {
        scope.launch {
            val ok = if (detailJob.shouldResume) viewModel.resume() else viewModel.pause()
            if (ok && !detailJob.shouldResume) onJobPaused()
        }
    }

    fun openRun(run: CronRunHistoryItem) {
        selectedRun = run
        viewModel.loadRunOutput(run)
    }

    Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier
                .fillMaxSize()
                .widthIn(max = MAX_PANE_CONTENT_WIDTH_DP.dp),
        ) {
            // Header bar.
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (onBack != null) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
                Text(
                    detailJob.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = if (onBack == null) 12.dp else 0.dp),
                )
                if (state.isLoading) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    IconButton(onClick = viewModel::load) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                    }
                }
                Box {
                    IconButton(
                        onClick = { menuExpanded = true },
                        enabled = !state.isMutating,
                    ) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "Task Actions")
                    }
                    DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                        if (features.offersRunNow(detailJob)) {
                            DropdownMenuItem(
                                text = { Text("Run Now") },
                                leadingIcon = { Icon(Icons.Filled.PlayArrow, contentDescription = null) },
                                enabled = !isActionDisabled && state.runNowState == RunNowState.IDLE,
                                onClick = {
                                    menuExpanded = false
                                    requestRunNow()
                                },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text(detailJob.pauseResumeTitle) },
                            leadingIcon = {
                                Icon(
                                    if (detailJob.shouldResume) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                                    contentDescription = null,
                                )
                            },
                            enabled = !isActionDisabled,
                            onClick = {
                                menuExpanded = false
                                togglePauseResume()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Edit") },
                            leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                            enabled = !isActionDisabled,
                            onClick = {
                                menuExpanded = false
                                viewModel.clearActionError()
                                showEditSheet = true
                            },
                        )
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                            leadingIcon = {
                                Icon(Icons.Filled.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
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
                TaskDetailHeaderCard(
                    job = detailJob,
                    runningElapsed = state.runningElapsed,
                    isBusy = isActionDisabled,
                    canSeeFullOutput = state.latestRun != null,
                    showsRunNow = features.offersRunNow(detailJob),
                    runNowState = state.runNowState,
                    runNow = ::requestRunNow,
                    togglePauseResume = ::togglePauseResume,
                    seeFullOutput = { state.latestRun?.let(::openRun) },
                )

                actionStatusSection(state.isMutating, state.actionErrorMessage)

                promptCard(detailJob)

                latestOutputCard(detailJob, state)

                configurationCard(detailJob)

                if (!state.isHistoryUnavailable) {
                    TaskRunHistorySection(
                        runs = state.runs,
                        total = state.runTotal,
                        isLoading = state.isLoadingHistory,
                        isLoadingMore = state.isLoadingMoreRuns,
                        canLoadMore = state.canLoadMoreRuns,
                        remainingCount = state.remainingRunCount,
                        errorMessage = state.historyErrorMessage,
                        outcome = viewModel::outcome,
                        selectRun = ::openRun,
                        retry = viewModel::loadHistory,
                        loadMore = viewModel::loadMoreRuns,
                    )
                }
            }
        }
    }

    if (showEditSheet) {
        CronJobEditorSheet(
            title = "Edit Task",
            initialDraft = CronJobEditorDraft(detailJob),
            saveTitle = "Save",
            isSaving = state.isMutating,
            errorMessage = state.actionErrorMessage,
            deliveryOptions = state.deliveryOptions,
            client = client,
            locksProfile = features.isProfileScoped,
            onDismiss = { showEditSheet = false },
            onSave = { draft ->
                val didUpdate = viewModel.update(draft)
                viewModel.lastMutation?.let(onMutation)
                didUpdate
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
                            if (viewModel.delete()) {
                                viewModel.lastMutation?.let(onMutation)
                                onDeleted()
                            }
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

    if (confirmingRunNow) {
        AlertDialog(
            onDismissRequest = { confirmingRunNow = false },
            title = { Text("Run Now?") },
            text = { Text("This also resumes the Task.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmingRunNow = false
                        startRunNow()
                    },
                ) {
                    Text("Run Now")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmingRunNow = false }) { Text("Cancel") }
            },
        )
    }

    selectedRun?.let { run ->
        TaskRunOutputSheet(
            run = run,
            output = state.runOutput,
            isLoading = state.isLoadingRunOutput,
            errorMessage = state.runOutputErrorMessage,
            retry = { viewModel.loadRunOutput(run) },
            onDismiss = {
                selectedRun = null
                viewModel.clearRunOutput()
            },
        )
    }
}

@Composable
private fun actionStatusSection(isMutating: Boolean, actionErrorMessage: String?) {
    when {
        isMutating -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
            Text(
                "Updating task...",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        actionErrorMessage != null -> Text(
            actionErrorMessage,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun promptCard(job: CronJob) {
    val prompt = job.prompt
    if (!prompt.isNullOrEmpty()) {
        SectionCard(title = "Prompt") {
            SelectionContainer {
                Text(
                    prompt,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/**
 * The last run's text, inline. Shown when the last run failed — a failure is
 * the one case where the output is the point of the screen — and also when
 * this server has no history endpoint.
 */
@Composable
private fun latestOutputCard(job: CronJob, state: TaskDetailUiState) {
    val output = state.outputs.firstOrNull() ?: return
    val content = output.content
    if (content.isNullOrEmpty()) return
    if (!job.hasFailedRun && !state.isHistoryUnavailable) return

    SectionCard(title = "Run Output") {
        SelectionContainer {
            Text(
                content.strippingANSIEscapes(),
                style = CodeTextStyle,
                maxLines = 14,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** Everything the list row deliberately stopped showing. */
@Composable
private fun configurationCard(job: CronJob) {
    val fields = TaskConfigurationField.fields(job)
    SectionCard(title = "Configuration") {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            fields.forEach { field ->
                Column {
                    Row(verticalAlignment = Alignment.Top) {
                        Text(
                            field.title,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(104.dp),
                        )
                        Column(Modifier.weight(1f)) {
                            Text(
                                field.value,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 3,
                            )
                            field.detail?.let { detail ->
                                Text(
                                    detail,
                                    style = CodeTextStyle.copy(fontSize = MaterialTheme.typography.labelSmall.fontSize),
                                    color = MaterialTheme.colorScheme.outline,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
