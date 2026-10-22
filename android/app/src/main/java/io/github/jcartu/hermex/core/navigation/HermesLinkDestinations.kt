package io.github.jcartu.hermex.core.navigation

import android.net.Uri

/**
 * Typed destinations the deep-link grammar names (#1026, #1176, #554, #1180,
 * #1217). Identity only — a destination is parsed from a link and routed by
 * `core/navigation/DeepLinkRoutes.kt`; nothing here guesses a session, bot or
 * Profile from a display name.
 *
 * `serverId` is the configured server's `ServerAccount.id` — its normalized
 * base-URL string, exactly as the registry stores it. Links carry it as a
 * percent-encoded `server` query item (`LiveActivities/HermesDeepLink.swift`:
 * "Every part rides as a query item, so a Profile name or key is
 * percent-encoded rather than mangled into the host").
 */

/**
 * Port of `HermesSessionDestination` (`LiveActivities/HermesDeepLink.swift`,
 * #1176): a Hermes server's stored session as a link names it — the configured
 * server, the Profile, and the session's stored key.
 *
 * [profile] is tri-state, exactly Swift: `null` = the Profile is unknown and
 * the lookup reads every Profile; `""` = the link names the host's default
 * Profile; anything else names that Profile. [opensParent] marks a subagent's
 * link (#1177): the lookup opens the session that delegated to it.
 * [movedFromServerId] is the configured webui server a moved webui link named
 * (#1217) — never part of a link; set by `MovedWebuiRoute.session` so a lookup
 * that finds nothing can fall back to the webui server while it is configured.
 */
data class HermesSessionDestination(
    val serverId: String,
    val profile: String?,
    val key: String,
    val opensParent: Boolean = false,
    val movedFromServerId: String? = null,
) {
    companion object {
        /**
         * Port of `HermesSessionDestination.init?(url:)`
         * (`Features/Hermes/HermesSessionLookup.swift`): parses a
         * server-qualified session link, `session?server=…&id=…[&profile=…][&subagent=1]`.
         * Null for any other link, for one without a usable server or key, and
         * for webui's legacy `session?id=`, which names no server and keeps its
         * own route. A `profile` item without a value is `""`, the host's
         * default Profile.
         */
        fun parse(uri: Uri, normalizeServer: (String) -> String?): HermesSessionDestination? {
            if (!HermexDeepLink.hasSupportedScheme(uri)) return null
            if (uri.host?.lowercase() != HermexDeepLink.SESSION_HOST) return null
            val rawServer = uri.takeIf { HermexDeepLink.hasQueryItem(it, HermexDeepLink.SERVER_QUERY_ITEM) }
                ?.getQueryParameter(HermexDeepLink.SERVER_QUERY_ITEM)
                ?: return null
            val server = normalizeServer(rawServer) ?: return null
            val key = HermexDeepLink.normalizedSessionId(uri.getQueryParameter(HermexDeepLink.ID_QUERY_ITEM))
                ?: return null
            val profile = if (HermexDeepLink.hasQueryItem(uri, HermexDeepLink.PROFILE_QUERY_ITEM)) {
                (uri.getQueryParameter(HermexDeepLink.PROFILE_QUERY_ITEM) ?: "").trim()
            } else {
                null
            }
            val opensParent = uri.getQueryParameter(HermexDeepLink.SUBAGENT_QUERY_ITEM) == "1"
            return HermesSessionDestination(server, profile, key, opensParent)
        }
    }
}

/**
 * Port of `WebuiPushDestination` (`Push/PushNotificationRouter.swift`): a
 * server-owned webui conversation link, shared by notification and Live
 * Activity taps — `webui-push?server=…&id=…[&onSource=1][&activity=…]`.
 *
 * [opensOnSource] means the link names a run on [serverId] (a relay push, a
 * local run alert, a Live Activity's tap) or the Hermes list's fallback
 * (#1217), so it opens on [serverId] while it is configured, even after the
 * server moved to Hermes. Any other webui link on a moved server opens on its
 * destination first (`MovedWebuiRoute`).
 */
data class WebuiPushDestination(
    val serverId: String,
    val sessionId: String,
    val opensOnSource: Boolean = false,
) {
    companion object {
        /** Port of `WebuiPushDestination.init?(url:)`. */
        fun parse(uri: Uri, normalizeServer: (String) -> String?): WebuiPushDestination? {
            if (!HermexDeepLink.hasSupportedScheme(uri)) return null
            if (uri.host?.lowercase() != HermexDeepLink.WEBUI_PUSH_HOST) return null
            val rawServer = uri.getQueryParameter(HermexDeepLink.SERVER_QUERY_ITEM) ?: return null
            val server = normalizeServer(rawServer) ?: return null
            val id = uri.getQueryParameter(HermexDeepLink.ID_QUERY_ITEM)?.trim()?.takeIf { it.isNotEmpty() }
                ?: return null
            val onSource = uri.getQueryParameter(HermexDeepLink.ON_SOURCE_QUERY_ITEM) == "1" ||
                HermexDeepLink.activityId(uri) != null
            return WebuiPushDestination(server, id, onSource)
        }
    }
}

/**
 * Port of `HermesNewChatDestination` (`AppIntents/HermexAppIntents.swift`,
 * #1180): a new Hermes session a Shortcut asked for — New Chat or New Chat
 * with Voice on the active Hermes server, or New Chat in Profile on a Hermes
 * server's Profile.
 *
 * [profile] null takes the one the Sessions list's New Session uses. [id] is
 * fresh per request, so running the same Shortcut again opens another chat.
 */
data class HermesNewChatDestination(
    val serverId: String,
    val profile: String? = null,
    val autoStartsVoiceInput: Boolean = false,
    val id: String = java.util.UUID.randomUUID().toString(),
) {
    companion object {
        /**
         * Port of `HermesNewChatDestination.init?(url:)`: parses a Hermes
         * Profile's `new-chat-profile?server=…&profile=…`. Null for any other
         * link, including a bare `new-chat-profile?profile=…`, which keeps its
         * webui route (#971).
         */
        fun parse(uri: Uri, normalizeServer: (String) -> String?): HermesNewChatDestination? {
            if (!HermexDeepLink.isNewChatInProfileUrl(uri)) return null
            if (!HermexDeepLink.hasQueryItem(uri, HermexDeepLink.SERVER_QUERY_ITEM)) return null
            val raw = uri.getQueryParameter(HermexDeepLink.SERVER_QUERY_ITEM) ?: return null
            val server = normalizeServer(raw) ?: return null
            val profile = HermexDeepLink.profileNameFromNewChatInProfile(uri) ?: return null
            return HermesNewChatDestination(serverId = server, profile = profile)
        }
    }
}

/**
 * Port of `BotDestination` (`Features/Bots/BotDeepLink.swift`, #554): where an
 * external event wants the app to land inside Bot Mode — the configured server
 * (owns the Bot connection), the connection UUID (survives password and name
 * edits), the server's own Profile key for that bot, and optionally the bot's
 * durable canonical conversation root. A link missing any identity piece is
 * dropped rather than opening the wrong bot.
 */
data class BotDestination(
    val serverId: String,
    val connectionId: String,
    val profile: String,
    val conversation: String? = null,
) {
    companion object {
        /** Port of `HermesDeepLink.botDestination(from:)`. */
        fun parse(uri: Uri, normalizeServer: (String) -> String?): BotDestination? {
            if (!HermexDeepLink.hasSupportedScheme(uri)) return null
            if (uri.host?.lowercase() != HermexDeepLink.BOT_HOST) return null
            val rawServer = uri.getQueryParameter(HermexDeepLink.SERVER_QUERY_ITEM)?.trimmedOrNull()
                ?: return null
            // The registry stores normalized URLs, so normalize here too: a
            // link written with a trailing slash still matches its configured
            // server.
            val server = normalizeServer(rawServer) ?: return null
            val connection = uri.getQueryParameter(HermexDeepLink.CONNECTION_QUERY_ITEM)
                .canonicalUuidOrNull() ?: return null
            val profile = uri.getQueryParameter(HermexDeepLink.PROFILE_QUERY_ITEM).trimmedOrNull()
                ?: return null
            val conversation = uri.getQueryParameter(HermexDeepLink.CONVERSATION_QUERY_ITEM).trimmedOrNull()
            return BotDestination(server, connection, profile, conversation)
        }
    }
}

/** Swift `trimmed(_:)`: whitespace-trimmed, or null when empty. */
internal fun String?.trimmedOrNull(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

/** Canonical UUID string, or null when malformed (Swift `UUID.init(uuidString:)`). */
internal fun String?.canonicalUuidOrNull(): String? {
    val raw = trimmedOrNull() ?: return null
    return runCatching { java.util.UUID.fromString(raw).toString() }.getOrNull()
}
