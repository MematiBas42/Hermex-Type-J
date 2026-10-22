package io.github.jcartu.hermex.core.navigation

import android.net.Uri
import androidx.core.net.toUri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

/**
 * One parsed deep link. Port of the destinations `ContentView.handleOpenURL`
 * routes (`HermesMobile/ContentView.swift`), plus the fork's pre-existing
 * Kanban/Git hosts. Every form the grammar accepts — old and new — lands in
 * exactly one case:
 *
 * - [WebuiPush]: `webui-push?server=…&id=…[&onSource=1][&activity=…]` — a
 *   server-owned webui destination (notification / Live Activity taps, #1185).
 * - [NewChatVoice]: `new-chat-voice` (#338).
 * - [HermesNewChat]: `new-chat-profile?server=…&profile=…` — a Hermes
 *   server's Profile (#1180); one naming a server that doesn't parse opens
 *   nothing, never the webui route.
 * - [WebuiNewChatInProfile]: bare `new-chat-profile?profile=…` (#339, #971).
 * - [NewChat]: `new-chat`, and the fork's legacy `chat` alias (#337).
 * - [Bot]: `bot?server=…&connection=…&profile=…[&conversation=…]` (#554).
 * - [HermesSession]: `session?server=…&id=…[&profile=…][&subagent=1]` (#1176).
 * - [Session]: serverless `session?id=` / `session/<id>` (legacy webui).
 * - [Share]: the share extension's open URL (#1181).
 */
sealed interface AppDeepLink {
    data object NewChat : AppDeepLink
    data object NewChatVoice : AppDeepLink
    data class WebuiNewChatInProfile(val profileName: String?) : AppDeepLink
    data class Session(val sessionId: String) : AppDeepLink
    data class HermesSession(val destination: HermesSessionDestination) : AppDeepLink
    data class WebuiPush(val destination: WebuiPushDestination) : AppDeepLink
    data class HermesNewChat(val destination: HermesNewChatDestination) : AppDeepLink
    data class Bot(val destination: BotDestination) : AppDeepLink
    data object Share : AppDeepLink
    data class Kanban(val boardSlug: String?, val cardId: String?) : AppDeepLink
    data class Git(val sessionId: String?) : AppDeepLink
}

/**
 * The deep-link parser: accepts the fork `hermex-type-j` and legacy
 * `hermes-agent` schemes and routes every host the grammar defines. Identity
 * fields ride as percent-encoded query items exactly like Swift's
 * `URLComponents`; the fork's path forms keep their injection guard.
 *
 * [normalizeServer] reduces a link's `server` item to the registry's stored
 * id (Swift passes `AuthManager.normalizedServerURL(from:)`), so a link
 * written with a trailing slash still matches its configured server. The
 * default keeps the item verbatim apart from trimming — production wires the
 * real normalizer through [DeepLinkRepository].
 */
object DeepLinkParser {

    fun parse(
        uri: Uri?,
        normalizeServer: (String) -> String? = { it.trim().ifEmpty { null } },
    ): AppDeepLink? {
        uri ?: return null
        if (!HermexDeepLink.hasSupportedScheme(uri)) return null
        return when (uri.host?.lowercase()) {
            HermexDeepLink.WEBUI_PUSH_HOST ->
                WebuiPushDestination.parse(uri, normalizeServer)?.let(AppDeepLink::WebuiPush)
            HermexDeepLink.NEW_CHAT_VOICE_HOST -> AppDeepLink.NewChatVoice
            HermexDeepLink.NEW_CHAT_IN_PROFILE_HOST ->
                if (HermexDeepLink.hasQueryItem(uri, HermexDeepLink.SERVER_QUERY_ITEM)) {
                    // A Hermes server's Profile (#1180); one naming a server
                    // that didn't parse opens nothing (Swift's fall-through).
                    HermesNewChatDestination.parse(uri, normalizeServer)?.let(AppDeepLink::HermesNewChat)
                } else {
                    AppDeepLink.WebuiNewChatInProfile(HermexDeepLink.profileNameFromNewChatInProfile(uri))
                }
            HermexDeepLink.NEW_CHAT_HOST, HermexDeepLink.LEGACY_NEW_CHAT_HOST -> AppDeepLink.NewChat
            HermexDeepLink.BOT_HOST ->
                BotDestination.parse(uri, normalizeServer)?.let(AppDeepLink::Bot)
            HermexDeepLink.SESSION_HOST ->
                if (HermexDeepLink.hasQueryItem(uri, HermexDeepLink.SERVER_QUERY_ITEM)) {
                    HermesSessionDestination.parse(uri, normalizeServer)?.let(AppDeepLink::HermesSession)
                } else {
                    HermexDeepLink.bareSessionId(uri)?.let(AppDeepLink::Session)
                }
            HermexDeepLink.SHARE_HOST -> AppDeepLink.Share
            HermexDeepLink.KANBAN_HOST -> AppDeepLink.Kanban(
                pathSegment(uri, 0),
                pathSegment(uri, 1),
            )
            HermexDeepLink.GIT_HOST -> AppDeepLink.Git(pathSegment(uri, 0))
            else -> null
        }
    }

    private fun pathSegment(uri: Uri, index: Int): String? =
        uri.pathSegments.mapNotNull(HermexDeepLink::safePathSegment).getOrNull(index)
}

/**
 * Persists the raw URI until the authenticated shell has routed it (Swift's
 * `PendingLinks` held across sign-in and server switching): a link that
 * arrives signed-out, or while its server is inactive, stays queued until
 * the shell can route it — the app never drops it just because onboarding or
 * a sign-in form is on screen.
 */
class DeepLinkRepository(
    private val dataStore: DataStore<Preferences>,
    private val normalizeServer: (String) -> String? = { it.trim().ifEmpty { null } },
) {
    private val pendingKey = stringPreferencesKey("navigation.pending_deep_link")
    private val _pending = MutableStateFlow<Pair<String, AppDeepLink>?>(null)
    val pending: StateFlow<Pair<String, AppDeepLink>?> = _pending.asStateFlow()

    suspend fun load() {
        val raw = dataStore.data.first()[pendingKey]
        _pending.value = raw?.let {
            DeepLinkParser.parse(it.toUri(), normalizeServer)?.let { link -> raw to link }
        }
    }

    suspend fun submit(uri: Uri?): AppDeepLink? {
        val parsed = DeepLinkParser.parse(uri, normalizeServer) ?: return null
        val raw = uri.toString()
        dataStore.edit { it[pendingKey] = raw }
        _pending.value = raw to parsed
        return parsed
    }

    suspend fun acknowledge(raw: String) {
        if (_pending.value?.first != raw) return
        dataStore.edit { it.remove(pendingKey) }
        _pending.value = null
    }
}
