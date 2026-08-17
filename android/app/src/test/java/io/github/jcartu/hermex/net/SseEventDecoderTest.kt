package io.github.jcartu.hermex.net

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Event decode matrix, mirroring `SSEEventDecoder` in `SSEClient.swift`:
 * every event type it handles, with the same tolerance — malformed payloads
 * degrade to empty events or the documented error messages, never throw.
 */
class SseEventDecoderTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
    }

    private fun decode(type: String, data: String) = SseEventDecoder.decode(json, type, data)

    // MARK: token / reasoning / pending_steer_leftover

    @Test
    fun tokenDecodesText() {
        assertEquals(ChatStreamEvent.Token("Hel"), decode("token", """{"text":"Hel"}"""))
    }

    @Test
    fun tokenDegradesToEmptyString() {
        assertEquals(ChatStreamEvent.Token(""), decode("token", """{}"""))
        assertEquals(ChatStreamEvent.Token(""), decode("token", """{"text":null}"""))
        assertEquals(ChatStreamEvent.Token(""), decode("token", """{"text":5}"""))
        assertEquals(ChatStreamEvent.Token(""), decode("token", "not json"))
    }

    @Test
    fun reasoningDecodesLikeToken() {
        assertEquals(ChatStreamEvent.Reasoning("thinking"), decode("reasoning", """{"text":"thinking"}"""))
        assertEquals(ChatStreamEvent.Reasoning(""), decode("reasoning", "garbage"))
    }

    @Test
    fun pendingSteerLeftoverCarriesText() {
        assertEquals(
            ChatStreamEvent.PendingSteerLeftover("queued"),
            decode("pending_steer_leftover", """{"text":"queued"}"""),
        )
    }

    // MARK: interim_assistant (lossy)

    @Test
    fun interimAssistantDecodesLossily() {
        val event = decode("interim_assistant", """{"text":5,"already_streamed":"yes"}""")
        assertEquals(
            ChatStreamEvent.InterimAssistant(InterimAssistantStreamEvent(text = "5", alreadyStreamed = true)),
            event,
        )
    }

    @Test
    fun interimAssistantMalformedPayloadIsEmpty() {
        assertEquals(
            ChatStreamEvent.InterimAssistant(InterimAssistantStreamEvent()),
            decode("interim_assistant", "not json"),
        )
    }

    // MARK: tool / tool_complete

    @Test
    fun toolStartedDecodesLossyFieldsAndStableId() {
        val event = decode(
            "tool",
            """{"event_type":"start","name":"bash","preview":"ls","duration":"1.5","is_error":0,"tid":"  t-1  "}""",
        ) as ChatStreamEvent.ToolStarted
        assertEquals("start", event.event.eventType)
        assertEquals("bash", event.event.name)
        assertEquals("ls", event.event.preview)
        assertEquals(1.5, event.event.duration!!, 0.0)
        assertEquals(false, event.event.isError)
        assertEquals("t-1", event.event.stableId)
    }

    @Test
    fun toolStableIdFallbackOrderMatchesSwift() {
        fun stableId(payload: String) =
            (decode("tool", payload) as ChatStreamEvent.ToolStarted).event.stableId

        assertEquals("a", stableId("""{"id":"a","call_id":"b"}"""))
        assertEquals("x", stableId("""{"tool_use_id":" x "}"""))
        assertEquals("real", stableId("""{"tid":"   ","id":"real"}"""))
        assertNull(stableId("""{"name":"bash"}"""))
    }

    @Test
    fun toolCompleteMalformedPayloadIsEmpty() {
        assertEquals(
            ChatStreamEvent.ToolCompleted(ToolStreamEvent()),
            decode("tool_complete", "not json"),
        )
    }

    // MARK: title (strict, whole payload degrades on mismatch)

    @Test
    fun titleDecodesSessionIdAndTitle() {
        assertEquals(
            ChatStreamEvent.Title(TitleStreamEvent(sessionId = "s1", title = "Greeting")),
            decode("title", """{"session_id":"s1","title":"Greeting"}"""),
        )
    }

    @Test
    fun titleTypeMismatchEmptiesWholePayload() {
        assertEquals(
            ChatStreamEvent.Title(TitleStreamEvent()),
            decode("title", """{"session_id":"s1","title":5}"""),
        )
    }

    // MARK: metering

    @Test
    fun meteringDecodesLossily() {
        val event = decode("metering", """{"tps":"42.5","tps_available":1,"estimated":false,"session_id":"s1"}""")
            as ChatStreamEvent.Metering
        assertEquals(42.5, event.event.tokensPerSecond!!, 0.0)
        assertEquals(true, event.event.isTokensPerSecondAvailable)
        assertEquals(false, event.event.isEstimated)
        assertEquals("s1", event.event.sessionId)
        assertEquals(42.5, event.event.displayableTokensPerSecond!!, 0.0)
    }

    @Test
    fun displayableTokensPerSecondGuardsMatchSwift() {
        assertNull(MeteringStreamEvent(tokensPerSecond = 42.5).displayableTokensPerSecond)
        assertNull(
            MeteringStreamEvent(
                tokensPerSecond = 42.5,
                isTokensPerSecondAvailable = true,
                isEstimated = true,
            ).displayableTokensPerSecond,
        )
        assertNull(
            MeteringStreamEvent(
                tokensPerSecond = 0.0,
                isTokensPerSecondAvailable = true,
            ).displayableTokensPerSecond,
        )
    }

    // MARK: done

    @Test
    fun doneWithEmptyObjectHasNoUsageOrSession() {
        assertEquals(ChatStreamEvent.Done(DoneStreamEvent()), decode("done", "{}"))
    }

    @Test
    fun doneDecodesUsageSnapshot() {
        val event = decode("done", """{"usage":{"input_tokens":123}}""") as ChatStreamEvent.Done
        assertNotNull(event.event.usage)
        assertEquals(123, event.event.usage?.inputTokens)
        assertNull(event.event.session)
    }

    @Test
    fun doneDecodesSessionDetail() {
        val event = decode("done", """{"session":{"session_id":"s1"}}""") as ChatStreamEvent.Done
        assertEquals("s1", event.event.session?.sessionId)
    }

    @Test
    fun malformedDoneIsTransportError() {
        assertEquals(
            ChatStreamEvent.TransportError("The stream returned a malformed completion event."),
            decode("done", "[]"),
        )
        assertEquals(
            ChatStreamEvent.TransportError("The stream returned a malformed completion event."),
            decode("done", "not json"),
        )
    }

    // MARK: initial / approval / clarify routing

    @Test
    fun initialWithClarificationMarkersRoutesToClarification() {
        val event = decode("initial", """{"pending":{"question":"Which one?"}}""")
        assertTrue("expected ClarificationPending, got $event", event is ChatStreamEvent.ClarificationPending)
    }

    @Test
    fun initialWithoutMarkersRoutesToApproval() {
        val event = decode("initial", """{"pending":{"command":"rm -rf"}}""")
        assertTrue("expected ApprovalPending, got $event", event is ChatStreamEvent.ApprovalPending)
    }

    @Test
    fun approvalAndClarifyEventsRouteDirectly() {
        assertTrue(decode("approval", """{"pending_count":1}""") is ChatStreamEvent.ApprovalPending)
        assertTrue(decode("clarify", """{"pending_count":1}""") is ChatStreamEvent.ClarificationPending)
    }

    // MARK: terminal frames

    @Test
    fun terminalFramesDecode() {
        assertEquals(ChatStreamEvent.StreamEnd, decode("stream_end", ""))
        assertEquals(ChatStreamEvent.Cancelled, decode("cancel", ""))
    }

    @Test
    fun errorPrefersErrorThenMessage() {
        assertEquals(ChatStreamEvent.Error("x"), decode("error", """{"error":"x","message":"y"}"""))
        assertEquals(ChatStreamEvent.Error("y"), decode("error", """{"message":"y"}"""))
        assertEquals(ChatStreamEvent.Error("The stream returned an error."), decode("error", "{}"))
    }

    @Test
    fun malformedErrorPayloadGetsMalformedMessage() {
        assertEquals(
            ChatStreamEvent.Error("The stream returned a malformed error event."),
            decode("error", "not json"),
        )
        assertEquals(
            ChatStreamEvent.Error("The stream returned a malformed error event."),
            decode("error", """{"error":5}"""),
        )
    }

    @Test
    fun apperrorMapsOntoErrorPath() {
        assertEquals(ChatStreamEvent.Error("boom"), decode("apperror", """{"message":"boom"}"""))
    }

    @Test
    fun unknownEventTypesAreIgnored() {
        assertEquals(ChatStreamEvent.Ignored, decode("some_future_event", """{"x":1}"""))
        assertEquals(ChatStreamEvent.Ignored, decode("message", ""))
    }
}
