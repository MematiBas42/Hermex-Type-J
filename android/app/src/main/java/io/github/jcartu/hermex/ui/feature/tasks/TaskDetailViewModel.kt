package io.github.jcartu.hermex.ui.feature.tasks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.jcartu.hermex.model.CronDeliveryOption
import io.github.jcartu.hermex.model.CronJob
import io.github.jcartu.hermex.model.CronJobEditorDraft
import io.github.jcartu.hermex.model.CronOutputItem
import io.github.jcartu.hermex.model.CronRunHistoryItem
import io.github.jcartu.hermex.model.CronRunHistoryResponse
import io.github.jcartu.hermex.net.ApiError
import java.io.InterruptedIOException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Where a Run Now on a Hermes host is (#1041). Port of `TaskDetailViewModel.RunNowState`. */
enum class RunNowState {
    IDLE,
    REQUESTED,
    RUNNING,
}

/** How a run went, as its row says it (#1042). Port of `TaskDetailViewModel.RunOutcome`. */
sealed interface RunOutcome {
    data object Running : RunOutcome
    data object Completed : RunOutcome
    data class Failed(val reason: String?) : RunOutcome
}

/**
 * One run's text, carried with the filename it belongs to so the sheet can
 * refuse to render output that arrived for a different run.
 * Port of `Features/Tasks/TaskDetailViewModel.swift` `CronRunOutput`.
 */
data class CronRunOutput(
    val filename: String,
    val text: String,
)

/**
 * Detail state for a single cron job. Port of
 * `Features/Tasks/TaskDetailViewModel.swift`.
 */
data class TaskDetailUiState(
    val job: CronJob,
    val runningElapsed: Double? = null,
    val outputs: List<CronOutputItem> = emptyList(),
    /** Server-provided deliver targets; `null` keeps the editor's free-text fallback. */
    val deliveryOptions: List<CronDeliveryOption>? = null,
    /** Loaded runs, newest first, accumulated across pages. */
    val runs: List<CronRunHistoryItem> = emptyList(),
    /** The server's full run count, which is what "Load more" counts down. */
    val runTotal: Int? = null,
    val isLoadingHistory: Boolean = false,
    val isLoadingMoreRuns: Boolean = false,
    val canLoadMoreRuns: Boolean = false,
    val remainingRunCount: Int = 0,
    val historyErrorMessage: String? = null,
    /** True once the server has answered 404 for the history endpoint: an older `hermes-webui` that predates it. */
    val isHistoryUnavailable: Boolean = false,
    val runOutput: CronRunOutput? = null,
    val isLoadingRunOutput: Boolean = false,
    val runOutputErrorMessage: String? = null,
    val isLoading: Boolean = false,
    val isMutating: Boolean = false,
    val errorMessage: String? = null,
    val actionErrorMessage: String? = null,
    val runNowState: RunNowState = RunNowState.IDLE,
    /** The run the job's last outcome describes, which the header's "See full output" opens. */
    val latestRun: CronRunHistoryItem? = null,
)

class TaskDetailViewModel(
    private val initialJob: CronJob,
    runningElapsed: Double?,
    private val client: CronDataClient,
    private val sleepMillis: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
) : ViewModel() {

    private val _uiState = MutableStateFlow(TaskDetailUiState(job = initialJob, runningElapsed = runningElapsed))
    val uiState: StateFlow<TaskDetailUiState> = _uiState.asStateFlow()

    // MARK: - Working state (mirrors the Swift observable; surfaced through uiState)

    private var job: CronJob = initialJob
    var runningElapsed: Double? = runningElapsed
        private set

    private var outputs: List<CronOutputItem> = emptyList()
    private var deliveryOptions: List<CronDeliveryOption>? = null
    private var isLoading = false
    private var isMutating = false
    private var errorMessage: String? = null
    var actionErrorMessage: String? = null
        private set
    var lastMutation: CronJobListMutation? = null
        private set

    // MARK: - Run history

    private var runs: List<CronRunHistoryItem> = emptyList()
    private var runTotal: Int? = null
    private var isLoadingHistory = false
    private var isLoadingMoreRuns = false
    private var historyErrorMessage: String? = null
    private var isHistoryUnavailable = false

    private var runOutput: CronRunOutput? = null
    private var isLoadingRunOutput = false
    private var runOutputErrorMessage: String? = null

    /** Runs are requested 50 at a time — webui's own default page size. */
    internal val historyPageSize = 50

    /** How far the next page starts. Advances by [historyPageSize], never by the number of rows a page returned. */
    private var historyOffset = 0

    /**
     * Bumped by every first-page load, so `runs` and `historyOffset` always
     * describe the same list. A page fetched before a refresh belongs to a
     * list that no longer exists.
     */
    private var historyGeneration = 0

    /** The job's `last_run_at` as the runs on screen were requested. See [latestRun]. */
    private var lastRunWhenRunsRead: Instant? = null
    private var runOutputToken = 0

    /** Bumped by every change the server accepts, so a list read sent before one can't put back the state it replaced. */
    private var mutationCount = 0

    // MARK: - Run Now on a Hermes host

    /** True from a Hermes host's Run Now until the host's outcome (#1041). */
    private var isRunNowPending = false

    /** How a pending Run Now reads the list. The Tasks list passes one that also shows each read on its rows. */
    var readList: (suspend () -> CronJobList)? = null

    /** Failed list reads in a row that end a pending Run Now: about 15 s of a host the phone can't reach. */
    internal val runNowReadAttempts = 3

    internal val runNowReadIntervalMillis = 5_000L

    private val features: CronFeatures
        get() = client.cronFeatures

    init {
        load()
    }

    // MARK: - Load

    /**
     * Port of the Swift `load()`: delivery options (tolerated failure), the
     * first page of run history, and — on webui, whose runs are output files —
     * the latest output. A Hermes host's latest output comes from the runs.
     */
    fun load() {
        viewModelScope.launch {
            val jobId = job.jobId
            if (jobId == null) {
                setError("Missing job identifier.")
                return@launch
            }

            isLoading = true
            errorMessage = null
            lastMutation = null
            publish()
            try {
                // Optional endpoints: failure must not break the detail view.
                val profile = job.profile
                val runsAreSessions = features.runsAreSessions
                val options = try {
                    client.cronDeliveryOptions(profile)?.platforms
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Exception) {
                    null
                }
                val generation = beginHistoryReload()

                // A Hermes host's job carries its running state and last outcome.
                if (runsAreSessions) {
                    reloadJob(jobId)
                }
                val lastRun = job.lastRunAt?.date
                val history = fetchHistory(client, jobId, profile, offset = 0)

                if (!runsAreSessions) {
                    try {
                        outputs = client.cronOutput(jobId, limit = 5)?.outputs ?: emptyList()
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (error: Exception) {
                        setError(error.message ?: "Could not load output.")
                    }
                }

                deliveryOptions = options
                applyFirstHistoryPage(history, generation = generation, lastRun = lastRun)
                loadLatestReply(jobId, generation = generation)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } finally {
                isLoading = false
                publish()
            }
        }
    }

    private fun setError(message: String) {
        errorMessage = message
        publish()
    }

    /**
     * Reads the job again from the list, the only read that carries a Hermes
     * host's running state (#1040), and hands it to the list too.
     */
    private suspend fun reloadJob(jobId: String) {
        val mutationsBefore = mutationCount
        try {
            val list = client.cronJobs()
            if (mutationCount != mutationsBefore) return
            val fresh = list.jobs.firstOrNull { it.jobId == jobId } ?: return
            job = fresh
            runningElapsed = list.runningJobs[jobId]
            lastMutation = CronJobListMutation.Upsert(fresh)
            publish()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            setError(error.message ?: "Could not load task.")
        }
    }

    /** Loads the first page of run history, replacing what is on screen. */
    fun loadHistory() {
        viewModelScope.launch {
            val jobId = job.jobId ?: return@launch

            val generation = beginHistoryReload()
            val lastRun = job.lastRunAt?.date
            applyFirstHistoryPage(
                fetchHistory(client, jobId, job.profile, offset = 0),
                generation = generation,
                lastRun = lastRun,
            )
            loadLatestReply(jobId, generation = generation)
        }
    }

    /**
     * A Hermes host's latest output (#1042): the reply of [latestRun], the run
     * the Task's last outcome describes. It is read only when that run failed.
     * On webui (`runsAreSessions == false`) this is a no-op.
     */
    private suspend fun loadLatestReply(jobId: String, generation: Int) {
        if (!features.runsAreSessions || generation != historyGeneration) return
        val run = latestRun()
        if (!job.hasFailedRun || run == null) {
            outputs = emptyList()
            publish()
            return
        }
        val reply = try {
            client.cronRunDetail(jobId, job.profile, run.filename)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            null
        }
        if (generation != historyGeneration) return
        outputs = listOf(CronOutputItem(filename = run.filename, content = reply?.content))
        publish()
    }

    /** Opens a new history generation and returns it. */
    private fun beginHistoryReload(): Int {
        historyGeneration += 1
        isLoadingHistory = true
        historyErrorMessage = null
        publish()
        return historyGeneration
    }

    /** `true` while the server still holds runs past the ones on screen. */
    private fun canLoadMoreRuns(): Boolean {
        val total = runTotal ?: return false
        if (isHistoryUnavailable) return false
        return historyOffset < total
    }

    /** How many runs "Load more" would still be working through. */
    private fun remainingRunCount(): Int {
        val total = runTotal ?: return 0
        return maxOf(0, total - runs.size)
    }

    /**
     * The run the job's last outcome describes, which the header's "See full
     * output" opens: the newest finished run.
     */
    private fun latestRun(): CronRunHistoryItem? {
        val finished = runs.filter { !it.isRunning }
        if (!features.runsAreSessions) return finished.firstOrNull()
        val lastRun = job.lastRunAt?.date
        if (lastRun != lastRunWhenRunsRead) return null
        return finished.firstOrNull { run ->
            val ended = run.modified
            if (ended == null || lastRun == null) true else ended <= lastRun
        }
    }

    /** How [run] went, or null where nothing says. */
    fun outcome(run: CronRunHistoryItem): RunOutcome? {
        if (run.isRunning) return RunOutcome.Running
        val runsAreSessions = features.runsAreSessions
        if (run.id != latestRun()?.id) return if (runsAreSessions) null else RunOutcome.Completed
        if (job.hasFailedRun) return RunOutcome.Failed(job.failureSummary)
        return if (!runsAreSessions || job.lastStatus == "ok") RunOutcome.Completed else null
    }

    fun loadMoreRuns() {
        viewModelScope.launch {
            val jobId = job.jobId ?: return@launch
            // Nothing is paged while a reload is in flight.
            if (!canLoadMoreRuns() || isLoadingMoreRuns || isLoadingHistory) return@launch

            val offset = historyOffset
            val generation = historyGeneration
            isLoadingMoreRuns = true
            historyErrorMessage = null
            publish()
            try {
                val result = fetchHistory(client, jobId, job.profile, offset)

                // A refresh landed while this page was in flight: drop it.
                if (generation != historyGeneration || offset != historyOffset) return@launch

                // The requested window is consumed whether or not every file in it could be read.
                historyOffset = offset + historyPageSize
                result.total?.let { runTotal = it }
                appendRuns(result.runs)
            } finally {
                isLoadingMoreRuns = false
                publish()
            }
        }
    }

    /** Fetches one run's full text for the output sheet. Only the newest request may write `runOutput`. */
    fun loadRunOutput(run: CronRunHistoryItem) {
        viewModelScope.launch {
            val jobId = job.jobId
            if (jobId == null) {
                runOutputErrorMessage = "Missing job identifier."
                publish()
                return@launch
            }

            runOutputToken += 1
            val token = runOutputToken
            runOutput = null
            runOutputErrorMessage = null
            isLoadingRunOutput = true
            publish()

            try {
                val response = client.cronRunDetail(jobId, job.profile, run.filename)
                if (token != runOutputToken) return@launch
                isLoadingRunOutput = false
                runOutput = CronRunOutput(
                    filename = run.filename,
                    text = (response.content ?: response.snippet ?: "").strippingANSIEscapes(),
                )
                publish()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                if (token != runOutputToken) return@launch
                isLoadingRunOutput = false
                runOutputErrorMessage = error.message ?: "Could not load output."
                publish()
            }
        }
    }

    /** Drops the sheet's output so a reopened sheet never flashes the previous run's text. */
    fun clearRunOutput() {
        runOutputToken += 1
        runOutput = null
        runOutputErrorMessage = null
        isLoadingRunOutput = false
        publish()
    }

    /** Applies a first page, or its failure. A 404 retires the section for this server. */
    private fun applyFirstHistoryPage(
        result: Result<CronRunHistoryResponse>,
        generation: Int,
        lastRun: Instant?,
    ) {
        if (generation != historyGeneration) return
        isLoadingHistory = false

        result.fold(
            onSuccess = { response ->
                runs = response.runs
                lastRunWhenRunsRead = lastRun
                runTotal = response.total
                historyOffset = historyPageSize
                isHistoryUnavailable = false
                publish()
            },
            onFailure = { error ->
                if (isMissingEndpoint(error)) {
                    isHistoryUnavailable = true
                    runs = emptyList()
                    runTotal = null
                    historyOffset = 0
                } else {
                    historyErrorMessage = error.message ?: "Could not load run history."
                }
                publish()
            },
        )
    }

    /** Appends a page, skipping runs already on screen. */
    private fun appendRuns(page: List<CronRunHistoryItem>) {
        val seen = runs.mapTo(mutableSetOf()) { it.filename }
        val updated = runs.toMutableList()
        for (run in page) {
            if (seen.add(run.filename)) updated.add(run)
        }
        runs = updated
    }

    private suspend fun fetchHistory(
        client: CronDataClient,
        jobId: String,
        profile: String?,
        offset: Int,
    ): Result<CronRunHistoryResponse> = try {
        Result.success(client.cronHistory(jobId, profile, offset, historyPageSize))
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Exception) {
        Result.failure(error)
    }

    /** A 404 means this server has no history endpoint, not that the request was wrong. */
    private fun isMissingEndpoint(error: Throwable): Boolean =
        error is ApiError.Http && error.statusCode == 404

    fun clearActionError() {
        actionErrorMessage = null
        publish()
    }

    // MARK: - Run Now

    /** Port of the Swift `runNowState` computed property. */
    private fun runNowState(): RunNowState {
        if (!isRunNowPending) return RunNowState.IDLE
        return if (runningElapsed == null) RunNowState.REQUESTED else RunNowState.RUNNING
    }

    /**
     * Runs the Task now. A webui server starts the run and answers at once, so
     * the Task shows as running from the answer; a Hermes host answers once the
     * run has finished ([followRunNow]). Returns true for the server's outcome.
     */
    suspend fun runNow(): Boolean {
        if (!features.runNowWaitsForRun) {
            val success = mutateJob { jobId ->
                client.runCron(jobId, job.profile)
            }
            if (success) {
                runningElapsed = 0.0
                publish()
            }
            return success
        }
        if (!followRunNow()) return false
        if (historyGeneration > 0) {
            loadHistory()
        }
        return true
    }

    /**
     * A Hermes host's Run Now (#1041). The trigger goes out on its own task,
     * which nothing here cancels, and the list is read every
     * [runNowReadIntervalMillis] until the host gives its outcome: the
     * trigger's reply, or a read showing the run finished.
     */
    private suspend fun followRunNow(): Boolean {
        val jobId = job.jobId
        if (jobId == null) {
            actionErrorMessage = "Missing job identifier."
            publish()
            return false
        }
        if (isRunNowPending) return false

        isRunNowPending = true
        actionErrorMessage = null
        lastMutation = null
        publish()
        try {
            // A finished run stamps `last_run_at`. Comparing the host's own stamps
            // keeps the phone's clock out of it.
            val lastRun = job.lastRunAt?.date
            val events = Channel<RunNowEvent>(Channel.UNLIMITED)

            val trigger = viewModelScope.launch {
                val profile = job.profile
                try {
                    events.send(RunNowEvent.Replied(Result.success(client.runCron(jobId, profile))))
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (error: Exception) {
                    events.send(RunNowEvent.Replied(Result.failure(error)))
                }
            }
            var timer = startRunNowTimer(events)
            var failedReads = 0
            try {
                for (event in events) {
                    var refusal: Exception? = null
                    when (event) {
                        is RunNowEvent.Replied -> {
                            event.result.fold(
                                onSuccess = { response ->
                                    showRunNowReply(response, jobId)
                                    return true
                                },
                                onFailure = { error ->
                                    when {
                                        isUnansweredTrigger(error) -> Unit // the reads go on
                                        error is HermesCronRefusal -> {
                                            refusal = error
                                            timer.cancel()
                                        }
                                        else -> return failRunNow(error)
                                    }
                                },
                            )
                        }
                        RunNowEvent.Tick -> Unit
                    }

                    val reading: RunNowReading = try {
                        readRunNow(jobId, since = lastRun).also { failedReads = 0 }
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (error: Exception) {
                        failedReads += 1
                        if (refusal == null && failedReads < runNowReadAttempts) {
                            timer = startRunNowTimer(events)
                            continue
                        }
                        return failRunNow(refusal ?: error)
                    }
                    if (reading == RunNowReading.FINISHED) return true
                    if (refusal != null && reading != RunNowReading.RUNNING) return failRunNow(refusal)
                    timer = startRunNowTimer(events)
                }
                return false
            } finally {
                timer.cancel()
                trigger.cancel()
                events.close()
            }
        } finally {
            isRunNowPending = false
            publish()
        }
    }

    /** Ends a pending Run Now with [error], which the screen shows. */
    private fun failRunNow(error: Exception): Boolean {
        actionErrorMessage = error.message ?: "Could not run task."
        publish()
        return false
    }

    private sealed interface RunNowEvent {
        data class Replied(val result: Result<io.github.jcartu.hermex.model.CronMutationResponse>) : RunNowEvent
        data object Tick : RunNowEvent
    }

    /** What one list read says about a pending Run Now. */
    private enum class RunNowReading {
        NOT_YET,
        RUNNING,
        FINISHED,
    }

    /** The tick for a pending Run Now's next read. */
    private fun startRunNowTimer(events: Channel<RunNowEvent>): Job = viewModelScope.launch {
        try {
            sleepMillis(runNowReadIntervalMillis)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            return@launch
        }
        if (!kotlinx.coroutines.isActive) return@launch
        events.send(RunNowEvent.Tick)
    }

    /** Reads the list for a pending Run Now and shows the Task as it reads. */
    private suspend fun readRunNow(jobId: String, since: Instant?): RunNowReading {
        val mutationsBefore = mutationCount
        val read = readList ?: { client.cronJobs() }
        val list = read()
        if (mutationCount != mutationsBefore) return RunNowReading.NOT_YET
        val fresh = list.jobs.firstOrNull { it.jobId == jobId } ?: return RunNowReading.NOT_YET
        job = fresh
        runningElapsed = list.runningJobs[jobId]
        lastMutation = CronJobListMutation.Upsert(fresh)
        publish()
        if (runningElapsed != null) return RunNowReading.RUNNING
        val ran = fresh.lastRunAt?.date
        if (ran != null && ran > (since ?: Instant.MIN)) return RunNowReading.FINISHED
        return RunNowReading.NOT_YET
    }

    /** Shows the trigger's reply: the job as the run left it, running only if the host says so. */
    private fun showRunNowReply(response: io.github.jcartu.hermex.model.CronMutationResponse, jobId: String) {
        mutationCount += 1
        val finished = response.job ?: return
        job = finished
        runningElapsed = CronJobList.hermesJobs(listOf(finished)).runningJobs[jobId]
        lastMutation = CronJobListMutation.Upsert(finished)
        publish()
    }

    /**
     * A failed trigger that a hop gave up on while the host goes on with the
     * run: a gateway timeout (504) or Cloudflare's (524), the request timing
     * out, or the connection dropping.
     */
    private fun isUnansweredTrigger(error: Throwable): Boolean {
        if (error is ApiError.Http && (error.statusCode == 504 || error.statusCode == 524)) return true
        if (error is ApiError.Network) {
            val underlying = error.underlying
            return underlying is SocketTimeoutException ||
                underlying is SocketException ||
                underlying is InterruptedIOException
        }
        return false
    }

    // MARK: - Mutations

    suspend fun pause(reason: String? = null): Boolean {
        val success = mutateJob { jobId ->
            client.pauseCron(jobId, job.profile, reason)
        }
        if (success) {
            runningElapsed = null
            publish()
        }
        return success
    }

    suspend fun resume(): Boolean = mutateJob { jobId ->
        client.resumeCron(jobId, job.profile)
    }

    suspend fun update(draft: CronJobEditorDraft): Boolean {
        draft.validationMessage?.let { message ->
            actionErrorMessage = message
            publish()
            return false
        }

        // A Task that lives in a Profile stays in it: the host can't move a job (#1040).
        val profile = if (features.isProfileScoped) job.profile else draft.profile.trim()
        return mutateJob { jobId ->
            client.updateCron(
                jobId = jobId,
                prompt = draft.trimmedPrompt,
                schedule = draft.trimmedSchedule,
                name = draft.name.trim(),
                deliver = draft.deliver.trim(),
                skills = draft.skills,
                model = draft.model.trim(),
                provider = draft.provider.trim(),
                profile = profile,
                toastNotifications = draft.toastNotifications,
            )
        }
    }

    suspend fun delete(): Boolean {
        val jobId = job.jobId
        if (jobId == null) {
            actionErrorMessage = "Missing job identifier."
            publish()
            return false
        }

        isMutating = true
        actionErrorMessage = null
        lastMutation = null
        publish()
        try {
            val response = client.deleteCron(jobId, job.profile)
            if (response.ok == false) {
                actionErrorMessage = response.error ?: "Could not delete task."
                publish()
                return false
            }

            mutationCount += 1
            lastMutation = CronJobListMutation.Delete(jobId)
            return true
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            actionErrorMessage = error.message ?: "Could not delete task."
            publish()
            return false
        } finally {
            isMutating = false
            publish()
        }
    }

    private suspend fun mutateJob(
        action: suspend (String) -> io.github.jcartu.hermex.model.CronMutationResponse,
    ): Boolean {
        val jobId = job.jobId
        if (jobId == null) {
            actionErrorMessage = "Missing job identifier."
            publish()
            return false
        }

        isMutating = true
        actionErrorMessage = null
        lastMutation = null
        publish()
        try {
            val response = action(jobId)
            if (response.ok == false) {
                actionErrorMessage = response.error ?: "Could not update task."
                publish()
                return false
            }

            mutationCount += 1
            val updatedJob = response.job
            if (updatedJob != null) {
                job = updatedJob
                lastMutation = CronJobListMutation.Upsert(updatedJob)
                publish()
            }
            return true
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            actionErrorMessage = error.message ?: "Could not update task."
            publish()
            return false
        } finally {
            isMutating = false
            publish()
        }
    }

    // MARK: - Publishing

    private fun publish() {
        _uiState.value = TaskDetailUiState(
            job = job,
            runningElapsed = runningElapsed,
            outputs = outputs,
            deliveryOptions = deliveryOptions,
            runs = runs,
            runTotal = runTotal,
            isLoadingHistory = isLoadingHistory,
            isLoadingMoreRuns = isLoadingMoreRuns,
            canLoadMoreRuns = canLoadMoreRuns(),
            remainingRunCount = remainingRunCount(),
            historyErrorMessage = historyErrorMessage,
            isHistoryUnavailable = isHistoryUnavailable,
            runOutput = runOutput,
            isLoadingRunOutput = isLoadingRunOutput,
            runOutputErrorMessage = runOutputErrorMessage,
            isLoading = isLoading,
            isMutating = isMutating,
            errorMessage = errorMessage,
            actionErrorMessage = actionErrorMessage,
            runNowState = runNowState(),
            latestRun = latestRun(),
        )
    }
}
