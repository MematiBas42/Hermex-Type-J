package io.github.jcartu.hermex.ui.feature.tasks

import io.github.jcartu.hermex.model.CronDateValue
import io.github.jcartu.hermex.model.CronDeliveryOptionsResponse
import io.github.jcartu.hermex.model.CronJob
import io.github.jcartu.hermex.model.CronJobEditorDraft
import io.github.jcartu.hermex.model.CronJobStatus
import io.github.jcartu.hermex.model.CronJobsResponse
import io.github.jcartu.hermex.model.CronMutationResponse
import io.github.jcartu.hermex.model.CronStatusResponse
import io.github.jcartu.hermex.net.ApiError
import io.github.jcartu.hermex.net.HermesApi
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class TasksViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun makeApi(
        jobs: List<CronJob> = emptyList(),
        running: Map<String, Double> = emptyMap(),
    ): HermesApi = mockk<HermesApi>().also { api ->
        coEvery { api.crons() } returns CronJobsResponse(jobs = jobs)
        coEvery { api.cronStatus() } returns CronStatusResponse(runningJobs = running)
        coEvery { api.cronDeliveryOptions() } returns CronDeliveryOptionsResponse(platforms = null)
    }

    @Test
    fun `load sorts running jobs first then by next run then by name`() = runTest(dispatcher) {
        val running = CronJob(jobId = "running", name = "zz-running")
        val soon = CronJob(
            jobId = "soon",
            name = "Soon",
            nextRunAt = CronDateValue(Instant.ofEpochSecond(1_000)),
        )
        val later = CronJob(
            jobId = "later",
            name = "Later",
            nextRunAt = CronDateValue(Instant.ofEpochSecond(2_000)),
        )
        val alpha = CronJob(jobId = "alpha", name = "alpha")
        val zeta = CronJob(jobId = "zeta", name = "Zeta")

        val api = makeApi(
            jobs = listOf(zeta, later, alpha, running, soon),
            running = mapOf("running" to 12.5),
        )
        val viewModel = TasksViewModel(api)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(
            listOf("running", "soon", "later", "alpha", "zeta"),
            state.jobs.map { it.jobId },
        )
        assertEquals(12.5, state.runningElapsed(running)!!, 0.0)
        assertNull(state.runningElapsed(soon))
        assertEquals(1, state.activeRunningCount)
        assertFalse(state.isLoading)
        assertNull(state.errorMessage)
    }

    @Test
    fun `load failure surfaces error message`() = runTest(dispatcher) {
        val api = mockk<HermesApi>()
        coEvery { api.crons() } throws ApiError.Http(500, null)
        coEvery { api.cronStatus() } returns CronStatusResponse(runningJobs = emptyMap())
        coEvery { api.cronDeliveryOptions() } returns CronDeliveryOptionsResponse(platforms = null)

        val viewModel = TasksViewModel(api)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertNotNull(state.errorMessage)
        assertFalse(state.isLoading)
        assertTrue(state.jobs.isEmpty())
    }

    @Test
    fun `reload refreshes running status`() = runTest(dispatcher) {
        val jobA = CronJob(jobId = "a", name = "Alpha")
        val jobB = CronJob(jobId = "b", name = "Beta")
        val api = mockk<HermesApi>()
        coEvery { api.crons() } returns CronJobsResponse(jobs = listOf(jobA, jobB))
        coEvery { api.cronStatus() } returnsMany listOf(
            CronStatusResponse(runningJobs = emptyMap()),
            CronStatusResponse(runningJobs = mapOf("b" to 3.0)),
        )
        coEvery { api.cronDeliveryOptions() } returns CronDeliveryOptionsResponse(platforms = null)

        val viewModel = TasksViewModel(api)
        advanceUntilIdle()
        assertEquals(0, viewModel.uiState.value.activeRunningCount)
        assertEquals(listOf("a", "b"), viewModel.uiState.value.jobs.map { it.jobId })

        viewModel.load()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(mapOf("b" to 3.0), state.runningJobs)
        // The running job resorts to the front.
        assertEquals(listOf("b", "a"), state.jobs.map { it.jobId })
    }

    @Test
    fun `search filters by name prompt and schedule`() = runTest(dispatcher) {
        val digest = CronJob(jobId = "1", name = "Daily digest", prompt = "Summarize inbox")
        val backup = CronJob(
            jobId = "2",
            name = "Backup",
            prompt = "Snapshot the workspace",
            scheduleDisplay = "Every night at 2:00",
        )
        val viewModel = TasksViewModel(makeApi(jobs = listOf(digest, backup)))
        advanceUntilIdle()

        viewModel.setSearchText("digest")
        assertEquals(listOf("1"), viewModel.uiState.value.filteredJobs.map { it.jobId })

        viewModel.setSearchText("snapshot")
        assertEquals(listOf("2"), viewModel.uiState.value.filteredJobs.map { it.jobId })

        viewModel.setSearchText("night")
        assertEquals(listOf("2"), viewModel.uiState.value.filteredJobs.map { it.jobId })

        viewModel.setSearchText("nothing-matches")
        assertTrue(viewModel.uiState.value.filteredJobs.isEmpty())

        viewModel.setSearchText("")
        assertEquals(2, viewModel.uiState.value.filteredJobs.size)
    }

    @Test
    fun `create validates draft before hitting the API`() = runTest(dispatcher) {
        val api = makeApi()
        val viewModel = TasksViewModel(api)
        advanceUntilIdle()

        val created = viewModel.create(CronJobEditorDraft(prompt = "", schedule = "* * * * *"))

        assertFalse(created)
        assertEquals("Prompt is required.", viewModel.uiState.value.actionErrorMessage)
        coVerify(exactly = 0) {
            api.createCron(any(), any(), any(), any(), any(), any(), any(), any(), any())
        }

        viewModel.clearActionError()
        assertNull(viewModel.uiState.value.actionErrorMessage)
    }

    @Test
    fun `create upserts the returned job`() = runTest(dispatcher) {
        val api = makeApi()
        val newJob = CronJob(jobId = "new", name = "Fresh task")
        coEvery {
            api.createCron(any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns CronMutationResponse(ok = true, job = newJob)

        val viewModel = TasksViewModel(api)
        advanceUntilIdle()

        val created = viewModel.create(
            CronJobEditorDraft(
                name = "  Fresh task  ",
                prompt = "Do the thing",
                schedule = "0 9 * * *",
                skillsText = "git, notes",
            ),
        )
        advanceUntilIdle()

        assertTrue(created)
        assertEquals(listOf("new"), viewModel.uiState.value.jobs.map { it.jobId })
        assertFalse(viewModel.uiState.value.isMutating)
        coVerify {
            api.createCron(
                prompt = "Do the thing",
                schedule = "0 9 * * *",
                name = "Fresh task",
                deliver = "local",
                skills = listOf("git", "notes"),
                model = null,
                provider = null,
                profile = null,
                toastNotifications = true,
            )
        }
    }

    @Test
    fun `create surfaces server rejection`() = runTest(dispatcher) {
        val api = makeApi()
        coEvery {
            api.createCron(any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns CronMutationResponse(ok = false, error = "schedule invalid")

        val viewModel = TasksViewModel(api)
        advanceUntilIdle()

        val created = viewModel.create(
            CronJobEditorDraft(prompt = "p", schedule = "bad"),
        )

        assertFalse(created)
        assertEquals("schedule invalid", viewModel.uiState.value.actionErrorMessage)
        assertTrue(viewModel.uiState.value.jobs.isEmpty())
    }

    @Test
    fun `apply upserts and deletes jobs`() = runTest(dispatcher) {
        val original = CronJob(jobId = "x", name = "Old name")
        val viewModel = TasksViewModel(makeApi(jobs = listOf(original), running = mapOf("x" to 1.0)))
        advanceUntilIdle()

        val renamed = CronJob(jobId = "x", name = "New name")
        viewModel.apply(CronJobListMutation.Upsert(renamed))
        assertEquals(listOf("New name"), viewModel.uiState.value.jobs.map { it.name })

        viewModel.apply(CronJobListMutation.Delete("x"))
        assertTrue(viewModel.uiState.value.jobs.isEmpty())
        assertTrue(viewModel.uiState.value.runningJobs.isEmpty())
    }

    @Test
    fun `mark running and stopped update status and order`() = runTest(dispatcher) {
        val a = CronJob(jobId = "a", name = "Alpha")
        val b = CronJob(jobId = "b", name = "Beta")
        val viewModel = TasksViewModel(makeApi(jobs = listOf(a, b)))
        advanceUntilIdle()

        viewModel.markRunning("b")
        assertEquals(0.0, viewModel.uiState.value.runningJobs["b"]!!, 0.0)
        assertEquals(listOf("b", "a"), viewModel.uiState.value.jobs.map { it.jobId })

        viewModel.markStopped("b")
        assertNull(viewModel.uiState.value.runningJobs["b"])
        assertEquals(listOf("a", "b"), viewModel.uiState.value.jobs.map { it.jobId })
    }

    @Test
    fun `decodes cron jobs fixture with unknown fields`() = runTest(dispatcher) {
        val fixture = """
            {
              "jobs": [
                {
                  "id": "j1",
                  "name": "Daily digest",
                  "prompt": "Summarize the inbox",
                  "schedule": "0 9 * * *",
                  "schedule_display": "Daily at 9:00",
                  "enabled": true,
                  "state": "active",
                  "next_run_at": 1755600000,
                  "last_status": "success",
                  "unknown_field": {"nested": [1, 2, 3]}
                }
              ],
              "another_unknown": true
            }
        """.trimIndent()
        val response = json.decodeFromString<CronJobsResponse>(fixture)

        val api = mockk<HermesApi>()
        coEvery { api.crons() } returns response
        coEvery { api.cronStatus() } returns CronStatusResponse(runningJobs = emptyMap())
        coEvery { api.cronDeliveryOptions() } returns CronDeliveryOptionsResponse(platforms = null)

        val viewModel = TasksViewModel(api)
        advanceUntilIdle()

        val job = viewModel.uiState.value.jobs.single()
        assertEquals("j1", job.jobId)
        assertEquals("Daily digest", job.displayName)
        assertEquals("Daily at 9:00", job.scheduleText)
        assertEquals(CronJobStatus.ACTIVE, job.status)
        assertNotNull(job.nextRunAt)
        assertEquals(Instant.ofEpochSecond(1_755_600_000), job.nextRunAt!!.date)
    }
}
