package io.github.jcartu.hermex.ui.feature.models

import io.github.jcartu.hermex.model.WorkspaceRoot
import io.github.jcartu.hermex.model.WorkspaceSuggestionsResponse
import io.github.jcartu.hermex.model.WorkspacesResponse
import io.github.jcartu.hermex.net.ApiError
import io.github.jcartu.hermex.net.HermesApi
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WorkspacePickerViewModelTest {

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun api(): HermesApi {
        val api = mockk<HermesApi>()
        coEvery { api.workspaces() } returns WorkspacesResponse(
            workspaces = listOf(
                WorkspaceRoot(path = "/home/josh/code", name = "Code"),
                WorkspaceRoot(path = "/home/josh/notes", name = null),
            ),
            last = "/home/josh/code",
        )
        coEvery { api.workspaceSuggestions(any()) } answers {
            WorkspaceSuggestionsResponse(
                suggestions = listOf("/home/josh/code", "/suggested/${firstArg<String>()}"),
                prefix = firstArg(),
            )
        }
        return api
    }

    @Test
    fun `init loads roots and fires an immediate empty-prefix suggestion query`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)

        val api = api()
        val vm = WorkspacePickerViewModel(api = api, selectedWorkspacePath = "/home/josh/code")
        advanceUntilIdle()

        val state = vm.uiState.value
        assertFalse(state.isLoading)
        assertEquals(listOf("/home/josh/code", "/home/josh/notes"), state.savedRows.map { it.path })
        // saved paths and the current selection are excluded from suggestions
        assertEquals(listOf("/suggested/"), state.suggestionPaths)
        coVerify(exactly = 1) { api.workspaceSuggestions("") }
    }

    @Test
    fun `rapid typing debounces to a single suggestion request`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)

        val api = api()
        val vm = WorkspacePickerViewModel(api = api)
        advanceUntilIdle() // init: roots + empty-prefix suggestions

        vm.updatePrefix("/h")
        advanceTimeBy(100)
        vm.updatePrefix("/ho")
        advanceTimeBy(100)
        vm.updatePrefix("/home")
        advanceTimeBy(249)
        // still inside the 250 ms window of the last keystroke
        coVerify(exactly = 0) { api.workspaceSuggestions("/home") }
        coVerify(exactly = 0) { api.workspaceSuggestions("/h") }
        coVerify(exactly = 0) { api.workspaceSuggestions("/ho") }

        advanceUntilIdle()
        coVerify(exactly = 1) { api.workspaceSuggestions("/home") }
        coVerify(exactly = 0) { api.workspaceSuggestions("/h") }
        coVerify(exactly = 0) { api.workspaceSuggestions("/ho") }
        assertTrue(vm.uiState.value.suggestionPaths.contains("/suggested//home"))
    }

    @Test
    fun `clearing the prefix skips the debounce`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)

        val api = api()
        val vm = WorkspacePickerViewModel(api = api)
        advanceUntilIdle()

        vm.updatePrefix("")
        advanceTimeBy(1) // well inside the 250 ms window — empty prefix must not wait
        coVerify(exactly = 2) { api.workspaceSuggestions("") }
    }

    @Test
    fun `accept wins exactly once`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)

        val vm = WorkspacePickerViewModel(api = api())
        advanceUntilIdle()

        assertTrue(vm.accept("/home/josh/code"))
        assertFalse(vm.accept("/home/josh/notes"))
        assertFalse(vm.accept("/home/josh/code"))
        assertEquals("/home/josh/code", vm.uiState.value.effectiveSelectedWorkspacePath)
    }

    @Test
    fun `suggestion failure keeps previous suggestions and surfaces the message`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)

        val api = api()
        val vm = WorkspacePickerViewModel(api = api)
        advanceUntilIdle()
        val before = vm.uiState.value.suggestions

        coEvery { api.workspaceSuggestions("/boom") } throws ApiError.Http(statusCode = 500, body = null)
        vm.updatePrefix("/boom")
        advanceUntilIdle()

        assertEquals(before, vm.uiState.value.suggestions)
        assertEquals(ApiError.Http(500, null).message, vm.uiState.value.errorMessage)
    }

    @Test
    fun `roots failure surfaces the error`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)

        val api = mockk<HermesApi>()
        coEvery { api.workspaces() } throws ApiError.Unauthorized
        coEvery { api.workspaceSuggestions(any()) } returns WorkspaceSuggestionsResponse()

        val vm = WorkspacePickerViewModel(api = api)
        advanceUntilIdle()

        assertFalse(vm.uiState.value.isLoading)
        assertEquals(ApiError.Unauthorized.message, vm.uiState.value.errorMessage)
    }
}
