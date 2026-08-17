package io.github.jcartu.hermex.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

// Port of `GitWorkspace.swift`.
//
// Tolerant, read-only models for the server "workspace git" API (issue #312, Slice A).
//
// Every field is optional and unknown keys are ignored so the app never crashes on a
// field the server adds or renames (hard rule #3). The shared `APIClient` decoder uses
// `.convertFromSnakeCase`, so snake_case JSON keys (`is_git`, `old_path`, `too_large`, …)
// map onto these camelCase properties automatically; camelCase spellings are accepted
// as fallbacks.
//
// Endpoint contract (verified against `.codex-tmp/hermes-webui/api/workspace_git.py`):
// - GET /api/git-info  → { "git": {...} | null }
// - GET /api/git/status → { "git": {...} }   (non-repo: is_git=false, HTTP 200)
// - GET /api/git/branches → { "branches": {...} } (non-repo: HTTP 400 error envelope)
// - GET /api/git/diff → { "diff": {...} }    (non-repo / missing path: HTTP 400)

// MARK: - git-info (lightweight badge data)

@Serializable
data class GitInfoResponse(
    /** `null` when the session workspace is not a git repository. */
    val git: GitInfo? = null,
)

@Serializable
data class GitInfo(
    val branch: String? = null,
    val dirty: Int? = null,
    val modified: Int? = null,
    val untracked: Int? = null,
    val ahead: Int? = null,
    val behind: Int? = null,
    @SerialName("is_git") val isGit: Boolean? = null,
)

// MARK: - git/status (the status sheet's source of truth)

@Serializable
data class GitStatusResponse(
    val git: GitStatus? = null,
)

@Serializable
data class GitStatus(
    /** `false` for a non-repo workspace (still an HTTP 200 response). */
    @SerialName("is_git") val isGit: Boolean? = null,
    val branch: String? = null,
    val upstream: String? = null,
    val ahead: Int? = null,
    val behind: Int? = null,
    val totals: GitTotals? = null,
    val files: List<GitFile>? = null,
    /** `true` when the file list was capped (server limit is 500 changed files). */
    val truncated: Boolean? = null,
) {
    /**
     * Changed files excluding ignored entries (e.g. `.DS_Store`), which the server
     * includes in `files[]` but excludes from `totals.changed`.
     */
    val trackedFiles: List<GitFile>
        get() = files.orEmpty().filter { !it.isIgnoredFile }

    /**
     * Changed-file count, preferring the server's `totals.changed` and falling back to
     * the non-ignored file count so the header never disagrees with the list.
     */
    val changedCount: Int
        get() = totals?.changed ?: trackedFiles.size

    /** Total additions/deletions across non-ignored files. */
    val totalAdditions: Int
        get() = trackedFiles.sumOf { it.additions ?: 0 }

    val totalDeletions: Int
        get() = trackedFiles.sumOf { it.deletions ?: 0 }
}

@Serializable
data class GitTotals(
    val changed: Int? = null,
    val staged: Int? = null,
    val unstaged: Int? = null,
    val untracked: Int? = null,
    val conflicts: Int? = null,
)

/**
 * One changed file from `git/status`. Decoded tolerantly via [GitFileSerializer]
 * (Swift custom `init(from:)`); the stable path identity mirrors `Identifiable`.
 */
@Serializable(with = GitFileSerializer::class)
data class GitFile(
    val id: String,
    val path: String? = null,
    val oldPath: String? = null,
    val workspacePath: String? = null,
    /**
     * Raw git status code (`M/A/D/R/C/T/U`, `"??"`, two-char conflicts, or `"Ignored"`).
     * Prefer the booleans below for display; treat this as a fallback label.
     */
    val status: String? = null,
    val staged: Boolean? = null,
    val unstaged: Boolean? = null,
    val untracked: Boolean? = null,
    val ignored: Boolean? = null,
    val conflict: Boolean? = null,
    val additions: Int? = null,
    val deletions: Int? = null,
    val binary: Boolean? = null,
) {
    companion object {
        /** First non-blank of path → workspacePath → oldPath (Swift `private static stablePath`). */
        internal fun stablePath(path: String?, workspacePath: String?, oldPath: String?): String? =
            listOfNotNull(path, workspacePath, oldPath)
                .map { it.trim() }
                .firstOrNull { it.isNotEmpty() }
    }

    /**
     * A normalized change kind derived from the booleans first (the reliable signal),
     * falling back to the raw `status` code. UI maps this to a localized chip + colour.
     */
    enum class ChangeKind {
        CONFLICT,
        UNTRACKED,
        ADDED,
        DELETED,
        RENAMED,
        MODIFIED,
        IGNORED,
        UNKNOWN,
    }

    val changeKind: ChangeKind
        get() {
            if (conflict == true) return ChangeKind.CONFLICT
            if (isIgnoredFile) return ChangeKind.IGNORED
            if (untracked == true) return ChangeKind.UNTRACKED

            return when (status.orEmpty().uppercase().firstOrNull()) {
                'A' -> ChangeKind.ADDED
                'D' -> ChangeKind.DELETED
                'R' -> ChangeKind.RENAMED
                'M', 'T' -> ChangeKind.MODIFIED
                else ->
                    // A tracked change with an unrecognized code is still a modification.
                    if (staged == true || unstaged == true) ChangeKind.MODIFIED else ChangeKind.UNKNOWN
            }
        }

    /** Last path component, e.g. `ContentView.swift`. */
    val fileName: String
        get() = displayPath.split('/').lastOrNull { it.isNotEmpty() } ?: displayPath

    /** Parent directory shown as secondary text, or `null` at the repo root. */
    val parentDirectory: String?
        get() {
            val parts = displayPath.split('/').filter { it.isNotEmpty() }
            if (parts.size <= 1) return null
            return parts.dropLast(1).joinToString("/")
        }

    /** The diff query uses staged content for staged-only changes, unstaged otherwise. */
    val preferredDiffKind: String
        get() = if (staged == true && unstaged != true) "staged" else "unstaged"

    val displayPath: String
        get() {
            val trimmed = (path ?: workspacePath ?: "").trim()
            return if (trimmed.isEmpty()) (oldPath ?: "") else trimmed
        }

    val isIgnoredFile: Boolean
        get() = ignored == true || (status ?: "").equals("Ignored", ignoreCase = true)
}

/** Tolerant decoding for [GitFile] — port of the Swift custom `init(from:)`. */
internal object GitFileSerializer : TolerantObjectSerializer<GitFile>("GitFile") {
    override fun fromJson(json: Json, obj: JsonObject): GitFile {
        val path = obj.lossyString("path")
        val workspacePath = obj.lossyString("workspace_path", "workspacePath")
        val oldPath = obj.lossyString("old_path", "oldPath")

        return GitFile(
            id = GitFile.stablePath(path, workspacePath, oldPath) ?: UUID.randomUUID().toString(),
            path = path,
            oldPath = oldPath,
            workspacePath = workspacePath,
            status = obj.lossyString("status"),
            staged = obj.lossyBool("staged"),
            unstaged = obj.lossyBool("unstaged"),
            untracked = obj.lossyBool("untracked"),
            ignored = obj.lossyBool("ignored"),
            conflict = obj.lossyBool("conflict"),
            additions = obj.lossyInt("additions"),
            deletions = obj.lossyInt("deletions"),
            binary = obj.lossyBool("binary"),
        )
    }

    /** Encodes the decoded fields (snake_case, nulls omitted); `id` is derived, not on the wire. */
    override fun toJson(json: Json, value: GitFile): JsonObject = buildJsonObject {
        value.path?.let { put("path", it) }
        value.oldPath?.let { put("old_path", it) }
        value.workspacePath?.let { put("workspace_path", it) }
        value.status?.let { put("status", it) }
        value.staged?.let { put("staged", it) }
        value.unstaged?.let { put("unstaged", it) }
        value.untracked?.let { put("untracked", it) }
        value.ignored?.let { put("ignored", it) }
        value.conflict?.let { put("conflict", it) }
        value.additions?.let { put("additions", it) }
        value.deletions?.let { put("deletions", it) }
        value.binary?.let { put("binary", it) }
    }
}

// MARK: - git/branches (decoded + tested now; interactive picker is Slice B)

@Serializable
data class GitBranchesResponse(
    val branches: GitBranches? = null,
)

@Serializable
data class GitBranches(
    @SerialName("is_git") val isGit: Boolean? = null,
    val current: String? = null,
    val detached: Boolean? = null,
    val head: String? = null,
    val local: List<GitBranchRef>? = null,
    val remote: List<GitBranchRef>? = null,
    val upstream: String? = null,
    val ahead: Int? = null,
    val behind: Int? = null,
)

@Serializable
data class GitBranchRef(
    val name: String? = null,
    val sha: String? = null,
    val updated: Int? = null,
    @SerialName("updated_relative") val updatedRelative: String? = null,
    val author: String? = null,
    val subject: String? = null,
    val upstream: String? = null,
    val ahead: Int? = null,
    val behind: Int? = null,
)

/** Swift `enum GitBranchMode: String` — `rawValue` keeps the wire spelling. */
@Serializable
enum class GitBranchMode(val rawValue: String) {
    @SerialName("local") LOCAL("local"),
    @SerialName("remote") REMOTE("remote"),
}

data class GitCheckoutTarget(
    val ref: String,
    val mode: GitBranchMode,
    var newBranch: String? = null,
    var track: Boolean = false,
) {
    val id: String
        get() = "${mode.rawValue}:$ref:${newBranch ?: ""}"

    val displayName: String
        get() = newBranch ?: ref
}

@Serializable
data class GitRemoteActionResponse(
    val ok: Boolean? = null,
    val message: String? = null,
    val status: GitStatus? = null,
)

/**
 * Both checkout endpoints currently return `status`; `git` is retained as a tolerant
 * alias for servers that shipped the earlier documented response name.
 */
@Serializable
data class GitCheckoutResponse(
    val ok: Boolean? = null,
    val message: String? = null,
    val status: GitStatus? = null,
    val git: GitStatus? = null,
    val branches: GitBranches? = null,
    @SerialName("current_branch") val currentBranch: String? = null,
    @SerialName("stash_name") val stashName: String? = null,
    val stashed: Boolean? = null,
    @SerialName("restored_stash") val restoredStash: GitRestoredStash? = null,
    @SerialName("restore_failed") val restoreFailed: Boolean? = null,
    @SerialName("restore_error") val restoreError: String? = null,
    @SerialName("restore_stash") val restoreStash: GitRestoredStash? = null,
) {
    val resolvedStatus: GitStatus?
        get() = status ?: git
}

@Serializable
data class GitRestoredStash(
    val ref: String? = null,
    val branch: String? = null,
    val message: String? = null,
)

// MARK: - git stage/unstage/discard/commit (issue #315, Slice C)

/**
 * Response for `stage` / `unstage` / `discard`, which return the refreshed status under
 * the `git` key (the commit endpoints return it under `status` instead — see below).
 */
@Serializable
data class GitMutationResponse(
    val ok: Boolean? = null,
    val git: GitStatus? = null,
) {
    val resolvedStatus: GitStatus?
        get() = git
}

/**
 * Response for `commit` (`{ok,commit,status}`) and `commit-selected`
 * (`{ok,commit,paths,status}`). Tolerant: `status` is decoded from either key.
 */
@Serializable
data class GitCommitResponse(
    val ok: Boolean? = null,
    val commit: String? = null,
    val paths: List<String>? = null,
    val status: GitStatus? = null,
    val git: GitStatus? = null,
) {
    val resolvedStatus: GitStatus?
        get() = status ?: git

    /** Short SHA produced by the commit, trimmed for display. */
    val shortSHA: String?
        get() {
            val trimmed = commit?.trim()
            return if (!trimmed.isNullOrEmpty()) trimmed else null
        }
}

/** Response for the (ungated) `commit-message` / `commit-message-selected` endpoints. */
@Serializable
data class GitCommitMessageResponse(
    val ok: Boolean? = null,
    val message: String? = null,
    /** `true` when the diff exceeded the server's 64 KiB prompt limit, so the generated message may be partial. */
    val truncated: Boolean? = null,
)

// MARK: - git/diff (per-file unified diff)

@Serializable
data class GitDiffResponse(
    val diff: GitDiff? = null,
)

@Serializable
data class GitDiff(
    val path: String? = null,
    val kind: String? = null,
    val binary: Boolean? = null,
    /** `true` when the diff exceeded the server's 512 KiB cap; `diff` text is then empty. */
    @SerialName("too_large") val tooLarge: Boolean? = null,
    val additions: Int? = null,
    val deletions: Int? = null,
    /** Unified diff text. Empty for binary or too-large diffs. */
    val diff: String? = null,
)
