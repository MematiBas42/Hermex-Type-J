package io.github.jcartu.hermex.net

import io.github.jcartu.hermex.model.SessionExportFormat
import okhttp3.HttpUrl

/**
 * Port of `Networking/Endpoints.swift` — one case per server route, carrying
 * exactly the query parameters the Swift enum builds. Dynamic Kanban/Git path
 * segments are encoded separately so a server-provided identifier can never
 * escape its route.
 */
sealed class Endpoint {

    object Health : Endpoint()
    object AuthStatus : Endpoint()
    object Login : Endpoint()
    object Logout : Endpoint()
    data class Sessions(val includeArchived: Boolean = false, val archivedLimit: Int? = null) : Endpoint()
    data class SessionsSearch(val query: String, val content: Boolean, val depth: Int) : Endpoint()
    data class Session(
        val id: String,
        val includeMessages: Boolean,
        val messageLimit: Int?,
        val messageBefore: Int?,
        val expandRenderable: Boolean = false,
    ) : Endpoint()

    data class SessionStatus(val id: String) : Endpoint()
    object NewSession : Endpoint()
    /** `POST /api/session/import_cli` — imports a CLI/messaging session into the WebUI store. */
    object ImportCliSession : Endpoint()
    /** `POST /api/session/duplicate` — a real copy, no fork lineage (#25). */
    object DuplicateSession : Endpoint()
    /** `POST /api/session/clear` — truncates the session to empty on the server (#389). Confirm first. */
    object ClearSession : Endpoint()
    object RenameSession : Endpoint()
    object DeleteSession : Endpoint()
    object PinSession : Endpoint()
    object ArchiveSession : Endpoint()
    object BranchSession : Endpoint()
    object TruncateSession : Endpoint()
    object CompressSession : Endpoint()
    object UndoSession : Endpoint()
    object RetrySession : Endpoint()
    object UpdateSession : Endpoint()
    object MoveSession : Endpoint()
    data class SessionYolo(val sessionId: String?) : Endpoint()
    data class ExportSession(val sessionId: String, val format: SessionExportFormat) : Endpoint()
    object Projects : Endpoint()
    object CreateProject : Endpoint()
    object RenameProject : Endpoint()
    object DeleteProject : Endpoint()
    object ChatStart : Endpoint()
    /**
     * `GET /api/chat/stream`. [replayAfterSeq] is the opt-in reconnect cursor:
     * omitted on a live attach so the URL stays byte-identical; negative
     * cursors clamp to 0.
     */
    data class ChatStream(val streamId: String, val replayAfterSeq: Int? = null) : Endpoint()
    data class ChatCancel(val streamId: String) : Endpoint()
    data class ChatStreamStatus(val streamId: String) : Endpoint()
    object ChatSteer : Endpoint()
    object SubmitGoal : Endpoint()
    data class ApprovalPending(val sessionId: String) : Endpoint()
    /** `GET /api/approval/stream` — the per-session approval SSE feed. */
    data class ApprovalStream(val sessionId: String) : Endpoint()
    object ApprovalRespond : Endpoint()
    data class ClarifyPending(val sessionId: String) : Endpoint()
    /** `GET /api/clarify/stream` — the per-session clarification SSE feed. */
    data class ClarifyStream(val sessionId: String) : Endpoint()
    object ClarifyRespond : Endpoint()
    object Btw : Endpoint()
    object Background : Endpoint()
    data class BackgroundStatus(val sessionId: String) : Endpoint()
    object Workspaces : Endpoint()
    data class WorkspaceSuggestions(val prefix: String) : Endpoint()
    data class DirectoryList(val sessionId: String, val filePath: String?) : Endpoint()
    data class File(val sessionId: String, val filePath: String) : Endpoint()
    data class RawFile(val sessionId: String, val filePath: String) : Endpoint()
    data class GitInfo(val sessionId: String) : Endpoint()
    data class GitStatus(val sessionId: String) : Endpoint()
    data class GitBranches(val sessionId: String) : Endpoint()
    data class GitDiff(val sessionId: String, val filePath: String, val kind: String) : Endpoint()
    object GitFetch : Endpoint()
    object GitPull : Endpoint()
    object GitPush : Endpoint()
    object GitCheckout : Endpoint()
    object GitStashCheckout : Endpoint()
    object GitStage : Endpoint()
    object GitUnstage : Endpoint()
    object GitDiscard : Endpoint()
    object GitCommit : Endpoint()
    object GitCommitSelected : Endpoint()
    object GitCommitMessage : Endpoint()
    object GitCommitMessageSelected : Endpoint()
    object Models : Endpoint()
    object ModelsLive : Endpoint()
    object Commands : Endpoint()
    object DefaultModel : Endpoint()
    data class Reasoning(val model: String? = null, val provider: String? = null) : Endpoint()
    object Personalities : Endpoint()
    object SetPersonality : Endpoint()
    object Profiles : Endpoint()
    object SwitchProfile : Endpoint()
    object Providers : Endpoint()
    object Settings : Endpoint()
    object Crons : Endpoint()
    object CronCreate : Endpoint()
    object CronUpdate : Endpoint()
    object CronDelete : Endpoint()
    object CronRun : Endpoint()
    object CronPause : Endpoint()
    object CronResume : Endpoint()
    data class CronStatus(val jobId: String?) : Endpoint()
    data class CronOutput(val jobId: String, val limit: Int?) : Endpoint()
    object CronDeliveryOptions : Endpoint()
    object Memory : Endpoint()
    object Skills : Endpoint()
    data class SkillContent(val name: String, val file: String?) : Endpoint()
    object ToggleSkill : Endpoint()
    object Upload : Endpoint()
    object KanbanConfig : Endpoint()
    object KanbanBoards : Endpoint()
    object KanbanCreateBoard : Endpoint()
    data class KanbanEditBoard(val slug: String) : Endpoint()
    data class KanbanArchiveBoard(val slug: String) : Endpoint()
    data class KanbanSwitchBoard(val slug: String) : Endpoint()
    data class KanbanDispatch(val board: String, val dryRun: Boolean, val maximum: Int) : Endpoint()
    data class KanbanBoard(
        val board: String,
        val tenant: String?,
        val assignee: String?,
        val includeArchived: Boolean,
        val onlyMine: Boolean,
        val since: Int?,
    ) : Endpoint()
    object Tts : Endpoint()
    object Transcribe : Endpoint()
    data class KanbanStats(val board: String) : Endpoint()
    data class KanbanAssignees(val board: String) : Endpoint()
    data class KanbanEvents(val board: String, val since: Int, val limit: Int) : Endpoint()
    data class KanbanCardDetail(val cardId: String, val board: String) : Endpoint()
    data class KanbanWorkerLog(val cardId: String, val board: String, val tailBytes: Int) : Endpoint()
    data class KanbanAddComment(val cardId: String, val board: String) : Endpoint()
    data class KanbanCreateCard(val board: String) : Endpoint()

    /**
     * A route declared outside this file (feature slices add their own in
     * `net/Endpoints+<Feature>.kt` factories) so the sealed hierarchy's
     * exhaustive `when`s stay untouched. [fixedPath] is the literal route;
     * [dynamicSegments] are server-provided identifiers appended one segment
     * each with traversal-safe encoding; [query] is appended verbatim.
     */
    data class Custom(
        val fixedPath: String,
        val dynamicSegments: List<String> = emptyList(),
        val query: List<Pair<String, String>> = emptyList(),
    ) : Endpoint()
    data class KanbanBulkAction(val board: String) : Endpoint()
    data class KanbanEditCard(val cardId: String, val board: String) : Endpoint()
    data class KanbanCardStatus(val cardId: String, val board: String) : Endpoint()
    data class KanbanBlockCard(val cardId: String, val board: String) : Endpoint()
    data class KanbanUnblockCard(val cardId: String, val board: String) : Endpoint()
    data class KanbanAddDependency(val board: String) : Endpoint()
    data class KanbanRemoveDependency(val board: String) : Endpoint()

    val path: String
        get() = when (this) {
            Health -> "/health"
            AuthStatus -> "/api/auth/status"
            Login -> "/api/auth/login"
            Logout -> "/api/auth/logout"
            is Sessions -> "/api/sessions"
            is SessionsSearch -> "/api/sessions/search"
            is Session -> "/api/session"
            is SessionStatus -> "/api/session/status"
            NewSession -> "/api/session/new"
            ImportCliSession -> "/api/session/import_cli"
            DuplicateSession -> "/api/session/duplicate"
            ClearSession -> "/api/session/clear"
            RenameSession -> "/api/session/rename"
            DeleteSession -> "/api/session/delete"
            PinSession -> "/api/session/pin"
            ArchiveSession -> "/api/session/archive"
            BranchSession -> "/api/session/branch"
            TruncateSession -> "/api/session/truncate"
            CompressSession -> "/api/session/compress"
            is GitInfo -> "/api/git-info"
            is GitStatus -> "/api/git/status"
            is GitBranches -> "/api/git/branches"
            is GitDiff -> "/api/git/diff"
            GitFetch -> "/api/git/fetch"
            GitPull -> "/api/git/pull"
            GitPush -> "/api/git/push"
            GitCheckout -> "/api/git/checkout"
            GitStashCheckout -> "/api/git/stash-checkout"
            GitStage -> "/api/git/stage"
            GitUnstage -> "/api/git/unstage"
            GitDiscard -> "/api/git/discard"
            GitCommit -> "/api/git/commit"
            GitCommitSelected -> "/api/git/commit-selected"
            GitCommitMessage -> "/api/git/commit-message"
            GitCommitMessageSelected -> "/api/git/commit-message-selected"
            UndoSession -> "/api/session/undo"
            RetrySession -> "/api/session/retry"
            UpdateSession -> "/api/session/update"
            MoveSession -> "/api/session/move"
            is SessionYolo -> "/api/session/yolo"
            is ExportSession -> "/api/session/export"
            Projects -> "/api/projects"
            CreateProject -> "/api/projects/create"
            RenameProject -> "/api/projects/rename"
            DeleteProject -> "/api/projects/delete"
            ChatStart -> "/api/chat/start"
            is ChatStream -> "/api/chat/stream"
            is ChatCancel -> "/api/chat/cancel"
            is ChatStreamStatus -> "/api/chat/stream/status"
            ChatSteer -> "/api/chat/steer"
            SubmitGoal -> "/api/goal"
            is ApprovalPending -> "/api/approval/pending"
            is ApprovalStream -> "/api/approval/stream"
            ApprovalRespond -> "/api/approval/respond"
            is ClarifyPending -> "/api/clarify/pending"
            is ClarifyStream -> "/api/clarify/stream"
            ClarifyRespond -> "/api/clarify/respond"
            Btw -> "/api/btw"
            Background -> "/api/background"
            is BackgroundStatus -> "/api/background/status"
            Workspaces -> "/api/workspaces"
            is WorkspaceSuggestions -> "/api/workspaces/suggest"
            is DirectoryList -> "/api/list"
            is File -> "/api/file"
            is RawFile -> "/api/file/raw"
            Models -> "/api/models"
            ModelsLive -> "/api/models/live"
            Commands -> "/api/commands"
            DefaultModel -> "/api/default-model"
            is Reasoning -> "/api/reasoning"
            Personalities -> "/api/personalities"
            SetPersonality -> "/api/personality/set"
            Profiles -> "/api/profiles"
            SwitchProfile -> "/api/profile/switch"
            Providers -> "/api/providers"
            Settings -> "/api/settings"
            Crons -> "/api/crons"
            CronCreate -> "/api/crons/create"
            CronUpdate -> "/api/crons/update"
            CronDelete -> "/api/crons/delete"
            CronRun -> "/api/crons/run"
            CronPause -> "/api/crons/pause"
            CronResume -> "/api/crons/resume"
            is CronStatus -> "/api/crons/status"
            is CronOutput -> "/api/crons/output"
            CronDeliveryOptions -> "/api/crons/delivery-options"
            Memory -> "/api/memory"
            Skills -> "/api/skills"
            is SkillContent -> "/api/skills/content"
            ToggleSkill -> "/api/skills/toggle"
            Upload -> "/api/upload"
            KanbanConfig -> "/api/kanban/config"
            KanbanBoards, KanbanCreateBoard -> "/api/kanban/boards"
            is KanbanEditBoard, is KanbanArchiveBoard -> "/api/kanban/boards"
            is KanbanSwitchBoard -> "/api/kanban/boards"
            is KanbanDispatch -> "/api/kanban/dispatch"
            is KanbanBoard -> "/api/kanban/board"
            is KanbanStats -> "/api/kanban/stats"
            is KanbanAssignees -> "/api/kanban/assignees"
            is KanbanEvents -> "/api/kanban/events"
            is KanbanCardDetail, is KanbanEditCard, is KanbanCardStatus -> "/api/kanban/tasks"
            is KanbanWorkerLog -> "/api/kanban/tasks"
            is KanbanAddComment -> "/api/kanban/tasks"
            is KanbanCreateCard -> "/api/kanban/tasks"
            is KanbanBulkAction -> "/api/kanban/tasks/bulk"
            is KanbanBlockCard, is KanbanUnblockCard -> "/api/kanban/tasks"
            is KanbanAddDependency -> "/api/kanban/links"
            is KanbanRemoveDependency -> "/api/kanban/links/delete"
            Tts -> "/api/tts"
            Transcribe -> "/api/transcribe"
            is Custom -> fixedPath
        }

    val queryItems: List<Pair<String, String>>
        get() = when (this) {
            is Sessions -> {
                // Opt-in (issue #17): the server's default response excludes archived
                // rows, so the main list request stays byte-identical when off.
                // `archived_limit` only means something alongside `include_archived=1`
                // (`_query_positive_int` in upstream routes.py), so it is only sent then.
                if (!includeArchived) {
                    emptyList()
                } else {
                    buildList {
                        add("include_archived" to "1")
                        archivedLimit?.let { add("archived_limit" to "$it") }
                    }
                }
            }

            is SessionsSearch -> listOf(
                "q" to query,
                "content" to if (content) "1" else "0",
                "depth" to "$depth",
            )

            is Session -> buildList {
                add("session_id" to id)
                add("messages" to if (includeMessages) "1" else "0")
                messageLimit?.let { add("msg_limit" to "$it") }
                messageBefore?.let { add("msg_before" to "$it") }
                // Opt-in (upstream #3790): on cold load only, ask the server to widen
                // the window until it holds ~msg_limit *renderable* rows so tool-heavy
                // sessions don't open showing 1–2 bubbles. Omitted when false; older
                // servers ignore it.
                if (expandRenderable) add("expand_renderable" to "1")
            }

            is SessionStatus -> listOf("session_id" to id)
            is ChatStream -> buildList {
                add("stream_id" to streamId)
                if (replayAfterSeq != null) {
                    add("replay" to "1")
                    add("after_seq" to "${maxOf(0, replayAfterSeq)}")
                }
            }
            is ChatCancel -> listOf("stream_id" to streamId)
            is ChatStreamStatus -> listOf("stream_id" to streamId)
            is ApprovalPending -> listOf("session_id" to sessionId)
            is ApprovalStream -> listOf("session_id" to sessionId)
            is ClarifyPending -> listOf("session_id" to sessionId)
            is ClarifyStream -> listOf("session_id" to sessionId)
            is BackgroundStatus -> listOf("session_id" to sessionId)
            is SessionYolo -> sessionId?.let { listOf("session_id" to it) } ?: emptyList()

            is DirectoryList -> buildList {
                add("session_id" to sessionId)
                filePath?.let { add("path" to it) }
            }

            is WorkspaceSuggestions -> listOf("prefix" to prefix)
            is File -> listOf("session_id" to sessionId, "path" to filePath)
            is RawFile -> listOf("session_id" to sessionId, "path" to filePath)
            is GitInfo -> listOf("session_id" to sessionId)
            is GitStatus -> listOf("session_id" to sessionId)
            is GitBranches -> listOf("session_id" to sessionId)
            is GitDiff -> listOf("session_id" to sessionId, "path" to filePath, "kind" to kind)

            is CronStatus -> jobId?.let { listOf("job_id" to it) } ?: emptyList()

            is ExportSession -> listOf(
                "session_id" to sessionId,
                "format" to format.rawValue,
            )

            is CronOutput -> buildList {
                add("job_id" to jobId)
                limit?.let { add("limit" to "$it") }
            }

            is Reasoning -> buildList {
                if (!model.isNullOrEmpty()) add("model" to model)
                if (!provider.isNullOrEmpty()) add("provider" to provider)
            }

            is SkillContent -> buildList {
                add("name" to name)
                file?.let { add("file" to it) }
            }
            is KanbanDispatch -> listOf(
                "board" to board,
                "dry_run" to if (dryRun) "true" else "false",
                "max" to maximum.coerceIn(1, 8).toString(),
            )
            is KanbanBoard -> buildList {
                add("board" to board)
                tenant?.takeIf { it.isNotBlank() }?.let { add("tenant" to it) }
                assignee?.takeIf { it.isNotBlank() }?.let { add("assignee" to it) }
                if (includeArchived) add("include_archived" to "true")
                if (onlyMine) add("only_mine" to "true")
                since?.let { add("since" to it.coerceAtLeast(0).toString()) }
            }
            is KanbanStats -> listOf("board" to board)
            is KanbanAssignees -> listOf("board" to board)
            is KanbanEvents -> listOf(
                "board" to board,
                "since" to since.coerceAtLeast(0).toString(),
                "limit" to limit.coerceIn(1, 200).toString(),
            )
            is KanbanCardDetail -> listOf("board" to board)
            is KanbanWorkerLog -> listOf("board" to board, "tail" to tailBytes.coerceIn(1, 2_000_000).toString())
            is KanbanAddComment -> listOf("board" to board)
            is KanbanCreateCard -> listOf("board" to board)
            is KanbanBulkAction -> listOf("board" to board)
            is KanbanEditCard -> listOf("board" to board)
            is KanbanCardStatus -> listOf("board" to board)
            is KanbanBlockCard -> listOf("board" to board)
            is KanbanUnblockCard -> listOf("board" to board)
            is KanbanAddDependency -> listOf("board" to board)
            is KanbanRemoveDependency -> listOf("board" to board)
            is Custom -> query

            else -> emptyList()
        }

    /**
     * Joins [path] onto [baseUrl], preserving any path prefix the base URL
     * carries (a reverse-proxied deployment like `https://host/hermes`), then
     * appends the query items.
     */
    fun url(baseUrl: HttpUrl): HttpUrl {
        val builder = when (this) {
            is KanbanEditBoard -> baseUrl.newBuilder().addPathSegments("api/kanban/boards").addDynamicPathSegment(slug)
            is KanbanArchiveBoard -> baseUrl.newBuilder().addPathSegments("api/kanban/boards").addDynamicPathSegment(slug)
            is KanbanSwitchBoard -> baseUrl.newBuilder().addPathSegments("api/kanban/boards").addDynamicPathSegment(slug).addPathSegment("switch")
            is KanbanCardDetail -> baseUrl.newBuilder().addPathSegments("api/kanban/tasks").addDynamicPathSegment(cardId)
            is KanbanWorkerLog -> baseUrl.newBuilder().addPathSegments("api/kanban/tasks").addDynamicPathSegment(cardId).addPathSegment("log")
            is KanbanAddComment -> baseUrl.newBuilder().addPathSegments("api/kanban/tasks").addDynamicPathSegment(cardId).addPathSegment("comments")
            is KanbanEditCard -> baseUrl.newBuilder().addPathSegments("api/kanban/tasks").addDynamicPathSegment(cardId)
            is KanbanCardStatus -> baseUrl.newBuilder().addPathSegments("api/kanban/tasks").addDynamicPathSegment(cardId)
            is KanbanBlockCard -> baseUrl.newBuilder().addPathSegments("api/kanban/tasks").addDynamicPathSegment(cardId).addPathSegment("block")
            is KanbanUnblockCard -> baseUrl.newBuilder().addPathSegments("api/kanban/tasks").addDynamicPathSegment(cardId).addPathSegment("unblock")
            is Custom -> baseUrl.newBuilder().addPathSegments(fixedPath.removePrefix("/")).also { b ->
                dynamicSegments.forEach { b.addDynamicPathSegment(it) }
            }
            else -> baseUrl.newBuilder().addPathSegments(path.removePrefix("/"))
        }
        for ((name, value) in queryItems) {
            val encoded = java.net.URLEncoder.encode(value, "UTF-8").replace("%2F", "/").replace("+", "%20")
            builder.addEncodedQueryParameter(name, encoded)
        }
        return builder.build()
    }
}

private fun HttpUrl.Builder.addDynamicPathSegment(value: String): HttpUrl.Builder {
    require(value.isNotBlank()) { "Dynamic path segment cannot be blank" }
    require(value != "." && value != "..") { "Dynamic path segment cannot be dot traversal" }
    val encoded = java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    return addEncodedPathSegment(encoded)
}
