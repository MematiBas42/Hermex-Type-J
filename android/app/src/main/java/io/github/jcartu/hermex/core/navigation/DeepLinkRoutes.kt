package io.github.jcartu.hermex.core.navigation

import io.github.jcartu.hermex.model.ServerAccount
import io.github.jcartu.hermex.model.ServerKind

/** Mirror of HermesSessionsHome's `HermesSessionLinkOutcome` (injected, adapted at the call site). */
sealed interface HermesSessionResolution {
    data object Ignore : HermesSessionResolution
    data class Webui(val serverId: String, val sessionId: String) : HermesSessionResolution
    data object WaitForSignIn : HermesSessionResolution
    data class SwitchServer(val account: ServerAccount) : HermesSessionResolution
    data object Open : HermesSessionResolution
}

/**
 * Pure deep-link routing — the ports of `ContentView.swift`'s
 * `WebuiEntryRoute` / `MovedWebuiRoute`, `PushNotificationRouter.swift`'s
 * `WebuiPushDestination.route`, `Features/Bots/BotDeepLink.swift`'s
 * `BotDeepLinkRouter` and `AppIntents/HermexAppIntents.swift`'s
 * `HermesNewChatDestination.route`/`onActiveServer`.
 *
 * Every decision is computed from the registry and auth state alone, exactly
 * like Swift: "Nothing is read from, or signed in to, a server the link
 * doesn't name: another server, signed in or on its sign-in form, is switched
 * away from first." No Compose, no coroutines — fully unit-testable.
 */

/** What the router knows: the active server, sign-in, the registry, Bot Mode. */
data class LinkRoutingContext(
    /** The active server's `ServerAccount.id`, or null when unconfigured (Swift `State.server`). */
    val activeServerId: String?,
    /** True when the active server is signed in (Swift `.loggedIn`). */
    val signedIn: Boolean,
    /** Every configured server, as the registry stores them. */
    val servers: List<ServerAccount>,
    /** Bot Mode opt-in (#1239): gates bot links naming a webui server; a Hermes server routes regardless (#899). */
    val isBotModeEnabled: Boolean = false,
    /** The server's saved Bot connection UUID, or null — Swift `BotDeepLinkRouter.savedConnectionID`. */
    val botConnectionId: (String) -> String? = { null },
    /** Move records (MoveToHermes' `MovedSessionMap` behind this seam); [MovedSessionLookup.NONE] before any move. */
    val movedSessions: MovedSessionLookup = MovedSessionLookup.NONE,
)

/**
 * Structural seam over MoveToHermes' `MovedSessionMap`
 * (`ui/feature/movetohermes`), so this package never imports UI. The adapter
 * lives at the HermexApp call site. Semantics are the map's: a lookup names a
 * route only while its destination Hermes server still exists (remove/re-add
 * does not resurrect routes).
 */
interface MovedSessionLookup {
    /** The Hermes server id holding the move record for [sourceServerId], or null when it never moved or the destination is gone. */
    fun destinationOf(sourceServerId: String): String?

    /** Every recorded move: the Hermes server holding the record and the webui server it names. */
    val moves: List<Move>

    /** Where Review mapped webui Profile [profile] on [sourceServerId]: a Hermes Profile, kept on webui, or null (not named). */
    fun profileMapping(sourceServerId: String, profile: String): ProfileMapping?

    /** The exact chat a moved webui session became, when the move recorded it. */
    fun exactTarget(sourceServerId: String, sessionId: String): ExactTarget?

    data class Move(val destinationServerId: String, val sourceServerId: String)

    sealed interface ProfileMapping {
        data class Hermes(val profile: String) : ProfileMapping
        data object KeepOnWebui : ProfileMapping
    }

    data class ExactTarget(val destinationServerId: String, val sessionKey: String, val profile: String?)

    companion object {
        /** No moves recorded: every route is today's. */
        val NONE: MovedSessionLookup = object : MovedSessionLookup {
            override fun destinationOf(sourceServerId: String): String? = null
            override val moves: List<Move> = emptyList()
            override fun profileMapping(sourceServerId: String, profile: String): ProfileMapping? = null
            override fun exactTarget(sourceServerId: String, sessionId: String): ExactTarget? = null
        }
    }
}

/**
 * Port of `WebuiEntryRoute` (`ContentView.swift`, #899): where a webui-only
 * entry point goes when the active server may be a Hermes server.
 */
sealed interface WebuiEntryRoute {
    /** The active server is a webui server, or nothing is configured: proceed as before. */
    data object Stay : WebuiEntryRoute

    /** A Hermes server is active: make the first webui server in the registry active. */
    data class SwitchServer(val account: ServerAccount) : WebuiEntryRoute

    /** A Hermes server is active and no webui server is configured. */
    data object Unavailable : WebuiEntryRoute

    companion object {
        /**
         * Port of `WebuiEntryRoute.resolve(active:servers:moved:)`. [moved]
         * are webui servers an entry point mustn't take (`MovedWebuiRoute`):
         * one that is active is left like a Hermes server, for the first
         * webui server not among them.
         */
        fun resolve(
            activeServerId: String?,
            servers: List<ServerAccount>,
            moved: Set<String> = emptySet(),
        ): WebuiEntryRoute {
            val activeIsWebui = activeServerId != null &&
                servers.firstOrNull { it.id == activeServerId }?.kind != ServerKind.HERMES &&
                activeServerId !in moved
            if (activeServerId == null || activeIsWebui) return Stay
            val webui = servers.firstOrNull { it.kind == ServerKind.WEBUI && it.id !in moved }
                ?: return Unavailable
            return SwitchServer(webui)
        }
    }
}

/** Port of `WebuiPushDestination.route(state:servers:)`. */
sealed interface WebuiPushRoute {
    data object Ignore : WebuiPushRoute
    data object WaitForSignIn : WebuiPushRoute
    data object Open : WebuiPushRoute
    data class SwitchServer(val account: ServerAccount) : WebuiPushRoute
}

/**
 * Port of `WebuiPushDestination.route(state:servers:)`: Switch even from
 * another server's login screen — credentials are never requested or looked
 * up against a different server to resolve this session.
 */
fun WebuiPushDestination.route(context: LinkRoutingContext): WebuiPushRoute {
    val account = context.servers.firstOrNull { it.id == serverId } ?: return WebuiPushRoute.Ignore
    if (context.activeServerId != serverId) return WebuiPushRoute.SwitchServer(account)
    return if (context.signedIn) WebuiPushRoute.Open else WebuiPushRoute.WaitForSignIn
}

/**
 * Port of `MovedWebuiRoute` (`ContentView.swift`, #707, #1217): where a webui
 * server's old entry points go once it moved to Hermes. Move records come from
 * [MovedSessionLookup] (MoveToHermes' `MovedSessionMap`); without records
 * every route is today's.
 */
object MovedWebuiRoute {

    /** What a bare link or webui Profile Shortcut does (port of `MovedWebuiRoute.Outcome`). */
    sealed interface Outcome<out D> {
        /** No move could own it: the webui route, among webui servers that didn't move. */
        data class Webui(val route: WebuiEntryRoute) : Outcome<Nothing>
        data class Hermes<D>(val destination: D) : Outcome<D>

        /** More than one place could take it, or the one place can't. */
        data object Refuse : Outcome<Nothing>
    }

    /**
     * Port of `MovedWebuiRoute.session(_:servers:)`: [link] as a session link
     * on the Hermes server its webui server moved to, falling back to that
     * webui server while it is configured; null when it didn't move, or when
     * [link] is about a run on it and it is still configured. An exact move
     * record pins destination, stored key and Profile; otherwise the session
     * is looked up in every Profile, so it is found where it actually is
     * rather than where Review mapped its Profile.
     */
    fun session(
        link: WebuiPushDestination,
        context: LinkRoutingContext,
    ): HermesSessionDestination? {
        val isConfigured = context.servers.any { it.id == link.serverId && it.kind == ServerKind.WEBUI }
        if (link.opensOnSource && isConfigured) return null
        val exact = context.movedSessions.exactTarget(link.serverId, link.sessionId)
        val destinationId = exact?.destinationServerId
            ?: context.movedSessions.destinationOf(link.serverId)
            ?: return null
        return HermesSessionDestination(
            serverId = destinationId,
            profile = exact?.profile,
            key = exact?.sessionKey ?: link.sessionId,
            movedFromServerId = if (isConfigured) link.serverId else null,
        )
    }

    /**
     * Port of `MovedWebuiRoute.fallback(for:)`: the link the Sessions list
     * opens when no Profile has [destination]'s key — the session on the
     * webui server it moved from, which opens there and never comes back.
     * Null for any other link.
     */
    fun fallback(destination: HermesSessionDestination): WebuiPushDestination? =
        destination.movedFromServerId?.let {
            WebuiPushDestination(serverId = it, sessionId = destination.key, opensOnSource = true)
        }

    /**
     * Port of `MovedWebuiRoute.bareSession(_:active:servers:)` (#1217): a bare
     * `session?id=` link, which any moved server could own. With any move
     * recorded it opens only when every move went to one Hermes server, at
     * most one of them is still configured to fall back to, and no webui
     * server stayed unmoved. With none, today's webui route.
     */
    fun bareSession(
        key: String,
        context: LinkRoutingContext,
    ): Outcome<HermesSessionDestination> {
        val moves = context.movedSessions.moves
        if (moves.isEmpty()) {
            return Outcome.Webui(WebuiEntryRoute.resolve(context.activeServerId, context.servers))
        }
        val destinations = moves.map { it.destinationServerId }.toSet()
        val sources = moves.map { it.sourceServerId }.toSet()
        val retained = sources.filter { id -> context.servers.any { it.id == id && it.kind == ServerKind.WEBUI } }
        val unmoved = unmovedWebui(context.servers, moves)
        if (unmoved.isNotEmpty() || destinations.size != 1 || retained.size > 1) return Outcome.Refuse
        return Outcome.Hermes(
            HermesSessionDestination(
                serverId = destinations.single(),
                profile = null,
                key = key,
                movedFromServerId = retained.singleOrNull(),
            ),
        )
    }

    /**
     * Port of `MovedWebuiRoute.newChat(inProfile:active:servers:)` (#1217): a
     * webui Profile's New Chat in Profile, which names no server. Each move
     * whose Review named [profile] sends it where it was mapped; with any
     * such move it opens only where they and every unmoved webui server
     * agree. With none, the webui route among unmoved webui servers.
     */
    fun newChat(
        profile: String?,
        context: LinkRoutingContext,
    ): Outcome<HermesNewChatDestination> {
        val moves = context.movedSessions.moves
        val places = mutableSetOf<Place>()
        for (move in moves) {
            when (val mapping = profile?.let { context.movedSessions.profileMapping(move.sourceServerId, it) }) {
                is MovedSessionLookup.ProfileMapping.Hermes ->
                    places += Place.Hermes(move.destinationServerId, mapping.profile)
                MovedSessionLookup.ProfileMapping.KeepOnWebui ->
                    places += Place.Webui(move.sourceServerId)
                null -> Unit
            }
        }
        if (places.isEmpty()) {
            return Outcome.Webui(
                WebuiEntryRoute.resolve(
                    context.activeServerId,
                    context.servers,
                    moved = moves.map { it.sourceServerId }.toSet(),
                ),
            )
        }
        places += unmovedWebui(context.servers, moves).map { Place.Webui(it.id) }
        val place = places.singleOrNull() ?: return Outcome.Refuse
        return when (place) {
            is Place.Hermes -> Outcome.Hermes(
                HermesNewChatDestination(serverId = place.serverId, profile = place.profile),
            )
            is Place.Webui -> {
                val account = context.servers.firstOrNull { it.id == place.serverId && it.kind == ServerKind.WEBUI }
                    ?: return Outcome.Refuse
                Outcome.Webui(
                    if (context.activeServerId == place.serverId) WebuiEntryRoute.Stay
                    else WebuiEntryRoute.SwitchServer(account),
                )
            }
        }
    }

    private sealed interface Place {
        data class Hermes(val serverId: String, val profile: String) : Place
        data class Webui(val serverId: String) : Place
    }

    private fun unmovedWebui(servers: List<ServerAccount>, moves: List<MovedSessionLookup.Move>): List<ServerAccount> {
        val moved = moves.map { it.sourceServerId }.toSet()
        return servers.filter { it.kind == ServerKind.WEBUI && it.id !in moved }
    }
}

/** Port of `BotDeepLinkOutcome` (`Features/Bots/BotDeepLink.swift`, #554). */
sealed interface BotDeepLinkOutcome {
    /** Nothing to route: Bot Mode is off and the named server is a webui server, the named server is gone, or its Bot connection was removed or replaced. */
    data object Ignore : BotDeepLinkOutcome

    /** Hold the destination until the user is signed in, then resolve it again. */
    data class WaitForSignIn(val destination: BotDestination) : BotDeepLinkOutcome

    /** Make the destination's server active first; the rebuilt tree takes the held destination. */
    data class SwitchServer(val account: ServerAccount, val destination: BotDestination) : BotDeepLinkOutcome

    /** The destination's server is already active: hand it to the Bots inbox. */
    data class Open(val destination: BotDestination) : BotDeepLinkOutcome
}

/**
 * Port of `BotDeepLinkRouter.resolve(_:state:servers:isBotModeEnabled:botConnectionID:)`:
 * decided by the Bot Mode gate, the server registry, the per-server Bot
 * connection and the auth state. The roster is not consulted: an unknown
 * Profile is resolved by the inbox it lands on, so a link never waits on a
 * socket to be routed.
 */
object BotDeepLinkRouter {
    fun resolve(destination: BotDestination, context: LinkRoutingContext): BotDeepLinkOutcome {
        val account = context.servers.firstOrNull { it.id == destination.serverId }
            ?: return BotDeepLinkOutcome.Ignore
        // Bot Mode off: a webui server's session list opens and no Bot UI
        // leaks through a link. A Hermes server is Bot UI throughout and
        // opens either way (#899).
        if (!context.isBotModeEnabled && account.kind != ServerKind.HERMES) return BotDeepLinkOutcome.Ignore
        // A replaced endpoint or account mints a new connection UUID, so this
        // also covers "the link names a bot on a connection this server no
        // longer has". UUID equality ignores case (Swift `UUID` equality;
        // the saved record's casing may vary).
        val savedConnectionId = context.botConnectionId(destination.serverId)
        if (savedConnectionId == null || !savedConnectionId.equals(destination.connectionId, ignoreCase = true)) {
            return BotDeepLinkOutcome.Ignore
        }
        return when {
            context.activeServerId == destination.serverId ->
                if (context.signedIn) BotDeepLinkOutcome.Open(destination)
                else BotDeepLinkOutcome.WaitForSignIn(destination)
            else -> BotDeepLinkOutcome.SwitchServer(account, destination)
        }
    }
}

/** Port of `HermesNewChatDestination.Route` (`AppIntents/HermexAppIntents.swift`, #1180). */
sealed interface HermesNewChatRoute {
    /** The server isn't a configured Hermes server: the app opens as it is. */
    data object Ignore : HermesNewChatRoute
    data object WaitForSignIn : HermesNewChatRoute

    /** Make the server active first; the rebuilt home takes the held request. */
    data class SwitchServer(val account: ServerAccount) : HermesNewChatRoute
    data object Open : HermesNewChatRoute
}

/** Port of `HermesNewChatDestination.route(state:servers:)`. */
fun HermesNewChatDestination.route(context: LinkRoutingContext): HermesNewChatRoute {
    val account = context.servers.firstOrNull { it.id == serverId && it.kind == ServerKind.HERMES }
        ?: return HermesNewChatRoute.Ignore
    if (context.activeServerId != serverId) return HermesNewChatRoute.SwitchServer(account)
    return if (context.signedIn) HermesNewChatRoute.Open else HermesNewChatRoute.WaitForSignIn
}

/**
 * Port of `HermesNewChatDestination.onActiveServer(state:servers:autoStartsVoiceInput:)`:
 * New Chat and New Chat with Voice on the active server when it is a Hermes
 * server, signed in or not; null on a webui server, whose New Chat is
 * unchanged.
 */
fun hermesNewChatOnActiveServer(
    context: LinkRoutingContext,
    autoStartsVoiceInput: Boolean,
): HermesNewChatDestination? {
    val active = context.activeServerId ?: return null
    if (context.servers.firstOrNull { it.id == active }?.kind != ServerKind.HERMES) return null
    return HermesNewChatDestination(serverId = active, profile = null, autoStartsVoiceInput = autoStartsVoiceInput)
}

/**
 * Port of `HermesSessionLinkRouter` (`Features/Hermes/HermesSessionLookup.swift`,
 * #1176): routes a session link by the registry and auth state alone, as a
 * Bot link is routed. Nothing is read from, or signed in to, a server the
 * link doesn't name: another server, signed in or on its sign-in form, is
 * switched away from first.
 *
 * HermesSessionsHome owns the shipped equivalent (`HermesSessionLookup.route`);
 * this local port is `DeepLinkRouter.dispatch`'s default so the dispatcher
 * and its tests stand alone. The HermexApp call site injects theirs.
 */
object HermesSessionLinkRouting {
    fun resolve(destination: HermesSessionDestination, context: LinkRoutingContext): HermesSessionResolution {
        val account = context.servers.firstOrNull { it.id == destination.serverId }
            ?: return HermesSessionResolution.Ignore
        if (account.kind != ServerKind.HERMES) {
            return HermesSessionResolution.Webui(serverId = destination.serverId, sessionId = destination.key)
        }
        if (context.activeServerId != destination.serverId) {
            return HermesSessionResolution.SwitchServer(account)
        }
        return if (context.signedIn) HermesSessionResolution.Open else HermesSessionResolution.WaitForSignIn
    }
}
