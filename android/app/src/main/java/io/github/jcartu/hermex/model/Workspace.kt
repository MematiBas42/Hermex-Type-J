package io.github.jcartu.hermex.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.util.UUID

@Serializable
data class WorkspacesResponse(
    val workspaces: List<WorkspaceRoot>? = null,
    val last: String? = null,
)

@Serializable
data class WorkspaceSuggestionsResponse(
    val suggestions: List<String>? = null,
    val prefix: String? = null,
)

@Serializable(with = WorkspaceRootSerializer::class)
data class WorkspaceRoot(
    val path: String? = null,
    val name: String? = null,
)

internal object WorkspaceRootSerializer : TolerantObjectSerializer<WorkspaceRoot>("WorkspaceRoot") {
    override fun deserialize(decoder: Decoder): WorkspaceRoot {
        val input = decoder as? JsonDecoder
            ?: throw SerializationException("WorkspaceRoot supports JSON decoding only")
        return when (val element = input.decodeJsonElement()) {
            is JsonObject -> fromJson(input.json, element)
            is JsonPrimitive -> {
                if (!element.isString) {
                    throw SerializationException("Expected JSON string or object for WorkspaceRoot")
                }
                WorkspaceRoot(path = element.content)
            }
            else -> throw SerializationException("Expected JSON string or object for WorkspaceRoot")
        }
    }

    override fun fromJson(json: Json, obj: JsonObject): WorkspaceRoot =
        WorkspaceRoot(
            path = decodeOptionalString(json, obj, "path"),
            name = decodeOptionalString(json, obj, "name"),
        )

    override fun toJson(json: Json, value: WorkspaceRoot): JsonObject = buildJsonObject {
        value.path?.let { put("path", JsonPrimitive(it)) }
        value.name?.let { put("name", JsonPrimitive(it)) }
    }
}

/**
 * Response shape shared by the four workspace-registry mutation routes
 * (`/api/workspaces/add|remove|rename|reorder`). Verified against upstream
 * `_handle_workspace_*` handlers: `{"ok": true, "workspaces": [...]}` on success.
 * These routes are undocumented (not on the official docs site), so every field
 * stays optional and callers must tolerate a missing `workspaces` echo.
 */
@Serializable
data class WorkspaceMutationResponse(
    val ok: Boolean? = null,
    val workspaces: List<WorkspaceRoot>? = null,
    val error: String? = null,
)

/**
 * Surfaced when a mutation route answers with HTTP success but reports
 * `ok: false` in the body. Upstream signals failure via non-2xx today, but
 * these routes are undocumented, so an explicit body-level failure must not
 * be presented as a success. Reuses the phrasing (and localization keys) of
 * `APIError`'s 400 handling.
 */
data class WorkspaceMutationRejection(
    val serverMessage: String?,
) : Exception(workspaceMutationRejectionDescription(serverMessage)) {
    val errorDescription: String?
        get() = workspaceMutationRejectionDescription(serverMessage)
}

@Serializable
data class AddWorkspaceRequest(
    val path: String,
    val name: String?,
    val create: Boolean?,
)

@Serializable
data class RemoveWorkspaceRequest(
    val path: String,
)

@Serializable
data class RenameWorkspaceRequest(
    val path: String,
    val name: String,
)

@Serializable
data class ReorderWorkspacesRequest(
    val paths: List<String>,
)

@Serializable
data class DirectoryListResponse(
    val entries: List<WorkspaceEntry>? = null,
    val path: String? = null,
    val workspace: String? = null,
    val error: String? = null,
)

@Serializable(with = WorkspaceEntrySerializer::class)
data class WorkspaceEntry(
    val name: String? = null,
    val path: String? = null,
    val type: String? = null,
    val size: Int? = null,
    val modified: Double? = null,
    val isDirectory: Boolean? = null,
) {
    val id: String
        get() = path ?: name ?: UUID.randomUUID().toString()

    val isBrowsableDirectory: Boolean
        get() = isDirectory == true || type == "dir"
}

internal object WorkspaceEntrySerializer :
    TolerantObjectSerializer<WorkspaceEntry>("WorkspaceEntry") {

    override fun fromJson(json: Json, obj: JsonObject): WorkspaceEntry =
        WorkspaceEntry(
            name = decodeOptionalString(json, obj, "name"),
            path = decodeOptionalString(json, obj, "path"),
            type = decodeOptionalString(json, obj, "type"),
            size = decodeOptionalInt(json, obj, "size"),
            modified = decodeOptionalDouble(json, obj, "modified"),
            isDirectory = decodeOptionalBoolean(
                json,
                obj,
                "is_directory",
                "isDirectory",
                "is_dir",
                "isDir",
            ),
        )

    override fun toJson(json: Json, value: WorkspaceEntry): JsonObject = buildJsonObject {
        value.name?.let { put("name", JsonPrimitive(it)) }
        value.path?.let { put("path", JsonPrimitive(it)) }
        value.type?.let { put("type", JsonPrimitive(it)) }
        value.size?.let { put("size", JsonPrimitive(it)) }
        value.modified?.let { put("modified", JsonPrimitive(it)) }
        value.isDirectory?.let { put("is_directory", JsonPrimitive(it)) }
    }
}

@Serializable
data class FileResponse(
    val content: String? = null,
    val path: String? = null,
    val name: String? = null,
    val language: String? = null,
    val size: Int? = null,
    val lines: Int? = null,
    val error: String? = null,
)

private fun workspaceMutationRejectionDescription(serverMessage: String?): String =
    if (!serverMessage.isNullOrEmpty()) {
        "The server rejected the request: $serverMessage"
    } else {
        "The server rejected the request."
    }

private fun decodeOptionalString(
    json: Json,
    obj: JsonObject,
    vararg keys: String,
): String? = obj.presentElement(*keys)?.let { json.decodeFromJsonElement(String.serializer(), it) }

private fun decodeOptionalInt(
    json: Json,
    obj: JsonObject,
    vararg keys: String,
): Int? = obj.presentElement(*keys)?.let { json.decodeFromJsonElement(Int.serializer(), it) }

private fun decodeOptionalDouble(
    json: Json,
    obj: JsonObject,
    vararg keys: String,
): Double? = obj.presentElement(*keys)?.let { json.decodeFromJsonElement(Double.serializer(), it) }

private fun decodeOptionalBoolean(
    json: Json,
    obj: JsonObject,
    vararg keys: String,
): Boolean? = obj.presentElement(*keys)?.let { json.decodeFromJsonElement(Boolean.serializer(), it) }
