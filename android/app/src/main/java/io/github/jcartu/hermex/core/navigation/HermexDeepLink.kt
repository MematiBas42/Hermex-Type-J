package io.github.jcartu.hermex.core.navigation

import android.net.Uri

/**
 * Port of `HermesDeepLink` (`LiveActivities/HermesDeepLink.swift`, extended in
 * `Features/Bots/BotDeepLink.swift` and `Features/Share/SharedDraftStore.swift`):
 * the deep-link grammar — schemes, hosts, query items, builders and
 * predicates.
 *
 * The fork accepts two schemes: its own `hermex-type-j` and the legacy
 * `hermes-agent` scheme upstream builds used, so every link written for either
 * app generation keeps working. Builders always emit the fork scheme.
 *
 * Hosts (Swift unless noted):
 * - [SESSION_HOST] `session` — a Hermes server's stored session,
 *   `session?server=…&id=…[&profile=…][&subagent=1]` (#1176), and webui's
 *   legacy serverless `session?id=` / `session/<id>`, which names no server.
 * - [WEBUI_PUSH_HOST] `webui-push` — a server-owned webui destination, shared
 *   by notification and Live Activity taps (#1185, #1188).
 * - [BOT_HOST] `bot` — the one bot route (#554):
 *   `bot?server=…&connection=…&profile=…[&conversation=…]`.
 * - [NEW_CHAT_HOST] `new-chat` — open the New Chat composer (#337); fork also
 *   accepts the legacy alias `chat`.
 * - [NEW_CHAT_VOICE_HOST] `new-chat-voice` — New Chat and auto-start voice
 *   dictation (#338). Distinct from [NEW_CHAT_HOST] so the two never alias.
 * - [NEW_CHAT_IN_PROFILE_HOST] `new-chat-profile` — New Chat pinned to a
 *   Profile (#339); a Hermes server's Profile adds `server=` first (#1180).
 * - [SHARE_HOST] `share` — the share extension's open URL (#1181).
 * - `kanban`, `git` — fork-native hosts (pre-existing; kept working).
 */
object HermexDeepLink {
    const val SCHEME = "hermex-type-j"
    const val LEGACY_SCHEME = "hermes-agent"

    const val SESSION_HOST = "session"
    const val WEBUI_PUSH_HOST = "webui-push"
    const val BOT_HOST = "bot"
    const val NEW_CHAT_HOST = "new-chat"
    const val NEW_CHAT_VOICE_HOST = "new-chat-voice"
    const val NEW_CHAT_IN_PROFILE_HOST = "new-chat-profile"
    const val SHARE_HOST = "share"
    const val LEGACY_NEW_CHAT_HOST = "chat"
    const val KANBAN_HOST = "kanban"
    const val GIT_HOST = "git"

    /** `HermesDeepLink.serverQueryItem`: the configured server a link names. */
    const val SERVER_QUERY_ITEM = "server"

    /** Session/stored-key query item (`id`; legacy alias `session_id`). */
    const val ID_QUERY_ITEM = "id"
    const val LEGACY_ID_QUERY_ITEM = "session_id"

    /** `HermesDeepLink.profileQueryItem`: the Profile name. */
    const val PROFILE_QUERY_ITEM = "profile"

    /** `HermesDeepLink.subagentQueryItem`: the key is a subagent's (#1177). */
    const val SUBAGENT_QUERY_ITEM = "subagent"

    /** `WebuiPushDestination.onSourceQueryItem`: the link names a run on its server. */
    const val ON_SOURCE_QUERY_ITEM = "onSource"

    /** `AgentRunTapTarget`'s `activity` item: a Live Activity tap id. */
    const val ACTIVITY_QUERY_ITEM = "activity"

    /** `Features/Bots/BotDeepLink.swift`'s connection UUID item. */
    const val CONNECTION_QUERY_ITEM = "connection"

    /** `Features/Bots/BotDeepLink.swift`'s canonical bot conversation item. */
    const val CONVERSATION_QUERY_ITEM = "conversation"

    private val schemes = setOf(SCHEME, LEGACY_SCHEME)

    fun hasSupportedScheme(uri: Uri): Boolean = uri.scheme?.lowercase() in schemes

    /** Whether [uri] carries the query item at all, value or not. */
    fun hasQueryItem(uri: Uri, name: String): Boolean =
        runCatching { uri.queryParameterNames.contains(name) }.getOrDefault(false)

    /** Port of `HermesDeepLink.normalizedSessionID(_:)`: trimmed, non-empty. */
    fun normalizedSessionId(rawValue: String?): String? =
        rawValue?.trim()?.takeIf { it.isNotEmpty() }

    // MARK: - Builders (emit the fork scheme; every part rides as a query item)

    /**
     * Port of `HermesDeepLink.sessionURL(for:)` (#1176):
     * `hermex-type-j://session?server=…[&profile=…]&id=…[&subagent=1]`.
     * A null Profile is left out; `""` stays, naming the host's default.
     * Returns null for a blank key.
     */
    fun hermesSessionUri(destination: HermesSessionDestination): Uri? {
        val key = normalizedSessionId(destination.key) ?: return null
        val builder = baseBuilder(SESSION_HOST)
            .appendQueryParameter(SERVER_QUERY_ITEM, destination.serverId)
        destination.profile?.let { builder.appendQueryParameter(PROFILE_QUERY_ITEM, it.trim()) }
        builder.appendQueryParameter(ID_QUERY_ITEM, key)
        if (destination.opensParent) builder.appendQueryParameter(SUBAGENT_QUERY_ITEM, "1")
        return builder.build()
    }

    /**
     * Port of `HermesDeepLink.sessionURL(sessionID:)` — webui's legacy
     * serverless `hermex-type-j://session?id=…`. Null for a blank id.
     */
    fun legacySessionUri(sessionId: String): Uri? {
        val id = normalizedSessionId(sessionId) ?: return null
        return baseBuilder(SESSION_HOST).appendQueryParameter(ID_QUERY_ITEM, id).build()
    }

    /**
     * Port of `HermesDeepLink.webuiSessionURL(server:sessionID:)` plus
     * `WebuiPushDestination.url`'s `onSource` item (#1217) and
     * `AgentRunTapTarget.url`'s `activity` item: a Live Activity's tap opens
     * on its source like a push.
     */
    fun webuiPushUri(
        serverId: String,
        sessionId: String,
        opensOnSource: Boolean = false,
        activityId: String? = null,
    ): Uri? {
        val id = normalizedSessionId(sessionId) ?: return null
        val builder = baseBuilder(WEBUI_PUSH_HOST)
            .appendQueryParameter(SERVER_QUERY_ITEM, serverId)
            .appendQueryParameter(ID_QUERY_ITEM, id)
        if (opensOnSource) builder.appendQueryParameter(ON_SOURCE_QUERY_ITEM, "1")
        activityId?.takeIf { it.isNotEmpty() }?.let { builder.appendQueryParameter(ACTIVITY_QUERY_ITEM, it) }
        return builder.build()
    }

    /**
     * Port of `HermesDeepLink.botURL(for:)` (#554):
     * `hermex-type-j://bot?server=…&connection=…&profile=…[&conversation=…]`.
     * Null for a blank Profile.
     */
    fun botUri(destination: BotDestination): Uri? {
        val profile = destination.profile.trim()
        if (profile.isEmpty()) return null
        val builder = baseBuilder(BOT_HOST)
            .appendQueryParameter(SERVER_QUERY_ITEM, destination.serverId)
            .appendQueryParameter(CONNECTION_QUERY_ITEM, destination.connectionId)
            .appendQueryParameter(PROFILE_QUERY_ITEM, profile)
        destination.conversation?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { builder.appendQueryParameter(CONVERSATION_QUERY_ITEM, it) }
        return builder.build()
    }

    /** Port of `HermesDeepLink.newChatURL` (#337). */
    fun newChatUri(): Uri = baseBuilder(NEW_CHAT_HOST).build()

    /** Port of `HermesDeepLink.newChatVoiceURL` (#338). */
    fun newChatVoiceUri(): Uri = baseBuilder(NEW_CHAT_VOICE_HOST).build()

    /**
     * Port of `HermesDeepLink.newChatInProfileURL(profileName:server:)` (#339,
     * #1180): `hermex-type-j://new-chat-profile?[server=…]&profile=…` with
     * `server=` first for a Hermes server's Profile, which only opens there.
     * Null for a blank Profile name.
     */
    fun newChatInProfileUri(profileName: String, serverId: String? = null): Uri? {
        val trimmed = profileName.trim()
        if (trimmed.isEmpty()) return null
        val builder = baseBuilder(NEW_CHAT_IN_PROFILE_HOST)
        serverId?.let { builder.appendQueryParameter(SERVER_QUERY_ITEM, it) }
        builder.appendQueryParameter(PROFILE_QUERY_ITEM, trimmed)
        return builder.build()
    }

    /** Port of `HermesShareDraft.openURL` (`Features/Share/SharedDraftStore.swift`). */
    fun shareUri(): Uri = baseBuilder(SHARE_HOST).build()

    private fun baseBuilder(host: String): Uri.Builder =
        Uri.Builder().scheme(SCHEME).authority(host)

    // MARK: - Predicates / extractors

    /** Port of `HermesDeepLink.isNewChatURL(_:)`. The fork's legacy `chat` host aliases here. */
    fun isNewChatUrl(uri: Uri): Boolean {
        if (!hasSupportedScheme(uri)) return false
        val host = uri.host?.lowercase()
        return host == NEW_CHAT_HOST || host == LEGACY_NEW_CHAT_HOST
    }

    /** Port of `HermesDeepLink.isNewChatVoiceURL(_:)` — mutually exclusive with [isNewChatUrl]. */
    fun isNewChatVoiceUrl(uri: Uri): Boolean =
        hasSupportedScheme(uri) && uri.host?.lowercase() == NEW_CHAT_VOICE_HOST

    /** Port of `HermesDeepLink.isNewChatInProfileURL(_:)`. */
    fun isNewChatInProfileUrl(uri: Uri): Boolean =
        hasSupportedScheme(uri) && uri.host?.lowercase() == NEW_CHAT_IN_PROFILE_HOST

    /**
     * Port of `HermesDeepLink.isWebuiNewChatInProfileURL(_:)`: a "New Chat in
     * <Profile>" link with no `server=` item — the webui route a Shortcut
     * saved before #1180 keeps (#339, #971). One naming a server opens only
     * there, or nowhere.
     */
    fun isWebuiNewChatInProfileUrl(uri: Uri): Boolean =
        isNewChatInProfileUrl(uri) && !hasQueryItem(uri, SERVER_QUERY_ITEM)

    /**
     * Port of `HermesDeepLink.profileName(fromNewChatInProfile:)`: the Profile
     * name, or null when the link is a different kind or carries no
     * (non-blank) profile.
     */
    fun profileNameFromNewChatInProfile(uri: Uri): String? {
        if (!isNewChatInProfileUrl(uri)) return null
        return normalizedSessionId(uri.getQueryParameter(PROFILE_QUERY_ITEM))
    }

    /** Port of `HermesShareDraft.isShareOpenURL(_:)`. */
    fun isShareOpenUrl(uri: Uri): Boolean =
        hasSupportedScheme(uri) && uri.host?.lowercase() == SHARE_HOST

    /**
     * Port of `HermesDeepLink.sessionID(from:)`: the session a legacy
     * `session?id=` link (or `session/<id>`) names, opened on a webui server.
     * Null for any other link, including one carrying a `server` item: that
     * link opens only on the server it names (#1176), or not at all, never on
     * whichever webui server is reachable.
     */
    fun bareSessionId(uri: Uri): String? {
        if (!hasSupportedScheme(uri)) return null
        if (uri.host?.lowercase() != SESSION_HOST) return null
        if (hasQueryItem(uri, SERVER_QUERY_ITEM)) return null
        normalizedSessionId(uri.getQueryParameter(ID_QUERY_ITEM))?.let { return it }
        normalizedSessionId(uri.getQueryParameter(LEGACY_ID_QUERY_ITEM))?.let { return it }
        val pathId = uri.pathSegments?.firstOrNull { it != "/" }
        // The path form keeps the fork's pre-existing injection guard.
        return pathId?.let(::safePathSegment)
    }

    /**
     * Port of `AgentRunTapTarget.activityID(from:)` — the Live Activity tap id
     * carried by session, bot and webui-push links.
     */
    fun activityId(uri: Uri): String? {
        if (!hasSupportedScheme(uri)) return null
        val host = uri.host?.lowercase()
        if (host != SESSION_HOST && host != BOT_HOST && host != WEBUI_PUSH_HOST) return null
        return uri.getQueryParameter(ACTIVITY_QUERY_ITEM)?.takeIf { it.isNotEmpty() }
    }

    /**
     * The fork's pre-existing path-segment guard (`DeepLinkParser.safeSegment`):
     * rejects blank, overlong, slash- or control-carrying ids. Applied only to
     * the path form — query items ride percent-encoded like Swift.
     */
    internal fun safePathSegment(raw: String): String? {
        val value = raw.trim()
        if (value.isEmpty() || value.length > 256 || '/' in value || '\\' in value || value.any(Char::isISOControl)) {
            return null
        }
        return value
    }
}
