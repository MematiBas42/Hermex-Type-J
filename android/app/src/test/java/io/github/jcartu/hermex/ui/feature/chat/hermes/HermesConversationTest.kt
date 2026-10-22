package io.github.jcartu.hermex.ui.feature.chat.hermes

import io.github.jcartu.hermex.model.hermes.*
import io.github.jcartu.hermex.net.hermes.*
import io.github.jcartu.hermex.ui.feature.chat.ChatUiState
import io.github.jcartu.hermex.ui.feature.chat.SendMode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test

/** Scripted BotClient-contract fixture; production always uses BotClientTransport. */
internal class HermesChatFixtureWire : BotTransport {
    override val replayEpoch = "epoch"
    override val serverVersion = "0.22.2"
    override val unavailableMethods = emptySet<String>()
    override var onEvent: ((BotJSON) -> Unit)? = null
    override var onDisconnect: ((Throwable) -> Unit)? = null
    val calls = mutableListOf<HermesCall>()
    var runtime = "runtime-1"
    var connects = 0
    var closes = 0
    var handler: ((HermesCall) -> BotJSON?)? = null
    var messages = emptyList<BotJSON>()
    override suspend fun connect() { connects += 1 }
    override suspend fun close() { closes += 1 }
    override suspend fun call(call: HermesCall, validateDispatch: (() -> Unit)?): BotJSON {
        validateDispatch?.invoke()
        calls += call
        handler?.invoke(call)?.let { return it }
        return when (call) {
            is HermesCall.SessionResume -> BotJSON.parse("""{"session_id":"$runtime","session_key":"stored","running":false,"info":{"running":false},"open_requests":[]}""")
            is HermesCall.SessionEventsSince -> BotJSON.parse("""{"latest_seq":0,"epoch":"epoch","truncated":false,"events":[],"open_requests":[]}""")
            is HermesCall.ModelOptions -> BotJSON.parse("""{"providers":[],"model":"model","provider":"provider"}""")
            is HermesCall.ProfilesList -> BotJSON.parse("""{"profiles":[{"name":"default"}]}""")
            is HermesCall.CommandsCatalog -> BotJSON.parse("""{"pairs":[],"canon":{},"skills":{}}""")
            is HermesCall.SubagentList -> BotJSON.parse("""{"subagents":[]}""")
            is HermesCall.SessionControlRead -> BotJSON.parse("""{"control":{}}""")
            is HermesCall.PromptSubmit -> BotJSON.parse("""{"status":"streaming"}""")
            else -> throw AssertionError("Unscripted call ${call.method}")
        }
    }
    override suspend fun keepPlatformNote(profile: String) = Unit
    override suspend fun currentProfile() = "default"
    override suspend fun sessionMessages(key: String, profile: String, offset: Int?) = messages
    override suspend fun sessionRow(key: String, profile: String): BotJSON? = null
    override suspend fun sessionPage(profile: String, offset: Int, archived: Boolean) = HermesSessionPage(emptyList(), 0, 0)
    override suspend fun artifactData(path: String, context: BotArtifactContext, limit: Int?): ByteArray = error("No artifact fixture")
    override suspend fun uploadImage(data: ByteArray, filename: String, context: BotArtifactContext): String = error("No upload fixture")
    override suspend fun speech(text: String, profile: String): ByteArray = error("No speech fixture")
    fun emit(type: String, seq: Int, payload: String = "{}") {
        onEvent?.invoke(BotJSON.parse("""{"type":"$type","session_id":"$runtime","seq":$seq,"payload":$payload}"""))
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class HermesConversationTest {
    private fun connection() = BotConnection(name = "Fixture", address = "https://example.invalid/".toHttpUrl(), username = "", password = "")

    @Test fun `idle reclaim reattaches stored identity before another deliberate send`() = runTest {
        val wire = HermesChatFixtureWire()
        val state = MutableStateFlow(ChatUiState())
        val engine = HermesConversation("https://example.invalid", connection(), ConversationTarget.Session("default", "stored"), wire, backgroundScope)
        val turn = HermesChatTurnCoordinator(engine, state)
        turn.activate()
        runCurrent()
        assertEquals(HermesConversation.ConnectionState.CONNECTED, engine.connectionState)
        val old = engine.generation
        wire.runtime = "runtime-2"
        wire.onEvent?.invoke(BotJSON.parse("""{"type":"session.reclaimed","payload":{"session_id":"runtime-1","stored_session_id":"stored","reason":"idle_timeout"}}"""))
        runCurrent()
        assertTrue(engine.generation > old)
        assertEquals("runtime-2", engine.runtime)
        assertEquals(2, wire.connects)
        assertEquals(0, wire.calls.count { it is HermesCall.PromptSubmit })
        assertEquals(BotPromptOutcome.STARTED, turn.submit("next", BotPromptMode.SEND))
        assertEquals("runtime-2", (wire.calls.last { it is HermesCall.PromptSubmit } as HermesCall.PromptSubmit).sessionID)
        turn.suspendConnection()
    }

    @Test fun `sequence duplicate does not duplicate reply and unrelated runtime is ignored`() = runTest {
        val wire = HermesChatFixtureWire()
        val state = MutableStateFlow(ChatUiState())
        val engine = HermesConversation("https://example.invalid", connection(), ConversationTarget.Session("default", "stored"), wire, backgroundScope)
        val turn = HermesChatTurnCoordinator(engine, state)
        turn.activate(); runCurrent()
        wire.emit("message.start", 1)
        wire.emit("message.delta", 2, """{"text":"Hello "}""")
        wire.emit("message.delta", 2, """{"text":"Hello "}""")
        wire.onEvent?.invoke(BotJSON.parse("""{"session_id":"other","type":"message.delta","seq":3,"payload":{"text":"foreign"}}"""))
        wire.emit("message.delta", 3, """{"text":"world."}""")
        runCurrent()
        assertEquals("Hello world.", state.value.messages.last().content)
        wire.emit("message.complete", 4, """{"status":"complete","text":"Hello world."}""")
        runCurrent()
        assertNotNull(state.value.activeStreamId)
        wire.emit("session.info", 5, """{"running":false}""")
        runCurrent()
        assertNull(state.value.activeStreamId)
        assertEquals("Hello world.", state.value.messages.last().content)
        turn.suspendConnection()
    }

    @Test fun `reducer keeps reasoning tools plan and failure separate from assistant text`() = runTest {
        val wire = HermesChatFixtureWire()
        val state = MutableStateFlow(ChatUiState())
        val engine = HermesConversation("https://example.invalid", connection(), ConversationTarget.Session("default", "stored"), wire, backgroundScope)
        val turn = HermesChatTurnCoordinator(engine, state)
        turn.activate(); runCurrent()
        wire.emit("message.start", 1)
        wire.emit("thinking.delta", 2, """{"text":"not reasoning"}""")
        wire.emit("reasoning.delta", 3, """{"text":"Inspecting the source."}""")
        wire.emit("tool.start", 4, """{"tool_id":"read-1","name":"read_file","args":{"path":"main.kt"}}""")
        wire.emit("tool.complete", 5, """{"tool_id":"read-1","result":"file output","duration_s":0.2}""")
        wire.emit("todo.updated", 6, """{"revision":2,"todos":[{"id":"1","content":"Inspect source","status":"in_progress"}]}""")
        wire.emit("message.delta", 7, """{"text":"A partial response."}""")
        runCurrent()
        assertEquals("Inspecting the source.", state.value.liveReasoningText)
        assertEquals(1, state.value.liveToolCalls.size)
        assertEquals("read_file", state.value.liveToolCalls.single().name)
        assertTrue(state.value.liveToolCalls.single().isCompleted)
        assertEquals("file output", state.value.liveToolCalls.single().preview)
        assertEquals(2, turn.activity.plan?.revision)
        wire.emit("message.complete", 8, """{"status":"error","error":"Rate limited","error_surface":{"layer":"provider","code":"rate_limit","retryable":true},"warning":"Partial reply","partial":true}""")
        runCurrent()
        assertEquals("Rate limited", turn.activity.failure?.error)
        assertEquals("Partial reply", turn.activity.notice?.warning)
        assertEquals("A partial response.", state.value.messages.last().content)
        turn.suspendConnection()
    }

    @Test fun `expired captured write cannot run after reattach`() = runTest {
        val wire = HermesChatFixtureWire()
        val engine = HermesConversation("https://example.invalid", connection(), ConversationTarget.Session("default", "stored"), wire, backgroundScope)
        val turn = HermesChatTurnCoordinator(engine, MutableStateFlow(ChatUiState()))
        turn.activate(); runCurrent()
        val old = engine.generation
        turn.suspendConnection(); turn.activate(); runCurrent()
        try {
            engine.write(HermesCall.SessionInterrupt("runtime-1"), old, "runtime-1")
            fail("stale write accepted")
        } catch (expected: BotFailure) { assertEquals(BotFailure.Stale, expected) }
        assertFalse(wire.calls.any { it is HermesCall.SessionInterrupt })
        turn.suspendConnection()
    }

    @Test fun `send modes always use native Hermes operations and unknown acknowledgments stay unknown`() {
        assertEquals(BotPromptMode.SEND, HermesChatViewModel.mappedMode(SendMode.STOP_AND_SEND, false, true))
        assertEquals(BotPromptMode.STEER, HermesChatViewModel.mappedMode(SendMode.DEFAULT, true, false))
        assertEquals(BotPromptMode.QUEUE, HermesChatViewModel.mappedMode(SendMode.DEFAULT, true, true))
        assertEquals(BotPromptMode.REDIRECT, HermesChatViewModel.mappedMode(SendMode.STOP_AND_SEND, true, true))
        assertEquals("session.redirect", BotPromptMode.REDIRECT.call("runtime", "text").method)
        assertEquals(true, BotPromptMode.QUEUE.call("runtime", "text").params()["queued"]?.flag)
        assertEquals(BotPromptOutcome.UNKNOWN, BotPromptMode.SEND.outcome(BotJSON.parse("""{"ok":true}""")))
        assertEquals(BotPromptOutcome.VOICE_STOPPED, BotPromptMode.SEND.outcome(BotJSON.parse("""{"voice_stopped":true}""")))
        assertEquals(BotPromptOutcome.REJECTED, BotPromptMode.STEER.outcome(BotJSON.parse("""{"status":"rejected"}""")))
    }
}
