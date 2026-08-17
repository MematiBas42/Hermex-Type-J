package io.github.jcartu.hermex.core.navigation

import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.core.net.toUri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

sealed interface AppDeepLink {
    data object NewChat : AppDeepLink
    data class Session(val sessionId: String) : AppDeepLink
    data class Kanban(val boardSlug: String?, val cardId: String?) : AppDeepLink
    data class Git(val sessionId: String?) : AppDeepLink
}

object DeepLinkParser {
    private val schemes = setOf("hermex-type-j", "hermes-agent")

    fun parse(uri: Uri?): AppDeepLink? {
        uri ?: return null
        if (uri.scheme?.lowercase() !in schemes) return null
        val host = uri.host?.lowercase() ?: return null
        val segments = uri.pathSegments.mapNotNull(::safeSegment)
        return when (host) {
            "new-chat", "chat" -> AppDeepLink.NewChat
            "session" -> segments.firstOrNull()?.let(AppDeepLink::Session)
            "kanban" -> AppDeepLink.Kanban(segments.getOrNull(0), segments.getOrNull(1))
            "git" -> AppDeepLink.Git(segments.firstOrNull())
            else -> null
        }
    }

    private fun safeSegment(raw: String): String? {
        val value = raw.trim()
        if (value.isEmpty() || value.length > 256 || '/' in value || '\\' in value || value.any(Char::isISOControl)) return null
        return value
    }
}

/** Persists the raw URI until the authenticated shell has copied it into SavedStateHandle. */
class DeepLinkRepository(private val dataStore: DataStore<Preferences>) {
    private val pendingKey = stringPreferencesKey("navigation.pending_deep_link")
    private val _pending = MutableStateFlow<Pair<String, AppDeepLink>?>(null)
    val pending: StateFlow<Pair<String, AppDeepLink>?> = _pending.asStateFlow()

    suspend fun load() {
        val raw = dataStore.data.first()[pendingKey]
        _pending.value = raw?.let { DeepLinkParser.parse(it.toUri())?.let { link -> raw to link } }
    }

    suspend fun submit(uri: Uri?): AppDeepLink? {
        val parsed = DeepLinkParser.parse(uri) ?: return null
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
