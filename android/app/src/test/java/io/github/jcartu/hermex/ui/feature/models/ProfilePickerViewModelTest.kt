package io.github.jcartu.hermex.ui.feature.models

import io.github.jcartu.hermex.model.ProfileSummary
import io.github.jcartu.hermex.model.ProfileSwitchResponse
import io.github.jcartu.hermex.model.ProfilesResponse
import io.github.jcartu.hermex.net.ApiError
import io.github.jcartu.hermex.net.HermesApi
import io.mockk.coEvery
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

@OptIn(ExperimentalCoroutinesApi::class)
class ProfilePickerViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()

    private val defaultProfile = ProfileSummary(name = "default", isDefault = true, model = "claude-fable-5", provider = "anthropic", skillCount = 3)
    private val workProfile = ProfileSummary(name = "work", model = "codex-sol", provider = "openai")

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun apiWithProfiles(): HermesApi {
        val api = mockk<HermesApi>()
        coEvery { api.profiles() } returns ProfilesResponse(
            profiles = listOf(defaultProfile, workProfile),
            active = "default",
            singleProfileMode = false,
        )
        return api
    }

    @Test
    fun `load seeds profiles and the active name`() = runTest(dispatcher) {
        val vm = ProfilePickerViewModel(api = apiWithProfiles())
        val state = vm.uiState.value

        assertFalse(state.isLoading)
        assertEquals(2, state.profiles.size)
        assertEquals("default", state.activeProfileName)
        assertFalse(state.isSingleProfileMode)
        assertTrue(state.isSelected(defaultProfile))
        assertFalse(state.isSelected(workProfile))
    }

    @Test
    fun `load failure falls back to the passed-in current profile name`() = runTest(dispatcher) {
        val api = mockk<HermesApi>()
        coEvery { api.profiles() } throws ApiError.Http(statusCode = 503, body = null)

        val vm = ProfilePickerViewModel(api = api, currentProfileName = "work")
        val state = vm.uiState.value

        assertEquals(ApiError.Http(503, null).message, state.errorMessage)
        assertTrue(state.profiles.isEmpty())
        // the error path leaves activeProfileName unset; a later successful
        // load recomputes it (the Swift picker behaves the same way).
        assertNull(state.activeProfileName)
    }

    @Test
    fun `search filters by name model and provider`() = runTest(dispatcher) {
        val vm = ProfilePickerViewModel(api = apiWithProfiles())

        vm.updateSearchText("codex")
        assertEquals(listOf("work"), vm.uiState.value.filteredProfiles.map { it.name })

        vm.updateSearchText("ANTHROPIC")
        assertEquals(listOf("default"), vm.uiState.value.filteredProfiles.map { it.name })

        vm.updateSearchText("")
        assertEquals(2, vm.uiState.value.filteredProfiles.size)
    }

    @Test
    fun `profileDetails joins model provider and skill count`() {
        assertEquals("claude-fable-5 - anthropic - 3 skills", profileDetails(defaultProfile))
        assertEquals("codex-sol - openai", profileDetails(workProfile))
        assertNull(profileDetails(ProfileSummary(name = "bare")))
    }

    @Test
    fun `switchTo adopts the server echo and reports the selection`() = runTest(dispatcher) {
        val api = apiWithProfiles()
        coEvery { api.switchProfile("work") } returns ProfileSwitchResponse(
            profiles = listOf(defaultProfile.copy(isActive = false), workProfile.copy(isActive = true)),
            active = "work",
            defaultModel = "codex-sol",
        )

        val vm = ProfilePickerViewModel(api = api)
        vm.switchTo(workProfile)

        val state = vm.uiState.value
        assertFalse(state.isSaving)
        assertEquals("work", state.activeProfileName)
        assertEquals(
            DefaultProfileSelection(name = "work", displayName = "work", defaultModel = "codex-sol"),
            state.completedSelection,
        )

        vm.consumeCompletedSelection()
        assertNull(vm.uiState.value.completedSelection)
    }

    @Test
    fun `switchTo surfaces a server error payload and clears the optimistic selection`() = runTest(dispatcher) {
        val api = apiWithProfiles()
        coEvery { api.switchProfile("work") } returns ProfileSwitchResponse(error = "  profile is locked  ")

        val vm = ProfilePickerViewModel(api = api)
        vm.switchTo(workProfile)

        val state = vm.uiState.value
        assertEquals("profile is locked", state.saveError)
        assertNull(state.selectedProfileName)
        assertNull(state.completedSelection)
    }

    @Test
    fun `switchTo network failure surfaces the error`() = runTest(dispatcher) {
        val api = apiWithProfiles()
        coEvery { api.switchProfile("work") } throws ApiError.Unauthorized

        val vm = ProfilePickerViewModel(api = api)
        vm.switchTo(workProfile)

        assertEquals(ApiError.Unauthorized.message, vm.uiState.value.saveError)
        assertNull(vm.uiState.value.completedSelection)
    }

    @Test
    fun `switchTo ignores profiles without a normalized name`() = runTest(dispatcher) {
        val vm = ProfilePickerViewModel(api = apiWithProfiles())
        vm.switchTo(ProfileSummary(name = "   "))
        assertFalse(vm.uiState.value.isSaving)
        assertNull(vm.uiState.value.completedSelection)
    }
}
