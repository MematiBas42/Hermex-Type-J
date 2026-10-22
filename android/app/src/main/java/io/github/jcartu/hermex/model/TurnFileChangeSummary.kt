package io.github.jcartu.hermex.model

// Port of `TurnFileChangeSummary.swift`.
//
// In-chat "N files changed" recap (issue #316, Workspace git Slice D).
//
// The recap's file list is derived from an assistant turn's *tool-call metadata* (which
// file-mutating tools ran, and on which paths) rather than a `git/status` snapshot — a
// snapshot can't isolate a single turn's edits from accumulated uncommitted/external
// changes. Each derived path is then joined to `git/status` for the `+N −M` line counts
// and the status chip. No new endpoint; read-only over data the chat already has.
//
// Mirrors the webui reference aggregation in `.codex-tmp/hermes-webui/static/workspace.js`
// (mutation tool-name set, path-arg keys, path normalization, ignore filter).

/** A single file changed during one assistant turn. */
data class TurnFileChange(
    /** Normalized, workspace-relative (or absolute, if the tool reported one) path. */
    val path: String,
    /** Added lines, from the matched `git/status` entry (`0` when there is no net change). */
    val additions: Int,
    /** Removed lines, from the matched `git/status` entry (`0` when there is no net change). */
    val deletions: Int,
    /** What the tool did to the file (drives the streaming action label / fallback chip). */
    val action: Action,
    /**
     * Status chip kind — the matched `git/status` kind when available, else derived
     * from [action].
     */
    val changeKind: GitFile.ChangeKind,
    /**
     * The matched `git/status` file, when one exists. `null` for a tool-touched path with
     * no net git change (the row then shows `+0 −0` and is not openable in the diff sheet).
     */
    val gitFile: GitFile?,
) {
    /** What the tool did to the file (Swift `enum Action: Equatable`). */
    enum class Action {
        EDITED,
        ADDED,
        DELETED,
        RENAMED;

        /** Fallback chip kind when the path didn't match a `git/status` entry. */
        val changeKind: GitFile.ChangeKind
            get() = when (this) {
                EDITED -> GitFile.ChangeKind.MODIFIED
                ADDED -> GitFile.ChangeKind.ADDED
                DELETED -> GitFile.ChangeKind.DELETED
                RENAMED -> GitFile.ChangeKind.RENAMED
            }
    }

    val id: String
        get() = path

    /** Last path component, e.g. `ContentView.swift`. */
    val fileName: String
        get() = path.split('/').lastOrNull { it.isNotEmpty() } ?: path
}

/**
 * Aggregated per-turn file-change recap. An empty [changes] array means "show nothing"
 * (a turn that changed no files, or a non-git workspace).
 */
data class TurnFileChangeSummary(
    val changes: List<TurnFileChange>,
) {
    val fileCount: Int
        get() = changes.size

    val hasChanges: Boolean
        get() = changes.isNotEmpty()

    val totalAdditions: Int
        get() = changes.sumOf { it.additions }

    val totalDeletions: Int
        get() = changes.sumOf { it.deletions }

    /** `git/status` files backing the changes, for opening the per-turn diff sheet. */
    val diffFiles: List<GitFile>
        get() = changes.mapNotNull { it.gitFile }

    /** Compact composer-capsule title, e.g. "1 change" / "3 changes". */
    val capsuleTitle: String
        get() = if (fileCount == 1) "1 change" else "$fileCount changes"

    /** Sheet/header title, e.g. "1 file changed" / "3 files changed". */
    val filesChangedTitle: String
        get() = if (fileCount == 1) "1 file changed" else "$fileCount files changed"

    companion object {
        val empty: TurnFileChangeSummary = TurnFileChangeSummary(changes = emptyList())
    }
}

/**
 * Pure aggregation of a turn's file changes from tool-call metadata, joined to git status.
 * Stateless and deterministic so it can be unit-tested in isolation.
 */
object TurnFileChangeAggregator {

    /** One extracted (path, action) pair — the Swift `(path: String, action: TurnFileChange.Action)` tuple. */
    private data class Candidate(val path: String, val action: TurnFileChange.Action)

    /**
     * Build the recap for one assistant turn.
     *
     * @param toolCalls the turn's tool calls (live during streaming, or the archived group).
     * @param status the latest `git/status`, joined for line counts and chips (`null` ok).
     */
    fun summarize(
        toolCalls: List<ToolCall>,
        status: GitStatus?,
        rowPath: (String) -> String = { it },
    ): TurnFileChangeSummary {
        val orderedPaths = mutableListOf<String>()
        val actionByPath = HashMap<String, TurnFileChange.Action>()

        for (toolCall in toolCalls) {
            for (candidate in candidates(toolCall)) {
                val path = normalize(candidate.path)?.let(rowPath) ?: continue

                val existing = actionByPath[path]
                if (existing != null) {
                    // A path edited and (re)created in the same turn reads better as the
                    // stronger action; never let a later plain "edit" downgrade it.
                    if (existing == TurnFileChange.Action.EDITED &&
                        candidate.action != TurnFileChange.Action.EDITED
                    ) {
                        actionByPath[path] = candidate.action
                    }
                } else {
                    orderedPaths.add(path)
                    actionByPath[path] = candidate.action
                }
            }
        }

        val trackedFiles = status?.trackedFiles ?: emptyList()
        val changes = orderedPaths.map { path ->
            val action = actionByPath[path] ?: TurnFileChange.Action.EDITED
            val match = matchingFile(path, trackedFiles)
            TurnFileChange(
                path = path,
                additions = match?.additions ?: 0,
                deletions = match?.deletions ?: 0,
                action = action,
                changeKind = match?.changeKind ?: action.changeKind,
                gitFile = match,
            )
        }

        return TurnFileChangeSummary(changes)
    }

    // MARK: - Tool-call metadata extraction

    /**
     * File-mutating tools whose path arguments attribute a per-turn change. Tools outside
     * this set (reads, searches, shell, …) are intentionally ignored — a path they touch
     * is not per-turn attributed (it still shows in the global status sheet).
     */
    private fun actionForToolNamed(name: String): TurnFileChange.Action? = when (name) {
        "create_file" -> TurnFileChange.Action.ADDED
        "remove_file", "delete_file", "mcp_filesystem_remove_file" -> TurnFileChange.Action.DELETED
        "move_file", "rename_file", "mcp_filesystem_move_file" -> TurnFileChange.Action.RENAMED
        "write_file", "patch", "edit_file",
        "mcp_filesystem_write_file", "mcp_filesystem_edit_file" -> TurnFileChange.Action.EDITED
        else -> null
    }

    /** Ordered (rawPath, action) candidates extracted from one tool call's arguments. */
    private fun candidates(toolCall: ToolCall): List<Candidate> {
        val name = normalizedToolName(toolCall.name) ?: return emptyList()
        val action = actionForToolNamed(name) ?: return emptyList()
        val args = toolCall.args ?: return emptyList()

        // A rename/move yields a single file at its destination; the source path no longer
        // exists in `git/status`, so don't emit a phantom row for it.
        if (action == TurnFileChange.Action.RENAMED) {
            val destination = firstString(args, listOf("destination", "path", "file_path", "filename"))
            if (destination != null) {
                return listOf(Candidate(destination, TurnFileChange.Action.RENAMED))
            }
            return emptyList()
        }

        val results = mutableListOf<Candidate>()

        // `source`/`destination` are rename semantics and are consumed by the early-return
        // rename branch above; only the plain path keys apply to edit/add/delete tools.
        for (key in listOf("path", "file_path", "filename")) {
            string(args[key])?.let { value ->
                results.add(Candidate(value, action))
            }
        }

        args["paths"]?.arrayValue?.forEach { item ->
            string(item)?.let { value ->
                results.add(Candidate(value, action))
            }
        }

        args["edits"]?.arrayValue?.forEach { edit ->
            val editObject = edit.objectValue ?: return@forEach
            string(editObject["path"])?.let { value ->
                results.add(Candidate(value, action))
            }
        }

        return results
    }

    private fun normalizedToolName(name: String?): String? {
        val trimmed = name?.trim()?.lowercase()
        return if (!trimmed.isNullOrEmpty()) trimmed else null
    }

    private fun firstString(args: Map<String, JSONValue>, keys: List<String>): String? {
        for (key in keys) {
            val value = string(args[key])
            if (value != null) {
                return value
            }
        }
        return null
    }

    /** Only string args are usable as paths (numbers/bools/objects are not file paths). */
    private fun string(value: JSONValue?): String? {
        val raw = value?.string ?: return null
        val trimmed = raw.trim()
        return if (trimmed.isEmpty()) null else trimmed
    }

    // MARK: - Path normalization & ignore filter

    /** Swift `CharacterSet(charactersIn: "`\"'<>()[]{}")` — wrapping quotes/brackets to strip. */
    private val trimCharacters = setOf('`', '"', '\'', '<', '>', '(', ')', '[', ']', '{', '}')

    /** Path component names that mark generated/vendored trees we never attribute. */
    private val ignoredComponents = setOf(
        ".git", ".hg", ".svn", "node_modules", ".venv", "venv",
        "__pycache__", "dist", "build", ".next", ".cache",
    )

    /**
     * Normalize a raw tool path to a comparable workspace path, or `null` to drop it.
     * Strips wrapping quotes/brackets, drops `~/` and `./` prefixes, rejects URLs and
     * over-long paths, and filters generated/vendored trees.
     */
    fun normalize(raw: String): String? {
        var path = raw.trim()
        path = path.trim { it in trimCharacters }
        path = path.trim()

        if (path.isEmpty() || path.length > 240 || path.contains("://")) return null

        if (path.startsWith("~/")) path = path.substring(2)
        while (path.startsWith("./")) path = path.substring(2)
        path = path.trim()

        if (path.isEmpty() || isIgnored(path)) return null
        return path
    }

    private fun isIgnored(path: String): Boolean =
        path.split('/').any { it in ignoredComponents }

    // MARK: - git/status join

    /** Find the `git/status` entry for a tool path, tolerating absolute-vs-relative references to the same repo file. */
    private fun matchingFile(path: String, files: List<GitFile>): GitFile? {
        files.firstOrNull { displayPath(it) == path }?.let { return it }
        return files.firstOrNull { representsSameFile(displayPath(it), path) }
    }

    private fun displayPath(file: GitFile): String =
        normalize(file.displayPath) ?: file.displayPath

    /**
     * True when two normalized paths point at the same file — equal, or one is the
     * absolute form of the other (suffix match on a `/`-boundary). The suffix checks are
     * gated on the longer path being absolute so two distinct *relative* paths that merely
     * share a trailing component (e.g. `other/App.swift` vs `some/other/App.swift`) never
     * falsely match.
     */
    private fun representsSameFile(lhs: String, rhs: String): Boolean {
        if (lhs.isEmpty() || rhs.isEmpty()) return false
        if (lhs == rhs) return true
        if (lhs.startsWith("/") && lhs.endsWith("/$rhs")) return true
        if (rhs.startsWith("/") && rhs.endsWith("/$lhs")) return true
        return false
    }
}
