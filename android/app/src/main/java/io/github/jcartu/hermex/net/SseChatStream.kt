package io.github.jcartu.hermex.net

import io.github.jcartu.hermex.model.ApprovalPendingResponse
import io.github.jcartu.hermex.model.ChatStreamStatusResponse
import io.github.jcartu.hermex.model.ClarificationPendingResponse
import io.github.jcartu.hermex.model.ContextWindowSnapshot
import io.github.jcartu.hermex.model.SessionDetail
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.BufferedSource
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Port of `Networking/SSEClient.swift` — the chat SSE transport plus the
 * event decoder, on a plain OkHttp call with a line reader that follows the
 * `text/event-stream` grammar (the `okhttp-sse` reader swallows comment
 * lines, and the Swift client needs them).
 *
 * Semantics mirrored from Swift:
 * - No auto-reconnect (`connectionErrorHandler = .shutdown`): a transport
 *   failure emits [ChatStreamEvent.TransportError] and ends the flow. The
 *   caller decides how to recover — check [statusCheck] and, when the stream
 *   is still alive, [connect] again with `replayAfterSeq` = last seen
 *   sequence. NEVER re-send the user message on reconnect.
 * - Unknown event types decode to [ChatStreamEvent.Ignored] instead of
 *   failing the stream.
 * - Comment lines (`: heartbeat`) surface as [ChatStreamEvent.Heartbeat]
 *   (`SSEEventHandler.onComment`): they prove the transport is alive without
 *   semantic progress, which keeps the "Checking stream" chip hidden (#227).
 */
class SseChatStream(
    httpClient: OkHttpClient,
    private val json: Json,
    private val api: HermesApi,
    private val customHeaderProvider: () -> List<CustomHeader> = { CustomHeaderStore.shared.snapshot() },
) {
    /** SSE connections stay open indefinitely, so the read timeout is disabled. */
    private val client = httpClient.newBuilder()
        .readTimeout(0, TimeUnit.SECONDS)
        .build()

    /** Last SSE id delivered to the chat collector, never a frame still buffered on the IO reader. */
    @Volatile
    var lastEventId: String? = null
        private set

    /**
     * Connects to `GET /api/chat/stream?stream_id=…` and emits every decoded
     * event. The flow completes when the server closes the socket (after one
     * of the terminal frames: `stream_end`, `cancel`, `error`, `apperror`) or
     * after a [ChatStreamEvent.TransportError]. Cancelling the collector
     * closes the connection.
     */
    fun connect(streamId: String, replayAfterSeq: Int? = null): Flow<ChatStreamEvent> =
        openEventStream(tracksCursor = true) { api.chatStreamUrl(streamId, replayAfterSeq) }

    /** `GET /api/approval/stream?session_id=…` — the per-session approval feed (`approvalStreamURL`). */
    fun connectApprovalStream(sessionId: String): Flow<ChatStreamEvent> =
        openEventStream { api.approvalStreamUrl(sessionId) }

    /** `GET /api/clarify/stream?session_id=…` — the per-session clarification feed (`clarifyStreamURL`). */
    fun connectClarifyStream(sessionId: String): Flow<ChatStreamEvent> =
        openEventStream { api.clarifyStreamUrl(sessionId) }

    private data class ReceivedEvent(val event: ChatStreamEvent, val cursor: String?)

    private fun openEventStream(tracksCursor: Boolean = false, url: suspend () -> HttpUrl): Flow<ChatStreamEvent> = callbackFlow {
        if (tracksCursor) lastEventId = null

        val resolvedUrl = try {
            url()
        } catch (error: ApiError) {
            trySend(ReceivedEvent(ChatStreamEvent.TransportError(error.message), null))
            close()
            awaitClose { }
            return@callbackFlow
        }
        val builder = Request.Builder().url(resolvedUrl)
        // Custom headers merged underneath the built-ins so the built-ins win on
        // collision; an empty list leaves the built-in three unchanged (#255).
        val headers = customHeaderProvider().mergedUnder(
            mapOf(
                "Accept" to "text/event-stream",
                "Cache-Control" to "no-cache, no-transform",
                "Accept-Encoding" to "identity",
            ),
        )
        for ((name, value) in headers) {
            builder.header(name, value)
        }

        val call = client.newCall(builder.build())
        val reader = launch(Dispatchers.IO) {
            try {
                readEvents(call) { event, cursor -> trySend(ReceivedEvent(event, cursor)) }
            } finally {
                close()
            }
        }

        awaitClose {
            call.cancel()
            reader.cancel()
        }
    }.buffer(Channel.UNLIMITED).map { received ->
        if (tracksCursor && received.cursor != null) lastEventId = received.cursor
        received.event
    }

    /** Executes [call] and feeds every frame through [emit]; returns at EOF, failure or cancellation. */
    private fun readEvents(call: Call, emit: (ChatStreamEvent, String?) -> Unit) {
        val response = try {
            call.execute()
        } catch (error: IOException) {
            if (!call.isCanceled()) emit(ChatStreamEvent.TransportError(error.message ?: "The stream connection failed."), null)
            return
        }
        response.use {
            if (!it.isSuccessful) {
                emit(ChatStreamEvent.TransportError("The stream returned HTTP ${it.code}."), null)
                return
            }
            val source = it.body?.source()
            if (source == null) {
                emit(ChatStreamEvent.TransportError("The stream connection failed."), null)
                return
            }
            var frameId: String? = null
            var deliveredId: String? = null
            val parser = SseFrameParser(
                onComment = { emit(ChatStreamEvent.Heartbeat, deliveredId) },
                onEventId = { id -> frameId = id },
                onFrame = { type, data ->
                    deliveredId = frameId
                    emit(SseEventDecoder.decode(json, type, data), deliveredId)
                },
            )
            try {
                readLines(source, parser)
            } catch (error: IOException) {
                if (!call.isCanceled()) {
                    emit(ChatStreamEvent.TransportError(error.message ?: "The stream connection failed."), null)
                }
            }
        }
    }

    private fun readLines(source: BufferedSource, parser: SseFrameParser) {
        while (true) {
            val line = source.readUtf8Line() ?: return
            parser.feed(line)
        }
    }

    /**
     * `GET /api/chat/stream/status` — ask whether the stream is still alive
     * and replayable before reconnecting, instead of re-sending anything.
     */
    suspend fun statusCheck(streamId: String): ChatStreamStatusResponse =
        api.chatStreamStatus(streamId)
}

/**
 * The `text/event-stream` line grammar: `field: value` lines accumulate into
 * one frame dispatched on a blank line (`data` lines join with `\n`; a frame
 * without data is discarded, as the W3C algorithm and OkHttp's reader do);
 * lines starting with `:` are comments; `retry` is ignored because the
 * consumers own reconnection.
 */
internal class SseFrameParser(
    private val onComment: (String) -> Unit,
    private val onEventId: (String) -> Unit,
    private val onFrame: (type: String, data: String) -> Unit,
) {
    private val data = StringBuilder()
    private var eventType = ""
    private var hasData = false

    fun feed(rawLine: String) {
        val line = rawLine.removeSuffix("\r")
        if (line.isEmpty()) {
            dispatch()
            return
        }
        if (line.startsWith(":")) {
            onComment(line.drop(1).trimStart())
            return
        }
        val colon = line.indexOf(':')
        val field = if (colon >= 0) line.substring(0, colon) else line
        var value = if (colon >= 0) line.substring(colon + 1) else ""
        if (value.startsWith(" ")) value = value.drop(1)
        when (field) {
            "data" -> {
                if (hasData) data.append('\n')
                data.append(value)
                hasData = true
            }
            "event" -> eventType = value
            "id" -> {
                val id = value.trim()
                if (id.isNotEmpty() && !id.contains('\u0000')) onEventId(id)
            }
            else -> Unit
        }
    }

    private fun dispatch() {
        val type = eventType.ifEmpty { "message" }
        val payload = data.toString()
        val shouldDispatch = hasData
        data.setLength(0)
        eventType = ""
        hasData = false
        if (shouldDispatch) onFrame(type, payload)
    }
}

/** Port of the Swift `SSEEvent` enum — one case per frame the chat stream emits. */
sealed class ChatStreamEvent {
    data class Token(val text: String) : ChatStreamEvent()
    data class InterimAssistant(val event: InterimAssistantStreamEvent) : ChatStreamEvent()
    data class Reasoning(val text: String) : ChatStreamEvent()
    data class ToolStarted(val event: ToolStreamEvent) : ChatStreamEvent()
    data class ToolCompleted(val event: ToolStreamEvent) : ChatStreamEvent()
    data class Title(val event: TitleStreamEvent) : ChatStreamEvent()
    data class Metering(val event: MeteringStreamEvent) : ChatStreamEvent()
    data class Done(val event: DoneStreamEvent) : ChatStreamEvent()
    data class ApprovalPending(val response: ApprovalPendingResponse) : ChatStreamEvent()
    data class ClarificationPending(val response: ClarificationPendingResponse) : ChatStreamEvent()
    data class PendingSteerLeftover(val text: String) : ChatStreamEvent()
    object StreamEnd : ChatStreamEvent()
    object Cancelled : ChatStreamEvent()
    data class Error(val message: String) : ChatStreamEvent()
    data class TransportError(val message: String) : ChatStreamEvent()

    /** Swift `onComment`: transport liveness without semantic progress. */
    object Heartbeat : ChatStreamEvent()
    object Ignored : ChatStreamEvent()
}

data class TitleStreamEvent(
    val sessionId: String? = null,
    val title: String? = null,
)

data class ToolStreamEvent(
    val eventType: String? = null,
    val name: String? = null,
    val preview: String? = null,
    val args: Map<String, JsonElement>? = null,
    val duration: Double? = null,
    val isError: Boolean? = null,
    val stableId: String? = null,
)

data class InterimAssistantStreamEvent(
    val text: String? = null,
    val alreadyStreamed: Boolean? = null,
)

data class MeteringStreamEvent(
    val tokensPerSecond: Double? = null,
    val isTokensPerSecondAvailable: Boolean? = null,
    val isEstimated: Boolean? = null,
    val sessionId: String? = null,
) {
    val displayableTokensPerSecond: Double?
        get() {
            if (isTokensPerSecondAvailable != true) return null
            if (isEstimated == true) return null
            val tps = tokensPerSecond ?: return null
            if (!tps.isFinite() || tps <= 0) return null
            return tps
        }
}

data class DoneStreamEvent(
    val usage: ContextWindowSnapshot? = null,
    val session: SessionDetail? = null,
)

/**
 * Port of `SSEEventDecoder` — maps a raw `(event type, data)` frame onto a
 * [ChatStreamEvent] with exactly the Swift tolerance: malformed payloads
 * degrade to empty events (or the documented transport-error messages) and
 * unknown event types are ignored, never thrown.
 */
object SseEventDecoder {

    fun decode(json: Json, eventType: String, data: String): ChatStreamEvent = when (eventType) {
        "token" -> ChatStreamEvent.Token(parseObject(json, data)?.strictString("text") ?: "")

        "interim_assistant" -> ChatStreamEvent.InterimAssistant(
            parseObject(json, data)?.let(::parseInterimAssistant) ?: InterimAssistantStreamEvent(),
        )

        "reasoning" -> ChatStreamEvent.Reasoning(parseObject(json, data)?.strictString("text") ?: "")

        "tool" -> ChatStreamEvent.ToolStarted(
            parseObject(json, data)?.let(::parseTool) ?: ToolStreamEvent(),
        )

        "tool_complete" -> ChatStreamEvent.ToolCompleted(
            parseObject(json, data)?.let(::parseTool) ?: ToolStreamEvent(),
        )

        "title" -> ChatStreamEvent.Title(
            parseObject(json, data)?.let(::parseTitle) ?: TitleStreamEvent(),
        )

        "metering" -> ChatStreamEvent.Metering(
            parseObject(json, data)?.let(::parseMetering) ?: MeteringStreamEvent(),
        )

        "done" -> parseDone(json, data)

        "initial" ->
            if (ClarificationPendingResponse.containsClarificationMarkers(data)) {
                ChatStreamEvent.ClarificationPending(ClarificationPendingResponse.streamPayload(json, data))
            } else {
                ChatStreamEvent.ApprovalPending(ApprovalPendingResponse.streamPayload(json, data))
            }

        "approval" -> ChatStreamEvent.ApprovalPending(ApprovalPendingResponse.streamPayload(json, data))

        "clarify" -> ChatStreamEvent.ClarificationPending(ClarificationPendingResponse.streamPayload(json, data))

        "pending_steer_leftover" -> ChatStreamEvent.PendingSteerLeftover(
            parseObject(json, data)?.strictString("text") ?: "",
        )

        "stream_end" -> ChatStreamEvent.StreamEnd

        "cancel" -> ChatStreamEvent.Cancelled

        // "apperror" is one of the four socket-closing frames (stream_end, cancel,
        // error, apperror). The docs describe its payload as {error, type, session,
        // terminal_state?} while the pinned upstream emits {message, type, hint,
        // details, …}; decoding both `error` and `message` covers either shape.
        // Mapping it onto Error reuses the existing terminal error path
        // (surface the message, finish the stream) unchanged.
        "error", "apperror" -> parseError(json, data)

        else -> ChatStreamEvent.Ignored
    }

    private fun parseDone(json: Json, data: String): ChatStreamEvent {
        val payload = parseObject(json, data)
            ?: return ChatStreamEvent.TransportError("The stream returned a malformed completion event.")

        return try {
            ChatStreamEvent.Done(
                DoneStreamEvent(
                    usage = payload["usage"]
                        ?.takeUnless { it is JsonNull }
                        ?.let { json.decodeFromJsonElement<ContextWindowSnapshot>(it) },
                    session = payload["session"]
                        ?.takeUnless { it is JsonNull }
                        ?.let { json.decodeFromJsonElement<SessionDetail>(it) },
                ),
            )
        } catch (_: IllegalArgumentException) {
            ChatStreamEvent.TransportError("The stream returned a malformed completion event.")
        }
    }

    private fun parseError(json: Json, data: String): ChatStreamEvent {
        val payload = parseObject(json, data)
            ?: return ChatStreamEvent.Error("The stream returned a malformed error event.")
        val error = payload.strictStringOrInvalid("error")
        val message = payload.strictStringOrInvalid("message")
        if (error === Invalid || message === Invalid) {
            return ChatStreamEvent.Error("The stream returned a malformed error event.")
        }
        return ChatStreamEvent.Error(
            error as String? ?: message as String? ?: "The stream returned an error.",
        )
    }

    private fun parseTool(payload: JsonObject) = ToolStreamEvent(
        eventType = payload.lossyString("event_type"),
        name = payload.lossyString("name"),
        preview = payload.lossyString("preview"),
        args = payload["args"] as? JsonObject,
        duration = payload.lossyDouble("duration"),
        isError = payload.lossyBool("is_error"),
        stableId = listOf("tid", "id", "tool_call_id", "tool_use_id", "call_id")
            .firstNotNullOfOrNull { key -> payload.lossyString(key)?.trim()?.ifEmpty { null } },
    )

    /**
     * The Swift payload uses synthesized (strict) decoding: a type-mismatched
     * field fails the whole payload, which degrades to an empty event.
     */
    private fun parseTitle(payload: JsonObject): TitleStreamEvent {
        val sessionId = payload.strictStringOrInvalid("session_id")
        val title = payload.strictStringOrInvalid("title")
        if (sessionId === Invalid || title === Invalid) return TitleStreamEvent()
        return TitleStreamEvent(sessionId = sessionId as String?, title = title as String?)
    }

    private fun parseInterimAssistant(payload: JsonObject) = InterimAssistantStreamEvent(
        text = payload.lossyString("text"),
        alreadyStreamed = payload.lossyBool("already_streamed"),
    )

    private fun parseMetering(payload: JsonObject) = MeteringStreamEvent(
        tokensPerSecond = payload.lossyDouble("tps"),
        isTokensPerSecondAvailable = payload.lossyBool("tps_available"),
        isEstimated = payload.lossyBool("estimated"),
        sessionId = payload.lossyString("session_id"),
    )

    private fun parseObject(json: Json, data: String): JsonObject? =
        runCatching { json.parseToJsonElement(data) }.getOrNull() as? JsonObject

    /** Marker distinguishing "field absent/null" from "field present with the wrong type". */
    private object Invalid

    /** Strict `String?` decode: only a JSON string yields a value, anything else is null. */
    private fun JsonObject.strictString(key: String): String? {
        val primitive = this[key] as? JsonPrimitive ?: return null
        return if (primitive.isString) primitive.content else null
    }

    /** Returns null (absent/JSON null), the string, or [Invalid] for a type mismatch. */
    private fun JsonObject.strictStringOrInvalid(key: String): Any? {
        val element = this[key] ?: return null
        if (element is JsonNull) return null
        val primitive = element as? JsonPrimitive ?: return Invalid
        return if (primitive.isString) primitive.content else Invalid
    }

    /** Mirror of Swift `decodeLossyStringIfPresent`: string, or number/bool rendered as text. */
    private fun JsonObject.lossyString(key: String): String? {
        val primitive = this[key] as? JsonPrimitive ?: return null
        if (primitive is JsonNull) return null
        return primitive.content
    }

    /** Mirror of Swift `decodeLossyDoubleIfPresent`: number, or a numeric string. */
    private fun JsonObject.lossyDouble(key: String): Double? {
        val primitive = this[key] as? JsonPrimitive ?: return null
        if (primitive is JsonNull) return null
        return primitive.content.trim().toDoubleOrNull()
    }

    /**
     * Mirror of Swift `decodeLossyBoolIfPresent`: bool, 0/1 numbers, or the
     * usual truthy/falsy strings; anything else is null.
     */
    private fun JsonObject.lossyBool(key: String): Boolean? {
        val primitive = this[key] as? JsonPrimitive ?: return null
        if (primitive is JsonNull) return null
        return when (primitive.content.trim().lowercase()) {
            "true", "1", "yes" -> true
            "false", "0", "no" -> false
            else -> null
        }
    }
}
