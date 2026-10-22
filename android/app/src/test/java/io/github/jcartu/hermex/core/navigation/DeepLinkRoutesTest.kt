package io.github.jcartu.hermex.core.navigation

import io.github.jcartu.hermex.model.ServerAccount
import io.github.jcartu.hermex.model.ServerKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Server-naming resolution rules, including the serverless fallbacks — ports
 * of `WebuiEntryRoute`, `MovedWebuiRoute`, `WebuiPushDestination.route`,
 * `BotDeepLinkRouter`, `HermesNewChatDestination.route` and
 * `HermesSessionLinkRouter`.
 */
class DeepLinkRoutesTest {

    private fun account(id: String, kind: ServerKind): ServerAccount =
        ServerAccount(id = id, urlString = id, kind = kind)

    private val webuiA = account("https://a.example.com", ServerKind.WEBUI)
    private val webuiB = account("https://b.example.com", ServerKind.WEBUI)
    private val hermesH = account("http://100.64.1.5:9119", ServerKind.HERMES)
    private val hermesG = account("https://g.example.com", ServerKind.HERMES)

    private class FakeMoves(
        destinations: Map<String, String> = emptyMap(),
        private val exact: Map<Pair<String, String>, MovedSessionLookup.ExactTarget> = emptyMap(),
        private val profiles: Map<Pair<String, String>, MovedSessionLookup.ProfileMapping> = emptyMap(),
    ) : MovedSessionLookup {
        private val destinations = destinations.toMap()
        override fun destinationOf(sourceServerId: String): String? = destinations[sourceServerId]
        override val moves: List<MovedSessionLookup.Move> =
            destinations.map { (source, destination) -> MovedSessionLookup.Move(destination, source) }
        override fun profileMapping(sourceServerId: String, profile: String): MovedSessionLookup.ProfileMapping? =
            profiles[sourceServerId to profile]
        override fun exactTarget(sourceServerId: String, sessionId: String): MovedSessionLookup.ExactTarget? =
            exact[sourceServerId to sessionId]
    }

    private fun context(
        activeServerId: String?,
        signedIn: Boolean,
        servers: List<ServerAccount>,
        isBotModeEnabled: Boolean = false,
        botConnectionId: (String) -> String? = { null },
        movedSessions: MovedSessionLookup = MovedSessionLookup.NONE,
    ) = LinkRoutingContext(activeServerId, signedIn, servers, isBotModeEnabled, botConnectionId, movedSessions)

    // MARK: - WebuiEntryRoute (#899, hermes-server.md serverless fallbacks)

    @Test fun `webui entry stays on a webui server or with nothing configured`() {
        assertEquals(WebuiEntryRoute.Stay, WebuiEntryRoute.resolve(webuiA.id, listOf(webuiA, hermesH)))
        assertEquals(WebuiEntryRoute.Stay, WebuiEntryRoute.resolve(null, listOf(hermesH)))
    }

    @Test fun `webui entry switches a Hermes-active app to the first webui server or opens nothing`() {
        assertEquals(
            WebuiEntryRoute.SwitchServer(webuiA),
            WebuiEntryRoute.resolve(hermesH.id, listOf(hermesH, webuiA, webuiB)),
        )
        assertEquals(WebuiEntryRoute.Unavailable, WebuiEntryRoute.resolve(hermesH.id, listOf(hermesH, hermesG)))
    }

    @Test fun `webui entry treats a moved-out active server like a Hermes server`() {
        val moved = setOf(webuiA.id)
        assertEquals(
            WebuiEntryRoute.SwitchServer(webuiB),
            WebuiEntryRoute.resolve(webuiA.id, listOf(webuiA, webuiB), moved = moved),
        )
        assertEquals(WebuiEntryRoute.Unavailable, WebuiEntryRoute.resolve(webuiA.id, listOf(webuiA), moved = moved))
    }

    // MARK: - WebuiPushDestination.route

    @Test fun `webui push route ignores an unknown server and switches to a known one`() {
        val destination = WebuiPushDestination(webuiA.id, "s1")
        assertEquals(WebuiPushRoute.Ignore, destination.route(context(webuiA.id, true, listOf(hermesH))))
        assertEquals(
            WebuiPushRoute.SwitchServer(webuiA),
            destination.route(context(hermesH.id, true, listOf(webuiA, hermesH))),
        )
        // Switch even from another server's login screen.
        assertEquals(
            WebuiPushRoute.SwitchServer(webuiA),
            destination.route(context(webuiB.id, false, listOf(webuiA, webuiB))),
        )
    }

    @Test fun `webui push route waits for sign-in and opens when signed in`() {
        val destination = WebuiPushDestination(webuiA.id, "s1")
        assertEquals(WebuiPushRoute.WaitForSignIn, destination.route(context(webuiA.id, false, listOf(webuiA))))
        assertEquals(WebuiPushRoute.Open, destination.route(context(webuiA.id, true, listOf(webuiA))))
    }

    // MARK: - MovedWebuiRoute.session (#1217)

    @Test fun `moved session link opens on its destination with an exact record`() {
        val moves = FakeMoves(
            destinations = mapOf(webuiA.id to hermesH.id),
            exact = mapOf(
                (webuiA.id to "s1") to MovedSessionLookup.ExactTarget(hermesH.id, "k9", "Inbox"),
            ),
        )
        val context = context(hermesH.id, true, listOf(webuiA, hermesH), movedSessions = moves)
        val moved = MovedWebuiRoute.session(WebuiPushDestination(webuiA.id, "s1"), context)!!
        assertEquals(hermesH.id, moved.serverId)
        assertEquals("k9", moved.key)
        assertEquals("Inbox", moved.profile)
        assertEquals(webuiA.id, moved.movedFromServerId)
    }

    @Test fun `moved session link scans every Profile without an exact record`() {
        val moves = FakeMoves(destinations = mapOf(webuiA.id to hermesH.id))
        val context = context(hermesH.id, true, listOf(hermesH), movedSessions = moves)
        val moved = MovedWebuiRoute.session(WebuiPushDestination(webuiA.id, "s1"), context)!!
        assertEquals(hermesH.id, moved.serverId)
        assertNull(moved.profile)
        assertEquals("s1", moved.key)
        // The source webui server is gone: nothing to fall back to.
        assertNull(moved.movedFromServerId)
    }

    @Test fun `on-source link on a configured server never moves and an unmoved server never moves`() {
        val moves = FakeMoves(destinations = mapOf(webuiA.id to hermesH.id))
        val context = context(webuiA.id, true, listOf(webuiA, hermesH), movedSessions = moves)
        assertNull(MovedWebuiRoute.session(WebuiPushDestination(webuiA.id, "s1", opensOnSource = true), context))
        assertNull(MovedWebuiRoute.session(WebuiPushDestination(webuiB.id, "s1"), context))
    }

    @Test fun `moved fallback opens the configured source on source only`() {
        val destination = HermesSessionDestination(hermesH.id, null, "k", movedFromServerId = webuiA.id)
        assertEquals(
            WebuiPushDestination(webuiA.id, "k", opensOnSource = true),
            MovedWebuiRoute.fallback(destination),
        )
        assertNull(MovedWebuiRoute.fallback(destination.copy(movedFromServerId = null)))
    }

    // MARK: - MovedWebuiRoute.bareSession (#1217)

    @Test fun `bare session keeps its webui route when nothing moved`() {
        val context = context(webuiA.id, true, listOf(webuiA, hermesH))
        assertEquals(
            MovedWebuiRoute.Outcome.Webui(WebuiEntryRoute.Stay),
            MovedWebuiRoute.bareSession("s1", context),
        )
        val hermesActive = context(hermesH.id, true, listOf(webuiA, hermesH))
        assertEquals(
            MovedWebuiRoute.Outcome.Webui(WebuiEntryRoute.SwitchServer(webuiA)),
            MovedWebuiRoute.bareSession("s1", hermesActive),
        )
    }

    @Test fun `bare session opens on the one destination every move agrees on`() {
        val moves = FakeMoves(destinations = mapOf(webuiA.id to hermesH.id, webuiB.id to hermesH.id))
        val context = context(hermesH.id, true, listOf(webuiA, hermesH), movedSessions = moves)
        assertEquals(
            MovedWebuiRoute.Outcome.Hermes(
                HermesSessionDestination(hermesH.id, null, "s1", movedFromServerId = webuiA.id),
            ),
            MovedWebuiRoute.bareSession("s1", context),
        )
        // Both sources retained: no unique fallback, still opens.
        val bothRetained = context(hermesH.id, true, listOf(webuiA, webuiB, hermesH), movedSessions = moves)
        assertEquals(MovedWebuiRoute.Outcome.Refuse, MovedWebuiRoute.bareSession("s1", bothRetained))
    }

    @Test fun `bare session refuses with an unmoved webui server or two destinations`() {
        val oneMove = FakeMoves(destinations = mapOf(webuiA.id to hermesH.id))
        val withUnmoved = context(hermesH.id, true, listOf(webuiB, hermesH), movedSessions = oneMove)
        assertEquals(MovedWebuiRoute.Outcome.Refuse, MovedWebuiRoute.bareSession("s1", withUnmoved))

        val twoDestinations = FakeMoves(destinations = mapOf(webuiA.id to hermesH.id, webuiB.id to hermesG.id))
        val context = context(hermesH.id, true, listOf(hermesH, hermesG), movedSessions = twoDestinations)
        assertEquals(MovedWebuiRoute.Outcome.Refuse, MovedWebuiRoute.bareSession("s1", context))
    }

    // MARK: - MovedWebuiRoute.newChat (#1217)

    @Test fun `bare profile new chat follows the review mapping`() {
        val moves = FakeMoves(
            destinations = mapOf(webuiA.id to hermesH.id),
            profiles = mapOf(
                (webuiA.id to "Inbox") to MovedSessionLookup.ProfileMapping.Hermes("Mapped"),
                (webuiA.id to "Kept") to MovedSessionLookup.ProfileMapping.KeepOnWebui,
            ),
        )
        val context = context(hermesH.id, true, listOf(webuiA, hermesH), movedSessions = moves)
        val hermesOutcome = MovedWebuiRoute.newChat("Inbox", context)
        // A fresh request id each time (#1180), so identity is compared by fields.
        val hermesDestination = (hermesOutcome as MovedWebuiRoute.Outcome.Hermes).destination
        assertEquals(hermesH.id, hermesDestination.serverId)
        assertEquals("Mapped", hermesDestination.profile)
        assertEquals(
            MovedWebuiRoute.Outcome.Webui(WebuiEntryRoute.SwitchServer(webuiA)),
            MovedWebuiRoute.newChat("Kept", context),
        )
        // The mapped webui server is the active one: stay.
        val activeSource = context(webuiA.id, true, listOf(webuiA, hermesH), movedSessions = moves)
        assertEquals(
            MovedWebuiRoute.Outcome.Webui(WebuiEntryRoute.Stay),
            MovedWebuiRoute.newChat("Kept", activeSource),
        )
    }

    @Test fun `bare profile new chat takes the webui route among unmoved servers when no review named it`() {
        val moves = FakeMoves(destinations = mapOf(webuiA.id to hermesH.id))
        val context = context(webuiA.id, true, listOf(webuiA, webuiB, hermesH), movedSessions = moves)
        // webuiA moved but its Review never named the Profile: it never takes the link.
        assertEquals(
            MovedWebuiRoute.Outcome.Webui(WebuiEntryRoute.SwitchServer(webuiB)),
            MovedWebuiRoute.newChat("Unknown", context),
        )
        // An unmoved webui server beside a mapped place: no agreement, opens nothing.
        val mapped = FakeMoves(
            destinations = mapOf(webuiA.id to hermesH.id),
            profiles = mapOf((webuiA.id to "Inbox") to MovedSessionLookup.ProfileMapping.Hermes("Mapped")),
        )
        val disagree = context(hermesH.id, true, listOf(webuiB, hermesH), movedSessions = mapped)
        assertEquals(MovedWebuiRoute.Outcome.Refuse, MovedWebuiRoute.newChat("Inbox", disagree))
    }

    // MARK: - BotDeepLinkRouter (#554, #899)

    private val botConnection = "3f2504e0-4f89-41d3-9a0c-0305e82c3301"
    private fun bot(serverId: String) = BotDestination(serverId, botConnection, "Inbox")

    @Test fun `bot link is ignored for unknown server gated webui or replaced connection`() {
        val context = context(hermesH.id, true, listOf(webuiA, hermesH), isBotModeEnabled = true,
            botConnectionId = { botConnection })
        assertEquals(BotDeepLinkOutcome.Ignore, BotDeepLinkRouter.resolve(bot("https://gone.example.com"), context))
        // Bot Mode off and the named server is a webui server.
        val gated = context(webuiA.id, true, listOf(webuiA), isBotModeEnabled = false,
            botConnectionId = { botConnection })
        assertEquals(BotDeepLinkOutcome.Ignore, BotDeepLinkRouter.resolve(bot(webuiA.id), gated))
        // Connection replaced under the link.
        val replaced = context(webuiA.id, true, listOf(webuiA), isBotModeEnabled = true,
            botConnectionId = { "0ba3a45e-8d3c-4c4c-9f6e-111111111111" })
        assertEquals(BotDeepLinkOutcome.Ignore, BotDeepLinkRouter.resolve(bot(webuiA.id), replaced))
        val noConnection = context(webuiA.id, true, listOf(webuiA), isBotModeEnabled = true)
        assertEquals(BotDeepLinkOutcome.Ignore, BotDeepLinkRouter.resolve(bot(webuiA.id), noConnection))
    }

    @Test fun `bot link routes a Hermes server regardless of the gate and compares UUIDs case-insensitively`() {
        val context = context(hermesH.id, true, listOf(hermesH), isBotModeEnabled = false,
            botConnectionId = { botConnection.uppercase() })
        assertEquals(BotDeepLinkOutcome.Open(bot(hermesH.id)), BotDeepLinkRouter.resolve(bot(hermesH.id), context))
    }

    @Test fun `bot link switches waits and opens by auth state`() {
        val destinations = context(webuiA.id, true, listOf(webuiA, hermesH),
            botConnectionId = { botConnection })
        assertEquals(
            BotDeepLinkOutcome.SwitchServer(hermesH, bot(hermesH.id)),
            BotDeepLinkRouter.resolve(bot(hermesH.id), destinations),
        )
        val signedOut = context(hermesH.id, false, listOf(hermesH), botConnectionId = { botConnection })
        assertEquals(
            BotDeepLinkOutcome.WaitForSignIn(bot(hermesH.id)),
            BotDeepLinkRouter.resolve(bot(hermesH.id), signedOut),
        )
        val signedIn = context(hermesH.id, true, listOf(hermesH), botConnectionId = { botConnection })
        assertEquals(BotDeepLinkOutcome.Open(bot(hermesH.id)), BotDeepLinkRouter.resolve(bot(hermesH.id), signedIn))
    }

    // MARK: - HermesNewChatDestination.route / onActiveServer (#1180)

    @Test fun `hermes new chat routes only to a configured Hermes server`() {
        val destination = HermesNewChatDestination(hermesH.id, "Inbox")
        assertEquals(HermesNewChatRoute.Ignore, destination.route(context(hermesH.id, true, listOf(webuiA))))
        assertEquals(
            HermesNewChatRoute.Ignore,
            destination.route(context(webuiA.id, true, listOf(webuiA.copy(id = hermesH.id)))),
        )
        assertEquals(
            HermesNewChatRoute.SwitchServer(hermesH),
            destination.route(context(webuiA.id, true, listOf(webuiA, hermesH))),
        )
        assertEquals(HermesNewChatRoute.WaitForSignIn, destination.route(context(hermesH.id, false, listOf(hermesH))))
        assertEquals(HermesNewChatRoute.Open, destination.route(context(hermesH.id, true, listOf(hermesH))))
    }

    @Test fun `new chat on the active server is Hermes only for a Hermes server signed in or not`() {
        val hermesActive = context(hermesH.id, false, listOf(hermesH))
        val destination = hermesNewChatOnActiveServer(hermesActive, autoStartsVoiceInput = true)!!
        assertEquals(hermesH.id, destination.serverId)
        assertEquals(null, destination.profile)
        assertEquals(true, destination.autoStartsVoiceInput)
        assertNull(hermesNewChatOnActiveServer(context(webuiA.id, true, listOf(webuiA)), false))
        assertNull(hermesNewChatOnActiveServer(context(null, false, listOf(hermesH)), false))
    }

    // MARK: - HermesSessionLinkRouting (#1176)

    @Test fun `hermes session link resolves by registry and auth alone`() {
        val destination = HermesSessionDestination(hermesH.id, null, "k")
        assertEquals(
            HermesSessionResolution.Ignore,
            HermesSessionLinkRouting.resolve(destination, context(hermesH.id, true, listOf(webuiA))),
        )
        // A link naming a webui server becomes its push destination.
        assertEquals(
            HermesSessionResolution.Webui(webuiA.id, "k"),
            HermesSessionLinkRouting.resolve(destination.copy(serverId = webuiA.id), context(hermesH.id, true, listOf(webuiA, hermesH))),
        )
        assertEquals(
            HermesSessionResolution.SwitchServer(hermesH),
            HermesSessionLinkRouting.resolve(destination, context(webuiA.id, true, listOf(webuiA, hermesH))),
        )
        assertEquals(
            HermesSessionResolution.WaitForSignIn,
            HermesSessionLinkRouting.resolve(destination, context(hermesH.id, false, listOf(hermesH))),
        )
        assertEquals(
            HermesSessionResolution.Open,
            HermesSessionLinkRouting.resolve(destination, context(hermesH.id, true, listOf(hermesH))),
        )
    }
}
