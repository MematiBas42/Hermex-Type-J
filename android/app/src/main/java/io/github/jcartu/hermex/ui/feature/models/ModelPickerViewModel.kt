package io.github.jcartu.hermex.ui.feature.models

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.jcartu.hermex.model.ModelCatalogGroup
import io.github.jcartu.hermex.model.ModelCatalogOption
import io.github.jcartu.hermex.model.ModelFavoriteKey
import io.github.jcartu.hermex.model.favoriteKey
import io.github.jcartu.hermex.model.ModelFavoritesStore
import io.github.jcartu.hermex.model.ModelRecentsStore
import io.github.jcartu.hermex.net.HermesApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Immutable UI state of the model picker. All derived lists are computed from
 * the stored fields with the pure functions in `ModelPickerLogic.kt`, mirroring
 * the Swift computed properties on `ComposerModelPickerSheet`.
 */
data class ModelPickerUiState(
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val modelGroups: List<ModelCatalogGroup> = emptyList(),
    val favoriteKeys: List<ModelFavoriteKey> = emptyList(),
    val recentKeys: List<ModelFavoriteKey> = emptyList(),
    val searchText: String = "",
    val customModelID: String = "",
    val customProviderID: String = "",
    val selectedModelID: String? = null,
    val selectedModelProviderID: String? = null,
    val expansion: ModelPickerSectionExpansionState = ModelPickerSectionExpansionState(),
) {
    /** Sections in render order (custom, favorites, recents, provider groups), search-filtered. */
    val displayGroups: List<ModelPickerDisplayGroup>
        get() = buildDisplayGroups(
            modelGroups = modelGroups,
            favoriteKeys = favoriteKeys,
            recentKeys = recentKeys,
            selectedModelID = selectedModelID,
            selectedModelProviderID = selectedModelProviderID,
            searchText = searchText,
        )

    /** The custom entry as an option, when both id and provider are filled in. */
    val customOption: ModelCatalogOption?
        get() = customOption(customModelID, customProviderID)

    val isCustomOptionFavorite: Boolean
        get() = customOption?.let { isFavorite(it) } ?: false

    /** Provider ids offered by the custom-entry provider menu. */
    val providerChoices: List<ModelProviderChoice>
        get() = providerChoices(modelGroups, selectedModelProviderID)

    fun isFavorite(option: ModelCatalogOption): Boolean =
        favoriteKeys.contains(option.favoriteKey)

    fun isSelected(option: ModelCatalogOption): Boolean =
        option.matchesSelection(modelID = selectedModelID, providerID = selectedModelProviderID)

    fun isExpanded(groupID: String): Boolean = expansion.isExpanded(groupID)
}

/**
 * ViewModel behind [ModelPickerSheet]. Port of the state logic in
 * `ComposerModelPickerSheet` (ChatComposerSelectorSheets.swift) plus the
 * favorites/recents bookkeeping `ChatComposerView` does around it:
 * `selectModel` records a recent, the star toggles [ModelFavoritesStore], and
 * deleting a saved custom removes it from both stores.
 *
 * @param writeScope scope used for persistence writes that must survive the
 *   picker being dismissed mid-write (pass `AppContainer.appScope`); falls
 *   back to [viewModelScope] when null.
 */
class ModelPickerViewModel(
    private val api: HermesApi,
    private val favoritesStore: ModelFavoritesStore,
    private val recentsStore: ModelRecentsStore,
    initialModelID: String? = null,
    initialProviderID: String? = null,
    writeScope: CoroutineScope? = null,
    initialGroups: List<ModelCatalogGroup>? = null,
) : ViewModel() {

    private val persistScope: CoroutineScope get() = externalWriteScope ?: viewModelScope
    private val externalWriteScope: CoroutineScope? = writeScope

    private val _uiState = MutableStateFlow(
        ModelPickerUiState(
            isLoading = initialGroups.isNullOrEmpty(),
            modelGroups = initialGroups.orEmpty(),
            selectedModelID = initialModelID?.trim()?.takeIf { it.isNotEmpty() },
            selectedModelProviderID = initialProviderID?.trim()?.takeIf { it.isNotEmpty() },
            // The pinned sections start open; provider groups start collapsed
            // like the iOS disclosure groups.
            expansion = ModelPickerSectionExpansionState()
                .settingExpanded(true, CURRENT_CUSTOM_GROUP_ID)
                .settingExpanded(true, SAVED_CUSTOM_GROUP_ID)
                .settingExpanded(true, FAVORITES_GROUP_ID)
                .settingExpanded(true, RECENTS_GROUP_ID),
        ),
    )
    val uiState: StateFlow<ModelPickerUiState> = _uiState.asStateFlow()

    private var loadJob: Job? = null

    init {
        viewModelScope.launch {
            favoritesStore.favoriteKeys.collect { keys ->
                _uiState.update { it.copy(favoriteKeys = keys) }
            }
        }
        viewModelScope.launch {
            recentsStore.recentKeys.collect { keys ->
                _uiState.update { it.copy(recentKeys = keys) }
            }
        }
        if (initialGroups.isNullOrEmpty()) {
            loadCatalog()
        }
    }

    /** Loads `GET /api/models` and seeds the selection from `default_model` when none was passed in. */
    fun loadCatalog() {
        if (loadJob?.isActive == true) return
        loadJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            try {
                val response = api.models()
                _uiState.update { state ->
                    val selected = state.selectedModelID ?: response.defaultModel?.trim()?.takeIf { it.isNotEmpty() }
                    state.copy(
                        isLoading = false,
                        modelGroups = response.catalogGroups,
                        selectedModelID = selected,
                    )
                }
                initializeCustomProviderIfNeeded()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _uiState.update {
                    it.copy(isLoading = false, errorMessage = error.message ?: "Could not load models")
                }
            }
        }
    }

    fun updateSearchText(text: String) {
        _uiState.update {
            it.copy(searchText = text, expansion = it.expansion.updatingSearchText(text))
        }
    }

    fun updateCustomModelID(text: String) {
        _uiState.update { it.copy(customModelID = text) }
    }

    fun updateCustomProviderID(text: String) {
        _uiState.update { it.copy(customProviderID = text) }
    }

    fun setGroupExpanded(groupID: String, expanded: Boolean) {
        _uiState.update { it.copy(expansion = it.expansion.settingExpanded(expanded, groupID)) }
    }

    /** Star toggle — flips membership in [ModelFavoritesStore]; state follows via the flow. */
    fun toggleFavorite(option: ModelCatalogOption) {
        persistScope.launch { favoritesStore.toggleFavorite(option) }
    }

    /** Saved-custom delete — removes the model from favorites and recents (iOS `onDeleteSavedCustom`). */
    fun deleteSavedCustom(option: ModelCatalogOption) {
        persistScope.launch {
            favoritesStore.removeFavorite(option)
            recentsStore.removeRecent(option)
        }
    }

    /**
     * Records the pick as a recent (iOS `ChatComposerView.selectModel`) and
     * updates the local selection so the checkmark tracks it. The caller owns
     * what "selecting" means (composer override, default-model save, ...).
     */
    fun select(option: ModelCatalogOption) {
        _uiState.update {
            it.copy(selectedModelID = option.id, selectedModelProviderID = option.providerID)
        }
        persistScope.launch { recentsStore.recordRecent(option) }
    }

    /** Port of the Swift `initializeCustomProviderIfNeeded` (run after the catalog lands). */
    private fun initializeCustomProviderIfNeeded() {
        _uiState.update { state ->
            if (state.customProviderID.isNotBlank()) return@update state
            val fallback = state.selectedModelProviderID
                ?: state.providerChoices.firstOrNull()?.id
                ?: ""
            state.copy(customProviderID = fallback)
        }
    }
}
