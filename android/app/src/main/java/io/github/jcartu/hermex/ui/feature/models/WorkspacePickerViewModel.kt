package io.github.jcartu.hermex.ui.feature.models

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.jcartu.hermex.model.WorkspaceRoot
import io.github.jcartu.hermex.net.HermesApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One selectable workspace row — port of the Swift `WorkspacePickerRow`. */
data class WorkspacePickerRow(
    val path: String,
    val name: String?,
)

/** Port of the Swift `String.lastPathComponentFallback` — last path segment, or the input when empty. */
internal fun lastPathComponentFallback(path: String): String {
    val component = path.trimEnd('/').substringAfterLast('/')
    return component.ifEmpty { path }
}

/** Port of the Swift `savedWorkspaceRows` — roots with a non-empty path, de-duplicated by path. */
internal fun savedWorkspaceRows(roots: List<WorkspaceRoot>): List<WorkspacePickerRow> {
    val seen = HashSet<String>()
    return roots.mapNotNull { root ->
        val path = root.path?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        if (!seen.add(path)) return@mapNotNull null
        WorkspacePickerRow(path = path, name = root.name)
    }
}

/**
 * Port of the Swift `suggestionRows` — server suggestions minus saved paths,
 * minus the effective selection, de-duplicated, empties dropped.
 */
internal fun suggestionRows(
    suggestions: List<String>,
    savedRows: List<WorkspacePickerRow>,
    effectiveSelectedPath: String?,
): List<String> {
    val savedPaths = savedRows.mapTo(HashSet()) { it.path }
    val seen = HashSet<String>()
    return suggestions.filter { path ->
        path.isNotEmpty() &&
            !savedPaths.contains(path) &&
            path != effectiveSelectedPath &&
            seen.add(path)
    }
}

/** UI state of [WorkspacePickerViewModel]. */
data class WorkspacePickerUiState(
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val workspaceRoots: List<WorkspaceRoot> = emptyList(),
    val suggestions: List<String> = emptyList(),
    val prefix: String = "",
    /** The path already selected when the picker opened (checkmark anchor). */
    val selectedWorkspacePath: String? = null,
    /** Set once a row is tapped; further taps are ignored (Swift accept-once guard). */
    val acceptedWorkspacePath: String? = null,
) {
    val effectiveSelectedWorkspacePath: String?
        get() = acceptedWorkspacePath ?: selectedWorkspacePath

    val savedRows: List<WorkspacePickerRow>
        get() = savedWorkspaceRows(workspaceRoots)

    val suggestionPaths: List<String>
        get() = suggestionRows(suggestions, savedRows, effectiveSelectedWorkspacePath)
}

/**
 * ViewModel behind [WorkspacePickerSheet] — port of
 * `ComposerWorkspacePickerSheet` (ChatComposerSelectorSheets.swift) plus the
 * loading `ChatViewModel` does around it: `workspaces()` seeds the trusted
 * roots and `workspaceSuggestions(prefix:)` refreshes live suggestions,
 * debounced 250 ms for a non-empty prefix (the Swift `.task(id: prefix)` sleep).
 */
class WorkspacePickerViewModel(
    private val api: HermesApi,
    selectedWorkspacePath: String? = null,
    private val suggestionDebounceMillis: Long = 250,
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        WorkspacePickerUiState(
            isLoading = true,
            selectedWorkspacePath = selectedWorkspacePath?.trim()?.takeIf { it.isNotEmpty() },
        ),
    )
    val uiState: StateFlow<WorkspacePickerUiState> = _uiState.asStateFlow()

    private var suggestionJob: Job? = null

    init {
        loadRoots()
        // The iOS sheet fires `.task(id: prefix)` on appear with the empty
        // prefix — no debounce for the initial/empty query.
        refreshSuggestions("")
    }

    /** Loads the trusted workspace roots; suggestions are seeded from their paths (config-loader parity). */
    fun loadRoots() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            try {
                val response = api.workspaces()
                val roots = response.workspaces ?: emptyList()
                _uiState.update { state ->
                    state.copy(
                        isLoading = false,
                        workspaceRoots = roots,
                        suggestions = state.suggestions.ifEmpty { roots.mapNotNull { it.path } },
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _uiState.update {
                    it.copy(isLoading = false, errorMessage = error.message ?: "Could not load workspaces")
                }
            }
        }
    }

    /** Updates the path prefix and (debounced) refreshes the server suggestions. */
    fun updatePrefix(prefix: String) {
        _uiState.update { it.copy(prefix = prefix) }
        suggestionJob?.cancel()
        suggestionJob = viewModelScope.launch {
            if (prefix.isNotEmpty()) delay(suggestionDebounceMillis)
            fetchSuggestions(prefix)
        }
    }

    /**
     * Accept-once selection guard (Swift `acceptedWorkspacePath`). Returns true
     * when this call won the selection — the sheet then reports the path and
     * dismisses; later taps return false and are ignored.
     */
    fun accept(path: String): Boolean {
        var won = false
        _uiState.update { state ->
            if (state.acceptedWorkspacePath != null) {
                state
            } else {
                won = true
                state.copy(acceptedWorkspacePath = path)
            }
        }
        return won
    }

    private fun refreshSuggestions(prefix: String) {
        suggestionJob?.cancel()
        suggestionJob = viewModelScope.launch { fetchSuggestions(prefix) }
    }

    private suspend fun fetchSuggestions(prefix: String) {
        try {
            val response = api.workspaceSuggestions(prefix)
            _uiState.update { it.copy(suggestions = response.suggestions ?: emptyList()) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            // ChatViewModel surfaces this as a composer configuration error;
            // in the picker we keep the previous suggestions and show the message.
            _uiState.update { it.copy(errorMessage = error.message ?: "Could not load suggestions") }
        }
    }
}
