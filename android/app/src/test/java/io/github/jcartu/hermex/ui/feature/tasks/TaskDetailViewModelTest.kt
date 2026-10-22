package io.github.jcartu.hermex.ui.feature.tasks

import io.github.jcartu.hermex.model.CronDateValue
import io.github.jcartu.hermex.model.CronJob
import io.github.jcartu.hermex.model.CronRunHistoryItem
import io.github.jcartu.hermex.model.CronRunHistoryResponse
import io.github.jcartu.hermex.net.ApiError
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A scripted CronDataClient: a mutable jobs list plus per-call suspend lambdas.
 */
private class FakeCronClient(
    override val cronFeatures: CronFeatures = CronFeatures.webui,
    var jobs: List<CronJob> = emptyList(),
    var running: Map<String, Double> = emptyMap(),
    var history: (offset: Int) -> CronRunHistoryResponse = { CronRunHistoryResponse() },
    var runReply: (jobId: String) -> io.github.jcartu.hermex.model.CronMutationResponse = {
        io.github.jcartu.hermex.model.CronMutationResponse(ok = true)
    },
    var cronJobsError: Exception? = null,
) : CronDataClient {

    var runCalls = mutableListOf<String>()

    override suspend fun cronJobs(): CronJobList {
        cronJobsError?.let { throw it }
        return CronJobList(jobs = jobs, runningJobs = running, recentRuns = null)
    }

    override suspend fun cronRecent() =
        io.github.jcartu.hermex.model.CronRecentCompletionsResponse(completions = emptyList())

    override suspend fun cronDeliveryOptions(profile: String?) =
        io.github.jcartu.hermex.model.CronDeliveryOptionsResponse(platforms = emptyList())

    override suspend fun createCron(
        prompt: String, schedule: String, name: String?, deliver: String?, skills: List<String>,
        model: String?, provider: String?, profile: String?, toastNotifications: Boolean,
    ) = io.github.jcartu.hermex.model.CronMutationResponse(ok = true)

    override suspend fun updateCron(
        jobId: String, prompt: String?, schedule: String?, name: String?, deliver: String?,
        skills: List<String>?, model: String?, provider: String?, profile: String?, toastNotifications: Boolean?,
    ) = io.github.jcartu.hermex.model.CronMutationResponse(ok = true)

    override suspend fun runCron(jobId: String, profile: String?): io.github.jcartu.hermex.model.CronMutationResponse {
        runCalls += jobId
        return runReply(jobId)
    }

    override suspend fun pauseCron(jobId: String, profile: String?, reason: String?) =
        io.github.jcartu.hermex.model.CronMutationResponse(ok = true)

    override suspend fun resumeCron(jobId: String, profile: String?) =
        io.github.jcartu.hermex.model.CronMutationResponse(ok = true)

    override suspend fun deleteCron(jobId: String, profile: String?) =
        io.github.jcartu.hermex.model.CronMutationResponse(ok = true)

    override suspend fun cronOutput(jobId: String, limit: Int?) =
        io.github.jcartu.hermex.model.CronOutputResponse(jobId = jobId, outputs = emptyList())

    override suspend fun cronHistory(jobId: String, profile: String?, offset: Int, limit: Int): CronRunHistoryResponse =
        history(offset)

    override suspend fun cronRunDetail(jobId: String, profile: String?, filename: String) =
        io.github.jcartu.hermex.model.CronRunDetailResponse(jobId = jobId, filename = filename, content = "output")

    override suspend fun cronModelGroups(profile: String?) = emptyList<io.github.jcartu.hermex.model.ModelCatalogGroup>()

    override suspend fun cronProfiles() = emptyList<io.github.jcartu.hermex.model.ProfileSummary>()

    override suspend fun cronSkills(profile: String?) = emptyList<io.github.jcartu.hermex.model.SkillSummary>()
}

@OptIn(ExperimentalCoroutinesApi::class)
class TaskDetailViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun TestScope.runNowAsync(vm: TaskDetailViewModel): CompletableDeferred<Boolean> {
        val outcome = CompletableDeferred<Boolean>()
        launch(dispatcher) { outcome.complete(vm.runNow()) }
        return outcome
    }

    @Test
    fun `webui run now marks the job running immediately`() = runTest(dispatcher) {
        val client = FakeCronClient(cronFeatures = CronFeatures.webui)
        val vm = TaskDetailViewModel(
            initialJob = CronJob(jobId = "j1", name = "j1"),
            runningElapsed = null,
            client = client,
        )
        advanceUntilIdle()

        val outcome = runNowAsync(vm)
        advanceUntilIdle()

        assertTrue(outcome.await())
        assertEquals(listOf("j1"), client.runCalls)
        assertEquals(0.0, vm.uiState.value.runningElapsed!!, 0.0)
    }

    @Test
    fun `hermes run now shows the host's outcome and ends idle`() = runTest(dispatcher) {
        val client = FakeCronClient(
            cronFeatures = CronFeatures.hermes,
            jobs = listOf(CronJob(jobId = "j1", name = "j1", lastRunAt = CronDateValue(Instant.ofEpochSecond(100)))),
        )
        client.runReply = {
            io.github.jcartu.hermex.model.CronMutationResponse(
                ok = true,
                job = CronJob(jobId = "j1", name = "j1", lastRunAt = CronDateValue(Instant.ofEpochSecond(500))),
            )
        }
        val vm = TaskDetailViewModel(
            initialJob = CronJob(jobId = "j1", name = "j1", lastRunAt = CronDateValue(Instant.ofEpochSecond(100))),
            runningElapsed = null,
            client = client,
        )
        advanceUntilIdle()

        val outcome = runNowAsync(vm)
        advanceUntilIdle()

        assertTrue(outcome.await())
        assertEquals(RunNowState.IDLE, vm.uiState.value.runNowState)
        assertEquals(listOf("j1"), client.runCalls)
    }

    @Test
    fun `run now follows an unanswered trigger through reads and fails after three`() = runTest(dispatcher) {
        val client = FakeCronClient(
            cronFeatures = CronFeatures.hermes,
            jobs = listOf(CronJob(jobId = "j1", name = "j1")),
            cronJobsError = ApiError.Http(500, "boom"),
        )
        client.runReply = { throw ApiError.Http(504, "gateway timeout") }
        val vm = TaskDetailViewModel(
            initialJob = CronJob(jobId = "j1", name = "j1"),
            runningElapsed = null,
            client = client,
        )
        advanceUntilIdle()

        val outcome = runNowAsync(vm)
        advanceTimeBy(20_000)
        advanceUntilIdle()

        // The trigger was sent; the three failed reads ended the follow with the read's error.
        assertFalse(outcome.await())
        assertEquals(listOf("j1"), client.runCalls)
        assertNotNull(vm.uiState.value.actionErrorMessage)
        assertEquals(RunNowState.IDLE, vm.uiState.value.runNowState)
    }

    @Test
    fun `a read showing the run running keeps run now pending until the host finishes`() = runTest(dispatcher) {
        val client = FakeCronClient(
            cronFeatures = CronFeatures.hermes,
            jobs = listOf(CronJob(jobId = "j1", name = "j1")),
        )
        client.runReply = { throw ApiError.Http(504, "gateway timeout") }
        val vm = TaskDetailViewModel(
            initialJob = CronJob(jobId = "j1", name = "j1"),
            runningElapsed = null,
            client = client,
        )
        advanceUntilIdle()

        // The trigger is unanswered (504), and the first read shows the job running.
        client.cronJobsError = null
        client.running = mapOf("j1" to 2.0)

        val outcome = runNowAsync(vm)
        advanceTimeBy(1)
        assertEquals(RunNowState.RUNNING, vm.uiState.value.runNowState)

        // The next read shows the run finished: a newer last_run_at, no running entry.
        client.running = emptyMap()
        client.jobs = listOf(CronJob(jobId = "j1", name = "j1", lastRunAt = CronDateValue(Instant.ofEpochSecond(900))))
        advanceTimeBy(6_000)
        advanceUntilIdle()

        assertTrue(outcome.await())
        assertEquals(RunNowState.IDLE, vm.uiState.value.runNowState)
    }

    @Test
    fun `history 404 retires the section and pages advance by the page size`() = runTest(dispatcher) {
        val missing = FakeCronClient(
            cronFeatures = CronFeatures.webui,
            history = { throw ApiError.Http(404, "gone") },
        )
        val vm = TaskDetailViewModel(
            initialJob = CronJob(jobId = "j1", name = "j1"),
            runningElapsed = null,
            client = missing,
        )
        advanceUntilIdle()
        assertTrue(vm.uiState.value.isHistoryUnavailable)

        val paged = FakeCronClient(
            cronFeatures = CronFeatures.webui,
            history = { offset ->
                if (offset == 0) {
                    CronRunHistoryResponse(
                        runs = (1..50).map { CronRunHistoryItem(filename = "run-$it") },
                        total = 53,
                        offset = 0,
                    )
                } else {
                    CronRunHistoryResponse(
                        runs = listOf(CronRunHistoryItem(filename = "run-51")),
                        total = 53,
                        offset = 50,
                    )
                }
            },
        )
        val vm2 = TaskDetailViewModel(
            initialJob = CronJob(jobId = "j2", name = "j2"),
            runningElapsed = null,
            client = paged,
        )
        advanceUntilIdle()
        assertEquals(50, vm2.uiState.value.runs.size)
        assertTrue(vm2.uiState.value.canLoadMoreRuns)
        vm2.loadMoreRuns()
        advanceUntilIdle()
        assertEquals(51, vm2.uiState.value.runs.size)
        assertFalse(vm2.uiState.value.canLoadMoreRuns)
    }
}
