package io.github.jcartu.hermex.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject

@Serializable
enum class MemorySection(val rawValue: String) {
    @SerialName("memory")
    MEMORY("memory"),

    @SerialName("user")
    USER("user"),

    @SerialName("soul")
    SOUL("soul"),
    ;

    val id: String
        get() = rawValue

    companion object {
        fun fromRawValue(rawValue: String): MemorySection? =
            values().firstOrNull { it.rawValue == rawValue }
    }
}

@Serializable(with = MemoryResponseSerializer::class)
data class MemoryResponse(
    val memory: String? = null,
    val user: String? = null,
    val soul: String? = null,
    val memoryPath: String? = null,
    val userPath: String? = null,
    val soulPath: String? = null,
    val memoryMtime: Double? = null,
    val userMtime: Double? = null,
    val soulMtime: Double? = null,
    val projectContext: String? = null,
    val projectContextName: String? = null,
    val projectContextPath: String? = null,
    val projectContextWorkspace: String? = null,
    val projectContextMtime: Double? = null,
    val projectContextShadowed: Boolean? = null,
    val externalNotesEnabled: Boolean? = null,
)

internal object MemoryResponseSerializer : TolerantObjectSerializer<MemoryResponse>("MemoryResponse") {
    override fun fromJson(json: Json, obj: JsonObject): MemoryResponse =
        MemoryResponse(
            memory = decodeOptionalString(json, obj, "memory"),
            user = decodeOptionalString(json, obj, "user"),
            soul = decodeOptionalString(json, obj, "soul"),
            memoryPath = decodeOptionalString(json, obj, "memory_path", "memoryPath"),
            userPath = decodeOptionalString(json, obj, "user_path", "userPath"),
            soulPath = decodeOptionalString(json, obj, "soul_path", "soulPath"),
            memoryMtime = obj.lossyDouble("memory_mtime", "memoryMtime"),
            userMtime = obj.lossyDouble("user_mtime", "userMtime"),
            soulMtime = obj.lossyDouble("soul_mtime", "soulMtime"),
            projectContext = decodeOptionalString(json, obj, "project_context", "projectContext"),
            projectContextName = decodeOptionalString(
                json,
                obj,
                "project_context_name",
                "projectContextName",
            ),
            projectContextPath = decodeOptionalString(
                json,
                obj,
                "project_context_path",
                "projectContextPath",
            ),
            projectContextWorkspace = decodeOptionalString(
                json,
                obj,
                "project_context_workspace",
                "projectContextWorkspace",
            ),
            projectContextMtime = obj.lossyDouble("project_context_mtime", "projectContextMtime"),
            // Upstream (routes.py @ 312d3fab, verified live 2026-07-03) returns a *list* of
            // shadowed-file objects here; the API docs describe a boolean flag. Accept both:
            // `true` or a non-empty list means the active document shadows another file.
            projectContextShadowed = decodeProjectContextShadowed(obj),
            externalNotesEnabled = strictBooleanOrNull(
                obj.presentElement("external_notes_enabled", "externalNotesEnabled"),
            ),
        )

    override fun toJson(json: Json, value: MemoryResponse): JsonObject = buildJsonObject {
        value.memory?.let { put("memory", JsonPrimitive(it)) }
        value.user?.let { put("user", JsonPrimitive(it)) }
        value.soul?.let { put("soul", JsonPrimitive(it)) }
        value.memoryPath?.let { put("memory_path", JsonPrimitive(it)) }
        value.userPath?.let { put("user_path", JsonPrimitive(it)) }
        value.soulPath?.let { put("soul_path", JsonPrimitive(it)) }
        value.memoryMtime?.let { put("memory_mtime", JsonPrimitive(it)) }
        value.userMtime?.let { put("user_mtime", JsonPrimitive(it)) }
        value.soulMtime?.let { put("soul_mtime", JsonPrimitive(it)) }
        value.projectContext?.let { put("project_context", JsonPrimitive(it)) }
        value.projectContextName?.let { put("project_context_name", JsonPrimitive(it)) }
        value.projectContextPath?.let { put("project_context_path", JsonPrimitive(it)) }
        value.projectContextWorkspace?.let {
            put("project_context_workspace", JsonPrimitive(it))
        }
        value.projectContextMtime?.let { put("project_context_mtime", JsonPrimitive(it)) }
        value.projectContextShadowed?.let {
            put("project_context_shadowed", JsonPrimitive(it))
        }
        value.externalNotesEnabled?.let { put("external_notes_enabled", JsonPrimitive(it)) }
    }
}

@Serializable(with = MemoryWriteResponseSerializer::class)
data class MemoryWriteResponse(
    val ok: Boolean? = null,
    val section: MemorySection? = null,
    val path: String? = null,
    val error: String? = null,
)

internal object MemoryWriteResponseSerializer :
    TolerantObjectSerializer<MemoryWriteResponse>("MemoryWriteResponse") {

    override fun fromJson(json: Json, obj: JsonObject): MemoryWriteResponse =
        MemoryWriteResponse(
            ok = decodeOptionalBoolean(json, obj, "ok"),
            section = decodeOptionalString(json, obj, "section")?.let { MemorySection.fromRawValue(it) },
            path = decodeOptionalString(json, obj, "path"),
            error = decodeOptionalString(json, obj, "error"),
        )

    override fun toJson(json: Json, value: MemoryWriteResponse): JsonObject = buildJsonObject {
        value.ok?.let { put("ok", JsonPrimitive(it)) }
        value.section?.let { put("section", JsonPrimitive(it.rawValue)) }
        value.path?.let { put("path", JsonPrimitive(it)) }
        value.error?.let { put("error", JsonPrimitive(it)) }
    }
}

private fun decodeProjectContextShadowed(obj: JsonObject): Boolean? {
    val element = obj.presentElement("project_context_shadowed", "projectContextShadowed")
        ?: return null
    strictBooleanOrNull(element)?.let { return it }
    return (element as? JsonArray)?.isNotEmpty()
}

private fun strictBooleanOrNull(element: JsonElement?): Boolean? {
    val primitive = element as? JsonPrimitive ?: return null
    if (primitive is JsonNull || primitive.isString) return null
    return primitive.booleanOrNull
}

private fun decodeOptionalString(
    json: Json,
    obj: JsonObject,
    vararg keys: String,
): String? = obj.presentElement(*keys)?.let { json.decodeFromJsonElement(String.serializer(), it) }

private fun decodeOptionalBoolean(
    json: Json,
    obj: JsonObject,
    vararg keys: String,
): Boolean? = obj.presentElement(*keys)?.let { json.decodeFromJsonElement(Boolean.serializer(), it) }
