package io.github.jcartu.hermex.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.util.UUID

@Serializable
data class SkillsResponse(
    val skills: List<SkillSummary>? = null,
)

@Serializable
data class SkillSummary(
    val name: String? = null,
    val category: String? = null,
    val description: String? = null,
    val path: String? = null,
    val disabled: Boolean? = null,
    val tags: List<String>? = null,
    @SerialName("related_skills") val relatedSkills: List<String>? = null,
) {
    val id: String
        get() = name ?: UUID.randomUUID().toString()
}

@Serializable
data class ToggleSkillRequest(
    val name: String,
    val enabled: Boolean,
)

@Serializable
data class ToggleSkillResponse(
    val ok: Boolean? = null,
    val name: String? = null,
    val enabled: Boolean? = null,
)

data class SkillSlashSuggestion(
    val name: String,
    val category: String?,
    val description: String?,
) {
    val id: String
        get() = slashName

    val slashName: String
        get() = SlashSkillFormatter.slug(name)
}

data class SkillSlashInvocation(
    val skill: SkillSlashSuggestion,
    val message: String,
)

object SlashSkillFormatter {
    fun slug(name: String): String {
        val lower = name.trim().lowercase()
        return buildString(lower.length) {
            var pendingHyphen = false

            for (character in lower) {
                when {
                    character == '-' ||
                        character == '_' ||
                        character.isWhitespace() ||
                        Character.isSpaceChar(character) -> {
                        if (isNotEmpty()) pendingHyphen = true
                    }

                    character in 'a'..'z' || character in '0'..'9' -> {
                        if (pendingHyphen && isNotEmpty()) append('-')
                        append(character)
                        pendingHyphen = false
                    }
                }
            }
        }
    }

    fun suggestions(skills: List<SkillSummary>): List<SkillSlashSuggestion> {
        val parsed = skills.mapNotNull { skill ->
            val rawName = skill.name?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            if (slug(rawName).isEmpty()) return@mapNotNull null

            SkillSlashSuggestion(
                name = rawName,
                category = skill.category?.trim()?.takeIf { it.isNotEmpty() },
                description = skill.description?.trim()?.takeIf { it.isNotEmpty() },
            )
        }

        val seen = mutableSetOf<String>()
        return parsed
            .filter { seen.add(it.slashName) }
            .sortedWith { lhs, rhs -> lhs.slashName.compareTo(rhs.slashName, ignoreCase = true) }
    }

    fun skillQuery(args: String): String {
        val trimmed = args.trim()
        if (trimmed.isEmpty()) return ""
        return trimmed.substringBefore(' ')
    }

    fun invocation(
        args: String,
        suggestions: List<SkillSlashSuggestion>,
    ): SkillSlashInvocation? {
        val trimmed = args.trim()
        val separatorIndex = trimmed.indexOf(' ')
        if (separatorIndex < 0) return null

        val skill = skill(trimmed.substring(0, separatorIndex), suggestions) ?: return null
        val message = trimmed.substring(separatorIndex + 1).trim()
        if (message.isEmpty()) return null
        return SkillSlashInvocation(skill = skill, message = message)
    }

    fun skill(
        name: String,
        suggestions: List<SkillSlashSuggestion>,
    ): SkillSlashSuggestion? {
        val requestedSkill = name.trim().lowercase()
        if (requestedSkill.isEmpty()) return null
        return suggestions.firstOrNull { suggestion ->
            suggestion.slashName.lowercase() == requestedSkill ||
                suggestion.name.lowercase() == requestedSkill
        }
    }

    fun messageText(invocation: SkillSlashInvocation): String =
        "/${invocation.skill.slashName} ${invocation.message}"

    fun detailMessage(skill: SkillSlashSuggestion): String {
        val lines = mutableListOf(
            "### `/${skill.slashName}`",
            "",
            "**${skill.name}**",
        )

        skill.category?.let { category ->
            lines += ""
            lines += "Category: $category"
        }

        skill.description?.let { description ->
            lines += ""
            lines += description
        }

        lines += ""
        lines += "Send `/${skill.slashName} <message>` to use this skill."
        return lines.joinToString(separator = "\n")
    }

    fun matching(
        query: String,
        suggestions: List<SkillSlashSuggestion>,
    ): List<SkillSlashSuggestion> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return suggestions

        return suggestions.filter { suggestion ->
            suggestion.slashName.contains(trimmed, ignoreCase = true) ||
                suggestion.name.contains(trimmed, ignoreCase = true) ||
                suggestion.category?.contains(trimmed, ignoreCase = true) == true ||
                suggestion.description?.contains(trimmed, ignoreCase = true) == true
        }
    }

    fun message(
        suggestions: List<SkillSlashSuggestion>,
        query: String,
    ): String {
        val trimmed = query.trim()
        if (suggestions.isEmpty()) return "No skills are configured on the server."

        val matches = matching(trimmed, suggestions)
        if (matches.isEmpty()) return "No skills match `$trimmed`."

        val heading = if (trimmed.isEmpty()) {
            "Available skills:"
        } else {
            "Skills matching `$trimmed`:"
        }
        val grouped = matches.groupBy { suggestion -> suggestion.category ?: "Uncategorized" }
        val sections = grouped.entries
            .sortedWith { lhs, rhs -> lhs.key.compareTo(rhs.key, ignoreCase = true) }
            .joinToString(separator = "\n\n") { (category, skills) ->
                val rows = skills
                    .sortedWith { lhs, rhs -> lhs.name.compareTo(rhs.name, ignoreCase = true) }
                    .joinToString(separator = "\n") { suggestion ->
                        val description = suggestion.description
                        if (!description.isNullOrEmpty()) {
                            "- `${suggestion.slashName}` - **${suggestion.name}** - $description"
                        } else {
                            "- `${suggestion.slashName}` - **${suggestion.name}**"
                        }
                    }

                "### $category\n$rows"
            }

        return "$heading\n\n$sections"
    }
}

@Serializable(with = SkillDetailResponseSerializer::class)
data class SkillDetailResponse(
    val name: String? = null,
    val content: String? = null,
    val linkedFiles: List<String>? = null,
)

internal object SkillDetailResponseSerializer :
    TolerantObjectSerializer<SkillDetailResponse>("SkillDetailResponse") {

    override fun fromJson(json: Json, obj: JsonObject): SkillDetailResponse =
        SkillDetailResponse(
            name = decodeOptionalString(json, obj, "name"),
            content = decodeOptionalString(json, obj, "content"),
            linkedFiles = decodeLinkedFiles(obj.presentElement("linked_files", "linkedFiles")),
        )

    override fun toJson(json: Json, value: SkillDetailResponse): JsonObject = buildJsonObject {
        value.name?.let { put("name", JsonPrimitive(it)) }
        value.content?.let { put("content", JsonPrimitive(it)) }
        value.linkedFiles?.let { files ->
            put("linked_files", JsonArray(files.map { JsonPrimitive(it) }))
        }
    }
}

@Serializable
data class SkillLinkedFileResponse(
    val content: String? = null,
    val path: String? = null,
)

private fun decodeLinkedFiles(element: JsonElement?): List<String>? {
    element ?: return null
    val raw = JSONValue(element)

    val objectValue = raw.objectValue
    if (objectValue != null && objectValue.values.all { it.string != null }) {
        return objectValue.keys.sorted().takeIf { it.isNotEmpty() }
    }

    return linkedFileNames(raw)
        .toSet()
        .sorted()
        .takeIf { it.isNotEmpty() }
}

private fun linkedFileNames(value: JSONValue): List<String> {
    value.string?.let { name ->
        val trimmed = name.trim()
        return if (trimmed.isEmpty()) emptyList() else listOf(trimmed)
    }

    value.arrayValue?.let { values ->
        return values.flatMap(::linkedFileNames)
    }

    value.objectValue?.let { objectValue ->
        return objectValue.flatMap { (key, nestedValue) ->
            val nestedArray = nestedValue.arrayValue
            when {
                nestedArray != null -> nestedArray.flatMap(::linkedFileNames)
                nestedValue.string != null -> listOf(key)
                else -> linkedFileNames(nestedValue)
            }
        }
    }

    return emptyList()
}

private fun decodeOptionalString(
    json: Json,
    obj: JsonObject,
    vararg keys: String,
): String? = obj.presentElement(*keys)?.let { json.decodeFromJsonElement(String.serializer(), it) }
