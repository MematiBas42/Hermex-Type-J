package io.github.jcartu.hermex.ui.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.jcartu.hermex.model.ProviderSummary
import io.github.jcartu.hermex.net.ApiError
import io.github.jcartu.hermex.net.HermesApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Backs the read-only Providers status screen (#26), port of
 * `ProvidersViewModel.swift`. Loads `GET /api/providers` and exposes pure,
 * testable presentation helpers — no write operations by design (key
 * set/delete stays a server-side concern).
 */
data class ProvidersUiState(
    /** Server order preserved: upstream sorts active-first, custom, key-holders, rest. */
    val providers: List<ProviderSummary> = emptyList(),
    val activeProviderId: String? = null,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
)

class ProvidersViewModel(private val api: HermesApi) : ViewModel() {

    private val _uiState = MutableStateFlow(ProvidersUiState())
    val uiState: StateFlow<ProvidersUiState> = _uiState.asStateFlow()

    /**
     * Monotonic token identifying the most recent [load] call: an older
     * in-flight request must not overwrite a newer response or clear the
     * loading flag while the newer request is pending.
     */
    private var loadGeneration = 0

    fun load() {
        viewModelScope.launch {
            loadGeneration += 1
            val generation = loadGeneration
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }

            try {
                val response = api.providers()
                if (generation != loadGeneration) return@launch
                _uiState.update {
                    it.copy(
                        providers = response.providers.orEmpty(),
                        activeProviderId = normalizedProviderId(response.activeProvider),
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: ApiError) {
                if (generation != loadGeneration) return@launch
                _uiState.update { it.copy(errorMessage = error.message) }
            }

            if (generation != loadGeneration) return@launch
            _uiState.update { it.copy(isLoading = false) }
        }
    }

    fun isActive(provider: ProviderSummary): Boolean {
        val active = _uiState.value.activeProviderId ?: return false
        val id = normalizedProviderId(provider.id) ?: return false
        return id == active
    }

    companion object {
        /**
         * `active_provider` comes from config while entry `id`s are canonical
         * slugs — trim and lowercase both sides so cosmetic differences don't
         * hide the active badge.
         */
        fun normalizedProviderId(raw: String?): String? =
            raw?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }

        fun displayName(provider: ProviderSummary): String =
            trimmedNonEmpty(provider.displayName)
                ?: trimmedNonEmpty(provider.id)
                ?: "Unknown provider"

        /**
         * Short technical badge naming where a configured key came from.
         * Collapses the upstream `key_source` vocabulary; unknown future values
         * pass through verbatim. Null when the provider has no key.
         */
        fun keySourceBadge(provider: ProviderSummary): String? {
            if (provider.hasKey != true) return null

            return when (val raw = provider.keySource?.trim()?.lowercase().orEmpty()) {
                "", "none" -> null
                "env_file", "env_var", "env" -> "env"
                "oauth", "token" -> "OAuth"
                "config_yaml", "config" -> "config"
                else -> raw
            }
        }

        fun authErrorText(provider: ProviderSummary): String? =
            trimmedNonEmpty(provider.authError)

        /**
         * Catalog size to advertise: `models_total` reflects the complete
         * catalog even when `models` is trimmed to a featured subset.
         */
        fun modelCount(provider: ProviderSummary): Int =
            maxOf(provider.modelsTotal ?: 0, provider.models?.size ?: 0)

        /**
         * Non-null only when the server trimmed the model list — drives the
         * "Showing X of Y models" footer.
         */
        fun truncatedModelInfo(provider: ProviderSummary): Pair<Int, Int>? {
            val shown = provider.models?.size ?: 0
            val total = provider.modelsTotal ?: return null
            return if (shown > 0 && total > shown) shown to total else null
        }

        private fun trimmedNonEmpty(value: String?): String? =
            value?.trim()?.takeIf { it.isNotEmpty() }
    }
}
