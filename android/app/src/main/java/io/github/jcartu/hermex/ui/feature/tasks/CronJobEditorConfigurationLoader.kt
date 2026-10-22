package io.github.jcartu.hermex.ui.feature.tasks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.jcartu.hermex.model.CronDeliveryOption
import io.github.jcartu.hermex.model.ModelCatalogGroup
import io.github.jcartu.hermex.model.ProfileSummary
import io.github.jcartu.hermex.model.SkillSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The model catalog, profile list, and skill list behind the Task editor's
 * Configuration section.
 *
 * Owned by `CronJobEditorSheet` and driven from its composition, so the
 * requests happen when the editor opens rather than when the Tasks list
 * appears — a user scrolling their tasks should not pay for a catalog fetch.
 * The create sheet and the edit sheet are the same sheet, so they share this
 * one loader instead of each view model growing its own copy.
 *
 * The loads are independent and non-fatal. A failed catalog leaves the model
 * picker with only its custom entry; a failed profile or skill list leaves its
 * row showing an error with a retry.
 *
 * On a server that keeps Tasks in Profiles (a Hermes host, #1040), the models,
 * skills and delivery targets are the Task's Profile's, so one loader serves
 * one Profile and the sheet makes a new one when the Profile changes.
 * Port of `Features/Tasks/CronJobEditorConfigurationLoader.swift`.
 */
data class CronJobEditorConfigurationUiState(
    val modelGroups: List<ModelCatalogGroup> = emptyList(),
    val isLoadingModels: Boolean = false,
    val modelsErrorMessage: String? = null,
    val profiles: List<ProfileSummary> = emptyList(),
    val isLoadingProfiles: Boolean = false,
    val profilesErrorMessage: String? = null,
    val skills: List<SkillSummary> = emptyList(),
    val isLoadingSkills: Boolean = false,
    val skillsErrorMessage: String? = null,
    /** The Profile's delivery targets, on a server that scopes them by Profile; null elsewhere. */
    val deliveryOptions: List<CronDeliveryOption>? = null,
)

class CronJobEditorConfigurationLoader(
    private val client: CronDataClient,
    val profile: String? = null,
) : ViewModel() {

    private val _uiState = MutableStateFlow(CronJobEditorConfigurationUiState())
    val uiState: StateFlow<CronJobEditorConfigurationUiState> = _uiState.asStateFlow()

    private val state: CronJobEditorConfigurationUiState
        get() = _uiState.value

    private val isProfileScoped: Boolean
        get() = client.cronFeatures.isProfileScoped

    /**
     * Runs all the loads concurrently. Re-entrant calls are dropped, so a
     * restarted composition does not stack requests.
     */
    fun load() {
        if (state.isLoadingModels || state.isLoadingProfiles || state.isLoadingSkills) return
        viewModelScope.launch {
            coroutineScope {
                val models = async { loadModels() }
                val profiles = async { loadProfiles() }
                val skills = async { loadSkills() }
                val delivery = async { loadDeliveryOptions() }
                models.await()
                profiles.await()
                skills.await()
                delivery.await()
            }
        }
    }

    suspend fun loadModels() {
        if (state.isLoadingModels) return
        _uiState.update { it.copy(isLoadingModels = true, modelsErrorMessage = null) }
        try {
            val groups = client.cronModelGroups(profile)
            _uiState.update { it.copy(modelGroups = groups, isLoadingModels = false) }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            _uiState.update { it.copy(isLoadingModels = false, modelsErrorMessage = error.message ?: "Could not load models.") }
        }
    }

    /** Also the profile row's retry. */
    suspend fun loadProfiles() {
        if (state.isLoadingProfiles) return
        _uiState.update { it.copy(isLoadingProfiles = true, profilesErrorMessage = null) }
        try {
            val profiles = client.cronProfiles()
            _uiState.update { it.copy(profiles = profiles, isLoadingProfiles = false) }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            _uiState.update { it.copy(isLoadingProfiles = false, profilesErrorMessage = error.message ?: "Could not load profiles.") }
        }
    }

    /** Also the skills row's retry. */
    suspend fun loadSkills() {
        if (state.isLoadingSkills) return
        _uiState.update { it.copy(isLoadingSkills = true, skillsErrorMessage = null) }
        try {
            // Disabled skills are filtered out: the picker offers what a run
            // could actually use, and the server would ignore the rest.
            val skills = client.cronSkills(profile).filter { it.disabled != true }
            _uiState.update { it.copy(skills = skills, isLoadingSkills = false) }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            _uiState.update { it.copy(isLoadingSkills = false, skillsErrorMessage = error.message ?: "Could not load skills.") }
        }
    }

    /** Only where the server scopes targets by Profile. A failure leaves the deliver field as free text. */
    suspend fun loadDeliveryOptions() {
        if (!isProfileScoped) return
        val options = try {
            client.cronDeliveryOptions(profile)?.platforms
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            null
        }
        _uiState.update { it.copy(deliveryOptions = options) }
    }
}
