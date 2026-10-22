package io.github.jcartu.hermex.ui.feature.hermes.sessions

import io.github.jcartu.hermex.core.navigation.HermesSessionDestination
import io.github.jcartu.hermex.model.ServerAccount
import io.github.jcartu.hermex.model.ServerKind
import io.github.jcartu.hermex.model.hermes.*
import io.github.jcartu.hermex.ui.feature.onboarding.AuthState
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class HermesSessionLookupTest {
    private val server = ServerAccount("https://hermes.example", "https://hermes.example", kind = ServerKind.HERMES)
    private val webui = ServerAccount("https://webui.example", "https://webui.example")
    @Test fun `registry routes do not sign in another server and serverless selects first webui only`() {
        val destination = HermesSessionDestination(server.id, null, "exact")
        assertEquals(HermesSessionLinkOutcome.Ignore, HermesSessionLookup.route(destination, AuthState.Unconfigured, emptyList()))
        assertTrue(HermesSessionLookup.route(destination, AuthState.LoggedIn(webui), listOf(webui, server)) is HermesSessionLinkOutcome.SwitchServer)
        assertTrue(HermesSessionLookup.route(destination, AuthState.LoggedOut(server), listOf(server)) is HermesSessionLinkOutcome.WaitForSignIn)
        assertTrue(HermesSessionLookup.route(destination, AuthState.LoggedIn(server), listOf(server)) is HermesSessionLinkOutcome.Open)
        assertEquals(webui.id, HermesSessionLookup.serverless("exact", listOf(server, webui))?.serverId)
        assertNull(HermesSessionLookup.serverless("exact", listOf(server)))
    }
    @Test fun `unknown Profile demands exactly one exact id and a prefix answer never opens`() = runTest {
        val wire = SessionTestWire().apply { profiles = listOf("default", "work") }
        wire.onRow = { _, _ -> BotJSON.parse("""{"id":"exact-longer"}""") }
        assertEquals(HermesSessionResolver.Outcome.Gone, HermesSessionResolver.resolve("exact", null, wire))
        wire.onRow = { _, _ -> BotJSON.parse("""{"id":"exact"}""") }
        assertEquals(HermesSessionResolver.Outcome.Ambiguous, HermesSessionResolver.resolve("exact", null, wire))
        val named = HermesSessionResolver.resolve("exact", "work", wire) as HermesSessionResolver.Outcome.Found
        assertEquals("work", named.session.profile)
        val default = HermesSessionResolver.resolve("exact", "", wire) as HermesSessionResolver.Outcome.Found
        assertEquals("default", default.session.profile)
    }
    @Test fun `one unreadable store cannot prove a unique link and room sessions never open`() = runTest {
        val wire = SessionTestWire().apply { profiles = listOf("default", "work") }
        wire.onRow = { _, profile -> if (profile == "work") throw BotFailure.Rejected(503) else BotJSON.parse("""{"id":"exact"}""") }
        try { HermesSessionResolver.resolve("exact", null, wire); fail("A failed store was treated as absent") }
        catch (error: BotFailure.Rejected) { assertEquals(503, error.code) }
        wire.onRow = { _, _ -> BotJSON.parse("""{"id":"exact","hidden":true,"title":"Group: room"}""") }
        assertEquals(HermesSessionResolver.Outcome.Room, HermesSessionResolver.resolve("exact", "work", wire))
    }
    @Test fun `legacy compression opens the linked tip but keeps root title identity and hidden flag`() = runTest {
        val wire = SessionTestWire()
        wire.onRow = { key, _ -> when (key) {
            "tip" -> BotJSON.parse("""{"id":"tip","parent_session_id":"root","started_at":20}""")
            "root" -> BotJSON.parse("""{"id":"root","end_reason":"compression","title":"Bot Chat","hidden":true,"started_at":1}""")
            else -> null
        } }
        val result = HermesSessionResolver.resolve("tip", "default", wire) as HermesSessionResolver.Outcome.Found
        assertEquals("tip", result.session.row.id)
        assertEquals("root", result.session.row.identity)
        assertEquals(1.0, result.session.row.startedAt!!, 0.0)
        assertTrue(result.session.row.isBotChat)
    }
    @Test fun `cyclic lineage keeps own identity and delegate marker opens its exact parent`() = runTest {
        val wire = SessionTestWire()
        wire.onRow = { key, _ -> BotJSON.parse("""{"id":"$key","parent_session_id":"${if (key == "a") "b" else "a"}","end_reason":"compression"}""") }
        val cycle = HermesSessionResolver.resolve("a", "default", wire) as HermesSessionResolver.Outcome.Found
        assertEquals("a", cycle.session.row.identity)
        wire.onRow = { key, _ -> when (key) {
            "child" -> BotJSON.parse("""{"id":"child","model_config":"{\"_delegate_from\":\"parent\"}"}""")
            "parent" -> BotJSON.parse("""{"id":"parent","title":"Origin"}""")
            else -> null
        } }
        val parent = HermesSessionResolver.resolve("child", "default", wire, opensParent = true) as HermesSessionResolver.Outcome.Found
        assertEquals("parent", parent.session.row.id)
    }
}
