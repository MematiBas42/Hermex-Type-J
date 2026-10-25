package io.github.jcartu.hermex.net.hermes

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class HermesSessionTokenInterceptorTest {

    private lateinit var server: MockWebServer
    private lateinit var rawClient: OkHttpClient
    private lateinit var client: OkHttpClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        HermesSessionTokenStore.clear()
        rawClient = OkHttpClient.Builder().build()
        client = OkHttpClient.Builder()
            .addInterceptor(
                HermesSessionTokenInterceptor(
                    tokenStore = HermesSessionTokenStore,
                    rawClientProvider = { rawClient },
                ),
            )
            .build()
    }

    @After
    fun tearDown() {
        server.shutdown()
        HermesSessionTokenStore.clear()
    }

    @Test
    fun attachesCachedSessionTokenAutomatically() {
        val baseUrl = server.url("/")
        HermesSessionTokenStore.set(baseUrl, "test-token-123")

        server.enqueue(MockResponse().setResponseCode(200).setBody("{\"ok\":true}"))

        val req = Request.Builder().url(server.url("/api/status")).build()
        val resp = client.newCall(req).execute()
        assertEquals(200, resp.code)
        resp.close()

        val recorded = server.takeRequest()
        assertEquals("test-token-123", recorded.getHeader(HermesHeaders.SESSION_TOKEN_NAME))
    }

    @Test
    fun scrapesPageTokenOn401AndRetriesSuccessfully() {
        val baseUrl = server.url("/")
        val apiAttempts = AtomicInteger(0)

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: ""
                return when {
                    path == "/" -> {
                        MockResponse().setResponseCode(200).setBody(
                            "<html><script>window.__HERMES_SESSION_TOKEN__=\"scraped-token-xyz\";</script></html>",
                        )
                    }
                    path.startsWith("/api/test") -> {
                        val token = request.getHeader(HermesHeaders.SESSION_TOKEN_NAME)
                        if (token == "scraped-token-xyz") {
                            MockResponse().setResponseCode(200).setBody("{\"success\":true}")
                        } else {
                            apiAttempts.incrementAndGet()
                            MockResponse().setResponseCode(401).setBody("{\"detail\":\"Unauthorized\"}")
                        }
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        val req = Request.Builder().url(server.url("/api/test")).build()
        val resp = client.newCall(req).execute()

        assertEquals(200, resp.code)
        assertEquals("{\"success\":true}", resp.body?.string())
        assertEquals("scraped-token-xyz", HermesSessionTokenStore.get(baseUrl))
    }

    @Test
    fun doesNotOverwriteExplicitAuthorizationHeader() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

        val req = Request.Builder()
            .url(server.url("/api/status"))
            .header("Authorization", "Bearer custom-bearer-abc")
            .build()

        val resp = client.newCall(req).execute()
        assertEquals(200, resp.code)
        resp.close()

        val recorded = server.takeRequest()
        assertEquals("Bearer custom-bearer-abc", recorded.getHeader("Authorization"))
        assertNull(recorded.getHeader(HermesHeaders.SESSION_TOKEN_NAME))
    }
}
