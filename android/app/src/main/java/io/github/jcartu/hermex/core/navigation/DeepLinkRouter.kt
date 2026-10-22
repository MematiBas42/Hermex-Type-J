package io.github.jcartu.hermex.core.navigation

import io.github.jcartu.hermex.model.ServerAccount

/**
 * The top-level link dispatcher — port of `ContentView.handleOpenURL` +
 * `PendingLinks` (`HermesMobile/ContentView.swift`): decides what one parsed
 * link does, purely from the registry and auth state, so every outcome is
 * testable without a view.
 *
 * Re-runnable and idempotent: a route that asks for another server or a
 * sign-in leaves the pending link in place; after the switch or sign-in the
 * same dispatch is run again and takes the next step, exactly as Swift's
 * `PendingLinks.reroute` does on every state change.
 *
 * Hermes session links are routed through [hermesSessionRoute] —
 * HermesSessionsHome's `HermesSessionLookup.route`, injected at the call
 * site so this package stays UI-free.
 */
object DeepLinkRouter {

    /** The outcome of dispatching one link, executed by the shell. */
    sealed interface Route {
        /** Activate [account] and hold the link; the rebuilt tree re-dispatches it. */
        data class SwitchServer(val account: ServerAccount) : Route

        /** Hold the link until its server signs in, then re-dispatch. */
        data object HoldForSignIn : Route

        /** Nothing can open this link: drop it (the app opens as it is). */
        data object Drop : Route

        /** Open the webui session on the active server. */
        data class OpenWebuiSession(val sessionId: String) : Route

        /** Open the webui New Chat composer, optionally pinned to a Profile / starting voice (#338, #339). */
        data class OpenWebuiNewChat(val profileName: String?, val autoStartsVoiceInput: Boolean) : Route

        data class OpenKanban(val boardSlug: String?, val cardId: String?) : Route
        data class OpenGit(val sessionId: String?) : Route

        /** Hand the Hermes session to HermesSessionsHome (`HermesSessionLookup.open`). */
        data class OpenHermesSession(val destination: HermesSessionDestination) : Route

        /** Hand the new Hermes session to HermesSessionsHome (`HermesSessionLookup.openNewChat`). */
        data class OpenHermesNewChat(val destination: HermesNewChatDestination) : Route

        /** Hand the bot to BotMode (`BotLinkMailbox.post`). */
        data class OpenBot(val destination: BotDestination) : Route

        /** The share extension's open URL: route the waiting shared import (#1181). */
        data object OpenShare : Route
    }

    fun dispatch(
        link: AppDeepLink,
        context: LinkRoutingContext,
        hermesSessionRoute: (HermesSessionDestination, LinkRoutingContext) -> HermesSessionResolution =
            HermesSessionLinkRouting::resolve,
    ): Route = when (link) {
        is AppDeepLink.WebuiPush -> routeWebuiPush(link.destination, context, hermesSessionRoute, HOP_LIMIT)
        AppDeepLink.NewChat -> routeNewChat(autoStartsVoiceInput = false, context)
        AppDeepLink.NewChatVoice -> routeNewChat(autoStartsVoiceInput = true, context)
        is AppDeepLink.WebuiNewChatInProfile -> routeBareProfileNewChat(link.profileName, context)
        is AppDeepLink.HermesNewChat -> routeHermesNewChat(link.destination, context)
        is AppDeepLink.Bot -> routeBot(link.destination, context)
        is AppDeepLink.HermesSession -> routeHermesSession(link.destination, context, hermesSessionRoute, HOP_LIMIT)
        is AppDeepLink.Session -> routeBareSession(link.sessionId, context, hermesSessionRoute)
        AppDeepLink.Share -> Route.OpenShare
        is AppDeepLink.Kanban -> Route.OpenKanban(link.boardSlug, link.cardId)
        is AppDeepLink.Git -> Route.OpenGit(link.sessionId)
    }

    /**
     * The routes below recurse across at most link → moved webui push →
     * Hermes destination on the destination server, which is HERMES-kind and
     * can never route back to webui. [HOP_LIMIT] is a defensive bound, not
     * part of the contract.
     */
    private const val HOP_LIMIT = 4

    /**
     * Port of `PendingLinks.route(_ destination: WebuiPushDestination, …)`:
     * a webui conversation link — or, when its server moved to Hermes, that
     * server's session link (`MovedWebuiRoute`).
     */
    private fun routeWebuiPush(
        destination: WebuiPushDestination,
        context: LinkRoutingContext,
        hermesSessionRoute: (HermesSessionDestination, LinkRoutingContext) -> HermesSessionResolution,
        hopsLeft: Int,
    ): Route {
        if (hopsLeft > 0) {
            MovedWebuiRoute.session(destination, context)?.let { moved ->
                return routeHermesSession(moved, context, hermesSessionRoute, hopsLeft - 1)
            }
        }
        return when (val route = destination.route(context)) {
            WebuiPushRoute.Ignore -> Route.Drop
            WebuiPushRoute.WaitForSignIn -> Route.HoldForSignIn
            WebuiPushRoute.Open -> Route.OpenWebuiSession(destination.sessionId)
            is WebuiPushRoute.SwitchServer -> Route.SwitchServer(route.account)
        }
    }

    /**
     * Port of `PendingLinks.route(_ destination: HermesSessionDestination, …)`:
     * dropped, handed to a webui server's push route, or held while its
     * server signs in or activates. A link naming a webui server removed
     * after it moved to Hermes takes the push route, which sends it to its
     * destination (#1217).
     */
    private fun routeHermesSession(
        destination: HermesSessionDestination,
        context: LinkRoutingContext,
        hermesSessionRoute: (HermesSessionDestination, LinkRoutingContext) -> HermesSessionResolution,
        hopsLeft: Int,
    ): Route = when (val resolution = hermesSessionRoute(destination, context)) {
        HermesSessionResolution.Ignore -> {
            if (hopsLeft > 0 && context.movedSessions.destinationOf(destination.serverId) != null) {
                routeWebuiPush(
                    WebuiPushDestination(serverId = destination.serverId, sessionId = destination.key),
                    context,
                    hermesSessionRoute,
                    hopsLeft - 1,
                )
            } else {
                Route.Drop
            }
        }
        is HermesSessionResolution.Webui ->
            if (hopsLeft > 0) {
                routeWebuiPush(
                    WebuiPushDestination(serverId = resolution.serverId, sessionId = resolution.sessionId),
                    context,
                    hermesSessionRoute,
                    hopsLeft - 1,
                )
            } else {
                Route.Drop
            }
        HermesSessionResolution.WaitForSignIn -> Route.HoldForSignIn
        is HermesSessionResolution.SwitchServer -> Route.SwitchServer(resolution.account)
        HermesSessionResolution.Open -> Route.OpenHermesSession(destination)
    }

    /** Port of `ContentView.openNewChat(autoStartsVoiceInput:)` (#337, #338, #1180). */
    private fun routeNewChat(
        autoStartsVoiceInput: Boolean,
        context: LinkRoutingContext,
    ): Route {
        hermesNewChatOnActiveServer(context, autoStartsVoiceInput)?.let { destination ->
            return routeHermesNewChat(destination, context)
        }
        // A webui-only entry point: switch to the first webui server while a
        // Hermes server is active, or open nothing when none is configured —
        // a Hermes-only user is never asked to add one (#1181).
        return when (val route = WebuiEntryRoute.resolve(context.activeServerId, context.servers)) {
            WebuiEntryRoute.Stay -> Route.OpenWebuiNewChat(profileName = null, autoStartsVoiceInput)
            is WebuiEntryRoute.SwitchServer -> Route.SwitchServer(route.account)
            WebuiEntryRoute.Unavailable -> Route.Drop
        }
    }

    /**
     * Port of the `isWebuiNewChatInProfileURL` branch of `handleOpenURL`
     * (#339, #971, #1217): a bare-Profile New Chat link names no server — a
     * webui server that moved sends its Profile where Review mapped it,
     * unless another place could take it too.
     */
    private fun routeBareProfileNewChat(
        profileName: String?,
        context: LinkRoutingContext,
    ): Route = when (val outcome = MovedWebuiRoute.newChat(profileName, context)) {
        is MovedWebuiRoute.Outcome.Webui -> when (val route = outcome.route) {
            WebuiEntryRoute.Stay -> Route.OpenWebuiNewChat(profileName, autoStartsVoiceInput = false)
            is WebuiEntryRoute.SwitchServer -> Route.SwitchServer(route.account)
            WebuiEntryRoute.Unavailable -> Route.Drop
        }
        is MovedWebuiRoute.Outcome.Hermes -> routeHermesNewChat(outcome.destination, context)
        MovedWebuiRoute.Outcome.Refuse -> Route.Drop
    }

    /** Port of `HermesNewChatDestination.route(state:servers:)` (#1180). */
    private fun routeHermesNewChat(destination: HermesNewChatDestination, context: LinkRoutingContext): Route =
        when (val route = destination.route(context)) {
            HermesNewChatRoute.Ignore -> Route.Drop
            HermesNewChatRoute.WaitForSignIn -> Route.HoldForSignIn
            is HermesNewChatRoute.SwitchServer -> Route.SwitchServer(route.account)
            HermesNewChatRoute.Open -> Route.OpenHermesNewChat(destination)
        }

    /** Port of `BotDeepLinkRouter.resolve` (#554). */
    private fun routeBot(destination: BotDestination, context: LinkRoutingContext): Route =
        when (val outcome = BotDeepLinkRouter.resolve(destination, context)) {
            BotDeepLinkOutcome.Ignore -> Route.Drop
            is BotDeepLinkOutcome.WaitForSignIn -> Route.HoldForSignIn
            is BotDeepLinkOutcome.SwitchServer -> Route.SwitchServer(outcome.account)
            is BotDeepLinkOutcome.Open -> Route.OpenBot(outcome.destination)
        }

    /**
     * Port of the bare `session?id=` branch of `handleOpenURL` (#1217): once
     * a webui server moved, it opens only where it can't be another server's.
     */
    private fun routeBareSession(
        sessionId: String,
        context: LinkRoutingContext,
        hermesSessionRoute: (HermesSessionDestination, LinkRoutingContext) -> HermesSessionResolution,
    ): Route = when (val outcome = MovedWebuiRoute.bareSession(sessionId, context)) {
        is MovedWebuiRoute.Outcome.Webui -> when (val route = outcome.route) {
            WebuiEntryRoute.Stay -> Route.OpenWebuiSession(sessionId)
            is WebuiEntryRoute.SwitchServer -> Route.SwitchServer(route.account)
            WebuiEntryRoute.Unavailable -> Route.Drop
        }
        is MovedWebuiRoute.Outcome.Hermes -> routeHermesSession(outcome.destination, context, hermesSessionRoute, HOP_LIMIT)
        MovedWebuiRoute.Outcome.Refuse -> Route.Drop
    }
}
