package io.github.jcartu.hermex.ui.feature.memory

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.jcartu.hermex.model.MemorySection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Port of `Features/Memory/MemoryViewModel.swift`, including conflict-safe Hermes edits. */
class MemoryViewModel(private val client: MemoryDataClient) : ViewModel() {
    private val _uiState = MutableStateFlow(MemoryUiState(features = client.features))
    val uiState: StateFlow<MemoryUiState> = _uiState.asStateFlow()

    private var loadRevision = 0

    fun loadIfNeeded() {
        if (_uiState.value.hasLoaded || _uiState.value.isLoading) return
        load()
    }

    fun load() {
        val revision = ++loadRevision
        _uiState.update { it.copy(isLoading = true, errorMessage = null) }
        viewModelScope.launch {
            try {
                val snapshot = client.memory()
                if (revision != loadRevision) return@launch
                _uiState.update { applySnapshot(it, snapshot).copy(isLoading = false) }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                if (revision != loadRevision) return@launch
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = error.userMessage(),
                    )
                }
            }
        }
    }

    fun clearActionError() {
        _uiState.update { it.copy(actionErrorMessage = null, conflictedSection = null) }
    }

    fun save(
        section: MemorySection,
        content: String,
        loaded: String,
        onSaved: () -> Unit = {},
    ) {
        val state = _uiState.value
        if (!state.features.canEdit || section in state.readOnlySections) return
        val limit = state.characterLimits[section]
        if (limit != null && MemoryCharacterCount(content, limit).isOver) {
            _uiState.update { it.copy(actionErrorMessage = "Over the host's limit. Shorten to save.") }
            return
        }
        _uiState.update {
            it.copy(
                isSaving = true,
                actionErrorMessage = null,
                conflictedSection = null,
            )
        }
        viewModelScope.launch {
            try {
                val response = client.saveMemory(section, content, loaded)
                if (response.ok == false) {
                    _uiState.update {
                        it.copy(
                            isSaving = false,
                            actionErrorMessage = response.error ?: "Could not save memory.",
                        )
                    }
                    return@launch
                }
                val refreshed = client.memory()
                _uiState.update { applySnapshot(it, refreshed).copy(isSaving = false) }
                onSaved()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: MemoryConflict) {
                _uiState.update {
                    it.copy(isSaving = false, conflictedSection = section)
                }
            } catch (error: Exception) {
                _uiState.update {
                    it.copy(isSaving = false, actionErrorMessage = error.userMessage())
                }
            }
        }
    }

    fun reloadSection(section: MemorySection, onReloaded: (String) -> Unit) {
        _uiState.update { it.copy(isReloading = true, actionErrorMessage = null) }
        viewModelScope.launch {
            try {
                val refreshed = client.memory()
                val next = applySnapshot(_uiState.value, refreshed).copy(
                    isReloading = false,
                    conflictedSection = null,
                )
                _uiState.value = next
                onReloaded(next.content(section))
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                _uiState.update {
                    it.copy(isReloading = false, actionErrorMessage = error.userMessage())
                }
            }
        }
    }

    private fun applySnapshot(state: MemoryUiState, snapshot: MemorySnapshot): MemoryUiState {
        val response = snapshot.response
        return state.copy(
            hasLoaded = true,
            errorMessage = null,
            memoryText = response.memory,
            userText = response.user,
            soulText = response.soul,
            memoryMtime = response.memoryMtime,
            userMtime = response.userMtime,
            soulMtime = response.soulMtime,
            projectContextText = response.projectContext,
            projectContextName = response.projectContextName,
            projectContextWorkspace = response.projectContextWorkspace,
            projectContextMtime = response.projectContextMtime,
            isProjectContextShadowed = response.projectContextShadowed ?: false,
            hiddenSections = snapshot.hiddenSections,
            characterLimits = snapshot.characterLimits,
            readOnlySections = snapshot.readOnlySections,
        )
    }
}

data class MemoryUiState(
    val features: MemoryFeatures,
    val isLoading: Boolean = false,
    val hasLoaded: Boolean = false,
    val isSaving: Boolean = false,
    val isReloading: Boolean = false,
    val errorMessage: String? = null,
    val actionErrorMessage: String? = null,
    val conflictedSection: MemorySection? = null,
    val memoryText: String? = null,
    val userText: String? = null,
    val soulText: String? = null,
    val memoryMtime: Double? = null,
    val userMtime: Double? = null,
    val soulMtime: Double? = null,
    val projectContextText: String? = null,
    val projectContextName: String? = null,
    val projectContextWorkspace: String? = null,
    val projectContextMtime: Double? = null,
    val isProjectContextShadowed: Boolean = false,
    val hiddenSections: Set<MemorySection> = emptySet(),
    val characterLimits: Map<MemorySection, Int> = emptyMap(),
    val readOnlySections: Set<MemorySection> = emptySet(),
) {
    val visibleSections: List<MemorySection>
        get() = MemorySection.entries.filterNot(hiddenSections::contains)

    fun content(section: MemorySection): String = when (section) {
        MemorySection.MEMORY -> memoryText.orEmpty()
        MemorySection.USER -> userText.orEmpty()
        MemorySection.SOUL -> soulText.orEmpty()
    }

    fun modifiedAt(section: MemorySection): Double? = when (section) {
        MemorySection.MEMORY -> memoryMtime
        MemorySection.USER -> userMtime
        MemorySection.SOUL -> soulMtime
    }

    val showsProjectContext: Boolean
        get() = !projectContextText.isNullOrBlank()

    val projectContextDetail: String?
        get() {
            val parts = listOfNotNull(projectContextName, projectContextWorkspace)
                .map(String::trim)
                .filter(String::isNotEmpty)
            return parts.takeIf(List<String>::isNotEmpty)?.joinToString(" — ")
        }
}

data class MemoryCharacterCount(val count: Int, val limit: Int) {
    constructor(draft: String, limit: Int) : this(
        count = MemoryCanonicalizer.scalarCount(MemoryCanonicalizer.canonical(draft)),
        limit = limit,
    )

    val isOver: Boolean get() = count > limit
    val overBy: Int get() = (count - limit).coerceAtLeast(0)
}

private fun Exception.userMessage(): String =
    message?.takeIf(String::isNotBlank) ?: "Something went wrong."
