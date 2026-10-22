package io.github.jcartu.hermex.ui.feature.tasks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.jcartu.hermex.model.CronDeliveryOption
import io.github.jcartu.hermex.model.CronJob
import io.github.jcartu.hermex.model.CronJobEditorDraft
import io.github.jcartu.hermex.model.CronRecentCompletion
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Port of Swift `CronJobListMutation`. */
sealed interface CronJobListMutation {
    data class Upsert(val job: CronJob) : CronJobListMutation
    data class Delete(val jobId: String) : CronJobListMutation
}

/**
 * State for the scheduled-tasks agenda. Port of
 * `Features/Tasks/TasksViewModel.swift`: the list, the running status, the
 * delivery targets, the recent-runs feed, the filter, and the pending row
 * actions. Nothing is applied optimistically — the server's response is what
 * moves a row.
 */
data class TasksUiState(
    val jobs: List<CronJob> = emptyList(),
    /** jobId → elapsed seconds, from the list read. */
    val runningJobs: Map<String, Double> = emptyMap(),
    /** Server-provided deliver targets; `null` keeps the editor's free-text fallback. */
    val deliveryOptions: List<CronDeliveryOption>? = null,
    /**
     * Newest-first recent completions across every job. Empty until the feed
     * answers, and stays empty on any feed failure; never gates `isLoading`.
     */
    val recentRuns: List<CronRecentCompletion> = emptyList(),
    /** The host's scheduler has stopped ticking past three missed ticks (#1040); null otherwise. */
    val schedulerStallAge: Double? = null,
    val isLoading: Boolean = false,
    val isMutating: Boolean = false,
    val errorMessage: String? = null,
    val actionErrorMessage: String? = null,
    /** What the server said about a Task it saved anyway (#1040). */
    val saveWarning: String? = null,
    /** Job ids with a row action in flight, so a row cannot be double-fired. */
    val pendingActionJobIds: Set<String> = emptySet(),
    /** Selected segment. View state only: never persisted, never carried between servers. */
    val filter: TaskFilter = TaskFilter.ALL,
) {
    fun runningElapsed(job: CronJob): Double? = job.jobId?.let(runningJobs::get)
}

class TasksViewModel(
    private val client: CronDataClient,
    private val sleepMillis: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
) : ViewModel() {

    private val _uiState = MutableStateFlow(TasksUiState())
    val uiState: StateFlow<TasksUiState> = _uiState.asStateFlow()

    /** The in-flight feed request, so a reload cancels the one before it. */
    private var recentRunsTask: Job? = null

    /** Bumped by every list shown and every change applied, so a row's Run Now read sent before one can't put back what it replaced (#1041). */
    private var listChanges = 0

    private val features: CronFeatures
        get() = client.cronFeatures

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _uiState.update {
                it.copy(isLoading = true, errorMessage = null, actionErrorMessage = null, saveWarning = null)
            }
            try {
                if (features.hasRecentRunsFeed) {
                    refreshRecentRuns()
                }

                val list = client.cronJobs()
                val options = if (features.isProfileScoped) null else try {
                    client.cronDeliveryOptions(null)?.platforms
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Exception) {
                    null
                }

                show(list)
                _uiState.update { it.copy(deliveryOptions = options, isLoading = false) }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                _uiState.update {
                    it.copy(isLoading = false, errorMessage = error.message ?: "Could not load tasks.")
                }
            }
        }
    }

    /** Shows one read of the list: a load's, or one a row's Run Now made while it followed the run (#1041). */
    private fun show(list: CronJobList) {
        listChanges += 1
        _uiState.update { state ->
            state.copy(
                runningJobs = list.runningJobs,
                jobs = list.jobs,
                recentRuns = list.recentRuns?.let(CronRecentCompletion::newestFirst) ?: state.recentRuns,
                schedulerStallAge = schedulerStallAge(list.jobs),
            )
        }
    }

    /**
     * The longest any enabled Task's Profile has gone without a scheduler tick,
     * when that is past three missed 60 s ticks: a Hermes host whose scheduler
     * has stopped (#1040). Null while every scheduler is ticking, or none reports.
     */
    private fun schedulerStallAge(jobs: List<CronJob>): Double? =
        jobs.filter { it.enabled != false }
            .mapNotNull { it.schedulerHeartbeatAge }
            .filter { it > 180 }
            .maxOrNull()

    /**
     * Fetches the recent-runs feed on its own job so a slow or missing endpoint
     * never holds the task list in its loading state. A reload cancels the
     * previous fetch, and a failed refresh keeps the last good feed.
     */
    private fun refreshRecentRuns() {
        recentRunsTask?.cancel()
        recentRunsTask = viewModelScope.launch {
            val response = try {
                client.cronRecent()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                return@launch
            }
            _uiState.update {
                it.copy(recentRuns = CronRecentCompletion.newestFirst(response.completions ?: emptyList()))
            }
        }
    }

    fun clearSaveWarning() {
        _uiState.update { it.copy(saveWarning = null) }
    }

    /** The loaded job a recent run belongs to, or null when the feed names a job the list does not have. */
    fun job(forCompletion completion: CronRecentCompletion): CronJob? {
        val jobId = completion.jobId
        if (jobId != null) {
            jobs.firstOrNull { it.jobId == jobId }?.let { return it }
        }
        val name = completion.name
        if (name.isNullOrEmpty()) return null
        return jobs.firstOrNull { it.name == name }
    }

    fun runningElapsed(forJob job: CronJob): Double? {
        val jobId = job.jobId ?: return null
        return runningJobs[jobId]
    }

    private val jobs: List<CronJob>
        get() = _uiState.value.jobs

    private val runningJobs: Map<String, Double>
        get() = _uiState.value.runningJobs

    fun setFilter(filter: TaskFilter) {
        _uiState.update { it.copy(filter = filter) }
    }

    /** The filtered, grouped agenda. [now] and [zone] are injected so tests can pin the clock. */
    fun sections(
        now: Instant = Instant.now(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<TaskAgendaSection> = TaskAgenda.sections(
        jobs = jobs,
        runningJobIds = runningJobs.keys,
        filter = _uiState.value.filter,
        now = now,
        zone = zone,
    )

    fun count(forFilter filter: TaskFilter): Int =
        TaskAgenda.count(filter, jobs, runningJobs.keys)

    fun isPendingAction(job: CronJob): Boolean {
        val jobId = job.jobId ?: return false
        return jobId in _uiState.value.pendingActionJobIds
    }

    fun clearActionError() {
        _uiState.update { it.copy(actionErrorMessage = null) }
    }

    /**
     * Creates a job from the editor draft. Port of Swift `create(from:)`:
     * draft validation and server `ok == false` surface via `actionErrorMessage`;
     * a returned job is upserted, otherwise the whole list reloads.
     * Returns true when the editor should dismiss.
     */
    suspend fun create(draft: CronJobEditorDraft): Boolean {
        draft.validationMessage?.let { message ->
            _uiState.update { it.copy(actionErrorMessage = message) }
            return false
        }

        _uiState.update { it.copy(isMutating = true, actionErrorMessage = null) }
        return try {
            val response = client.createCron(
                prompt = draft.trimmedPrompt,
                schedule = draft.trimmedSchedule,
                name = draft.trimmedName,
                deliver = draft.trimmedDeliver,
                skills = draft.skills,
                model = draft.trimmedModel,
                provider = draft.trimmedProvider,
                profile = draft.trimmedProfile,
                toastNotifications = draft.toastNotifications,
            )
            if (response.ok == false) {
                _uiState.update {
                    it.copy(isMutating = false, actionErrorMessage = response.error ?: "Could not create task.")
                }
                return false
            }

            _uiState.update { it.copy(isMutating = false, saveWarning = response.warning) }
            val job = response.job
            if (job != null) {
                apply(CronJobListMutation.Upsert(job))
            } else {
                load()
            }
            true
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            _uiState.update {
                it.copy(isMutating = false, actionErrorMessage = error.message ?: "Could not create task.")
            }
            false
        }
    }

    /** Port of Swift `apply(_:)` — upserts or removes a job. */
    fun apply(mutation: CronJobListMutation) {
        listChanges += 1
        when (mutation) {
            is CronJobListMutation.Upsert -> upsert(mutation.job)
            is CronJobListMutation.Delete -> _uiState.update { state ->
                state.copy(
                    jobs = state.jobs.filterNot { it.jobId == mutation.jobId },
                    runningJobs = state.runningJobs - mutation.jobId,
                    schedulerStallAge = schedulerStallAge(state.jobs.filterNot { it.jobId == mutation.jobId }),
                )
            }
        }
    }

    // MARK: - Row actions
    //
    // Row actions delegate to `TaskDetailViewModel` so the list and the detail
    // screen can never drift apart on what a mutation means. Nothing is applied
    // optimistically: the server's response is what moves the row.

    /**
     * Runs [job] now. A Hermes host's Run Now follows the run on the list until
     * the host's outcome (#1041), and the rows show each list it reads, unless
     * the list changed while that read was out.
     */
    suspend fun runNow(job: CronJob) {
        var elapsed: Double? = null
        val jobId = perform(job) { detail ->
            detail.readList = {
                val changesBefore = listChanges
                val list = client.cronJobs()
                if (listChanges == changesBefore) show(list)
                list
            }
            val didRun = detail.runNow()
            elapsed = detail.runningElapsed
            didRun
        } ?: return
        // webui accepted the run, so the job belongs in "Now" until the next
        // status poll replaces this with a real elapsed time.
        _uiState.update { it.copy(runningJobs = it.runningJobs + (jobId to elapsed)) }
    }

    suspend fun pause(job: CronJob) {
        val jobId = perform(job) { it.pause() } ?: return
        _uiState.update { it.copy(runningJobs = it.runningJobs - jobId) }
    }

    suspend fun resume(job: CronJob) {
        perform(job) { it.resume() }
    }

    suspend fun delete(job: CronJob) {
        perform(job) { it.delete() }
    }

    /**
     * Runs one Task Detail mutation for [job] and folds the result back into
     * the list. Returns the job's id on success, null on failure or when the
     * job has no id to mutate.
     */
    private suspend fun perform(
        job: CronJob,
        action: suspend (TaskDetailViewModel) -> Boolean,
    ): String? {
        val jobId = job.jobId ?: return null
        if (isPendingAction(job)) return null

        _uiState.update { state ->
            state.copy(
                pendingActionJobIds = state.pendingActionJobIds + jobId,
                actionErrorMessage = null,
            )
        }
        try {
            val detail = TaskDetailViewModel(
                job = job,
                runningElapsed = runningElapsed(job),
                client = client,
                sleepMillis = sleepMillis,
            )
            val succeeded = action(detail)
            if (!succeeded) {
                // A Run Now the screen stopped following has not failed.
                if (kotlinx.coroutines.currentCoroutineContext().isActive) {
                    _uiState.update { state ->
                        state.copy(actionErrorMessage = detail.actionErrorMessage ?: "Could not update task.")
                    }
                }
                return null
            }
            detail.lastMutation?.let(::apply)
            return jobId
        } finally {
            _uiState.update { it.copy(pendingActionJobIds = it.pendingActionJobIds - jobId) }
        }
    }

    private fun upsert(job: CronJob) {
        _uiState.update { state ->
            val matchingIndex = when {
                job.jobId != null -> state.jobs.indexOfFirst { it.jobId == job.jobId }
                job.name != null -> state.jobs.indexOfFirst { it.jobId == null && it.name == job.name }
                else -> -1
            }
            val updated = if (matchingIndex >= 0) {
                state.jobs.toMutableList().also { it[matchingIndex] = job }
            } else {
                state.jobs + job
            }
            state.copy(jobs = updated, schedulerStallAge = schedulerStallAge(updated))
        }
    }
}
