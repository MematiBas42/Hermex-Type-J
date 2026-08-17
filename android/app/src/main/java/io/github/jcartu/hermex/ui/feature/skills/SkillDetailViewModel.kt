package io.github.jcartu.hermex.ui.feature.skills

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.jcartu.hermex.net.HermesApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Detail state for a single skill. Port of the state held by Swift
 * `SkillDetailView` (`Features/Skills/SkillsView.swift`): the markdown content
 * and linked-file list from `skillContent(name:)`, plus the linked-file viewer
 * fed by `skillContent(name:file:)`.
 */
data class SkillDetailUiState(
    val content: String? = null,
    val linkedFiles: List<String> = emptyList(),
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    /** The linked file currently open in the viewer; null when closed. */
    val selectedFile: String? = null,
    val fileContent: String? = null,
    val isLoadingFile: Boolean = false,
)

class SkillDetailViewModel(
    private val api: HermesApi,
    private val skillName: String,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SkillDetailUiState())
    val uiState: StateFlow<SkillDetailUiState> = _uiState.asStateFlow()

    init {
        load()
    }

    fun load() {
        // Mirrors the Swift `guard let name` — unnamed skills load nothing and
        // the view falls through to its "No Content" state.
        if (skillName.isEmpty()) return

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            try {
                val response = api.skillContent(skillName)
                _uiState.update {
                    it.copy(
                        content = response.content,
                        linkedFiles = response.linkedFiles ?: emptyList(),
                        isLoading = false,
                    )
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = error.message ?: "Could not load skill.",
                    )
                }
            }
        }
    }

    /**
     * Opens a linked file in the viewer. A failure keeps the viewer open with
     * an inline message, exactly like the Swift `loadLinkedFile(named:)`.
     */
    fun openFile(fileName: String) {
        if (skillName.isEmpty()) return

        _uiState.update {
            it.copy(selectedFile = fileName, fileContent = null, isLoadingFile = true)
        }
        viewModelScope.launch {
            try {
                val response = api.skillContent(skillName, fileName)
                _uiState.update { it.copy(fileContent = response.content, isLoadingFile = false) }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                _uiState.update {
                    it.copy(
                        fileContent = "Could not load file: ${error.message}",
                        isLoadingFile = false,
                    )
                }
            }
        }
    }

    fun closeFile() {
        _uiState.update { it.copy(selectedFile = null, fileContent = null, isLoadingFile = false) }
    }
}
