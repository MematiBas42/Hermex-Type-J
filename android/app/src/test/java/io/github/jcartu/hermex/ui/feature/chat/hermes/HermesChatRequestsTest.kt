package io.github.jcartu.hermex.ui.feature.chat.hermes

import io.github.jcartu.hermex.model.hermes.BotArtifactContext
import io.github.jcartu.hermex.model.hermes.BotConnection
import io.github.jcartu.hermex.model.hermes.BotConnectionOperation
import io.github.jcartu.hermex.model.hermes.BotJSON
import io.github.jcartu.hermex.model.hermes.BotPendingRequest
import io.github.jcartu.hermex.model.hermes.HermesSessionPage
import io.github.jcartu.hermex.net.hermes.BotApprovalChoice
import io.github.jcartu.hermex.net.hermes.HermesCall
import io.github.jcartu.hermex.ui.feature.chat.ApprovalScopeFormatter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Controller parity tests ported from `HermesMobileTests/HermesChatRequestTests.swift`. */
@OptIn(ExperimentalCoroutinesApi::class)
class HermesChatRequestsTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var scope: CoroutineScope

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        scope = CoroutineScope(SupervisorJob() + dispatcher)
    }

    @After
    fun tearDown() {
        scope.cancel()
        Dispatchers.resetMain()
    }

    @Test
    fun `missing malformed and negative approval verdicts keep the card answerable without reconnecting`() = runTest(dispatcher) {
        val fixture = fixture()
        val replies = listOf(
            obj(),
            obj("resolved" to text("1")),
            obj("resolved" to BotJSON.number(1.5)),
            obj("resolved" to BotJSON.number(-1)),
            obj("resolved" to BotJSON.NULL),
        )

        replies.forEachIndexed { index, reply ->
            val envelopeID = "srq-$index"
            val requestID = "approval-$index"
            fixture.wire.enqueue("approval.respond", reply)
            fixture.requests.receive(approval(envelopeID, requestID))

            val accepted = fixture.requests.respond(
                fixture.requests.prepareAnswer()!!,
                BotApprovalChoice.ONCE,
            )

            assertFalse("$reply", accepted)
            assertEquals(requestID, fixture.requests.onScreen?.requestID)
            assertEquals(
                "Answer outcome unknown. Check Hermes Desktop before answering again.",
                fixture.requests.errorMessage,
            )
            assertTrue(fixture.requests.mayAnswer)
            assertEquals(HermesConversation.ConnectionState.CONNECTED, fixture.engine.connectionState)
            fixture.requests.cancel(
                obj(
                    "id" to text(envelopeID),
                    "method" to text("approval"),
                    "reason" to text("resolved"),
                ),
            )
            assertNull(fixture.requests.errorMessage)
        }

        assertEquals(replies.size, fixture.wire.calls.count { it is HermesCall.ApprovalRespond })
        assertEquals(1, fixture.wire.connectCount)
    }

    @Test
    fun `snapshot and replay open requests cannot overwrite newer request frames`() = runTest(dispatcher) {
        val fixture = fixture()
        fixture.requests.willReadSnapshot()
        fixture.requests.receive(request("sudo", "live-sudo"))
        fixture.requests.didReadSnapshot(
            obj("open_requests" to array(request("secret", "stale-secret"))),
        )
        assertEquals(listOf("live-sudo"), fixture.requests.open.map { it.id })

        fixture.requests.willReplay()
        fixture.requests.receive(
            request(
                "clarify",
                "live-question",
                mapOf("question" to text("Which branch?")),
            ),
        )
        fixture.requests.didReplay(
            obj("open_requests" to array(request("secret", "stale-again"))),
            frames = emptyList(),
        )
        assertEquals(listOf("live-sudo", "live-question"), fixture.requests.open.map { it.id })
        assertEquals("live-question", fixture.requests.onScreen?.requestID)
    }

    @Test
    fun `batch skip locks every outstanding qid and never sends a bare answer`() = runTest(dispatcher) {
        val fixture = fixture()
        fixture.wire.enqueue(
            "clarify.lock",
            obj("status" to text("ok"), "remaining" to array(text("q3"))),
        )
        fixture.wire.enqueue(
            "clarify.lock",
            obj("status" to text("ok"), "remaining" to array()),
        )
        fixture.requests.receive(
            request(
                "clarify",
                "batch-1",
                mapOf(
                    "questions" to array(
                        question("q1", "Already?"),
                        question("q2", "Deploy?"),
                        question("q3", "Notify?"),
                    ),
                    "answers" to obj("q1" to text("yes")),
                ),
            ),
        )

        assertTrue(fixture.requests.skipQuestion(fixture.requests.prepareAnswer()!!))

        val locks = fixture.wire.calls.filterIsInstance<HermesCall.ClarifyLock>()
        assertEquals(listOf("q2", "q3"), locks.map { it.questionID })
        assertEquals(listOf("", ""), locks.map { it.answer })
        assertTrue(fixture.wire.calls.none { it is HermesCall.RequestAnswer })
        assertNull(fixture.requests.onScreen)
    }

    @Test
    fun `vault requests wait without cards while desktop tasks are shown but never answered`() = runTest(dispatcher) {
        val fixture = fixture()
        fixture.requests.receive(request("vault.unlock_prompt", "vault-1"))
        assertNull(fixture.requests.onScreen)
        assertNull(fixture.requests.prepareAnswer())
        assertTrue(fixture.requests.isWaiting)
        assertTrue(fixture.requests.stopWithdrawsAnswers)

        fixture.requests.reset()
        fixture.requests.receive(request("terminal.read", "desktop-1"))
        assertTrue(fixture.requests.onScreen is BotPendingRequest.DesktopTask)
        assertFalse(fixture.requests.mayAnswer)
        assertNull(fixture.requests.prepareAnswer())
        assertTrue(fixture.requests.isWaiting)
        assertFalse(fixture.requests.stopWithdrawsAnswers)
        assertTrue(fixture.wire.calls.none { it is HermesCall.RequestAnswer })
    }

    @Test
    fun `connection operation accepts only newer sequence and never revives after settlement`() = runTest(dispatcher) {
        val fixture = fixture()
        fixture.requests.receiveConnection(operation(seq = 2, state = "pending"), opens = true)
        assertEquals(2, fixture.requests.connection?.seq)

        fixture.requests.receiveConnection(operation(seq = 1, state = "failed"), opens = false)
        assertEquals(BotConnectionOperation.Target.State.PENDING, fixture.requests.connection?.targets?.single()?.state)

        fixture.requests.receiveConnection(operation(seq = 2, state = "failed", settled = true), opens = false)
        assertEquals(2, fixture.requests.connection?.seq)
        assertTrue(fixture.requests.isWaiting)

        fixture.requests.receiveConnection(operation(seq = 3, state = "connected"), opens = false)
        assertEquals(BotConnectionOperation.Target.State.CONNECTED, fixture.requests.connection?.targets?.single()?.state)
        fixture.requests.receiveConnection(operation(seq = 4, state = "connected", settled = true), opens = false)
        assertNull(fixture.requests.connection)
        assertFalse(fixture.requests.isWaiting)

        fixture.requests.receiveConnection(operation(seq = 5, state = "pending"), opens = true)
        assertNull(fixture.requests.connection)
        assertFalse(fixture.requests.isWaiting)
    }

    @Test
    fun `connection answer is fenced against an operation replaced before dispatch`() = runTest(dispatcher) {
        val fixture = fixture()
        fixture.requests.receiveConnection(operation(id = "op-1", seq = 1), opens = true)
        val action = fixture.requests.prepareAnswer()!!
        fixture.wire.beforeDispatch = { call ->
            if (call is HermesCall.ConnectionRespond) {
                fixture.requests.receiveConnection(operation(id = "op-2", seq = 1), opens = true)
            }
        }
        fixture.wire.enqueue(
            "connection.respond",
            obj("status" to text("ok"), "settled" to BotJSON.bool(false)),
        )

        assertFalse(
            fixture.requests.respondToConnection(
                action,
                io.github.jcartu.hermex.net.hermes.BotConnectionAnswer.Skip("gmail"),
            ),
        )
        assertEquals("op-2", fixture.requests.connection?.opID)
        assertTrue(fixture.wire.calls.none { it is HermesCall.ConnectionRespond })
        assertTrue(fixture.requests.mayAnswer)
    }

    @Test
    fun `controller publishes open answering and error mutations through onChange`() = runTest(dispatcher) {
        val fixture = fixture()
        var changes = 0
        var openChanges = 0
        fixture.requests.onChange = { changes += 1 }
        fixture.requests.onOpenChange = { openChanges += 1 }
        fixture.wire.enqueue("request.answer", obj("status" to text("ok")))

        fixture.requests.receive(request("sudo", "sudo-1"))
        assertTrue(fixture.requests.answerCredential(fixture.requests.prepareAnswer()!!, "one-use-value"))

        assertTrue(changes >= 3)
        assertTrue(openChanges >= 2)
        assertNull(fixture.requests.onScreen)
    }

    @Test
    fun `Hermes approval scope uses chat Profile and Tirith downgrade wording`() {
        assertNull(
            ApprovalScopeFormatter.hermesLine(
                keys = listOf("recursive delete"),
                description = "recursive delete",
                command = "rm -rf build",
                toolName = null,
                offersSession = false,
                offersAlways = false,
            ),
        )
        assertEquals(
            "Allow session covers “recursive delete” and this security finding in this chat; " +
                "Always allow covers “recursive delete” for this Profile from now on. " +
                "The security finding stays allowed for this chat only.",
            ApprovalScopeFormatter.hermesLine(
                keys = listOf("tirith:shortened_url", "recursive delete"),
                description = "Security scan; recursive delete",
                command = "curl https://bit.ly/x && rm -rf build",
                toolName = null,
                offersSession = true,
                offersAlways = true,
            ),
        )
        assertEquals(
            "Allow session covers this security finding in this chat.",
            ApprovalScopeFormatter.hermesLine(
                keys = listOf("tirith:shortened_url"),
                description = "Security scan",
                command = "curl https://bit.ly/x",
                toolName = null,
                offersSession = true,
                offersAlways = false,
            ),
        )
    }

    private suspend fun fixture(): Fixture {
        val wire = FakeBotTransport()
        val engine = HermesConversation(
            server = "https://hermes.example",
            connection = BotConnection(
                name = "Fixture",
                address = "https://hermes.example".toHttpUrl(),
                username = "",
                password = "",
            ),
            target = ConversationTarget.Session(profile = "default", key = "stored"),
            wire = wire,
            scope = scope,
        )
        engine.activate()
        assertEquals(HermesConversation.ConnectionState.CONNECTED, engine.connectionState)
        return Fixture(wire, engine, HermesChatRequests(engine))
    }

    private data class Fixture(
        val wire: FakeBotTransport,
        val engine: HermesConversation,
        val requests: HermesChatRequests,
    )

    private class FakeBotTransport : BotTransport {
        override val replayEpoch: String = "epoch-1"
        override val serverVersion: String = "0.21.5"
        override val unavailableMethods: Set<String> = emptySet()
        override var onEvent: ((BotJSON) -> Unit)? = null
        override var onDisconnect: ((Throwable) -> Unit)? = null
        val calls = mutableListOf<HermesCall>()
        var connectCount = 0
        var beforeDispatch: ((HermesCall) -> Unit)? = null
        private val replies = mutableMapOf<String, ArrayDeque<BotJSON>>()

        fun enqueue(method: String, reply: BotJSON) {
            replies.getOrPut(method) { ArrayDeque() }.addLast(reply)
        }

        override suspend fun connect() {
            connectCount += 1
        }

        override suspend fun close() = Unit

        override suspend fun call(call: HermesCall, validateDispatch: (() -> Unit)?): BotJSON {
            beforeDispatch?.invoke(call)
            validateDispatch?.invoke()
            calls += call
            val queued = replies[call.method]
            if (queued != null && queued.isNotEmpty()) return queued.removeFirst()
            return when (call) {
                is HermesCall.SessionResume -> obj(
                    "session_id" to text("runtime"),
                    "session_key" to text("stored"),
                    "running" to BotJSON.bool(true),
                    "info" to obj("profile_name" to text("default")),
                )
                is HermesCall.SessionEventsSince -> obj(
                    "latest_seq" to BotJSON.number(0),
                    "epoch" to text(replayEpoch),
                    "truncated" to BotJSON.bool(false),
                    "events" to array(),
                    "open_requests" to array(),
                )
                else -> throw AssertionError("Unexpected ${call.method}")
            }
        }

        override suspend fun keepPlatformNote(profile: String) = Unit
        override suspend fun currentProfile(): String = throw AssertionError("Unexpected currentProfile")
        override suspend fun sessionMessages(key: String, profile: String, offset: Int?): List<BotJSON>? =
            throw AssertionError("Unexpected sessionMessages")
        override suspend fun sessionRow(key: String, profile: String): BotJSON? =
            throw AssertionError("Unexpected sessionRow")
        override suspend fun sessionPage(profile: String, offset: Int, archived: Boolean): HermesSessionPage =
            throw AssertionError("Unexpected sessionPage")
        override suspend fun artifactData(path: String, context: BotArtifactContext, limit: Int?): ByteArray =
            throw AssertionError("Unexpected artifactData")
        override suspend fun uploadImage(data: ByteArray, filename: String, context: BotArtifactContext): String =
            throw AssertionError("Unexpected uploadImage")
        override suspend fun speech(text: String, profile: String): ByteArray =
            throw AssertionError("Unexpected speech")
    }

    private fun approval(envelopeID: String, requestID: String): BotJSON = request(
        "approval",
        envelopeID,
        mapOf(
            "request_id" to text(requestID),
            "command" to text("rm -rf build"),
            "description" to text("recursive delete"),
            "choices" to array(text("once"), text("session"), text("always"), text("deny")),
        ),
    )

    private fun request(method: String, id: String, extra: Map<String, BotJSON> = emptyMap()): BotJSON = obj(
        "jsonrpc" to text("2.0"),
        "id" to text(id),
        "method" to text(method),
        "params" to obj(extra + ("session_id" to text("runtime"))),
    )

    private fun question(id: String, prompt: String): BotJSON = obj(
        "qid" to text(id),
        "question" to text(prompt),
        "choices" to array(),
        "multi_select" to BotJSON.bool(false),
    )

    private fun operation(
        id: String = "op-1",
        seq: Int,
        state: String = "pending",
        settled: Boolean = false,
    ): BotJSON = obj(
        "op_id" to text(id),
        "seq" to BotJSON.number(seq),
        "deadline_at" to BotJSON.number(1_900_000_000.0),
        "settled" to BotJSON.bool(settled),
        "targets" to array(
            obj(
                "name" to text("gmail"),
                "kind" to text("connector"),
                "action" to text("connect"),
                "state" to text(state),
                "connect_url" to text("https://accounts.example/oauth"),
            ),
        ),
    )

    companion object {
        private fun obj(vararg fields: Pair<String, BotJSON>): BotJSON = BotJSON.objectOf(*fields)
        private fun obj(fields: Map<String, BotJSON>): BotJSON = BotJSON.objectOf(fields)
        private fun array(vararg values: BotJSON): BotJSON = BotJSON.array(values.toList())
        private fun text(value: String): BotJSON = BotJSON.string(value)
    }
}
