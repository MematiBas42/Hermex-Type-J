package io.github.jcartu.hermex.model.hermes

import io.github.jcartu.hermex.net.hermes.BotApprovalChoice
import io.github.jcartu.hermex.net.hermes.BotConnectionAnswer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Deterministic parser coverage ported from `HermesMobileTests/BotPendingRequestTests.swift`. */
class BotPendingRequestTest {

    @Test
    fun `approval keeps only host choices and always retains deny`() {
        val approval = BotApprovalRequest.from(
            obj(
                "request_id" to text("req-1"),
                "command" to text("rm -rf build"),
                "description" to text("recursive delete"),
                "choices" to array(text("once"), text("teleport")),
            ),
        )

        assertEquals("req-1", approval?.requestID)
        assertEquals("rm -rf build", approval?.command)
        assertEquals("recursive delete", approval?.consequence)
        assertEquals(listOf(BotApprovalChoice.ONCE, BotApprovalChoice.DENY), approval?.choices)
    }

    @Test
    fun `present malformed or wholly unknown approval choices offer deny only`() {
        val malformed = listOf(
            BotJSON.NULL,
            text("once"),
            obj("once" to BotJSON.bool(true)),
            BotJSON.number(1),
            array(),
            array(text("allow_forever"), text("nope")),
        )
        malformed.forEach { choices ->
            val approval = BotApprovalRequest.from(
                obj(
                    "request_id" to text("req-2"),
                    "choices" to choices,
                    "allow_permanent" to BotJSON.bool(true),
                ),
            )
            assertEquals("$choices", listOf(BotApprovalChoice.DENY), approval?.choices)
        }
    }

    @Test
    fun `absent approval choices rebuild only legacy permissions`() {
        assertEquals(
            listOf(BotApprovalChoice.ONCE, BotApprovalChoice.SESSION, BotApprovalChoice.ALWAYS, BotApprovalChoice.DENY),
            BotApprovalRequest.from(obj("request_id" to text("a")))?.choices,
        )
        assertEquals(
            listOf(BotApprovalChoice.ONCE, BotApprovalChoice.SESSION, BotApprovalChoice.DENY),
            BotApprovalRequest.from(
                obj("request_id" to text("a"), "allow_permanent" to BotJSON.bool(false)),
            )?.choices,
        )
        assertEquals(
            listOf(BotApprovalChoice.ONCE, BotApprovalChoice.DENY),
            BotApprovalRequest.from(
                obj("request_id" to text("a"), "smart_denied" to BotJSON.bool(true)),
            )?.choices,
        )
    }

    @Test
    fun `approval reads pattern keys with plural precedence and tool name`() {
        val mixed = BotApprovalRequest.from(
            obj(
                "request_id" to text("a"),
                "pattern_key" to text("tirith:shortened_url"),
                "pattern_keys" to array(text("tirith:shortened_url"), text("recursive delete")),
                "tool_name" to text("terminal"),
            ),
        )
        assertEquals(listOf("tirith:shortened_url", "recursive delete"), mixed?.patternKeys)
        assertEquals("terminal", mixed?.toolName)

        val fallback = BotApprovalRequest.from(
            obj(
                "request_id" to text("b"),
                "pattern_key" to text("recursive delete"),
                "pattern_keys" to array(),
            ),
        )
        assertEquals(listOf("recursive delete"), fallback?.patternKeys)
        assertNull(fallback?.toolName)
        assertNull(BotApprovalRequest.from(obj("command" to text("rm -rf /"))))
    }

    @Test
    fun `single and batch questions preserve wire labels ids and locks`() {
        val single = BotQuestionRequest.from(
            obj(
                "request_id" to text("clr-1"),
                "question" to text("Which mailbox first?"),
                "choices" to array(text("Primary (Recommended)"), text("Follow-ups")),
            ),
        )
        assertFalse(single!!.isBatch)
        assertNull(single.questions.single().wireID)
        assertEquals(listOf("Primary", "Follow-ups"), single.questions.single().choices.map { it.label })
        assertEquals(
            listOf("Primary (Recommended)", "Follow-ups"),
            single.questions.single().choices.map { it.wireLabel },
        )
        assertEquals(listOf(true, false), single.questions.single().choices.map { it.isRecommended })

        val batch = BotQuestionRequest.from(
            obj(
                "request_id" to text("clr-2"),
                "questions" to array(
                    question("q0", "Which mailbox?"),
                    question("q1", "Newsletters?", multiple = true),
                    obj("qid" to text("broken")),
                ),
                "answers" to obj("q0" to text("Primary")),
            ),
        )
        assertTrue(batch!!.isBatch)
        assertEquals(listOf("q0", "q1"), batch.questions.map { it.wireID })
        assertEquals(listOf(true, false), batch.questions.map { it.isAnswered })
        assertEquals(1, batch.unansweredCount)
        assertTrue(batch.questions.last().allowsMultipleChoices)
    }

    @Test
    fun `unreadable questions and requests without identities are dropped`() {
        assertNull(BotQuestionRequest.from(obj("request_id" to text("clr"))))
        assertNull(BotQuestionRequest.from(obj("request_id" to text("clr"), "question" to text("  "))))
        assertNull(BotQuestionRequest.from(obj("question" to text("orphan"))))
        assertNull(BotQuestionRequest.from(BotJSON.NULL))

        assertNull(BotServerRequest.from(obj("method" to text("sudo"), "params" to obj("session_id" to text("runtime")))))
        assertNull(BotServerRequest.from(frame("sudo", id = "", runtime = "runtime")))
        assertNull(BotServerRequest.from(frame("sudo", runtime = "")))
    }

    @Test
    fun `multi selection answer is a compact JSON array string`() {
        val answer = BotQuestionAnswer.selections("q1", listOf("Archive", "Unsubscribe"))
        assertEquals("q1", answer.questionID)
        assertEquals("[\"Archive\",\"Unsubscribe\"]", answer.text)
    }

    @Test
    fun `credential and desktop methods map while vault and future methods remain cardless`() {
        val sudo = BotServerRequest.from(frame("sudo", extra = mapOf("command" to text("sudo ls"))))
        val secret = BotServerRequest.from(
            frame(
                "secret",
                extra = mapOf("env_var" to text("OPENAI_API_KEY"), "prompt" to text("Paste the key")),
            ),
        )
        assertEquals(
            BotPendingRequest.Credential(BotCredentialRequest(BotCredentialRequest.Kind.SUDO, "srq-1", null, null)),
            sudo?.pending,
        )
        assertEquals(
            BotPendingRequest.Credential(
                BotCredentialRequest(BotCredentialRequest.Kind.SECRET, "srq-1", "OPENAI_API_KEY", "Paste the key"),
            ),
            secret?.pending,
        )

        BotDesktopTaskRequest.Kind.entries.forEach { kind ->
            val pending = BotServerRequest.from(frame(kind.rawValue))?.pending
            assertEquals(BotPendingRequest.DesktopTask(BotDesktopTaskRequest(kind, "srq-1")), pending)
            assertTrue(kind.title(HermesRequestSubject.HERMES) != kind.title(HermesRequestSubject.BOT))
            assertTrue(kind.detail(HermesRequestSubject.HERMES) != kind.detail(HermesRequestSubject.BOT))
        }

        listOf("mcp.setup", "vault.unlock_prompt", "vault.save_login", "vault.code", "future.prompt").forEach { method ->
            val request = BotServerRequest.from(frame(method))
            assertEquals(method, request?.method)
            assertNull(request?.pending)
        }
    }

    @Test
    fun `connection parser deduplicates rows and treats absent field flags safely`() {
        val operation = BotConnectionOperation.from(
            operation(
                seq = 7,
                targets = listOf(
                    connector("gmail"),
                    mcpInstall("github"),
                    connector("gmail", state = "connected"),
                    obj(
                        "name" to text("future"),
                        "kind" to text("hologram"),
                        "action" to text("beam"),
                        "state" to text("paused"),
                    ),
                    obj("kind" to text("mcp")),
                ),
            ),
        )

        assertEquals("op-1", operation?.opID)
        assertEquals(7, operation?.seq)
        assertEquals(listOf("gmail", "github", "future"), operation?.targets?.map { it.name })
        val github = operation!!.targets[1]
        assertEquals(listOf("GITHUB_TOKEN", "GITHUB_HOST"), github.requiredEnv.map { it.name })
        assertTrue(github.requiredEnv[0].isRequired)
        assertTrue(github.requiredEnv[0].isSecret)
        assertNull(github.requiredEnv[0].defaultValue)
        assertFalse(github.requiredEnv[1].isRequired)
        assertEquals("github.com", github.requiredEnv[1].defaultValue)
        assertFalse(operation.targets[2].canSkip)
        assertFalse(operation.targets[2].canConnect)
    }

    @Test
    fun `connection rows offer only legal moves and plain defaults ride along`() {
        val operation = BotConnectionOperation.from(operation())!!
        val gmail = operation.targets[0]
        val github = operation.targets[1]

        assertEquals("https://accounts.example/oauth?op=1", gmail.linkToOpen)
        assertTrue(gmail.canSkip)
        assertFalse(gmail.canConnect)
        assertTrue(operation.offers(BotConnectionAnswer.Skip("gmail")))
        assertFalse(operation.offers(BotConnectionAnswer.Connect("github", emptyMap())))

        assertEquals(
            mapOf("GITHUB_TOKEN" to "ghp_1", "GITHUB_HOST" to "github.com"),
            github.env(mapOf("GITHUB_TOKEN" to " ghp_1 ")),
        )
        assertTrue(github.accepts(mapOf("GITHUB_TOKEN" to "ghp_1")))
        assertTrue(github.accepts(mapOf("GITHUB_TOKEN" to "ghp_1", "GITHUB_HOST" to "ghe.example")))
        assertFalse(github.accepts(mapOf("GITHUB_TOKEN" to "")))
        assertFalse(github.accepts(mapOf("GITHUB_TOKEN" to "ghp_1", "PATH" to "/tmp")))
        assertTrue(operation.offers(BotConnectionAnswer.ContinueWithout))
    }

    @Test
    fun `only a newer frame for the same connection operation replaces it`() {
        val held = BotConnectionOperation.from(operation(seq = 3))!!
        val older = BotConnectionOperation.from(operation(seq = 2, targets = listOf(connector("gmail", "connected"))))!!
        val equal = BotConnectionOperation.from(operation(seq = 3, targets = listOf(connector("gmail", "connected"))))!!
        val foreign = BotConnectionOperation.from(operation(id = "op-2", seq = 9))!!
        val newer = BotConnectionOperation.from(operation(seq = 4, targets = listOf(connector("gmail", "connected"))))!!

        assertEquals(held, held.applying(older))
        assertEquals(held, held.applying(equal))
        assertEquals(held, held.applying(foreign))
        assertEquals(newer, held.applying(newer))
    }

    @Test
    fun `withdrawals stay silent for resolutions and desktop tasks`() {
        val speaking = listOf(
            "approval" to "timeout",
            "clarify" to "interrupted",
            "sudo" to "shutdown",
        )
        speaking.forEach { (method, reason) -> assertTrue(BotRequestWithdrawal.from(method, reason) != null) }
        assertNull(BotRequestWithdrawal.from("approval", "resolved"))
        assertNull(BotRequestWithdrawal.from("sudo", null))
        BotDesktopTaskRequest.Kind.entries.forEach { assertNull(BotRequestWithdrawal.from(it.rawValue, "timeout")) }
    }

    private fun frame(
        method: String,
        id: String = "srq-1",
        runtime: String = "runtime",
        extra: Map<String, BotJSON> = emptyMap(),
    ): BotJSON = obj(
        "jsonrpc" to text("2.0"),
        "id" to text(id),
        "method" to text(method),
        "params" to obj(extra + ("session_id" to text(runtime))),
    )

    private fun question(id: String, prompt: String, multiple: Boolean = false): BotJSON = obj(
        "qid" to text(id),
        "question" to text(prompt),
        "choices" to array(text("Primary")),
        "multi_select" to BotJSON.bool(multiple),
    )

    private fun operation(
        id: String = "op-1",
        seq: Int = 1,
        settled: Boolean = false,
        targets: List<BotJSON> = listOf(connector("gmail"), mcpInstall("github")),
    ): BotJSON = obj(
        "op_id" to text(id),
        "seq" to BotJSON.number(seq),
        "deadline_at" to BotJSON.number(1_900_000_000.0),
        "settled" to BotJSON.bool(settled),
        "targets" to BotJSON.array(targets),
    )

    private fun connector(name: String, state: String = "pending"): BotJSON = obj(
        "name" to text(name),
        "kind" to text("connector"),
        "action" to text("connect"),
        "state" to text(state),
        "connect_url" to text("https://accounts.example/oauth?op=1"),
    )

    private fun mcpInstall(name: String): BotJSON = obj(
        "name" to text(name),
        "kind" to text("mcp"),
        "action" to text("install"),
        "state" to text("pending"),
        "required_env" to array(
            obj(
                "name" to text("GITHUB_TOKEN"),
                "required" to BotJSON.bool(true),
                "secret" to BotJSON.bool(true),
                "default" to text("must-not-be-used"),
            ),
            obj(
                "name" to text("GITHUB_HOST"),
                "required" to BotJSON.bool(false),
                "secret" to BotJSON.bool(false),
                "default" to text("github.com"),
            ),
        ),
    )

    private fun obj(vararg fields: Pair<String, BotJSON>): BotJSON = BotJSON.objectOf(*fields)
    private fun obj(fields: Map<String, BotJSON>): BotJSON = BotJSON.objectOf(fields)
    private fun array(vararg values: Any): BotJSON = BotJSON.array(values.map { it as BotJSON })
    private fun text(value: String): BotJSON = BotJSON.string(value)
}
