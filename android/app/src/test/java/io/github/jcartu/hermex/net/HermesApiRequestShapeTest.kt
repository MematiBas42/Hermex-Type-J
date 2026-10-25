package io.github.jcartu.hermex.net

import io.github.jcartu.hermex.core.prefs.ServerConfig
import io.github.jcartu.hermex.model.ApprovalChoice
import io.github.jcartu.hermex.model.SessionExportFormat
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Request-shape contract matrix, mirroring the iOS `APIClientTests`: every
 * endpoint's method, path, query, and JSON body must match the Swift wire
 * format exactly — snake_case keys, nulls omitted — and no request may ever
 * carry `Origin` or `Referer` headers (PROJECT_SPEC.md §6.1).
 */
class HermesApiRequestShapeTest {

    private lateinit var server: MockWebServer
    private lateinit var api: HermesApi

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
        encodeDefaults = false
    }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        api = HermesApi(
            httpClient = OkHttpClient(),
            json = json,
            configProvider = { ServerConfig(server.url("/").toString(), "pw") },
            customHeaderProvider = { emptyList() },
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun record(body: String = "{}", call: suspend () -> Any?): RecordedRequest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody(body),
        )
        runBlocking { call() }
        val request = server.takeRequest()
        assertNoBrowserHeaders(request)
        return request
    }

    private fun assertNoBrowserHeaders(request: RecordedRequest) {
        assertNull("Origin must never be sent", request.getHeader("Origin"))
        assertNull("Referer must never be sent", request.getHeader("Referer"))
    }

    private fun jsonBody(request: RecordedRequest) =
        json.parseToJsonElement(request.body.readUtf8()).jsonObject

    // MARK: Auth

    @Test
    fun healthIsPlainGet() {
        val request = record { api.health() }
        assertEquals("GET", request.method)
        assertEquals("/health", request.path)
    }

    @Test
    fun loginPostsPassword() {
        val request = record { api.login("hunter2") }
        assertEquals("POST", request.method)
        assertEquals("/api/auth/login", request.path)
        assertEquals("application/json", request.getHeader("Content-Type")?.substringBefore(';'))
        assertEquals("hunter2", jsonBody(request)["password"]?.jsonPrimitive?.content)
    }

    @Test
    fun logoutPostsEmptyObject() {
        val request = record { api.logout() }
        assertEquals("POST", request.method)
        assertEquals("/api/auth/logout", request.path)
        assertEquals(emptySet<String>(), jsonBody(request).keys)
    }

    // MARK: Sessions

    @Test
    fun sessionsDefaultHasNoQuery() {
        val request = record("""{"sessions":[]}""") { api.sessions() }
        assertEquals("GET", request.method)
        assertEquals("/api/sessions", request.path)
    }

    @Test
    fun sessionsIncludeArchivedSendsOptInFlags() {
        val request = record("""{"sessions":[]}""") { api.sessions(includeArchived = true, archivedLimit = 25) }
        assertEquals("/api/sessions?include_archived=1&archived_limit=25", request.path)
    }

    @Test
    fun sessionsArchivedLimitOnlySentWithIncludeArchived() {
        val request = record("""{"sessions":[]}""") { api.sessions(includeArchived = false, archivedLimit = 25) }
        assertEquals("/api/sessions", request.path)
    }

    @Test
    fun searchSessionsEncodesQuery() {
        val request = record { api.searchSessions("hello world", content = true, depth = 5) }
        assertEquals("GET", request.method)
        assertEquals("/api/sessions/search?q=hello%20world&content=1&depth=5", request.path)
    }

    @Test
    fun sessionDetailSendsMessageWindow() {
        val request = record { api.session("abc", messageLimit = 50, messageBefore = 7, expandRenderable = true) }
        assertEquals(
            "/api/session?session_id=abc&messages=1&msg_limit=50&msg_before=7&expand_renderable=1",
            request.path,
        )
    }

    @Test
    fun sessionDetailOmitsOptionalQueryWhenUnset() {
        val request = record { api.session("abc", includeMessages = false, messageLimit = null) }
        assertEquals("/api/session?session_id=abc&messages=0", request.path)
    }

    @Test
    fun sessionStatusQueriesById() {
        val request = record { api.sessionStatus("abc") }
        assertEquals("/api/session/status?session_id=abc", request.path)
    }

    @Test
    fun createSessionPostsSnakeCaseBodyAndOmitsNulls() {
        val request = record { api.createSession(workspace = "/tmp/ws", model = null, modelProvider = null, profile = null) }
        assertEquals("POST", request.method)
        assertEquals("/api/session/new", request.path)
        val body = jsonBody(request)
        assertEquals("/tmp/ws", body["workspace"]?.jsonPrimitive?.content)
        assertFalse("null model must be omitted", "model" in body)
        assertFalse("null model_provider must be omitted", "model_provider" in body)
        assertFalse("null profile must be omitted", "profile" in body)
    }

    @Test
    fun renameSessionPostsSessionIdAndTitle() {
        val request = record { api.renameSession("abc", "New title") }
        assertEquals("/api/session/rename", request.path)
        val body = jsonBody(request)
        assertEquals("abc", body["session_id"]?.jsonPrimitive?.content)
        assertEquals("New title", body["title"]?.jsonPrimitive?.content)
    }

    @Test
    fun deleteSessionPostsSessionId() {
        val request = record { api.deleteSession("abc") }
        assertEquals("/api/session/delete", request.path)
        assertEquals("abc", jsonBody(request)["session_id"]?.jsonPrimitive?.content)
    }

    @Test
    fun pinSessionPostsPinnedFlag() {
        val request = record { api.pinSession("abc", pinned = true) }
        assertEquals("/api/session/pin", request.path)
        val body = jsonBody(request)
        assertEquals("abc", body["session_id"]?.jsonPrimitive?.content)
        assertEquals("true", body["pinned"]?.jsonPrimitive?.content)
    }

    @Test
    fun archiveSessionPostsArchivedFlag() {
        val request = record { api.archiveSession("abc", archived = false) }
        assertEquals("/api/session/archive", request.path)
        assertEquals("false", jsonBody(request)["archived"]?.jsonPrimitive?.content)
    }

    @Test
    fun branchSessionPostsKeepCountAndTitle() {
        val request = record { api.branchSession("abc", keepCount = 4, title = "Branch") }
        assertEquals("/api/session/branch", request.path)
        val body = jsonBody(request)
        assertEquals("4", body["keep_count"]?.jsonPrimitive?.content)
        assertEquals("Branch", body["title"]?.jsonPrimitive?.content)
    }

    @Test
    fun branchSessionOmitsNullOptionals() {
        val request = record { api.branchSession("abc") }
        val body = jsonBody(request)
        assertFalse("keep_count" in body)
        assertFalse("title" in body)
    }

    @Test
    fun truncateSessionPostsKeepCount() {
        val request = record { api.truncateSession("abc", keepCount = 12) }
        assertEquals("/api/session/truncate", request.path)
        assertEquals("12", jsonBody(request)["keep_count"]?.jsonPrimitive?.content)
    }

    @Test
    fun moveSessionPostsProjectId() {
        val request = record { api.moveSession("abc", projectId = "proj-1") }
        assertEquals("/api/session/move", request.path)
        assertEquals("proj-1", jsonBody(request)["project_id"]?.jsonPrimitive?.content)
    }

    @Test
    fun moveSessionToNoProjectOmitsProjectId() {
        val request = record { api.moveSession("abc", projectId = null) }
        assertFalse("project_id" in jsonBody(request))
    }

    // MARK: Projects

    @Test
    fun projectsIsPlainGet() {
        val request = record { api.projects() }
        assertEquals("GET", request.method)
        assertEquals("/api/projects", request.path)
    }

    @Test
    fun createProjectPostsNameAndColor() {
        val request = record { api.createProject("Work", color = "#aabbcc") }
        assertEquals("/api/projects/create", request.path)
        val body = jsonBody(request)
        assertEquals("Work", body["name"]?.jsonPrimitive?.content)
        assertEquals("#aabbcc", body["color"]?.jsonPrimitive?.content)
    }

    // MARK: Chat

    @Test
    fun startChatPostsFullBody() {
        val attachments = listOf<JsonElement>(
            buildJsonObject { put("path", JsonPrimitive("/tmp/a.png")) },
        )
        val request = record {
            api.startChat(
                sessionId = "abc",
                message = "hi",
                workspace = "/tmp/ws",
                model = "gpt-x",
                modelProvider = "openai",
                profile = "default",
                explicitModelPick = true,
                attachments = attachments,
            )
        }
        assertEquals("POST", request.method)
        assertEquals("/api/chat/start", request.path)
        val body = jsonBody(request)
        assertEquals("abc", body["session_id"]?.jsonPrimitive?.content)
        assertEquals("hi", body["message"]?.jsonPrimitive?.content)
        assertEquals("gpt-x", body["model"]?.jsonPrimitive?.content)
        assertEquals("openai", body["model_provider"]?.jsonPrimitive?.content)
        assertEquals("true", body["explicit_model_pick"]?.jsonPrimitive?.content)
        val first = (body["attachments"] as JsonArray).first().jsonObject
        assertEquals("/tmp/a.png", first["path"]?.jsonPrimitive?.content)
    }

    @Test
    fun startChatOmitsExplicitModelPickWhenFalse() {
        val request = record { api.startChat("abc", "hi", workspace = null, model = null) }
        val body = jsonBody(request)
        assertFalse("explicit_model_pick sent only when true", "explicit_model_pick" in body)
        assertFalse("attachments" in body)
        assertFalse("workspace" in body)
    }

    @Test
    fun cancelChatIsGetWithStreamId() {
        val request = record { api.cancelChat("stream-1") }
        assertEquals("GET", request.method)
        assertEquals("/api/chat/cancel?stream_id=stream-1", request.path)
    }

    @Test
    fun chatStreamStatusIsGetWithStreamId() {
        val request = record { api.chatStreamStatus("stream-1") }
        assertEquals("/api/chat/stream/status?stream_id=stream-1", request.path)
    }

    @Test
    fun steerChatPostsText() {
        val request = record { api.steerChat("abc", "focus on tests") }
        assertEquals("POST", request.method)
        assertEquals("/api/chat/steer", request.path)
        val body = jsonBody(request)
        assertEquals("abc", body["session_id"]?.jsonPrimitive?.content)
        assertEquals("focus on tests", body["text"]?.jsonPrimitive?.content)
    }

    @Test
    fun chatStreamUrlCarriesStreamIdAndReplayCursor() {
        runBlocking {
            val plain = api.chatStreamUrl("stream-1")
            assertEquals("/api/chat/stream", plain.encodedPath)
            assertEquals("stream-1", plain.queryParameter("stream_id"))
            assertNull(plain.queryParameter("replay"))

            val replay = api.chatStreamUrl("stream-1", replayAfterSeq = 17)
            assertEquals("1", replay.queryParameter("replay"))
            assertEquals("17", replay.queryParameter("after_seq"))

            val clamped = api.chatStreamUrl("stream-1", replayAfterSeq = -3)
            assertEquals("0", clamped.queryParameter("after_seq"))
        }
    }

    @Test
    fun approvalEndpointsMatchSwift() {
        val pending = record { api.approvalPending("abc") }
        assertEquals("GET", pending.method)
        assertEquals("/api/approval/pending?session_id=abc", pending.path)

        val respond = record { api.respondApproval("abc", ApprovalChoice.ONCE, approvalId = "ap-1") }
        assertEquals("POST", respond.method)
        assertEquals("/api/approval/respond", respond.path)
        val body = jsonBody(respond)
        assertEquals("abc", body["session_id"]?.jsonPrimitive?.content)
        assertEquals("once", body["choice"]?.jsonPrimitive?.content)
        assertEquals("ap-1", body["approval_id"]?.jsonPrimitive?.content)
    }

    @Test
    fun clarifyEndpointsMatchSwift() {
        val pending = record { api.clarifyPending("abc") }
        assertEquals("/api/clarify/pending?session_id=abc", pending.path)

        val respond = record { api.respondClarification("abc", response = "option B", clarifyId = null) }
        assertEquals("/api/clarify/respond", respond.path)
        val body = jsonBody(respond)
        assertEquals("option B", body["response"]?.jsonPrimitive?.content)
        assertFalse("clarify_id" in body)
    }

    @Test
    fun btwPostsQuestion() {
        val request = record { api.startBtw("abc", "what changed?") }
        assertEquals("/api/btw", request.path)
        assertEquals("what changed?", jsonBody(request)["question"]?.jsonPrimitive?.content)
    }

    @Test
    fun backgroundPostsPromptAndStatusQueriesBySession() {
        val start = record { api.startBackground("abc", "run tests") }
        assertEquals("POST", start.method)
        assertEquals("/api/background", start.path)
        assertEquals("run tests", jsonBody(start)["prompt"]?.jsonPrimitive?.content)

        val status = record { api.backgroundStatus("abc") }
        assertEquals("GET", status.method)
        assertEquals("/api/background/status?session_id=abc", status.path)
    }

    // MARK: Crons

    @Test
    fun cronEndpointsMatchSwift() {
        val list = record { api.crons() }
        assertEquals("/api/crons", list.path)

        val statusAll = record { api.cronStatus() }
        assertEquals("/api/crons/status", statusAll.path)

        val statusOne = record { api.cronStatus("job-1") }
        assertEquals("/api/crons/status?job_id=job-1", statusOne.path)

        val output = record { api.cronOutput("job-1", limit = 5) }
        assertEquals("/api/crons/output?job_id=job-1&limit=5", output.path)

        val outputNoLimit = record { api.cronOutput("job-1", limit = null) }
        assertEquals("/api/crons/output?job_id=job-1", outputNoLimit.path)
    }

    // MARK: Skills + memory

    @Test
    fun skillEndpointsMatchSwift() {
        val list = record { api.skills() }
        assertEquals("/api/skills", list.path)

        val content = record { api.skillContent("research") }
        assertEquals("/api/skills/content?name=research", content.path)

        val linked = record { api.skillContent("research", file = "SKILL.md") }
        assertEquals("/api/skills/content?name=research&file=SKILL.md", linked.path)
    }

    @Test
    fun memoryIsPlainGet() {
        val request = record { api.memory() }
        assertEquals("GET", request.method)
        assertEquals("/api/memory", request.path)
    }

    // MARK: Server panels

    @Test
    fun serverPanelGetsMatchSwift() {
        assertEquals("/api/models", record { api.models() }.path)
        assertEquals("/api/providers", record { api.providers() }.path)
        assertEquals("/api/settings", record { api.settings() }.path)
        assertEquals("/api/profiles", record { api.profiles() }.path)
        assertEquals("/api/personalities", record { api.personalities() }.path)
        assertEquals("/api/commands", record { api.commands() }.path)
        assertEquals("/api/workspaces", record { api.workspaces() }.path)
    }

    @Test
    fun saveDefaultModelPostsModel() {
        val request = record { api.saveDefaultModel("gpt-x") }
        assertEquals("POST", request.method)
        assertEquals("/api/default-model", request.path)
        assertEquals("gpt-x", jsonBody(request)["model"]?.jsonPrimitive?.content)
    }

    @Test
    fun reasoningGetSendsModelAndProviderOnlyWhenPresent() {
        val bare = record { api.reasoning() }
        assertEquals("/api/reasoning", bare.path)

        val scoped = record { api.reasoning(model = "gpt-x", provider = "openai") }
        assertEquals("/api/reasoning?model=gpt-x&provider=openai", scoped.path)

        val emptyStrings = record { api.reasoning(model = "", provider = "") }
        assertEquals("empty strings are treated like nil", "/api/reasoning", emptyStrings.path)
    }

    @Test
    fun reasoningPostsEffortAndDisplaySeparately() {
        val effort = record { api.saveReasoningEffort("high") }
        assertEquals("POST", effort.method)
        assertEquals("/api/reasoning", effort.path)
        assertEquals("high", jsonBody(effort)["effort"]?.jsonPrimitive?.content)

        val display = record { api.saveReasoningDisplay("full") }
        val displayBody = jsonBody(display)
        assertEquals("full", displayBody["display"]?.jsonPrimitive?.content)
        assertFalse("effort" in displayBody)
    }

    @Test
    fun setPersonalityPostsSessionIdAndName() {
        val request = record { api.setPersonality("abc", "concise") }
        assertEquals("/api/personality/set", request.path)
        val body = jsonBody(request)
        assertEquals("abc", body["session_id"]?.jsonPrimitive?.content)
        assertEquals("concise", body["name"]?.jsonPrimitive?.content)
    }

    @Test
    fun switchProfilePostsName() {
        val request = record { api.switchProfile("work") }
        assertEquals("/api/profile/switch", request.path)
        assertEquals("work", jsonBody(request)["name"]?.jsonPrimitive?.content)
    }

    // MARK: Workspace + files

    @Test
    fun workspaceSuggestionsQueriesPrefix() {
        val request = record { api.workspaceSuggestions("/tm") }
        assertEquals("/api/workspaces/suggest?prefix=/tm", request.path)
    }

    @Test
    fun directoryListSendsPathOnlyWhenPresent() {
        val root = record { api.directoryList("abc") }
        assertEquals("/api/list?session_id=abc", root.path)

        val nested = record { api.directoryList("abc", path = "src/main") }
        assertEquals("/api/list?session_id=abc&path=src/main", nested.path)
    }

    @Test
    fun fileReadsQuerySessionAndPath() {
        val request = record { api.file("abc", "src/App.kt") }
        assertEquals("/api/file?session_id=abc&path=src/App.kt", request.path)
    }

    @Test
    fun rawFileUrlIsAuthenticatedFileRawRoute() {
        runBlocking {
            val url = api.rawFileUrl("abc", "img/logo.png")
            assertEquals("/api/file/raw", url.encodedPath)
            assertEquals("abc", url.queryParameter("session_id"))
            assertEquals("img/logo.png", url.queryParameter("path"))
        }
    }

    @Test
    fun baseUrlPathPrefixIsPreserved() {
        val prefixed = HermesApi(
            httpClient = OkHttpClient(),
            json = json,
            configProvider = { ServerConfig(server.url("/hermes").toString(), "pw") },
            customHeaderProvider = { emptyList() },
        )
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"status":"ok"}"""))
        runBlocking { prefixed.health() }
        assertEquals("/hermes/health", server.takeRequest().path)
    }

    // MARK: Custom headers

    @Test
    fun customHeadersApplyButBuiltInsWin() {
        val withHeaders = HermesApi(
            httpClient = OkHttpClient(),
            json = json,
            configProvider = { ServerConfig(server.url("/").toString(), "pw") },
            customHeaderProvider = {
                listOf(
                    CustomHeader("X-Api-Key", "secret"),
                    CustomHeader("Accept", "text/plain"), // built-in must win
                    CustomHeader("", "skipped"), // not applicable
                )
            },
        )
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        runBlocking { withHeaders.memory() }
        val request = server.takeRequest()
        assertEquals("secret", request.getHeader("X-Api-Key"))
        assertEquals("application/json", request.getHeader("Accept"))
        assertNoBrowserHeaders(request)
    }

    // MARK: Upload

    @Test
    fun uploadPostsMultipartWithSessionIdAndFilePart() {
        val request = record("""{"filename":"a.png"}""") {
            api.uploadFile("abc", byteArrayOf(1, 2, 3), "a.png")
        }
        assertEquals("POST", request.method)
        assertEquals("/api/upload", request.path)

        val contentType = request.getHeader("Content-Type").orEmpty()
        assertTrue(
            "multipart content type with boundary, got: $contentType",
            contentType.startsWith("multipart/form-data; boundary=Boundary-"),
        )
        val boundary = contentType.substringAfter("boundary=")
        val body = request.body.readUtf8()
        assertTrue(
            "session_id field encoded like Swift",
            body.contains("--$boundary\r\nContent-Disposition: form-data; name=\"session_id\"\r\n\r\nabc\r\n"),
        )
        assertTrue(
            "file part encoded like Swift",
            body.contains(
                "--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"a.png\"\r\n" +
                    "Content-Type: application/octet-stream\r\n\r\n",
            ),
        )
        assertTrue("closing boundary present", body.endsWith("--$boundary--\r\n"))
    }

    // MARK: Session export

    @Test
    fun exportSessionIsGetWithFormatAndWildcardAccept() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "text/html")
                .addHeader("Content-Disposition", "attachment; filename=\"hermes-abc.html\"")
                .setBody("<html>transcript</html>"),
        )
        val export = runBlocking { api.exportSession("abc", SessionExportFormat.html) }

        val request = server.takeRequest()
        assertNoBrowserHeaders(request)
        assertEquals("GET", request.method)
        assertEquals("/api/session/export?session_id=abc&format=html", request.path)
        assertEquals("file downloads must not claim JSON-only", "*/*", request.getHeader("Accept"))
        assertEquals("hermes-abc.html", export.filename)
        assertEquals("<html>transcript</html>", export.data.toString(Charsets.UTF_8))
    }

    @Test
    fun exportSessionFallsBackToTitleThenSessionId() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        val titled = runBlocking {
            api.exportSession("abc", SessionExportFormat.json, fallbackTitle = "  Planning /  Q3  ")
        }
        server.takeRequest()
        assertEquals("Planning Q3.json", titled.filename)

        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        val bare = runBlocking { api.exportSession("abc", SessionExportFormat.json) }
        server.takeRequest()
        assertEquals("hermes-abc.json", bare.filename)
    }

    @Test
    fun exportSessionMapsErrorsLikeEveryOtherEndpoint() {
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"error":"Session not found"}"""))
        try {
            runBlocking { api.exportSession("gone", SessionExportFormat.html) }
            org.junit.Assert.fail("expected ApiError")
        } catch (error: ApiError) {
            assertEquals(404, (error as ApiError.Http).statusCode)
        }
        server.takeRequest()
    }

    // MARK: Project mutations

    @Test
    fun renameProjectPostsProjectIdNameAndColor() {
        val request = record { api.renameProject("proj-1", "Renamed", color = "#123456") }
        assertEquals("POST", request.method)
        assertEquals("/api/projects/rename", request.path)
        val body = jsonBody(request)
        assertEquals("proj-1", body["project_id"]?.jsonPrimitive?.content)
        assertEquals("Renamed", body["name"]?.jsonPrimitive?.content)
        assertEquals("#123456", body["color"]?.jsonPrimitive?.content)
    }

    @Test
    fun deleteProjectPostsProjectId() {
        val request = record { api.deleteProject("proj-1") }
        assertEquals("/api/projects/delete", request.path)
        assertEquals("proj-1", jsonBody(request)["project_id"]?.jsonPrimitive?.content)
    }

    // MARK: Cron mutations

    @Test
    fun createCronPostsFullSnakeCaseBody() {
        val request = record {
            api.createCron(
                prompt = "check inbox",
                schedule = "0 9 * * *",
                name = "Morning check",
                deliver = "toast",
                skills = listOf("email", "summarize"),
                model = "gpt-x",
                provider = "openai",
                profile = "default",
                toastNotifications = true,
            )
        }
        assertEquals("POST", request.method)
        assertEquals("/api/crons/create", request.path)
        val body = jsonBody(request)
        assertEquals("check inbox", body["prompt"]?.jsonPrimitive?.content)
        assertEquals("0 9 * * *", body["schedule"]?.jsonPrimitive?.content)
        assertEquals("Morning check", body["name"]?.jsonPrimitive?.content)
        assertEquals("toast", body["deliver"]?.jsonPrimitive?.content)
        assertEquals(
            listOf("email", "summarize"),
            (body["skills"] as JsonArray).map { it.jsonPrimitive.content },
        )
        assertEquals("true", body["toast_notifications"]?.jsonPrimitive?.content)
    }

    @Test
    fun createCronAlwaysSendsSkillsAndToastEvenWhenOptionalFieldsAreNull() {
        val request = record {
            api.createCron(
                prompt = "p",
                schedule = "@daily",
                name = null,
                deliver = null,
                skills = emptyList(),
                model = null,
                provider = null,
                profile = null,
                toastNotifications = false,
            )
        }
        val body = jsonBody(request)
        assertEquals(0, (body["skills"] as JsonArray).size)
        assertEquals("false", body["toast_notifications"]?.jsonPrimitive?.content)
        assertFalse("name" in body)
        assertFalse("deliver" in body)
        assertFalse("model" in body)
    }

    @Test
    fun updateCronPostsJobIdAndOnlyProvidedFields() {
        val request = record {
            api.updateCron(
                jobId = "job-1",
                prompt = "new prompt",
                schedule = null,
                name = null,
                deliver = null,
                skills = null,
                model = null,
                provider = null,
                profile = null,
                toastNotifications = null,
            )
        }
        assertEquals("/api/crons/update", request.path)
        val body = jsonBody(request)
        assertEquals("job-1", body["job_id"]?.jsonPrimitive?.content)
        assertEquals("new prompt", body["prompt"]?.jsonPrimitive?.content)
        assertFalse("schedule" in body)
        assertFalse("skills" in body)
        assertFalse("toast_notifications" in body)
    }

    @Test
    fun cronLifecycleMutationsPostJobId() {
        val delete = record { api.deleteCron("job-1") }
        assertEquals("/api/crons/delete", delete.path)
        assertEquals("job-1", jsonBody(delete)["job_id"]?.jsonPrimitive?.content)

        val run = record { api.runCron("job-1") }
        assertEquals("/api/crons/run", run.path)
        assertEquals("job-1", jsonBody(run)["job_id"]?.jsonPrimitive?.content)

        val pausePlain = record { api.pauseCron("job-1") }
        assertEquals("/api/crons/pause", pausePlain.path)
        assertFalse("reason omitted when null", "reason" in jsonBody(pausePlain))

        val pauseWithReason = record { api.pauseCron("job-1", reason = "vacation") }
        assertEquals("vacation", jsonBody(pauseWithReason)["reason"]?.jsonPrimitive?.content)

        val resume = record { api.resumeCron("job-1") }
        assertEquals("/api/crons/resume", resume.path)
        assertEquals("job-1", jsonBody(resume)["job_id"]?.jsonPrimitive?.content)
    }

    @Test
    fun cronDeliveryOptionsIsPlainGet() {
        val request = record { api.cronDeliveryOptions() }
        assertEquals("GET", request.method)
        assertEquals("/api/crons/delivery-options", request.path)
    }

    // MARK: Session compress/undo/retry

    @Test
    fun compressSessionPostsFocusTopicOnlyWhenPresent() {
        val focused = record { api.compressSession("abc", focusTopic = "auth work") }
        assertEquals("/api/session/compress", focused.path)
        val body = jsonBody(focused)
        assertEquals("abc", body["session_id"]?.jsonPrimitive?.content)
        assertEquals("auth work", body["focus_topic"]?.jsonPrimitive?.content)

        val plain = record { api.compressSession("abc") }
        assertFalse("focus_topic" in jsonBody(plain))
    }

    @Test
    fun undoAndRetryPostSessionId() {
        val undo = record { api.undoSession("abc") }
        assertEquals("/api/session/undo", undo.path)
        assertEquals("abc", jsonBody(undo)["session_id"]?.jsonPrimitive?.content)

        val retry = record { api.retrySession("abc") }
        assertEquals("/api/session/retry", retry.path)
        assertEquals("abc", jsonBody(retry)["session_id"]?.jsonPrimitive?.content)
    }

    // MARK: Skills toggle

    @Test
    fun toggleSkillPostsNameAndEnabled() {
        val request = record { api.toggleSkill("research", enabled = true) }
        assertEquals("POST", request.method)
        assertEquals("/api/skills/toggle", request.path)
        val body = jsonBody(request)
        assertEquals("research", body["name"]?.jsonPrimitive?.content)
        assertEquals("true", body["enabled"]?.jsonPrimitive?.content)
    }

    // MARK: Session update + yolo

    @Test
    fun updateSessionPostsSnakeCaseBodyAndOmitsNulls() {
        val request = record { api.updateSession("abc", workspace = "/tmp/ws", model = "gpt-x", modelProvider = null) }
        assertEquals("POST", request.method)
        assertEquals("/api/session/update", request.path)
        val body = jsonBody(request)
        assertEquals("abc", body["session_id"]?.jsonPrimitive?.content)
        assertEquals("/tmp/ws", body["workspace"]?.jsonPrimitive?.content)
        assertEquals("gpt-x", body["model"]?.jsonPrimitive?.content)
        assertFalse("null model_provider must be omitted", "model_provider" in body)
    }

    @Test
    fun sessionYoloReadsWithQueryAndWritesWithBody() {
        val read = record { api.sessionYolo("abc") }
        assertEquals("GET", read.method)
        assertEquals("/api/session/yolo?session_id=abc", read.path)

        val write = record { api.setSessionYolo("abc", enabled = true) }
        assertEquals("POST", write.method)
        assertEquals("write goes to the bare route, session in the body", "/api/session/yolo", write.path)
        val body = jsonBody(write)
        assertEquals("abc", body["session_id"]?.jsonPrimitive?.content)
        assertEquals("true", body["enabled"]?.jsonPrimitive?.content)
    }

    // MARK: Goal

    @Test
    fun submitGoalPostsFullBodyAndOmitsNulls() {
        val request = record {
            api.submitGoal(
                sessionId = "abc",
                args = "ship the port",
                workspace = "/tmp/ws",
                model = null,
                modelProvider = null,
                profile = null,
            )
        }
        assertEquals("POST", request.method)
        assertEquals("/api/goal", request.path)
        val body = jsonBody(request)
        assertEquals("abc", body["session_id"]?.jsonPrimitive?.content)
        assertEquals("ship the port", body["args"]?.jsonPrimitive?.content)
        assertEquals("/tmp/ws", body["workspace"]?.jsonPrimitive?.content)
        assertFalse("model" in body)
        assertFalse("model_provider" in body)
        assertFalse("profile" in body)
    }

    // MARK: Live models

    @Test
    fun modelsLiveIsPlainGet() {
        val request = record { api.modelsLive() }
        assertEquals("GET", request.method)
        assertEquals("/api/models/live", request.path)
    }

    // MARK: Hermes Dashboard fallbacks

    @Test
    fun modelsFallsBackToModelOptionsWhen404() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404).setBody("{\"detail\":\"No such API endpoint: /api/models\"}"))
        server.enqueue(
            MockResponse().setResponseCode(200).addHeader("Content-Type", "application/json").setBody(
                """{"providers":[{"slug":"agy","name":"agy-gateway","models":["gemini-3.8-flash"]}],"model":"gemini-3.8-flash","provider":"agy"}""",
            ),
        )

        val response = api.models()
        val r1 = server.takeRequest()
        val r2 = server.takeRequest()

        assertEquals("/api/models", r1.path)
        assertEquals("/api/model/options", r2.path)
        assertEquals("gemini-3.8-flash", response.defaultModel)
        assertEquals("agy", response.activeProvider)
        assertEquals(1, response.catalogGroups.size)
        assertEquals("gemini-3.8-flash", response.catalogGroups.first().models.first().id)
    }

    @Test
    fun updateSessionFallsBackToModelSetWhen405() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(405).setBody("{\"detail\":\"Method Not Allowed\"}"))
        server.enqueue(
            MockResponse().setResponseCode(200).addHeader("Content-Type", "application/json").setBody(
                """{"ok":true,"scope":"main","provider":"agy","model":"gemini-3.8-flash"}""",
            ),
        )

        val response = api.updateSession("sid-1", "/ws", "gemini-3.8-flash", "agy")
        val r1 = server.takeRequest()
        val r2 = server.takeRequest()

        assertEquals("/api/session/update", r1.path)
        assertEquals("/api/model/set", r2.path)
        val body2 = jsonBody(r2)
        assertEquals("gemini-3.8-flash", body2["model"]?.jsonPrimitive?.content)
        assertEquals("agy", body2["provider"]?.jsonPrimitive?.content)
        assertEquals("main", body2["scope"]?.jsonPrimitive?.content)
        assertEquals("gemini-3.8-flash", response.session?.model)
    }
}
