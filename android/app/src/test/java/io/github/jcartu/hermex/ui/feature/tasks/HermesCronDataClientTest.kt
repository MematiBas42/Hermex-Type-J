package io.github.jcartu.hermex.ui.feature.tasks

import io.github.jcartu.hermex.model.CronDateValue
import io.github.jcartu.hermex.model.CronJob
import io.github.jcartu.hermex.model.CronMutationResponse
import io.github.jcartu.hermex.model.hermes.BotFailure
import io.github.jcartu.hermex.model.hermes.HermesCronJobList
import io.github.jcartu.hermex.model.hermes.HermesCronRecentCompletion
import io.github.jcartu.hermex.model.hermes.HermesCronRun
import io.github.jcartu.hermex.net.ApiError
import io.github.jcartu.hermex.net.hermes.HermesCronClient
import io.github.jcartu.hermex.net.hermes.HermesCronRefusal as TransportHermesCronRefusal
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Consumer-level coverage for `HermesCronDataClient` and Hermes Run Now (#1040–#1042). */
@OptIn(ExperimentalCoroutinesApi::class)
class HermesCronDataClientTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var transport: HermesCronClient

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        transport = mockk()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `cron list maps running jobs and recent completions`() = runTest(dispatcher) {
        val job = CronJob(jobId = "daily", name = "Daily", lastStatus = "ok")
        coEvery { transport.cronJobs(any()) } returns HermesCronJobList(
            jobs = listOf(job),
            runningJobs = mapOf("daily" to 12.5),
            recentRuns = listOf(
                HermesCronRecentCompletion(
                    jobId = "daily",
                    name = "Daily",
                    status = "ok",
                    completedAt = 1_800_000_000.25,
                ),
            ),
        )

        val list = HermesCronDataClient(transport, profile = "inbox").cronJobs()

        assertEquals(listOf(job), list.jobs)
        assertEquals(12.5, list.runningJobs["daily"]!!, 0.0)
        assertEquals(Instant.ofEpochSecond(1_800_000_000, 250_000_000), list.recentRuns?.single()?.completedAt)
    }

    @Test
    fun `history is the newest hundred single page and accepts any requested limit`() = runTest(dispatcher) {
        coEvery { transport.cronHistory("daily", "inbox", 0) } returns listOf(
            HermesCronRun(
                id = "cron_daily_1",
                startedAt = 100.0,
                endedAt = 104.5,
                model = "provider/model",
                inputTokens = 4,
                outputTokens = 6,
                totalTokens = 10,
            ),
        )
        val client = HermesCronDataClient(transport, profile = "inbox")

        val first = client.cronHistory("daily", profile = null, offset = 0, limit = 1)
        val later = client.cronHistory("daily", profile = null, offset = 50, limit = 500)

        assertEquals("cron_daily_1", first.runs.single().filename)
        assertEquals(Instant.ofEpochSecond(100), first.runs.single().startedAt)
        assertEquals(Instant.ofEpochSecond(104, 500_000_000), first.runs.single().modified)
        assertEquals(10, first.runs.single().usage.totalTokens)
        assertNull(first.total)
        assertTrue(later.runs.isEmpty())
        coVerify(exactly = 1) { transport.cronHistory("daily", "inbox", 0) }
    }

    @Test
    fun `transport refusal keeps Tasks refusal copy`() = runTest(dispatcher) {
        coEvery { transport.pauseCron("daily", "inbox") } throws TransportHermesCronRefusal("already paused")
        val client = HermesCronDataClient(transport, profile = "inbox")

        val failure = runCatching { client.pauseCron("daily", profile = null, reason = "later") }.exceptionOrNull()

        assertTrue(failure is HermesCronRefusal)
        assertEquals("The server rejected the request: already paused", failure?.message)
    }

    @Test
    fun `editor sources and create use the selected Profile and preserve scheduler warning`() = runTest(dispatcher) {
        val delivery = io.github.jcartu.hermex.model.CronDeliveryOptionsResponse(
            platforms = listOf(io.github.jcartu.hermex.model.CronDeliveryOption("local", "Local")),
        )
        coEvery { transport.cronDeliveryOptions("inbox") } returns delivery
        coEvery { transport.cronModelGroups("inbox") } returns emptyList()
        coEvery { transport.cronProfiles() } returns emptyList()
        coEvery { transport.cronSkills("inbox") } returns emptyList()
        coEvery {
            transport.createCron(
                prompt = "Review",
                schedule = "0 9 * * *",
                name = "Daily",
                deliver = "local",
                skills = emptyList(),
                model = null,
                provider = null,
                profile = "inbox",
            )
        } returns HermesCronClient.CreateResult(
            response = CronMutationResponse(ok = true),
            warning = "The scheduler could not register this Task.",
        )
        val client = HermesCronDataClient(transport, profile = "inbox")

        assertSame(delivery, client.cronDeliveryOptions(profile = null))
        assertTrue(client.cronModelGroups(profile = null).isEmpty())
        assertTrue(client.cronProfiles().isEmpty())
        assertTrue(client.cronSkills(profile = null).isEmpty())
        val created = client.createCron(
            prompt = "Review",
            schedule = "0 9 * * *",
            name = "Daily",
            deliver = "local",
            skills = emptyList(),
            model = null,
            provider = null,
            profile = null,
            toastNotifications = true,
        )
        assertEquals("The scheduler could not register this Task.", created.warning)
    }

    @Test
    fun `run now is requested then server-confirmed running then finished`() = runTest(dispatcher) {
        val trigger = CompletableDeferred<CronMutationResponse>()
        val original = CronJob(
            jobId = "daily",
            name = "Daily",
            profile = "inbox",
            lastRunAt = CronDateValue(Instant.ofEpochSecond(100)),
        )
        val running = original.copy(fireClaim = io.github.jcartu.hermex.model.CronFireClaim(Instant.ofEpochSecond(110)))
        val finished = original.copy(lastRunAt = CronDateValue(Instant.ofEpochSecond(120)), lastStatus = "ok")
        coEvery { transport.runCron("daily", "inbox") } coAnswers { trigger.await() }
        var listRead = 0
        coEvery { transport.cronJobs(any()) } answers {
            when (listRead++) {
                0 -> HermesCronJobList(listOf(original), emptyMap(), emptyList())
                1 -> HermesCronJobList(listOf(running), mapOf("daily" to 2.0), emptyList())
                else -> HermesCronJobList(
                    listOf(finished),
                    emptyMap(),
                    listOf(HermesCronRecentCompletion("daily", "Daily", "ok", 120.0)),
                )
            }
        }
        val viewModel = TaskDetailViewModel(
            initialJob = original,
            runningElapsed = null,
            client = HermesCronDataClient(transport, profile = "inbox"),
        )
        advanceUntilIdle()

        val outcome = async { viewModel.runNow() }
        runCurrent()
        assertEquals(RunNowState.REQUESTED, viewModel.uiState.value.runNowState)

        advanceTimeBy(5_001)
        runCurrent()
        assertEquals(RunNowState.RUNNING, viewModel.uiState.value.runNowState)

        advanceTimeBy(5_001)
        advanceUntilIdle()
        assertTrue(outcome.await())
        assertEquals(RunNowState.IDLE, viewModel.uiState.value.runNowState)
        assertEquals(Instant.ofEpochSecond(120), viewModel.uiState.value.job.lastRunAt?.date)
    }

    @Test
    fun `unanswered trigger times out after three failed confirmation reads`() = runTest(dispatcher) {
        val original = CronJob(jobId = "daily", name = "Daily", profile = "inbox")
        coEvery { transport.runCron("daily", "inbox") } throws BotFailure.Rejected(504)
        var listRead = 0
        coEvery { transport.cronJobs(any()) } answers {
            if (listRead++ == 0) {
                HermesCronJobList(listOf(original), emptyMap(), emptyList())
            } else {
                throw ApiError.Http(503, "offline")
            }
        }
        val viewModel = TaskDetailViewModel(
            initialJob = original,
            runningElapsed = null,
            client = HermesCronDataClient(transport, profile = "inbox"),
        )
        advanceUntilIdle()

        val outcome = async { viewModel.runNow() }
        runCurrent()
        assertEquals(RunNowState.REQUESTED, viewModel.uiState.value.runNowState)
        advanceTimeBy(11_000)
        advanceUntilIdle()

        assertFalse(outcome.await())
        assertEquals(RunNowState.IDLE, viewModel.uiState.value.runNowState)
        assertTrue(!viewModel.uiState.value.actionErrorMessage.isNullOrBlank())
        coVerify(exactly = 4) { transport.cronJobs(any()) }
    }
}
