package io.github.jcartu.hermex.ui.feature.chat.hermes

import io.github.jcartu.hermex.model.hermes.BotArtifactContext
import io.github.jcartu.hermex.model.hermes.BotConnection
import io.github.jcartu.hermex.model.hermes.BotFailure
import io.github.jcartu.hermex.model.hermes.BotJSON
import io.github.jcartu.hermex.model.hermes.HermesBtw
import io.github.jcartu.hermex.model.hermes.HermesSessionPage
import io.github.jcartu.hermex.net.hermes.HermesCall
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Focused ports of `HermesChatTurnTests.swift`, `HermesChatSideTaskTests.swift`, and `HermesDelegatedWorkTests.swift`. */
class HermesActivitySideDelegationTest {
    @Test
    fun `older plan revision cannot replace or resurrect a newer clear`() = runTest {
        val activity = HermesChatActivity(FakeTransport(), this)
        activity.turnDidStart(showsPrompt = true)

        activity.receivePlan(plan(revision = 2, statuses = listOf("in_progress", "pending")))
        activity.receivePlan(plan(revision = 1, statuses = listOf("completed"), prefix = "Stale"))

        assertEquals(2, activity.pinnedPlan?.revision)
        assertEquals(listOf("Step 1", "Step 2"), activity.pinnedPlan?.items?.map { it.content })

        activity.receivePlan(plan(revision = 3, statuses = emptyList()))
        assertNull(activity.pinnedPlan)
        assertNull(activity.settledPlan)

        activity.receivePlan(plan(revision = 2, statuses = listOf("in_progress"), prefix = "Resurrected"))
        assertNull(activity.plan)
    }

    @Test
    fun `retry target waits for exact durable prompt row and refused cut stays hidden`() = runTest {
        val activity = HermesChatActivity(FakeTransport(), this)
        activity.turnDidStart(showsPrompt = true)
        activity.turnDidComplete(
            BotJSON.objectOf(
                "status" to BotJSON.string("error"),
                "error" to BotJSON.string("rate limited"),
                "recoverable" to BotJSON.bool(true),
                "persisted_turn" to BotJSON.objectOf("user_row_id" to BotJSON.number(7)),
            ),
        )
        assertNull(activity.retryTarget)

        activity.readSnapshot(
            snapshot = BotJSON.objectOf(
                "inflight" to BotJSON.objectOf(
                    "error" to BotJSON.string("rate limited"),
                    "recoverable" to BotJSON.bool(true),
                    "user" to BotJSON.string("Summarize the logs"),
                    "started_at" to BotJSON.number(42.0),
                ),
            ),
            promptRowID = 7,
            followsTurn = true,
            followedPromptRowID = 7,
        )
        assertEquals(
            HermesChatActivity.RetryTarget(7, "Summarize the logs", showsPrompt = true),
            activity.retryTarget,
        )

        activity.refuseRetry(7)
        assertNull(activity.retryTarget)
    }

    @Test
    fun `completion that beats side start reply is claimed only by matching task id`() = runTest {
        val wire = FakeTransport()
        val engine = conversation(wire, this)
        lateinit var sideTasks: HermesChatSideTasks
        wire.handler = { call ->
            when (call) {
                is HermesCall.SessionResume -> attachedSnapshot()
                is HermesCall.SessionEventsSince -> emptyReplay()
                is HermesCall.PromptBtw -> {
                    sideTasks.receive(
                        frame(
                            type = "btw.complete",
                            taskID = "btw-1",
                            text = "The answer arrived first.",
                        ),
                    )
                    BotJSON.objectOf("task_id" to BotJSON.string("btw-1"))
                }
                is HermesCall.PromptBackground -> {
                    sideTasks.receive(
                        frame(
                            type = "background.complete",
                            taskID = "bg-1",
                            text = "The result arrived first.",
                        ),
                    )
                    BotJSON.objectOf("task_id" to BotJSON.string("bg-1"))
                }
                else -> throw BotFailure.Unsupported
            }
        }
        engine.activate()
        sideTasks = HermesChatSideTasks(engine)

        sideTasks.ask("What are you doing?")
        assertEquals(HermesBtw.State.Answered("The answer arrived first."), sideTasks.btw?.state)

        sideTasks.startBackground("Summarize this chat")
        assertEquals("bg-1", sideTasks.backgroundTasks.single().id)
        assertEquals(
            io.github.jcartu.hermex.model.hermes.HermesBackgroundTask.State.Finished("The result arrived first."),
            sideTasks.backgroundTasks.single().state,
        )

        sideTasks.receive(frame("btw.complete", "someone-else", "wrong"))
        assertEquals(HermesBtw.State.Answered("The answer arrived first."), sideTasks.btw?.state)
    }

    @Test
    fun `delegated roster is hierarchy ordered and capped without losing active count`() = runTest {
        val wire = FakeTransport()
        val rows = buildList {
            add(worker(id = "child", parent = "parent", depth = 1, startedAt = 2.0))
            add(worker(id = "parent", startedAt = 1.0))
            repeat(HermesDelegatedWork.MAXIMUM_WORKERS) { add(worker(id = "extra-$it")) }
        }
        wire.handler = { call ->
            if (call is HermesCall.SubagentList) {
                BotJSON.objectOf("subagents" to BotJSON.array(rows))
            } else {
                throw BotFailure.Unsupported
            }
        }
        val work = HermesDelegatedWork(wire, this)

        work.connect(context())

        assertEquals(HermesDelegatedWork.MAXIMUM_WORKERS, work.workers.size)
        assertEquals(2, work.omittedWorkerCount)
        assertEquals(rows.size, work.activeCount)
        assertEquals(listOf("parent", "child"), work.workers.take(2).map { it.subagentID })
    }

    @Test
    fun `tail is bounded to latest 16 KiB and marks client truncation`() = runTest {
        val wire = FakeTransport()
        val row = worker(id = "child", startedAt = 1.0)
        wire.handler = { call ->
            when (call) {
                is HermesCall.SubagentList -> BotJSON.objectOf("subagents" to BotJSON.array(listOf(row)))
                is HermesCall.SubagentTail -> BotJSON.objectOf(
                    "subagent_id" to BotJSON.string("child"),
                    "available" to BotJSON.bool(true),
                    "text" to BotJSON.string("é".repeat(12_000)),
                    "truncated" to BotJSON.bool(false),
                )
                else -> throw BotFailure.Unsupported
            }
        }
        val work = HermesDelegatedWork(wire, this)
        work.connect(context())

        work.loadTail(work.workers.single())

        val tail = requireNotNull(work.tail)
        assertTrue(tail.available)
        assertTrue(tail.truncated)
        assertTrue(tail.text.toByteArray().size <= HermesDelegatedWork.MAXIMUM_TAIL_BYTES + 2)
        assertEquals(listOf(HermesCall.SubagentList::class, HermesCall.SubagentTail::class), wire.calls.map { it::class })
    }

    @Test
    fun `interrupt re-list rejects completed or reused worker identity`() = runTest {
        listOf<BotJSON?>(null, worker(id = "same", startedAt = 2.0)).forEach { replacement ->
            val wire = FakeTransport()
            var lists = 0
            wire.handler = { call ->
                when (call) {
                    is HermesCall.SubagentList -> {
                        val rows = if (lists++ == 0) {
                            listOf(worker(id = "same", startedAt = 1.0))
                        } else {
                            replacement?.let { listOf(it) }.orEmpty()
                        }
                        BotJSON.objectOf("subagents" to BotJSON.array(rows))
                    }
                    is HermesCall.SubagentInterrupt -> error("A stale identity must never be interrupted")
                    else -> throw BotFailure.Unsupported
                }
            }
            val work = HermesDelegatedWork(wire, this)
            work.connect(context())
            val action = requireNotNull(work.prepareInterrupt(work.workers.single()))

            work.interrupt(action)

            assertFalse(wire.calls.any { it is HermesCall.SubagentInterrupt })
            assertEquals("This worker is no longer active.", work.errorMessage)
        }
    }

    private fun conversation(wire: FakeTransport, scope: kotlinx.coroutines.CoroutineScope) = HermesConversation(
        server = "http://localhost:9120",
        connection = BotConnection(
            id = "connection",
            name = "Test",
            address = "http://localhost:9120".toHttpUrl(),
            username = "",
            password = "",
        ),
        target = ConversationTarget.Session(profile = "default", key = "stored"),
        wire = wire,
        scope = scope,
    )

    private fun context() = HermesDelegatedWork.Context(
        connectionID = "connection",
        runtime = "runtime",
        generation = 1,
    )

    private fun attachedSnapshot() = BotJSON.objectOf(
        "session_id" to BotJSON.string("runtime"),
        "session_key" to BotJSON.string("stored"),
        "inflight" to BotJSON.NULL,
    )

    private fun emptyReplay() = BotJSON.objectOf(
        "latest_seq" to BotJSON.number(0),
        "epoch" to BotJSON.string("epoch"),
        "truncated" to BotJSON.bool(false),
        "events" to BotJSON.array(emptyList()),
    )

    private fun plan(revision: Int, statuses: List<String>, prefix: String = "Step"): BotJSON =
        BotJSON.objectOf(
            "revision" to BotJSON.number(revision),
            "todos" to BotJSON.array(statuses.mapIndexed { index, status ->
                BotJSON.objectOf(
                    "id" to BotJSON.string("item-$index"),
                    "content" to BotJSON.string("$prefix ${index + 1}"),
                    "status" to BotJSON.string(status),
                )
            }),
        )

    private fun frame(type: String, taskID: String, text: String) = BotJSON.objectOf(
        "type" to BotJSON.string(type),
        "payload" to BotJSON.objectOf(
            "task_id" to BotJSON.string(taskID),
            "text" to BotJSON.string(text),
        ),
    )

    private fun worker(
        id: String,
        parent: String? = null,
        depth: Int = 0,
        startedAt: Double? = null,
    ): BotJSON {
        val fields = linkedMapOf(
            "subagent_id" to BotJSON.string(id),
            "depth" to BotJSON.number(depth),
        )
        if (parent != null) fields["parent_id"] = BotJSON.string(parent)
        if (startedAt != null) fields["started_at"] = BotJSON.number(startedAt)
        return BotJSON.objectOf(fields)
    }
}

/** Test transport shaped after Swift `BotTransport` for the focused controller cases above. */
private class FakeTransport : BotTransport {
    override val replayEpoch: String = "epoch"
    override val serverVersion: String? = "test"
    override val unavailableMethods: Set<String> = emptySet()
    override var onEvent: ((BotJSON) -> Unit)? = null
    override var onDisconnect: ((Throwable) -> Unit)? = null
    var handler: suspend (HermesCall) -> BotJSON = { throw BotFailure.Unsupported }
    val calls = mutableListOf<HermesCall>()

    override suspend fun connect() = Unit
    override suspend fun close() = Unit
    override suspend fun call(call: HermesCall, validateDispatch: (() -> Unit)?): BotJSON {
        validateDispatch?.invoke()
        calls += call
        return handler(call)
    }
    override suspend fun keepPlatformNote(profile: String) = Unit
    override suspend fun currentProfile(): String = "default"
    override suspend fun sessionMessages(key: String, profile: String, offset: Int?): List<BotJSON>? =
        throw BotFailure.Unsupported
    override suspend fun sessionRow(key: String, profile: String): BotJSON? = throw BotFailure.Unsupported
    override suspend fun sessionPage(profile: String, offset: Int, archived: Boolean): HermesSessionPage =
        throw BotFailure.Unsupported
    override suspend fun artifactData(path: String, context: BotArtifactContext, limit: Int?): ByteArray =
        throw BotFailure.Unsupported
    override suspend fun uploadImage(data: ByteArray, filename: String, context: BotArtifactContext): String =
        throw BotFailure.Unsupported
    override suspend fun speech(text: String, profile: String): ByteArray = throw BotFailure.Unsupported
}
