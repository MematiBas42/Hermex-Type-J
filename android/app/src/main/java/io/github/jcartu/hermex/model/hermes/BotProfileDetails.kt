package io.github.jcartu.hermex.model.hermes

import io.github.jcartu.hermex.model.ModelCatalogOption

/**
 * One Profile-scoped capability returned by `profiles.describe`.
 * Port of `BotProfileCapability` in `Features/Bots/BotProfileEditor.swift`.
 */
data class BotProfileCapability(
    val id: String,
    val name: String,
    val detail: String? = null,
    val enabled: Boolean,
)

/**
 * The editable, Profile-scoped projection of `profiles.describe`.
 *
 * Only capability rows the host returned are represented. Missing and future fields are
 * tolerated, while the expected Profile name remains the identity fallback. Port of
 * `BotProfileDetails` in `Features/Bots/BotProfileEditor.swift`.
 */
data class BotProfileDetails(
    val name: String,
    val description: String,
    val instructions: String,
    val model: ModelCatalogOption?,
    val skills: List<BotProfileCapability>,
    val toolsets: List<BotProfileCapability>,
    val toolsetsPinned: Boolean,
    val mcpServers: List<BotProfileCapability>,
) {
    companion object {
        fun from(payload: BotJSON, expectedName: String): BotProfileDetails {
            val modelID = payload["model"]["default"].text?.trim().orEmpty()
            val provider = payload["model"]["provider"].text?.trim().orEmpty()
            return BotProfileDetails(
                name = payload["name"].text ?: expectedName,
                description = payload["description"].text ?: "",
                instructions = payload["soul"].text ?: "",
                model = if (modelID.isEmpty() || provider.isEmpty()) {
                    null
                } else {
                    ModelCatalogOption(id = modelID, displayName = modelID, providerID = provider)
                },
                skills = capabilities(payload["skills"], label = "name"),
                toolsets = capabilities(payload["toolsets"], label = "label") { row ->
                    val description = row["description"].text?.trim()?.takeIf(String::isNotEmpty)
                    val count = row["tool_count"].integer?.toString()
                    listOfNotNull(description, count).joinToString(" · ").takeIf(String::isNotEmpty)
                },
                toolsetsPinned = payload["toolsets_pinned"].flag == true,
                mcpServers = capabilities(payload["mcp_servers"], label = "name") { row ->
                    row["transport"].text
                },
            )
        }

        private fun capabilities(
            payload: BotJSON,
            label: String,
            detail: (BotJSON) -> String? = { null },
        ): List<BotProfileCapability> {
            val seen = HashSet<String>()
            return (payload.list ?: emptyList()).mapNotNull { row ->
                val id = row["name"].text?.trim()?.takeIf(String::isNotEmpty) ?: return@mapNotNull null
                if (!seen.add(id)) return@mapNotNull null
                val display = row[label].text?.trim()?.takeIf(String::isNotEmpty) ?: id
                BotProfileCapability(
                    id = id,
                    name = display,
                    detail = detail(row),
                    enabled = row["enabled"].flag != false,
                )
            }
        }
    }
}
