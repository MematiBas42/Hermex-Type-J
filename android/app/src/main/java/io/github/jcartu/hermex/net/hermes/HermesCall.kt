package io.github.jcartu.hermex.net.hermes

import io.github.jcartu.hermex.model.hermes.BotFailure
import io.github.jcartu.hermex.model.hermes.BotJSON
import io.github.jcartu.hermex.model.hermes.botObject
import io.github.jcartu.hermex.net.hermes.HermesCompatibility.lexicographicallyPrecedes

/**
 * Port of `Networking/Hermes/HermesCall.swift`: every JSON-RPC request Hermex sends to a
 * direct Hermes gateway: one case per operation the app uses, and deliberately none for any
 * other upstream method. A case carries only what its callers vary. Values the contract
 * fixes for Hermex (the canonical Bot Chat title, `queued`, the avatar asset, the room page
 * size) are written here, so no caller can widen a request.
 *
 * [params] is the only way to the wire. It runs admission first, for the value rules the
 * types cannot express, so `BotClient` never sends a refused request.
 */
sealed class HermesCall {

    // Profiles
    data class ProfilesList(val includeSessions: Boolean) : HermesCall()
    data class ProfilesDescribe(val name: String) : HermesCall()

    /** The Profile's avatar, the only asset Hermex reads. */
    data class ProfilesGetAsset(val name: String) : HermesCall()
    data class ProfilesSetAsset(val name: String, val avatar: AvatarChange) : HermesCall()
    data class ProfilesConfigure(val changes: ProfileChanges) : HermesCall()
    data class ProfilesCreate(val profile: NewProfile) : HermesCall()

    // The canonical Bot Chat
    /** Finds the Profile's Bot Chat by its exact title, hidden rows included. */
    data class SessionList(val profile: String) : HermesCall()

    /** Mints the Profile's hidden Bot Chat under its configuration. */
    data class SessionCreate(val profile: String) : HermesCall()

    /** Persists a freshly minted Bot Chat under its canonical title. */
    data class SessionTitle(val sessionID: String) : HermesCall()

    /** Attaches to a session without closing it when this socket drops. */
    data class SessionResume(val profile: String, val sessionID: String, val omitMessages: Boolean) : HermesCall()
    data class SessionEventsSince(val sessionID: String, val lastSeen: Int) : HermesCall()

    /** The inbox's live-status read. */
    object SessionActiveList : HermesCall()

    /** The Sessions list's one Profile-scoped read after it connects (#1046). */
    data class SessionMostRecent(val profile: String) : HermesCall()

    // Sessions
    /** Mints a plain session under the Profile: no title, not hidden (`ConversationTarget.new`). */
    data class SessionNew(val profile: String, val cwd: String? = null, val model: Model? = null) : HermesCall()

    /** `/title` in an open chat (#1048). A title in use or over 100 characters is 4022. */
    data class SessionRename(val runtime: String, val title: String) : HermesCall()

    /** Ends one runtime, for every client attached to it (#1048). */
    data class SessionClose(val runtime: String) : HermesCall()

    /** Deletes that exact stored session under `profile` (#1048); 4023 while a runtime holds it. */
    data class SessionDelete(val profile: String, val storedKey: String) : HermesCall()

    /** Fork From Here, `/branch` and `/fork` (#1051). Nothing to copy is 4008; a name in use is 5008. */
    data class SessionBranch(val runtime: String, val name: String?, val count: Int?) : HermesCall()

    /** Move to Project (#1052): sets that stored session's working folder. A folder the host lacks is 4017. */
    data class SessionWorkspaceMove(val profile: String, val storedKey: String, val cwd: String) : HermesCall()

    // Projects (#1052)
    data class ProjectsTree(val profile: String) : HermesCall()
    data class ProjectsCreate(val profile: String, val name: String, val folder: String, val color: String?) : HermesCall()
    data class ProjectsUpdate(val profile: String, val id: String, val name: String, val color: String?) : HermesCall()
    data class ProjectsDelete(val profile: String, val id: String) : HermesCall()

    // Turns
    /** Always `queued`: a fresh send never inherits a host setting that converts it into a redirect or steer. */
    data class PromptSubmit(val sessionID: String, val text: String) : HermesCall()

    /**
     * Cuts the transcript at one durable prompt row and starts the turn again with `text`.
     * Never `queued`. `deep` sends `confirm_deep_truncate`, which a host that guards deep
     * cuts asks for (4033); older hosts refuse the unknown key (4000).
     */
    data class PromptRewind(val sessionID: String, val text: String, val beforeRowID: Int, val deep: Boolean) : HermesCall()

    /** `/undo` (#1049): rewinds the last real user turn and answers `{removed}`. */
    data class SessionUndo(val runtime: String) : HermesCall()

    /** `/compress` and `/compact` (#1050). */
    data class SessionCompress(val runtime: String, val focus: String?, val profile: String) : HermesCall()
    data class SessionSteer(val sessionID: String, val text: String) : HermesCall()
    data class SessionRedirect(val sessionID: String, val text: String) : HermesCall()
    data class SessionInterrupt(val sessionID: String) : HermesCall()
    data class FileAttach(val sessionID: String, val name: String, val dataURL: String) : HermesCall()

    // Side work (#1013)
    /** A side question over a snapshot of the conversation, tools off: `btw.complete`. */
    data class PromptBtw(val sessionID: String, val text: String) : HermesCall()

    /** A side agent in its own `bg_<id>` session under the Profile: `background.complete`. */
    data class PromptBackground(val sessionID: String, val text: String) : HermesCall()

    // Answers to the host's requests
    data class ApprovalRespond(val sessionID: String, val requestID: String, val choice: BotApprovalChoice) : HermesCall()
    data class RequestAnswer(val id: String, val result: Answer) : HermesCall()
    data class ClarifyLock(val requestID: String, val questionID: String, val answer: String) : HermesCall()
    data class ConnectionRespond(val sessionID: String, val opID: String, val answer: BotConnectionAnswer) : HermesCall()

    // Runtime-scoped session settings
    data class ModelOptions(val sessionID: String, val profile: String) : HermesCall()

    /** The configured-provider inventory the Profile editors pick from. */
    object ConfiguredModelOptions : HermesCall()

    /** One Profile's models, outside any session: the Task editor's picker (#1040). */
    data class ProfileModelOptions(val profile: String) : HermesCall()
    data class ConfigSet(val sessionID: String, val profile: String, val setting: SessionSetting) : HermesCall()
    data class SessionCwdSet(val sessionID: String, val profile: String, val cwd: String) : HermesCall()
    data class SessionControlRead(val sessionID: String, val profile: String) : HermesCall()
    data class SessionControl(val sessionID: String, val profile: String, val action: String) : HermesCall()

    // Composer panels
    data class CommandsCatalog(val sessionID: String) : HermesCall()
    data class CommandDispatch(val name: String, val argument: String, val sessionID: String) : HermesCall()

    /** `timesOutLocally`: the Hermes chat's `@` lookups (#1113) fail only themselves when the host never answers. */
    data class CompletePath(
        val word: String,
        val sessionID: String,
        val profile: String,
        /** True for the composer's optional `@` lookup, whose timeout fails only itself. */
        val localTimeout: Boolean = false,
    ) : HermesCall()

    /** `complete.path` for a host folder outside any session (#1052). */
    data class CompleteFolder(val word: String, val profile: String) : HermesCall()

    /** `complete.slash` at a command's argument stage (#1036): one `/name …` line with a space. */
    data class CompleteSlash(val text: String, val sessionID: String) : HermesCall()

    /** Runs one typed `/name …` line on the session (#1036). */
    data class SlashExec(val sessionID: String, val command: String) : HermesCall()

    // Git (#1115)
    /** A commit message for `diff` from `llm.oneshot`'s `commit_message` template. Answers `{text}`. */
    data class CommitMessage(
        val diff: String,
        val recentCommits: String,
        val avoid: String?,
        val sessionID: String?,
        val profile: String,
    ) : HermesCall()

    // Usage
    /** `{days, sessions, messages}`, or 5017 for a store the host can't open (#1074). */
    data class InsightsGet(val days: Int, val profile: String) : HermesCall()

    // Delegated work
    data class SubagentList(val sessionID: String) : HermesCall()
    data class SubagentTail(val sessionID: String, val subagentID: String) : HermesCall()
    data class SubagentInterrupt(val sessionID: String, val subagentID: String) : HermesCall()

    // Rooms: reads, participation and lifecycle; never peer administration.
    object GroupsCapabilities : HermesCall()
    data class GroupsList(val offset: Int) : HermesCall()
    data class GroupsState(val roomID: String) : HermesCall()
    data class GroupsLog(val roomID: String, val sinceSeq: Int, val limit: Int) : HermesCall()
    data class GroupsSend(val roomID: String, val eventID: String, val text: String, val threadID: String) : HermesCall()
    data class GroupsStop(val roomID: String, val cancelID: String) : HermesCall()
    data class GroupsApprove(
        val roomID: String,
        val memberID: String,
        val taskID: String,
        val executionGeneration: Int,
        val requestID: String,
        val choice: BotApprovalChoice,
    ) : HermesCall()
    data class GroupsRetry(val roomID: String, val taskID: String) : HermesCall()
    data class GroupsCreate(val room: RoomCreation) : HermesCall()
    data class GroupsRename(val roomID: String, val eventID: String, val name: String) : HermesCall()
    data class GroupsDisband(val roomID: String) : HermesCall()

    /** The socket handshake's opt-in to server→client requests. `HermesGateway` sends it itself. */
    object ClientCapabilities : HermesCall()

    /** A new avatar as a base64 data URL, or its removal. */
    sealed class AvatarChange {
        data class Replace(val data: String) : AvatarChange()
        object Clear : AvatarChange()
    }

    /** A model the host resolves under one provider. */
    data class Model(val id: String, val provider: String) {
        internal val isAdmissible: Boolean get() = id.isNotEmpty() && provider.isNotEmpty()
    }

    /** Desktop's `hermes-bots` look object, sent whole under the revision it was read at. */
    data class Look(val fields: Map<String, BotJSON>, val revision: Int)

    /** One `profiles.configure` write. Only the fields that are set are sent. */
    data class ProfileChanges(
        val name: String,
        val description: String? = null,
        val soul: String? = null,
        val model: Model? = null,
        /** Resends a model change the host asked to confirm. Only with `model`. */
        val confirmExpensiveModel: Boolean = false,
        val disabledSkills: List<String>? = null,
        val enabledToolsets: List<String>? = null,
        val enabledMCPServers: List<String>? = null,
        val look: Look? = null,
    ) {
        internal val isAdmissible: Boolean
            get() {
                val changesSomething = description != null || soul != null || model != null || disabledSkills != null ||
                    enabledToolsets != null || enabledMCPServers != null || look != null
                val lists = listOfNotNull(disabledSkills, enabledToolsets, enabledMCPServers)
                return name.isNotEmpty() && changesSomething && model?.isAdmissible != false &&
                    (!confirmExpensiveModel || model != null) &&
                    lists.all { list -> list.all { it.isNotEmpty() } } && (look?.revision ?: 0) >= 0
            }

        internal val params: Map<String, BotJSON>
            get() {
                val params = LinkedHashMap<String, BotJSON>()
                params["name"] = BotJSON.string(name)
                description?.let { params["description"] = BotJSON.string(it) }
                soul?.let { params["soul"] = BotJSON.string(it) }
                model?.let {
                    params["model"] = BotJSON.string(it.id)
                    params["provider"] = BotJSON.string(it.provider)
                    if (confirmExpensiveModel) params["confirm_expensive_model"] = BotJSON.bool(true)
                }
                disabledSkills?.let { params["disabled_skills"] = BotJSON.array(it.map(BotJSON::string)) }
                enabledToolsets?.let { params["enabled_toolsets"] = BotJSON.array(it.map(BotJSON::string)) }
                enabledMCPServers?.let { params["enabled_mcp_servers"] = BotJSON.array(it.map(BotJSON::string)) }
                look?.let {
                    params["ui_meta"] = BotJSON.objectOf("hermes-bots" to BotJSON.objectOf(it.fields))
                    params["ui_meta_expected_revisions"] = BotJSON.objectOf("hermes-bots" to BotJSON.number(it.revision))
                }
                return params
            }
    }

    /** One `profiles.create`. A clone copies its source's skills, so it cannot skip them. */
    data class NewProfile(
        val name: String,
        val description: String? = null,
        val cloneFrom: String? = null,
        val soul: String? = null,
        val model: Model? = null,
        val skipsBundledSkills: Boolean = false,
        /** True shares the default Profile's credentials; false keeps its own, unmirrored. */
        val sharesCredentials: Boolean,
    ) {
        internal val isAdmissible: Boolean
            get() = BotProfileName.isValid(name) && listOf(description, cloneFrom, soul).all { it?.isEmpty() != true } &&
                model?.isAdmissible != false && !(skipsBundledSkills && cloneFrom != null)

        internal val params: Map<String, BotJSON>
            get() {
                val params = LinkedHashMap<String, BotJSON>()
                params["name"] = BotJSON.string(name)
                description?.let { params["description"] = BotJSON.string(it) }
                cloneFrom?.let { params["clone_from"] = BotJSON.string(it) }
                soul?.let { params["soul"] = BotJSON.string(it) }
                model?.let {
                    params["model"] = BotJSON.string(it.id)
                    params["provider"] = BotJSON.string(it.provider)
                }
                if (skipsBundledSkills) params["no_skills"] = BotJSON.bool(true)
                if (sharesCredentials) params["share_auth"] = BotJSON.bool(true) else params["mirror_credentials"] = BotJSON.bool(false)
                return params
            }
    }

    /** `request.answer`'s result: a `value` for sudo, secret and Desktop prompts, an `answer` for an unkeyed clarify. */
    sealed class Answer {
        data class Value(val value: String) : Answer()
        data class Text(val answer: String) : Answer()
    }

    /** The writes `config.set` may carry. All are runtime-scoped except [Personality]. */
    sealed class SessionSetting {
        /** `value` must end in `--session` so the host never writes the Profile's default. */
        data class Model(val value: String, val confirmExpensive: Boolean) : SessionSetting()
        data class Reasoning(val value: String) : SessionSetting()
        data class Fast(val enabled: Boolean) : SessionSetting()

        /** The session's approval bypass: on auto-approves its dangerous commands, off asks again. */
        data class Yolo(val enabled: Boolean) : SessionSetting()

        /** The one write that reaches the Profile, deliberately (#1016). `none` clears it. */
        data class Personality(val name: String) : SessionSetting()
    }

    /** One immutable room creation. A deliberate retry resends the same value. */
    data class RoomCreation(val roomID: String, val name: String, val members: List<RoomMember>) {
        /**
         * Two to six members, each a Profile addressed by its own name (`default` may answer to
         * `hermes`), with no duplicate id, Profile or handle and no handle that collides with
         * `all` or `everyone`.
         */
        internal val isAdmissible: Boolean
            get() {
                if (!BotRoomRPC.validID(roomID) || !BotRoomRPC.validName(name) || members.size !in 2..6) return false
                val ids = HashSet<String>()
                val profiles = HashSet<String>()
                val handles = hashSetOf("all", "everyone")
                return members.all { member ->
                    listOf(member.memberID, member.profile, member.handle).all(BotRoomRPC::validID) &&
                        ids.add(member.memberID.lowercase()) &&
                        profiles.add(member.profile.lowercase()) &&
                        handles.add(member.handle.lowercase()) &&
                        member.memberID == member.profile &&
                        (member.handle == member.profile || (member.profile == "default" && member.handle == "hermes")) &&
                        (member.displayName?.codePointCount(0, member.displayName.length) ?: 0) <= 200
                }
            }
    }

    data class RoomMember(val memberID: String, val profile: String, val handle: String, val displayName: String? = null) {
        internal val json: BotJSON
            get() = botObject(
                "member_id" to BotJSON.string(memberID), "profile" to BotJSON.string(profile),
                "handle" to BotJSON.string(handle), "display_name" to displayName?.let(BotJSON::string),
            )
    }

    /** How the socket treats each request once it is on the wire. */
    enum class Rejection { PLAIN, ROOM, SETTING }

    val method: String
        get() = when (this) {
            is ProfilesList -> "profiles.list"
            is ProfilesDescribe -> "profiles.describe"
            is ProfilesGetAsset -> "profiles.get_asset"
            is ProfilesSetAsset -> "profiles.set_asset"
            is ProfilesConfigure -> "profiles.configure"
            is ProfilesCreate -> "profiles.create"
            is SessionList -> "session.list"
            is SessionCreate, is SessionNew -> "session.create"
            is SessionTitle, is SessionRename -> "session.title"
            is SessionClose -> "session.close"
            is SessionDelete -> "session.delete"
            is SessionBranch -> "session.branch"
            is SessionWorkspaceMove -> "session.workspace.move"
            is ProjectsTree -> "projects.tree"
            is ProjectsCreate -> "projects.create"
            is ProjectsUpdate -> "projects.update"
            is ProjectsDelete -> "projects.delete"
            is SessionResume -> "session.resume"
            is SessionEventsSince -> "session.events.since"
            SessionActiveList -> "session.active_list"
            is SessionMostRecent -> "session.most_recent"
            is PromptSubmit, is PromptRewind -> "prompt.submit"
            is SessionSteer -> "session.steer"
            is SessionRedirect -> "session.redirect"
            is SessionInterrupt -> "session.interrupt"
            is SessionUndo -> "session.undo"
            is SessionCompress -> "session.compress"
            is FileAttach -> "file.attach"
            is PromptBtw -> "prompt.btw"
            is PromptBackground -> "prompt.background"
            is ApprovalRespond -> "approval.respond"
            is RequestAnswer -> "request.answer"
            is ClarifyLock -> "clarify.lock"
            is ConnectionRespond -> "connection.respond"
            is ModelOptions, ConfiguredModelOptions, is ProfileModelOptions -> "model.options"
            is ConfigSet -> "config.set"
            is SessionCwdSet -> "session.cwd.set"
            is SessionControlRead -> "session.control.read"
            is SessionControl -> "session.control"
            is CommandsCatalog -> "commands.catalog"
            is CommandDispatch -> "command.dispatch"
            is CompletePath, is CompleteFolder -> "complete.path"
            is CompleteSlash -> "complete.slash"
            is SlashExec -> "slash.exec"
            is InsightsGet -> "insights.get"
            is CommitMessage -> "llm.oneshot"
            is SubagentList -> "subagent.list"
            is SubagentTail -> "subagent.tail"
            is SubagentInterrupt -> "subagent.interrupt"
            GroupsCapabilities -> "groups.capabilities"
            is GroupsList -> "groups.list"
            is GroupsState -> "groups.state"
            is GroupsLog -> "groups.log"
            is GroupsSend -> "groups.send"
            is GroupsStop -> "groups.stop"
            is GroupsApprove -> "groups.approve"
            is GroupsRetry -> "groups.retry"
            is GroupsCreate -> "groups.create"
            is GroupsRename -> "groups.rename"
            is GroupsDisband -> "groups.disband"
            ClientCapabilities -> "client.capabilities"
        }

    /**
     * The JSON-RPC `params`, after admission, in the shape the host's release takes.
     * [hostVersion] is the `version` `/api/status` reported; null reads as the pin. Throws
     * [BotFailure.Unsupported] for a value the host must never receive from this app.
     */
    @Throws(BotFailure::class)
    fun params(hostVersion: String? = null): Map<String, BotJSON> {
        admit()
        val s = BotJSON::string
        return when (this) {
            is ProfilesList -> mapOf("include_sessions" to BotJSON.bool(includeSessions))
            is ProfilesDescribe -> mapOf("name" to s(name))
            is ProfilesGetAsset -> mapOf("name" to s(name), "asset" to s("avatar"))
            is ProfilesSetAsset -> when (avatar) {
                is AvatarChange.Replace -> mapOf("name" to s(name), "asset" to s("avatar"), "data" to s(avatar.data))
                AvatarChange.Clear -> mapOf("name" to s(name), "asset" to s("avatar"), "clear" to BotJSON.bool(true))
            }
            is ProfilesConfigure -> changes.params
            is ProfilesCreate -> profile.params
            is SessionList -> mapOf("profile" to s(profile), "title" to s(BOT_CHAT_TITLE), "include_hidden" to BotJSON.bool(true))
            is SessionCreate -> mapOf(
                "profile" to s(profile), "title" to s(BOT_CHAT_TITLE),
                "hidden" to BotJSON.bool(true), "follow_profile_config" to BotJSON.bool(true), "source" to s(SOURCE),
            )
            is SessionNew -> linkedMapOf("profile" to s(profile), "source" to s(SOURCE)).also { params ->
                cwd?.let { params["cwd"] = s(it) }
                model?.let { params["model"] = s(it.id); params["provider"] = s(it.provider) }
            }
            is SessionMostRecent -> mapOf("profile" to s(profile))
            is SessionTitle -> mapOf("session_id" to s(sessionID), "title" to s(BOT_CHAT_TITLE))
            is SessionRename -> mapOf("session_id" to s(runtime), "title" to s(title))
            is SessionClose -> mapOf("session_id" to s(runtime))
            is SessionUndo -> mapOf("session_id" to s(runtime))
            is SessionDelete -> mapOf("session_id" to s(storedKey), "profile" to s(profile))
            is SessionBranch -> linkedMapOf("session_id" to s(runtime)).also { params ->
                name?.let { params["name"] = s(it) }
                count?.let { params["count"] = BotJSON.number(it) }
            }
            is SessionWorkspaceMove -> mapOf("session_key" to s(storedKey), "cwd" to s(cwd), "profile" to s(profile))
            is ProjectsTree -> mapOf("profile" to s(profile))
            is ProjectsCreate -> linkedMapOf(
                "profile" to s(profile), "name" to s(name),
                "folders" to BotJSON.array(listOf(s(folder))), "primary_path" to s(folder),
            ).also { params -> color?.let { params["color"] = s(it) } }
            is ProjectsUpdate -> linkedMapOf("profile" to s(profile), "id" to s(id), "name" to s(name))
                .also { params -> color?.let { params["color"] = s(it) } }
            is ProjectsDelete -> mapOf("profile" to s(profile), "id" to s(id))
            is SessionCompress -> linkedMapOf("session_id" to s(runtime), "profile" to s(profile))
                .also { params -> focus?.let { params["focus_topic"] = s(it) } }
            is SessionResume -> linkedMapOf(
                "profile" to s(profile), "session_id" to s(sessionID),
                "close_on_disconnect" to BotJSON.bool(false), "source" to s(SOURCE),
            ).also { params -> if (omitMessages) params["omit_messages"] = BotJSON.bool(true) }
            is SessionEventsSince -> mapOf("session_id" to s(sessionID), "last_seen" to BotJSON.number(lastSeen))
            SessionActiveList, GroupsCapabilities -> emptyMap()
            is PromptSubmit -> mapOf("session_id" to s(sessionID), "text" to s(text), "queued" to BotJSON.bool(true))
            is PromptRewind -> linkedMapOf(
                // `confirm_empty_truncate` too, as Desktop sends whenever it names a row:
                // cutting at the first prompt legitimately empties the transcript.
                "session_id" to s(sessionID), "text" to s(text), "truncate_before_row_id" to BotJSON.number(beforeRowID),
                "confirm_truncate" to BotJSON.bool(true), "confirm_empty_truncate" to BotJSON.bool(true),
            ).also { params -> if (deep) params["confirm_deep_truncate"] = BotJSON.bool(true) }
            is SessionSteer -> mapOf("session_id" to s(sessionID), "text" to s(text))
            is SessionRedirect -> mapOf("session_id" to s(sessionID), "text" to s(text))
            is PromptBtw -> mapOf("session_id" to s(sessionID), "text" to s(text))
            is PromptBackground -> mapOf("session_id" to s(sessionID), "text" to s(text))
            is SessionInterrupt -> mapOf("session_id" to s(sessionID))
            is CommandsCatalog -> mapOf("session_id" to s(sessionID))
            is SubagentList -> mapOf("session_id" to s(sessionID))
            is FileAttach -> mapOf("session_id" to s(sessionID), "name" to s(name), "data_url" to s(dataURL))
            is ApprovalRespond -> mapOf("session_id" to s(sessionID), "request_id" to s(requestID), "choice" to s(choice.rawValue))
            is RequestAnswer -> when (result) {
                is Answer.Value -> mapOf("id" to s(id), "result" to BotJSON.objectOf("value" to s(result.value)))
                is Answer.Text -> mapOf("id" to s(id), "result" to BotJSON.objectOf("answer" to s(result.answer)))
            }
            is ClarifyLock -> mapOf("request_id" to s(requestID), "question_id" to s(questionID), "answer" to s(answer))
            is ConnectionRespond -> linkedMapOf("op_id" to s(opID), "result" to answer.result).also { params ->
                // 0.21.5 names the session as an `owner`; 0.21.4 took a bare `session_id`.
                // Each refuses the other's key, so send the one the host's release expects.
                if (predatesSessionOwner(hostVersion)) {
                    params["session_id"] = s(sessionID)
                } else {
                    params["owner"] = BotJSON.objectOf("type" to s("session"), "session_id" to s(sessionID))
                }
            }
            is ModelOptions -> mapOf("session_id" to s(sessionID), "profile" to s(profile))
            is SessionControlRead -> mapOf("session_id" to s(sessionID), "profile" to s(profile))
            ConfiguredModelOptions -> mapOf("include_unconfigured" to BotJSON.bool(false))
            is ProfileModelOptions -> mapOf("profile" to s(profile))
            is ConfigSet -> linkedMapOf("session_id" to s(sessionID), "profile" to s(profile)).also { params ->
                if (setting !is SessionSetting.Personality) params["scope"] = s("session")
                when (setting) {
                    is SessionSetting.Model -> {
                        params["key"] = s("model"); params["value"] = s(setting.value)
                        params["confirm_expensive_model"] = BotJSON.bool(setting.confirmExpensive)
                    }
                    is SessionSetting.Reasoning -> { params["key"] = s("reasoning"); params["value"] = s(setting.value) }
                    is SessionSetting.Fast -> { params["key"] = s("fast"); params["value"] = s(if (setting.enabled) "fast" else "normal") }
                    is SessionSetting.Yolo -> { params["key"] = s("yolo"); params["value"] = s(if (setting.enabled) "on" else "off") }
                    is SessionSetting.Personality -> { params["key"] = s("personality"); params["value"] = s(setting.name) }
                }
            }
            is SessionCwdSet -> mapOf("session_id" to s(sessionID), "profile" to s(profile), "cwd" to s(cwd))
            is SessionControl -> mapOf("session_id" to s(sessionID), "profile" to s(profile), "action" to s(action))
            is CommandDispatch -> mapOf("name" to s(name), "arg" to s(argument), "session_id" to s(sessionID))
            is CompletePath -> mapOf("word" to s(word), "session_id" to s(sessionID), "profile" to s(profile))
            is CompleteFolder -> mapOf("word" to s(word), "profile" to s(profile))
            is CompleteSlash -> mapOf("text" to s(text), "session_id" to s(sessionID))
            is SlashExec -> mapOf("session_id" to s(sessionID), "command" to s(command))
            is InsightsGet -> mapOf("days" to BotJSON.number(days), "profile" to s(profile))
            is CommitMessage -> {
                val variables = linkedMapOf("diff" to s(diff), "recent_commits" to s(recentCommits))
                avoid?.let { variables["avoid"] = s(it) }
                linkedMapOf(
                    "template" to s("commit_message"), "variables" to BotJSON.objectOf(variables),
                    "temperature" to BotJSON.number(0.8), "profile" to s(profile),
                ).also { params -> sessionID?.let { params["session_id"] = s(it) } }
            }
            is SubagentTail -> mapOf("session_id" to s(sessionID), "subagent_id" to s(subagentID))
            is SubagentInterrupt -> mapOf("session_id" to s(sessionID), "subagent_id" to s(subagentID))
            is GroupsList -> mapOf("limit" to BotJSON.number(ROOM_PAGE_SIZE), "offset" to BotJSON.number(offset))
            is GroupsState -> mapOf("room_id" to s(roomID))
            is GroupsDisband -> mapOf("room_id" to s(roomID))
            is GroupsLog -> mapOf("room_id" to s(roomID), "since_seq" to BotJSON.number(sinceSeq), "limit" to BotJSON.number(limit))
            is GroupsSend -> mapOf(
                "room_id" to s(roomID), "event_id" to s(eventID),
                "payload" to BotJSON.objectOf("text" to s(text), "thread_id" to s(threadID)),
            )
            is GroupsStop -> mapOf("room_id" to s(roomID), "cancel_id" to s(cancelID))
            is GroupsApprove -> mapOf(
                "room_id" to s(roomID), "member_id" to s(memberID), "task_id" to s(taskID),
                "execution_generation" to BotJSON.number(executionGeneration),
                "request_id" to s(requestID), "choice" to s(choice.rawValue),
            )
            is GroupsRetry -> mapOf("room_id" to s(roomID), "task_id" to s(taskID))
            is GroupsCreate -> mapOf(
                "room_id" to s(room.roomID), "name" to s(room.name), "members" to BotJSON.array(room.members.map { it.json }),
            )
            is GroupsRename -> mapOf("room_id" to s(roomID), "event_id" to s(eventID), "name" to s(name))
            ClientCapabilities -> mapOf("server_requests" to BotJSON.bool(true))
        }
    }

    /**
     * Reads and uploads that never control agent execution. Cancelling one discards its late
     * reply; cancelling any other call ends its screen's part, because its outcome is unknown.
     */
    val isCancellationSafe: Boolean
        get() = when (this) {
            is FileAttach, is CompletePath, is CompleteFolder, is CompleteSlash, is SubagentList, is SubagentTail,
            SessionActiveList, is SessionMostRecent, is ProfilesList, is ProfileModelOptions, is ProjectsTree,
            is CommitMessage,
            -> true
            else -> false
        }

    /**
     * Optional reads, and slash commands, whose timeout fails only that request. Any other
     * call that times out ends its screen's connection; the socket stays for the others.
     */
    val timesOutLocally: Boolean
        get() = when (this) {
            is SubagentList, is SubagentTail, SessionActiveList, is SessionMostRecent, is CompleteSlash, is SlashExec,
            is ProfileModelOptions, is ProjectsTree, is CompleteFolder, is CommitMessage,
            -> true
            is CompletePath -> localTimeout
            else -> false
        }

    /**
     * How long a reply may take, in milliseconds. A slash command waits twice the usual
     * deadline; `session.compress` (#1050) waits as long as Desktop does (660 s); a commit
     * message (#1115) waits two minutes.
     */
    fun deadlineMillis(standardMillis: Long): Long = when (this) {
        is SlashExec -> standardMillis * 2
        is CommitMessage -> 120_000L
        is SessionCompress -> 660_000L
        else -> standardMillis
    }

    /**
     * Room rejections carry the host's reason as `BotRoomFailure`; setting rejections carry
     * its message as `BotSettingFailure.Rejected`, as do a refused `/goal`, slash command,
     * `/title`, rewind, `/undo`, `/compress`, project change or move, and branch.
     */
    val rejection: Rejection
        get() = when {
            method.startsWith("groups.") -> Rejection.ROOM
            this is ConfigSet || this is SessionCwdSet || this is SessionControl || this is ModelOptions ||
                this === ConfiguredModelOptions || this is ProfileModelOptions || this is SessionControlRead -> Rejection.SETTING
            this is CommandDispatch && name == "goal" -> Rejection.SETTING
            this is SlashExec || this is SessionRename || this is PromptRewind || this is SessionUndo ||
                this is SessionCompress || this is SessionWorkspaceMove || this is ProjectsCreate ||
                this is ProjectsUpdate || this is ProjectsDelete || this is SessionBranch -> Rejection.SETTING
            else -> Rejection.PLAIN
        }

    /** The value rules each case's types cannot express. A case with none is valid. */
    private fun admit() {
        val valid: Boolean = when (this) {
            is ProfilesDescribe -> name.isNotEmpty()
            is ProfilesSetAsset -> when (avatar) {
                // Base64 for the server's 2 MB decoded cap, with small data-URL headroom.
                is AvatarChange.Replace -> name.isNotEmpty() && avatar.data.isNotEmpty() && avatar.data.toByteArray().size <= 3_000_000
                AvatarChange.Clear -> name.isNotEmpty()
            }
            is ProfilesConfigure -> changes.isAdmissible
            is ProfilesCreate -> profile.isAdmissible
            is SessionCreate -> profile.isNotEmpty()
            is SessionMostRecent -> profile.isNotEmpty()
            is SessionNew -> profile.isNotEmpty() && cwd?.isEmpty() != true && model?.isAdmissible != false
            is SessionCompress -> runtime.isNotEmpty() && profile.isNotEmpty() && focus?.isBlank() != true
            is SessionTitle -> sessionID.isNotEmpty()
            is CommandsCatalog -> sessionID.isNotEmpty()
            is SubagentList -> sessionID.isNotEmpty()
            is SessionClose -> runtime.isNotEmpty()
            is SessionUndo -> runtime.isNotEmpty()
            is SessionRename -> runtime.isNotEmpty() && title.isNotBlank()
            is SessionDelete -> profile.isNotEmpty() && storedKey.isNotEmpty()
            is SessionBranch -> runtime.isNotEmpty() && name?.isBlank() != true && (count ?: 1) > 0
            is SessionWorkspaceMove -> profile.isNotEmpty() && storedKey.isNotEmpty() && cwd.isNotBlank()
            is ProjectsTree -> profile.isNotEmpty()
            is ProjectsCreate -> profile.isNotEmpty() && name.isNotBlank() && folder.isNotBlank() && color?.isEmpty() != true
            is ProjectsUpdate -> profile.isNotEmpty() && id.isNotEmpty() && name.isNotBlank() && color?.isEmpty() != true
            is ProjectsDelete -> profile.isNotEmpty() && id.isNotEmpty()
            is CompleteFolder -> HermesFolderCompletion.completes(word) && profile.isNotEmpty()
            is ConfigSet -> when (setting) {
                is SessionSetting.Model -> sessionID.isNotEmpty() && setting.value.endsWith(" --session")
                is SessionSetting.Reasoning -> sessionID.isNotEmpty() && setting.value in HermesModelCatalog.EFFORT_LEVELS
                is SessionSetting.Fast, is SessionSetting.Yolo -> sessionID.isNotEmpty()
                is SessionSetting.Personality -> sessionID.isNotEmpty() && setting.name.isNotBlank()
            }
            // One bare name, so this never widens into the general slash runner: the gateway
            // resolves quick commands, which can run shell, ahead of skills.
            is CommandDispatch -> name.isNotEmpty() && !name.startsWith("/") && name.none { it.isWhitespace() } && sessionID.isNotEmpty()
            // The argument stage only: a named command, then a space.
            is CompleteSlash -> sessionID.isNotEmpty() && isSlashLine(text) && text.getOrNull(1)?.isWhitespace() == false && text.contains(" ")
            is SlashExec -> sessionID.isNotEmpty() && isSlashLine(command) && command.getOrNull(1)?.isWhitespace() == false
            is CompletePath -> word.isNotEmpty() && word.none { it.isWhitespace() } && sessionID.isNotEmpty() && profile.isNotEmpty()
            is SubagentTail -> sessionID.isNotEmpty() && subagentID.isNotEmpty()
            is SubagentInterrupt -> sessionID.isNotEmpty() && subagentID.isNotEmpty()
            is ConnectionRespond -> {
                val answerIsValid = when (answer) {
                    BotConnectionAnswer.ContinueWithout -> true
                    is BotConnectionAnswer.Skip -> answer.target.isNotEmpty()
                    is BotConnectionAnswer.Connect ->
                        answer.target.isNotEmpty() && answer.env.all { it.key.isNotEmpty() && it.value.isNotEmpty() }
                }
                sessionID.isNotEmpty() && opID.isNotEmpty() && answerIsValid
            }
            is PromptRewind -> sessionID.isNotEmpty() && text.isNotBlank() && beforeRowID > 0
            // The host refuses empty text (4012).
            is PromptBtw -> sessionID.isNotEmpty() && text.isNotBlank()
            is PromptBackground -> sessionID.isNotEmpty() && text.isNotBlank()
            is GroupsList -> offset >= 0
            is ProfileModelOptions -> profile.isNotEmpty()
            is InsightsGet -> days in 1..365 && profile.isNotEmpty()
            is CommitMessage -> diff.isNotBlank() && avoid?.isBlank() != true && sessionID?.isEmpty() != true && profile.isNotEmpty()
            is GroupsState -> BotRoomRPC.validID(roomID)
            is GroupsDisband -> BotRoomRPC.validID(roomID)
            is GroupsLog -> BotRoomRPC.validID(roomID) && sinceSeq >= 0 && limit in 1..ROOM_PAGE_SIZE
            is GroupsSend -> listOf(roomID, eventID, threadID).all(BotRoomRPC::validID) && BotRoomRPC.validText(text)
            is GroupsStop -> BotRoomRPC.validID(roomID) && BotRoomRPC.validID(cancelID)
            is GroupsApprove -> listOf(roomID, memberID, taskID, requestID).all(BotRoomRPC::validID) && executionGeneration > 0 &&
                choice in setOf(BotApprovalChoice.ONCE, BotApprovalChoice.DENY)
            is GroupsRetry -> BotRoomRPC.validID(roomID) && BotRoomRPC.validID(taskID)
            is GroupsCreate -> room.isAdmissible
            is GroupsRename -> BotRoomRPC.validID(roomID) && BotRoomRPC.validID(eventID) && BotRoomRPC.validName(name)
            is ProfilesList, is ProfilesGetAsset, is SessionList, is SessionResume, is SessionEventsSince, SessionActiveList,
            is PromptSubmit, is SessionSteer, is SessionRedirect, is SessionInterrupt, is FileAttach, is ApprovalRespond,
            is RequestAnswer, is ClarifyLock, is ModelOptions, ConfiguredModelOptions, is SessionCwdSet, is SessionControlRead,
            is SessionControl, GroupsCapabilities, ClientCapabilities,
            -> true
        }
        if (!valid) throw BotFailure.Unsupported
    }

    companion object {
        /** The exact title of a bot's one canonical chat, as Desktop names it. */
        const val BOT_CHAT_TITLE = "Bot Chat"

        /** Room lists are read in the host's largest page. */
        const val ROOM_PAGE_SIZE = 500

        /**
         * The surface every session Hermex creates or resumes runs as (#1278). The host picks
         * the agent's note about the screen it is talking to by this name.
         */
        const val SOURCE = "hermex"

        /**
         * Whether `version` is a release before 0.21.5, which moved `connection.respond`'s
         * `session_id` into `owner`. A missing, partial or unreadable one reads as the pin.
         */
        private fun predatesSessionOwner(version: String?): Boolean =
            HermesCompatibility.release(version)?.lexicographicallyPrecedes(listOf(0, 21, 5)) ?: false

        /** One line that opens with `/` and names something after it. */
        private fun isSlashLine(text: String): Boolean =
            text.length > 1 && text.startsWith("/") && text.none { it == '\n' || it == '\r' }
    }
}

/**
 * Port of `BotApprovalRequest.Choice` (`Features/Bots/BotPendingRequest.swift`): the host's
 * wire vocabulary for `approval.respond`'s `choice`.
 */
enum class BotApprovalChoice(val rawValue: String) {
    ONCE("once"),
    SESSION("session"),
    ALWAYS("always"),
    DENY("deny"),
    ;

    /** True for the choice that writes a permanent rule into the host's config. */
    val isPermanent: Boolean get() = this == ALWAYS

    companion object {
        fun fromRawValue(rawValue: String?): BotApprovalChoice? = entries.firstOrNull { it.rawValue == rawValue }
    }
}

/**
 * Port of `BotConnectionOperation.Answer` (`BotPendingRequest.swift`): one row's answer, or
 * Continue, on its way to `connection.respond`.
 */
sealed class BotConnectionAnswer {
    data class Connect(val target: String, val env: Map<String, String>) : BotConnectionAnswer()
    data class Skip(val target: String) : BotConnectionAnswer()

    /** Settles the whole operation now; unresolved rows become `not_connected`. */
    object ContinueWithout : BotConnectionAnswer()

    /** The `result` object: one row per answer, or only `settled_by`. */
    val result: BotJSON
        get() = when (this) {
            is Connect -> {
                val row = linkedMapOf("name" to BotJSON.string(target), "status" to BotJSON.string("approved"))
                if (env.isNotEmpty()) row["env"] = BotJSON.objectOf(env.mapValues { BotJSON.string(it.value) })
                BotJSON.objectOf("targets" to BotJSON.array(listOf(BotJSON.objectOf(row))))
            }
            is Skip -> BotJSON.objectOf(
                "targets" to BotJSON.array(listOf(BotJSON.objectOf("name" to BotJSON.string(target), "status" to BotJSON.string("skipped")))),
            )
            ContinueWithout -> BotJSON.objectOf("settled_by" to BotJSON.string("continue"))
        }
}

/** Port of `BotRoomRPC` (`Features/Bots/BotGroupRoom.swift`): `HermesCall`'s room admission. */
object BotRoomRPC {
    private val ID = Regex("\\A[A-Za-z0-9][A-Za-z0-9._:-]{0,127}\\z")

    fun validID(id: String): Boolean = ID.matches(id)

    fun validText(text: String): Boolean = text.isNotBlank() && text.toByteArray().size <= 64 * 1024

    fun validName(name: String): Boolean = name.isNotBlank() && name.codePointCount(0, name.length) <= 200
}

/**
 * Port of `BotProfileName` (`Features/Bots/BotCreator.swift`): the host's rules for a new
 * Profile's name.
 */
object BotProfileName {
    val reserved: Set<String> = setOf("hermes", "default", "test", "tmp", "root", "sudo")

    fun isValid(name: String): Boolean {
        val first = name.firstOrNull() ?: return false
        if (name.length > 64 || name in reserved) return false
        if (first !in 'a'..'z' && first !in '0'..'9') return false
        return name.all { it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '_' }
    }

    /**
     * `slug(from:)` — the host-shaped name the creator derives from the title the user
     * types: diacritics folded, lowercased, runs of anything else collapsed to one `-`.
     */
    fun slug(title: String): String {
        val folded = java.text.Normalizer.normalize(title, java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase()
        val builder = StringBuilder()
        var pendingDash = false
        for (char in folded) {
            if (char in 'a'..'z' || char in '0'..'9' || char == '_') {
                if (pendingDash && builder.isNotEmpty()) builder.append('-')
                pendingDash = false
                builder.append(char)
            } else {
                pendingDash = true
            }
        }
        return builder.toString().take(64)
    }
}

/**
 * Port of `BotRoomFailure` (`BotGroupRoom.swift`): a room RPC the host refused, with its reason.
 */
class BotRoomFailure(val code: Int, val reason: String?) : Exception() {
    val expired: Boolean get() = code == 4114 || reason == "room_history_expired"

    override val message: String
        get() = when {
            expired -> "This room’s history is no longer available."
            code == 4123 -> "Restart the Hermes gateway on your Mac, then reconnect."
            else -> BotFailure.Rejected(code).message
        }
}

/**
 * Port of `BotSettingFailure` (`Features/Bots/BotChatSettings.swift`): a rejected setting
 * preserves the host's explanation.
 */
sealed class BotSettingFailure : Exception() {
    data class Rejected(val code: Int, val reason: String) : BotSettingFailure()
    object UnknownOutcome : BotSettingFailure() {
        private fun readResolve(): Any = UnknownOutcome
    }

    override val message: String
        get() = when (this) {
            is Rejected -> reason
            UnknownOutcome -> "The change was not confirmed. Check its current value before trying again."
        }
}
