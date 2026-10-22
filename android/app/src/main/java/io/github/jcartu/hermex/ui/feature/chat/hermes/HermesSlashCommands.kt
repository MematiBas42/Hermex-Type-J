package io.github.jcartu.hermex.ui.feature.chat.hermes

import io.github.jcartu.hermex.model.AgentCommand
import io.github.jcartu.hermex.model.SkillSlashSuggestion
import io.github.jcartu.hermex.model.SlashSkillFormatter
import io.github.jcartu.hermex.model.hermes.BotFailure
import io.github.jcartu.hermex.model.hermes.BotJSON
import io.github.jcartu.hermex.net.hermes.HermesCall
import io.github.jcartu.hermex.ui.feature.chat.HermesSlashCommandCatalog
import io.github.jcartu.hermex.ui.feature.chat.SlashCommand
import io.github.jcartu.hermex.ui.feature.chat.SlashCommandScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/** Port of `BotSlashInvocation` in `Features/Bots/BotSlashCommands.swift`. */
data class BotSlashInvocation(
    val name: String,
    val argument: String,
)

/**
 * Port of the skill catalog in `Features/Bots/BotSlashCommands.swift`.
 * Registry, plugin and quick-command collisions are excluded because
 * `command.dispatch` resolves those ahead of skills.
 */
object BotSlashCatalog {
    fun skills(reply: BotJSON): List<SkillSlashSuggestion> {
        val entries = reply["skills"].fields?.takeIf { it.isNotEmpty() } ?: return emptyList()
        val shadowed = HashSet<String>()
        for ((alias, target) in reply["canon"].fields.orEmpty()) {
            shadowed += alias.lowercase()
            target.text?.let { shadowed += it.lowercase() }
        }
        reply["commands"].fields.orEmpty().keys.forEach { shadowed += it.lowercase() }

        val descriptions = HashMap<String, String>()
        for (row in reply["pairs"].list.orEmpty()) {
            val cells = row.list ?: continue
            val key = cells.firstOrNull()?.text ?: continue
            val description = cells.getOrNull(1)?.text.nonEmpty() ?: continue
            descriptions[key] = description
        }

        val seen = HashSet<String>()
        val suggestions = ArrayList<SkillSlashSuggestion>()
        for ((rawKey, info) in entries) {
            val key = rawKey.trim()
            if (!key.startsWith('/')) continue
            val name = key.drop(1)
            if (name.isEmpty() || name.any(Char::isWhitespace)) continue
            if (key.lowercase() in shadowed || name.lowercase() in shadowed) continue
            val slug = SlashSkillFormatter.slug(name)
            if (slug.isEmpty() || !seen.add(slug)) continue
            suggestions += SkillSlashSuggestion(
                name = name,
                category = info["origin"].text.nonEmpty(),
                description = descriptions[key],
            )
        }
        return suggestions.sortedWith { left, right -> left.name.compareTo(right.name, ignoreCase = true) }
    }

    fun invocation(draft: String): BotSlashInvocation? {
        val trimmed = draft.trim()
        if (!trimmed.startsWith('/')) return null
        val body = trimmed.drop(1)
        val separator = body.indexOfFirst(Char::isWhitespace)
        if (separator < 0) return body.takeIf(String::isNotEmpty)?.let { BotSlashInvocation(it, "") }
        val name = body.substring(0, separator)
        if (name.isEmpty()) return null
        return BotSlashInvocation(
            name = name,
            argument = body.substring(separator).trim(),
        )
    }
}

/**
 * Port of `HermesSlashCatalog` in
 * `Features/Chat/HermesSlashCommands.swift`.
 */
class HermesSlashCatalog(reply: BotJSON = BotJSON.NULL) {
    val commands: List<AgentCommand>
    private val canonical: Map<String, String>
    val skills: List<SkillSlashSuggestion>
    val hostArgumentNames: Set<String>

    init {
        skills = BotSlashCatalog.skills(reply)
        val canonical = LinkedHashMap<String, String>()
        val aliases = HashMap<String, MutableList<String>>()
        for ((key, target) in reply["canon"].fields.orEmpty()) {
            val alias = commandName(key) ?: continue
            val name = commandName(target.text) ?: continue
            canonical[alias.lowercase()] = name
            if (!alias.equals(name, ignoreCase = true)) {
                aliases.getOrPut(name.lowercase()) { mutableListOf() }.add(alias)
            }
        }
        this.canonical = canonical
        hostArgumentNames = canonical.mapNotNullTo(linkedSetOf()) { (alias, name) ->
            val appOwned = HermesSlashCommandCatalog.command(named = alias) != null ||
                HermesSlashCommandCatalog.command(named = name) != null
            val held = alias in HermesSlashCommandCatalog.heldNames || name.lowercase() in HermesSlashCommandCatalog.heldNames
            alias.takeUnless { appOwned || held }
        }

        val seen = HashSet<String>()
        val commands = ArrayList<AgentCommand>()
        for (row in reply["pairs"].list.orEmpty()) {
            val cells = row.list ?: continue
            val name = commandName(cells.firstOrNull()?.text) ?: continue
            val lower = name.lowercase()
            if (!canonical[lower].equals(name, ignoreCase = true) || !seen.add(lower)) continue
            commands += AgentCommand(
                name = name,
                description = cells.getOrNull(1)?.text,
                aliases = aliases[lower]?.sorted(),
            )
        }
        this.commands = commands
    }

    fun command(named: String): String? = canonical[named.lowercase()]

    private fun commandName(key: String?): String? {
        val trimmed = key?.trim() ?: return null
        if (!trimmed.startsWith('/')) return null
        val name = trimmed.drop(1)
        return name.takeIf { it.isNotEmpty() && it.none(Char::isWhitespace) }
    }
}

/**
 * Port of `HermesChatPolicy` in `HermesSlashCommands.swift`. Bot Chat keeps one
 * conversation and therefore refuses every command that leaves or rewrites it.
 */
enum class HermesChatPolicy {
    SESSION,
    BOT_CHAT;

    fun refusal(typed: String, canonical: String): String? {
        if (this != BOT_CHAT) return null
        return when (canonical.lowercase()) {
            "new", "reset" -> "A bot keeps one chat, so /$typed can’t start another."
            "clear" -> "A bot keeps one chat, so /$typed can’t start another. Run /compress to free up its context."
            "resume", "sessions" -> "A bot keeps one chat, so /$typed can’t open another."
            "branch", "fork" -> "A bot’s chat can’t be forked."
            "title" -> "A bot’s chat keeps the bot’s name."
            "undo", "retry" -> "A bot’s chat can’t be rewound."
            else -> null
        }
    }

    val offersHistoryActions: Boolean get() = this == SESSION
    val picksProfile: Boolean get() = this == SESSION
    val offersMentions: Boolean get() = this == BOT_CHAT

    companion object {
        fun from(target: ConversationTarget, botChatRoot: String? = null): HermesChatPolicy =
            if (target is ConversationTarget.CanonicalChat || botChatRoot != null) BOT_CHAT else SESSION
    }
}

/** Port of `HermesSlashRoute` in `HermesSlashCommands.swift`. */
sealed class HermesSlashRoute {
    data class Refused(val message: String) : HermesSlashRoute()
    data class AppOwned(val command: SlashCommand) : HermesSlashRoute()
    object Held : HermesSlashRoute()
    data class Skill(val skill: SkillSlashSuggestion) : HermesSlashRoute()
    object Host : HermesSlashRoute()
    object Text : HermesSlashRoute()
}

/** Port of `HermesSlashReply` in `HermesSlashCommands.swift`. */
sealed class HermesSlashReply {
    data class Notice(val text: String) : HermesSlashReply()
    data class Send(val message: String, val shown: String, val notice: String?) : HermesSlashReply()
    data class Prefill(val message: String, val notice: String?) : HermesSlashReply()
    data class Alias(val target: String) : HermesSlashReply()

    companion object {
        fun from(reply: BotJSON, typed: String): HermesSlashReply? {
            val notice = reply["notice"].text.nonEmpty()
            val message = reply["message"].text.nonEmpty()
            return when (reply["type"].text) {
                null, "exec", "plugin" -> {
                    val parts = ArrayList<String>(3)
                    notice?.let(parts::add)
                    reply["warning"].text.nonEmpty()?.let(parts::add)
                    verbatim(reply["output"].text.orEmpty()).takeIf(String::isNotEmpty)?.let(parts::add)
                    Notice(parts.joinToString("\n\n"))
                }
                "send", "skill" -> {
                    val value = message ?: return null
                    val shown = reply["display"].text.nonEmpty()
                        ?: if (reply["type"].text == "skill") typed else value
                    Send(message = value, shown = shown, notice = notice)
                }
                "prefill" -> Prefill(message = message ?: return null, notice = notice)
                "alias" -> {
                    val target = reply["target"].text.nonEmpty()?.trim(' ', '\t') ?: return null
                    Alias(target = target.removePrefix("/"))
                }
                else -> null
            }
        }

        /** Preserves terminal columns and chooses a fence absent from the output. */
        fun verbatim(output: String): String {
            val trimmed = output.trim('\n', '\r')
            if (trimmed.trim().isEmpty()) return ""
            var fence = "```"
            while (fence in trimmed) fence += '`'
            return "${fence}text\n$trimmed\n$fence"
        }
    }
}

/**
 * Port of `HermesSlashCompletion` in `HermesSlashCommands.swift`.
 * [replaceFrom] is a Unicode-scalar/code-point offset, never a UTF-16 offset.
 */
data class HermesSlashCompletion(
    val text: String,
    val items: List<Item>,
    val replaceFrom: Int,
) {
    data class Item(
        val text: String,
        val display: String,
        val meta: String,
    ) {
        val id: String get() = text
    }

    private val head: String
        get() {
            val count = text.codePointCount(0, text.length)
            val safeCount = replaceFrom.coerceIn(0, count)
            return text.substring(0, text.offsetByCodePoints(0, safeCount))
        }

    fun applies(current: String): Boolean =
        current.startsWith(head) && current.drop(head.length).none(Char::isWhitespace)

    fun applying(item: Item): String = head + item.text
}

/**
 * Port of `Features/Chat/HermesSlashCommands.swift`: catalog routing, guarded
 * host writes and debounced newest-wins argument completion for one chat.
 */
class HermesSlashCommands(
    private val engine: HermesConversation,
    val policy: HermesChatPolicy = HermesChatPolicy.SESSION,
) {
    var catalog: HermesSlashCatalog = HermesSlashCatalog()
        private set
    var scope: SlashCommandScope = SlashCommandScope(
        builtins = HermesSlashCommandCatalog.commands.filter { command ->
            policy.refusal(command.name, command.name) == null
        },
        isHermes = true,
    )
        private set

    val commands: List<AgentCommand>
        get() = if (policy == HermesChatPolicy.SESSION) {
            catalog.commands
        } else {
            catalog.commands.filter { command ->
                command.name?.let { policy.refusal(it, it) == null } ?: true
            }
        }

    var completion: HermesSlashCompletion? = null
        private set
    var completionDelayMillis: Long = 150L
    var onNeedsReattach: () -> Unit = {}
    var onChange: () -> Unit = {}

    private var completionRevision = 0L

    /** Reads the catalog only for the still-current attach. */
    suspend fun connect(runtime: String, attempt: Int) {
        if (engine.generation != attempt || engine.connectionState != HermesConversation.ConnectionState.CONNECTED) return
        val reply = try {
            engine.request(HermesCall.CommandsCatalog(sessionID = runtime), attempt)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            return
        }
        if (engine.generation != attempt) return
        catalog = HermesSlashCatalog(reply)
        scope = scope.copy(hostCommands = catalog.hostArgumentNames)
        onChange()
    }

    fun route(name: String): HermesSlashRoute {
        val typed = name.lowercase()
        val canonical = catalog.command(named = typed)?.lowercase() ?: typed
        policy.refusal(typed = name, canonical = canonical)?.let { return HermesSlashRoute.Refused(it) }
        val appOwned = HermesSlashCommandCatalog.command(named = typed)
            ?: HermesSlashCommandCatalog.command(named = canonical)
        if (appOwned != null) return HermesSlashRoute.AppOwned(appOwned)
        if (typed in HermesSlashCommandCatalog.heldNames || canonical in HermesSlashCommandCatalog.heldNames) {
            return HermesSlashRoute.Held
        }
        SlashSkillFormatter.skill(name, catalog.skills)?.let { return HermesSlashRoute.Skill(it) }
        return if (catalog.command(named = typed) == null) HermesSlashRoute.Text else HermesSlashRoute.Host
    }

    suspend fun run(line: String): HermesSlashReply {
        val reply = write { runtime -> HermesCall.SlashExec(sessionID = runtime, command = line) }
        return HermesSlashReply.from(reply, typed = line) ?: throw BotFailure.Unsupported
    }

    suspend fun rename(title: String): String {
        val reply = write { runtime -> HermesCall.SessionRename(runtime = runtime, title = title) }
        return reply["title"].text?.takeIf(String::isNotEmpty) ?: title
    }

    suspend fun expand(
        skill: SkillSlashSuggestion,
        argument: String,
        typed: String,
    ): HermesSlashReply {
        val reply = write { runtime ->
            HermesCall.CommandDispatch(name = skill.name, argument = argument, sessionID = runtime)
        }
        if (reply["type"].text != "skill") throw BotFailure.Unsupported
        return HermesSlashReply.from(reply, typed = typed) ?: throw BotFailure.Unsupported
    }

    private suspend fun write(call: (String) -> HermesCall): BotJSON {
        val runtime = engine.runtime
        if (engine.connectionState != HermesConversation.ConnectionState.CONNECTED || runtime == null) {
            throw HermesChatTurnCoordinator.NotSent(BotFailure.Transport)
        }
        var dispatched = false
        try {
            return engine.write(call(runtime), engine.generation, runtime) { dispatched = true }
        } catch (error: Throwable) {
            if (HermesChatSideTasks.isReaped(error)) onNeedsReattach()
            if (dispatched) throw error
            throw HermesChatTurnCoordinator.NotSent(error)
        }
    }

    /**
     * Debounces one host-command argument by 150 ms. Revisions fence replies so
     * an older request can never overwrite a newer query.
     */
    suspend fun complete(text: String?) {
        completionRevision += 1
        val revision = completionRevision
        val runtime = engine.runtime
        val attempt = engine.generation
        if (text == null || engine.connectionState != HermesConversation.ConnectionState.CONNECTED || runtime == null) {
            replaceCompletion(null)
            return
        }
        completion?.takeUnless { it.applies(text) }?.let { replaceCompletion(null) }
        try {
            delay(completionDelayMillis)
        } catch (_: CancellationException) {
            return
        }
        val reply = try {
            engine.request(HermesCall.CompleteSlash(text = text, sessionID = runtime), attempt)
        } catch (_: CancellationException) {
            return
        } catch (_: Throwable) {
            null
        }
        if (revision != completionRevision || !currentCoroutineContext().isActive) return
        val items = reply?.get("items")?.list.orEmpty().mapNotNull { item ->
            val value = item["text"].text?.takeIf(String::isNotEmpty) ?: return@mapNotNull null
            HermesSlashCompletion.Item(
                text = value,
                display = item["display"].text ?: value,
                meta = item["meta"].text.orEmpty(),
            )
        }
        val replaceFrom = reply?.get("replace_from")?.integer ?: text.codePointCount(0, text.length)
        replaceCompletion(
            if (items.isEmpty()) null else HermesSlashCompletion(text = text, items = items, replaceFrom = replaceFrom),
        )
    }

    private fun replaceCompletion(value: HermesSlashCompletion?) {
        if (completion == value) return
        completion = value
        onChange()
    }
}

private fun String?.nonEmpty(): String? = this?.trim()?.takeIf(String::isNotEmpty)
