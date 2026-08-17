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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class VoiceApiTest {
    private lateinit var server: MockWebServer
    private lateinit var api: HermesApi
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    @Before fun setUp() {
        server = MockWebServer(); server.start()
        api = HermesApi(OkHttpClient(), json, { ServerConfig(server.url("/").toString(), "pw") }, { emptyList() })
    }
    @After fun tearDown() = server.shutdown()

    @Test fun `tts sends explicit English voice and returns bytes`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).addHeader("Content-Type", "audio/mpeg").setBody("audio"))
        assertEquals("audio", api.synthesizeSpeech("Hello", "en-US-AriaNeural").toString(Charsets.UTF_8))
        val request = server.takeRequest()
        assertEquals("/api/tts", request.path)
        assertEquals("audio/mpeg", request.getHeader("Accept"))
        val body = json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals("Hello", body["text"]!!.jsonPrimitive.content)
        assertEquals("en-US-AriaNeural", body["voice"]!!.jsonPrimitive.content)
    }

    @Test fun `tts maps non success with server body`() {
        server.enqueue(MockResponse().setResponseCode(429).setBody("rate limited"))
        val error = assertThrows(ApiError.Http::class.java) { runBlocking { api.synthesizeSpeech("Hello", "voice") } }
        assertEquals(429, error.statusCode)
        assertEquals("rate limited", error.body)
    }

    @Test fun `transcribe sends only multipart file and decodes non-2xx error envelope`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(503).addHeader("Content-Type", "application/json")
                .setBody("""{"error":"STT unavailable"}"""),
        )
        val response = api.transcribeAudio("voice".toByteArray(), "note.m4a")
        assertEquals("STT unavailable", response.error)
        val request = server.takeRequest()
        assertEquals("/api/transcribe", request.path)
        val body = request.body.readUtf8()
        assertTrue(body.contains("name=\"file\""))
        assertTrue(body.contains("filename=\"note.m4a\""))
        assertFalse(body.contains("session_id"))
    }

    @Test fun `transcribe maps 401 before body decoding`() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"login"}"""))
        assertThrows(ApiError.Unauthorized::class.java) {
            runBlocking { api.transcribeAudio(byteArrayOf(1), "note.m4a") }
        }
    }
}
