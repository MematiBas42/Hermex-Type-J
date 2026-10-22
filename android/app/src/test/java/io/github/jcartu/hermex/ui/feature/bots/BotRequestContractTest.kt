package io.github.jcartu.hermex.ui.feature.bots

import io.github.jcartu.hermex.model.hermes.BotJSON
import io.github.jcartu.hermex.model.hermes.BotPendingRequest
import io.github.jcartu.hermex.model.hermes.BotServerRequest
import io.github.jcartu.hermex.net.hermes.BotConnectionAnswer
import io.github.jcartu.hermex.net.hermes.HermesCall
import org.junit.Assert.*
import org.junit.Test

/** Current 2.0 BotPendingRequest contract: vault requests were removed upstream in #1148. */
class BotRequestContractTest {
    @Test fun `sudo and secret answer envelope ID with value and empty value skips`() {
        for (method in listOf("sudo", "secret")) {
            val request = BotServerRequest.from(BotJSON.parse("""{"id":"envelope-7","method":"$method","params":{"session_id":"runtime","env_var":"EXAMPLE_KEY","prompt":"Supply a value"}}"""))!!
            val card = request.pending as BotPendingRequest.Credential
            assertEquals("envelope-7", card.request.requestID)
            for (value in listOf("fixture-value", "")) {
                val call = HermesCall.RequestAnswer(card.request.requestID, HermesCall.Answer.Value(value))
                assertEquals("request.answer", call.method)
                val params = call.params()
                assertEquals(setOf("id", "result"), params.keys)
                assertEquals("envelope-7", params.getValue("id").text)
                assertEquals(mapOf("value" to BotJSON.string(value)), params.getValue("result").fields)
            }
        }
    }

    @Test fun `vault requests remain waiting without answerable cards`() {
        for (method in listOf("vault.unlock_prompt", "vault.save_login", "vault.code", "future.request")) {
            val request = BotServerRequest.from(BotJSON.parse("""{"id":"request","method":"$method","params":{"session_id":"runtime"}}"""))!!
            assertEquals(method, request.method)
            assertNull(request.pending)
        }
    }

    @Test fun `app connection answers use release-specific owner and typed target results`() {
        val connect = HermesCall.ConnectionRespond("runtime", "operation", BotConnectionAnswer.Connect("app", mapOf("TOKEN" to "fixture")))
        val current = connect.params("0.22.2")
        assertEquals(BotJSON.objectOf("type" to BotJSON.string("session"), "session_id" to BotJSON.string("runtime")), current["owner"])
        assertFalse(current.containsKey("session_id"))
        val target = current.getValue("result")["targets"].list!!.single()
        assertEquals("approved", target["status"].text)
        assertEquals("fixture", target["env"]["TOKEN"].text)
        val old = connect.params("0.21.4")
        assertEquals("runtime", old["session_id"]?.text)
        assertFalse(old.containsKey("owner"))
        assertEquals(BotJSON.objectOf("settled_by" to BotJSON.string("continue")), HermesCall.ConnectionRespond("runtime", "operation", BotConnectionAnswer.ContinueWithout).params()["result"])
    }
}
