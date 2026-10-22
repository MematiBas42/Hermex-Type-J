package io.github.jcartu.hermex.model.hermes

import io.github.jcartu.hermex.net.hermes.BotApprovalChoice
import io.github.jcartu.hermex.net.hermes.BotConnectionAnswer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.ceil
import kotlin.math.max

/**
 * Port of `HermesRequestSubject` (`Features/Chat/HermesRequestCards.swift`): whose request a
 * card describes, so its copy names the bot in a bot's chat and Hermes in any other chat (#1141).
 */
enum class HermesRequestSubject { HERMES, BOT }

/**
 * Port of `BotPendingRequest` (`Features/Bots/BotPendingRequest.swift`): a request that parks
 * a bot's work until someone answers it. Requests arrive as server-request envelopes, live or
 * restored from `open_requests`. A connection operation is the one kind that is not a server
 * request: see [BotConnectionOperation]. Only [DesktopTask] is unanswerable: the answer is
 * data only Hermes Desktop's own window holds.
 */
sealed class BotPendingRequest {
    data class Approval(val request: BotApprovalRequest) : BotPendingRequest()
    data class Question(val request: BotQuestionRequest) : BotPendingRequest()
    data class Credential(val request: BotCredentialRequest) : BotPendingRequest()
    data class DesktopTask(val request: BotDesktopTaskRequest) : BotPendingRequest()
    data class Connection(val operation: BotConnectionOperation) : BotPendingRequest()

    /** The host's id for this request: the envelope id, approval's queue id, or an operation's `op_id`. */
    val requestID: String
        get() = when (this) {
            is Approval -> request.requestID
            is Question -> request.requestID
            is Credential -> request.requestID
            is DesktopTask -> request.requestID
            is Connection -> operation.opID
        }

    /** False only for the kinds whose answer lives in the Desktop renderer. */
    val isAnswerable: Boolean get() = this !is DesktopTask
}

/**
 * Port of `BotServerRequest`: a server request, received live or restored from `open_requests`.
 * The envelope id stays apart from an approval's underlying queue request id. An unknown
 * method keeps [pending] null: the bot is still blocked, and the phone never answers it, not
 * even with -32601, because a reply from here would pre-empt the Hermes Desktop window that
 * can (#1148). Password-vault prompts (`vault.*`) are unknown on purpose.
 */
data class BotServerRequest(
    val id: String,
    val method: String,
    val sessionID: String,
    val pending: BotPendingRequest?,
) {
    companion object {
        /** `init?(_ frame:)` — null without a string id, a method and a `params.session_id`. */
        fun from(frame: BotJSON): BotServerRequest? {
            val id = frame["id"].text?.takeIf { it.isNotEmpty() } ?: return null
            val method = frame["method"].text?.takeIf { it.isNotEmpty() } ?: return null
            val params = frame["params"].fields ?: return null
            val sessionID = params["session_id"]?.text?.takeIf { it.isNotEmpty() } ?: return null
            val pending: BotPendingRequest? = when {
                method == "approval" -> BotApprovalRequest.from(frame["params"])?.let(BotPendingRequest::Approval)
                method == "clarify" -> {
                    val withID = BotJSON.objectOf(params + ("request_id" to BotJSON.string(id)))
                    BotQuestionRequest.from(withID)?.let(BotPendingRequest::Question)
                }
                BotCredentialRequest.Kind.fromRawValue(method) != null -> BotPendingRequest.Credential(
                    BotCredentialRequest(
                        kind = BotCredentialRequest.Kind.fromRawValue(method)!!,
                        requestID = id,
                        envVar = trimmed(params["env_var"]),
                        prompt = trimmed(params["prompt"]),
                    ),
                )
                BotDesktopTaskRequest.Kind.fromRawValue(method) != null -> BotPendingRequest.DesktopTask(
                    BotDesktopTaskRequest(BotDesktopTaskRequest.Kind.fromRawValue(method)!!, id),
                )
                else -> null
            }
            return BotServerRequest(id, method, sessionID, pending)
        }

        private fun trimmed(json: BotJSON?): String? = json?.text?.trim()?.takeIf { it.isNotEmpty() }
    }
}

/**
 * Port of `BotApprovalRequest`: one pending dangerous-command approval. The host precomputes
 * `choices` from its own smart-approval and permanent-allow policy, so the phone offers
 * exactly what the host offered rather than inventing a fifth option (#1104).
 */
data class BotApprovalRequest(
    val requestID: String,
    /** The command as the host redacted it. Null when the gate was not command-shaped. */
    val command: String?,
    /** Why the host flagged this action, from `description`. */
    val consequence: String?,
    /** Server order, `once` first and `deny` last. Never empty. */
    val choices: List<BotApprovalChoice>,
    /** What Allow session and Always allow would allowlist: `pattern_keys`, else `[pattern_key]`. */
    val patternKeys: List<String>,
    /** The gated tool, when the host names it. */
    val toolName: String?,
) {
    val offersSession: Boolean get() = BotApprovalChoice.SESSION in choices
    val offersAlways: Boolean get() = BotApprovalChoice.ALWAYS in choices

    companion object {
        /** `init?(_ json:)` — null when the payload carries no usable request id. */
        fun from(json: BotJSON): BotApprovalRequest? {
            val id = json["request_id"].text?.takeIf { it.isNotEmpty() } ?: return null
            val keys = (json["pattern_keys"].list ?: emptyList()).mapNotNull { trimmed(it) }
            val patternKeys = if (keys.isEmpty()) listOfNotNull(trimmed(json["pattern_key"])) else keys
            return BotApprovalRequest(
                requestID = id,
                command = trimmed(json["command"]),
                consequence = trimmed(json["description"]),
                choices = choices(json),
                patternKeys = patternKeys,
                toolName = trimmed(json["tool_name"]),
            )
        }

        /**
         * A present `choices` is the host speaking, and nothing may be added to what it
         * offered. If none of it parses, Deny is the only thing left that is safe to offer:
         * rebuilding here would invent an Always allow the host never sanctioned. Only an
         * absent `choices` is an older host, whose set is rebuilt from its flags.
         */
        fun choices(json: BotJSON): List<BotApprovalChoice> {
            val present = json.fields?.get("choices")
            if (present != null && present.list == null) return listOf(BotApprovalChoice.DENY)
            val list = present?.list
            if (list != null) {
                val offered = list.mapNotNull { BotApprovalChoice.fromRawValue(it.text) }.distinct()
                if (offered.isEmpty()) return listOf(BotApprovalChoice.DENY)
                return if (BotApprovalChoice.DENY in offered) offered else offered + BotApprovalChoice.DENY
            }
            val rebuilt = ArrayList<BotApprovalChoice>()
            rebuilt += BotApprovalChoice.ONCE
            if (json["smart_denied"].flag != true && json["allow_session"].flag != false) {
                rebuilt += BotApprovalChoice.SESSION
                if (json["allow_permanent"].flag != false) rebuilt += BotApprovalChoice.ALWAYS
            }
            rebuilt += BotApprovalChoice.DENY
            return rebuilt
        }

        private fun trimmed(json: BotJSON): String? = json.text?.trim()?.takeIf { it.isNotEmpty() }
    }
}

/**
 * Port of `BotQuestionRequest`: one pending clarify request, in the single-question shape
 * (`question`/`choices`/`multi_select`) or the batch shape (`questions`), which locks one
 * answer per question id and carries the locked ones back on reconnect.
 */
data class BotQuestionRequest(
    val requestID: String,
    val questions: List<Question>,
) {
    /** One offered answer. [wireLabel] goes back to the host verbatim. */
    data class Choice(val id: Int, val wireLabel: String) {
        val isRecommended: Boolean
        val label: String

        init {
            val trimmed = wireLabel.trim()
            isRecommended = trimmed.lowercase().endsWith(RECOMMENDED_SUFFIX.lowercase())
            label = if (isRecommended) trimmed.dropLast(RECOMMENDED_SUFFIX.length).trim() else trimmed
        }

        companion object {
            const val RECOMMENDED_SUFFIX = "(Recommended)"
        }
    }

    data class Question(
        /** The host's `qid`. Null for the single-question shape. */
        val wireID: String?,
        val prompt: String,
        /** Empty means open-ended: free text is the only answer. */
        val choices: List<Choice>,
        val allowsMultipleChoices: Boolean,
        /** An answer already locked on the host, replayed so a reconnect restores it. */
        val lockedAnswer: String?,
    ) {
        val id: String get() = wireID ?: ""
        val isAnswered: Boolean get() = lockedAnswer != null
    }

    /** Batch questions lock one answer per question id (`clarify.lock`). */
    val isBatch: Boolean get() = questions.firstOrNull()?.wireID != null
    val unansweredCount: Int get() = questions.count { !it.isAnswered }

    companion object {
        /** `init?(_ json:)` — null without a request id or a readable question. */
        fun from(json: BotJSON): BotQuestionRequest? {
            val id = json["request_id"].text?.takeIf { it.isNotEmpty() } ?: return null
            val locked = json["answers"]
            val rows = json["questions"].list
            if (!rows.isNullOrEmpty()) {
                val parsed = rows.mapNotNull { row ->
                    val qid = (row["qid"].text ?: row["id"].text)?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                    val prompt = trimmed(row["question"]) ?: return@mapNotNull null
                    Question(
                        wireID = qid, prompt = prompt, choices = choices(row["choices"]),
                        allowsMultipleChoices = row["multi_select"].flag == true,
                        lockedAnswer = locked[qid].text,
                    )
                }
                if (parsed.isEmpty()) return null
                return BotQuestionRequest(id, parsed)
            }
            val prompt = trimmed(json["question"]) ?: return null
            return BotQuestionRequest(
                id,
                listOf(
                    Question(
                        wireID = null, prompt = prompt, choices = choices(json["choices"]),
                        allowsMultipleChoices = json["multi_select"].flag == true, lockedAnswer = null,
                    ),
                ),
            )
        }

        private fun choices(json: BotJSON): List<Choice> =
            (json.list ?: emptyList()).mapIndexedNotNull { index, row ->
                trimmed(row)?.let { Choice(index, it) }
            }

        private fun trimmed(json: BotJSON): String? = json.text?.trim()?.takeIf { it.isNotEmpty() }
    }
}

/**
 * Port of `BotCredentialRequest`: a value only the person can supply, the Mac's administrator
 * password or a secret the bot asked for by name. Answers carry `{value}` through
 * `request.answer`; an empty value skips without retaining a secret.
 */
data class BotCredentialRequest(
    val kind: Kind,
    val requestID: String,
    /** `secret` only: the name the host stores the value under. */
    val envVar: String?,
    /** `secret` only: the host's own words for what it wants. */
    val prompt: String?,
) {
    enum class Kind(val rawValue: String) {
        SUDO("sudo"),
        SECRET("secret"),
        ;

        /** What skipping costs, so declining is an informed choice too. */
        val skipConsequence: String
            get() = when (this) {
                SUDO -> "Skip to let the command fail instead."
                SECRET -> "Skip to continue without it."
            }

        companion object {
            fun fromRawValue(value: String): Kind? = entries.firstOrNull { it.rawValue == value }
        }
    }

    val title: String
        get() = when (kind) {
            Kind.SUDO -> "Administrator password needed"
            Kind.SECRET -> "Secret needed"
        }

    /** What the bot is asking for, preferring the host's wording when it sent any. */
    fun detail(subject: HermesRequestSubject): String = when (kind) {
        Kind.SUDO -> "A command on this Mac needs an administrator password to run."
        Kind.SECRET -> prompt ?: when (subject) {
            HermesRequestSubject.BOT -> "This bot needs a secret value to carry on."
            HermesRequestSubject.HERMES -> "Hermes needs a secret value to carry on."
        }
    }

    /** Where the value ends up, stated before it is typed. Neither is ever stored by Hermex. */
    fun handling(subject: HermesRequestSubject): String {
        val owner = when (subject) {
            HermesRequestSubject.BOT -> "this bot's Mac"
            HermesRequestSubject.HERMES -> "the Hermes host"
        }
        return when (kind) {
            Kind.SUDO -> "Sent to $owner to run this command. Hermex never saves it."
            Kind.SECRET -> if (envVar == null) {
                "Saved on $owner. Hermex never saves it."
            } else {
                "Saved on $owner as $envVar. Hermex never saves it."
            }
        }
    }
}

/**
 * Port of `BotDesktopTaskRequest`: a request only Hermes Desktop can answer, work its own
 * window performs. The phone reports the wait rather than sending anyone to a desk.
 */
data class BotDesktopTaskRequest(val kind: Kind, val requestID: String) {
    enum class Kind(val rawValue: String) {
        TOUR("tour"),
        TERMINAL_READ("terminal.read"),
        WINDOW_READ("window.read"),
        PREVIEW_READ("preview.read"),
        PREVIEW_ACT("preview.act"),
        ;

        /** What is happening, in the user's words, naming the bot in a bot's chat and Hermes elsewhere (#1141). */
        fun title(subject: HermesRequestSubject): String {
            val actor = if (subject == HermesRequestSubject.BOT) "This bot" else "Hermes"
            return when (this) {
                TERMINAL_READ -> "$actor is reading a terminal on the Mac."
                WINDOW_READ -> "$actor is reading a window on the Mac."
                PREVIEW_READ -> "$actor is reading the preview pane on the Mac."
                PREVIEW_ACT -> "$actor is using the preview pane on the Mac."
                TOUR -> "$actor is running a tour in Hermes Desktop."
            }
        }

        fun detail(subject: HermesRequestSubject): String = when (subject) {
            HermesRequestSubject.BOT ->
                "Hermes Desktop answers this by itself, and the bot carries on without it if it cannot. There is nothing to do here or at the Mac."
            HermesRequestSubject.HERMES ->
                "Hermes Desktop answers this by itself, and Hermes carries on without it if it cannot. There is nothing to do here or at the Mac."
        }

        companion object {
            fun fromRawValue(value: String): Kind? = entries.firstOrNull { it.rawValue == value }
        }
    }
}

/**
 * Port of `BotConnectionOperation`: a `manage_connections` operation. The bot asked to connect
 * one or more apps, and the host holds the tool until every row is connected or skipped,
 * Continue settles it, Stop interrupts it, or its deadline passes. It is an event plus an RPC:
 * `connection.request` opens it, each `connection.update` carries the full snapshot again, the
 * snapshot's `pending_connection` restores it, and `connection.respond` answers it by `op_id`.
 */
data class BotConnectionOperation(
    val opID: String,
    /** The host's write counter for this operation. A frame that is not newer is older. */
    val seq: Int,
    /** Epoch seconds. */
    val deadline: Double,
    val isSettled: Boolean,
    /** Server order, one row per name. */
    val targets: List<Target>,
) {
    /** One value an MCP install still needs; the card draws a field for each. */
    data class EnvField(
        val name: String,
        val isRequired: Boolean,
        val isSecret: Boolean,
        /** The catalog's value for a non-secret field; the field starts with it. */
        val defaultValue: String?,
        val prompt: String?,
    ) {
        /** What the field holds: the user's edit, else the default it starts with. */
        fun value(values: Map<String, String>): String = values[name] ?: defaultValue ?: ""

        companion object {
            fun from(json: BotJSON): EnvField? {
                val name = json["name"].text?.trim()?.takeIf { it.isNotEmpty() } ?: return null
                // Absent means the host's own defaults: required, and masked to be safe.
                val isSecret = json["secret"].flag ?: true
                return EnvField(
                    name = name,
                    isRequired = json["required"].flag ?: true,
                    isSecret = isSecret,
                    defaultValue = if (isSecret) null else trimmed(json["default"]),
                    prompt = trimmed(json["prompt"]),
                )
            }
        }
    }

    /** One app the bot asked for. An unrecognized value keeps its row readable but offers nothing. */
    data class Target(
        val name: String,
        val kind: Kind?,
        val action: Action?,
        val state: State?,
        val detail: String?,
        val instructions: String?,
        val discoveryError: String?,
        val connectURL: String?,
        val requiredEnv: List<EnvField>,
    ) {
        enum class Kind(val rawValue: String) {
            CONNECTOR("connector"), MCP("mcp");

            companion object {
                fun fromRawValue(value: String?): Kind? = entries.firstOrNull { it.rawValue == value }
            }
        }

        enum class Action(val rawValue: String) {
            AUTHORIZE("authorize"), CONNECT("connect"), ENABLE("enable"), INSTALL("install"), RECONNECT("reconnect");

            companion object {
                fun fromRawValue(value: String?): Action? = entries.firstOrNull { it.rawValue == value }
            }
        }

        enum class State(val rawValue: String) {
            PENDING("pending"), INITIATED("initiated"), CONNECTED("connected"), SKIPPED("skipped"),
            FAILED("failed"), EXPIRED("expired"), UNAVAILABLE("unavailable"), NOT_CONNECTED("not_connected");

            companion object {
                fun fromRawValue(value: String?): State? = entries.firstOrNull { it.rawValue == value }
            }
        }

        /** The host lets the user skip any row still open. Connected and skipped rows are resolved. */
        val canSkip: Boolean
            get() = kind != null && action != null && state in setOf(State.PENDING, State.INITIATED, State.FAILED, State.EXPIRED)

        /** A managed connector's vendor sign-in, finished in the browser. Only an https link opens. */
        val linkToOpen: String?
            get() {
                if (kind != Kind.CONNECTOR || action == null) return null
                if (state != State.PENDING && state != State.INITIATED) return null
                val url = connectURL ?: return null
                return url.takeIf { it.lowercase().startsWith("https://") }
            }

        /** An MCP sign-in, which redirects to a listener on the host's own loopback. */
        val finishesOnTheMac: Boolean
            get() = kind == Kind.MCP && (action == Action.AUTHORIZE || connectURL != null) &&
                state in setOf(State.PENDING, State.INITIATED, State.FAILED)

        /** An MCP enable or install the phone approves, sending a value for each [requiredEnv] field. */
        val canConnect: Boolean
            get() = kind == Kind.MCP && (action == Action.ENABLE || action == Action.INSTALL) &&
                connectURL == null && (state == State.PENDING || state == State.FAILED)

        /** The values Connect sends for what the fields hold: trimmed, and an emptied field left out. */
        fun env(values: Map<String, String>): Map<String, String> {
            val env = LinkedHashMap<String, String>()
            for (field in requiredEnv) {
                val value = field.value(values).trim()
                if (value.isNotEmpty()) env[field.name] = value
            }
            return env
        }

        /** True when this row connects with [env]: every required field filled, nothing undeclared, no empty value. */
        fun accepts(env: Map<String, String>): Boolean {
            if (!canConnect) return false
            val declared = requiredEnv.map { it.name }.toSet()
            return env.keys.all { it in declared } && env.values.none { it.isEmpty() } &&
                requiredEnv.all { !it.isRequired || env[it.name] != null }
        }

        companion object {
            fun from(json: BotJSON): Target? {
                val name = trimmed(json["name"]) ?: return null
                return Target(
                    name = name,
                    kind = Kind.fromRawValue(json["kind"].text),
                    action = Action.fromRawValue(json["action"].text),
                    state = State.fromRawValue(json["state"].text),
                    detail = trimmed(json["detail"]),
                    instructions = trimmed(json["instructions"]),
                    discoveryError = trimmed(json["discovery_error"]),
                    connectURL = json["connect_url"].text?.takeIf { it.isNotEmpty() },
                    requiredEnv = (json["required_env"].list ?: emptyList()).mapNotNull(EnvField::from),
                )
            }
        }
    }

    /** This operation after [frame]: a newer counter for the same `op_id` replaces it whole. */
    fun applying(frame: BotConnectionOperation): BotConnectionOperation =
        if (frame.opID == opID && frame.seq > seq) frame else this

    /** True when the row on screen offers [answer], with every required value filled. */
    fun offers(answer: BotConnectionAnswer): Boolean = when (answer) {
        BotConnectionAnswer.ContinueWithout -> true
        is BotConnectionAnswer.Skip -> targets.firstOrNull { it.name == answer.target }?.canSkip == true
        is BotConnectionAnswer.Connect -> targets.firstOrNull { it.name == answer.target }?.accepts(answer.env) == true
    }

    companion object {
        /** Reads `connection.request`, a `connection.update` or `pending_connection`; null without an id, counter, deadline or row. */
        fun from(json: BotJSON): BotConnectionOperation? {
            val opID = json["op_id"].text?.takeIf { it.isNotEmpty() } ?: return null
            val seq = json["seq"].integer ?: return null
            val deadline = json["deadline_at"].number?.takeIf { it.isFinite() && it > 0 } ?: return null
            val seen = HashSet<String>()
            val targets = (json["targets"].list ?: emptyList()).mapNotNull(Target::from).filter { seen.add(it.name) }
            if (targets.isEmpty()) return null
            return BotConnectionOperation(opID, seq, deadline, json["settled"].flag == true, targets)
        }

        /** Whole minutes left before the host's deadline, never below one. */
        fun minutesLeft(deadlineEpochSeconds: Double, nowEpochSeconds: Double): Int =
            max(1, ceil((deadlineEpochSeconds - nowEpochSeconds) / 60.0).toInt())

        internal fun trimmed(json: BotJSON): String? = json.text?.trim()?.takeIf { it.isNotEmpty() }
    }
}

/**
 * Port of `BotQuestionAnswer`: one question's answer on its way to the host. [questionID] is
 * the host's `qid` for a batch and null for the single-question shape.
 */
data class BotQuestionAnswer(val questionID: String?, val text: String) {
    companion object {
        /** Multi-select answers go over the wire as a JSON array string; a bare array is not part of the contract. */
        fun selections(questionID: String?, selections: List<String>): BotQuestionAnswer =
            BotQuestionAnswer(questionID, JsonArray(selections.map { JsonPrimitive(it) }).toString())
    }
}

/**
 * Port of `BotRequestResolution`: why the request on screen can no longer be acted on,
 * scoped to the request it describes so a newer request never inherits an older verdict.
 */
data class BotRequestResolution(val requestID: String, val outcome: Outcome) {
    enum class Outcome {
        /** The host accepted the answer. The next snapshot removes the request. */
        ANSWERED,

        /** The host had nothing left to resolve: answered on another surface, or expired. */
        ALREADY_RESOLVED,

        /** Delivery failed in a way that cannot distinguish sent from not sent. */
        UNCERTAIN,
    }

    /** True while the request must stay inert; an uncertain outcome warns instead of locking the user out (#1103). */
    val blocksFurtherAnswers: Boolean get() = outcome != Outcome.UNCERTAIN

    fun message(subject: HermesRequestSubject): String = when (outcome) {
        Outcome.ANSWERED -> "Answer sent."
        Outcome.ALREADY_RESOLVED -> "This request was already answered or has expired."
        Outcome.UNCERTAIN -> when (subject) {
            HermesRequestSubject.BOT -> "Answer outcome unknown. Check this bot in Desktop before answering again."
            HermesRequestSubject.HERMES -> "Answer outcome unknown. Check Hermes Desktop before answering again."
        }
    }
}

/**
 * Port of `BotRequestWithdrawal`: a request the host withdrew while it was on screen, and
 * why (#892). The chat leaves [message] where the card stood. Only the family and the host's
 * reason are kept; the note is never cached.
 */
data class BotRequestWithdrawal(val family: Family, val reason: Reason) {
    /** A server request by the identity `request.cancel` names: the envelope id and method. */
    data class Envelope(val id: String, val method: String)

    enum class Family {
        APPROVAL, QUESTION,

        /** sudo, secret and the password-manager prompts. */
        OTHER;

        companion object {
            /** Null for the Desktop renderer's own tasks, whose withdrawal is silent. */
            fun from(method: String): Family? = when (method) {
                "approval" -> APPROVAL
                "clarify" -> QUESTION
                "tour", "terminal.read", "window.read", "preview.read", "preview.act" -> null
                else -> OTHER
            }
        }
    }

    enum class Reason {
        TIMEOUT,

        /** `interrupted` or `session_closed`: the work stopped. */
        STOPPED,
        SHUTDOWN,
        OTHER;

        companion object {
            /** Null for `resolved` and for a cancel that gives no reason: silence is the honest note. */
            fun from(wire: String?): Reason? = when (wire?.trim() ?: "") {
                "", "resolved" -> null
                "timeout" -> TIMEOUT
                "interrupted", "session_closed" -> STOPPED
                "shutdown" -> SHUTDOWN
                else -> OTHER
            }
        }
    }

    val message: String
        get() = when (family) {
            Family.APPROVAL -> when (reason) {
                Reason.TIMEOUT -> "Approval timed out, so it didn't run."
                Reason.STOPPED -> "Approval withdrawn because the work stopped."
                Reason.SHUTDOWN -> "Approval withdrawn because Hermes shut down."
                Reason.OTHER -> "Approval withdrawn."
            }
            Family.QUESTION -> when (reason) {
                Reason.TIMEOUT -> "Question timed out. Hermes carried on without an answer."
                Reason.STOPPED -> "Question withdrawn because the work stopped."
                Reason.SHUTDOWN -> "Question withdrawn because Hermes shut down."
                Reason.OTHER -> "Question withdrawn."
            }
            Family.OTHER -> when (reason) {
                Reason.TIMEOUT -> "Request timed out. Hermes carried on without it."
                Reason.STOPPED -> "Request withdrawn because the work stopped."
                Reason.SHUTDOWN -> "Request withdrawn because Hermes shut down."
                Reason.OTHER -> "Request withdrawn."
            }
        }

    companion object {
        /** Null when this withdrawal leaves no note. */
        fun from(method: String, reason: String?): BotRequestWithdrawal? {
            val family = Family.from(method) ?: return null
            val parsed = Reason.from(reason) ?: return null
            return BotRequestWithdrawal(family, parsed)
        }
    }
}
