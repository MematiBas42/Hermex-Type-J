package io.github.jcartu.hermex.ui.feature.kanban

import io.github.jcartu.hermex.model.KanbanAddCommentRequest
import io.github.jcartu.hermex.model.KanbanAddCommentResponse
import io.github.jcartu.hermex.model.KanbanAssigneeHistory
import io.github.jcartu.hermex.model.KanbanBoardMutationEnvelope
import io.github.jcartu.hermex.model.KanbanBoardRequest
import io.github.jcartu.hermex.model.KanbanBoardSnapshot
import io.github.jcartu.hermex.model.KanbanBoardsResponse
import io.github.jcartu.hermex.model.KanbanBulkActionEnvelope
import io.github.jcartu.hermex.model.KanbanBulkActionRequest
import io.github.jcartu.hermex.model.KanbanCardActionRequest
import io.github.jcartu.hermex.model.KanbanCardDetailEnvelope
import io.github.jcartu.hermex.model.KanbanCardDetailRequest
import io.github.jcartu.hermex.model.KanbanCardMutationEnvelope
import io.github.jcartu.hermex.model.KanbanCardStatusRequest
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
import io.github.jcartu.hermex.model.KanbanWorkerLog
import io.github.jcartu.hermex.model.KanbanWorkerLogRequest
import io.github.jcartu.hermex.net.HermesApi

/**
 * Which server implementation backs Kanban. Port of `KanbanBackend` in
 * `Networking/APIClient+Kanban.swift` and its policies in `Models/Kanban.swift`.
 */
enum class KanbanBackend {
    WEBUI,
    HERMES,
    ;

    /** Statuses a new Card may start in and ordinary Move may target. */
    val createStatuses: List<String>
        get() = if (this == HERMES) listOf("triage", "ready") else listOf("triage", "todo", "ready")

    /** Statuses the UI must not offer as direct destinations. */
    val unofferedStatuses: Set<String>
        get() = if (this == HERMES) {
            setOf("running", "todo", "scheduled", "review")
        } else {
            setOf("running")
        }

    /** A Hermes Card's tenant is set at creation and immutable afterward (#1044). */
    fun canEditTenant(isEditing: Boolean): Boolean = !isEditing || this == WEBUI

    /** Hermes only accepts Block from Ready or Running. */
    fun offersBlock(status: String?): Boolean = when (this) {
        WEBUI -> status != "blocked" && status != "archived"
        HERMES -> status == "ready" || status == "running"
    }

    /** A server-confirmed landing accepted for the requested mutation. */
    fun accepts(landed: String?, requested: String, previous: String?): Boolean {
        if (landed == null) return false
        if (landed == requested) return true
        if (this != HERMES || landed == previous) return false
        return when (requested) {
            "blocked" -> landed == "triage"
            "ready" -> landed == "todo" || landed == "review"
            else -> false
        }
    }
}

/** Feature gates that keep unsupported operational controls out of the UI. */
data class KanbanCapabilities(
    val dispatcher: Boolean = true,
    val workerLogs: Boolean = true,
    val onlyMineFilter: Boolean = true,
    val socketLiveUpdates: Boolean = false,
)

/** A normalized live-update frame consumed by [KanbanViewModel]. */
sealed interface KanbanLiveFrame {
    data object Opened : KanbanLiveFrame
    data class Events(val envelope: KanbanEventsEnvelope) : KanbanLiveFrame
    data object Malformed : KanbanLiveFrame
}

/**
 * One Board-pinned live stream. Hermes uses the plugin WebSocket; webui keeps
 * its existing cursor polling path and therefore returns no instance.
 */
interface KanbanLiveEventSource {
    fun start(
        board: String,
        since: Int,
        onFrame: (KanbanLiveFrame) -> Unit,
        onFailure: () -> Unit,
    )

    fun stop()
}

/**
 * Everything the shared Kanban composables read and mutate. This is the Android
 * kind seam corresponding to Swift's `KanbanDataClient`: route-level dispatch
 * supplies either [WebuiKanbanDataSource] or `HermesKanbanDataSource`.
 *
 * Port of `Networking/APIClient+Kanban.swift`.
 */
interface KanbanDataSource {
    val backend: KanbanBackend
    val capabilities: KanbanCapabilities

    fun makeLiveEventSource(): KanbanLiveEventSource? = null

    suspend fun kanbanConfiguration(): KanbanConfiguration
    suspend fun kanbanBoards(): KanbanBoardsResponse
    suspend fun kanbanBoard(request: KanbanBoardRequest): KanbanBoardSnapshot
    suspend fun kanbanStats(board: String): KanbanStats
    suspend fun kanbanAssignees(board: String): KanbanAssigneeHistory
    suspend fun kanbanEvents(request: KanbanEventsRequest): KanbanEventsEnvelope
    suspend fun kanbanCardDetail(request: KanbanCardDetailRequest): KanbanCardDetailEnvelope
    suspend fun kanbanWorkerLog(request: KanbanWorkerLogRequest): KanbanWorkerLog

    suspend fun createKanbanBoard(request: KanbanCreateBoardRequest): KanbanBoardMutationEnvelope
    suspend fun editKanbanBoard(request: KanbanEditBoardRequest): KanbanBoardMutationEnvelope
    suspend fun archiveKanbanBoard(slug: String): KanbanBoardMutationEnvelope
    suspend fun makeKanbanBoardActive(slug: String): KanbanBoardMutationEnvelope
    suspend fun createKanbanCard(request: KanbanCreateCardRequest): KanbanCardMutationEnvelope
    suspend fun editKanbanCard(
        request: KanbanEditCardRequest,
        changesAssignee: Boolean = true,
    ): KanbanCardMutationEnvelope
    suspend fun setKanbanCardStatus(request: KanbanCardStatusRequest): KanbanCardMutationEnvelope
    suspend fun blockKanbanCard(request: KanbanCardActionRequest): KanbanCardMutationEnvelope
    suspend fun unblockKanbanCard(request: KanbanCardActionRequest): KanbanCardMutationEnvelope
    suspend fun addKanbanComment(request: KanbanAddCommentRequest): KanbanAddCommentResponse
    suspend fun addKanbanDependency(request: KanbanDependencyMutationRequest): KanbanDependencyMutationEnvelope
    suspend fun removeKanbanDependency(request: KanbanDependencyMutationRequest): KanbanDependencyMutationEnvelope
    suspend fun performKanbanBulkAction(request: KanbanBulkActionRequest): KanbanBulkActionEnvelope
    suspend fun dispatchKanban(request: KanbanDispatchRequest): KanbanDispatchResult
}

/** webui implementation of [KanbanDataSource], preserving the existing REST behavior. */
class WebuiKanbanDataSource(private val api: HermesApi) : KanbanDataSource {
    override val backend: KanbanBackend = KanbanBackend.WEBUI
    override val capabilities: KanbanCapabilities = KanbanCapabilities(
        dispatcher = true,
        workerLogs = true,
        onlyMineFilter = true,
        socketLiveUpdates = false,
    )

    override suspend fun kanbanConfiguration() = api.kanbanConfiguration()
    override suspend fun kanbanBoards() = api.kanbanBoards()
    override suspend fun kanbanBoard(request: KanbanBoardRequest) = api.kanbanBoard(request)
    override suspend fun kanbanStats(board: String) = api.kanbanStats(board)
    override suspend fun kanbanAssignees(board: String) = api.kanbanAssignees(board)
    override suspend fun kanbanEvents(request: KanbanEventsRequest) = api.kanbanEvents(request)
    override suspend fun kanbanCardDetail(request: KanbanCardDetailRequest) = api.kanbanCardDetail(request)
    override suspend fun kanbanWorkerLog(request: KanbanWorkerLogRequest) = api.kanbanWorkerLog(request)
    override suspend fun createKanbanBoard(request: KanbanCreateBoardRequest) = api.createKanbanBoard(request)
    override suspend fun editKanbanBoard(request: KanbanEditBoardRequest) = api.editKanbanBoard(request)
    override suspend fun archiveKanbanBoard(slug: String) = api.archiveKanbanBoard(slug)
    override suspend fun makeKanbanBoardActive(slug: String) = api.makeKanbanBoardActive(slug)
    override suspend fun createKanbanCard(request: KanbanCreateCardRequest) = api.createKanbanCard(request)
    override suspend fun editKanbanCard(request: KanbanEditCardRequest, changesAssignee: Boolean) = api.editKanbanCard(request)
    override suspend fun setKanbanCardStatus(request: KanbanCardStatusRequest) = api.setKanbanCardStatus(request)
    override suspend fun blockKanbanCard(request: KanbanCardActionRequest) = api.blockKanbanCard(request)
    override suspend fun unblockKanbanCard(request: KanbanCardActionRequest) = api.unblockKanbanCard(request)
    override suspend fun addKanbanComment(request: KanbanAddCommentRequest) = api.addKanbanComment(request)
    override suspend fun addKanbanDependency(request: KanbanDependencyMutationRequest) = api.addKanbanDependency(request)
    override suspend fun removeKanbanDependency(request: KanbanDependencyMutationRequest) = api.removeKanbanDependency(request)
    override suspend fun performKanbanBulkAction(request: KanbanBulkActionRequest) = api.performKanbanBulkAction(request)
    override suspend fun dispatchKanban(request: KanbanDispatchRequest) = api.dispatchKanban(request)
}
