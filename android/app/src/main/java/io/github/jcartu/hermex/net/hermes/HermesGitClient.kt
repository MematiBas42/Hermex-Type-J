package io.github.jcartu.hermex.net.hermes

import io.github.jcartu.hermex.model.GitBranchMode
import io.github.jcartu.hermex.model.GitBranchRef
import io.github.jcartu.hermex.model.GitBranches
import io.github.jcartu.hermex.model.GitCheckoutResponse
import io.github.jcartu.hermex.model.GitCheckoutTarget
import io.github.jcartu.hermex.model.GitCommitMessageResponse
import io.github.jcartu.hermex.model.GitCommitResponse
import io.github.jcartu.hermex.model.GitDiff
import io.github.jcartu.hermex.model.GitFile
import io.github.jcartu.hermex.model.GitInfo
import io.github.jcartu.hermex.model.GitRemoteActionResponse
import io.github.jcartu.hermex.model.GitStatus
import io.github.jcartu.hermex.model.GitTotals
import io.github.jcartu.hermex.model.hermes.BotFailure
import io.github.jcartu.hermex.model.hermes.BotJSON
import io.github.jcartu.hermex.net.ApiError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.SerializationException

/**
 * Port of `HermesGitClient` (`Networking/Hermes/HermesGitClient.swift`): a Hermes chat's
 * repository (#1114), read through the host's Git routes on the sign-in its Bot screens share,
 * mapped into webui's Git models so the menu, Changes sheet, diffs and turn-changes card read
 * it unchanged. It shows the whole repository holding the chat's folder: the root is resolved
 * once from `GET /api/fs/git-root` and every read names it.
 *
 * Writes (#1115) go through the host's review routes, which guard nothing, so every write
 * names one exact file ([HermesREST.isGitFile]), reads the rows it acts on again, and the
 * guards live here, ahead of the request ([HermesGitRefusal]); each request also asks the
 * owning chat first ([writeOwner]). A branch switch (#1116) is a write too, refused while the
 * tree has any uncommitted change. Fetch, pull and New Branch (#1236) go through the host's
 * hermex-push plugin and show only while it lists their routes ([supportsSync]).
 *
 * Neither the root nor git's own output reaches the screen or a log.
 */
class HermesGitClient(
    val context: HermesWorkspaceContext,
    private val http: HermesConnection,
    /**
     * Writes a commit message for a diff with the host's one-shot model call
     * (`HermesCall.CommitMessage`): the diff, the recent subjects to match, and on Regenerate
     * the last suggestion, which the host is told not to repeat.
     */
    private val writeMessage: (suspend (diff: String, recent: String, avoid: String?) -> String)? = null,
    /**
     * The chat that owns the repository: asked as each write begins, it throws while a turn
     * runs or the chat shows cached data, else returns the check every request of the write
     * runs as it goes out, which also throws once the chat reattached or left this folder.
     */
    private val writeOwner: (() -> (() -> Unit))? = null,
) {
    /** Shared by everything that writes through this client: the chat's Git menu, branch picker and commit sheet. */
    val writeLock = Mutex()

    /** The repository root, once `fs/git-root` has found one. Never shown, logged or persisted. */
    private var root: String? = null

    /** Writes that went out. A write whose reply is lost may still have run. */
    private var sentWrites = 0

    /** A commit's result: webui's response, and whether Commit Selected could stage the other files again. */
    data class Commit(val response: GitCommitResponse, val stagingNotRestored: Boolean = false)

    /** A Hermes host's writes answer only `{ok}`: a failed one reads the status again, and a commit shows its sha. */
    val isHermes: Boolean = true

    /** The branch, ahead/behind and dirty counts from `git/status`; null outside a repository. */
    suspend fun info(): GitInfo? {
        val root = repositoryRoot() ?: return null
        val summary = json(send(HermesREST.GitStatus(root)))
        if (!summary.isObject) return null
        val changed = summary["changed"].integer ?: 0
        val untracked = summary["untracked"].integer ?: 0
        return GitInfo(
            branch = branch(summary), dirty = changed, modified = changed - untracked, untracked = untracked,
            ahead = summary["ahead"].integer, behind = summary["behind"].integer, isGit = true,
        )
    }

    /** Every uncommitted change; `isGit == false` outside a repository. */
    suspend fun status(): GitStatus? {
        val root = repositoryRoot() ?: return NOT_A_REPOSITORY
        return currentStatus(root)
    }

    private suspend fun currentStatus(root: String): GitStatus = coroutineScope {
        val summary = async { send(HermesREST.GitStatus(root)) }
        val changes = async { send(HermesREST.GitChanges(root)) }
        status(json(summary.await()), json(changes.await()))
    }

    /**
     * The row's staged diff when it has only staged changes, else its worktree diff. A staged
     * row past the status cap may also have worktree edits, so it reads its whole change
     * against HEAD; before the first commit that read is empty, so a new file reads as its
     * current content. Over [MAXIMUM_DIFF_BYTES] it is too large.
     */
    suspend fun diff(file: GitFile): GitDiff? {
        val root = repositoryRoot() ?: return null
        val path = file.displayPath
        val wholeChange = file.staged == true && file.unstaged == null
        val kind = if (wholeChange) null else file.preferredDiffKind
        val request: HermesREST = if (wholeChange) HermesREST.GitFileDiff(root, path) else HermesREST.GitDiff(root, path, kind == "staged")
        val text = json(send(request))["diff"].text ?: ""
        if (wholeChange && text.isEmpty() && file.changeKind == GitFile.ChangeKind.ADDED) return currentContent(path, root)
        val tooLarge = text.toByteArray().size > MAXIMUM_DIFF_BYTES
        return GitDiff(path = path, kind = kind, binary = isBinary(text), tooLarge = tooLarge, additions = null, deletions = null, diff = if (tooLarge) null else text)
    }

    /** A new file's whole content as an all-add diff, from `fs/read-text`. */
    private suspend fun currentContent(path: String, root: String): GitDiff {
        val file = json(send(HermesREST.FsReadText("$root/$path")))
        val binary = file["binary"].flag == true
        val tooLarge = file["truncated"].flag == true
        val lines = (file["text"].text ?: "").split("\n").toMutableList()
        if (lines.lastOrNull() == "") lines.removeAt(lines.lastIndex)
        val diff = "diff --git a/$path b/$path\n--- /dev/null\n+++ b/$path\n" +
            if (lines.isEmpty()) "" else "@@ -0,0 +1,${lines.size} @@\n" + lines.joinToString("") { "+$it\n" }
        return GitDiff(path = path, kind = null, binary = binary, tooLarge = tooLarge, additions = null, deletions = null, diff = if (binary || tooLarge) null else diff)
    }

    /** A turn's tool path as its row's root-relative path. Left as named until the root is known, or when it lies outside. */
    fun rowPath(toolPath: String): String {
        val root = root ?: return toolPath
        return rowPath(toolPath, context.cwd, root)
    }

    // MARK: - Writes (#1115)

    /** Stages each file with something to stage, one request per file. A conflicted file is refused. */
    suspend fun stage(files: List<GitFile>): GitStatus? = writing(files) { root, rows, dispatch ->
        if (rows.any { it.conflict == true }) throw HermesGitRefusal.ConflictedStage
        for (row in rows) if (row.staged != true || row.unstaged != false) write(HermesREST.GitStage(root, row.path ?: ""), dispatch)
    }

    /** Unstages each staged file, one request per file. A rename is refused. */
    suspend fun unstage(files: List<GitFile>): GitStatus? = writing(files) { root, rows, dispatch ->
        if (rows.any { isRename(it) }) throw HermesGitRefusal.RenamedUnstage
        for (row in rows) if (row.staged == true) write(HermesREST.GitUnstage(root, row.path ?: ""), dispatch)
    }

    /**
     * Returns each file to HEAD, one at a time. A staged one is unstaged first; the host
     * deletes an untracked file. Without [deleteUntracked] a file that would be deleted is left
     * alone. A conflicted file is refused, and so is a rename.
     */
    suspend fun discard(files: List<GitFile>, deleteUntracked: Boolean): GitStatus? = writing(files) { root, rows, dispatch ->
        if (rows.any { it.conflict == true }) throw HermesGitRefusal.ConflictedDiscard
        if (rows.any { isRename(it) }) throw HermesGitRefusal.RenamedDiscard
        for (row in rows) {
            if (!deleteUntracked && isNew(row)) continue
            val path = row.path ?: ""
            if (row.staged == true) write(HermesREST.GitUnstage(root, path), dispatch)
            write(HermesREST.GitRevert(root, path), dispatch)
        }
    }

    /** Commits what is staged, or only [files]. Refused while the repository has a conflict, and with nothing staged. */
    suspend fun commit(message: String, files: List<GitFile>? = null): Commit {
        val dispatch = beginWrite()
        val root = writableRoot()
        if (files != null) return commitSelected(files, message, root, dispatch)
        val summary = json(send(HermesREST.GitStatus(root)))
        if ((summary["conflicted"].integer ?: 0) != 0) throw HermesGitRefusal.ConflictedCommit
        if ((summary["staged"].integer ?: 0) <= 0) throw HermesGitRefusal.NothingStaged
        write(HermesREST.GitCommit(root, message), dispatch, HermesConnection.Deadline.PROVISIONING)
        return committed(root, null)
    }

    /**
     * The host commits the whole index, so "commit selected" is a sequence: unstage
     * everything, stage the selection, commit, then stage again the files that were staged
     * before. Refused before it starts while a staged file also has worktree edits, or may
     * have, or is a rename; while a staged file's path isn't one file; and before the first
     * commit. A failure once any write went out stages those files again and throws.
     */
    private suspend fun commitSelected(files: List<GitFile>, message: String, root: String, dispatch: () -> Unit): Commit {
        val current = currentStatus(root)
        val rows = current.files ?: emptyList()
        if ((current.totals?.conflicts ?: 0) != 0 || rows.any { it.conflict == true }) throw HermesGitRefusal.ConflictedCommitSelected
        val selected = rows(files, rows).mapNotNull { it.path }
        if (selected.isEmpty()) throw HermesGitRefusal.NothingStaged
        val staged = rows.filter { it.staged == true }
        if (staged.any { it.unstaged != false || isRename(it) }) throw HermesGitRefusal.PartlyStagedSelection
        val restaged = rows(staged, rows).mapNotNull { it.path }
        if (text(json(send(HermesREST.GitHead(root)))["sha"]) == null) throw HermesGitRefusal.FirstCommitSelected
        val sentBefore = sentWrites
        try {
            write(HermesREST.GitUnstage(root, null), dispatch)
            for (path in selected) write(HermesREST.GitStage(root, path), dispatch)
            // With nothing staged the host would stage everything instead.
            val summary = json(send(HermesREST.GitStatus(root)))
            if ((summary["staged"].integer ?: 0) <= 0) throw HermesGitRefusal.NothingStaged
            write(HermesREST.GitCommit(root, message), dispatch, HermesConnection.Deadline.PROVISIONING)
        } catch (error: Throwable) {
            if (sentWrites <= sentBefore) throw error
            try {
                write(HermesREST.GitUnstage(root, null), dispatch)
                for (path in restaged) write(HermesREST.GitStage(root, path), dispatch)
            } catch (restore: Throwable) {
                if (restore is CancellationException) throw restore
                throw HermesGitRefusal.RestoreFailed
            }
            throw error
        }
        var restored = true
        for (path in restaged) {
            if (path in selected) continue
            try {
                write(HermesREST.GitStage(root, path), dispatch)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                restored = false
            }
        }
        return committed(root, selected, restored)
    }

    /** The new commit's short sha and the status after it. */
    private suspend fun committed(root: String, paths: List<String>?, stagingRestored: Boolean = true): Commit {
        val sha = runCatching { text(json(send(HermesREST.GitHead(root)))["sha"]) }.getOrNull()
        val status = runCatching { status() }.getOrNull()
        return Commit(
            GitCommitResponse(ok = true, commit = sha?.take(7), paths = paths, status = status, git = null),
            stagingNotRestored = !stagingRestored,
        )
    }

    /** Pushes the branch to its upstream, or to origin as its new upstream. A detached HEAD is refused. */
    suspend fun push(): GitRemoteActionResponse {
        val dispatch = beginWrite()
        val root = writableRoot()
        val summary = json(send(HermesREST.GitStatus(root)))
        if (summary["detached"].flag == true) throw HermesGitRefusal.DetachedHead
        write(HermesREST.GitPush(root), dispatch, HermesConnection.Deadline.PROVISIONING)
        return GitRemoteActionResponse(ok = true, message = null, status = runCatching { status() }.getOrNull())
    }

    // MARK: - Sync (#1236)

    /** Whether the host's hermex-push plugin has all three sync routes loaded. A host that can't say has none. */
    suspend fun supportsSync(): Boolean {
        val routes = try {
            BotDashboardClient(http).companionRoutes()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            return false
        }
        return SYNC_ROUTES.all { routes.supports(it) }
    }

    suspend fun fetch(): GitRemoteActionResponse = sync { HermesREST.GitFetch(it) }

    suspend fun pull(): GitRemoteActionResponse = sync { HermesREST.GitPull(it) }

    /** One plugin write at the root; its answer is the status read again, never git's output. */
    private suspend fun sync(request: (String) -> HermesREST): GitRemoteActionResponse {
        val dispatch = beginWrite()
        val root = writableRoot()
        write(request(root), dispatch, HermesConnection.Deadline.PROVISIONING)
        return GitRemoteActionResponse(ok = true, message = null, status = runCatching { status() }.getOrNull())
    }

    /**
     * A commit message from the host's model, as Desktop drafts one: `commit-context`'s diff of
     * what would commit, or the selected [files]' whole changes, with its recent subjects to
     * match. [avoiding] is the last suggestion, so Regenerate gives a different one.
     */
    suspend fun suggestMessage(files: List<GitFile>?, avoiding: String?): GitCommitMessageResponse {
        val writeMessage = writeMessage ?: throw HermesGitRefusal.NoMessage
        val root = writableRoot()
        val context = json(send(HermesREST.GitCommitContext(root)))
        var diff = context["diff"].text ?: ""
        if (files != null) {
            val builder = StringBuilder()
            for (file in files) {
                if (builder.length >= SELECTED_DIFF_CHARACTERS) break
                val path = file.path ?: file.displayPath
                val change = json(send(HermesREST.GitFileDiff(root, path)))["diff"].text ?: ""
                builder.append(if (change.isEmpty() && file.changeKind == GitFile.ChangeKind.ADDED) currentContent(path, root).diff ?: "" else change)
            }
            diff = builder.toString().take(SELECTED_DIFF_CHARACTERS)
        }
        if (diff.isBlank()) throw HermesGitRefusal.NoMessage
        val text = try {
            writeMessage(diff, context["recent"].text ?: "", avoiding)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            throw HermesGitRefusal.NoMessage
        }
        val message = text.trim()
        if (message.isEmpty()) throw HermesGitRefusal.NoMessage
        return GitCommitMessageResponse(ok = true, message = message, truncated = diff.length > MESSAGE_DIFF_CHARACTERS)
    }

    // MARK: - Branches (#1116)

    /** `git/branches`' local heads, then its remote-tracking refs without one. The current branch is the status's. */
    suspend fun branches(): GitBranches? {
        val root = repositoryRoot() ?: return null
        val rows = json(send(HermesREST.GitBranches(root)))["branches"].list ?: emptyList()
        fun refs(remote: Boolean): List<GitBranchRef> =
            rows.filter { (it["isRemote"].flag == true) == remote }.mapNotNull { row -> text(row["name"])?.let { GitBranchRef(name = it) } }
        return GitBranches(isGit = true, current = null, detached = null, head = null, local = refs(false), remote = refs(true))
    }

    /**
     * Switches to a listed branch with `git switch`. A remote row (`origin/name`) goes out as
     * `name`, refused unless the host lists it, now, as the only branch called `name`. Refused
     * while the tree has any uncommitted change, untracked files included: there is no stash
     * on Hermes. A new branch is created from HEAD through the plugin (#1236).
     */
    suspend fun checkout(target: GitCheckoutTarget, stashingChanges: Boolean): GitCheckoutResponse {
        target.newBranch?.let { name ->
            if (!HermesREST.isBranchName(name) || stashingChanges) throw HermesGitRefusal.Failed
            val dispatch = beginWrite()
            val root = writableRoot()
            write(HermesREST.GitCreateBranch(root, name), dispatch, HermesConnection.Deadline.PROVISIONING)
            return switched(name, runCatching { status() }.getOrNull())
        }
        val remote = target.mode != GitBranchMode.LOCAL
        val branch = if (remote) shortName(target.ref) else target.ref
        if (branch == null || !HermesREST.isBranchName(branch) || stashingChanges) throw HermesGitRefusal.Failed
        val dispatch = beginWrite()
        val root = writableRoot()
        if (remote) {
            val rows = json(send(HermesREST.GitBranches(root)))["branches"].list ?: emptyList()
            val named = rows.filter { row ->
                text(row["name"])?.let { if (row["isRemote"].flag == true) shortName(it) else it } == branch
            }
            if (named.size != 1 || named[0]["isRemote"].flag != true || text(named[0]["name"]) != target.ref) throw HermesGitRefusal.Failed
        }
        val summary = json(send(HermesREST.GitStatus(root)))
        if (summary["changed"].integer != 0) throw HermesGitRefusal.UncommittedSwitch
        write(HermesREST.GitSwitchBranch(root, branch), dispatch, HermesConnection.Deadline.PROVISIONING)
        return switched(branch, runCatching { status() }.getOrNull())
    }

    /** Runs [writes] at the root on the rows the host lists now for [files], then reads the status again. */
    private suspend fun writing(files: List<GitFile>, writes: suspend (String, List<GitFile>, () -> Unit) -> Unit): GitStatus? {
        val dispatch = beginWrite()
        val root = writableRoot()
        val rows = rows(files, currentStatus(root).files ?: emptyList())
        writes(root, rows, dispatch)
        return runCatching { status() }.getOrNull()
    }

    /** The owner's check for one write, which each of its requests runs again as it goes out. */
    private fun beginWrite(): () -> Unit = writeOwner?.invoke() ?: UNOWNED

    /** The root for a write; a folder outside a repository has nothing to write. */
    private suspend fun writableRoot(): String = repositoryRoot() ?: throw HermesGitUnavailable()

    /** One write, sent only while [dispatch] passes and counted in [sentWrites] as it goes out. A refusal reads as [HermesGitRefusal.Failed]. */
    private suspend fun write(rest: HermesREST, dispatch: () -> Unit, deadline: HermesConnection.Deadline = HermesConnection.Deadline.STANDARD) {
        send(rest, deadline, refusal = HermesGitRefusal.Failed) {
            dispatch()
            sentWrites += 1
        }
    }

    /** The root of the repository holding the chat's folder, resolved once; null outside one. */
    private suspend fun repositoryRoot(): String? {
        root?.let { return it }
        val found = json(send(HermesREST.GitRoot(context.cwd)))["root"].text?.takeIf { it.isNotEmpty() }
        root = found
        return found
    }

    /**
     * One request's body. A refusal (400 `{detail}`, git's stderr) and any other failed status
     * read as [refusal], so neither git's output nor a host path reaches the screen. A dropped
     * request reads as webui's network failure; the sign-in's own failures keep theirs.
     */
    private suspend fun send(
        rest: HermesREST,
        deadline: HermesConnection.Deadline = HermesConnection.Deadline.STANDARD,
        refusal: Throwable = HermesGitUnavailable(),
        validateDispatch: (() -> Unit)? = null,
    ): ByteArray {
        val reply = try {
            http.reply(rest, deadline, validateDispatch)
        } catch (error: BotFailure.Network) {
            throw ApiError.Network(error.underlying)
        }
        return try {
            HermesCronClient.accepted(reply)
        } catch (error: HermesCronRefusal) {
            throw refusal
        } catch (error: ApiError.Http) {
            throw refusal
        }
    }

    private fun json(body: ByteArray): BotJSON = try {
        BotJSON.parse(body)
    } catch (error: SerializationException) {
        throw ApiError.Decoding(error)
    } catch (error: IllegalArgumentException) {
        throw ApiError.Decoding(error)
    }

    companion object {
        /** webui's diff cap: a longer diff shows "Diff too large to show." instead of its text. */
        const val MAXIMUM_DIFF_BYTES = 512 * 1024

        /** The longest diff the host's `commit_message` template reads; past it a message may be partial. */
        const val MESSAGE_DIFF_CHARACTERS = 12_000

        /** The most of the selected files' diffs sent for a message, as `commit-context` caps its own. */
        const val SELECTED_DIFF_CHARACTERS = 120_000

        /** hermex-push's fetch, pull and New Branch routes, as `/capabilities` lists them. */
        val SYNC_ROUTES = listOf("POST /git/fetch", "POST /git/pull", "POST /git/branch")

        private val UNOWNED: () -> Unit = {}

        private val NOT_A_REPOSITORY = GitStatus(isGit = false)

        /**
         * `rowPath(_:folder:root:)` — a tool path as its row's root-relative path for a chat
         * working in [folder] inside the repository at [root]. The path is anchored to the
         * folder and its `.` and `..` collapsed, as the host resolves it. The host's root has
         * its symlinks resolved and the folder may not, so a folder spelled through one is
         * matched by the root's trailing folders.
         */
        fun rowPath(path: String, folder: String, root: String): String {
            val target = components(if (path.startsWith("/")) path else "$folder/$path")
            val rootParts = components(root)
            val folderParts = components(folder)
            val folderSpelling = (minOf(rootParts.size, folderParts.size) downTo 0)
                .map { folderParts.take(it) }
                .firstOrNull { it.isNotEmpty() && rootParts.takeLast(it.size) == it }
            for (base in listOfNotNull(rootParts, folderSpelling)) {
                if (target.size > base.size && target.take(base.size) == base) {
                    return target.drop(base.size).joinToString("/")
                }
            }
            return path
        }

        /** An absolute path's folder names, with `.` dropped and `..` taking off the one before it. */
        private fun components(path: String): List<String> {
            val parts = ArrayList<String>()
            for (part in path.split("/")) {
                when (part) {
                    "", "." -> Unit
                    ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex)
                    else -> parts += part
                }
            }
            return parts
        }

        /**
         * `status(summary:changes:)` — `git/status` and `review/list` as one [GitStatus]. Rows
         * are `review/list`'s, which lists every change; `status.files` stops at 200 and adds
         * the flags a row lacks. The list is whole, so it is never `truncated`.
         */
        fun status(summary: BotJSON, changes: BotJSON): GitStatus {
            if (!summary.isObject) return NOT_A_REPOSITORY
            val flags = HashMap<String, BotJSON>()
            for (entry in summary["files"].list ?: emptyList()) entry["path"].text?.let { flags[it] = entry }
            val files = (changes["files"].list ?: emptyList()).mapNotNull { row ->
                val path = row["path"].text?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val flag = flags[path]
                val letter = row["status"].text
                GitFile(
                    id = path, path = path, status = letter,
                    staged = row["staged"].flag ?: flag?.get("staged")?.flag,
                    unstaged = flag?.get("unstaged")?.flag,
                    untracked = flag?.get("untracked")?.flag ?: (letter == "?"),
                    conflict = flag?.get("conflicted")?.flag ?: (letter == "U"),
                    additions = row["added"].integer, deletions = row["removed"].integer,
                )
            }
            val totals = GitTotals(
                changed = summary["changed"].integer ?: files.size, staged = summary["staged"].integer,
                unstaged = summary["unstaged"].integer, untracked = summary["untracked"].integer, conflicts = summary["conflicted"].integer,
            )
            return GitStatus(
                isGit = true, branch = branch(summary), upstream = null, ahead = summary["ahead"].integer,
                behind = summary["behind"].integer, totals = totals, files = files, truncated = false,
            )
        }

        /** The branch's name, or `HEAD` when detached, as webui names it. */
        private fun branch(summary: BotJSON): String? = summary["branch"].text ?: if (summary["detached"].flag == true) "HEAD" else null

        /** A diff with no hunk whose patch says the file is binary. */
        private fun isBinary(text: String): Boolean =
            !text.contains("\n@@") && text.split("\n").any { it.startsWith("Binary files ") && it.endsWith(" differ") }

        private fun switched(branch: String, status: GitStatus?): GitCheckoutResponse =
            GitCheckoutResponse(ok = true, message = null, status = status, currentBranch = branch)

        /** A remote-tracking ref's branch name, the part after its remote; null without a remote part. */
        private fun shortName(ref: String): String? = ref.indexOf('/').takeIf { it >= 0 }?.let { ref.substring(it + 1) }

        /** A file a revert deletes: untracked, or new in the index. */
        private fun isNew(file: GitFile): Boolean = file.untracked == true || file.changeKind == GitFile.ChangeKind.ADDED

        /** A staged rename or copy, whose row names only its new path. */
        private fun isRename(file: GitFile): Boolean = (file.status ?: "").uppercase().firstOrNull() in setOf('R', 'C')

        /** A non-empty string, else null. */
        private fun text(value: BotJSON): String? = value.text?.takeIf { it.isNotEmpty() }

        /**
         * The current rows for [files], by exact path, never trimmed. A path that isn't one
         * file, which the host would read as the whole tree, is refused, and so is one the
         * host lists twice.
         */
        private fun rows(files: List<GitFile>, current: List<GitFile>): List<GitFile> = files.mapNotNull { file ->
            val path = file.path
            if (path == null || !HermesREST.isGitFile(path)) throw HermesGitRefusal.Failed
            val matches = current.filter { it.path == path }
            if (matches.size >= 2) throw HermesGitRefusal.Failed
            matches.firstOrNull()
        }
    }
}

/** Port of `HermesGitUnavailable`: a Git read a Hermes host refused (#1114), shown with the existing copy. */
class HermesGitUnavailable : Exception() {
    override val message: String get() = "Repository status unavailable"
}

/** Port of `HermesGitRefusal`: a Git write [HermesGitClient] refuses before sending it, or one the host refused (#1115). */
sealed class HermesGitRefusal : Exception() {
    object ConflictedStage : HermesGitRefusal()
    object ConflictedDiscard : HermesGitRefusal()
    object ConflictedCommit : HermesGitRefusal()
    object ConflictedCommitSelected : HermesGitRefusal()
    object NothingStaged : HermesGitRefusal()
    object DetachedHead : HermesGitRefusal()
    object NoMessage : HermesGitRefusal()
    object RenamedDiscard : HermesGitRefusal()
    object RenamedUnstage : HermesGitRefusal()

    /** A staged file is partly staged, may be (past the status cap), or is a rename. */
    object PartlyStagedSelection : HermesGitRefusal()
    object FirstCommitSelected : HermesGitRefusal()

    /** A turn is running in the chat that owns the repository. */
    object TurnRunning : HermesGitRefusal()

    /** A Commit Selected that failed before its commit couldn't stage the files again either. */
    object RestoreFailed : HermesGitRefusal()

    /** A branch switch with uncommitted changes (#1116). */
    object UncommittedSwitch : HermesGitRefusal()

    /** The host refused it, or it names no single file; git's own output isn't shown. */
    object Failed : HermesGitRefusal()

    override val message: String
        get() = when (this) {
            ConflictedStage -> "Conflicted files cannot be staged from this panel"
            ConflictedDiscard -> "Conflicted files cannot be discarded from this panel"
            ConflictedCommit -> "Resolve conflicts before committing"
            ConflictedCommitSelected -> "Resolve conflicts before committing selected files"
            NothingStaged -> "Stage changes before committing"
            DetachedHead -> "Cannot push from a detached HEAD"
            NoMessage -> "No commit message could be generated."
            RenamedDiscard -> "Renamed files cannot be discarded from this panel"
            RenamedUnstage -> "Renamed files cannot be unstaged from this panel"
            PartlyStagedSelection -> "Fully stage or fully unstage partly staged or renamed files before committing selected files"
            FirstCommitSelected -> "Make the first commit with Commit, then commit selected files"
            TurnRunning -> "Wait for the active response to finish before changing this repository."
            RestoreFailed -> "Git couldn’t finish this change, and the staged files couldn’t be restored."
            UncommittedSwitch -> "Commit or discard first."
            Failed -> "Git couldn’t finish this change on your Hermes host."
        }
}
