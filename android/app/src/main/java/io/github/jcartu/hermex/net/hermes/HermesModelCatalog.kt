package io.github.jcartu.hermex.net.hermes

import io.github.jcartu.hermex.model.ModelCatalogGroup
import io.github.jcartu.hermex.model.ModelCatalogOption
import io.github.jcartu.hermex.model.ModelFavoriteKey
import io.github.jcartu.hermex.model.hermes.BotJSON

/**
 * Port of `Networking/Hermes/HermesModelCatalog.swift`: `model.options` projected into the
 * shared native picker: providers the host has not authenticated, models it marks
 * unavailable, and providers left with none are left out. A Hermes chat's composer (#1015)
 * reads it; no webui transport participates.
 */
class HermesModelCatalog(payload: BotJSON) {
    val groups: List<ModelCatalogGroup>
    val active: ModelCatalogOption?

    /** Each model's `capabilities` object from its provider row, by favorite key. */
    val capabilities: Map<ModelFavoriteKey, BotJSON>

    init {
        val seenProviders = HashSet<String>()
        val capabilities = HashMap<ModelFavoriteKey, BotJSON>()
        groups = (payload["providers"].list ?: emptyList()).mapNotNull { row ->
            val provider = row["slug"].text?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            if (!seenProviders.add(provider)) return@mapNotNull null
            val seenModels = HashSet<String>()
            val unavailable = (row["unavailable_models"].list ?: emptyList()).mapNotNull { it.text }.toSet()
            val options = (row["models"].list ?: emptyList()).mapNotNull { value ->
                if (row["authenticated"].flag == false) return@mapNotNull null
                val id = value.text?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                if (id in unavailable || !seenModels.add(id)) return@mapNotNull null
                val option = ModelCatalogOption(id = id, displayName = id, providerID = provider)
                capabilities[ModelFavoriteKey(modelID = id, providerID = provider)] = row["capabilities"][id]
                option
            }
            // As webui's catalog does, a provider with nothing to pick gets no section.
            if (options.isEmpty()) return@mapNotNull null
            ModelCatalogGroup(id = provider, name = row["name"].text ?: provider, providerID = provider, models = options)
        }
        this.capabilities = capabilities
        active = payload["model"].text?.takeIf { it.isNotEmpty() }?.let { id ->
            ModelCatalogOption(id = id, displayName = id, providerID = payload["provider"].text)
        }
    }

    companion object {
        /** Accepted by the pinned host's `parse_reasoning_effort`; "off" is a display command. */
        val EFFORT_LEVELS = listOf("none", "minimal", "low", "medium", "high", "xhigh", "max", "ultra")

        /**
         * `sessionModelValue(_:)` — the host parses a flag string, not shell quoting. Reject
         * tokens that could change scope; always send `--session`, even when the host persists
         * picks by default.
         */
        fun sessionModelValue(option: ModelCatalogOption): String? {
            fun safe(value: String): Boolean =
                value.isNotEmpty() && value.none { it.isWhitespace() } && !value.contains("--") &&
                    value.none { it in "\u2012\u2013\u2014\u2015" }
            val provider = option.providerID ?: return null
            if (!safe(option.id) || !safe(provider)) return null
            return "${option.id} --provider $provider --session"
        }
    }
}
