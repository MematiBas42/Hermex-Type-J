package io.github.jcartu.hermex.ui.feature.tasks

import io.github.jcartu.hermex.model.CronDateValue
import io.github.jcartu.hermex.model.CronJob
import io.github.jcartu.hermex.model.CronJobEditorDraft
import io.github.jcartu.hermex.model.CronRecentCompletion
import io.github.jcartu.hermex.model.CronRunHistoryResponse
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
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

/** A minimal scripted client for the list-level tests. */
private class ListFakeClient(
    var jobs: List<CronJob> = emptyList(),
    var running: Map<String, Double> = emptyMap(),
    var recent: List<CronRecentCompletion> = emptyList(),
    var recentError: Exception? = null,
    var createResponse: io.github.jcartu.hermex.model.CronMutationResponse =
        io.github.jcartu.hermex.model.CronMutationResponse(ok = true),
) : CronDataClient {

    override val cronFeatures: CronFeatures = CronFeatures.webui

    var createCalls = mutableListOf<CronJobEditorDraft>()
    var deleteCalls = mutableListOf<String>()
    var runCalls = mutableListOf<String>()
    var pauseCalls = mutableListOf<String>()
    var resumeCalls = mutableListOf<String>()

    override suspend fun cronJobs(): CronJobList =
        CronJobList(jobs = jobs, runningJobs = running, recentRuns = null)

    override suspend fun cronRecent(): io.github.jcartu.hermex.model.CronRecentCompletionsResponse {
        recentError?.let { throw it }
        return io.github.jcartu.hermex.model.CronRecentCompletionsResponse(completions = recent)
    }

    override suspend fun cronDeliveryOptions(profile: String?) =
        io.github.jcartu.hermex.model.CronDeliveryOptionsResponse(
            platforms = listOf(io.github.jcartu.hermex.model.CronDeliveryOption(value = "telegram", label = "Telegram")),
        )

    override suspend fun createCron(
        prompt: String, schedule: String, name: String?, deliver: String?, skills: List<String>,
        model: String?, provider: String?, profile: String?, toastNotifications: Boolean,
    ): io.github.jcartu.hermex.model.CronMutationResponse {
        createCalls += CronJobEditorDraft(
            name = name.orEmpty(), prompt = prompt, schedule = schedule, deliver = deliver.orEmpty(),
            skillsText = skills.joinToString(", "), model = model.orEmpty(), provider = provider.orEmpty(),
            profile = profile.orEmpty(), toastNotifications = toastNotifications,
        )
        return createResponse
    }

    override suspend fun updateCron(
        jobId: String, prompt: String?, schedule: String?, name: String?, deliver: String?,
        skills: List<String>?, model: String?, provider: String?, profile: String?, toastNotifications: Boolean?,
    ) = io.github.jcartu.hermex.model.CronMutationResponse(ok = true)

    override suspend fun runCron(jobId: String, profile: String?): io.github.jcartu.hermex.model.CronMutationResponse {
        runCalls += jobId
        return io.github.jcartu.hermex.model.CronMutationResponse(ok = true)
    }

    override suspend fun pauseCron(jobId: String, profile: String?, reason: String?): io.github.jcartu.hermex.model.CronMutationResponse {
        pauseCalls += jobId
        return io.github.jcartu.hermex.model.CronMutationResponse(ok = true)
    }

    override suspend fun resumeCron(jobId: String, profile: String?): io.github.jcartu.hermex.model.CronMutationResponse {
        resumeCalls += jobId
        return io.github.jcartu.hermex.model.CronMutationResponse(ok = true)
    }

    override suspend fun deleteCron(jobId: String, profile: String?): io.github.jcartu.hermex.model.CronMutationResponse {
        deleteCalls += jobId
        return io.github.jcartu.hermex.model.CronMutationResponse(ok = true)
    }

    override suspend fun cronOutput(jobId: String, limit: Int?) =
        io.github.jcartu.hermex.model.CronOutputResponse(outputs = emptyList())

    override suspend fun cronHistory(jobId: String, profile: String?, offset: Int, limit: Int) =
        CronRunHistoryResponse()

    override suspend fun cronRunDetail(jobId: String, profile: String?, filename: String) =
        io.github.jcartu.hermex.model.CronRunDetailResponse(filename = filename, content = "")

    override suspend fun cronModelGroups(profile: String?) = emptyList<io.github.jcartu.hermex.model.ModelCatalogGroup>()

    override suspend fun cronProfiles() = emptyList<io.github.jcartu.hermex.model.ProfileSummary>()

    override suspend fun cronSkills(profile: String?) = emptyList<io.github.jcartu.hermex.model.SkillSummary>()
}

@OptIn(ExperimentalCoroutinesApi::class)
class TasksViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `load shows jobs running state delivery options and the recent feed`() = runTest(dispatcher) {
        val client = ListFakeClient(
            jobs = listOf(CronJob(jobId = "j1", name = "One")),
            running = mapOf("j1" to 3.0),
            recent = listOf(
                CronRecentCompletion(jobId = "j1", name = "One", status = "ok", completedAt = Instant.ofEpochSecond(50)),
                CronRecentCompletion(jobId = "j2", name = "Two", status = "ok", completedAt = Instant.ofEpochSecond(10)),
            ),
        )
        val vm = TasksViewModel(client)
        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals(listOf("j1"), state.jobs.map { it.jobId })
        assertEquals(3.0, state.runningElapsed(CronJob(jobId = "j1", name = "One"))!!, 0.0)
        assertEquals("telegram", state.deliveryOptions?.single()?.value)
        // Newest first.
        assertEquals(listOf("j1", "j2"), state.recentRuns.map { it.jobId })
    }

    @Test
    fun `a failed recent feed keeps the last good one and never gates loading`() = runTest(dispatcher) {
        val client = ListFakeClient(
            jobs = listOf(CronJob(jobId = "j1", name = "One")),
            recent = listOf(CronRecentCompletion(jobId = "j1", completedAt = Instant.ofEpochSecond(50))),
        )
        val vm = TasksViewModel(client)
        advanceUntilIdle()
        assertEquals(1, vm.uiState.value.recentRuns.size)

        client.recentError = RuntimeException("feed down")
        vm.load()
        advanceUntilIdle()
        assertEquals(1, vm.uiState.value.recentRuns.size)
        assertFalse(vm.uiState.value.isLoading)
    }

    @Test
    fun `create validates the draft before the network`() = runTest(dispatcher) {
        val client = ListFakeClient()
        val vm = TasksViewModel(client)
        advanceUntilIdle()

        val result = vm.create(CronJobEditorDraft(name = "", prompt = "", schedule = ""))
        advanceUntilIdle()
        assertFalse(result)
        assertEquals("Prompt is required.", vm.uiState.value.actionErrorMessage)
        assertTrue(client.createCalls.isEmpty())
    }

    @Test
    fun `create sends trimmed draft values and dismisses`() = runTest(dispatcher) {
        val client = ListFakeClient()
        val vm = TasksViewModel(client)
        advanceUntilIdle()

        val result = vm.create(
            CronJobEditorDraft(
                name = "  Backup  ", prompt = " p ", schedule = " s ", deliver = "local",
                skillsText = "a, b", model = " m ", provider = " prov ",
            ),
        )
        advanceUntilIdle()
        assertTrue(result)
        val sent = client.createCalls.single()
        assertEquals("Backup", sent.name)
        assertEquals("p", sent.prompt)
        assertEquals("s", sent.schedule)
        assertEquals(listOf("a", "b"), sent.skills)
        assertEquals("m", sent.model)
        assertEquals("prov", sent.provider)
    }

    @Test
    fun `row actions route to the detail view model and fold back`() = runTest(dispatcher) {
        val client = ListFakeClient(jobs = listOf(CronJob(jobId = "j1", name = "One")))
        val vm = TasksViewModel(client)
        advanceUntilIdle()

        vm.runNow(CronJob(jobId = "j1", name = "One"))
        advanceUntilIdle()
        assertEquals(listOf("j1"), client.runCalls)
        assertEquals(0.0, vm.uiState.value.runningJobs["j1"]!!, 0.0)

        vm.pause(CronJob(jobId = "j1", name = "One"))
        advanceUntilIdle()
        assertEquals(listOf("j1"), client.pauseCalls)
        assertEquals(null, vm.uiState.value.runningJobs["j1"])

        vm.resume(CronJob(jobId = "j1", name = "One"))
        advanceUntilIdle()
        assertEquals(listOf("j1"), client.resumeCalls)

        vm.delete(CronJob(jobId = "j1", name = "One"))
        advanceUntilIdle()
        assertEquals(listOf("j1"), client.deleteCalls)
        assertTrue(vm.uiState.value.jobs.isEmpty())
    }

    @Test
    fun `scheduler stall age surfaces only past three missed ticks`() = runTest(dispatcher) {
        val client = ListFakeClient(
            jobs = listOf(
                CronJob(jobId = "j1", name = "One", schedulerHeartbeatAge = 240.0),
                CronJob(jobId = "j2", name = "Two", schedulerHeartbeatAge = 120.0),
                CronJob(jobId = "j3", name = "Three", enabled = false, schedulerHeartbeatAge = 9999.0),
            ),
        )
        val vm = TasksViewModel(client)
        advanceUntilIdle()
        assertEquals(240.0, vm.uiState.value.schedulerStallAge!!, 0.0)
    }

    @Test
    fun `the recent feed job lookup matches id then name`() = runTest(dispatcher) {
        val client = ListFakeClient(jobs = listOf(CronJob(jobId = "j1", name = "One")))
        val vm = TasksViewModel(client)
        advanceUntilIdle()

        assertNotNull(vm.job(CronRecentCompletion(jobId = "j1", completedAt = Instant.EPOCH)))
        assertNotNull(vm.job(CronRecentCompletion(name = "One", completedAt = Instant.EPOCH)))
        assertEquals(null, vm.job(CronRecentCompletion(jobId = "zzz", completedAt = Instant.EPOCH)))
    }
}
