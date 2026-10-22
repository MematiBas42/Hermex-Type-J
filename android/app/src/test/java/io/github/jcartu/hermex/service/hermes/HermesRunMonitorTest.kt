package io.github.jcartu.hermex.service.hermes

import io.github.jcartu.hermex.model.hermes.BotArtifactContext
import io.github.jcartu.hermex.model.hermes.BotConnection
import io.github.jcartu.hermex.model.hermes.BotFailure
import io.github.jcartu.hermex.model.hermes.BotJSON
import io.github.jcartu.hermex.model.hermes.HermesSessionPage
import io.github.jcartu.hermex.net.hermes.HermesCall
import io.github.jcartu.hermex.service.RunNotificationOutcome
import io.github.jcartu.hermex.ui.feature.chat.hermes.BotTransport
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HermesRunMonitorTest {
    @Test
    fun `only the resumed runtime can change run or prompt state`() = runTest {
        val fixture = fixture()
        val job = start(fixture)

        fixture.wire.emitRequest(approval("foreign-envelope", "foreign-approval", "other-runtime"))
        fixture.wire.emitFor("other-runtime", "message.complete", 1, """{"status":"error"}""")
        fixture.wire.emitFor("other-runtime", "session.info", 2, """{"running":false}""")
        runCurrent()

        assertTrue(fixture.monitor.updates.value.active)
        assertNull(fixture.monitor.updates.value.outcome)
        assertNull(fixture.monitor.updates.value.approval)

        fixture.wire.emitRequest(approval("own-envelope", "own-approval", fixture.wire.runtime))
        runCurrent()
        assertEquals(HermesRunPrompt("own-approval", "Allow the build"), fixture.monitor.updates.value.approval)
        assertTrue(fixture.wire.calls.filterIsInstance<HermesCall.SessionResume>().all {
            it.profile == fixture.target.profile && it.sessionID == fixture.target.storedKey
        })

        fixture.monitor.close()
        runCurrent()
        assertTrue(job.isCompleted)
        assertFalse(fixture.wire.calls.any { it is HermesCall.PromptSubmit })
    }

    @Test
    fun `completed failed and cancelled outcomes wait for host idle`() = runTest {
        val cases = listOf(
            "complete" to RunNotificationOutcome.COMPLETED,
            "error" to RunNotificationOutcome.FAILED,
            "interrupted" to RunNotificationOutcome.CANCELLED,
        )
        for ((status, expected) in cases) {
            val fixture = fixture()
            val job = start(fixture)
            fixture.wire.emit("message.start")
            fixture.wire.emit("message.complete", """{"status":"$status"}""")
            runCurrent()
            assertTrue("$status must not finish before session.info", fixture.monitor.updates.value.active)
            assertNull(fixture.monitor.updates.value.outcome)

            fixture.wire.emit("session.info", """{"running":false}""")
            runCurrent()
            assertEquals(expected, fixture.monitor.updates.value.outcome)
            assertFalse(fixture.monitor.updates.value.active)
            assertTrue(job.isCompleted)
        }
    }

    @Test
    fun `terminal run retains prompts through missing metadata until explicit empty snapshot`() = runTest {
        val fixture = fixture(
            requests = listOf(
                approval("approval-envelope", "approval-1", RUNTIME),
                question("question-envelope", "Which target?", RUNTIME),
            ),
        )
        val job = start(fixture)
        assertEquals("approval-1", fixture.monitor.updates.value.approval?.id)
        assertEquals("question-envelope", fixture.monitor.updates.value.question?.id)
        assertFalse(fixture.monitor.updates.value.approvalsConfirmedClear)
        assertFalse(fixture.monitor.updates.value.questionsConfirmedClear)

        fixture.wire.emit("message.complete", """{"status":"complete"}""")
        fixture.wire.emit("session.info", """{"running":false}""")
        runCurrent()
        assertEquals(RunNotificationOutcome.COMPLETED, fixture.monitor.updates.value.outcome)
        assertFalse(job.isCompleted)

        fixture.wire.openRequests = null
        fixture.wire.disconnect(BotFailure.Transport)
        runCurrent()
        assertEquals("approval-1", fixture.monitor.updates.value.approval?.id)
        assertEquals("question-envelope", fixture.monitor.updates.value.question?.id)
        assertFalse(job.isCompleted)

        fixture.wire.openRequests = emptyList()
        fixture.wire.disconnect(BotFailure.Transport)
        runCurrent()
        assertNull(fixture.monitor.updates.value.approval)
        assertNull(fixture.monitor.updates.value.question)
        assertTrue(fixture.monitor.updates.value.approvalsConfirmedClear)
        assertTrue(fixture.monitor.updates.value.questionsConfirmedClear)
        assertTrue(job.isCompleted)
    }

    @Test
    fun `historical idle replay cannot announce completion before the current snapshot`() = runTest {
        val fixture = fixture(requests = listOf(approval("approval-envelope", "approval-1", RUNTIME)))
        fixture.wire.replayFrames = listOf(
            BotJSON.parse("""{"type":"session.info","session_id":"$RUNTIME","seq":1,"payload":{"running":false}}"""),
            BotJSON.parse("""{"type":"message.start","session_id":"$RUNTIME","seq":2,"payload":{}}"""),
        )
        val published = mutableListOf<HermesRunMonitorUpdate>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            fixture.monitor.updates.collect { published += it }
        }
        val job = start(fixture)
        assertTrue(fixture.monitor.updates.value.active)
        assertEquals("approval-1", fixture.monitor.updates.value.approval?.id)
        assertFalse(published.any { it.outcome != null })
        fixture.monitor.close()
        runCurrent()
        assertTrue(job.isCompleted)
    }

    @Test
    fun `accepted request cancellation clears only its own prompt family`() = runTest {
        val fixture = fixture(
            requests = listOf(
                approval("approval-envelope", "approval-1", RUNTIME),
                question("question-envelope", "Which target?", RUNTIME),
            ),
        )
        val job = start(fixture)
        fixture.wire.emit("message.complete", """{"status":"complete"}""")
        fixture.wire.emit("session.info", """{"running":false}""")
        fixture.wire.emit(
            "request.cancel",
            """{"id":"approval-envelope","method":"approval","reason":"answered"}""",
        )
        runCurrent()

        assertNull(fixture.monitor.updates.value.approval)
        assertTrue(fixture.monitor.updates.value.approvalsConfirmedClear)
        assertEquals("question-envelope", fixture.monitor.updates.value.question?.id)
        assertFalse(job.isCompleted)

        fixture.wire.emit(
            "request.cancel",
            """{"id":"question-envelope","method":"clarify","reason":"answered"}""",
        )
        runCurrent()
        assertTrue(fixture.monitor.updates.value.questionsConfirmedClear)
        assertTrue(job.isCompleted)
    }

    @Test
    fun `connection replacement ends only this observer without fabricating cancellation`() = runTest {
        val fixture = fixture()
        val job = start(fixture)
        fixture.wire.disconnect(BotFailure.Stale)
        runCurrent()

        assertTrue(job.isCompleted)
        assertNull(fixture.monitor.updates.value.outcome)
        assertEquals("Connection changed", fixture.monitor.updates.value.status)
        assertFalse(fixture.wire.calls.any { it is HermesCall.SessionInterrupt || it is HermesCall.SessionClose })
        assertTrue(fixture.wire.closes > 0)
    }

    @Test
    fun `close leaves host work running while interrupt is native runtime fenced and one shot`() = runTest {
        val closed = fixture()
        val closedJob = start(closed)
        closed.monitor.close()
        runCurrent()
        assertTrue(closedJob.isCompleted)
        assertNull(closed.monitor.updates.value.outcome)
        assertFalse(closed.wire.calls.any { it is HermesCall.SessionInterrupt || it is HermesCall.SessionClose })

        val interrupted = fixture()
        val interruptedJob = start(interrupted)
        interrupted.monitor.interrupt()
        interrupted.monitor.interrupt()
        val calls = interrupted.wire.calls.filterIsInstance<HermesCall.SessionInterrupt>()
        assertEquals(1, calls.size)
        assertEquals(interrupted.wire.runtime, calls.single().sessionID)

        interrupted.wire.emit("message.complete", """{"status":"interrupted"}""")
        interrupted.wire.emit("session.info", """{"running":false}""")
        runCurrent()
        assertEquals(RunNotificationOutcome.CANCELLED, interrupted.monitor.updates.value.outcome)
        assertTrue(interruptedJob.isCompleted)
    }

    @Test
    fun `transport reconnect only resumes and replays without prompt submission`() = runTest {
        val fixture = fixture()
        val job = start(fixture)
        assertEquals(1, fixture.wire.connects)

        fixture.wire.disconnect(BotFailure.Transport)
        runCurrent()

        assertEquals(2, fixture.wire.connects)
        assertTrue(fixture.monitor.updates.value.active)
        assertNull(fixture.monitor.updates.value.outcome)
        assertFalse(fixture.wire.calls.any { it is HermesCall.PromptSubmit })
        assertEquals(4, fixture.wire.calls.count { it is HermesCall.SessionResume })
        assertEquals(2, fixture.wire.calls.count { it is HermesCall.SessionEventsSince })

        fixture.monitor.close()
        runCurrent()
        assertTrue(job.isCompleted)
    }

    private data class Fixture(
        val target: HermesRunTarget,
        val wire: ScriptedWire,
        val monitor: HermesRunMonitor,
    )

    private fun TestScope.fixture(requests: List<BotJSON>? = emptyList()): Fixture {
        val connection = BotConnection(
            id = CONNECTION,
            name = "Fixture",
            address = "https://example.invalid/".toHttpUrl(),
            username = "",
            password = "",
        )
        val target = HermesRunTarget(
            serverId = "https://example.invalid",
            connectionId = connection.id,
            profile = "work",
            storedKey = "stored-key",
            title = "Run title",
        )
        val wire = ScriptedWire(requests)
        val monitor = HermesRunMonitor(
            target = target,
            connection = connection,
            wire = wire,
            dispatcher = StandardTestDispatcher(testScheduler),
            reconnectDelay = {},
        )
        return Fixture(target, wire, monitor)
    }

    private fun TestScope.start(fixture: Fixture): Job {
        val job = backgroundScope.launch { fixture.monitor.run() }
        runCurrent()
        assertTrue(fixture.monitor.updates.value.active)
        return job
    }

    private class ScriptedWire(requests: List<BotJSON>?) : BotTransport {
        override val replayEpoch: String get() = "epoch"
        override val serverVersion: String get() = "0.22.2"
        override val unavailableMethods: Set<String> = emptySet()
        override var onEvent: ((BotJSON) -> Unit)? = null
        override var onDisconnect: ((Throwable) -> Unit)? = null

        val calls = mutableListOf<HermesCall>()
        var runtime = RUNTIME
        var running = true
        var openRequests: List<BotJSON>? = requests
        var replayFrames: List<BotJSON> = emptyList()
        var connects = 0
        var closes = 0
        private var sequence = 0

        override suspend fun connect() {
            connects += 1
        }

        override suspend fun close() {
            closes += 1
        }

        override suspend fun call(call: HermesCall, validateDispatch: (() -> Unit)?): BotJSON {
            validateDispatch?.invoke()
            calls += call
            return when (call) {
                is HermesCall.SessionResume -> snapshot()
                is HermesCall.SessionEventsSince -> replay()
                is HermesCall.SessionInterrupt -> BotJSON.parse("""{"status":"interrupted"}""")
                else -> throw AssertionError("Unexpected monitor call ${call.method}")
            }
        }

        override suspend fun keepPlatformNote(profile: String) = Unit
        override suspend fun currentProfile(): String = "work"
        override suspend fun sessionMessages(key: String, profile: String, offset: Int?): List<BotJSON>? = emptyList()
        override suspend fun sessionRow(key: String, profile: String): BotJSON? = null
        override suspend fun sessionPage(profile: String, offset: Int, archived: Boolean) = HermesSessionPage(emptyList(), 0, 0)
        override suspend fun artifactData(path: String, context: BotArtifactContext, limit: Int?): ByteArray = error("unused")
        override suspend fun uploadImage(data: ByteArray, filename: String, context: BotArtifactContext): String = error("unused")
        override suspend fun speech(text: String, profile: String): ByteArray = error("unused")

        fun emit(type: String, payload: String = "{}") {
            if (type == "session.info") BotJSON.parse(payload)["running"].flag?.let { running = it }
            sequence += 1
            emitFor(runtime, type, sequence, payload)
        }

        fun emitFor(session: String, type: String, seq: Int, payload: String) {
            onEvent?.invoke(BotJSON.parse("""{"type":"$type","session_id":"$session","seq":$seq,"payload":$payload}"""))
        }

        fun emitRequest(request: BotJSON) {
            onEvent?.invoke(request)
        }

        fun disconnect(error: Throwable) {
            onDisconnect?.invoke(error)
        }

        private fun snapshot(): BotJSON {
            val fields = linkedMapOf(
                "session_id" to BotJSON.string(runtime),
                "session_key" to BotJSON.string("stored-key"),
                "running" to BotJSON.bool(running),
                "info" to BotJSON.objectOf("running" to BotJSON.bool(running)),
                "inflight" to BotJSON.NULL,
            )
            openRequests?.let { fields["open_requests"] = BotJSON.array(it) }
            return BotJSON.objectOf(fields)
        }

        private fun replay(): BotJSON {
            val fields = linkedMapOf(
                "latest_seq" to BotJSON.number(maxOf(sequence, replayFrames.maxOfOrNull { it["seq"].integer ?: 0 } ?: 0)),
                "epoch" to BotJSON.string("epoch"),
                "truncated" to BotJSON.bool(false),
                "events" to BotJSON.array(replayFrames),
            )
            openRequests?.let { fields["open_requests"] = BotJSON.array(it) }
            return BotJSON.objectOf(fields)
        }
    }

    companion object {
        private const val CONNECTION = "connection-1"
        private const val RUNTIME = "runtime-1"

        private fun approval(envelope: String, request: String, runtime: String): BotJSON = BotJSON.parse(
            """{"id":"$envelope","method":"approval","params":{"session_id":"$runtime","request_id":"$request","description":"Allow the build","choices":["once","deny"]}}""",
        )

        private fun question(envelope: String, text: String, runtime: String): BotJSON = BotJSON.parse(
            """{"id":"$envelope","method":"clarify","params":{"session_id":"$runtime","question":"$text"}}""",
        )
    }
}
