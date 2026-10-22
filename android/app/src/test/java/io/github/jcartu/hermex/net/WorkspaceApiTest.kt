package io.github.jcartu.hermex.net

import io.github.jcartu.hermex.core.prefs.ServerConfig
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

class WorkspaceApiTest {
    private lateinit var server: MockWebServer
    private lateinit var api: HermesApi
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = false }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        api = HermesApi(OkHttpClient(), json, { ServerConfig(server.url("/").toString(), "pw") }, { emptyList() })
    }

    @After fun tearDown() = server.shutdown()

    @Test
    fun `workspace mutations use custom routes and snake case bodies`() {
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("""{"ok":true}"""))
        runBlocking { api.addWorkspace("/srv/app", name = "App", create = true) }
        val request = server.takeRequest()
        assertEquals("/api/workspaces/add", request.path)
        assertEquals("POST", request.method)
        val body = json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals("/srv/app", body["path"]!!.jsonPrimitive.content)
        assertEquals("App", body["name"]!!.jsonPrimitive.content)
        assertEquals(true, body["create"]!!.jsonPrimitive.content.toBoolean())
        assertNull(request.getHeader("Origin"))
        assertNull(request.getHeader("Referer"))
    }

    @Test
    fun `bounded raw file accepts cap and refuses declared body above it`() {
        server.enqueue(MockResponse().setBody(okio.Buffer().write(byteArrayOf(1, 2, 3))))
        val bytes = runBlocking { api.rawFilePreviewData("s1", "report.pdf", limitBytes = 3) }
        assertArrayEquals(byteArrayOf(1, 2, 3), bytes)
        assertEquals("/api/file/raw?session_id=s1&path=report.pdf", server.takeRequest().path)

        server.enqueue(MockResponse().setBody(okio.Buffer().write(byteArrayOf(1, 2, 3, 4))))
        val error = assertThrows(WorkspacePreviewTooLargeException::class.java) {
            runBlocking { api.rawFilePreviewData("s1", "report.pdf", limitBytes = 3) }
        }
        assertEquals(3L, error.limitBytes)
        assertEquals("/api/file/raw?session_id=s1&path=report.pdf", server.takeRequest().path)
    }
}
