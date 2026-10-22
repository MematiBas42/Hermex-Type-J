package io.github.jcartu.hermex.ui.feature.kanban

import io.github.jcartu.hermex.model.KanbanEvent
import io.github.jcartu.hermex.model.KanbanEventsEnvelope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class KanbanLiveReconciliationTest {
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
    fun `cursor applies fresh events and replace or stale input never regresses`() {
        val original = KanbanLiveCursor(5)
        val admitted = original.apply(
            KanbanEventsEnvelope(
                events = listOf(KanbanEvent(eventId = 8, cardId = "c1", kind = "updated")),
                cursor = 8,
            ),
        )
        assertEquals(8, (admitted as KanbanLiveAdmission.Apply).cursor.value)

        assertEquals(9, admitted.cursor.replacing(9).value)
        assertEquals(8, admitted.cursor.replacing(3).value)
        assertEquals(
            KanbanLiveAdmission.Stale,
            admitted.cursor.apply(
                KanbanEventsEnvelope(
                    events = listOf(KanbanEvent(eventId = 7, cardId = "c1", kind = "stale")),
                    cursor = 7,
                ),
            ),
        )
    }

    @Test
    fun `Hermes event advances cursor and replaces board while stale event is ignored`() = runTest(dispatcher) {
        val source = FakeKanbanDataSource()
        source.snapshotQueue = ArrayDeque<io.github.jcartu.hermex.model.KanbanBoardSnapshot>().apply {
            add(FakeKanbanDataSource.snapshot(cursor = 3, title = "Before"))
            add(FakeKanbanDataSource.snapshot(cursor = 8, title = "After"))
        }
        val viewModel = KanbanViewModel(
            dataSource = source,
            liveTiming = KanbanLiveTiming(coalescingMillis = 1),
        )
        viewModel.load()
        advanceUntilIdle()
        viewModel.startLiveUpdates()
        runCurrent()
        source.liveEvents.emit(KanbanLiveFrame.Opened)
        runCurrent()

        source.liveEvents.emit(
            KanbanLiveFrame.Events(
                KanbanEventsEnvelope(
                    events = listOf(KanbanEvent(eventId = 8, cardId = "c1", kind = "updated")),
                    cursor = 8,
                ),
            ),
        )
        runCurrent()
        advanceTimeBy(2)
        runCurrent()

        assertEquals(8, viewModel.uiState.value.latestEventId)
        assertEquals("After", viewModel.uiState.value.cards.single().title)
        val readsAfterFreshEvent = source.boardReadCount

        source.liveEvents.emit(
            KanbanLiveFrame.Events(
                KanbanEventsEnvelope(
                    events = listOf(KanbanEvent(eventId = 7, cardId = "c1", kind = "stale")),
                    cursor = 7,
                ),
            ),
        )
        runCurrent()
        advanceTimeBy(2)
        runCurrent()

        assertEquals(8, viewModel.uiState.value.latestEventId)
        assertEquals(readsAfterFreshEvent, source.boardReadCount)
        viewModel.stopLiveUpdates()
    }

    @Test
    fun `Hermes stream reconnects on documented backoff and delays notice after third failure`() = runTest(dispatcher) {
        val source = FakeKanbanDataSource()
        val viewModel = KanbanViewModel(
            dataSource = source,
            liveTiming = KanbanLiveTiming(
                reconnectDelaysMillis = listOf(1, 2, 5, 10, 30),
                fallbackPollingMillis = 1_000,
                failuresBeforePolling = 3,
            ),
        )
        viewModel.load()
        advanceUntilIdle()
        viewModel.startLiveUpdates()
        runCurrent()
        assertEquals(listOf(FakeKanbanLiveEventSource.Start("main", 3)), source.liveEvents.starts)

        source.liveEvents.fail()
        runCurrent()
        advanceTimeBy(1)
        runCurrent()
        assertEquals(2, source.liveEvents.starts.size)

        source.liveEvents.fail()
        runCurrent()
        advanceTimeBy(2)
        runCurrent()
        assertEquals(3, source.liveEvents.starts.size)

        source.liveEvents.fail()
        runCurrent()
        assertTrue(viewModel.uiState.value.liveUpdatesDelayed)
        assertFalse(viewModel.uiState.value.isLive)

        advanceTimeBy(5)
        runCurrent()
        assertEquals(4, source.liveEvents.starts.size)
        viewModel.stopLiveUpdates()
    }
    @Test
    fun `Hermes dispatcher worker logs and refusal copy remain available`() = runTest(dispatcher) {
        val source = FakeKanbanDataSource()
        val viewModel = KanbanViewModel(dataSource = source)
        viewModel.load()
        advanceUntilIdle()

        viewModel.previewDispatch()
        advanceUntilIdle()
        assertEquals(1, viewModel.uiState.value.dispatchPreview?.promotedCount)

        viewModel.runDispatcher()
        advanceUntilIdle()
        assertEquals(1, viewModel.uiState.value.dispatchResult?.promotedCount)

        viewModel.loadWorkerLog("c1")
        advanceUntilIdle()
        assertEquals("done", viewModel.uiState.value.workerLog?.content)

        source.mutationFailure = io.github.jcartu.hermex.net.hermes.KanbanWriteRefusal(
            409,
            "A prerequisite is still open",
        )
        viewModel.setStatus("c1", "ready")
        advanceUntilIdle()
        assertEquals("A prerequisite is still open", viewModel.uiState.value.errorMessage)
    }

}
