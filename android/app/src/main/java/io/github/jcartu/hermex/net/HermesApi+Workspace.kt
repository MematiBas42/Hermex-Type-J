package io.github.jcartu.hermex.net

import io.github.jcartu.hermex.model.AddWorkspaceRequest
import io.github.jcartu.hermex.model.RemoveWorkspaceRequest
import io.github.jcartu.hermex.model.RenameWorkspaceRequest
import io.github.jcartu.hermex.model.ReorderWorkspacesRequest
import io.github.jcartu.hermex.model.WorkspaceMutationResponse

/** The Quick Look/document-preview limit used by upstream (#1012/#1112). */
const val WORKSPACE_PREVIEW_MAX_BYTES: Long = 25L * 1024L * 1024L

/** Port of `BotArtifactFailure.tooLarge` used by `APIClient.sendBoundedData`. */
class WorkspacePreviewTooLargeException(
    val limitBytes: Long = WORKSPACE_PREVIEW_MAX_BYTES,
) : Exception("This file is too large to preview on this device (25 MB maximum).")

/** Port of `APIClient.addWorkspace(path:name:create:)` in `APIClient+Workspace.swift`. */
suspend fun HermesApi.addWorkspace(
    path: String,
    name: String? = null,
    create: Boolean? = null,
): WorkspaceMutationResponse =
    post(WorkspaceEndpoints.add(), AddWorkspaceRequest(path, name, create))

/** Port of `APIClient.removeWorkspace(path:)`. */
suspend fun HermesApi.removeWorkspace(path: String): WorkspaceMutationResponse =
    post(WorkspaceEndpoints.remove(), RemoveWorkspaceRequest(path))

/** Port of `APIClient.renameWorkspace(path:name:)`. */
suspend fun HermesApi.renameWorkspace(path: String, name: String): WorkspaceMutationResponse =
    post(WorkspaceEndpoints.rename(), RenameWorkspaceRequest(path, name))

/** Port of `APIClient.reorderWorkspaces(paths:)`. */
suspend fun HermesApi.reorderWorkspaces(paths: List<String>): WorkspaceMutationResponse =
    post(WorkspaceEndpoints.reorder(), ReorderWorkspacesRequest(paths))

/** Port of `APIClient.rawFileData(sessionID:path:)`. */
suspend fun HermesApi.rawFileData(sessionId: String, path: String): ByteArray =
    getBytes(WorkspaceEndpoints.rawFile(sessionId, path))

/** Port of `APIClient.rawFilePreviewData(sessionID:path:)`; reads at most 25 MB. */
suspend fun HermesApi.rawFilePreviewData(
    sessionId: String,
    path: String,
    limitBytes: Long = WORKSPACE_PREVIEW_MAX_BYTES,
): ByteArray = getBytes(WorkspaceEndpoints.rawFile(sessionId, path), limitBytes)

/** Port of `APIClient.mediaData(sessionID:path:)`. */
suspend fun HermesApi.mediaData(sessionId: String, path: String): ByteArray =
    getBytes(WorkspaceEndpoints.media(sessionId, path))

/** Bounded media read used by document/attachment previews. */
suspend fun HermesApi.mediaPreviewData(
    sessionId: String,
    path: String,
    limitBytes: Long = WORKSPACE_PREVIEW_MAX_BYTES,
): ByteArray = getBytes(WorkspaceEndpoints.media(sessionId, path), limitBytes)
