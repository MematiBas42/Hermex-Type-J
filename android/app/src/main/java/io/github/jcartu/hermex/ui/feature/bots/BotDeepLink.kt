package io.github.jcartu.hermex.ui.feature.bots

import android.net.Uri
import io.github.jcartu.hermex.model.hermes.BotConnection
import io.github.jcartu.hermex.model.hermes.BotProfile
import io.github.jcartu.hermex.model.hermes.BotRoomKey
import io.github.jcartu.hermex.ui.feature.onboarding.AuthManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * Port of `BotDestination` (`Features/Bots/BotDeepLink.swift`): where an external event — a
 * deep link, a push tap, a notification — wants the app to land inside Bot Mode. Identity only,
 * never a display name: the configured server owns the Bot connection, the connection UUID
 * survives password and name edits, and the Profile name is the server's own key for that bot.
 * [conversation] is the bot's durable canonical root when the sender knows it (#554).
 */
data class BotDestination(
    /** The configured server's id (its normalized URL string). */
    val server: String,
    val connectionID: String,
    val profile: String,
    val conversation: String? = null,
)

/**
 * The one bot route, `<scheme>://bot?server=…&connection=…&profile=…[&conversation=…]`, on the
 * fork's `hermex-type-j://` scheme and the legacy `hermes-agent://` one. Every part rides as a
 * query item, like `newChatInProfileURL`, so a Profile name with spaces or non-ASCII is
 * percent-encoded rather than mangled into the host.
 */
object BotDeepLink {
    const val HOST = "bot"
    private val schemes = setOf("hermex-type-j", "hermes-agent")
    private const val SERVER = "server"
    private const val CONNECTION = "connection"
    private const val PROFILE = "profile"
    private const val CONVERSATION = "conversation"

    /** `HermesDeepLink.botURL(for:)` — null for a destination without a Profile. */
    fun url(destination: BotDestination, scheme: String = "hermex-type-j"): Uri? {
        val profile = destination.profile.trim()
        if (profile.isEmpty()) return null
        val builder = Uri.Builder().scheme(scheme).authority(HOST)
            .appendQueryParameter(SERVER, destination.server)
            .appendQueryParameter(CONNECTION, destination.connectionID)
            .appendQueryParameter(PROFILE, profile)
        trimmed(destination.conversation)?.let { builder.appendQueryParameter(CONVERSATION, it) }
        return builder.build()
    }

    /**
     * `HermesDeepLink.botDestination(from:)` — null for any other kind of link and for one
     * missing the identity needed to route without guessing: a link is dropped rather than
     * opening the wrong bot. The server is normalized as the registry stores it, so a link
     * written with a trailing slash still matches its configured server.
     */
    fun parse(uri: Uri?): BotDestination? {
        uri ?: return null
        if (uri.scheme?.lowercase() !in schemes || uri.host?.lowercase() != HOST) return null
        fun value(name: String): String? = trimmed(runCatching { uri.getQueryParameter(name) }.getOrNull())
        val rawServer = value(SERVER) ?: return null
        val server = runCatching { AuthManager.normalizedServerUrl(rawServer) }.getOrNull() ?: return null
        val connection = value(CONNECTION)?.let { raw -> runCatching { UUID.fromString(raw) }.getOrNull()?.let { raw.uppercase() } } ?: return null
        val profile = value(PROFILE) ?: return null
        return BotDestination(server = server, connectionID = connection, profile = profile, conversation = value(CONVERSATION))
    }

    private fun trimmed(rawValue: String?): String? = rawValue?.trim()?.takeIf { it.isNotEmpty() }
}

/**
 * Port of `BotDeepLinkRouter.profile(for:connection:profiles:)` and `inboxCanAnswer`: a
 * destination resolves only against the connection it named, and only once the inbox has a
 * roster to answer with or has settled on having no connection.
 */
object BotDeepLinkRouter {
    /** The bot the destination names, or null when the connection was replaced or the Profile is gone. */
    fun profile(destination: BotDestination, connection: BotConnection?, profiles: List<BotProfile>): BotProfile? {
        if (connection == null || !connection.id.equals(destination.connectionID, ignoreCase = true)) return null
        return profiles.firstOrNull { it.id == destination.profile }
    }

    /**
     * True when the inbox can settle a held destination: it is live with a roster, or it has
     * settled without a connection, so "no Bot connection" is an answer rather than not-yet.
     */
    fun inboxCanAnswer(link: BotInbox.Link, hasConnection: Boolean, hasSettled: Boolean): Boolean =
        link == BotInbox.Link.LIVE || (!hasConnection && hasSettled)
}

/**
 * Port of `BotInboxSelection`: the inbox's presented chat and optional linked root. Picking a
 * room replaces the bot (including a linked conversation), and picking a bot replaces the room.
 * [pick] is new on every pick, the same bot or room again included, so a wide layout's detail
 * re-roots and pops what it pushed, as a Sessions row does.
 */
data class BotInboxSelection(
    val profile: BotProfile? = null,
    val room: BotRoomKey? = null,
    val conversation: String? = null,
    val pick: String = UUID.randomUUID().toString(),
) {
    fun selecting(profile: BotProfile?): BotInboxSelection =
        BotInboxSelection(profile = profile, room = if (profile != null) null else room, conversation = null)

    fun selectingRoom(room: BotRoomKey?): BotInboxSelection =
        BotInboxSelection(profile = if (room != null) null else profile, room = room, conversation = if (room != null) null else conversation)

    /** `open(_:connection:profiles:)` — resolves a link; a Profile no longer on the roster leaves nothing selected. */
    fun opening(destination: BotDestination, connection: BotConnection?, profiles: List<BotProfile>): BotInboxSelection {
        val profile = BotDeepLinkRouter.profile(destination, connection, profiles)
        return BotInboxSelection(profile = profile, room = null, conversation = if (profile == null) null else destination.conversation)
    }

    val isEmpty: Boolean get() = profile == null && room == null
}

/**
 * The hand-off for a bot link between whoever parsed it (the navigation layer) and the Hermes
 * home, which turns to Bots and passes [pending] to `BotsInbox` as its `pendingDestination`.
 * The inbox resolves it once its roster can answer and the home then calls [consume].
 */
object BotLinkMailbox {
    private val _pending = MutableStateFlow<BotDestination?>(null)
    val pending: StateFlow<BotDestination?> = _pending.asStateFlow()

    fun post(destination: BotDestination) {
        _pending.value = destination
    }

    fun consume() {
        _pending.value = null
    }
}
