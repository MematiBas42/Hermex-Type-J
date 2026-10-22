package io.github.jcartu.hermex.net

/**
 * New routes from `APIClient+Workspace.swift`, isolated behind Endpoint.Custom
 * so the shared sealed endpoint table remains source-compatible with peers.
 */
internal object WorkspaceEndpoints {
    /** Port of `Endpoint.workspaceAdd`. */
    fun add() = Endpoint.Custom("/api/workspaces/add")

    /** Port of `Endpoint.workspaceRemove`. */
    fun remove() = Endpoint.Custom("/api/workspaces/remove")

    /** Port of `Endpoint.workspaceRename`. */
    fun rename() = Endpoint.Custom("/api/workspaces/rename")

    /** Port of `Endpoint.workspaceReorder`. */
    fun reorder() = Endpoint.Custom("/api/workspaces/reorder")

    /** Custom endpoint form of `Endpoint.rawFile(sessionID:path:)` for new binary reads. */
    fun rawFile(sessionId: String, path: String) = Endpoint.Custom(
        fixedPath = "/api/file/raw",
        query = listOf("session_id" to sessionId, "path" to path),
    )

    /** Port of `Endpoint.media(sessionID:path:)`, new to Android. */
    fun media(sessionId: String, path: String) = Endpoint.Custom(
        fixedPath = "/api/media",
        query = listOf("session_id" to sessionId, "path" to path),
    )
}
