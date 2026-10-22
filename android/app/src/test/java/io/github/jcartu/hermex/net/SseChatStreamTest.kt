package io.github.jcartu.hermex.net

import io.github.jcartu.hermex.core.prefs.ServerConfig
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * End-to-end SSE transport test: a scripted `text/event-stream` response must
 * decode into the exact event sequence, heartbeat comment lines must be
 * skipped, `stream_end` is the terminal frame, and the stream request must
 * carry the SSE headers but never `Origin`/`Referer`.
 */
class SseChatStreamTest {

    private lateinit var server: MockWebServer
    private lateinit var api: HermesApi
    private lateinit var stream: SseChatStream

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
    }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val client = OkHttpClient()
        api = HermesApi(
            httpClient = client,
            json = json,
            configProvider = { ServerConfig(server.url("/").toString(), "pw") },
            customHeaderProvider = { emptyList() },
        )
        stream = SseChatStream(
            httpClient = client,
            json = json,
            api = api,
            customHeaderProvider = { emptyList() },
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun enqueueStream(body: String) {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "text/event-stream")
                .setBody(body),
        )
    }

    private fun collect(streamId: String, replayAfterSeq: Int? = null): List<ChatStreamEvent> =
        runBlocking {
            withTimeout(10_000) {
                stream.connect(streamId, replayAfterSeq).toList()
            }
        }

    @Test
    fun scriptedStreamDecodesInOrderAndSurfacesHeartbeats() {
        enqueueStream(
            buildString {
                append("retry: 3000\n")
                append(": heartbeat\n\n")
                append("event: token\ndata: {\"text\":\"Hel\"}\n\n")
                append(": another heartbeat\n\n")
                append("id: 7\nevent: token\ndata: {\"text\":\"lo\"}\n\n")
                append("event: reasoning\ndata: {\"text\":\"thinking\"}\n\n")
                append("event: tool\ndata: {\"name\":\"bash\",\"event_type\":\"start\",\"tid\":\"t-1\"}\n\n")
                append("event: tool_complete\ndata: {\"name\":\"bash\",\"duration\":\"1.5\",\"is_error\":0}\n\n")
                append("event: title\ndata: {\"session_id\":\"s1\",\"title\":\"Greeting\"}\n\n")
                append("event: metering\ndata: {\"tps\":42.5,\"tps_available\":true,\"estimated\":false}\n\n")
                append("event: some_future_event\ndata: {}\n\n")
                append("event: done\ndata: {\"usage\":{}}\n\n")
                append("event: stream_end\ndata: {}\n\n")
            },
        )

        val events = collect("stream-1")

        // Heartbeat comments surface as events (Swift `onComment` → `.heartbeat`),
        // so the coordinator can tell a quiet-but-alive transport from a dead one.
        assertEquals(ChatStreamEvent.Heartbeat, events[0])
        assertEquals(ChatStreamEvent.Token("Hel"), events[1])
        assertEquals(ChatStreamEvent.Heartbeat, events[2])
        assertEquals(ChatStreamEvent.Token("lo"), events[3])
        assertEquals(ChatStreamEvent.Reasoning("thinking"), events[4])
        val tool = events[5] as ChatStreamEvent.ToolStarted
        assertEquals("bash", tool.event.name)
        assertEquals("t-1", tool.event.stableId)
        val complete = events[6] as ChatStreamEvent.ToolCompleted
        assertEquals(1.5, complete.event.duration!!, 0.0)
        assertEquals(false, complete.event.isError)
        assertEquals(ChatStreamEvent.Title(TitleStreamEvent("s1", "Greeting")), events[7])
        val metering = events[8] as ChatStreamEvent.Metering
        assertEquals(42.5, metering.event.displayableTokensPerSecond!!, 0.0)
        assertEquals(ChatStreamEvent.Ignored, events[9])
        val done = events[10] as ChatStreamEvent.Done
        assertNotNull(done.event.usage)
        assertEquals(ChatStreamEvent.StreamEnd, events[11])
        assertEquals("terminal stream_end ends the flow", 12, events.size)

        // The SSE id is tracked as the replay cursor.
        assertEquals("7", stream.lastEventId)
    }

    @Test
    fun parserJoinsMultiLineDataAndDiscardsDatalessFrames() {
        val frames = mutableListOf<Pair<String, String>>()
        val comments = mutableListOf<String>()
        val ids = mutableListOf<String>()
        val parser = SseFrameParser(
            onComment = { comments += it },
            onEventId = { ids += it },
            onFrame = { type, data -> frames += type to data },
        )
        listOf(
            "event: token", "data: {\"a\":", "data:  1}", "id: run:12", "",
            ":keep-alive", "",
            "event: title", "",
            "data: bare\r", "",
            "id:   ", "data: x", "",
        ).forEach(parser::feed)

        assertEquals(listOf("token" to "{\"a\":\n 1}", "message" to "bare", "message" to "x"), frames)
        assertEquals(listOf("keep-alive"), comments)
        assertEquals("blank ids are ignored", listOf("run:12"), ids)
    }

    @Test
    fun streamRequestCarriesSseHeadersButNoBrowserHeaders() {
        enqueueStream("event: stream_end\ndata: {}\n\n")
        collect("stream-1")

        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/api/chat/stream?stream_id=stream-1", request.path)
        assertEquals("text/event-stream", request.getHeader("Accept"))
        assertEquals("no-cache, no-transform", request.getHeader("Cache-Control"))
        assertEquals("identity", request.getHeader("Accept-Encoding"))
        assertNull("Origin must never be sent", request.getHeader("Origin"))
        assertNull("Referer must never be sent", request.getHeader("Referer"))
    }

    @Test
    fun reconnectRequestsReplayAfterSequenceWithoutResendingAnything() {
        enqueueStream("event: stream_end\ndata: {}\n\n")
        collect("stream-1", replayAfterSeq = 41)

        val request = server.takeRequest()
        assertEquals("/api/chat/stream?stream_id=stream-1&replay=1&after_seq=41", request.path)
        // A replay reconnect is a bare GET: nothing is re-sent.
        assertEquals(0L, request.bodySize)
    }

    @Test
    fun cancelFrameDecodesBeforeSocketClose() {
        enqueueStream("event: cancel\ndata: {}\n\n")
        val events = collect("stream-1")
        assertEquals(listOf<ChatStreamEvent>(ChatStreamEvent.Cancelled), events)
    }

    @Test
    fun errorFrameSurfacesServerMessage() {
        enqueueStream("event: apperror\ndata: {\"message\":\"model exploded\"}\n\n")
        val events = collect("stream-1")
        assertEquals(listOf<ChatStreamEvent>(ChatStreamEvent.Error("model exploded")), events)
    }

    @Test
    fun httpFailureEmitsSingleTransportErrorAndEnds() {
        server.enqueue(MockResponse().setResponseCode(404).setBody("nope"))
        val events = collect("stream-1")
        assertEquals(1, events.size)
        assertTrue("expected TransportError, got ${events[0]}", events[0] is ChatStreamEvent.TransportError)
    }

    @Test
    fun statusCheckHitsStreamStatusEndpoint() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody("""{"active":true,"stream_id":"stream-1"}"""),
        )
        val status = runBlocking { stream.statusCheck("stream-1") }
        assertEquals(true, status.active)
        assertEquals("/api/chat/stream/status?stream_id=stream-1", server.takeRequest().path)
    }
}
