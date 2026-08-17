package io.github.jcartu.hermex.ui.feature.tasks

import io.github.jcartu.hermex.model.CronDeliveryOption
import io.github.jcartu.hermex.model.CronDeliveryOptionsResponse
import io.github.jcartu.hermex.model.CronJob
import io.github.jcartu.hermex.model.CronJobEditorDraft
import io.github.jcartu.hermex.model.CronMutationResponse
import io.github.jcartu.hermex.model.CronOutputItem
import io.github.jcartu.hermex.model.CronOutputResponse
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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

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

    private fun makeApi(
        outputs: List<CronOutputItem> = emptyList(),
        options: List<CronDeliveryOption>? = null,
    ): HermesApi = mockk<HermesApi>().also { api ->
        coEvery { api.cronOutput(any(), any()) } returns CronOutputResponse(outputs = outputs)
        coEvery { api.cronDeliveryOptions() } returns CronDeliveryOptionsResponse(platforms = options)
    }

    @Test
    fun `load fetches outputs with limit five and delivery options`() = runTest(dispatcher) {
        val api = makeApi(
            outputs = listOf(CronOutputItem(filename = "out.md", content = "hello")),
            options = listOf(CronDeliveryOption(value = "email", label = "Email")),
        )
        val viewModel = TaskDetailViewModel(api, "j1")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(1, state.outputs.size)
        assertEquals("out.md", state.outputs.single().filename)
        assertEquals("email", state.deliveryOptions?.single()?.value)
        assertFalse(state.isLoading)
        assertNull(state.errorMessage)
        coVerify { api.cronOutput("j1", 5) }
    }

    @Test
    fun `missing job id surfaces error without network`() = runTest(dispatcher) {
        val api = mockk<HermesApi>()
        val viewModel = TaskDetailViewModel(api, null)
        advanceUntilIdle()

        assertEquals("Missing job identifier.", viewModel.uiState.value.errorMessage)
        coVerify(exactly = 0) { api.cronOutput(any(), any()) }
    }

    @Test
    fun `delivery options failure does not break the output load`() = runTest(dispatcher) {
        val api = mockk<HermesApi>()
        coEvery { api.cronOutput(any(), any()) } returns CronOutputResponse(
            outputs = listOf(CronOutputItem(filename = "log.txt", content = "ok")),
        )
        coEvery { api.cronDeliveryOptions() } throws ApiError.Http(404, null)

        val viewModel = TaskDetailViewModel(api, "j1")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(1, state.outputs.size)
        assertNull(state.deliveryOptions)
        assertNull(state.errorMessage)
    }

    @Test
    fun `output failure surfaces error message`() = runTest(dispatcher) {
        val api = mockk<HermesApi>()
        coEvery { api.cronOutput(any(), any()) } throws ApiError.Http(500, null)
        coEvery { api.cronDeliveryOptions() } returns CronDeliveryOptionsResponse(platforms = null)

        val viewModel = TaskDetailViewModel(api, "j1")
        advanceUntilIdle()

        assertNotNull(viewModel.uiState.value.errorMessage)
        assertTrue(viewModel.uiState.value.outputs.isEmpty())
    }

    @Test
    fun `reload refreshes outputs`() = runTest(dispatcher) {
        val api = mockk<HermesApi>()
        coEvery { api.cronOutput(any(), any()) } returnsMany listOf(
            CronOutputResponse(outputs = emptyList()),
            CronOutputResponse(outputs = listOf(CronOutputItem(filename = "new.md", content = "x"))),
        )
        coEvery { api.cronDeliveryOptions() } returns CronDeliveryOptionsResponse(platforms = null)

        val viewModel = TaskDetailViewModel(api, "j1")
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.outputs.isEmpty())

        viewModel.load()
        advanceUntilIdle()
        assertEquals("new.md", viewModel.uiState.value.outputs.single().filename)
        coVerify(exactly = 2) { api.cronOutput("j1", 5) }
    }

    @Test
    fun `run now forwards the upsert mutation`() = runTest(dispatcher) {
        val api = makeApi()
        val updated = CronJob(jobId = "j1", name = "Job", state = "active")
        coEvery { api.runCron("j1") } returns CronMutationResponse(ok = true, job = updated)

        val mutations = mutableListOf<CronJobListMutation>()
        val viewModel = TaskDetailViewModel(api, "j1", mutations::add)
        advanceUntilIdle()

        assertTrue(viewModel.runNow())
        assertEquals(listOf<CronJobListMutation>(CronJobListMutation.Upsert(updated)), mutations)
        assertFalse(viewModel.uiState.value.isMutating)
    }

    @Test
    fun `pause rejection surfaces action error`() = runTest(dispatcher) {
        val api = makeApi()
        coEvery { api.pauseCron("j1", null) } returns
            CronMutationResponse(ok = false, error = "job is busy")

        val mutations = mutableListOf<CronJobListMutation>()
        val viewModel = TaskDetailViewModel(api, "j1", mutations::add)
        advanceUntilIdle()

        assertFalse(viewModel.pause())
        assertEquals("job is busy", viewModel.uiState.value.actionErrorMessage)
        assertTrue(mutations.isEmpty())

        viewModel.clearActionError()
        assertNull(viewModel.uiState.value.actionErrorMessage)
    }

    @Test
    fun `resume forwards the upsert mutation`() = runTest(dispatcher) {
        val api = makeApi()
        val resumed = CronJob(jobId = "j1", name = "Job", enabled = true)
        coEvery { api.resumeCron("j1") } returns CronMutationResponse(ok = true, job = resumed)

        val mutations = mutableListOf<CronJobListMutation>()
        val viewModel = TaskDetailViewModel(api, "j1", mutations::add)
        advanceUntilIdle()

        assertTrue(viewModel.resume())
        assertEquals(listOf<CronJobListMutation>(CronJobListMutation.Upsert(resumed)), mutations)
    }

    @Test
    fun `delete forwards the delete mutation`() = runTest(dispatcher) {
        val api = makeApi()
        coEvery { api.deleteCron("j1") } returns CronMutationResponse(ok = true)

        val mutations = mutableListOf<CronJobListMutation>()
        val viewModel = TaskDetailViewModel(api, "j1", mutations::add)
        advanceUntilIdle()

        assertTrue(viewModel.delete())
        assertEquals(listOf<CronJobListMutation>(CronJobListMutation.Delete("j1")), mutations)
    }

    @Test
    fun `update validates and sends trimmed draft values`() = runTest(dispatcher) {
        val api = makeApi()
        val updated = CronJob(jobId = "j1", name = "Renamed")
        coEvery {
            api.updateCron(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns CronMutationResponse(ok = true, job = updated)

        val mutations = mutableListOf<CronJobListMutation>()
        val viewModel = TaskDetailViewModel(api, "j1", mutations::add)
        advanceUntilIdle()

        // Invalid draft never reaches the network.
        assertFalse(viewModel.update(CronJobEditorDraft(prompt = " ", schedule = "x")))
        assertEquals("Prompt is required.", viewModel.uiState.value.actionErrorMessage)
        coVerify(exactly = 0) {
            api.updateCron(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        }

        val draft = CronJobEditorDraft(
            name = " Renamed ",
            prompt = " Do it ",
            schedule = " 0 9 * * * ",
            deliver = " email ",
            skillsText = "a, b",
            model = " gpt ",
            provider = " openai ",
            profile = " work ",
            toastNotifications = false,
        )
        assertTrue(viewModel.update(draft))
        assertEquals(listOf<CronJobListMutation>(CronJobListMutation.Upsert(updated)), mutations)
        coVerify {
            api.updateCron(
                jobId = "j1",
                prompt = "Do it",
                schedule = "0 9 * * *",
                name = "Renamed",
                deliver = "email",
                skills = listOf("a", "b"),
                model = "gpt",
                provider = "openai",
                profile = "work",
                toastNotifications = false,
            )
        }
    }
}
