package io.github.jcartu.hermex.core.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.jcartu.hermex.core.crypto.StringCipher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

data class ServerConfig(
    val baseUrl: String,
    val password: String,
) {
    val isConfigured: Boolean get() = baseUrl.isNotBlank() && password.isNotBlank()

    companion object {
        /** Normalizes a user-entered server URL: trims, adds https:// when no scheme present, strips trailing slashes. */
        fun normalizeBaseUrl(raw: String): String {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return trimmed
            val withScheme = when {
                trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true) -> trimmed
                else -> "https://$trimmed"
            }
            return withScheme.trimEnd('/')
        }
    }
}

/**
 * Stores the server base URL in the clear (it is not a secret) and the server
 * password encrypted under the Android Keystore. Mirrors iOS AuthManager +
 * KeychainStore.
 */
class ServerConfigRepository(
    private val dataStore: DataStore<Preferences>,
    private val cipher: StringCipher,
) {

    private val baseUrlKey = stringPreferencesKey("server.base_url")
    private val passwordKey = stringPreferencesKey("server.password_encrypted")

    val config: Flow<ServerConfig> = dataStore.data.map { prefs ->
        ServerConfig(
            baseUrl = prefs[baseUrlKey].orEmpty(),
            password = runCatching {
                prefs[passwordKey]?.let { cipher.decrypt(it) } ?: ""
            }.getOrDefault(""),
        )
    }

    suspend fun current(): ServerConfig = config.first()

    suspend fun save(baseUrl: String, password: String) {
        dataStore.edit { prefs ->
            prefs[baseUrlKey] = ServerConfig.normalizeBaseUrl(baseUrl)
            prefs[passwordKey] = cipher.encrypt(password)
        }
    }

    suspend fun clear() {
        dataStore.edit { prefs ->
            prefs.remove(baseUrlKey)
            prefs.remove(passwordKey)
        }
    }
}
