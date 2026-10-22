package io.github.jcartu.hermex.core.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.jcartu.hermex.core.crypto.StringCipher
import kotlinx.coroutines.flow.first

/**
 * One encrypted webui password per configured server, keyed by the server's
 * registry id (its normalized URL). Port of the per-server `KeychainStore`
 * scoping (`Auth/KeychainStore.swift` `Key.scoped(_:)`): server A's password is
 * stored under A's id and can never resolve for server B. Values are encrypted
 * under the Android Keystore before they reach DataStore, like the legacy
 * single `server.password_encrypted` entry they replace.
 */
class ServerCredentialStore(
    private val dataStore: DataStore<Preferences>,
    private val cipher: StringCipher,
) {
    private fun key(serverId: String) = stringPreferencesKey("$KEY_PREFIX$serverId")

    /** The saved password for [serverId], or empty when none (or undecryptable). */
    suspend fun password(serverId: String): String {
        val stored = dataStore.data.first()[key(serverId)] ?: return ""
        return runCatching { cipher.decrypt(stored) }.getOrDefault("")
    }

    suspend fun save(serverId: String, password: String) {
        dataStore.edit { prefs -> prefs[key(serverId)] = cipher.encrypt(password) }
    }

    suspend fun remove(serverId: String) {
        dataStore.edit { prefs -> prefs.remove(key(serverId)) }
    }

    private companion object {
        const val KEY_PREFIX = "server.credential."
    }
}
