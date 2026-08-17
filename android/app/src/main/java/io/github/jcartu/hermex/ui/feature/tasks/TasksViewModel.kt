package io.github.jcartu.hermex.ui.feature.tasks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.jcartu.hermex.model.CronDeliveryOption
import io.github.jcartu.hermex.model.CronJob
import io.github.jcartu.hermex.model.CronJobEditorDraft
import io.github.jcartu.hermex.net.HermesApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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
 * State for the scheduled-tasks list. Port of `Features/Tasks/TasksViewModel.swift`.
 *
 * The Swift app refreshes on appear and via manual refresh only — there is no
 * polling timer in the iOS ViewModel, so none here either; [TasksViewModel.load]
 * re-fetches the job list, the live running status, and the delivery options.
 */
data class TasksUiState(
    val jobs: List<CronJob> = emptyList(),
    /** jobId → elapsed seconds, from `GET /api/crons/status`. */
    val runningJobs: Map<String, Double> = emptyMap(),
    /**
     * Server-provided deliver targets; `null` while unknown or when the
     * endpoint is unavailable (the editor then falls back to free text).
     */
    val deliveryOptions: List<CronDeliveryOption>? = null,
    val isLoading: Boolean = false,
    val isMutating: Boolean = false,
    val errorMessage: String? = null,
    val actionErrorMessage: String? = null,
    val searchText: String = "",
) {
    val activeRunningCount: Int
        get() = runningJobs.size

    fun runningElapsed(job: CronJob): Double? = job.jobId?.let(runningJobs::get)

    /** Jobs matching [searchText]; a blank query returns every job. */
    val filteredJobs: List<CronJob>
        get() = TasksViewModel.filterJobs(jobs, searchText)
}

class TasksViewModel(private val api: HermesApi) : ViewModel() {

    private val _uiState = MutableStateFlow(TasksUiState())
    val uiState: StateFlow<TasksUiState> = _uiState.asStateFlow()

    init {
        load()
    }

    /**
     * Fetches the job list, running status, and delivery options in parallel,
     * mirroring the Swift `async let` triple. Failure of jobs/status fails the
     * load; delivery-options failure is tolerated (editor falls back to free
     * text), exactly like the Swift `try?`.
     */
    fun load() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            try {
                coroutineScope {
                    val jobsDeferred = async { api.crons() }
                    val statusDeferred = async { api.cronStatus() }
                    val optionsDeferred = async {
                        try {
                            api.cronDeliveryOptions()
                        } catch (cancellation: CancellationException) {
                            throw cancellation
                        } catch (_: Exception) {
                            null
                        }
                    }
                    val running = statusDeferred.await().runningJobs ?: emptyMap()
                    val jobs = sortJobs(jobsDeferred.await().jobs ?: emptyList(), running)
                    val options = optionsDeferred.await()?.platforms
                    _uiState.update {
                        it.copy(
                            jobs = jobs,
                            runningJobs = running,
                            deliveryOptions = options,
                            isLoading = false,
                        )
                    }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = error.message ?: "Could not load tasks.",
                    )
                }
            }
        }
    }

    fun setSearchText(text: String) {
        _uiState.update { it.copy(searchText = text) }
    }

    fun clearActionError() {
        _uiState.update { it.copy(actionErrorMessage = null) }
    }

    /**
     * Creates a job from the editor draft. Port of Swift `create(from:)`:
     * draft validation and server `ok == false` surface via
     * [TasksUiState.actionErrorMessage]; a returned job is upserted, otherwise
     * the whole list reloads. Returns true when the editor should dismiss.
     */
    suspend fun create(draft: CronJobEditorDraft): Boolean {
        draft.validationMessage?.let { message ->
            _uiState.update { it.copy(actionErrorMessage = message) }
            return false
        }

        _uiState.update { it.copy(isMutating = true, actionErrorMessage = null) }
        return try {
            val response = api.createCron(
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
                    it.copy(
                        isMutating = false,
                        actionErrorMessage = response.error ?: "Could not create task.",
                    )
                }
                return false
            }

            _uiState.update { it.copy(isMutating = false) }
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
                it.copy(
                    isMutating = false,
                    actionErrorMessage = error.message ?: "Could not create task.",
                )
            }
            false
        }
    }

    /** Port of Swift `apply(_:)` — upserts or removes a job and re-sorts. */
    fun apply(mutation: CronJobListMutation) {
        when (mutation) {
            is CronJobListMutation.Upsert -> _uiState.update { state ->
                state.copy(jobs = upsert(state.jobs, mutation.job, state.runningJobs))
            }
            is CronJobListMutation.Delete -> _uiState.update { state ->
                state.copy(
                    jobs = state.jobs.filterNot { it.jobId == mutation.jobId },
                    runningJobs = state.runningJobs - mutation.jobId,
                )
            }
        }
    }

    /** Marks a job as running with zero elapsed (after Run Now), like Swift's `runningElapsed = 0`. */
    fun markRunning(jobId: String) {
        _uiState.update { state ->
            val running = state.runningJobs + (jobId to 0.0)
            state.copy(runningJobs = running, jobs = sortJobs(state.jobs, running))
        }
    }

    /** Clears a job's running marker (after Pause), like Swift's `runningElapsed = nil`. */
    fun markStopped(jobId: String) {
        _uiState.update { state ->
            val running = state.runningJobs - jobId
            state.copy(runningJobs = running, jobs = sortJobs(state.jobs, running))
        }
    }

    private fun upsert(
        jobs: List<CronJob>,
        job: CronJob,
        runningJobs: Map<String, Double>,
    ): List<CronJob> {
        val matchingIndex = when {
            job.jobId != null -> jobs.indexOfFirst { it.jobId == job.jobId }
            job.name != null -> jobs.indexOfFirst { it.jobId == null && it.name == job.name }
            else -> -1
        }
        val updated = if (matchingIndex >= 0) {
            jobs.toMutableList().also { it[matchingIndex] = job }
        } else {
            jobs + job
        }
        return sortJobs(updated, runningJobs)
    }

    companion object {
        /**
         * Port of Swift `sortJobs`: running jobs first, then by next run date
         * ascending (jobs with a date before jobs without), then by display
         * name, case-insensitively.
         */
        fun sortJobs(jobs: List<CronJob>, runningJobs: Map<String, Double>): List<CronJob> =
            jobs.sortedWith { left, right ->
                val leftRunning = left.jobId?.let(runningJobs::containsKey) == true
                val rightRunning = right.jobId?.let(runningJobs::containsKey) == true
                if (leftRunning != rightRunning) {
                    return@sortedWith if (leftRunning) -1 else 1
                }

                val leftDate = left.nextRunAt?.date
                val rightDate = right.nextRunAt?.date
                when {
                    leftDate != null && rightDate != null -> leftDate.compareTo(rightDate)
                    leftDate != null -> -1
                    rightDate != null -> 1
                    else -> left.displayName.compareTo(right.displayName, ignoreCase = true)
                }
            }

        /** Case-insensitive match against name, prompt, and schedule text. */
        fun filterJobs(jobs: List<CronJob>, searchText: String): List<CronJob> {
            val query = searchText.trim()
            if (query.isEmpty()) return jobs
            return jobs.filter { job ->
                job.displayName.contains(query, ignoreCase = true) ||
                    job.prompt?.contains(query, ignoreCase = true) == true ||
                    job.scheduleText?.contains(query, ignoreCase = true) == true
            }
        }
    }
}
