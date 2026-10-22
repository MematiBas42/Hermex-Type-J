package io.github.jcartu.hermex.ui.feature.skills

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.jcartu.hermex.model.SkillSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One category section of the skills list. */
data class SkillGroup(
    val category: String,
    val skills: List<SkillSummary>,
)

/**
 * State for the skills catalog. Port of `Features/Skills/SkillsViewModel.swift`:
 * category grouping, search across name/description/category/tags, and the
 * optimistic enable/disable toggle with rollback on failure.
 */
data class SkillsUiState(
    val skills: List<SkillSummary> = emptyList(),
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val togglingSkillNames: Set<String> = emptySet(),
    val searchText: String = "",
) {
    /** Category sections after applying [searchText]. */
    val groups: List<SkillGroup>
        get() = SkillsViewModel.filteredGroupedSkills(skills, searchText)
}
class SkillsViewModel(private val client: SkillsDataClient) : ViewModel() {

    private val _uiState = MutableStateFlow(SkillsUiState())
    val uiState: StateFlow<SkillsUiState> = _uiState.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            try {
                val skills = client.skills()
                _uiState.update {
                    it.copy(skills = skills, isLoading = false)
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = error.message ?: "Could not load skills.",
                    )
                }
            }
        }
    }

    fun setSearchText(text: String) {
        _uiState.update { it.copy(searchText = text) }
    }

    /**
     * Enables/disables a skill. Port of Swift `setSkill(_:enabled:)`: the local
     * state flips optimistically, the toggle name is tracked so the row's
     * switch disables while in flight, and a failure rolls back and surfaces
     * the error. Success reloads the catalog for the server's view.
     */
    fun setSkill(skill: SkillSummary, enabled: Boolean) {
        val name = skill.name?.trim().takeUnless { it.isNullOrEmpty() } ?: return
        if (name in _uiState.value.togglingSkillNames) return

        _uiState.update { state ->
            state.copy(
                togglingSkillNames = state.togglingSkillNames + name,
                errorMessage = null,
                skills = updateSkill(state.skills, name, disabled = !enabled),
            )
        }
        viewModelScope.launch {
            try {
                client.toggleSkill(name = name, enabled = enabled)
                _uiState.update { it.copy(togglingSkillNames = it.togglingSkillNames - name) }
                load()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                _uiState.update { state ->
                    state.copy(
                        togglingSkillNames = state.togglingSkillNames - name,
                        skills = updateSkill(state.skills, name, disabled = enabled),
                        errorMessage = error.message ?: "Could not update skill.",
                    )
                }
            }
        }
    }

    private fun updateSkill(
        skills: List<SkillSummary>,
        name: String,
        disabled: Boolean,
    ): List<SkillSummary> = skills.map { skill ->
        if (skill.name?.trim() == name) skill.copy(disabled = disabled) else skill
    }

    companion object {
        private const val UNCATEGORIZED = "Uncategorized"

        /**
         * Port of Swift `groupedSkills(for:)`: groups by category (trimmed,
         * "Uncategorized" fallback), categories sorted case-insensitively,
         * skills within each sorted by display name case-insensitively.
         */
        fun groupedSkills(skills: List<SkillSummary>): List<SkillGroup> =
            skills
                .groupBy { categoryName(it) }
                .entries
                .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.key })
                .map { (category, grouped) ->
                    SkillGroup(
                        category = category,
                        skills = grouped.sortedWith(
                            compareBy(String.CASE_INSENSITIVE_ORDER, ::displayName),
                        ),
                    )
                }

        /**
         * Port of Swift `filteredGroupedSkills(searchText:)`: a blank query
         * returns every group; otherwise matches name, description, category,
         * or any tag, case-insensitively.
         */
        fun filteredGroupedSkills(skills: List<SkillSummary>, searchText: String): List<SkillGroup> {
            val query = searchText.trim()
            if (query.isEmpty()) return groupedSkills(skills)

            val filtered = skills.filter { skill ->
                skill.name?.contains(query, ignoreCase = true) == true ||
                    skill.description?.contains(query, ignoreCase = true) == true ||
                    skill.category?.contains(query, ignoreCase = true) == true ||
                    skill.tags?.any { it.contains(query, ignoreCase = true) } == true
            }
            if (filtered.isEmpty()) return emptyList()
            return groupedSkills(filtered)
        }

        fun displayName(skill: SkillSummary): String =
            skill.name?.trim().takeUnless { it.isNullOrEmpty() } ?: "Unnamed Skill"

        private fun categoryName(skill: SkillSummary): String =
            skill.category?.trim().takeUnless { it.isNullOrEmpty() } ?: UNCATEGORIZED
    }
}
