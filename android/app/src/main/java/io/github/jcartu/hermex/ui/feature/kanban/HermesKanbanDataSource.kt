package io.github.jcartu.hermex.ui.feature.kanban

import io.github.jcartu.hermex.model.KanbanAddCommentRequest
import io.github.jcartu.hermex.model.KanbanBoardRequest
import io.github.jcartu.hermex.model.KanbanBulkActionRequest
import io.github.jcartu.hermex.model.KanbanCardActionRequest
import io.github.jcartu.hermex.model.KanbanCardDetailRequest
import io.github.jcartu.hermex.model.KanbanCardStatusRequest
import io.github.jcartu.hermex.model.KanbanCreateBoardRequest
import io.github.jcartu.hermex.model.KanbanCreateCardRequest
import io.github.jcartu.hermex.model.KanbanDependencyMutationRequest
import io.github.jcartu.hermex.model.KanbanDispatchRequest
import io.github.jcartu.hermex.model.KanbanEditBoardRequest
import io.github.jcartu.hermex.model.KanbanEditCardRequest
import io.github.jcartu.hermex.model.KanbanEventsEnvelope
import io.github.jcartu.hermex.model.KanbanEventsRequest
import io.github.jcartu.hermex.model.KanbanWorkerLogRequest
import io.github.jcartu.hermex.model.hermes.BotFailure
import io.github.jcartu.hermex.net.hermes.HermesConnection
import io.github.jcartu.hermex.net.hermes.HermesKanbanClient
import io.github.jcartu.hermex.net.hermes.KanbanSocketFrame
import io.github.jcartu.hermex.net.hermes.KanbanWebSocketEventClient
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement

/**
 * Kanban on a Hermes server: the shared UI's data source over
 * [HermesKanbanClient] and its Board-pinned [KanbanWebSocketEventClient]. The
 * transport performs the exact plugin request/response adaptation, strips host
 * paths and process identifiers, marks replies writable, and maps 400/409
 * details to `KanbanWriteRefusal`.
 *
 * Port of `Networking/Hermes/HermesKanbanClient.swift` and the Hermes branches
 * in `Features/Kanban/KanbanFeatureState.swift` (#1043–#1045).
 */
class HermesKanbanDataSource internal constructor(
    private val client: HermesKanbanClient,
) : KanbanDataSource {

    constructor(connection: HermesConnection) : this(HermesKanbanClient(connection))

    override val backend: KanbanBackend = KanbanBackend.HERMES
    override val capabilities: KanbanCapabilities = KanbanCapabilities(
        dispatcher = true,
        workerLogs = true,
        onlyMineFilter = false,
        socketLiveUpdates = true,
    )

    override fun makeLiveEventSource(): KanbanLiveEventSource =
        HermesKanbanLiveEventSource(client.makeEventStream())

    override suspend fun kanbanConfiguration() = client.kanbanConfiguration()
    override suspend fun kanbanBoards() = client.kanbanBoards()
    override suspend fun kanbanBoard(request: KanbanBoardRequest) = client.kanbanBoard(request)
    override suspend fun kanbanStats(board: String) = client.kanbanStats(board)
    override suspend fun kanbanAssignees(board: String) = client.kanbanAssignees(board)

    /** The Hermes plugin has no REST events route; live events use its WebSocket. */
    override suspend fun kanbanEvents(request: KanbanEventsRequest): KanbanEventsEnvelope =
        throw BotFailure.Unsupported

    override suspend fun kanbanCardDetail(request: KanbanCardDetailRequest) = client.kanbanCardDetail(request)
    override suspend fun kanbanWorkerLog(request: KanbanWorkerLogRequest) = client.kanbanWorkerLog(request)
    override suspend fun createKanbanBoard(request: KanbanCreateBoardRequest) = client.createKanbanBoard(request)
    override suspend fun editKanbanBoard(request: KanbanEditBoardRequest) = client.editKanbanBoard(request)
    override suspend fun archiveKanbanBoard(slug: String) = client.archiveKanbanBoard(slug)
    override suspend fun makeKanbanBoardActive(slug: String) = client.makeKanbanBoardActive(slug)
    override suspend fun createKanbanCard(request: KanbanCreateCardRequest) = client.createKanbanCard(request)

    override suspend fun editKanbanCard(request: KanbanEditCardRequest, changesAssignee: Boolean) =
        client.editKanbanCard(request, changesAssignee)

    override suspend fun setKanbanCardStatus(request: KanbanCardStatusRequest) = client.setKanbanCardStatus(request)
    override suspend fun blockKanbanCard(request: KanbanCardActionRequest) = client.blockKanbanCard(request)
    override suspend fun unblockKanbanCard(request: KanbanCardActionRequest) = client.unblockKanbanCard(request)
    override suspend fun addKanbanComment(request: KanbanAddCommentRequest) = client.addKanbanComment(request)
    override suspend fun addKanbanDependency(request: KanbanDependencyMutationRequest) = client.addKanbanDependency(request)
    override suspend fun removeKanbanDependency(request: KanbanDependencyMutationRequest) = client.removeKanbanDependency(request)
    override suspend fun performKanbanBulkAction(request: KanbanBulkActionRequest) = client.performKanbanBulkAction(request)
    override suspend fun dispatchKanban(request: KanbanDispatchRequest) = client.dispatchKanban(request)
}

/**
 * Adapts transport socket callbacks to the UI seam. The callbacks arrive on
 * `HermesActor.dispatcher`; [KanbanViewModel] explicitly launches their work in
 * `viewModelScope` (Main) before touching Compose state.
 */
private class HermesKanbanLiveEventSource(
    private val stream: KanbanWebSocketEventClient,
) : KanbanLiveEventSource {
    override fun start(
        board: String,
        since: Int,
        onFrame: (KanbanLiveFrame) -> Unit,
        onFailure: () -> Unit,
    ) {
        stream.start(
            board = board,
            since = since,
            onFrame = { frame ->
                when (frame) {
                    KanbanSocketFrame.Opened -> onFrame(KanbanLiveFrame.Opened)
                    is KanbanSocketFrame.Events -> onFrame(decode(frame))
                }
            },
            onFailure = onFailure,
        )
    }

    override fun stop() {
        stream.stop()
    }

    private fun decode(frame: KanbanSocketFrame.Events): KanbanLiveFrame = try {
        KanbanLiveFrame.Events(
            JSON.decodeFromJsonElement(KanbanEventsEnvelope.serializer(), frame.payload.element),
        )
    } catch (_: SerializationException) {
        KanbanLiveFrame.Malformed
    } catch (_: IllegalArgumentException) {
        KanbanLiveFrame.Malformed
    }

    private companion object {
        val JSON = Json {
            ignoreUnknownKeys = true
            isLenient = true
            coerceInputValues = true
            explicitNulls = false
        }
    }
}
