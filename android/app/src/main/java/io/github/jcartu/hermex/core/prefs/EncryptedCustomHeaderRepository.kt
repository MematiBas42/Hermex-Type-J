package io.github.jcartu.hermex.core.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.jcartu.hermex.core.crypto.StringCipher
import io.github.jcartu.hermex.net.CustomHeader
import io.github.jcartu.hermex.net.CustomHeaderStore
import io.github.jcartu.hermex.net.sanitizedForStorage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

@Serializable
private data class CustomHeaderProfiles(
    val byOrigin: Map<String, List<CustomHeader>> = emptyMap(),
)

class CustomHeaderValidationException(val problems: List<String>) :
    IllegalArgumentException(problems.joinToString("\n"))

/**
 * Encrypted, per-origin custom request headers. Values may be access tokens;
 * plaintext never enters DataStore. The process-wide store contains only the
 * currently configured server's profile and updates synchronously on save or
 * server switch.
 */
class EncryptedCustomHeaderRepository(
    private val dataStore: DataStore<Preferences>,
    private val cipher: StringCipher,
    private val json: Json,
    private val runtimeStore: CustomHeaderStore,
) {
    private val encryptedKey = stringPreferencesKey("net.custom_headers_by_origin.v1")
    private val _headers = MutableStateFlow<List<CustomHeader>>(emptyList())
    val headers: StateFlow<List<CustomHeader>> = _headers.asStateFlow()
    private var profiles: CustomHeaderProfiles = CustomHeaderProfiles()
    private var activeOrigin: String? = null

    suspend fun load(activeBaseUrl: String) {
        val encrypted = dataStore.data.first()[encryptedKey]
        profiles = runCatching {
            if (encrypted.isNullOrBlank()) CustomHeaderProfiles()
            else json.decodeFromString(CustomHeaderProfiles.serializer(), cipher.decrypt(encrypted))
        }.getOrDefault(CustomHeaderProfiles())
        activate(activeBaseUrl)
    }

    fun activate(baseUrl: String) {
        activeOrigin = normalizedOrigin(baseUrl)
        val next = activeOrigin?.let { profiles.byOrigin[it] }.orEmpty().filter(CustomHeader::isApplicable)
        runtimeStore.replace(next)
        _headers.value = next
    }

    suspend fun replaceFor(baseUrl: String, candidate: List<CustomHeader>) {
        val origin = normalizedOrigin(baseUrl)
            ?: throw CustomHeaderValidationException(listOf("Configure a valid server URL before adding headers."))
        val problems = validate(candidate)
        if (problems.isNotEmpty()) throw CustomHeaderValidationException(problems)
        val sanitized = candidate.sanitizedForStorage().map {
            CustomHeader(it.sanitizedName, it.sanitizedValue)
        }
        val updated = profiles.byOrigin.toMutableMap().apply {
            if (sanitized.isEmpty()) remove(origin) else put(origin, sanitized)
        }
        profiles = CustomHeaderProfiles(updated)
        persist()
        if (activeOrigin == origin || activeOrigin == null) activate(baseUrl)
    }

    suspend fun clearFor(baseUrl: String) = replaceFor(baseUrl, emptyList())

    fun validate(candidate: List<CustomHeader>): List<String> {
        val problems = mutableListOf<String>()
        val names = mutableSetOf<String>()
        candidate.forEachIndexed { index, header ->
            val name = header.sanitizedName
            if (name.isEmpty() && header.value.isBlank()) return@forEachIndexed
            if (!header.isApplicable) problems += "Header ${index + 1} has an invalid name or value."
            val key = name.lowercase()
            if (key in PROTECTED_HEADERS) problems += "$name is managed by Type-J and cannot be overridden."
            if (name.isNotEmpty() && !names.add(key)) problems += "$name appears more than once."
        }
        return problems.distinct()
    }

    private suspend fun persist() {
        val plain = json.encodeToString(CustomHeaderProfiles.serializer(), profiles)
        val encrypted = cipher.encrypt(plain)
        dataStore.edit { it[encryptedKey] = encrypted }
    }

    companion object {
        private val PROTECTED_HEADERS = setOf(
            "host", "content-length", "connection", "transfer-encoding",
            "origin", "referer", "cookie", "accept", "content-type",
        )

        fun normalizedOrigin(raw: String): String? {
            val url = raw.trim().toHttpUrlOrNull() ?: return null
            return "${url.scheme}://${url.host}:${url.port}"
        }
    }
}
