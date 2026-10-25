package io.github.jcartu.hermex.net

import io.github.jcartu.hermex.core.prefs.ServerConfig
import io.github.jcartu.hermex.model.ApprovalChoice
import io.github.jcartu.hermex.model.ApprovalPendingResponse
import io.github.jcartu.hermex.model.ApprovalRespondResponse
import io.github.jcartu.hermex.model.AuthStatusResponse
import io.github.jcartu.hermex.model.BackgroundStartResponse
import io.github.jcartu.hermex.model.BackgroundStatusResponse
import io.github.jcartu.hermex.model.BtwStartResponse
import io.github.jcartu.hermex.model.ChatCancelResponse
import io.github.jcartu.hermex.model.ChatStartResponse
import io.github.jcartu.hermex.model.ChatStreamStatusResponse
import io.github.jcartu.hermex.model.ChatSteerResponse
import io.github.jcartu.hermex.model.ClarificationPendingResponse
import io.github.jcartu.hermex.model.ClarificationRespondResponse
import io.github.jcartu.hermex.model.CommandsResponse
import io.github.jcartu.hermex.model.CronJobsResponse
import io.github.jcartu.hermex.model.CronDeliveryOptionsResponse
import io.github.jcartu.hermex.model.CronMutationResponse
import io.github.jcartu.hermex.model.CronOutputResponse
import io.github.jcartu.hermex.model.CronStatusResponse
import io.github.jcartu.hermex.model.DefaultModelResponse
import io.github.jcartu.hermex.model.DirectoryListResponse
import io.github.jcartu.hermex.model.FileResponse
import io.github.jcartu.hermex.model.HealthResponse
import io.github.jcartu.hermex.model.LoginResponse
import io.github.jcartu.hermex.model.GoalSubmissionResponse
import io.github.jcartu.hermex.model.MemoryResponse
import io.github.jcartu.hermex.model.GitBranchesResponse
import io.github.jcartu.hermex.model.GitCheckoutResponse
import io.github.jcartu.hermex.model.GitCheckoutTarget
import io.github.jcartu.hermex.model.GitCommitMessageResponse
import io.github.jcartu.hermex.model.GitCommitResponse
import io.github.jcartu.hermex.model.GitDiffResponse
import io.github.jcartu.hermex.model.GitInfoResponse
import io.github.jcartu.hermex.model.GitMutationResponse
import io.github.jcartu.hermex.model.GitRemoteActionResponse
import io.github.jcartu.hermex.model.GitStatusResponse
import io.github.jcartu.hermex.model.KanbanAddCommentRequest
import io.github.jcartu.hermex.model.KanbanAddCommentResponse
import io.github.jcartu.hermex.model.KanbanAssigneeHistory
import io.github.jcartu.hermex.model.KanbanBoardMutationEnvelope
import io.github.jcartu.hermex.model.KanbanBoardRequest
import io.github.jcartu.hermex.model.KanbanBoardSnapshot
import io.github.jcartu.hermex.model.KanbanBoardsResponse
import io.github.jcartu.hermex.model.KanbanBulkActionEnvelope
import io.github.jcartu.hermex.model.KanbanBulkActionRequest
import io.github.jcartu.hermex.model.KanbanBulkActionType
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
import io.github.jcartu.hermex.model.ModelsResponse
import io.github.jcartu.hermex.model.ModelsLiveResponse
import io.github.jcartu.hermex.model.SessionYoloResponse
import io.github.jcartu.hermex.model.PersonalitiesResponse
import io.github.jcartu.hermex.model.PersonalitySetResponse
import io.github.jcartu.hermex.model.ProfileSwitchResponse
import io.github.jcartu.hermex.model.ProfilesResponse
import io.github.jcartu.hermex.model.ProjectMutationResponse
import io.github.jcartu.hermex.model.ProjectsResponse
import io.github.jcartu.hermex.model.ProvidersResponse
import io.github.jcartu.hermex.model.ReasoningStatusResponse
import io.github.jcartu.hermex.model.SessionBranchResponse
import io.github.jcartu.hermex.model.SessionCompressResponse
import io.github.jcartu.hermex.model.SessionMutationResponse
import io.github.jcartu.hermex.model.SessionResponse
import io.github.jcartu.hermex.model.SessionSearchResponse
import io.github.jcartu.hermex.model.SessionRetryResponse
import io.github.jcartu.hermex.model.SessionUndoResponse
import io.github.jcartu.hermex.model.TranscribeResponse
import io.github.jcartu.hermex.model.ToggleSkillRequest
import io.github.jcartu.hermex.model.ToggleSkillResponse
import io.github.jcartu.hermex.model.SessionStatusResponse
import io.github.jcartu.hermex.model.SessionsResponse
import io.github.jcartu.hermex.model.SessionExportFile
import io.github.jcartu.hermex.model.SessionExportFormat
import io.github.jcartu.hermex.model.SettingsResponse
import io.github.jcartu.hermex.model.SkillDetailResponse
import io.github.jcartu.hermex.model.SkillsResponse
import io.github.jcartu.hermex.model.UploadResponse
import io.github.jcartu.hermex.model.WorkspaceSuggestionsResponse
import io.github.jcartu.hermex.model.WorkspacesResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.decodeFromString
import io.github.jcartu.hermex.model.JSONValue
import io.github.jcartu.hermex.model.SessionDetail
import kotlinx.serialization.encodeToString
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Port of `Networking/APIClient.swift` and its in-scope extensions
 * (`+Sessions`, `+Projects`, `+Chat`, `+Cron`, `+Skills`, `+Memory`,
 * `+ServerPanels`, `+Workspace`, `+Upload`, `+SessionExport`).
 *
 * Wire contract, mirrored from Swift:
 * - Request bodies encode with snake_case keys and omit null fields
 *   (Swift `convertToSnakeCase` + `encodeIfPresent`; here `@SerialName`
 *   + `explicitNulls = false` on the shared [json]).
 * - HTTP 401 maps to [ApiError.Unauthorized]; any other non-2xx maps to
 *   [ApiError.Http] carrying the raw body; transport failures map to
 *   [ApiError.Network]; undecodable 2xx bodies map to [ApiError.Decoding].
 * - `Origin`/`Referer` are NEVER set — the upstream CSRF check treats
 *   requests without them as non-browser clients (PROJECT_SPEC.md §6.1).
 *
 * Auth is cookie-based via the shared cookie jar on [httpClient]. The base
 * URL is read from [configProvider] per request so a server switch applies
 * without rebuilding the client.
 */
class HermesApi(
    httpClient: OkHttpClient,
    @PublishedApi internal val json: Json,
    private val configProvider: suspend () -> ServerConfig,
    private val customHeaderProvider: () -> List<CustomHeader> = { CustomHeaderStore.shared.snapshot() },
) {
    /**
     * Last successfully resolved base URL, read by the redirect guard — the
     * network interceptor runs mid-request, after [resolveBaseUrl] stored it.
     */
    @Volatile
    private var lastBaseUrl: HttpUrl? = null

    /**
     * The caller's client plus the cross-origin redirect guard (#277): when the
     * server redirects a same-origin request off-origin, the user's custom
     * headers (which may be secrets) are stripped from the follow-up hop.
     */
    private val client: OkHttpClient = httpClient.newBuilder()
        .addNetworkInterceptor(
            CrossOriginHeaderStripper(
                baseUrlProvider = { lastBaseUrl },
                customHeaderProvider = customHeaderProvider,
            ),
        )
        .build()

    // MARK: Auth (APIClient.swift)

    suspend fun health(): HealthResponse = get(Endpoint.Health)

    suspend fun authStatus(): AuthStatusResponse = get(Endpoint.AuthStatus)

    suspend fun login(password: String): LoginResponse =
        post(Endpoint.Login, LoginRequest(password))

    suspend fun logout(): LoginResponse = post(Endpoint.Logout, EmptyBody())

    // MARK: Sessions (APIClient+Sessions.swift)

    /**
     * Fetches the session list. [includeArchived] opts in to archived rows
     * (merged with the visible ones; each row carries an `archived` flag) and
     * [archivedLimit] optionally caps how many archived rows the server
     * appends (issue #17). Defaults keep the request byte-identical to the
     * plain list fetch.
     */
    suspend fun sessions(includeArchived: Boolean = false, archivedLimit: Int? = null): SessionsResponse =
        get(Endpoint.Sessions(includeArchived, archivedLimit))

    suspend fun searchSessions(query: String, content: Boolean = true, depth: Int = 5): SessionSearchResponse =
        get(Endpoint.SessionsSearch(query, content, depth))

    suspend fun session(
        id: String,
        includeMessages: Boolean = true,
        messageLimit: Int? = 50,
        messageBefore: Int? = null,
        expandRenderable: Boolean = false,
    ): SessionResponse =
        get(Endpoint.Session(id, includeMessages, messageLimit, messageBefore, expandRenderable))

    suspend fun sessionStatus(id: String): SessionStatusResponse =
        get(Endpoint.SessionStatus(id))

    /**
     * Creates a session. A null [projectId] leaves `project_id` out of the body,
     * so the session starts unassigned; the server stores a given id without
     * checking it (#875: chats started under a project filter join that project).
     */
    suspend fun createSession(
        workspace: String?,
        model: String?,
        modelProvider: String?,
        profile: String?,
        projectId: String? = null,
    ): SessionResponse =
        post(Endpoint.NewSession, NewSessionRequest(workspace, model, modelProvider, profile, projectId))

    /**
     * Imports a CLI or messaging session into the WebUI-owned session store
     * (`POST /api/session/import_cli`). The returned session is authoritative
     * for whether continuation is safe.
     */
    suspend fun importExternalSession(id: String): SessionResponse =
        post(Endpoint.ImportCliSession, SessionIdRequest(id))

    /**
     * Copies a session (`POST /api/session/duplicate`): independent messages,
     * tool calls and usage counters, no fork lineage (#25). Answers with the
     * whole duplicated session, so no follow-up fetch is needed.
     */
    suspend fun duplicateSession(id: String): SessionResponse =
        post(Endpoint.DuplicateSession, SessionIdRequest(id))

    /**
     * Truncates the session to empty on the server and resets its title to
     * Untitled (`POST /api/session/clear`, #389). Destructive and irreversible —
     * only call it behind a confirmation. Answers the compact session shape.
     */
    suspend fun clearSession(id: String): SessionMutationResponse =
        post(Endpoint.ClearSession, SessionIdRequest(id))

    suspend fun renameSession(id: String, title: String): SessionMutationResponse =
        post(Endpoint.RenameSession, RenameSessionRequest(id, title))

    suspend fun deleteSession(id: String): SessionMutationResponse =
        post(Endpoint.DeleteSession, SessionIdRequest(id))

    suspend fun pinSession(id: String, pinned: Boolean): SessionMutationResponse =
        post(Endpoint.PinSession, PinSessionRequest(id, pinned))

    suspend fun archiveSession(id: String, archived: Boolean): SessionMutationResponse =
        post(Endpoint.ArchiveSession, ArchiveSessionRequest(id, archived))

    suspend fun branchSession(id: String, keepCount: Int? = null, title: String? = null): SessionBranchResponse =
        post(Endpoint.BranchSession, BranchSessionRequest(id, keepCount, title))

    suspend fun truncateSession(id: String, keepCount: Int): SessionResponse =
        post(Endpoint.TruncateSession, TruncateSessionRequest(id, keepCount))

    suspend fun compressSession(id: String, focusTopic: String? = null): SessionCompressResponse =
        post(Endpoint.CompressSession, CompressSessionRequest(id, focusTopic))

    suspend fun undoSession(id: String): SessionUndoResponse =
        post(Endpoint.UndoSession, SessionIdRequest(id))

    suspend fun retrySession(id: String): SessionRetryResponse =
        post(Endpoint.RetrySession, SessionIdRequest(id))

    suspend fun updateSession(
        id: String,
        workspace: String?,
        model: String?,
        modelProvider: String?,
    ): SessionResponse {
        return try {
            post(
                Endpoint.UpdateSession,
                UpdateSessionRequest(
                    sessionId = id,
                    workspace = workspace,
                    model = model,
                    modelProvider = modelProvider,
                ),
            )
        } catch (e: ApiError.Http) {
            if ((e.statusCode == 404 || e.statusCode == 405) && model != null) {
                setHermesModel(model, modelProvider)
                SessionResponse(
                    session = SessionDetail(
                        sessionId = id,
                        model = model,
                        modelProvider = modelProvider,
                        workspace = workspace,
                    ),
                )
            } else {
                throw e
            }
        }
    }

    suspend fun setHermesModel(model: String, provider: String? = null, scope: String = "main"): String {
        val payload = buildJsonObject {
            put("model", JsonPrimitive(model))
            provider?.let { put("provider", JsonPrimitive(it)) }
            put("scope", JsonPrimitive(scope))
        }
        val body = payload.toString().toRequestBody(JSON_MEDIA_TYPE)
        return sendForBody(Endpoint.ModelSet, "POST", body)
    }

    suspend fun moveSession(id: String, projectId: String?): SessionMutationResponse =
        post(Endpoint.MoveSession, MoveSessionRequest(id, projectId))

    suspend fun sessionYolo(sessionId: String): SessionYoloResponse =
        get(Endpoint.SessionYolo(sessionId))

    suspend fun setSessionYolo(sessionId: String, enabled: Boolean): SessionYoloResponse =
        post(Endpoint.SessionYolo(sessionId = null), SessionYoloRequest(sessionId, enabled))

    /**
     * Downloads a session transcript via `GET /api/session/export`
     * (`session_id` + `format` query params). The response is a file download
     * (`text/html` or `application/json`), not a JSON envelope, so this
     * bypasses the decoding helpers like the Swift client does.
     *
     * The filename comes from the server's `Content-Disposition` header
     * (upstream sends `attachment; filename="hermes-<sid>.<ext>"`); if that
     * header is missing or unparsable, it falls back to a sanitized
     * `<title-or-id>.<ext>`. Errors reuse the standard mapping: 401 →
     * [ApiError.Unauthorized], other non-2xx (400 missing param, 404 unknown/
     * foreign-profile session) → [ApiError.Http] carrying the error body.
     */
    suspend fun exportSession(
        id: String,
        format: SessionExportFormat,
        fallbackTitle: String? = null,
    ): SessionExportFile {
        val builder = Request.Builder()
            .url(Endpoint.ExportSession(id, format).url(resolveBaseUrl()))
            .get()
        customHeaderProvider().applyTo(builder)
        // The 2xx response is a file download (text/html or application/json),
        // so don't claim we only accept JSON.
        builder.header("Accept", "*/*")

        execute(builder.build()).use { response ->
            val bytes = withContext(Dispatchers.IO) {
                try {
                    response.body?.bytes() ?: ByteArray(0)
                } catch (error: IOException) {
                    throw ApiError.Network(error)
                }
            }

            if (response.code == 401) throw ApiError.Unauthorized
            if (response.code !in 200..299) {
                throw ApiError.Http(response.code, bytes.toString(Charsets.UTF_8))
            }

            return SessionExportFile(
                data = bytes,
                filename = SessionExportFile.filename(
                    contentDisposition = response.header("Content-Disposition"),
                    fallbackTitle = fallbackTitle,
                    sessionId = id,
                    format = format,
                ),
            )
        }
    }

    // MARK: Projects (APIClient+Projects.swift)

    suspend fun projects(): ProjectsResponse = get(Endpoint.Projects)

    suspend fun createProject(name: String, color: String?): ProjectMutationResponse =
        post(Endpoint.CreateProject, CreateProjectRequest(name, color))

    suspend fun renameProject(id: String, name: String, color: String?): ProjectMutationResponse =
        post(Endpoint.RenameProject, RenameProjectRequest(id, name, color))

    suspend fun deleteProject(id: String): ProjectMutationResponse =
        post(Endpoint.DeleteProject, ProjectIdRequest(id))

    // MARK: Chat (APIClient+Chat.swift)

    suspend fun startChat(
        sessionId: String,
        message: String,
        workspace: String?,
        model: String?,
        modelProvider: String? = null,
        profile: String? = null,
        explicitModelPick: Boolean = false,
        attachments: List<JsonElement>? = null,
    ): ChatStartResponse = post(
        Endpoint.ChatStart,
        ChatStartRequest(
            sessionId = sessionId,
            message = message,
            workspace = workspace,
            model = model,
            modelProvider = modelProvider,
            profile = profile,
            explicitModelPick = if (explicitModelPick) true else null,
            attachments = attachments,
        ),
    )

    /**
     * URL for the chat SSE stream. With [replayAfterSeq] the server replays
     * buffered events after that sequence number before going live
     * (`replay=1&after_seq=N`), so a reconnect never re-sends the user message.
     */
    suspend fun chatStreamUrl(streamId: String, replayAfterSeq: Int? = null): HttpUrl =
        Endpoint.ChatStream(streamId, replayAfterSeq).url(resolveBaseUrl())

    /** URL for the per-session approval SSE feed (`APIClient+Chat.swift` `approvalStreamURL`). */
    suspend fun approvalStreamUrl(sessionId: String): HttpUrl =
        Endpoint.ApprovalStream(sessionId).url(resolveBaseUrl())

    /** URL for the per-session clarification SSE feed (`clarifyStreamURL`). */
    suspend fun clarifyStreamUrl(sessionId: String): HttpUrl =
        Endpoint.ClarifyStream(sessionId).url(resolveBaseUrl())

    suspend fun cancelChat(streamId: String): ChatCancelResponse =
        get(Endpoint.ChatCancel(streamId))

    suspend fun chatStreamStatus(streamId: String): ChatStreamStatusResponse =
        get(Endpoint.ChatStreamStatus(streamId))

    /**
     * Steers the running turn. The server either injects the steer text into
     * the active stream (`accepted: true`) or reports how it fell back
     * (`fallback`, e.g. queued as a follow-up message). [steerChatOutcome]
     * classifies the answer the way `ChatViewModel` acts on it.
     */
    suspend fun steerChat(sessionId: String, text: String): ChatSteerResponse =
        post(Endpoint.ChatSteer, ChatSteerRequest(sessionId, text))

    /**
     * Port of `APIClient.steerChat(sessionID:text:) -> ChatSteerOutcome`: steers
     * the session's active run and reports what became of the hint. Never
     * throws — a failed request is a [ChatSteerOutcome.Refused] carrying its error.
     */
    suspend fun steerChatOutcome(sessionId: String, text: String): ChatSteerOutcome {
        val response = try {
            steerChat(sessionId, text)
        } catch (error: ApiError) {
            return ChatSteerOutcome.Refused(transportError = error)
        }
        return ChatSteerOutcome.classify(response)
    }

    suspend fun approvalPending(sessionId: String): ApprovalPendingResponse =
        get(Endpoint.ApprovalPending(sessionId))

    suspend fun respondApproval(
        sessionId: String,
        choice: ApprovalChoice,
        approvalId: String?,
    ): ApprovalRespondResponse =
        post(Endpoint.ApprovalRespond, ApprovalRespondRequest(sessionId, choice, approvalId))

    suspend fun clarifyPending(sessionId: String): ClarificationPendingResponse =
        get(Endpoint.ClarifyPending(sessionId))

    suspend fun respondClarification(
        sessionId: String,
        response: String,
        clarifyId: String?,
    ): ClarificationRespondResponse =
        post(Endpoint.ClarifyRespond, ClarificationRespondRequest(sessionId, response, clarifyId))

    suspend fun startBtw(sessionId: String, question: String): BtwStartResponse =
        post(Endpoint.Btw, BtwRequest(sessionId, question))

    suspend fun submitGoal(
        sessionId: String,
        args: String,
        workspace: String?,
        model: String?,
        modelProvider: String?,
        profile: String?,
    ): GoalSubmissionResponse = post(
        Endpoint.SubmitGoal,
        GoalSubmissionRequest(
            sessionId = sessionId,
            args = args,
            workspace = workspace,
            model = model,
            modelProvider = modelProvider,
            profile = profile,
        ),
    )

    suspend fun startBackground(sessionId: String, prompt: String): BackgroundStartResponse =
        post(Endpoint.Background, BackgroundRequest(sessionId, prompt))

    suspend fun backgroundStatus(sessionId: String): BackgroundStatusResponse =
        get(Endpoint.BackgroundStatus(sessionId))

    // MARK: Crons (APIClient+Cron.swift)

    suspend fun crons(): CronJobsResponse = get(Endpoint.Crons)

    suspend fun createCron(
        prompt: String,
        schedule: String,
        name: String?,
        deliver: String?,
        skills: List<String>,
        model: String?,
        provider: String?,
        profile: String?,
        toastNotifications: Boolean,
    ): CronMutationResponse = post(
        Endpoint.CronCreate,
        CronCreateRequest(
            prompt = prompt,
            schedule = schedule,
            name = name,
            deliver = deliver,
            skills = skills,
            model = model,
            provider = provider,
            profile = profile,
            toastNotifications = toastNotifications,
        ),
    )

    suspend fun updateCron(
        jobId: String,
        prompt: String?,
        schedule: String?,
        name: String?,
        deliver: String?,
        skills: List<String>?,
        model: String?,
        provider: String?,
        profile: String?,
        toastNotifications: Boolean?,
    ): CronMutationResponse = post(
        Endpoint.CronUpdate,
        CronUpdateRequest(
            jobId = jobId,
            prompt = prompt,
            schedule = schedule,
            name = name,
            deliver = deliver,
            skills = skills,
            model = model,
            provider = provider,
            profile = profile,
            toastNotifications = toastNotifications,
        ),
    )

    suspend fun deleteCron(jobId: String): CronMutationResponse =
        post(Endpoint.CronDelete, CronJobIdRequest(jobId, reason = null))

    suspend fun runCron(jobId: String): CronMutationResponse =
        post(Endpoint.CronRun, CronJobIdRequest(jobId, reason = null))

    suspend fun pauseCron(jobId: String, reason: String? = null): CronMutationResponse =
        post(Endpoint.CronPause, CronJobIdRequest(jobId, reason))

    suspend fun resumeCron(jobId: String): CronMutationResponse =
        post(Endpoint.CronResume, CronJobIdRequest(jobId, reason = null))

    suspend fun cronDeliveryOptions(): CronDeliveryOptionsResponse =
        get(Endpoint.CronDeliveryOptions)

    suspend fun cronStatus(jobId: String? = null): CronStatusResponse =
        get(Endpoint.CronStatus(jobId))

    suspend fun cronOutput(jobId: String, limit: Int? = 5): CronOutputResponse =
        get(Endpoint.CronOutput(jobId, limit))

    // MARK: Skills (APIClient+Skills.swift)

    suspend fun skills(): SkillsResponse = get(Endpoint.Skills)

    suspend fun skillContent(name: String, file: String? = null): SkillDetailResponse =
        get(Endpoint.SkillContent(name, file))

    suspend fun toggleSkill(name: String, enabled: Boolean): ToggleSkillResponse =
        post(Endpoint.ToggleSkill, ToggleSkillRequest(name, enabled))

    // MARK: Memory (APIClient+Memory.swift)

    suspend fun memory(): MemoryResponse = get(Endpoint.Memory)

    // MARK: Server panels (APIClient+ServerPanels.swift)

    suspend fun models(): ModelsResponse {
        return try {
            get(Endpoint.Models)
        } catch (e: ApiError.Http) {
            if (e.statusCode == 404) {
                fetchHermesModelOptions()
            } else {
                throw e
            }
        }
    }

    private suspend fun fetchHermesModelOptions(): ModelsResponse {
        val jsonStr = sendForBody(Endpoint.ModelOptions, "GET", null)
        val root = json.parseToJsonElement(jsonStr).jsonObject
        val defaultModel = root["model"]?.jsonPrimitive?.contentOrNull
        val activeProvider = root["provider"]?.jsonPrimitive?.contentOrNull

        val providersArray = root["providers"]?.let { if (it is JsonArray) it else null } ?: JsonArray(emptyList())
        val groups = providersArray.mapNotNull { provElem ->
            val provObj = (provElem as? JsonObject) ?: return@mapNotNull null
            val slug = provObj["slug"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val name = provObj["name"]?.jsonPrimitive?.contentOrNull ?: slug
            val modelsArray = provObj["models"]?.let { if (it is JsonArray) it else null }
            val modelsList = modelsArray?.mapNotNull { modelElem ->
                val modelId = modelElem.jsonPrimitive.contentOrNull ?: return@mapNotNull null
                buildJsonObject {
                    put("id", JsonPrimitive(modelId))
                    put("name", JsonPrimitive(modelId))
                    put("provider_id", JsonPrimitive(slug))
                }
            } ?: emptyList()

            buildJsonObject {
                put("provider_id", JsonPrimitive(slug))
                put("name", JsonPrimitive(name))
                put("models", JsonArray(modelsList))
            }
        }

        return ModelsResponse(
            groups = groups.map { JSONValue(it) },
            defaultModel = defaultModel,
            activeProvider = activeProvider,
        )
    }

    /**
     * Live (uncached) model list for the active provider. The server resolves
     * the provider itself when no `provider` param is sent and echoes it back,
     * so callers can match the result against the cached catalog's groups.
     */
    suspend fun modelsLive(): ModelsLiveResponse = get(Endpoint.ModelsLive)

    suspend fun commands(): CommandsResponse = get(Endpoint.Commands)

    suspend fun saveDefaultModel(model: String): DefaultModelResponse =
        post(Endpoint.DefaultModel, DefaultModelRequest(model))

    /**
     * Reasoning status for a specific model/provider (`GET /api/reasoning`).
     * Passing the session's current model + provider makes `supported_efforts`
     * model-accurate (mirrors the upstream WebUI composer chip, issue #18);
     * with no params the server resolves the config default model instead.
     */
    suspend fun reasoning(model: String? = null, provider: String? = null): ReasoningStatusResponse =
        get(Endpoint.Reasoning(model, provider))

    /**
     * Saves the reasoning effort. With [sessionId] the change is scoped to that
     * session (`ReasoningEffortRequest(effort:sessionId:)` in
     * `APIClient+ServerPanels.swift`); without it the server's global default moves.
     */
    suspend fun saveReasoningEffort(effort: String, sessionId: String? = null): ReasoningStatusResponse =
        post(Endpoint.Reasoning(), ReasoningEffortRequest(effort, sessionId))

    suspend fun saveReasoningDisplay(display: String): ReasoningStatusResponse =
        post(Endpoint.Reasoning(), ReasoningDisplayRequest(display))

    suspend fun personalities(): PersonalitiesResponse = get(Endpoint.Personalities)

    suspend fun setPersonality(sessionId: String, name: String): PersonalitySetResponse =
        post(Endpoint.SetPersonality, PersonalitySetRequest(sessionId, name))

    suspend fun profiles(): ProfilesResponse = get(Endpoint.Profiles)

    suspend fun switchProfile(name: String): ProfileSwitchResponse =
        post(Endpoint.SwitchProfile, ProfileSwitchRequest(name))

    suspend fun providers(): ProvidersResponse = get(Endpoint.Providers)

    suspend fun settings(): SettingsResponse = get(Endpoint.Settings)

    // MARK: Git workspace (APIClient+Git.swift)

    suspend fun gitInfo(sessionId: String): GitInfoResponse =
        get(Endpoint.GitInfo(sessionId))

    suspend fun gitStatus(sessionId: String): GitStatusResponse =
        get(Endpoint.GitStatus(sessionId))

    suspend fun gitBranches(sessionId: String): GitBranchesResponse =
        get(Endpoint.GitBranches(sessionId))

    suspend fun gitDiff(sessionId: String, path: String, kind: String = "unstaged"): GitDiffResponse =
        get(Endpoint.GitDiff(sessionId, path, kind))

    suspend fun gitFetch(sessionId: String): GitRemoteActionResponse =
        post(Endpoint.GitFetch, GitSessionRequest(sessionId))

    suspend fun gitPull(sessionId: String): GitRemoteActionResponse =
        post(Endpoint.GitPull, GitSessionRequest(sessionId))

    suspend fun gitPush(sessionId: String): GitRemoteActionResponse =
        post(Endpoint.GitPush, GitSessionRequest(sessionId))

    suspend fun gitCheckout(sessionId: String, target: GitCheckoutTarget): GitCheckoutResponse =
        post(Endpoint.GitCheckout, GitCheckoutRequest(sessionId, target, includesDirtyMode = true))

    suspend fun gitStashCheckout(sessionId: String, target: GitCheckoutTarget): GitCheckoutResponse =
        post(Endpoint.GitStashCheckout, GitCheckoutRequest(sessionId, target, includesDirtyMode = false))

    suspend fun gitStage(sessionId: String, paths: List<String>): GitMutationResponse =
        post(Endpoint.GitStage, GitPathsRequest(sessionId, paths))

    suspend fun gitUnstage(sessionId: String, paths: List<String>): GitMutationResponse =
        post(Endpoint.GitUnstage, GitPathsRequest(sessionId, paths))

    suspend fun gitDiscard(
        sessionId: String,
        paths: List<String>,
        deleteUntracked: Boolean = false,
    ): GitMutationResponse =
        post(Endpoint.GitDiscard, GitDiscardRequest(sessionId, paths, deleteUntracked))

    suspend fun gitCommit(sessionId: String, message: String): GitCommitResponse =
        post(Endpoint.GitCommit, GitCommitRequest(sessionId, message))

    suspend fun gitCommitSelected(sessionId: String, message: String, paths: List<String>): GitCommitResponse =
        post(Endpoint.GitCommitSelected, GitCommitSelectedRequest(sessionId, message, paths))

    suspend fun gitCommitMessage(sessionId: String): GitCommitMessageResponse =
        post(Endpoint.GitCommitMessage, GitSessionRequest(sessionId))

    suspend fun gitCommitMessageSelected(sessionId: String, paths: List<String>): GitCommitMessageResponse =
        post(Endpoint.GitCommitMessageSelected, GitPathsRequest(sessionId, paths))

    // MARK: Kanban (APIClient+Kanban.swift)

    suspend fun kanbanConfiguration(): KanbanConfiguration =
        kanbanRequest(Endpoint.KanbanConfig)

    suspend fun kanbanBoards(): KanbanBoardsResponse =
        kanbanRequest(Endpoint.KanbanBoards)

    suspend fun createKanbanBoard(request: KanbanCreateBoardRequest): KanbanBoardMutationEnvelope =
        kanbanBody(Endpoint.KanbanCreateBoard, "POST", KanbanCreateBoardBody(request))

    suspend fun editKanbanBoard(request: KanbanEditBoardRequest): KanbanBoardMutationEnvelope =
        kanbanBody(Endpoint.KanbanEditBoard(request.slug), "PATCH", KanbanEditBoardBody(request))

    suspend fun archiveKanbanBoard(slug: String): KanbanBoardMutationEnvelope =
        kanbanRequest(Endpoint.KanbanArchiveBoard(slug), method = "DELETE")

    suspend fun makeKanbanBoardActive(slug: String): KanbanBoardMutationEnvelope =
        kanbanRequest(Endpoint.KanbanSwitchBoard(slug), method = "POST", body = EMPTY_REQUEST_BODY)

    suspend fun dispatchKanban(request: KanbanDispatchRequest): KanbanDispatchResult {
        val result: KanbanDispatchResult = kanbanRequest(
            Endpoint.KanbanDispatch(request.board, request.dryRun, request.maximum),
            method = "POST",
            body = EMPTY_REQUEST_BODY,
        )
        if (!result.hasKnownCategory) throw ApiError.Decoding(
            IllegalArgumentException("Kanban dispatch response omitted result categories"),
        )
        return result
    }

    suspend fun kanbanBoard(request: KanbanBoardRequest): KanbanBoardSnapshot =
        kanbanRequest(
            Endpoint.KanbanBoard(
                board = request.board,
                tenant = request.tenant,
                assignee = request.assignee,
                includeArchived = request.includeArchived,
                onlyMine = request.onlyMine,
                since = request.since,
            ),
        )

    suspend fun kanbanStats(board: String): KanbanStats =
        kanbanRequest(Endpoint.KanbanStats(board))

    suspend fun kanbanAssignees(board: String): KanbanAssigneeHistory =
        kanbanRequest(Endpoint.KanbanAssignees(board))

    suspend fun kanbanEvents(request: KanbanEventsRequest): KanbanEventsEnvelope =
        kanbanRequest(Endpoint.KanbanEvents(request.board, request.since, request.limit))

    suspend fun kanbanCardDetail(request: KanbanCardDetailRequest): KanbanCardDetailEnvelope =
        kanbanRequest(Endpoint.KanbanCardDetail(request.cardId, request.board))

    suspend fun kanbanWorkerLog(request: KanbanWorkerLogRequest): KanbanWorkerLog =
        kanbanRequest(Endpoint.KanbanWorkerLog(request.cardId, request.board, request.tailBytes))

    suspend fun addKanbanComment(request: KanbanAddCommentRequest): KanbanAddCommentResponse =
        kanbanBody(Endpoint.KanbanAddComment(request.cardId, request.board), "POST", KanbanCommentBody(request.body))

    suspend fun createKanbanCard(request: KanbanCreateCardRequest): KanbanCardMutationEnvelope =
        kanbanBody(Endpoint.KanbanCreateCard(request.board), "POST", KanbanCreateCardBody(request))

    suspend fun performKanbanBulkAction(request: KanbanBulkActionRequest): KanbanBulkActionEnvelope =
        kanbanBody(Endpoint.KanbanBulkAction(request.board), "POST", KanbanBulkActionBody(request))

    suspend fun editKanbanCard(request: KanbanEditCardRequest): KanbanCardMutationEnvelope =
        kanbanBody(Endpoint.KanbanEditCard(request.cardId, request.board), "PATCH", KanbanEditCardBody(request))

    suspend fun setKanbanCardStatus(request: KanbanCardStatusRequest): KanbanCardMutationEnvelope {
        require(request.status.trim().lowercase() != "running") {
            "Running status must be entered through the dispatcher"
        }
        return kanbanBody(
            Endpoint.KanbanCardStatus(request.cardId, request.board),
            "PATCH",
            KanbanStatusBody(request.status),
        )
    }

    suspend fun blockKanbanCard(request: KanbanCardActionRequest): KanbanCardMutationEnvelope =
        kanbanBody(
            Endpoint.KanbanBlockCard(request.cardId, request.board),
            "POST",
            KanbanActionBody(request.reason),
        )

    suspend fun unblockKanbanCard(request: KanbanCardActionRequest): KanbanCardMutationEnvelope =
        kanbanBody(
            Endpoint.KanbanUnblockCard(request.cardId, request.board),
            "POST",
            KanbanActionBody(null),
        )

    suspend fun addKanbanDependency(request: KanbanDependencyMutationRequest): KanbanDependencyMutationEnvelope =
        kanbanBody(
            Endpoint.KanbanAddDependency(request.board),
            "POST",
            KanbanDependencyBody(request.prerequisiteId, request.dependentId),
        )

    suspend fun removeKanbanDependency(request: KanbanDependencyMutationRequest): KanbanDependencyMutationEnvelope =
        kanbanBody(
            Endpoint.KanbanRemoveDependency(request.board),
            "POST",
            KanbanDependencyBody(request.prerequisiteId, request.dependentId),
        )

    // MARK: Workspace + files (APIClient+Workspace.swift)

    suspend fun workspaces(): WorkspacesResponse = get(Endpoint.Workspaces)

    suspend fun workspaceSuggestions(prefix: String): WorkspaceSuggestionsResponse =
        get(Endpoint.WorkspaceSuggestions(prefix))

    suspend fun directoryList(sessionId: String, path: String? = null): DirectoryListResponse =
        get(Endpoint.DirectoryList(sessionId, path))

    suspend fun file(sessionId: String, path: String): FileResponse =
        get(Endpoint.File(sessionId, path))

    /**
     * Authenticated URL for `GET /api/file/raw` — hand it to an image loader
     * (Coil) that shares [client]'s cookie jar rather than buffering bytes here.
     */
    suspend fun rawFileUrl(sessionId: String, path: String): HttpUrl =
        Endpoint.RawFile(sessionId, path).url(resolveBaseUrl())

    // MARK: Voice (APIClient+TTS.swift + APIClient+Transcribe.swift)

    suspend fun synthesizeSpeech(text: String, voice: String): ByteArray {
        val encoded = json.encodeToString(TtsSynthesisRequest(text, voice))
        val builder = Request.Builder()
            .url(Endpoint.Tts.url(resolveBaseUrl()))
            .post(encoded.toRequestBody(JSON_MEDIA_TYPE))
        customHeaderProvider().applyTo(builder)
        builder.header("Accept", "audio/mpeg")
        return execute(builder.build()).use { response ->
            val bytes = withContext(Dispatchers.IO) {
                runCatching { response.body?.bytes() ?: ByteArray(0) }
                    .getOrElse { throw ApiError.Network(it as? IOException ?: IOException(it)) }
            }
            if (response.code == 401) throw ApiError.Unauthorized
            if (!response.isSuccessful) throw ApiError.Http(response.code, bytes.toString(Charsets.UTF_8))
            bytes
        }
    }

    suspend fun transcribeAudio(data: ByteArray, filename: String): TranscribeResponse {
        val form = MultipartFormData().apply { appendFileField("file", filename, data) }
        val builder = Request.Builder()
            .url(Endpoint.Transcribe.url(resolveBaseUrl()))
            .post(form.build().toRequestBody(form.contentType.toMediaType()))
        customHeaderProvider().applyTo(builder)
        builder.header("Accept", "application/json")
        return execute(builder.build()).use { response ->
            val raw = withContext(Dispatchers.IO) {
                runCatching { response.body?.string().orEmpty() }
                    .getOrElse { throw ApiError.Network(it as? IOException ?: IOException(it)) }
            }
            if (response.code == 401) throw ApiError.Unauthorized
            val decoded = runCatching { json.decodeFromString<TranscribeResponse>(raw) }.getOrNull()
            if (decoded != null) return@use decoded
            if (!response.isSuccessful) throw ApiError.Http(response.code, raw)
            throw ApiError.Decoding(IllegalArgumentException("The transcription response could not be decoded"))
        }
    }
    // MARK: Upload (APIClient+Upload.swift + MultipartFormData.swift)

    suspend fun uploadFile(sessionId: String, data: ByteArray, filename: String): UploadResponse {
        val baseUrl = resolveBaseUrl()
        val form = MultipartFormData()
        form.appendTextField("session_id", sessionId)
        form.appendFileField("file", filename, data)
        val body = form.build().toRequestBody(form.contentType.toMediaType())

        // Custom headers first, then built-ins so the multipart Content-Type
        // always wins. Without them the upload is rejected by auth reverse
        // proxies that every other request path already passes (#61).
        val builder = Request.Builder()
            .url(Endpoint.Upload.url(baseUrl))
            .post(body)
        customHeaderProvider().applyTo(builder)

        val response = execute(builder.build())
        return decode(readSuccessBody(response))
    }

    // MARK: Plumbing

    private suspend inline fun <reified R> kanbanRequest(
        endpoint: Endpoint,
        method: String = "GET",
        body: RequestBody? = null,
    ): R {
        val builder = Request.Builder().url(endpoint.url(resolveBaseUrl())).method(method, body)
        customHeaderProvider().applyTo(builder)
        builder.header("Accept", "application/json")
        val response = execute(builder.build())
        val contentType = response.header("Content-Type").orEmpty().lowercase()
        val raw = readSuccessBody(response)
        if (!contentType.startsWith("application/json")) {
            throw ApiError.Decoding(IllegalArgumentException("Kanban endpoint returned non-JSON content"))
        }
        return decode(raw)
    }

    private suspend inline fun <reified R, reified B> kanbanBody(
        endpoint: Endpoint,
        method: String,
        body: B,
    ): R {
        val encoded = json.encodeToString(body)
        return kanbanRequest(endpoint, method, encoded.toRequestBody(JSON_MEDIA_TYPE))
    }

    /**
     * Request helpers are `internal` so feature slices add endpoints in their
     * own `net/HermesApi+<Feature>.kt` extension files instead of growing
     * this class. Same contract as the methods above: 401 → Unauthorized,
     * other non-2xx → Http(code, body), transport → Network, bad 2xx → Decoding.
     */
    internal suspend inline fun <reified R> get(endpoint: Endpoint): R =
        decode(sendForBody(endpoint, method = "GET", body = null))

    internal suspend inline fun <reified R, reified B> post(endpoint: Endpoint, body: B): R {
        val encoded = json.encodeToString(body)
        return decode(sendForBody(endpoint, method = "POST", body = encoded.toRequestBody(JSON_MEDIA_TYPE)))
    }

    /** Same as [post] with a non-JSON or pre-encoded body (multipart uploads, raw text). */
    internal suspend inline fun <reified R> send(endpoint: Endpoint, method: String, body: RequestBody?): R =
        decode(sendForBody(endpoint, method = method, body = body))

    /**
     * Authenticated binary GET used by workspace/document previews.
     *
     * When [limitBytes] is set, a successful response with a larger declared
     * body is refused before reading and an unbounded/chunked response is
     * stopped as soon as it crosses the same limit.
     */
    internal suspend fun getBytes(endpoint: Endpoint, limitBytes: Long? = null): ByteArray {
        val builder = Request.Builder()
            .url(endpoint.url(resolveBaseUrl()))
            .get()
        customHeaderProvider().applyTo(builder)
        builder.header("Accept", "*/*")

        return execute(builder.build()).use { response ->
            val contentLength = response.body?.contentLength() ?: -1L
            if (response.code in 200..299 && limitBytes != null && contentLength > limitBytes) {
                throw WorkspacePreviewTooLargeException(limitBytes)
            }

            val initialCapacity = when {
                contentLength in 1..Int.MAX_VALUE.toLong() ->
                    minOf(contentLength, limitBytes ?: contentLength).toInt()
                else -> 64 * 1024
            }
            val output = java.io.ByteArrayOutputStream(initialCapacity)
            try {
                response.body?.byteStream()?.use { input ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (limitBytes != null && total > limitBytes) {
                            throw WorkspacePreviewTooLargeException(limitBytes)
                        }
                        output.write(buffer, 0, read)
                    }
                }
            } catch (error: WorkspacePreviewTooLargeException) {
                throw error
            } catch (error: IOException) {
                throw ApiError.Network(error)
            }
            val bytes = output.toByteArray()
            if (response.code == 401) throw ApiError.Unauthorized
            if (response.code !in 200..299) {
                throw ApiError.Http(response.code, bytes.toString(Charsets.UTF_8))
            }
            bytes
        }
    }

    @PublishedApi
    internal inline fun <reified R> decode(body: String): R = try {
        json.decodeFromString<R>(body)
    } catch (error: IllegalArgumentException) {
        throw ApiError.Decoding(error)
    }

    @PublishedApi
    internal suspend fun sendForBody(endpoint: Endpoint, method: String, body: RequestBody?): String {
        // Custom headers first, then built-ins so Accept always wins.
        val builder = Request.Builder()
            .url(endpoint.url(resolveBaseUrl()))
            .method(method, body)
        customHeaderProvider().applyTo(builder)
        builder.header("Accept", "application/json")
        return readSuccessBody(execute(builder.build()))
    }

    private suspend fun execute(request: Request): Response =
        client.newCall(request).await()

    /** 401 → [ApiError.Unauthorized]; other non-2xx → [ApiError.Http] with the raw body. */
    private suspend fun readSuccessBody(response: Response): String = withContext(Dispatchers.IO) {
        response.use {
            val bodyText = try {
                it.body?.string().orEmpty()
            } catch (error: IOException) {
                throw ApiError.Network(error)
            }

            if (it.code == 401) throw ApiError.Unauthorized
            if (it.code !in 200..299) throw ApiError.Http(it.code, bodyText)
            bodyText
        }
    }

    /**
     * Parses the stored base URL fresh on every request so live config edits
     * apply immediately; an unparseable or blank URL fails the call with
     * [ApiError.InvalidServerUrl] before anything touches the network.
     */
    private suspend fun resolveBaseUrl(): HttpUrl {
        val raw = configProvider().baseUrl.trim()
        val url = raw.takeIf { it.isNotEmpty() }?.toHttpUrlOrNull() ?: throw ApiError.InvalidServerUrl
        lastBaseUrl = url
        return url
    }

    private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isCancelled) return
                continuation.resumeWithException(ApiError.Network(e))
            }

            override fun onResponse(call: Call, response: Response) {
                continuation.resume(response)
            }
        })
        continuation.invokeOnCancellation { cancel() }
    }

    @PublishedApi
    internal companion object {
        val JSON_MEDIA_TYPE = "application/json".toMediaType()
        val EMPTY_REQUEST_BODY = ByteArray(0).toRequestBody(null)
    }
}

// MARK: Request bodies (wire keys are snake_case, mirroring Swift convertToSnakeCase)

@Serializable
private data class LoginRequest(val password: String)

@Serializable
private class EmptyBody

@Serializable
private data class NewSessionRequest(
    val workspace: String?,
    val model: String?,
    @SerialName("model_provider") val modelProvider: String?,
    val profile: String?,
    @SerialName("project_id") val projectId: String? = null,
)

@Serializable
private data class RenameSessionRequest(
    @SerialName("session_id") val sessionId: String,
    val title: String,
)

@Serializable
private data class SessionIdRequest(
    @SerialName("session_id") val sessionId: String,
)

@Serializable
private data class PinSessionRequest(
    @SerialName("session_id") val sessionId: String,
    val pinned: Boolean,
)

@Serializable
private data class ArchiveSessionRequest(
    @SerialName("session_id") val sessionId: String,
    val archived: Boolean,
)

@Serializable
private data class BranchSessionRequest(
    @SerialName("session_id") val sessionId: String,
    @SerialName("keep_count") val keepCount: Int?,
    val title: String?,
)

@Serializable
private data class TruncateSessionRequest(
    @SerialName("session_id") val sessionId: String,
    @SerialName("keep_count") val keepCount: Int,
)

@Serializable
private data class CompressSessionRequest(
    @SerialName("session_id") val sessionId: String,
    @SerialName("focus_topic") val focusTopic: String?,
)

@Serializable
private data class CronCreateRequest(
    val prompt: String,
    val schedule: String,
    val name: String?,
    val deliver: String?,
    val skills: List<String>,
    val model: String?,
    val provider: String?,
    val profile: String?,
    @SerialName("toast_notifications") val toastNotifications: Boolean,
)

@Serializable
private data class CronUpdateRequest(
    @SerialName("job_id") val jobId: String,
    val prompt: String?,
    val schedule: String?,
    val name: String?,
    val deliver: String?,
    val skills: List<String>?,
    val model: String?,
    val provider: String?,
    val profile: String?,
    @SerialName("toast_notifications") val toastNotifications: Boolean?,
)

@Serializable
private data class CronJobIdRequest(
    @SerialName("job_id") val jobId: String,
    val reason: String?,
)

@Serializable
private data class MoveSessionRequest(
    @SerialName("session_id") val sessionId: String,
    @SerialName("project_id") val projectId: String?,
)

@Serializable
private data class UpdateSessionRequest(
    @SerialName("session_id") val sessionId: String,
    val workspace: String?,
    val model: String?,
    @SerialName("model_provider") val modelProvider: String?,
)

@Serializable
private data class SessionYoloRequest(
    @SerialName("session_id") val sessionId: String,
    val enabled: Boolean,
)

@Serializable
private data class CreateProjectRequest(
    val name: String,
    val color: String?,
)

@Serializable
private data class RenameProjectRequest(
    @SerialName("project_id") val projectId: String,
    val name: String,
    val color: String?,
)

@Serializable
private data class ProjectIdRequest(
    @SerialName("project_id") val projectId: String,
)

@Serializable
private data class ChatStartRequest(
    @SerialName("session_id") val sessionId: String,
    val message: String,
    val workspace: String?,
    val model: String?,
    @SerialName("model_provider") val modelProvider: String?,
    val profile: String?,
    @SerialName("explicit_model_pick") val explicitModelPick: Boolean?,
    val attachments: List<JsonElement>?,
)

@Serializable
private data class ChatSteerRequest(
    @SerialName("session_id") val sessionId: String,
    val text: String,
)

@Serializable
private data class ApprovalRespondRequest(
    @SerialName("session_id") val sessionId: String,
    val choice: ApprovalChoice,
    @SerialName("approval_id") val approvalId: String?,
)

@Serializable
private data class ClarificationRespondRequest(
    @SerialName("session_id") val sessionId: String,
    val response: String,
    @SerialName("clarify_id") val clarifyId: String?,
)

@Serializable
private data class BtwRequest(
    @SerialName("session_id") val sessionId: String,
    val question: String,
)

@Serializable
private data class BackgroundRequest(
    @SerialName("session_id") val sessionId: String,
    val prompt: String,
)

@Serializable
private data class GoalSubmissionRequest(
    @SerialName("session_id") val sessionId: String,
    val args: String,
    val workspace: String?,
    val model: String?,
    @SerialName("model_provider") val modelProvider: String?,
    val profile: String?,
)

@Serializable
private data class DefaultModelRequest(val model: String)

/**
 * `session_id` is omitted when null (settings' Default Model picker saves the
 * global default); the chat composer always scopes the effort to its session
 * (1.6.0: effort changes no longer apply globally).
 */
@Serializable
private data class ReasoningEffortRequest(
    val effort: String,
    @SerialName("session_id") val sessionId: String? = null,
)

@Serializable
private data class ReasoningDisplayRequest(val display: String)

@Serializable
private data class PersonalitySetRequest(
    @SerialName("session_id") val sessionId: String,
    val name: String,
)

@Serializable
private data class ProfileSwitchRequest(val name: String)

@Serializable
private data class KanbanCreateBoardBody(
    val slug: String,
    val name: String,
    val description: String,
    val icon: String,
    val color: String,
) {
    constructor(request: KanbanCreateBoardRequest) : this(
        request.slug, request.name, request.description, request.icon, request.color,
    )
}

@Serializable
private data class KanbanEditBoardBody(
    val name: String,
    val description: String,
    val icon: String,
    val color: String,
) {
    constructor(request: KanbanEditBoardRequest) : this(
        request.name, request.description, request.icon, request.color,
    )
}

@Serializable
private data class KanbanCommentBody(val body: String)

@Serializable
private data class KanbanStatusBody(val status: String)

@Serializable
private data class KanbanActionBody(val reason: String?)

@Serializable
private data class KanbanDependencyBody(
    @SerialName("parent_id") val parentId: String,
    @SerialName("child_id") val childId: String,
)

@Serializable
private data class KanbanCreateCardBody(
    val title: String,
    val body: String?,
    val status: String,
    val priority: Int?,
    val assignee: String?,
    val tenant: String?,
    @SerialName("workspace_kind") val workspaceKind: String,
    @SerialName("workspace_path") val workspacePath: String?,
    val skills: List<String>?,
    @SerialName("max_runtime_seconds") val maxRuntimeSeconds: Int?,
    val parents: List<String>?,
    @SerialName("idempotency_key") val idempotencyKey: String,
) {
    constructor(request: KanbanCreateCardRequest) : this(
        title = request.title,
        body = request.body,
        status = request.status,
        priority = request.priority,
        assignee = request.assignee,
        tenant = request.tenant,
        workspaceKind = request.workspaceKind,
        workspacePath = request.workspacePath,
        skills = request.skills,
        maxRuntimeSeconds = request.maxRuntimeSeconds,
        parents = request.prerequisiteId?.let(::listOf),
        idempotencyKey = request.idempotencyKey,
    )
}

@Serializable
private data class KanbanEditCardBody(
    val title: String,
    val body: String,
    val tenant: JsonElement,
    val priority: Int,
    val assignee: JsonElement,
    val status: String?,
) {
    constructor(request: KanbanEditCardRequest) : this(
        title = request.title,
        body = request.body,
        tenant = request.tenant?.let(::JsonPrimitive) ?: JsonNull,
        priority = request.priority,
        assignee = request.assignee?.let(::JsonPrimitive) ?: JsonNull,
        status = request.status,
    )
}

@Serializable
private data class KanbanBulkActionBody(
    val ids: List<String>,
    val archive: Boolean? = null,
    val status: String? = null,
    val assignee: String? = null,
    val priority: Int? = null,
) {
    constructor(request: KanbanBulkActionRequest) : this(
        ids = request.cardIds,
        archive = true.takeIf { request.action == KanbanBulkActionType.ARCHIVE },
        status = request.value.takeIf { request.action == KanbanBulkActionType.CHANGE_STATUS },
        assignee = request.value.orEmpty().takeIf { request.action == KanbanBulkActionType.ASSIGN_PROFILE },
        priority = request.value?.toIntOrNull().takeIf { request.action == KanbanBulkActionType.SET_PRIORITY },
    )
}

@Serializable
private data class GitSessionRequest(@SerialName("session_id") val sessionId: String)

@Serializable
private data class GitPathsRequest(
    @SerialName("session_id") val sessionId: String,
    val paths: List<String>,
)

@Serializable
private data class GitDiscardRequest(
    @SerialName("session_id") val sessionId: String,
    val paths: List<String>,
    @SerialName("delete_untracked") val deleteUntracked: Boolean,
)

@Serializable
private data class GitCommitRequest(
    @SerialName("session_id") val sessionId: String,
    val message: String,
)

@Serializable
private data class GitCommitSelectedRequest(
    @SerialName("session_id") val sessionId: String,
    val message: String,
    val paths: List<String>,
)

@Serializable
private data class GitCheckoutRequest(
    @SerialName("session_id") val sessionId: String,
    val ref: String,
    val mode: String,
    @SerialName("new_branch") val newBranch: String?,
    val track: Boolean?,
    @SerialName("dirty_mode") val dirtyMode: String?,
) {
    constructor(sessionId: String, target: GitCheckoutTarget, includesDirtyMode: Boolean) : this(
        sessionId = sessionId,
        ref = target.ref,
        mode = if (target.mode.rawValue == "local" && target.newBranch != null) "new" else target.mode.rawValue,
        newBranch = target.newBranch,
        track = true.takeIf { target.track },
        dirtyMode = "block".takeIf { includesDirtyMode },
    )
}

@Serializable
private data class TtsSynthesisRequest(
    val text: String,
    val voice: String,
)
