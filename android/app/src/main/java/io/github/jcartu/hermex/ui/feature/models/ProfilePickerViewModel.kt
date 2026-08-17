package io.github.jcartu.hermex.ui.feature.models

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.jcartu.hermex.model.ProfileSummary
import io.github.jcartu.hermex.model.ProfilesResponse
import io.github.jcartu.hermex.net.HermesApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The confirmed result of a profile switch — port of `DefaultProfileSelection`
 * (DefaultProfilePickerView.swift). [defaultModel] is the switched profile's
 * default model when the server reported one.
 */
data class DefaultProfileSelection(
    val name: String,
    val displayName: String,
    val defaultModel: String?,
)

/** UI state of [ProfilePickerViewModel]. */
data class ProfilePickerUiState(
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val profiles: List<ProfileSummary> = emptyList(),
    val activeProfileName: String? = null,
    val selectedProfileName: String? = null,
    val searchText: String = "",
    val errorMessage: String? = null,
    val saveError: String? = null,
    val isSingleProfileMode: Boolean = false,
    /** Set once a switch succeeds; the sheet observes it, reports, and dismisses. */
    val completedSelection: DefaultProfileSelection? = null,
) {
    /** Port of the Swift `filteredProfiles` — name/model/provider substring match. */
    val filteredProfiles: List<ProfileSummary>
        get() {
            val query = searchText.trim().lowercase()
            if (query.isEmpty()) return profiles
            return profiles.filter { profile ->
                profile.displayName.lowercase().contains(query) ||
                    (profile.normalizedName?.lowercase()?.contains(query) ?: false) ||
                    (profile.model?.lowercase()?.contains(query) ?: false) ||
                    (profile.provider?.lowercase()?.contains(query) ?: false)
            }
        }

    /** Port of the Swift `isSelected(_:)`. */
    fun isSelected(profile: ProfileSummary): Boolean {
        val name = profile.normalizedName ?: return false
        return selectedProfileName == name || activeProfileName == name
    }
}

/** Port of the Swift `profileDetails(_:)` — "model - provider - N skills". */
internal fun profileDetails(profile: ProfileSummary): String? {
    val details = ArrayList<String>(3)
    profile.model?.trim()?.takeIf { it.isNotEmpty() }?.let(details::add)
    profile.provider?.trim()?.takeIf { it.isNotEmpty() }?.let(details::add)
    profile.skillCount?.let { details.add("$it skills") }
    return if (details.isEmpty()) null else details.joinToString(" - ")
}

/**
 * ViewModel behind [ProfilePickerSheet] — port of the load/save flow in
 * `DefaultProfilePickerView.swift`: `profiles()` seeds the list and the
 * active name; selecting a row calls `switchProfile(name:)`, adopts the
 * server's echoed active profile, and reports a [DefaultProfileSelection].
 */
class ProfilePickerViewModel(
    private val api: HermesApi,
    private val currentProfileName: String? = null,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ProfilePickerUiState(isLoading = true))
    val uiState: StateFlow<ProfilePickerUiState> = _uiState.asStateFlow()

    init {
        loadProfiles()
    }

    fun loadProfiles() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            try {
                val response = api.profiles()
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        profiles = response.profiles ?: emptyList(),
                        activeProfileName = response.effectiveDefaultProfileName ?: currentProfileName,
                        isSingleProfileMode = response.singleProfileMode ?: false,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _uiState.update {
                    it.copy(isLoading = false, errorMessage = error.message ?: "Could not load profiles")
                }
            }
        }
    }

    fun updateSearchText(text: String) {
        _uiState.update { it.copy(searchText = text) }
    }

    /** Port of the Swift `save(_:)` — optimistic selection, switch, echo reconciliation. */
    fun switchTo(profile: ProfileSummary) {
        val name = profile.normalizedName ?: return
        if (_uiState.value.isSaving) return

        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, saveError = null, selectedProfileName = name) }
            try {
                val response = api.switchProfile(name)
                val serverError = response.error?.trim()
                if (!serverError.isNullOrEmpty()) {
                    _uiState.update {
                        it.copy(isSaving = false, saveError = serverError, selectedProfileName = null)
                    }
                    return@launch
                }

                val resolvedName = response.active?.trim()?.takeIf { it.isNotEmpty() } ?: name
                val updatedProfiles = response.profiles ?: _uiState.value.profiles
                val selectionResponse = ProfilesResponse(profiles = updatedProfiles, active = resolvedName)
                val activeName = selectionResponse.effectiveDefaultProfileName ?: name

                _uiState.update {
                    it.copy(
                        isSaving = false,
                        profiles = updatedProfiles,
                        activeProfileName = activeName,
                        completedSelection = DefaultProfileSelection(
                            name = activeName,
                            displayName = selectionResponse.displayName(activeName) ?: profile.displayName,
                            defaultModel = response.defaultModel,
                        ),
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _uiState.update {
                    it.copy(
                        isSaving = false,
                        saveError = error.message ?: "Could not switch profile",
                        selectedProfileName = null,
                    )
                }
            }
        }
    }

    /** Clears [ProfilePickerUiState.completedSelection] after the sheet reported it. */
    fun consumeCompletedSelection() {
        _uiState.update { it.copy(completedSelection = null) }
    }
}
