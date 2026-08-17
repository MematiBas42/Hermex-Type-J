package io.github.jcartu.hermex.core.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Persists the server's auth cookies (the upstream HMAC session cookie with a
 * 24h TTL) so restarts keep the session alive, mirroring iOS HTTPCookieStorage.
 * Storage format per cookie: "<cookieUrl>\t<cookieString>"; the list joins with newlines.
 */
class CookieSerializer(private val dataStore: DataStore<Preferences>) {

    private val cookiesKey = stringPreferencesKey("net.cookies")

    val cookies: Flow<String> = dataStore.data.map { prefs -> prefs[cookiesKey].orEmpty() }

    suspend fun current(): String = cookies.first()

    suspend fun save(serialized: String) {
        dataStore.edit { it[cookiesKey] = serialized }
    }

    suspend fun clear() {
        dataStore.edit { it.remove(cookiesKey) }
    }
}
