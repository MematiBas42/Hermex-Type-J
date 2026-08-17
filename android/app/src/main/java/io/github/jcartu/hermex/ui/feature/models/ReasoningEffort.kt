package io.github.jcartu.hermex.ui.feature.models

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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
 * Port of `ReasoningEffortOption` (ChatComposerSelectorControls.swift):
 * the static effort vocabulary and the issue-#18 server-vocabulary rules.
 */
data class ReasoningEffortOption(
    val id: String,
    val title: String,
) {
    companion object {
        /** Full static list — `none` through `xhigh`, exactly the Swift `allCases`. */
        val allCases: List<ReasoningEffortOption> = listOf(
            ReasoningEffortOption(id = "none", title = "None"),
            ReasoningEffortOption(id = "minimal", title = "Minimal"),
            ReasoningEffortOption(id = "low", title = "Low"),
            ReasoningEffortOption(id = "medium", title = "Medium"),
            ReasoningEffortOption(id = "high", title = "High"),
            ReasoningEffortOption(id = "xhigh", title = "XHigh"),
        )

        /** Display title for an effort id; unknown ids fall back to `capitalized` (Swift parity). */
        fun titleFor(effort: String): String =
            allCases.firstOrNull { it.id == effort }?.title
                ?: effort.replaceFirstChar { it.uppercaseChar() }

        /**
         * Menu options for a server-provided effort vocabulary (issue #18).
         * `null` or empty → the full static list (older servers / defensive
         * fallback). Ids are trimmed, lowercased, de-duplicated; unknown ids
         * are kept with a capitalized title so a newer server's vocabulary
         * still works.
         */
        fun optionsForSupportedEfforts(supportedEfforts: List<String>?): List<ReasoningEffortOption> {
            if (supportedEfforts.isNullOrEmpty()) return allCases

            val seen = HashSet<String>()
            return supportedEfforts
                .map { it.trim().lowercase() }
                .filter { it.isNotEmpty() && seen.add(it) }
                .map { id ->
                    allCases.firstOrNull { it.id == id }
                        ?: ReasoningEffortOption(id = id, title = id.replaceFirstChar { it.uppercaseChar() })
                }
        }

        /**
         * Whether the effort control should be shown at all (issue #18):
         * `supports_reasoning_effort == false` hides it; older servers (both
         * fields absent) keep it visible.
         */
        fun showsEffortControl(
            supportsReasoningEffort: Boolean?,
            supportedEfforts: List<String>?,
        ): Boolean {
            if (supportsReasoningEffort != null) return supportsReasoningEffort
            if (supportedEfforts != null) return supportedEfforts.isNotEmpty()
            return true
        }
    }
}

/**
 * Reasoning effort dropdown — port of `ComposerReasoningMenu`.
 *
 * Public API (stable — chat/settings agents call this):
 * ```
 * ReasoningEffortSelector(
 *     selectedEffort = state.selectedReasoningEffort,
 *     supportedEfforts = state.supportedReasoningEfforts, // null → full static list
 *     onSelectEffort = { effort -> viewModel.saveEffort(effort) },
 *     supportsReasoningEffort = state.supportsReasoningEffort, // false hides the control
 * )
 * ```
 * Renders nothing when [ReasoningEffortOption.showsEffortControl] is false.
 * Persisting the choice is the caller's job (e.g. [ReasoningEffortViewModel.saveEffort]).
 */
@Composable
fun ReasoningEffortSelector(
    selectedEffort: String?,
    supportedEfforts: List<String>?,
    onSelectEffort: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    supportsReasoningEffort: Boolean? = null,
) {
    if (!ReasoningEffortOption.showsEffortControl(supportsReasoningEffort, supportedEfforts)) return

    var menuOpen by remember { mutableStateOf(false) }
    val options = ReasoningEffortOption.optionsForSupportedEfforts(supportedEfforts)
    val title = selectedEffort?.let(ReasoningEffortOption::titleFor) ?: "Reasoning"

    Box(modifier) {
        AssistChip(
            onClick = { menuOpen = true },
            enabled = enabled,
            label = { Text(title, style = MaterialTheme.typography.labelLarge) },
            leadingIcon = {
                Icon(Icons.Filled.Psychology, contentDescription = null, modifier = Modifier.size(18.dp))
            },
            trailingIcon = {
                Icon(Icons.Filled.ArrowDropDown, contentDescription = "Select reasoning effort")
            },
        )
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.title) },
                    leadingIcon = {
                        if (selectedEffort == option.id) {
                            Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                        }
                    },
                    onClick = {
                        menuOpen = false
                        onSelectEffort(option.id)
                    },
                )
            }
        }
    }
}

/** UI state of [ReasoningEffortViewModel]. */
data class ReasoningUiState(
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val errorMessage: String? = null,
    /** Server-coerced effort for the queried model (`reasoning_effort` / `effort`). */
    val selectedEffort: String? = null,
    /** Model-aware vocabulary; null on older servers → callers use the static list. */
    val supportedEfforts: List<String>? = null,
    /** `supports_reasoning_effort`; false hides the effort control, null (older servers) shows it. */
    val supportsReasoningEffort: Boolean? = null,
    /** `show_reasoning` — whether reasoning traces are displayed (the reasoning display option). */
    val showReasoning: Boolean? = null,
)

/**
 * Load/save flow for reasoning settings — ports the seeding done by
 * `ChatComposerConfigLoader` (`GET /api/reasoning` scoped to model/provider)
 * and the save paths `ChatViewModel` uses (`saveReasoningEffort` for effort
 * ids, `saveReasoningDisplay` for show/hide).
 *
 * Construct with the model/provider the control is scoped to (both optional):
 * ```
 * ReasoningEffortViewModel(api = container.api, model = currentModel, provider = currentProvider)
 * ```
 */
class ReasoningEffortViewModel(
    private val api: HermesApi,
    private val model: String? = null,
    private val provider: String? = null,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ReasoningUiState(isLoading = true))
    val uiState: StateFlow<ReasoningUiState> = _uiState.asStateFlow()

    init {
        load()
    }

    /** Loads `GET /api/reasoning` scoped to the model/provider (issue #18 gating fields included). */
    fun load() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            try {
                val response = api.reasoning(model = nonEmpty(model), provider = nonEmpty(provider))
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        selectedEffort = response.effectiveEffort,
                        supportedEfforts = response.normalizedSupportedEfforts,
                        supportsReasoningEffort = response.supportsReasoningEffort,
                        showReasoning = response.showReasoning,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _uiState.update {
                    it.copy(isLoading = false, errorMessage = error.message ?: "Could not load reasoning settings")
                }
            }
        }
    }

    /**
     * Saves an effort id via `POST /api/reasoning` and adopts the server's
     * echoed (possibly coerced) effort — mirrors `ChatViewModel`:
     * `selectedReasoningEffort = response.effectiveEffort ?? reasoning`.
     */
    fun saveEffort(effort: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, errorMessage = null) }
            try {
                val response = api.saveReasoningEffort(effort)
                _uiState.update {
                    it.copy(isSaving = false, selectedEffort = response.effectiveEffort ?: effort)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _uiState.update {
                    it.copy(isSaving = false, errorMessage = error.message ?: "Could not save reasoning effort")
                }
            }
        }
    }

    /**
     * Saves the reasoning display option via `POST /api/reasoning` with
     * `"show"` / `"hide"` (the vocabulary `ChatViewModel.reasoningDisplayArgs`
     * accepts) and adopts the echoed `show_reasoning`.
     */
    fun setReasoningShown(shown: Boolean) {
        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, errorMessage = null) }
            try {
                val response = api.saveReasoningDisplay(if (shown) "show" else "hide")
                _uiState.update {
                    it.copy(isSaving = false, showReasoning = response.showReasoning ?: shown)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _uiState.update {
                    it.copy(isSaving = false, errorMessage = error.message ?: "Could not save reasoning display")
                }
            }
        }
    }

    private fun nonEmpty(value: String?): String? =
        value?.trim()?.takeIf { it.isNotEmpty() }
}
