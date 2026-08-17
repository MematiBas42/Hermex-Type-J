package io.github.jcartu.hermex.net

import io.github.jcartu.hermex.core.prefs.ServerConfig
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Response decoding + error mapping, mirroring `APIError.swift`: 401 →
 * Unauthorized, other non-2xx → Http with the raw body (and the documented
 * user-facing messages), undecodable 2xx → Decoding, transport → Network.
 */
class HermesApiErrorMappingTest {

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

    private fun failWith(code: Int, body: String = ""): ApiError {
        server.enqueue(MockResponse().setResponseCode(code).setBody(body))
        return try {
            runBlocking { api.memory() }
            fail("expected ApiError for HTTP $code")
            error("unreachable")
        } catch (error: ApiError) {
            error
        }
    }

    @Test
    fun status401MapsToUnauthorized() {
        val error = failWith(401)
        assertTrue(error is ApiError.Unauthorized)
        assertEquals("The password was rejected. Check the server password and try again.", error.message)
    }

    @Test
    fun status403MapsToHttpWithAccessMessage() {
        val error = failWith(403)
        assertEquals(403, (error as ApiError.Http).statusCode)
        assertEquals("The server refused access. Check the server password and permissions.", error.message)
    }

    @Test
    fun status500MapsToHttpWithServerErrorMessage() {
        val error = failWith(500, """{"error":"boom"}""")
        assertEquals(500, (error as ApiError.Http).statusCode)
        assertEquals("The Hermes server hit an internal error. Check the server logs, then try again.", error.message)
        assertEquals("boom", error.serverMessage)
    }

    @Test
    fun status400SurfacesServerMessage() {
        val error = failWith(400, """{"error":"bad workspace"}""")
        assertEquals("The server rejected the request: bad workspace", error.message)
    }

    @Test
    fun status400WithoutPayloadUsesGenericMessage() {
        val error = failWith(400, "not json")
        assertEquals("The server rejected the request.", error.message)
    }

    @Test
    fun vanishedSession404GetsFriendlyMessage() {
        val error = failWith(404, """{"error":"Session not found"}""")
        assertEquals(
            "That session no longer exists on the server. Reopen another session or create a new one.",
            error.message,
        )
    }

    @Test
    fun plain404IsEndpointNotFound() {
        val error = failWith(404, """{"error":"nope"}""")
        assertEquals(
            "The server endpoint was not found. Check that the URL points to a Hermes Web UI server.",
            error.message,
        )
    }

    @Test
    fun missingStream404IsDetected() {
        val error = failWith(404, """{"error":"stream not found"}""")
        assertTrue(error.indicatesMissingStream)
        assertFalse(failWith(404, """{"error":"other"}""").indicatesMissingStream)
    }

    @Test
    fun conflict409ExposesActiveStreamIdAndStaleFlag() {
        val busy = failWith(409, """{"error":"busy","active_stream_id":"  stream-7  "}""")
        assertEquals("stream-7", busy.activeStreamId)
        assertFalse(busy.indicatesExpiredPendingPrompt)

        val stale = failWith(409, """{"stale":true,"error":"expired"}""")
        assertTrue(stale.indicatesExpiredPendingPrompt)
        assertNull(stale.activeStreamId)
    }

    @Test
    fun serverCodeAndMessageFallbacksMatchSwiftPriority() {
        // error > message > detail, trimmed, empty → null.
        val error = failWith(422, """{"message":"  from message  ","detail":"from detail","code":"E42"}""")
        assertEquals("from message", error.serverMessage)
        assertEquals("E42", error.serverCode)
        assertEquals("Server returned HTTP 422: from message", error.message)
    }

    @Test
    fun transientGatewayStatusesGetTunnelMessage() {
        for (code in intArrayOf(502, 503, 504)) {
            val error = failWith(code)
            assertEquals(
                "The server or Cloudflare tunnel is unavailable. Check that the Mac is awake, hermes-webui is running, and the tunnel is connected.",
                error.message,
            )
        }
    }

    @Test
    fun undecodableSuccessBodyMapsToDecoding() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("<html>not json</html>"))
        try {
            runBlocking { api.memory() }
            fail("expected decoding failure")
        } catch (error: ApiError) {
            assertTrue("expected Decoding, got $error", error is ApiError.Decoding)
            assertEquals("The server response could not be read.", error.message)
        }
    }

    @Test
    fun unknownFieldsInSuccessBodyAreIgnored() {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"memory":"m","totally_new_server_field":{"x":1}}"""),
        )
        val response = runBlocking { api.memory() }
        assertEquals("m", response.memory)
    }

    @Test
    fun connectionFailureMapsToNetwork() {
        val dead = HermesApi(
            httpClient = OkHttpClient.Builder()
                .connectTimeout(250, TimeUnit.MILLISECONDS)
                .build(),
            json = json,
            // TEST-NET-1 address: connect fails or times out, nothing listens there.
            configProvider = { ServerConfig("http://127.0.0.1:9/", "pw") },
            customHeaderProvider = { emptyList() },
        )
        try {
            runBlocking { dead.memory() }
            fail("expected network failure")
        } catch (error: ApiError) {
            assertTrue("expected Network, got $error", error is ApiError.Network)
        }
    }

    @Test
    fun blankOrUnparseableBaseUrlFailsBeforeNetwork() {
        for (bad in listOf("", "   ", "not a url", "ftp://host")) {
            val broken = HermesApi(
                httpClient = OkHttpClient(),
                json = json,
                configProvider = { ServerConfig(bad, "pw") },
                customHeaderProvider = { emptyList() },
            )
            try {
                runBlocking { broken.memory() }
                fail("expected invalid URL failure for '$bad'")
            } catch (error: ApiError) {
                assertTrue("expected InvalidServerUrl for '$bad', got $error", error is ApiError.InvalidServerUrl)
            }
        }
    }
}
