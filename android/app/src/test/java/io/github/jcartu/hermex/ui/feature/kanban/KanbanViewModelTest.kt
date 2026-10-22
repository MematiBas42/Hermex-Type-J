package io.github.jcartu.hermex.ui.feature.kanban

import io.github.jcartu.hermex.model.KanbanAddCommentResponse
import io.github.jcartu.hermex.model.KanbanAssigneeHistory
import io.github.jcartu.hermex.model.KanbanBoard
import io.github.jcartu.hermex.model.KanbanBoardSnapshot
import io.github.jcartu.hermex.model.KanbanBoardsResponse
import io.github.jcartu.hermex.model.KanbanBulkActionEnvelope
import io.github.jcartu.hermex.model.KanbanBulkActionRequest
import io.github.jcartu.hermex.model.KanbanBulkActionResult
import io.github.jcartu.hermex.model.KanbanBulkActionType
import io.github.jcartu.hermex.model.KanbanCard
import io.github.jcartu.hermex.model.KanbanCardDetailEnvelope
import io.github.jcartu.hermex.model.KanbanCardMutationEnvelope
import io.github.jcartu.hermex.model.KanbanColumn
import io.github.jcartu.hermex.model.KanbanConfiguration
import io.github.jcartu.hermex.model.KanbanDispatchResult
import io.github.jcartu.hermex.model.KanbanEvent
import io.github.jcartu.hermex.model.KanbanEventsEnvelope
import io.github.jcartu.hermex.model.KanbanStatus
import io.github.jcartu.hermex.model.KanbanStats
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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class KanbanViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var api: HermesApi

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        api = mockk()
        coEvery { api.kanbanConfiguration() } returns KanbanConfiguration(
            columns = listOf("todo", "ready", "running", "done"), assignees = listOf("reviewer"),
        )
        coEvery { api.kanbanBoards() } returns KanbanBoardsResponse(
            boards = listOf(KanbanBoard("main", "Type-J", isCurrent = true)), current = "main",
        )
        coEvery { api.kanbanBoard(any()) } returns snapshot()
        coEvery { api.kanbanCardDetail(any()) } answers {
            val request = firstArg<io.github.jcartu.hermex.model.KanbanCardDetailRequest>()
            KanbanCardDetailEnvelope(card = card(request.cardId, "todo"))
        }
        coEvery { api.kanbanStats(any()) } returns KanbanStats(total = 2)
        coEvery { api.kanbanAssignees(any()) } returns KanbanAssigneeHistory(listOf("reviewer"))
    }

    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun `load resolves active board columns and filters`() = runTest(dispatcher) {
        val vm = KanbanViewModel(WebuiKanbanDataSource(api))
        vm.load(); advanceUntilIdle()
        val state = vm.uiState.value
        assertEquals("main", state.selectedBoardSlug)
        assertEquals(listOf("todo", "ready"), state.columns.mapNotNull { it.name })
        assertEquals(listOf("tenant-a"), state.tenants)
        assertFalse(state.isLoading)
    }

    @Test fun `select card loads validated detail`() = runTest(dispatcher) {
        val vm = loadedViewModel()
        vm.selectCard("c1"); advanceUntilIdle()
        assertEquals("c1", vm.uiState.value.detail?.card?.cardId)
        coVerify { api.kanbanCardDetail(match { it.cardId == "c1" && it.board == "main" }) }
    }

    @Test fun `status mutation moves card to echoed status column`() = runTest(dispatcher) {
        coEvery { api.setKanbanCardStatus(any()) } returns KanbanCardMutationEnvelope(card("c1", "ready"))
        val vm = loadedViewModel()
        vm.setStatus("c1", "ready"); advanceUntilIdle()
        assertTrue(vm.uiState.value.columns.first { it.name == "todo" }.cards.orEmpty().none { it.cardId == "c1" })
        assertTrue(vm.uiState.value.columns.first { it.name == "ready" }.cards.orEmpty().any { it.cardId == "c1" })
    }

    @Test fun `comment rejection surfaces action error`() = runTest(dispatcher) {
        coEvery { api.addKanbanComment(any()) } returns KanbanAddCommentResponse(ok = false)
        val vm = loadedViewModel()
        vm.addComment("c1", "Review this"); advanceUntilIdle()
        assertTrue(vm.uiState.value.errorMessage!!.contains("rejected"))
    }

    @Test fun `bulk partial failure is never presented as success`() = runTest(dispatcher) {
        coEvery { api.performKanbanBulkAction(any()) } returns KanbanBulkActionEnvelope(
            listOf(KanbanBulkActionResult("c1", true), KanbanBulkActionResult("c2", false, "busy")),
        )
        val vm = loadedViewModel()
        vm.toggleCardSelection("c1"); vm.toggleCardSelection("c2")
        vm.bulk(KanbanBulkActionRequest("main", listOf("c1", "c2"), KanbanBulkActionType.ARCHIVE))
        advanceUntilIdle()
        assertTrue(vm.uiState.value.errorMessage!!.contains("1 card"))
        assertEquals(setOf("c2"), vm.uiState.value.selectedCardIds)
    }

    @Test fun `dispatcher preview and run keep separate results`() = runTest(dispatcher) {
        coEvery { api.dispatchKanban(match { it.dryRun }) } returns KanbanDispatchResult(spawned = JsonArray(listOf(JsonPrimitive(1))))
        coEvery { api.dispatchKanban(match { !it.dryRun }) } returns KanbanDispatchResult(promoted = JsonPrimitive(2))
        val vm = loadedViewModel()
        vm.previewDispatch(); advanceUntilIdle()
        assertEquals(1, vm.uiState.value.dispatchPreview?.spawnedCount)
        vm.runDispatcher(); advanceUntilIdle()
        assertEquals(2, vm.uiState.value.dispatchResult?.promotedCount)
        assertEquals(null, vm.uiState.value.dispatchPreview)
    }

    @Test fun `live event advances cursor and reconciles board`() = runTest(dispatcher) {
        coEvery { api.kanbanEvents(any()) } returns KanbanEventsEnvelope(
            events = listOf(KanbanEvent(8, "c1", null, "updated", 1)), latestEventId = 8,
        )
        val vm = KanbanViewModel(
            WebuiKanbanDataSource(api),
            liveTiming = KanbanLiveTiming(webuiPollingMillis = 10),
        )
        vm.load(); advanceUntilIdle()
        vm.startLiveUpdates(); advanceTimeBy(11); vm.stopLiveUpdates(); advanceUntilIdle()
        assertEquals(8, vm.uiState.value.latestEventId)
        assertFalse(vm.uiState.value.isLive)
        coVerify(atLeast = 2) { api.kanbanBoard(any()) }
    }

    private fun loadedViewModel(): KanbanViewModel {
        val vm = KanbanViewModel(WebuiKanbanDataSource(api))
        vm.load()
        dispatcher.scheduler.advanceUntilIdle()
        return vm
    }

    private fun snapshot() = KanbanBoardSnapshot(
        columns = listOf(
            KanbanColumn("todo", listOf(card("c1", "todo"), card("c2", "todo"))),
            KanbanColumn("ready", emptyList()),
        ),
        tenants = listOf("tenant-a"),
        assignees = listOf("reviewer"),
        latestEventId = 3,
    )

    private fun card(id: String, status: String) = KanbanCard(
        cardId = id,
        title = "Card $id",
        status = KanbanStatus(status),
        priority = 3,
    )
}
