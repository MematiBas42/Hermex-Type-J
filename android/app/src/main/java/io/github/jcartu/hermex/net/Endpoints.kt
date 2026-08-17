package io.github.jcartu.hermex.net

import io.github.jcartu.hermex.model.SessionExportFormat
import okhttp3.HttpUrl

/**
 * Port of `Networking/Endpoints.swift` — one case per server route, carrying
 * exactly the query parameters the Swift enum builds. Git, Kanban, TTS, and
 * transcribe routes are out of the Android v1 scope and are intentionally
 * absent.
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
    data class ChatStream(val streamId: String) : Endpoint()
    data class ChatCancel(val streamId: String) : Endpoint()
    data class ChatStreamStatus(val streamId: String) : Endpoint()
    object ChatSteer : Endpoint()
    object SubmitGoal : Endpoint()
    data class ApprovalPending(val sessionId: String) : Endpoint()
    object ApprovalRespond : Endpoint()
    data class ClarifyPending(val sessionId: String) : Endpoint()
    object ClarifyRespond : Endpoint()
    object Btw : Endpoint()
    object Background : Endpoint()
    data class BackgroundStatus(val sessionId: String) : Endpoint()
    object Workspaces : Endpoint()
    data class WorkspaceSuggestions(val prefix: String) : Endpoint()
    data class DirectoryList(val sessionId: String, val filePath: String?) : Endpoint()
    data class File(val sessionId: String, val filePath: String) : Endpoint()
    data class RawFile(val sessionId: String, val filePath: String) : Endpoint()
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
            RenameSession -> "/api/session/rename"
            DeleteSession -> "/api/session/delete"
            PinSession -> "/api/session/pin"
            ArchiveSession -> "/api/session/archive"
            BranchSession -> "/api/session/branch"
            TruncateSession -> "/api/session/truncate"
            CompressSession -> "/api/session/compress"
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
            ApprovalRespond -> "/api/approval/respond"
            is ClarifyPending -> "/api/clarify/pending"
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
            is ChatStream -> listOf("stream_id" to streamId)
            is ChatCancel -> listOf("stream_id" to streamId)
            is ChatStreamStatus -> listOf("stream_id" to streamId)
            is ApprovalPending -> listOf("session_id" to sessionId)
            is ClarifyPending -> listOf("session_id" to sessionId)
            is BackgroundStatus -> listOf("session_id" to sessionId)
            is SessionYolo -> sessionId?.let { listOf("session_id" to it) } ?: emptyList()

            is DirectoryList -> buildList {
                add("session_id" to sessionId)
                filePath?.let { add("path" to it) }
            }

            is WorkspaceSuggestions -> listOf("prefix" to prefix)
            is File -> listOf("session_id" to sessionId, "path" to filePath)
            is RawFile -> listOf("session_id" to sessionId, "path" to filePath)

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

            else -> emptyList()
        }

    /**
     * Joins [path] onto [baseUrl], preserving any path prefix the base URL
     * carries (a reverse-proxied deployment like `https://host/hermes`), then
     * appends the query items.
     */
    fun url(baseUrl: HttpUrl): HttpUrl {
        val builder = baseUrl.newBuilder()
            .addPathSegments(path.removePrefix("/"))
        for ((name, value) in queryItems) {
            // addQueryParameter percent-encodes '/', but path-like values (workspace
            // prefixes, file paths) must stay literal to match the Swift endpoints
            // and upstream's expectations, so encode everything except '/'.
            val encoded = java.net.URLEncoder.encode(value, "UTF-8").replace("%2F", "/").replace("+", "%20")
            builder.addEncodedQueryParameter(name, encoded)
        }
        return builder.build()
    }
}
