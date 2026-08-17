package io.github.jcartu.hermex.ui.feature.memory

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.jcartu.hermex.model.MemorySection
import io.github.jcartu.hermex.net.HermesApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Port of `Features/Memory/MemoryViewModel.swift`, read-only: the Android v1
 * surface renders the agent's notes/profile/soul and the project-context
 * document but has no write path (`memoryWrite` is not part of the ported
 * API surface), so the iOS edit sheet is intentionally absent.
 */
class MemoryViewModel(private val api: HermesApi) : ViewModel() {

    private val _uiState = MutableStateFlow(MemoryUiState())
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
                val response = api.memory()
                if (revision != loadRevision) return@launch
                _uiState.update { state ->
                    state.copy(
                        isLoading = false,
                        hasLoaded = true,
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
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (revision != loadRevision) return@launch
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = e.message?.takeIf { m -> m.isNotBlank() } ?: "Something went wrong.",
                    )
                }
            }
        }
    }
}

data class MemoryUiState(
    val isLoading: Boolean = false,
    val hasLoaded: Boolean = false,
    val errorMessage: String? = null,
    val memoryText: String? = null,
    val userText: String? = null,
    val soulText: String? = null,
    /** Epoch seconds, as reported by the server. */
    val memoryMtime: Double? = null,
    val userMtime: Double? = null,
    val soulMtime: Double? = null,
    val projectContextText: String? = null,
    val projectContextName: String? = null,
    val projectContextWorkspace: String? = null,
    val projectContextMtime: Double? = null,
    val isProjectContextShadowed: Boolean = false,
) {
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

    /** Only a non-blank project-context document renders a section (issue parity with iOS). */
    val showsProjectContext: Boolean
        get() = !projectContextText.isNullOrBlank()

    /** Non-localized "name — workspace" detail line for the project-context section. */
    val projectContextDetail: String?
        get() {
            val parts = listOfNotNull(projectContextName, projectContextWorkspace)
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            return if (parts.isEmpty()) null else parts.joinToString(" — ")
        }
}
