package io.github.jcartu.hermex.ui.feature.models

import io.github.jcartu.hermex.model.ReasoningStatusResponse
import io.github.jcartu.hermex.net.ApiError
import io.github.jcartu.hermex.net.HermesApi
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ReasoningEffortOptionTest {

    @Test
    fun `null or empty vocabulary falls back to the full static list`() {
        assertEquals(ReasoningEffortOption.allCases, ReasoningEffortOption.optionsForSupportedEfforts(null))
        assertEquals(ReasoningEffortOption.allCases, ReasoningEffortOption.optionsForSupportedEfforts(emptyList()))
    }

    @Test
    fun `static list is none through xhigh in order`() {
        assertEquals(
            listOf("none", "minimal", "low", "medium", "high", "xhigh"),
            ReasoningEffortOption.allCases.map { it.id },
        )
    }

    @Test
    fun `server vocabulary is trimmed lowercased and deduplicated in order`() {
        val options = ReasoningEffortOption.optionsForSupportedEfforts(
            listOf(" High ", "low", "HIGH", "", "  "),
        )
        assertEquals(listOf("high", "low"), options.map { it.id })
        assertEquals(listOf("High", "Low"), options.map { it.title })
    }

    @Test
    fun `unknown ids are kept with a capitalized title`() {
        val options = ReasoningEffortOption.optionsForSupportedEfforts(listOf("turbo"))
        assertEquals(listOf(ReasoningEffortOption("turbo", "Turbo")), options)
    }

    @Test
    fun `titleFor maps known ids and capitalizes unknown ones`() {
        assertEquals("XHigh", ReasoningEffortOption.titleFor("xhigh"))
        assertEquals("Custom", ReasoningEffortOption.titleFor("custom"))
    }

    @Test
    fun `showsEffortControl follows supports flag then vocabulary then defaults to visible`() {
        assertFalse(ReasoningEffortOption.showsEffortControl(supportsReasoningEffort = false, supportedEfforts = listOf("high")))
        assertTrue(ReasoningEffortOption.showsEffortControl(supportsReasoningEffort = true, supportedEfforts = emptyList()))
        assertFalse(ReasoningEffortOption.showsEffortControl(supportsReasoningEffort = null, supportedEfforts = emptyList()))
        assertTrue(ReasoningEffortOption.showsEffortControl(supportsReasoningEffort = null, supportedEfforts = listOf("low")))
        assertTrue(ReasoningEffortOption.showsEffortControl(supportsReasoningEffort = null, supportedEfforts = null))
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ReasoningEffortViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `load scopes the query to model and provider and maps the response`() = runTest(dispatcher) {
        val api = mockk<HermesApi>()
        coEvery { api.reasoning(model = "claude-fable-5", provider = "anthropic") } returns ReasoningStatusResponse(
            reasoningEffort = "high",
            supportedEfforts = listOf(" Low ", "HIGH", "low"),
            supportsReasoningEffort = true,
            showReasoning = true,
        )

        val vm = ReasoningEffortViewModel(api = api, model = "claude-fable-5", provider = "anthropic")
        val state = vm.uiState.value

        assertFalse(state.isLoading)
        assertEquals("high", state.selectedEffort)
        assertEquals(listOf("low", "high"), state.supportedEfforts)
        assertEquals(true, state.supportsReasoningEffort)
        assertEquals(true, state.showReasoning)
        coVerify(exactly = 1) { api.reasoning(model = "claude-fable-5", provider = "anthropic") }
    }

    @Test
    fun `blank model and provider are sent as null`() = runTest(dispatcher) {
        val api = mockk<HermesApi>()
        coEvery { api.reasoning(model = null, provider = null) } returns ReasoningStatusResponse(effort = "medium")

        val vm = ReasoningEffortViewModel(api = api, model = "  ", provider = "")
        assertEquals("medium", vm.uiState.value.selectedEffort)
    }

    @Test
    fun `saveEffort adopts the server's coerced effort`() = runTest(dispatcher) {
        val api = mockk<HermesApi>()
        coEvery { api.reasoning(model = null, provider = null) } returns ReasoningStatusResponse(effort = "medium")
        coEvery { api.saveReasoningEffort("xhigh") } returns ReasoningStatusResponse(reasoningEffort = "high")

        val vm = ReasoningEffortViewModel(api = api)
        vm.saveEffort("xhigh")

        assertEquals("high", vm.uiState.value.selectedEffort)
        assertFalse(vm.uiState.value.isSaving)
    }

    @Test
    fun `saveEffort falls back to the requested effort when the server echoes none`() = runTest(dispatcher) {
        val api = mockk<HermesApi>()
        coEvery { api.reasoning(model = null, provider = null) } returns ReasoningStatusResponse()
        coEvery { api.saveReasoningEffort("low") } returns ReasoningStatusResponse(ok = true)

        val vm = ReasoningEffortViewModel(api = api)
        vm.saveEffort("low")

        assertEquals("low", vm.uiState.value.selectedEffort)
    }

    @Test
    fun `saveEffort failure surfaces the error and keeps the previous effort`() = runTest(dispatcher) {
        val api = mockk<HermesApi>()
        coEvery { api.reasoning(model = null, provider = null) } returns ReasoningStatusResponse(effort = "medium")
        coEvery { api.saveReasoningEffort(any()) } throws ApiError.Http(statusCode = 500, body = null)

        val vm = ReasoningEffortViewModel(api = api)
        vm.saveEffort("high")

        assertEquals("medium", vm.uiState.value.selectedEffort)
        assertEquals(ApiError.Http(500, null).message, vm.uiState.value.errorMessage)
    }

    @Test
    fun `setReasoningShown posts show or hide and adopts the echo`() = runTest(dispatcher) {
        val api = mockk<HermesApi>()
        coEvery { api.reasoning(model = null, provider = null) } returns ReasoningStatusResponse(showReasoning = false)
        coEvery { api.saveReasoningDisplay("show") } returns ReasoningStatusResponse(showReasoning = true)
        coEvery { api.saveReasoningDisplay("hide") } returns ReasoningStatusResponse(ok = true)

        val vm = ReasoningEffortViewModel(api = api)

        vm.setReasoningShown(true)
        assertEquals(true, vm.uiState.value.showReasoning)

        // no echo → fall back to the requested value
        vm.setReasoningShown(false)
        assertEquals(false, vm.uiState.value.showReasoning)

        coVerify(exactly = 1) { api.saveReasoningDisplay("show") }
        coVerify(exactly = 1) { api.saveReasoningDisplay("hide") }
    }

    @Test
    fun `load failure surfaces the error message`() = runTest(dispatcher) {
        val api = mockk<HermesApi>()
        coEvery { api.reasoning(model = null, provider = null) } throws ApiError.Unauthorized

        val vm = ReasoningEffortViewModel(api = api)
        assertFalse(vm.uiState.value.isLoading)
        assertEquals(ApiError.Unauthorized.message, vm.uiState.value.errorMessage)
        assertNull(vm.uiState.value.selectedEffort)
    }
}
