package io.github.jcartu.hermex.ui.feature.models

import io.github.jcartu.hermex.model.ModelCatalogGroup
import io.github.jcartu.hermex.model.ModelCatalogOption
import io.github.jcartu.hermex.model.ModelFavoriteKey
import io.github.jcartu.hermex.model.ModelFavoritesStore
import io.github.jcartu.hermex.model.ModelRecentsStore
import io.github.jcartu.hermex.model.favoriteKey
import io.github.jcartu.hermex.model.firstMatchingSelection

/**
 * Pure model-picker derivations ported from
 * `Features/Chat/ChatComposerSelectorSheets.swift` (`ComposerModelPickerSheet`)
 * and `Features/Chat/ComposerModelPickerSectionExpansionState.swift`.
 *
 * Everything here is side-effect free so [ModelPickerViewModel] state and the
 * unit tests share one implementation.
 */

/** Group id of the synthetic "Current Custom" section (Swift: `currentCustomGroupID`). */
const val CURRENT_CUSTOM_GROUP_ID: String = "current-custom-model"

/** Group id of the synthetic "Saved Custom" section (Swift: `savedCustomGroupID`). */
const val SAVED_CUSTOM_GROUP_ID: String = "saved-custom-models"

/** Group id of the pinned Favorites section (Android addition; iOS shows favorites in the composer menu). */
const val FAVORITES_GROUP_ID: String = "favorite-models"

/** Group id of the pinned Recent section (Android addition; iOS shows recents in the composer menu). */
const val RECENTS_GROUP_ID: String = "recent-models"

/**
 * Port of `ComposerModelPickerSectionExpansionState` — immutable Kotlin
 * variant of the Swift mutating struct. Outside a search, expansion is
 * opt-in per group; while a search query is active every group is expanded
 * unless explicitly collapsed, and changing the query resets those
 * collapses.
 */
data class ModelPickerSectionExpansionState(
    private val expandedGroupIDs: Set<String> = emptySet(),
    private val collapsedSearchGroupIDs: Set<String> = emptySet(),
    private val searchQuery: String = "",
) {
    fun updatingSearchText(searchText: String): ModelPickerSectionExpansionState {
        val query = searchText.trim()
        if (query == searchQuery) return this
        return copy(searchQuery = query, collapsedSearchGroupIDs = emptySet())
    }

    fun isExpanded(groupID: String): Boolean =
        if (searchQuery.isEmpty()) {
            expandedGroupIDs.contains(groupID)
        } else {
            !collapsedSearchGroupIDs.contains(groupID)
        }

    fun settingExpanded(isExpanded: Boolean, groupID: String): ModelPickerSectionExpansionState =
        if (searchQuery.isEmpty()) {
            if (isExpanded) {
                copy(expandedGroupIDs = expandedGroupIDs + groupID)
            } else {
                copy(expandedGroupIDs = expandedGroupIDs - groupID)
            }
        } else if (isExpanded) {
            copy(collapsedSearchGroupIDs = collapsedSearchGroupIDs - groupID)
        } else {
            copy(collapsedSearchGroupIDs = collapsedSearchGroupIDs + groupID)
        }
}

/** One provider choice for the custom-model provider menu (Swift: `ModelProviderChoice`). */
data class ModelProviderChoice(
    val id: String,
    val name: String,
)

/** A rendered picker section: a catalog/synthetic group plus its row affordances. */
data class ModelPickerDisplayGroup(
    val id: String,
    val name: String,
    val models: List<ModelCatalogOption>,
    /** Rows get a delete affordance only in the Saved Custom section (Swift parity). */
    val allowsDelete: Boolean = false,
)

/** Port of the Swift `matches(_:query:)` — case-insensitive match on name, id, or provider. */
internal fun optionMatches(option: ModelCatalogOption, query: String): Boolean =
    option.displayName.contains(query, ignoreCase = true) ||
        option.id.contains(query, ignoreCase = true) ||
        (option.providerID?.contains(query, ignoreCase = true) ?: false)

/** Port of the base-groups part of `filteredModelGroups` (without the custom groups prepended). */
internal fun filteredCatalogGroups(
    modelGroups: List<ModelCatalogGroup>,
    searchText: String,
): List<ModelCatalogGroup> {
    val query = searchText.trim()
    if (query.isEmpty()) return modelGroups

    return modelGroups.mapNotNull { group ->
        val filteredModels = group.models.filter { optionMatches(it, query) }
        if (filteredModels.isEmpty()) {
            null
        } else {
            ModelCatalogGroup(
                id = group.id,
                name = group.name,
                providerID = group.providerID,
                models = filteredModels,
            )
        }
    }
}

/** Port of the Swift `customOption` — both fields trimmed, provider lowercased, both required. */
internal fun customOption(customModelID: String, customProviderID: String): ModelCatalogOption? {
    val modelID = customModelID.trim()
    val providerID = customProviderID.trim().lowercase()
    if (modelID.isEmpty() || providerID.isEmpty()) return null
    return ModelCatalogOption(id = modelID, displayName = modelID, providerID = providerID)
}

/**
 * Port of the Swift `selectedCustomOption` — the currently selected model when
 * it exists in no catalog group (a custom selection carried in from the session).
 */
internal fun selectedCustomOption(
    modelGroups: List<ModelCatalogGroup>,
    selectedModelID: String?,
    selectedModelProviderID: String?,
    searchText: String,
): ModelCatalogOption? {
    if (selectedModelID.isNullOrEmpty()) return null
    val catalogOptions = modelGroups.flatMap { it.models }
    if (catalogOptions.firstMatchingSelection(selectedModelID, selectedModelProviderID) != null) {
        return null
    }

    val option = ModelCatalogOption(
        id = selectedModelID,
        displayName = selectedModelID,
        providerID = selectedModelProviderID,
    )
    val query = searchText.trim()
    if (query.isNotEmpty() && !optionMatches(option, query)) return null
    return option
}

/**
 * Port of the Swift `storedCustomOptions` — favorites and recents that do not
 * exist in the catalog (i.e. saved custom models), search-filtered, first
 * occurrence per favorite key wins.
 */
internal fun storedCustomOptions(
    modelGroups: List<ModelCatalogGroup>,
    favoriteKeys: List<ModelFavoriteKey>,
    recentKeys: List<ModelFavoriteKey>,
    searchText: String,
): List<ModelCatalogOption> {
    val catalogKeys = modelGroups.flatMap { it.models }.map { it.favoriteKey }.toHashSet()
    val query = searchText.trim()
    val seen = HashSet<ModelFavoriteKey>()
    val result = ArrayList<ModelCatalogOption>()

    fun append(option: ModelCatalogOption) {
        if (catalogKeys.contains(option.favoriteKey)) return
        if (!seen.add(option.favoriteKey)) return
        if (query.isNotEmpty() && !optionMatches(option, query)) return
        result.add(option)
    }

    ModelFavoritesStore.visibleFavoriteOptions(modelGroups, favoriteKeys).forEach(::append)
    ModelRecentsStore.visibleRecentOptions(modelGroups, recentKeys, favoriteKeys).forEach(::append)
    return result
}

/**
 * Port of the Swift `customModelGroups` — the synthetic "Current Custom" and
 * "Saved Custom" groups prepended to the catalog groups.
 */
internal fun customModelGroups(
    modelGroups: List<ModelCatalogGroup>,
    favoriteKeys: List<ModelFavoriteKey>,
    recentKeys: List<ModelFavoriteKey>,
    selectedModelID: String?,
    selectedModelProviderID: String?,
    searchText: String,
): List<ModelPickerDisplayGroup> {
    val groups = ArrayList<ModelPickerDisplayGroup>()
    val stored = storedCustomOptions(modelGroups, favoriteKeys, recentKeys, searchText)

    val selectedCustom = selectedCustomOption(
        modelGroups = modelGroups,
        selectedModelID = selectedModelID,
        selectedModelProviderID = selectedModelProviderID,
        searchText = searchText,
    )
    if (selectedCustom != null && stored.none { it.favoriteKey == selectedCustom.favoriteKey }) {
        groups.add(
            ModelPickerDisplayGroup(
                id = CURRENT_CUSTOM_GROUP_ID,
                name = "Current Custom",
                models = listOf(selectedCustom),
            ),
        )
    }

    if (stored.isNotEmpty()) {
        groups.add(
            ModelPickerDisplayGroup(
                id = SAVED_CUSTOM_GROUP_ID,
                name = "Saved Custom",
                models = stored,
                allowsDelete = true,
            ),
        )
    }

    return groups
}

/**
 * Port of the Swift `providerChoices` — provider ids collected from the catalog
 * groups (first group name wins as the label), with the currently selected
 * provider inserted first when it is not already present.
 */
internal fun providerChoices(
    modelGroups: List<ModelCatalogGroup>,
    selectedModelProviderID: String?,
): List<ModelProviderChoice> {
    val seen = HashSet<String>()
    val result = ArrayList<ModelProviderChoice>()

    for (group in modelGroups) {
        val providerID = group.providerID?.trim().orEmpty()
        if (providerID.isEmpty() || !seen.add(providerID.lowercase())) continue
        result.add(ModelProviderChoice(id = providerID, name = group.name))
    }

    val selected = selectedModelProviderID?.trim().orEmpty()
    if (selected.isNotEmpty() && seen.add(selected.lowercase())) {
        result.add(0, ModelProviderChoice(id = selected, name = selected))
    }

    return result
}

/**
 * Full section list for the picker, in render order:
 * Current Custom, Saved Custom, Favorites, Recent, then the (search-filtered)
 * provider groups. Favorites/Recent are pinned sections the iOS composer menu
 * shows (`ComposerModelMenu`); the sheet-level star toggles feed them.
 */
internal fun buildDisplayGroups(
    modelGroups: List<ModelCatalogGroup>,
    favoriteKeys: List<ModelFavoriteKey>,
    recentKeys: List<ModelFavoriteKey>,
    selectedModelID: String?,
    selectedModelProviderID: String?,
    searchText: String,
): List<ModelPickerDisplayGroup> {
    val query = searchText.trim()
    val groups = ArrayList<ModelPickerDisplayGroup>()

    groups += customModelGroups(
        modelGroups = modelGroups,
        favoriteKeys = favoriteKeys,
        recentKeys = recentKeys,
        selectedModelID = selectedModelID,
        selectedModelProviderID = selectedModelProviderID,
        searchText = searchText,
    )

    val favorites = ModelFavoritesStore.visibleFavoriteOptions(modelGroups, favoriteKeys)
        .filter { query.isEmpty() || optionMatches(it, query) }
    if (favorites.isNotEmpty()) {
        groups += ModelPickerDisplayGroup(id = FAVORITES_GROUP_ID, name = "Favorites", models = favorites)
    }

    val recents = ModelRecentsStore.visibleRecentOptions(modelGroups, recentKeys, favoriteKeys)
        .filter { query.isEmpty() || optionMatches(it, query) }
    if (recents.isNotEmpty()) {
        groups += ModelPickerDisplayGroup(id = RECENTS_GROUP_ID, name = "Recent", models = recents)
    }

    groups += filteredCatalogGroups(modelGroups, searchText).map { group ->
        ModelPickerDisplayGroup(id = group.id, name = group.name, models = group.models)
    }

    return groups
}
