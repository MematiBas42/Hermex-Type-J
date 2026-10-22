package io.github.jcartu.hermex.ui.feature.memory

import io.github.jcartu.hermex.model.MemorySection
import io.github.jcartu.hermex.model.hermes.BotConnection
import io.github.jcartu.hermex.model.hermes.BotJSON
import io.github.jcartu.hermex.net.hermes.HermesAddress
import io.github.jcartu.hermex.net.hermes.HermesConnection
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class HermesMemoryDataClientTest {
    private lateinit var server: MockWebServer
    private lateinit var host: MemoryHost

    @Before
    fun setUp() {
        host = MemoryHost()
        server = MockWebServer().also {
            it.dispatcher = host
            it.start()
        }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `memory save canonicalizes and confirms the exact fs request shape`() = runBlocking {
        val client = HermesMemoryDataClient(
            http = connection(),
            profile = "default",
            resolveMemoriesFolder = { "/profiles/default/memories" },
        )

        val result = client.saveMemory(
            section = MemorySection.MEMORY,
            content = "\uFEFF new \r\n§\r\nnew\n",
            loaded = "old",
        )

        assertEquals(true, result.ok)
        val reads = host.requests.filter { it.requestUrl?.encodedPath == "/api/fs/read-text" }
        assertEquals(2, reads.size)
        assertEquals("/profiles/default/memories/MEMORY.md", reads[0].requestUrl?.queryParameter("path"))
        val write = host.requests.single { it.requestUrl?.encodedPath == "/api/fs/write-text" }
        assertEquals("POST", write.method)
        val body = BotJSON.parse(write.body.readUtf8())
        assertEquals("/profiles/default/memories/MEMORY.md", body["path"].text)
        assertEquals("new", body["content"].text)
        assertNull(write.getHeader("Origin"))
        assertNull(write.getHeader("Referer"))
    }

    @Test
    fun `soul save checks current content then uses profile soul put`() = runBlocking {
        val client = HermesMemoryDataClient(
            http = connection(),
            profile = "default",
            resolveMemoriesFolder = { error("soul must not resolve a memory folder") },
        )

        client.saveMemory(MemorySection.SOUL, content = "careful and kind", loaded = "old soul")

        val soul = host.requests.filter { it.requestUrl?.encodedPath == "/api/profiles/default/soul" }
        assertEquals(listOf("GET", "PUT"), soul.map { it.method })
        assertEquals("careful and kind", BotJSON.parse(soul.last().body.readUtf8())["content"].text)
        assertNull(soul.last().getHeader("Origin"))
        assertNull(soul.last().getHeader("Referer"))
    }

    @Test
    fun `canonicalizer matches host separators deduplication and scalar count`() {
        assertEquals("one\n§\ntwo", MemoryCanonicalizer.canonical(" one\r\n § \r\ntwo\n§\none "))
        assertEquals(2, MemoryCanonicalizer.scalarCount("👍🏽"))
    }

    private fun connection(): HermesConnection = HermesConnection(
        connection = BotConnection(
            name = "Hermes",
            address = HermesAddress.parse("http://localhost:${server.port}"),
            username = "",
            password = "",
        ),
        httpClient = OkHttpClient(),
    )

    private class MemoryHost : Dispatcher() {
        val requests = CopyOnWriteArrayList<RecordedRequest>()
        private var memory = "old"
        private var soul = "old soul"

        override fun dispatch(request: RecordedRequest): MockResponse {
            requests += request
            val path = request.requestUrl?.encodedPath ?: "/"
            return when (path) {
                "/api/status" -> json("""{"auth_required":false,"version":"0.22.2"}""")
                "/" -> MockResponse().setResponseCode(200)
                    .setBody("<script>window.__HERMES_SESSION_TOKEN__=\"token\";</script>")
                    .addHeader("Content-Type", "text/html")
                "/api/profiles/active" -> json("""{"active":"default","current":"default"}""")
                "/api/fs/read-text" -> json(
                    """{"text":${BotJSON.string(memory).encoded},"binary":false,"truncated":false}""",
                )
                "/api/fs/write-text" -> {
                    val body = BotJSON.parse(request.body.clone().readUtf8())
                    memory = body["content"].text.orEmpty()
                    json("""{"ok":true}""")
                }
                "/api/profiles/default/soul" -> if (request.method == "GET") {
                    json("""{"content":${BotJSON.string(soul).encoded},"exists":true}""")
                } else {
                    soul = BotJSON.parse(request.body.clone().readUtf8())["content"].text.orEmpty()
                    json("""{"ok":true}""")
                }
                else -> MockResponse().setResponseCode(404).setBody("""{"detail":"missing"}""")
            }
        }

        private fun json(body: String): MockResponse = MockResponse()
            .setResponseCode(200)
            .setBody(body)
            .addHeader("Content-Type", "application/json")
    }
}
