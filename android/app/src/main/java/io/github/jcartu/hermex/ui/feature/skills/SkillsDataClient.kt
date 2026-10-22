package io.github.jcartu.hermex.ui.feature.skills

import io.github.jcartu.hermex.model.SkillSummary
import io.github.jcartu.hermex.model.ToggleSkillResponse
import io.github.jcartu.hermex.net.HermesApi
import io.github.jcartu.hermex.net.hermes.HermesSkillsClient

/**
 * Kind-neutral Skills surface. Port of `Features/Skills/SkillsViewModel.swift`
 * `SkillsDataClient`: webui and direct-Hermes screens share the same reducers and Compose UI.
 */
interface SkillsDataClient {
    /** Hermes alone can enumerate the files beside a skill's SKILL.md. */
    val hasLinkedFiles: Boolean

    suspend fun skills(): List<SkillSummary>

    suspend fun skillContent(name: String, file: String? = null): SkillContent

    suspend fun toggleSkill(name: String, enabled: Boolean): ToggleSkillResponse
}

/** One SKILL.md or linked-file answer, including Hermes' binary/truncation flags (#1070). */
data class SkillContent(
    val name: String? = null,
    val content: String? = null,
    val linkedFiles: List<String> = emptyList(),
    val isBinary: Boolean = false,
    val isTruncated: Boolean = false,
)

/** Legacy webui adapter; its endpoint has no binary/truncation metadata. */
class WebuiSkillsDataClient(private val api: HermesApi) : SkillsDataClient {
    override val hasLinkedFiles: Boolean = false

    override suspend fun skills(): List<SkillSummary> = api.skills().skills.orEmpty()

    override suspend fun skillContent(name: String, file: String?): SkillContent {
        val response = api.skillContent(name, file)
        return SkillContent(
            name = response.name,
            content = response.content,
            linkedFiles = response.linkedFiles.orEmpty(),
        )
    }

    override suspend fun toggleSkill(name: String, enabled: Boolean): ToggleSkillResponse =
        api.toggleSkill(name, enabled)
}

/** Direct-Hermes adapter over `Networking/Hermes/HermesSkillsClient.swift` (#1069/#1070). */
class HermesSkillsDataClient(private val client: HermesSkillsClient) : SkillsDataClient {
    override val hasLinkedFiles: Boolean = true

    override suspend fun skills(): List<SkillSummary> = client.skills()

    override suspend fun skillContent(name: String, file: String?): SkillContent {
        val response = client.skillContent(name, file)
        return SkillContent(
            name = response.name,
            content = response.content,
            linkedFiles = response.linkedFiles.orEmpty(),
            isBinary = response.isBinary,
            isTruncated = response.isTruncated,
        )
    }

    override suspend fun toggleSkill(name: String, enabled: Boolean): ToggleSkillResponse =
        client.toggleSkill(name, enabled)
}
