package io.github.jcartu.hermex.net

import io.github.jcartu.hermex.core.prefs.ServerConfig
import io.github.jcartu.hermex.model.KanbanBoardRequest
import io.github.jcartu.hermex.model.KanbanBulkActionRequest
import io.github.jcartu.hermex.model.KanbanBulkActionType
import io.github.jcartu.hermex.model.KanbanCardStatusRequest
import io.github.jcartu.hermex.model.KanbanCreateBoardRequest
import io.github.jcartu.hermex.model.KanbanCreateCardRequest
import io.github.jcartu.hermex.model.KanbanEditBoardRequest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class KanbanApiTest {
    private lateinit var server: MockWebServer
    private lateinit var api: HermesApi
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = false }

    @Before fun setUp() {
        server = MockWebServer(); server.start()
        api = HermesApi(OkHttpClient(), json, { ServerConfig(server.url("/").toString(), "pw") }, { emptyList() })
    }

    @After fun tearDown() = server.shutdown()

    private fun record(response: String = "{}", call: suspend () -> Any?): RecordedRequest {
        server.enqueue(MockResponse().setResponseCode(200).addHeader("Content-Type", "application/json").setBody(response))
        runBlocking { call() }
        return server.takeRequest().also {
            assertNull(it.getHeader("Origin")); assertNull(it.getHeader("Referer"))
        }
    }

    @Test fun `board filters preserve exact query contract`() {
        val request = record("""{"columns":[]}""") {
            api.kanbanBoard(KanbanBoardRequest("main", "tenant a", "reviewer", true, true, -9))
        }
        assertEquals("GET", request.method)
        assertEquals(
            "/api/kanban/board?board=main&tenant=tenant%20a&assignee=reviewer&include_archived=true&only_mine=true&since=0",
            request.path,
        )
    }

    @Test fun `board slug is encoded as one path segment`() {
        val request = record("""{"board":{"slug":"a/b"}}""") {
            api.editKanbanBoard(KanbanEditBoardRequest("a/b", "Roadmap", "Type-J", "J", "#FFC629"))
        }
        assertEquals("PATCH", request.method)
        assertEquals("/api/kanban/boards/a%2Fb", request.path)
        val body = json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals("Roadmap", body["name"]!!.jsonPrimitive.content)
        assertEquals("#FFC629", body["color"]!!.jsonPrimitive.content)
        assertTrue("slug" !in body)
    }

    @Test fun `create card separates board query from snake case body`() {
        val request = record("""{"task":{"id":"c1","status":"todo"}}""") {
            api.createKanbanCard(
                KanbanCreateCardRequest(
                    board = "main", title = "Ship Type-J", body = "Deep test", status = "todo",
                    priority = 4, assignee = "reviewer", workspaceKind = "path",
                    workspacePath = "/workspace/type-j", skills = listOf("android"),
                    maxRuntimeSeconds = 900, prerequisiteId = "parent", idempotencyKey = "idem-1",
                ),
            )
        }
        assertEquals("POST", request.method)
        assertEquals("/api/kanban/tasks?board=main", request.path)
        val body = json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals("path", body["workspace_kind"]!!.jsonPrimitive.content)
        assertEquals(900, body["max_runtime_seconds"]!!.jsonPrimitive.content.toInt())
        assertEquals("parent", body["parents"]!!.jsonArray.single().jsonPrimitive.content)
        assertEquals("idem-1", body["idempotency_key"]!!.jsonPrimitive.content)
    }

    @Test fun `bulk archive uses archive flag and ids only`() {
        val request = record("""{"results":[]}""") {
            api.performKanbanBulkAction(
                KanbanBulkActionRequest("main", listOf("a", "b"), KanbanBulkActionType.ARCHIVE),
            )
        }
        val body = json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals(true, body["archive"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(listOf("a", "b"), body["ids"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertTrue("status" !in body)
    }

    @Test fun `running status is rejected before network`() {
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { api.setKanbanCardStatus(KanbanCardStatusRequest("c1", "main", " running ")) }
        }
        assertEquals(0, server.requestCount)
    }

    @Test fun `non json success is rejected`() {
        server.enqueue(MockResponse().setResponseCode(200).addHeader("Content-Type", "text/html").setBody("<html/>"))
        assertThrows(ApiError.Decoding::class.java) { runBlocking { api.kanbanBoards() } }
    }

    @Test fun `dispatch without categories is rejected`() {
        server.enqueue(MockResponse().setResponseCode(200).addHeader("Content-Type", "application/json").setBody("{}"))
        assertThrows(ApiError.Decoding::class.java) {
            runBlocking { api.dispatchKanban(io.github.jcartu.hermex.model.KanbanDispatchRequest("main", false)) }
        }
    }
}
