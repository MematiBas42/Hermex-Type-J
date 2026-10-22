package io.github.jcartu.hermex.net.hermes

import io.github.jcartu.hermex.model.hermes.BotArtifactFailure
import io.github.jcartu.hermex.model.hermes.BotFailure
import io.github.jcartu.hermex.model.hermes.BotJSON
import io.github.jcartu.hermex.model.hermes.botObject
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID

/**
 * Port of `Networking/Hermes/HermesREST.swift`: every HTTP request Hermex sends to a direct
 * Hermes host. A case builds its method, path, query and JSON body against the connection's
 * address. `HermesConnection` sends them with its headers and cookie jar; only the connection
 * screen's status probe sends [Status] bare. Each operation reads its own reply status.
 *
 * Route shapes were read at the hermes-agent pin `ca678285` (0.21.5); the Swift file's header
 * records each verification. Nothing here is invented: every path, query and body key is the
 * Swift case's.
 */
sealed class HermesREST {

    /** Public, so it reads the host before any credential is sent. */
    object Status : HermesREST()

    /**
     * The dashboard's own page, whose script carries the session token an open dashboard
     * (`HermesSignInMethod.OPEN`, #1314) checks on every `/api/…` route ([pageToken]). Public.
     */
    object DashboardPage : HermesREST()

    /** Public process liveness with the running release, read while the dashboard restarts. */
    object Health : HermesREST()

    data class Login(val username: String, val password: String) : HermesREST()

    /**
     * Trades a browser sign-in's refresh token for a new access token and a rotated refresh
     * token (#708): `{access_token, refresh_token, token_type, expires_at, provider, user_id}`,
     * or 401 `{error: "session_expired"}` once the host no longer accepts it. Public.
     */
    data class NativeRefresh(val refreshToken: String, val provider: String) : HermesREST()

    /**
     * Trades a browser sign-in's single-use loopback code and its PKCE verifier for the same
     * tokens [NativeRefresh] answers (#708); any refusal is a generic 400. Public.
     */
    data class NativeToken(val code: String, val verifier: String) : HermesREST()

    object Identity : HermesREST()

    /** `{active, current}`: `current` is the Profile this dashboard is scoped to (#1010). */
    object ProfilesActive : HermesREST()

    /** Mints the single-use ticket one gateway socket presents. */
    object Ticket : HermesREST()

    /** `DELETE /api/profiles/{name}`; `name` is a validated Profile slug. */
    data class DeleteProfile(val name: String) : HermesREST()

    /** Stores image bytes under the Profile. The returned path travels in one prompt. */
    data class UploadImage(val profile: String, val filename: String, val dataURL: String) : HermesREST()

    /** One recording for `profile`'s speech-to-text: base64 of up to 25 MiB of an `audio` type. */
    data class Transcribe(val profile: String, val dataURL: String, val mimeType: String) : HermesREST()

    /**
     * One file download. With a session the host resolves a relative path against that
     * stored session's folder; without one, `path` must be absolute.
     */
    data class DownloadArtifact(val path: String, val profile: String, val sessionID: String?) : HermesREST()

    /** Writes one managed environment value at the host root. */
    data class SetEnvironment(val key: String, val value: String) : HermesREST()

    /** Installs an agent plugin, reinstalling over an existing copy. */
    data class InstallPlugin(val identifier: String) : HermesREST()

    data class SetPlugin(val name: String, val enabled: Boolean) : HermesREST()

    object RestartGateway : HermesREST()

    object PushPairing : HermesREST()

    /** hermex-push's mounted routes (#1235, plugin 0.5.0): no side effects, never 409. */
    object PluginCapabilities : HermesREST()

    /** hermex-push 0.7.0's account limits for one provider and Profile (#1237). */
    data class CompanionProviderQuota(val provider: String, val profile: String, val refresh: Boolean) : HermesREST()

    /** hermex-push 0.4.0's restart: 202, then the dashboard re-execs itself. */
    object RestartDashboard : HermesREST()

    /** Every agent plugin with its on-disk version. */
    object PluginsHub : HermesREST()

    /**
     * A stored session's rows under `profile`. Without an offset, its latest 500 (#1013). With
     * one, a transcript page (#1047): [TRANSCRIPT_PAGE_SIZE] display rows counted back from the
     * newest, compacted ones included, in chronological order.
     */
    data class SessionMessages(val key: String, val profile: String, val offset: Int? = null) : HermesREST()

    /**
     * One page of `profile`'s sessions for the Sessions list (#1046): latest activity first,
     * without archived, empty or machine-run rows, [SESSION_PAGE_SIZE] at a time from `offset`.
     * `archived` reads the Archived screen's page instead (#1048).
     */
    data class SessionList(
        val profile: String,
        val offset: Int,
        val archived: Boolean = false,
        val scheduled: Boolean = false,
    ) : HermesREST()

    /** One page of every session `profile` lists for Move to Hermes's scan (#1213). */
    data class SessionScan(val profile: String, val offset: Int) : HermesREST()

    /**
     * One change to a session, with `profile` in the body (#1046, #1048), and the write fence's
     * identity (#1300) on a host that has the fence; null sends the change alone.
     */
    data class UpdateSession(
        val key: String,
        val profile: String,
        val change: HermesSessionChange,
        val identity: HermesSessionWriteIdentity?,
    ) : HermesREST()

    /** The write fence's counters for that session (#1300). */
    data class SessionMutationSnapshot(val key: String, val profile: String) : HermesREST()

    /** That exact session's row and every message, unredacted, as one JSON object (#1048). */
    data class SessionExport(val key: String, val profile: String) : HermesREST()

    /** That exact session's stored row (#1051), or 404 `{detail}` when `profile` has none. */
    data class SessionRow(val key: String, val profile: String) : HermesREST()

    /**
     * Imports sessions as an export holds them (#1051). `body` is the JSON `{sessions, profile}`,
     * encoded by the caller; `identity` is spliced in after its `{` rather than encoded with it.
     */
    class ImportSessions(val body: ByteArray, val identity: HermesSessionWriteIdentity) : HermesREST()

    /** `profile`'s sessions matching `query` (#1053), at most `limit`. */
    data class SessionSearch(
        val query: String,
        val profile: String,
        val limit: Int = SESSION_SEARCH_LIMIT,
        val scheduled: Boolean = false,
    ) : HermesREST()

    /** Every Profile's scheduled Tasks, paused and completed included: a bare array. */
    object CronJobs : HermesREST()

    /** Creates a Task in `profile`, or in the host's default Profile when null. */
    data class CronCreate(val profile: String?, val fields: Map<String, BotJSON>) : HermesREST()

    /** `{updates}` never names the job or its Profile: `profile` only routes the request. */
    data class CronUpdate(val id: String, val profile: String?, val updates: Map<String, BotJSON>) : HermesREST()

    data class CronPause(val id: String, val profile: String?) : HermesREST()

    data class CronResume(val id: String, val profile: String?) : HermesREST()

    data class CronDelete(val id: String, val profile: String?) : HermesREST()

    /** Runs the job now, without a body, and answers it once the run has finished. */
    data class CronTrigger(val id: String, val profile: String?) : HermesREST()

    /** The job's newest `limit` runs (at most 100), newest first. */
    data class CronRuns(val id: String, val profile: String?, val limit: Int) : HermesREST()

    /** `{targets: [{id, name, …}]}`, `local` first, for one Profile's gateway platforms. */
    data class CronDeliveryTargets(val profile: String?) : HermesREST()

    /** One Profile's skills, disabled ones included: a bare array. */
    data class Skills(val profile: String?) : HermesREST()

    /** Turns one skill on or off for `profile`, which goes in the body; `{ok, name, enabled}`. */
    data class SetSkill(val name: String, val enabled: Boolean, val profile: String?) : HermesREST()

    /** A skill's SKILL.md: `{name, content, path}`, or 404 `{detail}`. */
    data class SkillContent(val name: String, val profile: String?) : HermesREST()

    /** `{entries: [{name, path, isDirectory}]}` for one folder at a host path, not recursive. */
    data class FsList(val path: String) : HermesREST()

    /** `{text, binary, truncated, byteSize, …}` for the file at a host path, its first 512 KiB. */
    data class FsReadText(val path: String) : HermesREST()

    /** `{root}`: the repository folder holding a host path, or null outside one (#1114). */
    data class GitRoot(val path: String) : HermesREST()

    /** `null` outside a repository, else the status summary with `files` capped at 200. */
    data class GitStatus(val repository: String) : HermesREST()

    /** `{files: [{path, added, removed, status, staged}], base}`: every uncommitted change. */
    data class GitChanges(val repository: String) : HermesREST()

    /** `{diff}`: one file's staged or worktree diff. */
    data class GitDiff(val repository: String, val file: String, val staged: Boolean) : HermesREST()

    /** `{diff}`: one file's whole change against HEAD. */
    data class GitFileDiff(val repository: String, val file: String) : HermesREST()

    /** `git add -- file`. One is always named (the host's default is `git add -A`). */
    data class GitStage(val repository: String, val file: String) : HermesREST()

    /** `git reset -q HEAD [-- file]`; null unstages everything. */
    data class GitUnstage(val repository: String, val file: String?) : HermesREST()

    /** `git checkout HEAD -- file` then `git clean -fd -- file`. One is always named. */
    data class GitRevert(val repository: String, val file: String) : HermesREST()

    /** `git commit -m message`, never pushing. */
    data class GitCommit(val repository: String, val message: String) : HermesREST()

    /** Pushes to the upstream, or `-u origin <branch>` without one. */
    data class GitPush(val repository: String) : HermesREST()

    /** `{sha}`: HEAD's full sha, or null before the first commit. */
    data class GitHead(val repository: String) : HermesREST()

    /** `{diff, recent}`: what a commit would take and the last ten subjects. */
    data class GitCommitContext(val repository: String) : HermesREST()

    /** `{branches: [{name, checkedOut, isDefault, isRemote, worktreePath}]}` (#1116). */
    data class GitBranches(val repository: String) : HermesREST()

    /** `git switch branch`; `{branch}`, or 400 `{detail}`. Refused unless [isBranchName]. */
    data class GitSwitchBranch(val repository: String, val branch: String) : HermesREST()

    /** hermex-push 0.6.0's `fetch --prune` (#1236). */
    data class GitFetch(val repository: String) : HermesREST()

    /** hermex-push 0.6.0's fast-forward-only `pull` (#1236). */
    data class GitPull(val repository: String) : HermesREST()

    /** hermex-push 0.6.0's `switch -c name` from HEAD (#1236). */
    data class GitCreateBranch(val repository: String, val name: String) : HermesREST()

    /** Replaces or creates the file at a host path, atomically; `{ok, path, byteSize}`. */
    data class FsWriteText(val path: String, val content: String) : HermesREST()

    /** Creates the folder at a host path, with its parents; 409 when a file is in the way. */
    data class FilesMkdir(val path: String) : HermesREST()

    /** A Profile's whole config, unredacted: decode only what is needed. */
    data class Config(val profile: String) : HermesREST()

    /** Writes [PLATFORM_NOTE] as the Profile's `platform_hints.hermex.append` (#1278). */
    data class SetPlatformNote(val profile: String) : HermesREST()

    /** `{content, exists}`: the Profile's SOUL.md, empty when it has none. */
    data class ProfileSoul(val name: String) : HermesREST()

    /** Replaces the Profile's SOUL.md, atomically; `{ok: true}`. */
    data class SetProfileSoul(val name: String, val content: String) : HermesREST()

    /** Speaks `text` in `profile`'s voice: the audio as a base64 data URL. */
    data class Speak(val text: String, val profile: String) : HermesREST()

    /** One Profile's usage over the last `days` (1-365). */
    data class AnalyticsUsage(val days: Int, val profile: String) : HermesREST()

    /** The same window by model and billing provider. */
    data class AnalyticsModels(val days: Int, val profile: String) : HermesREST()

    /** `{default_tenant, …}`, or 404 when the Kanban plugin is disabled or absent. */
    object KanbanConfig : HermesREST()

    object KanbanBoards : HermesREST()

    data class KanbanBoard(val board: String, val tenant: String?, val includeArchived: Boolean) : HermesREST()

    data class KanbanStats(val board: String) : HermesREST()

    data class KanbanAssignees(val board: String) : HermesREST()

    data class KanbanTask(val id: String, val board: String) : HermesREST()

    data class KanbanTaskLog(val id: String, val board: String, val tailBytes: Int) : HermesREST()

    data class KanbanCreateTask(val board: String, val body: Map<String, BotJSON>) : HermesREST()

    data class KanbanUpdateTask(val id: String, val board: String, val body: Map<String, BotJSON>) : HermesREST()

    data class KanbanComment(val id: String, val board: String, val body: String) : HermesREST()

    data class KanbanLink(val board: String, val parent: String, val child: String) : HermesREST()

    data class KanbanUnlink(val board: String, val parent: String, val child: String) : HermesREST()

    data class KanbanBulk(val board: String, val body: Map<String, BotJSON>) : HermesREST()

    data class KanbanDispatch(val board: String, val dryRun: Boolean) : HermesREST()

    data class KanbanCreateBoard(val body: Map<String, BotJSON>) : HermesREST()

    data class KanbanEditBoard(val slug: String, val body: Map<String, BotJSON>) : HermesREST()

    data class KanbanArchiveBoard(val slug: String) : HermesREST()

    data class KanbanSwitchBoard(val slug: String) : HermesREST()

    /** Whether the host's install is behind; `force` skips the host's 24-hour cache. */
    data class UpdateCheck(val force: Boolean) : HermesREST()

    /** Starts `hermes update` on the host, without a body. */
    object UpdateApply : HermesREST()

    /** The update action: whether it runs, its exit code and the tail of its log. */
    object UpdateStatus : HermesREST()

    /** The host's latest update receipt, or 404 when no update has run. */
    object UpdateReceipt : HermesREST()

    /** `request(base:)` — the OkHttp request for this route against the dashboard root. */
    @Throws(BotFailure::class, BotArtifactFailure::class)
    fun request(base: HttpUrl): Request = when (this) {
        Status -> get(base.path("api/status"))
        DashboardPage -> get(base)
        Health -> get(base.path("api/health"))
        is UpdateCheck -> get(base.path("api/hermes/update/check") { if (force) addQueryParameter("force", "true") })
        UpdateApply -> bare("POST", base.path("api/hermes/update"))
        // The log tail is only read for the summary of a run that stopped, so 40 lines will do.
        UpdateStatus -> get(base.path("api/actions/hermes-update/status") { addQueryParameter("lines", "40") })
        UpdateReceipt -> get(base.path("api/hermes/update/receipt"))
        is Login -> send(
            "POST", base.path("auth/password-login"),
            botObject("provider" to BotJSON.string("basic"), "username" to BotJSON.string(username), "password" to BotJSON.string(password)),
        )
        is NativeRefresh -> send(
            "POST", base.path("auth/native/refresh"),
            botObject("refresh_token" to BotJSON.string(refreshToken), "provider" to BotJSON.string(provider)),
        )
        is NativeToken -> send(
            "POST", base.path("auth/native/token"),
            botObject("code" to BotJSON.string(code), "code_verifier" to BotJSON.string(verifier)),
        )
        Identity -> get(base.path("api/auth/me"))
        ProfilesActive -> get(base.path("api/profiles/active"))
        Ticket -> send("POST", base.path("api/auth/ws-ticket"), BotJSON.objectOf())
        is DeleteProfile -> bare("DELETE", base.path("api/profiles") { addPathSegment(name) })
        is UploadImage -> {
            if (profile.isEmpty()) throw BotFailure.InvalidAddress
            send(
                "POST", base.path("api/chat/image-upload") { addQueryParameter("profile", profile) },
                botObject("filename" to BotJSON.string(filename), "data_url" to BotJSON.string(dataURL)),
            )
        }
        is Transcribe -> send(
            "POST", profiled(base, "api/audio/transcribe", profile),
            botObject("data_url" to BotJSON.string(dataURL), "mime_type" to BotJSON.string(mimeType)),
        )
        is DownloadArtifact -> {
            if (profile.isEmpty() || sessionID?.isEmpty() == true) throw BotArtifactFailure.InvalidReference
            val resolved = BotArtifactReference.path(path, base)
            // The host reads a query's `+` as a space; OkHttp encodes a path's own as `%2B`.
            val url = base.path("api/fs/download") {
                addQueryParameter("path", resolved)
                addQueryParameter("profile", profile)
                if (sessionID != null) addQueryParameter("session_id", sessionID)
            }
            get(url)
        }
        is SetEnvironment -> send(
            "PUT", base.path("api/env"),
            botObject("key" to BotJSON.string(key), "value" to BotJSON.string(value)),
        )
        is InstallPlugin -> send(
            "POST", base.path("api/dashboard/agent-plugins/install"),
            botObject("identifier" to BotJSON.string(identifier), "enable" to BotJSON.bool(true), "force" to BotJSON.bool(true)),
        )
        is SetPlugin -> send(
            "POST",
            base.path("api/dashboard/agent-plugins") { addPathSegment(name).addPathSegment(if (enabled) "enable" else "disable") },
            BotJSON.objectOf(),
        )
        RestartGateway -> send("POST", base.path("api/gateway/restart"), BotJSON.objectOf())
        PushPairing -> get(base.path("api/plugins/hermex-push/pairing"))
        PluginCapabilities -> get(base.path("api/plugins/hermex-push/capabilities"))
        is CompanionProviderQuota -> get(
            base.path("api/plugins/hermex-push/provider-quota") {
                addQueryParameter("provider", provider)
                addQueryParameter("profile", profile)
                if (refresh) addQueryParameter("refresh", "1")
            },
        )
        RestartDashboard -> send("POST", base.path("api/plugins/hermex-push/restart"), BotJSON.objectOf())
        PluginsHub -> get(base.path("api/dashboard/plugins/hub"))
        is SessionMessages -> {
            if (!isSegment(key) || profile.isEmpty() || (offset ?: 0) < 0) throw BotFailure.InvalidAddress
            get(
                base.path("api/sessions") {
                    addPathSegment(key).addPathSegment("messages")
                    addQueryParameter("profile", profile)
                    // Both `order` and `limit`: a limit alone pages from the oldest row. Without
                    // `include_compacted` the transcript would end at the last compaction.
                    if (offset != null) {
                        addQueryParameter("order", "latest")
                        addQueryParameter("limit", TRANSCRIPT_PAGE_SIZE.toString())
                        addQueryParameter("offset", offset.toString())
                        addQueryParameter("include_compacted", "true")
                    }
                },
            )
        }
        is SessionList -> {
            if (profile.isEmpty() || offset < 0) throw BotFailure.InvalidAddress
            // Every value is sent: the host's defaults order by creation, list empty sessions
            // and keep cron, Kanban, one-shot, subagent and tool runs.
            get(
                base.path("api/sessions") {
                    addQueryParameter("profile", profile)
                    addQueryParameter("order", "recent")
                    addQueryParameter("archived", if (archived) "only" else "exclude")
                    addQueryParameter("limit", SESSION_PAGE_SIZE.toString())
                    addQueryParameter("offset", offset.toString())
                    if (!archived) addQueryParameter("min_messages", "1")
                    if (scheduled) {
                        addQueryParameter("sources", "cron")
                        addQueryParameter("exclude_sources", "kanban,oneshot,subagent,tool")
                    } else {
                        addQueryParameter(
                            "exclude_sources",
                            if (archived) "kanban,oneshot,subagent,tool" else "cron,kanban,oneshot,subagent,tool",
                        )
                    }
                },
            )
        }
        is SessionScan -> {
            if (profile.isEmpty() || offset < 0) throw BotFailure.InvalidAddress
            get(
                base.path("api/sessions") {
                    addQueryParameter("profile", profile)
                    addQueryParameter("order", "created")
                    addQueryParameter("archived", "include")
                    addQueryParameter("limit", SESSION_PAGE_SIZE.toString())
                    addQueryParameter("offset", offset.toString())
                },
            )
        }
        is UpdateSession -> {
            if (!isSegment(key) || profile.isEmpty()) throw BotFailure.InvalidAddress
            val fields = LinkedHashMap<String, BotJSON>()
            fields[change.field] = change.value
            fields["profile"] = BotJSON.string(profile)
            identity?.fields?.let { fields.putAll(it) }
            send("PATCH", base.path("api/sessions") { addPathSegment(key) }, BotJSON.objectOf(fields))
        }
        is SessionMutationSnapshot -> {
            if (!isSegment(key) || profile.isEmpty()) throw BotFailure.InvalidAddress
            get(profiled(base, "api/sessions/$key/mutation-snapshot", profile))
        }
        is SessionExport -> {
            if (!isSegment(key) || profile.isEmpty()) throw BotFailure.InvalidAddress
            get(profiled(base, "api/sessions/$key/export", profile))
        }
        is SessionRow -> {
            if (!isSegment(key) || profile.isEmpty()) throw BotFailure.InvalidAddress
            get(profiled(base, "api/sessions/$key", profile))
        }
        is ImportSessions -> {
            if (body.firstOrNull() != '{'.code.toByte()) throw BotFailure.Unsupported
            val spliced = "{".toByteArray() + identity.leadingMembers + body.copyOfRange(1, body.size)
            Request.Builder()
                .url(base.path("api/sessions/import"))
                .method("POST", spliced.toRequestBody(JSON_MEDIA_TYPE))
                .build()
        }
        is SessionSearch -> {
            if (query.isEmpty() || profile.isEmpty()) throw BotFailure.InvalidAddress
            // The same sources the list leaves out, so a search never finds a row it never shows.
            get(
                base.path("api/sessions/search") {
                    addQueryParameter("q", query)
                    addQueryParameter("profile", profile)
                    addQueryParameter("limit", limit.toString())
                    if (scheduled) {
                        addQueryParameter("sources", "cron")
                        addQueryParameter("exclude_sources", "kanban,oneshot,subagent,tool")
                    } else {
                        addQueryParameter("exclude_sources", "cron,kanban,oneshot,subagent,tool")
                    }
                },
            )
        }
        CronJobs -> get(base.path("api/cron/jobs"))
        is CronCreate -> send("POST", profiled(base, "api/cron/jobs", profile), BotJSON.objectOf(fields))
        is CronUpdate -> send("PUT", cronJob(base, id, null, profile), botObject("updates" to BotJSON.objectOf(updates)))
        is CronPause -> bare("POST", cronJob(base, id, "pause", profile))
        is CronResume -> bare("POST", cronJob(base, id, "resume", profile))
        is CronDelete -> bare("DELETE", cronJob(base, id, null, profile))
        is CronTrigger -> bare("POST", cronJob(base, id, "trigger", profile))
        is CronRuns -> get(cronJob(base, id, "runs", profile).newBuilder().addQueryParameter("limit", limit.toString()).build())
        is CronDeliveryTargets -> get(profiled(base, "api/cron/delivery-targets", profile))
        is Skills -> get(profiled(base, "api/skills", profile))
        is SetSkill -> send(
            "PUT", base.path("api/skills/toggle"),
            botObject(
                "name" to BotJSON.string(name), "enabled" to BotJSON.bool(enabled),
                "profile" to profile?.takeIf { it.isNotEmpty() }?.let(BotJSON::string),
            ),
        )
        is SkillContent -> get(
            base.path("api/skills/content") {
                addQueryParameter("name", name)
                if (!profile.isNullOrEmpty()) addQueryParameter("profile", profile)
            },
        )
        is FsList -> get(base.path("api/fs/list") { addQueryParameter("path", path) })
        is FsReadText -> get(base.path("api/fs/read-text") { addQueryParameter("path", path) })
        is GitRoot -> get(base.path("api/fs/git-root") { addQueryParameter("path", path) })
        is GitStatus -> get(base.path("api/git/status") { addQueryParameter("path", repository) })
        is GitChanges -> get(
            base.path("api/git/review/list") {
                addQueryParameter("path", repository)
                addQueryParameter("scope", "uncommitted")
            },
        )
        is GitDiff -> get(
            base.path("api/git/review/diff") {
                addQueryParameter("path", repository)
                addQueryParameter("file", file)
                addQueryParameter("scope", "uncommitted")
                addQueryParameter("staged", if (staged) "true" else "false")
            },
        )
        is GitFileDiff -> get(
            base.path("api/git/file-diff") {
                addQueryParameter("path", repository)
                addQueryParameter("file", file)
            },
        )
        is GitStage -> gitWrite(base, "api/git/review/stage", repository, file)
        is GitUnstage -> gitWrite(base, "api/git/review/unstage", repository, file)
        is GitRevert -> gitWrite(base, "api/git/review/revert", repository, file)
        is GitCommit -> send(
            "POST", base.path("api/git/review/commit"),
            botObject("path" to BotJSON.string(repository), "message" to BotJSON.string(message), "push" to BotJSON.bool(false)),
        )
        is GitPush -> send("POST", base.path("api/git/review/push"), botObject("path" to BotJSON.string(repository)))
        is GitHead -> get(base.path("api/git/review/rev-parse") { addQueryParameter("path", repository) })
        is GitCommitContext -> get(base.path("api/git/review/commit-context") { addQueryParameter("path", repository) })
        is GitBranches -> get(base.path("api/git/branches") { addQueryParameter("path", repository) })
        is GitSwitchBranch -> {
            if (repository.isEmpty() || !isBranchName(branch)) throw BotFailure.InvalidAddress
            send(
                "POST", base.path("api/git/branch/switch"),
                botObject("path" to BotJSON.string(repository), "branch" to BotJSON.string(branch)),
            )
        }
        is GitFetch -> pluginGit(base, "fetch", repository, emptyMap())
        is GitPull -> pluginGit(base, "pull", repository, emptyMap())
        is GitCreateBranch -> {
            if (!isBranchName(name)) throw BotFailure.InvalidAddress
            pluginGit(base, "branch", repository, mapOf("name" to BotJSON.string(name)))
        }
        is FsWriteText -> send(
            "POST", base.path("api/fs/write-text"),
            botObject("path" to BotJSON.string(path), "content" to BotJSON.string(content)),
        )
        is FilesMkdir -> send("POST", base.path("api/files/mkdir"), botObject("path" to BotJSON.string(path)))
        is Config -> {
            if (profile.isEmpty()) throw BotFailure.InvalidAddress
            get(profiled(base, "api/config", profile))
        }
        is SetPlatformNote -> {
            if (profile.isEmpty()) throw BotFailure.InvalidAddress
            val note = BotJSON.objectOf(HermesCall.SOURCE to BotJSON.objectOf("append" to BotJSON.string(PLATFORM_NOTE)))
            send(
                "PUT", profiled(base, "api/config", profile),
                botObject("config" to BotJSON.objectOf("platform_hints" to note)),
            )
        }
        is ProfileSoul -> {
            if (!isSegment(name)) throw BotFailure.InvalidAddress
            get(base.path("api/profiles") { addPathSegment(name).addPathSegment("soul") })
        }
        is SetProfileSoul -> {
            if (!isSegment(name)) throw BotFailure.InvalidAddress
            send(
                "PUT", base.path("api/profiles") { addPathSegment(name).addPathSegment("soul") },
                botObject("content" to BotJSON.string(content)),
            )
        }
        is Speak -> {
            if (profile.isEmpty()) throw BotFailure.InvalidAddress
            send("POST", profiled(base, "api/audio/speak", profile), botObject("text" to BotJSON.string(text)))
        }
        is AnalyticsUsage -> analytics(base, "usage", days, profile)
        is AnalyticsModels -> analytics(base, "models", days, profile)
        KanbanConfig -> kanban(base, listOf("config"))
        KanbanBoards -> kanban(base, listOf("boards"))
        is KanbanBoard -> kanban(
            base, listOf("board"),
            buildList {
                add("board" to board)
                if (!tenant.isNullOrEmpty()) add("tenant" to tenant)
                if (includeArchived) add("include_archived" to "true")
            },
        )
        is KanbanStats -> kanban(base, listOf("stats"), listOf("board" to board))
        is KanbanAssignees -> kanban(base, listOf("assignees"), listOf("board" to board))
        is KanbanTask -> {
            if (!isSegment(id)) throw BotFailure.InvalidAddress
            kanban(base, listOf("tasks", id), listOf("board" to board))
        }
        is KanbanTaskLog -> {
            if (!isSegment(id)) throw BotFailure.InvalidAddress
            kanban(base, listOf("tasks", id, "log"), listOf("board" to board, "tail" to tailBytes.toString()))
        }
        is KanbanCreateTask -> kanban(base, listOf("tasks"), listOf("board" to board), "POST", BotJSON.objectOf(body))
        is KanbanUpdateTask -> {
            if (!isSegment(id)) throw BotFailure.InvalidAddress
            kanban(base, listOf("tasks", id), listOf("board" to board), "PATCH", BotJSON.objectOf(body))
        }
        is KanbanComment -> {
            if (!isSegment(id)) throw BotFailure.InvalidAddress
            kanban(base, listOf("tasks", id, "comments"), listOf("board" to board), "POST", botObject("body" to BotJSON.string(body)))
        }
        is KanbanLink -> kanban(
            base, listOf("links"), listOf("board" to board), "POST",
            botObject("parent_id" to BotJSON.string(parent), "child_id" to BotJSON.string(child)),
        )
        is KanbanUnlink -> kanban(
            base, listOf("links"), listOf("board" to board, "parent_id" to parent, "child_id" to child),
        ).newBuilder().method("DELETE", null).build()
        is KanbanBulk -> kanban(base, listOf("tasks", "bulk"), listOf("board" to board), "POST", BotJSON.objectOf(body))
        is KanbanDispatch -> kanban(
            base, listOf("dispatch"),
            listOf("board" to board, "dry_run" to if (dryRun) "true" else "false", "max" to KANBAN_DISPATCH_MAXIMUM.toString()),
            "POST", BotJSON.objectOf(),
        )
        is KanbanCreateBoard -> kanban(base, listOf("boards"), emptyList(), "POST", BotJSON.objectOf(body))
        is KanbanEditBoard -> {
            if (!isSegment(slug)) throw BotFailure.InvalidAddress
            kanban(base, listOf("boards", slug), emptyList(), "PATCH", BotJSON.objectOf(body))
        }
        is KanbanArchiveBoard -> {
            if (!isSegment(slug)) throw BotFailure.InvalidAddress
            kanban(base, listOf("boards", slug), listOf("delete" to "false")).newBuilder().method("DELETE", null).build()
        }
        is KanbanSwitchBoard -> {
            if (!isSegment(slug)) throw BotFailure.InvalidAddress
            kanban(base, listOf("boards", slug, "switch"), emptyList(), "POST", BotJSON.objectOf())
        }
    }

    /**
     * Port of `HermesREST.UpgradePass`: what a socket upgrade presents to the host's check,
     * which runs before the socket opens.
     */
    sealed class UpgradePass {
        /** A single-use ticket minted at [Ticket], for a host with sign-in. */
        data class Ticket(val ticket: String) : UpgradePass()

        /**
         * An open dashboard's session token (#1314), presented as `?token=`. The host reads
         * tickets only behind its sign-in, and accepts this one without a subprotocol.
         */
        data class PageToken(val token: String) : UpgradePass()
    }

    companion object {
        /**
         * What the agent of a Hermex session is told about the screen it writes to (#1278):
         * the host appends it to the system prompt of every agent running as `HermesCall.SOURCE`.
         */
        const val PLATFORM_NOTE =
            "You are chatting with the user through Hermex, a native iPhone app for Hermes. Markdown renders. " +
                "To send the user a file from this machine, write MEDIA:/absolute/path/to/file in your reply: " +
                "images show inline, audio plays in the chat, video opens from a tile, and any other file arrives " +
                "as a file the user can save or share. Send the file rather than asking the user to open a path."

        /** The most rows `GET /api/sessions` lists in one page. */
        const val SESSION_PAGE_SIZE = 100

        /** The most display rows one transcript page ([SessionMessages] with an offset) carries. */
        const val TRANSCRIPT_PAGE_SIZE = 100

        /** The most sessions one search answers; the host allows up to 100 and defaults to 20. */
        const val SESSION_SEARCH_LIMIT = 50

        /** The host's cap on one search, which has no offset to page past it. */
        const val SESSION_SEARCH_MAXIMUM = 100

        /** `KanbanDispatchRequest.maximum` — one dispatcher pass starts at most eight workers. */
        const val KANBAN_DISPATCH_MAXIMUM = 8

        private val JSON_MEDIA_TYPE = "application/json".toMediaType()

        /**
         * `gatewayUpgrade(base:pass:)` — the gateway socket's upgrade: `/api/ws` on the
         * address. A ticket goes with `hermes-gateway-v1` as subprotocols in the request's
         * `Sec-WebSocket-Protocol` header, which the host splits on commas; a page token goes
         * as `?token=`, with none. OkHttp reads the `http(s)` URL and upgrades it itself.
         */
        fun gatewayUpgrade(base: HttpUrl, pass: UpgradePass): Request {
            val url = base.path("api/ws") {
                if (pass is UpgradePass.PageToken) addQueryParameter("token", pass.token)
            }
            val builder = Request.Builder().url(url)
            if (pass is UpgradePass.Ticket) {
                builder.header("Sec-WebSocket-Protocol", "hermes-gateway-v1, hermes-gateway-ticket." + pass.ticket)
            }
            return builder.build()
        }

        /**
         * `kanbanEventsUpgrade(base:board:since:pass:)` — a Board's Kanban event socket (#1045):
         * `/api/plugins/kanban/events`, presenting a ticket as `?ticket=` or a page token as
         * `?token=`. It offers no subprotocol, because the host accepts without echoing one.
         */
        fun kanbanEventsUpgrade(base: HttpUrl, board: String, since: Int, pass: UpgradePass): Request {
            val url = base.path("api/plugins/kanban/events") {
                addQueryParameter("board", board)
                addQueryParameter("since", maxOf(0, since).toString())
                when (pass) {
                    is UpgradePass.Ticket -> addQueryParameter("ticket", pass.ticket)
                    is UpgradePass.PageToken -> addQueryParameter("token", pass.token)
                }
            }
            return Request.Builder().url(url).build()
        }

        private val PAGE_TOKEN = Regex("__HERMES_SESSION_TOKEN__\\s*=\\s*\"([^\"\\\\]+)\"")

        /**
         * `pageToken(in:)` — the session token in [DashboardPage]'s body: upstream writes
         * `window.__HERMES_SESSION_TOKEN__="<token>";`. Null for a page without one, such as a
         * dashboard behind its sign-in, which never injects it.
         */
        fun pageToken(page: ByteArray): String? =
            PAGE_TOKEN.find(page.toString(Charsets.UTF_8))?.groupValues?.get(1)

        /**
         * `isGitFile(_:)` — whether `file` names one path inside a repository, as a Git write's
         * `file` must: not blank, not absolute, no `.` or `..` part, and no empty part but a
         * folder's trailing slash.
         */
        fun isGitFile(file: String): Boolean {
            if (file.isBlank()) return false
            var parts = file.split("/")
            if (parts.size > 1 && parts.last() == "") parts = parts.dropLast(1)
            return parts.all { it.isNotEmpty() && it != "." && it != ".." }
        }

        /**
         * `isBranchName(_:)` — whether the host's branch sanitizer leaves `name` as it is:
         * Python word characters (letters, numerals, `_`), `.`, `/` and `-` only, no run of
         * `-`, `/` or `.`, and none of those three at either end. Checked per code point.
         */
        fun isBranchName(name: String): Boolean {
            val points = name.codePoints().toArray()
            if (points.isEmpty()) return false
            val edges = setOf('-'.code, '.'.code, '/'.code)
            if (points.first() in edges || points.last() in edges) return false
            val allowed = points.all { point ->
                Character.isLetter(point) || Character.getType(point) == Character.DECIMAL_DIGIT_NUMBER.toInt() ||
                    Character.getType(point) == Character.LETTER_NUMBER.toInt() ||
                    Character.getType(point) == Character.OTHER_NUMBER.toInt() ||
                    point == '_'.code || point in edges
            }
            return allowed && listOf("--", "//", "..").none { name.contains(it) }
        }

        /** One path segment of the host's own id characters, so an id never names another route. */
        fun isSegment(value: String): Boolean =
            value.isNotEmpty() && value.all { it.isLetterOrDigit() || it == '_' || it == '-' }

        private fun HttpUrl.path(segments: String, query: (HttpUrl.Builder.() -> Unit)? = null): HttpUrl {
            val builder = newBuilder().addPathSegments(segments)
            query?.invoke(builder)
            return builder.build()
        }

        private fun get(url: HttpUrl): Request = Request.Builder().url(url).build()

        /** A request without a body. */
        private fun bare(method: String, url: HttpUrl): Request =
            Request.Builder().url(url).method(method, if (method == "GET" || method == "HEAD") null else EMPTY_BODY).build()

        private val EMPTY_BODY = ByteArray(0).toRequestBody(null)

        private fun send(method: String, url: HttpUrl, body: BotJSON): Request =
            Request.Builder()
                .url(url)
                .method(method, body.encoded.toByteArray().toRequestBody(JSON_MEDIA_TYPE))
                .build()

        /** `path` under `base`, with `?profile=` when a Profile is named. */
        private fun profiled(base: HttpUrl, path: String, profile: String?): HttpUrl =
            base.path(path) { if (!profile.isNullOrEmpty()) addQueryParameter("profile", profile) }

        /** One job's route, or one of its actions. */
        private fun cronJob(base: HttpUrl, id: String, action: String?, profile: String?): HttpUrl {
            if (!isSegment(id)) throw BotFailure.InvalidAddress
            return profiled(base, "api/cron/jobs/$id" + (action?.let { "/$it" } ?: ""), profile)
        }

        /** One analytics read for `profile` over the last `days`, a window the host accepts. */
        private fun analytics(base: HttpUrl, route: String, days: Int, profile: String): Request {
            if (days !in 1..365 || profile.isEmpty()) throw BotFailure.InvalidAddress
            return get(
                base.path("api/analytics/$route") {
                    addQueryParameter("days", days.toString())
                    addQueryParameter("profile", profile)
                },
            )
        }

        /** A request under the Kanban plugin's mount: a GET, or `method` with the JSON `body`. */
        private fun kanban(
            base: HttpUrl,
            path: List<String>,
            query: List<Pair<String, String>> = emptyList(),
            method: String = "GET",
            body: BotJSON? = null,
        ): Request {
            val url = base.path("api/plugins/kanban") {
                path.forEach { addPathSegment(it) }
                query.forEach { (name, value) -> addQueryParameter(name, value) }
            }
            return if (body == null) get(url) else send(method, url, body)
        }

        /** One of hermex-push's git writes at `repository`, with `fields` beside its `path`. */
        private fun pluginGit(base: HttpUrl, route: String, repository: String, fields: Map<String, BotJSON>): Request {
            if (repository.isEmpty()) throw BotFailure.InvalidAddress
            val body = LinkedHashMap(fields)
            body["path"] = BotJSON.string(repository)
            return send("POST", base.path("api/plugins/hermex-push/git/$route"), BotJSON.objectOf(body))
        }

        /** A Git review write at `repository`, its `file` literal ([isGitFile]); null only unstages. */
        private fun gitWrite(base: HttpUrl, route: String, repository: String, file: String?): Request {
            if (repository.isEmpty() || (file != null && !isGitFile(file))) throw BotFailure.InvalidAddress
            return send(
                "POST", base.path(route),
                botObject("path" to BotJSON.string(repository), "file" to file?.let { BotJSON.string(":(literal)$it") }),
            )
        }
    }
}

/**
 * Port of `HermesSessionWriteIdentity`: what hermes-agent's write fence (#1300) asks of a REST
 * session write: a request id the host dedupes a retried write on, and the `runtime_revision`
 * the write expects the session to be at.
 */
data class HermesSessionWriteIdentity(
    val expectedRevision: Int,
    val requestID: String = UUID.randomUUID().toString().uppercase(),
) {
    internal val fields: Map<String, BotJSON>
        get() = mapOf("request_id" to BotJSON.string(requestID), "expected_revision" to BotJSON.number(expectedRevision))

    val leadingMembers: ByteArray
        get() = "\"request_id\":\"$requestID\",\"expected_revision\":$expectedRevision,".toByteArray()
}

/**
 * Port of `HermesSessionChange`: one change [HermesREST.UpdateSession] writes (#1046, #1048).
 * `pinned` and `archived` apply across the session's compression lineage, and `pinned: true`
 * also unhides it; `title` names that exact session, cleaned by the host.
 */
sealed class HermesSessionChange {
    /** `false` reads the session up to now for every client; `true` marks it unread. */
    data class Unread(val flag: Boolean) : HermesSessionChange()
    data class Pinned(val flag: Boolean) : HermesSessionChange()
    data class Archived(val flag: Boolean) : HermesSessionChange()
    data class Title(val title: String) : HermesSessionChange()

    internal val field: String
        get() = when (this) {
            is Unread -> "unread"
            is Pinned -> "pinned"
            is Archived -> "archived"
            is Title -> "title"
        }

    internal val value: BotJSON
        get() = when (this) {
            is Unread -> BotJSON.bool(flag)
            is Pinned -> BotJSON.bool(flag)
            is Archived -> BotJSON.bool(flag)
            is Title -> BotJSON.string(title)
        }
}
