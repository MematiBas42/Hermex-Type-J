package io.github.jcartu.hermex.ui.feature.kanban

import io.github.jcartu.hermex.model.KanbanAddCommentRequest
import io.github.jcartu.hermex.model.KanbanAddCommentResponse
import io.github.jcartu.hermex.model.KanbanAssigneeHistory
import io.github.jcartu.hermex.model.KanbanBoard
import io.github.jcartu.hermex.model.KanbanBoardMutationEnvelope
import io.github.jcartu.hermex.model.KanbanBoardRequest
import io.github.jcartu.hermex.model.KanbanBoardSnapshot
import io.github.jcartu.hermex.model.KanbanBoardsResponse
import io.github.jcartu.hermex.model.KanbanBulkActionEnvelope
import io.github.jcartu.hermex.model.KanbanBulkActionRequest
import io.github.jcartu.hermex.model.KanbanBulkActionResult
import io.github.jcartu.hermex.model.KanbanCard
import io.github.jcartu.hermex.model.KanbanCardActionRequest
import io.github.jcartu.hermex.model.KanbanCardDetailEnvelope
import io.github.jcartu.hermex.model.KanbanCardDetailRequest
import io.github.jcartu.hermex.model.KanbanCardMutationEnvelope
import io.github.jcartu.hermex.model.KanbanCardStatusRequest
import io.github.jcartu.hermex.model.KanbanColumn
import io.github.jcartu.hermex.model.KanbanConfiguration
import io.github.jcartu.hermex.model.KanbanCreateBoardRequest
import io.github.jcartu.hermex.model.KanbanCreateCardRequest
import io.github.jcartu.hermex.model.KanbanDependencyMutationEnvelope
import io.github.jcartu.hermex.model.KanbanDependencyMutationRequest
import io.github.jcartu.hermex.model.KanbanDispatchRequest
import io.github.jcartu.hermex.model.KanbanDispatchResult
import io.github.jcartu.hermex.model.KanbanEditBoardRequest
import io.github.jcartu.hermex.model.KanbanEditCardRequest
import io.github.jcartu.hermex.model.KanbanEventsEnvelope
import io.github.jcartu.hermex.model.KanbanEventsRequest
import io.github.jcartu.hermex.model.KanbanStats
import io.github.jcartu.hermex.model.KanbanStatus
import io.github.jcartu.hermex.model.KanbanWorkerLog
import io.github.jcartu.hermex.model.KanbanWorkerLogRequest

internal class FakeKanbanLiveEventSource : KanbanLiveEventSource {
    data class Start(val board: String, val since: Int)

    val starts = mutableListOf<Start>()
    var stopCount = 0
    private var onFrame: ((KanbanLiveFrame) -> Unit)? = null
    private var onFailure: (() -> Unit)? = null

    override fun start(
        board: String,
        since: Int,
        onFrame: (KanbanLiveFrame) -> Unit,
        onFailure: () -> Unit,
    ) {
        starts += Start(board, since)
        this.onFrame = onFrame
        this.onFailure = onFailure
    }

    override fun stop() {
        stopCount += 1
        onFrame = null
        onFailure = null
    }

    fun emit(frame: KanbanLiveFrame) {
        onFrame?.invoke(frame)
    }

    fun fail() {
        onFailure?.invoke()
    }
}

internal class FakeKanbanDataSource(
    override val backend: KanbanBackend = KanbanBackend.HERMES,
    val liveEvents: FakeKanbanLiveEventSource = FakeKanbanLiveEventSource(),
) : KanbanDataSource {
    override val capabilities: KanbanCapabilities = KanbanCapabilities(
        dispatcher = true,
        workerLogs = true,
        onlyMineFilter = backend == KanbanBackend.WEBUI,
        socketLiveUpdates = backend == KanbanBackend.HERMES,
    )

    var configuration = KanbanConfiguration(
        columns = if (backend == KanbanBackend.WEBUI) listOf("triage", "ready", "running", "done") else null,
        assignees = listOf("inbox"),
        readOnly = false,
    )
    var boards = KanbanBoardsResponse(
        boards = listOf(KanbanBoard(slug = "main", name = "Main", isCurrent = true)),
        current = "main",
        readOnly = false,
    )
    var snapshotQueue = ArrayDeque<KanbanBoardSnapshot>().apply { add(snapshot(cursor = 3)) }
    var boardReadCount = 0
    var detail = KanbanCardDetailEnvelope(card = card("c1", "triage"), readOnly = false)
    var workerLog = KanbanWorkerLog(cardId = "c1", exists = true, content = "done")
    var dispatchResult = KanbanDispatchResult(promoted = kotlinx.serialization.json.JsonPrimitive(1))
    var mutationFailure: Throwable? = null

    override fun makeLiveEventSource(): KanbanLiveEventSource? =
        if (backend == KanbanBackend.HERMES) liveEvents else null

    override suspend fun kanbanConfiguration() = configuration
    override suspend fun kanbanBoards() = boards

    override suspend fun kanbanBoard(request: KanbanBoardRequest): KanbanBoardSnapshot {
        boardReadCount += 1
        return if (snapshotQueue.size > 1) snapshotQueue.removeFirst() else snapshotQueue.first()
    }

    override suspend fun kanbanStats(board: String) = KanbanStats(total = snapshotQueue.first().columns.orEmpty().sumOf { it.cards.orEmpty().size })
    override suspend fun kanbanAssignees(board: String) = KanbanAssigneeHistory(listOf("inbox"))
    override suspend fun kanbanEvents(request: KanbanEventsRequest) = KanbanEventsEnvelope(events = emptyList(), cursor = request.since)
    override suspend fun kanbanCardDetail(request: KanbanCardDetailRequest) = detail.copy(card = card(request.cardId, detail.card?.status?.rawValue ?: "triage"))
    override suspend fun kanbanWorkerLog(request: KanbanWorkerLogRequest) = workerLog.copy(cardId = request.cardId)

    override suspend fun createKanbanBoard(request: KanbanCreateBoardRequest): KanbanBoardMutationEnvelope {
        failMutationIfNeeded()
        return KanbanBoardMutationEnvelope(board = KanbanBoard(request.slug, request.name))
    }

    override suspend fun editKanbanBoard(request: KanbanEditBoardRequest): KanbanBoardMutationEnvelope {
        failMutationIfNeeded()
        return KanbanBoardMutationEnvelope(board = KanbanBoard(request.slug, request.name))
    }

    override suspend fun archiveKanbanBoard(slug: String): KanbanBoardMutationEnvelope {
        failMutationIfNeeded()
        return KanbanBoardMutationEnvelope(current = boards.current)
    }

    override suspend fun makeKanbanBoardActive(slug: String): KanbanBoardMutationEnvelope {
        failMutationIfNeeded()
        return KanbanBoardMutationEnvelope(current = slug)
    }

    override suspend fun createKanbanCard(request: KanbanCreateCardRequest): KanbanCardMutationEnvelope {
        failMutationIfNeeded()
        return KanbanCardMutationEnvelope(card = card("created", request.status).copy(title = request.title, tenant = request.tenant))
    }

    override suspend fun editKanbanCard(request: KanbanEditCardRequest, changesAssignee: Boolean): KanbanCardMutationEnvelope {
        failMutationIfNeeded()
        return KanbanCardMutationEnvelope(
            card = card(request.cardId, request.status ?: "triage").copy(
                title = request.title,
                tenant = detail.card?.tenant,
                assignee = request.assignee,
            ),
        )
    }

    override suspend fun setKanbanCardStatus(request: KanbanCardStatusRequest): KanbanCardMutationEnvelope {
        failMutationIfNeeded()
        return KanbanCardMutationEnvelope(card = card(request.cardId, request.status))
    }

    override suspend fun blockKanbanCard(request: KanbanCardActionRequest): KanbanCardMutationEnvelope {
        failMutationIfNeeded()
        return KanbanCardMutationEnvelope(card = card(request.cardId, "blocked"))
    }

    override suspend fun unblockKanbanCard(request: KanbanCardActionRequest): KanbanCardMutationEnvelope {
        failMutationIfNeeded()
        return KanbanCardMutationEnvelope(card = card(request.cardId, "ready"))
    }

    override suspend fun addKanbanComment(request: KanbanAddCommentRequest): KanbanAddCommentResponse {
        failMutationIfNeeded()
        return KanbanAddCommentResponse(ok = true)
    }

    override suspend fun addKanbanDependency(request: KanbanDependencyMutationRequest): KanbanDependencyMutationEnvelope {
        failMutationIfNeeded()
        return KanbanDependencyMutationEnvelope(ok = true, prerequisiteId = request.prerequisiteId, dependentId = request.dependentId)
    }

    override suspend fun removeKanbanDependency(request: KanbanDependencyMutationRequest): KanbanDependencyMutationEnvelope {
        failMutationIfNeeded()
        return KanbanDependencyMutationEnvelope(ok = true, prerequisiteId = request.prerequisiteId, dependentId = request.dependentId)
    }

    override suspend fun performKanbanBulkAction(request: KanbanBulkActionRequest): KanbanBulkActionEnvelope {
        failMutationIfNeeded()
        return KanbanBulkActionEnvelope(request.cardIds.map { KanbanBulkActionResult(it, true) })
    }

    override suspend fun dispatchKanban(request: KanbanDispatchRequest): KanbanDispatchResult {
        failMutationIfNeeded()
        return dispatchResult
    }

    private fun failMutationIfNeeded() {
        mutationFailure?.let { throw it }
    }

    companion object {
        fun card(id: String, status: String) = KanbanCard(
            cardId = id,
            title = "Card $id",
            status = KanbanStatus(status),
            priority = 3,
            assignee = "inbox",
            tenant = "tenant-a",
        )

        fun snapshot(cursor: Int, title: String = "Card c1") = KanbanBoardSnapshot(
            columns = listOf(
                KanbanColumn("triage", listOf(card("c1", "triage").copy(title = title))),
                KanbanColumn("ready", emptyList()),
                KanbanColumn("running", emptyList()),
                KanbanColumn("done", emptyList()),
            ),
            tenants = listOf("tenant-a"),
            assignees = listOf("inbox"),
            latestEventId = cursor,
            readOnly = false,
        )
    }
}
