package io.github.jcartu.hermex.core.navigation

import io.github.jcartu.hermex.model.ServerAccount
import io.github.jcartu.hermex.model.ServerKind
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The top-level dispatch — port of `ContentView.handleOpenURL`'s branch order:
 * what one parsed link does, per active server kind, including the serverless
 * fallbacks and the moved-webui recursion.
 */
class DeepLinkRouterDispatchTest {

    private fun account(id: String, kind: ServerKind): ServerAccount =
        ServerAccount(id = id, urlString = id, kind = kind)

    private val webuiA = account("https://a.example.com", ServerKind.WEBUI)
    private val webuiB = account("https://b.example.com", ServerKind.WEBUI)
    private val hermesH = account("http://100.64.1.5:9119", ServerKind.HERMES)

    private fun context(
        activeServerId: String?,
        signedIn: Boolean,
        servers: List<ServerAccount>,
        isBotModeEnabled: Boolean = false,
        botConnectionId: (String) -> String? = { null },
        movedSessions: MovedSessionLookup = MovedSessionLookup.NONE,
    ) = LinkRoutingContext(activeServerId, signedIn, servers, isBotModeEnabled, botConnectionId, movedSessions)

    private class ExactMoves(
        destinations: Map<String, String>,
        private val exact: Map<Pair<String, String>, MovedSessionLookup.ExactTarget> = emptyMap(),
    ) : MovedSessionLookup {
        private val destinations = destinations.toMap()
        override fun destinationOf(sourceServerId: String): String? = destinations[sourceServerId]
        override val moves: List<MovedSessionLookup.Move> =
            destinations.map { (source, destination) -> MovedSessionLookup.Move(destination, source) }
        override fun profileMapping(sourceServerId: String, profile: String): MovedSessionLookup.ProfileMapping? = null
        override fun exactTarget(sourceServerId: String, sessionId: String): MovedSessionLookup.ExactTarget? =
            exact[sourceServerId to sessionId]
    }

    // MARK: - New Chat on the active server (Hermes or webui) — the launcher shortcut

    @Test fun `new chat shortcut opens a Hermes new chat on an active Hermes server`() {
        val route = DeepLinkRouter.dispatch(AppDeepLink.NewChat, context(hermesH.id, true, listOf(hermesH, webuiA)))
        // A fresh request id each time (#1180), so identity is compared by fields.
        val destination = (route as DeepLinkRouter.Route.OpenHermesNewChat).destination
        assertEquals(hermesH.id, destination.serverId)
        assertEquals(null, destination.profile)
        assertEquals(false, destination.autoStartsVoiceInput)
    }

    @Test fun `new chat shortcut stays a webui new chat on an active webui server`() {
        val route = DeepLinkRouter.dispatch(AppDeepLink.NewChat, context(webuiA.id, true, listOf(webuiA, hermesH)))
        assertEquals(DeepLinkRouter.Route.OpenWebuiNewChat(profileName = null, autoStartsVoiceInput = false), route)
    }

    @Test fun `voice shortcut carries the voice flag on either kind`() {
        val hermes = DeepLinkRouter.dispatch(AppDeepLink.NewChatVoice, context(hermesH.id, true, listOf(hermesH)))
        assertEquals(true, (hermes as DeepLinkRouter.Route.OpenHermesNewChat).destination.autoStartsVoiceInput)
        val webui = DeepLinkRouter.dispatch(AppDeepLink.NewChatVoice, context(webuiA.id, true, listOf(webuiA)))
        assertEquals(DeepLinkRouter.Route.OpenWebuiNewChat(null, autoStartsVoiceInput = true), webui)
    }

    @Test fun `hermes new chat waits for sign-in on its own server`() {
        val route = DeepLinkRouter.dispatch(AppDeepLink.NewChat, context(hermesH.id, false, listOf(hermesH)))
        assertEquals(DeepLinkRouter.Route.HoldForSignIn, route)
    }

    // MARK: - Serverless session links (hermes-server.md): first webui server, or nothing

    @Test fun `bare session link switches a Hermes-active app to the first webui server`() {
        val route = DeepLinkRouter.dispatch(AppDeepLink.Session("s1"), context(hermesH.id, true, listOf(hermesH, webuiA, webuiB)))
        assertEquals(DeepLinkRouter.Route.SwitchServer(webuiA), route)
    }

    @Test fun `bare session link opens nothing when no webui server is configured`() {
        val route = DeepLinkRouter.dispatch(AppDeepLink.Session("s1"), context(hermesH.id, true, listOf(hermesH)))
        assertEquals(DeepLinkRouter.Route.Drop, route)
    }

    @Test fun `bare session link opens on the active webui server`() {
        val route = DeepLinkRouter.dispatch(AppDeepLink.Session("s1"), context(webuiA.id, true, listOf(webuiA, hermesH)))
        assertEquals(DeepLinkRouter.Route.OpenWebuiSession("s1"), route)
    }

    @Test fun `bare profile new chat switches to the first webui server or opens nothing`() {
        val link = AppDeepLink.WebuiNewChatInProfile("Inbox")
        assertEquals(
            DeepLinkRouter.Route.SwitchServer(webuiA),
            DeepLinkRouter.dispatch(link, context(hermesH.id, true, listOf(hermesH, webuiA))),
        )
        assertEquals(
            DeepLinkRouter.Route.Drop,
            DeepLinkRouter.dispatch(link, context(hermesH.id, true, listOf(hermesH))),
        )
        assertEquals(
            DeepLinkRouter.Route.OpenWebuiNewChat("Inbox", autoStartsVoiceInput = false),
            DeepLinkRouter.dispatch(link, context(webuiA.id, true, listOf(webuiA))),
        )
    }

    // MARK: - Server-named links

    @Test fun `hermes session link opens on its server after switching and waits for sign-in`() {
        val link = AppDeepLink.HermesSession(HermesSessionDestination(hermesH.id, "Inbox", "k"))
        assertEquals(
            DeepLinkRouter.Route.SwitchServer(hermesH),
            DeepLinkRouter.dispatch(link, context(webuiA.id, true, listOf(webuiA, hermesH))),
        )
        assertEquals(
            DeepLinkRouter.Route.HoldForSignIn,
            DeepLinkRouter.dispatch(link, context(hermesH.id, false, listOf(hermesH))),
        )
        assertEquals(
            DeepLinkRouter.Route.OpenHermesSession(HermesSessionDestination(hermesH.id, "Inbox", "k")),
            DeepLinkRouter.dispatch(link, context(hermesH.id, true, listOf(hermesH))),
        )
        // An unknown server: the app opens as it is.
        assertEquals(
            DeepLinkRouter.Route.Drop,
            DeepLinkRouter.dispatch(link, context(webuiA.id, true, listOf(webuiA))),
        )
    }

    @Test fun `session link naming a webui server takes the webui push route`() {
        val link = AppDeepLink.HermesSession(HermesSessionDestination(webuiA.id, null, "s1"))
        assertEquals(
            DeepLinkRouter.Route.OpenWebuiSession("s1"),
            DeepLinkRouter.dispatch(link, context(webuiA.id, true, listOf(webuiA))),
        )
    }

    @Test fun `webui push on a moved server opens the exact Hermes chat`() {
        val moves = ExactMoves(
            destinations = mapOf(webuiA.id to hermesH.id),
            exact = mapOf((webuiA.id to "s1") to MovedSessionLookup.ExactTarget(hermesH.id, "k9", "Inbox")),
        )
        val link = AppDeepLink.WebuiPush(WebuiPushDestination(webuiA.id, "s1"))
        val route = DeepLinkRouter.dispatch(link, context(hermesH.id, true, listOf(webuiA, hermesH), movedSessions = moves))
        assertEquals(
            DeepLinkRouter.Route.OpenHermesSession(
                HermesSessionDestination(hermesH.id, "Inbox", "k9", movedFromServerId = webuiA.id),
            ),
            route,
        )
    }

    @Test fun `on-source push on a configured moved server still opens on the source`() {
        val moves = ExactMoves(destinations = mapOf(webuiA.id to hermesH.id))
        val link = AppDeepLink.WebuiPush(WebuiPushDestination(webuiA.id, "s1", opensOnSource = true))
        val route = DeepLinkRouter.dispatch(link, context(webuiA.id, true, listOf(webuiA, hermesH), movedSessions = moves))
        assertEquals(DeepLinkRouter.Route.OpenWebuiSession("s1"), route)
    }

    @Test fun `hermes session link naming a removed moved webui server reroutes to its destination`() {
        // The webui server is gone from the registry; its move record lives on the destination.
        val moves = ExactMoves(destinations = mapOf(webuiA.id to hermesH.id))
        val link = AppDeepLink.HermesSession(HermesSessionDestination(webuiA.id, null, "s1"))
        val route = DeepLinkRouter.dispatch(link, context(hermesH.id, true, listOf(hermesH), movedSessions = moves))
        assertEquals(
            DeepLinkRouter.Route.OpenHermesSession(HermesSessionDestination(hermesH.id, null, "s1")),
            route,
        )
    }

    @Test fun `bot link posts to the inbox only when everything checks out`() {
        val connection = "3f2504e0-4f89-41d3-9a0c-0305e82c3301"
        val link = AppDeepLink.Bot(BotDestination(hermesH.id, connection, "Inbox"))
        assertEquals(
            DeepLinkRouter.Route.OpenBot(BotDestination(hermesH.id, connection, "Inbox")),
            DeepLinkRouter.dispatch(link, context(hermesH.id, true, listOf(hermesH), botConnectionId = { connection })),
        )
        assertEquals(
            DeepLinkRouter.Route.Drop,
            DeepLinkRouter.dispatch(link, context(hermesH.id, true, listOf(hermesH))),
        )
    }

    // MARK: - Pass-throughs

    @Test fun `share kanban and git links pass straight through`() {
        val context = context(webuiA.id, true, listOf(webuiA))
        assertEquals(DeepLinkRouter.Route.OpenShare, DeepLinkRouter.dispatch(AppDeepLink.Share, context))
        assertEquals(
            DeepLinkRouter.Route.OpenKanban("main", "c1"),
            DeepLinkRouter.dispatch(AppDeepLink.Kanban("main", "c1"), context),
        )
        assertEquals(
            DeepLinkRouter.Route.OpenGit("s1"),
            DeepLinkRouter.dispatch(AppDeepLink.Git("s1"), context),
        )
    }

    @Test fun `injected hermes session resolution drives the dispatch`() {
        val context = context(webuiA.id, true, listOf(webuiA))
        val link = AppDeepLink.HermesSession(HermesSessionDestination(hermesH.id, null, "k"))
        val route = DeepLinkRouter.dispatch(link, context) { _, _ -> HermesSessionResolution.WaitForSignIn }
        assertEquals(DeepLinkRouter.Route.HoldForSignIn, route)
    }
}
