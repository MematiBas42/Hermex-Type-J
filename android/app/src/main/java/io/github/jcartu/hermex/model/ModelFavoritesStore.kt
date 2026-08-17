package io.github.jcartu.hermex.model

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Port of `ModelFavoriteKey` from `ModelFavoritesStore.swift`.
 *
 * Persisted with Swift's synthesized `Codable` via a plain `JSONDecoder`
 * (no key conversion), so the stored JSON keys are the literal property
 * names `modelID` / `providerID` — Kotlin's default serial names match.
 */
@Serializable
data class ModelFavoriteKey(
    val modelID: String,
    val providerID: String? = null,
)

/**
 * Json used to persist `[ModelFavoriteKey]` blobs. Mirrors AppContainer's
 * tolerant settings; `encodeDefaults = false` + `explicitNulls = false` omits
 * a `null` providerID exactly like Swift's synthesized `encodeIfPresent`.
 */
private val favoritesJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
    explicitNulls = false
    encodeDefaults = false
}

private val favoriteKeyListSerializer = ListSerializer(ModelFavoriteKey.serializer())

/**
 * Mirrors the Swift read path: `defaults.data(forKey:)` +
 * `try? JSONDecoder().decode([ModelFavoriteKey].self, from: data)` — any
 * missing blob or decode failure yields an empty list.
 */
private fun decodeFavoriteKeys(raw: String?): List<ModelFavoriteKey> {
    if (raw == null) return emptyList()
    return runCatching { favoritesJson.decodeFromString(favoriteKeyListSerializer, raw) }
        .getOrNull() ?: emptyList()
}

/**
 * Mirrors `try? JSONEncoder().encode(deduplicated)` — returns null on encode
 * failure so callers can skip the write like Swift's `guard let data`.
 */
private fun encodeFavoriteKeys(keys: List<ModelFavoriteKey>): String? =
    runCatching { favoritesJson.encodeToString(favoriteKeyListSerializer, keys) }.getOrNull()

/**
 * Port of `ModelFavoritesStore` from `ModelFavoritesStore.swift`.
 *
 * iOS backs this with `UserDefaults` under the storage key
 * `"hermes.mobile.favoriteModels"`; Android uses a Preferences DataStore with
 * the byte-identical preference name. Reads are exposed as a [Flow] snapshot
 * of the stored keys plus a suspend snapshot accessor; mutations are suspend
 * functions that, like the Swift `@discardableResult` methods, return the
 * updated key list.
 */
class ModelFavoritesStore(
    private val dataStore: DataStore<Preferences>,
    storageKey: String = "hermes.mobile.favoriteModels",
) {
    private val preferenceKey = stringPreferencesKey(storageKey)

    /** Port of the Swift computed `var favoriteKeys`, as an observable stream. */
    val favoriteKeys: Flow<List<ModelFavoriteKey>> =
        dataStore.data.map { preferences -> deduplicated(decodeFavoriteKeys(preferences[preferenceKey])) }

    /** One-shot read of `favoriteKeys` (the Swift computed-property access pattern). */
    suspend fun currentFavoriteKeys(): List<ModelFavoriteKey> = favoriteKeys.first()

    /** Port of `isFavorite(_:)`. */
    suspend fun isFavorite(option: ModelCatalogOption): Boolean =
        currentFavoriteKeys().contains(option.favoriteKey)

    /** Port of `toggleFavorite(for:)` — atomic read-modify-write; returns the updated keys. */
    suspend fun toggleFavorite(option: ModelCatalogOption): List<ModelFavoriteKey> {
        val key = option.favoriteKey
        var result: List<ModelFavoriteKey> = emptyList()
        dataStore.edit { preferences ->
            val keys = deduplicated(decodeFavoriteKeys(preferences[preferenceKey])).toMutableList()
            val index = keys.indexOf(key)
            if (index >= 0) {
                keys.removeAt(index)
            } else {
                keys.add(key)
            }
            result = deduplicated(keys)
            encodeFavoriteKeys(result)?.let { preferences[preferenceKey] = it }
        }
        return result
    }

    /** Port of `removeFavorite(for:)` — returns the updated keys. */
    suspend fun removeFavorite(option: ModelCatalogOption): List<ModelFavoriteKey> {
        val favoriteKey = option.favoriteKey
        var result: List<ModelFavoriteKey> = emptyList()
        dataStore.edit { preferences ->
            val keys = deduplicated(decodeFavoriteKeys(preferences[preferenceKey]))
                .filter { it != favoriteKey }
            result = deduplicated(keys)
            encodeFavoriteKeys(result)?.let { preferences[preferenceKey] = it }
        }
        return result
    }

    /** Port of `save(_:)` — deduplicates, then writes; a failed encode skips the write. */
    suspend fun save(keys: List<ModelFavoriteKey>) {
        val encoded = encodeFavoriteKeys(deduplicated(keys)) ?: return
        dataStore.edit { preferences -> preferences[preferenceKey] = encoded }
    }

    companion object {
        /** Port of the static `visibleFavoriteOptions(in:favoriteKeys:)`. */
        fun visibleFavoriteOptions(
            groups: List<ModelCatalogGroup>,
            favoriteKeys: List<ModelFavoriteKey>,
        ): List<ModelCatalogOption> {
            val optionsByKey = groups.catalogOptionsByFavoriteKey()

            return deduplicated(favoriteKeys).map { key ->
                optionsByKey[key] ?: key.fallbackOption
            }
        }

        /** Port of the private static `deduplicated(_:)` — first occurrence wins. */
        private fun deduplicated(keys: List<ModelFavoriteKey>): List<ModelFavoriteKey> {
            val seen = HashSet<ModelFavoriteKey>()
            val result = ArrayList<ModelFavoriteKey>()

            for (key in keys) {
                if (seen.add(key)) {
                    result.add(key)
                }
            }

            return result
        }
    }
}

/**
 * Port of `ModelRecentsStore` from `ModelFavoritesStore.swift`.
 *
 * iOS storage key: `"hermes.mobile.recentModels"`, capped at [limit] entries
 * (5 on iOS). Most-recent-first ordering: `recordRecent` prepends the key,
 * then dedups + caps.
 */
class ModelRecentsStore(
    private val dataStore: DataStore<Preferences>,
    storageKey: String = "hermes.mobile.recentModels",
    private val limit: Int = 5,
) {
    private val preferenceKey = stringPreferencesKey(storageKey)

    /** Port of the Swift computed `var recentKeys`, as an observable stream. */
    val recentKeys: Flow<List<ModelFavoriteKey>> =
        dataStore.data.map { preferences ->
            limitedDeduplicated(decodeFavoriteKeys(preferences[preferenceKey]), limit)
        }

    /** One-shot read of `recentKeys` (the Swift computed-property access pattern). */
    suspend fun currentRecentKeys(): List<ModelFavoriteKey> = recentKeys.first()

    /** Port of `recordRecent(_:)` — prepends, dedups, caps; returns the updated keys. */
    suspend fun recordRecent(option: ModelCatalogOption): List<ModelFavoriteKey> {
        val key = option.favoriteKey
        var result: List<ModelFavoriteKey> = emptyList()
        dataStore.edit { preferences ->
            val existing = limitedDeduplicated(decodeFavoriteKeys(preferences[preferenceKey]), limit)
            result = limitedDeduplicated(listOf(key) + existing, limit)
            encodeFavoriteKeys(result)?.let { preferences[preferenceKey] = it }
        }
        return result
    }

    /** Port of `removeRecent(for:)` — returns the updated keys. */
    suspend fun removeRecent(option: ModelCatalogOption): List<ModelFavoriteKey> {
        val favoriteKey = option.favoriteKey
        var result: List<ModelFavoriteKey> = emptyList()
        dataStore.edit { preferences ->
            val keys = limitedDeduplicated(decodeFavoriteKeys(preferences[preferenceKey]), limit)
                .filter { it != favoriteKey }
            result = limitedDeduplicated(keys, limit)
            encodeFavoriteKeys(result)?.let { preferences[preferenceKey] = it }
        }
        return result
    }

    /** Port of `save(_:)` — dedups + caps, then writes; a failed encode skips the write. */
    suspend fun save(keys: List<ModelFavoriteKey>) {
        val encoded = encodeFavoriteKeys(limitedDeduplicated(keys, limit)) ?: return
        dataStore.edit { preferences -> preferences[preferenceKey] = encoded }
    }

    companion object {
        /** Port of the static `visibleRecentOptions(in:recentKeys:favoriteKeys:)`. */
        fun visibleRecentOptions(
            groups: List<ModelCatalogGroup>,
            recentKeys: List<ModelFavoriteKey>,
            favoriteKeys: List<ModelFavoriteKey>,
        ): List<ModelCatalogOption> {
            val favoriteKeySet = favoriteKeys.toHashSet()
            val optionsByKey = groups.catalogOptionsByFavoriteKey()

            return limitedDeduplicated(recentKeys, recentKeys.size)
                .filter { !favoriteKeySet.contains(it) }
                .map { key -> optionsByKey[key] ?: key.fallbackOption }
        }

        /**
         * Port of the private static `limitedDeduplicated(_:limit:)` — first
         * occurrence wins; stops once [limit] entries were collected.
         */
        private fun limitedDeduplicated(keys: List<ModelFavoriteKey>, limit: Int): List<ModelFavoriteKey> {
            val seen = HashSet<ModelFavoriteKey>()
            val result = ArrayList<ModelFavoriteKey>()

            for (key in keys) {
                if (seen.add(key)) {
                    result.add(key)
                    if (result.size == limit) {
                        break
                    }
                }
            }

            return result
        }
    }
}

/**
 * Port of the Swift `Sequence where Element == ModelCatalogGroup` extension:
 * first option seen for a given favorite key wins.
 */
fun Iterable<ModelCatalogGroup>.catalogOptionsByFavoriteKey(): Map<ModelFavoriteKey, ModelCatalogOption> {
    val optionsByKey = LinkedHashMap<ModelFavoriteKey, ModelCatalogOption>()
    for (option in flatMap { it.models }) {
        if (optionsByKey[option.favoriteKey] == null) {
            optionsByKey[option.favoriteKey] = option
        }
    }
    return optionsByKey
}

/** Port of the `ModelCatalogOption.favoriteKey` extension. */
val ModelCatalogOption.favoriteKey: ModelFavoriteKey
    get() = ModelFavoriteKey(modelID = id, providerID = providerID)

/** Port of the private `ModelFavoriteKey.fallbackOption` extension. */
private val ModelFavoriteKey.fallbackOption: ModelCatalogOption
    get() = ModelCatalogOption(id = modelID, displayName = modelID, providerID = providerID)
