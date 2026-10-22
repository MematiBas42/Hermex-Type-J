package io.github.jcartu.hermex.ui.feature.chat

import io.github.jcartu.hermex.model.AgentCommand

/**
 * Port of `SlashCommandScope` in `Features/Chat/SlashCommandCatalog.swift`.
 *
 * A Hermes scope opens only at the beginning of a draft and delegates argument
 * completion for [hostCommands] to `complete.slash`.
 */
data class SlashCommandScope(
    val builtins: List<SlashCommand>,
    val hostCommands: Set<String> = emptySet(),
    val isHermes: Boolean = false,
) {
    fun command(named: String): SlashCommand? =
        builtins.firstOrNull { it.name.equals(named, ignoreCase = true) }

    fun completesOnHost(name: String): Boolean = name.lowercase() in hostCommands

    companion object {
        val WEBUI = SlashCommandScope(builtins = SlashCommandCatalog.allCommands)
    }
}

/**
 * The app-owned subset from `Features/Chat/SlashCommandCatalog.swift`.
 *
 * Custom rows whose Android webui catalog has no handler are still app-owned;
 * the Hermes pane routes them by command name and never sends them to the host.
 */
object HermesSlashCommandCatalog {
    val commands: List<SlashCommand> = buildList {
        listOf(
            "new", "stop", "model", "reasoning", "personality", "title", "goal",
            "btw", "background", "bg", "retry", "undo", "compress", "compact", "branch", "fork",
        ).mapNotNullTo(this) { SlashCommandCatalog.command(named = it) }
        add(
            SlashCommand(
                name = "clear",
                description = "Start a new chat with the same model and folder",
                noEcho = true,
                handler = SlashCommandHandler.ClientSide(ClientSideAction.CLEAR),
            ),
        )
        add(
            SlashCommand(
                name = "sessions",
                description = "Show this Profile’s sessions",
                noEcho = true,
            ),
        )
        add(
            SlashCommand(
                name = "resume",
                description = "Open a session by its title",
                argHint = "name",
                noEcho = true,
            ),
        )
        add(
            SlashCommand(
                name = "yolo",
                description = "Skip approvals in this chat, or ask again",
                noEcho = true,
            ),
        )
    }

    val heldNames: Set<String> = emptySet()

    fun command(named: String): SlashCommand? =
        commands.firstOrNull { it.name.equals(named, ignoreCase = true) }
}

/** Port of `ParsedSlashQuery` in `SlashCommandAutocompleteView.swift`. */
data class ParsedSlashQuery(
    val query: String,
    val scope: SlashCommandScope = SlashCommandScope.WEBUI,
) {
    val commandName: String
        get() {
            val trimmed = query.trim()
            val body = if (trimmed.startsWith('/')) trimmed.drop(1) else trimmed
            return body.substringBefore(' ')
        }

    val argQuery: String
        get() {
            val trimmed = query.trim()
            if (!trimmed.startsWith('/')) return ""
            val body = trimmed.drop(1)
            val separator = body.indexOf(' ')
            return if (separator < 0) "" else body.substring(separator + 1).trim()
        }

    val command: SlashCommand?
        get() = scope.command(named = commandName)

    val isSubArgMode: Boolean
        get() = command?.let { it.subArgs != SlashCommandSubArgs.NONE && hasArgument(after = it.name) } == true

    val isHostArgumentMode: Boolean
        get() = command == null && scope.completesOnHost(commandName) && hasArgument(after = commandName)

    private fun hasArgument(after: String): Boolean {
        val prefix = "/$after"
        return query.startsWith(prefix) && query.drop(prefix.length).startsWith(' ')
    }
}

/**
 * Port of `AgentSlashCommandSuggestion` in
 * `Features/Chat/SlashAutocompleteRanking.swift`.
 */
data class AgentSlashCommandSuggestion(
    val name: String,
    val description: String,
    val argHint: String?,
    val aliases: List<String>,
    internal val serverDescription: String?,
) {
    val id: String get() = name.lowercase()

    companion object {
        fun from(command: AgentCommand): AgentSlashCommandSuggestion? {
            if (command.cliOnly == true || command.gatewayOnly == true) return null
            val name = command.name.nonEmpty() ?: return null
            val description = command.description.nonEmpty()
            return AgentSlashCommandSuggestion(
                name = name,
                description = description ?: "Agent command",
                argHint = command.argsHint.nonEmpty(),
                aliases = command.aliases.orEmpty(),
                serverDescription = description,
            )
        }

        /**
         * Ranks each Hermes command by its best canonical name or alias, then
         * emits that command once. This is the alias-aware Swift algorithm.
         */
        fun matching(
            query: String,
            commands: List<AgentCommand>,
            excludedNames: Set<String> = emptySet(),
            matchingAliases: Boolean = false,
            limit: Int = SlashCommandRanker.RESULT_LIMIT,
        ): List<AgentSlashCommandSuggestion> {
            val seen = excludedNames.toMutableSet()
            val candidates = ArrayList<AgentSlashCommandSuggestion>(commands.size)
            for (command in commands) {
                val suggestion = from(command) ?: continue
                if (seen.add(suggestion.id)) candidates += suggestion
            }
            if (!matchingAliases || query.isBlank()) {
                return SlashCommandRanker.rank(candidates, query, limit) { suggestion ->
                    SlashRankableFields(
                        name = suggestion.name,
                        description = suggestion.serverDescription,
                    )
                }
            }

            var entryCount = 0
            for (candidate in candidates) entryCount += 1 + candidate.aliases.size
            val entries = ArrayList<RankedAgentAlias>(entryCount)
            for (suggestion in candidates) {
                entries += RankedAgentAlias(suggestion = suggestion, name = suggestion.name)
                for (alias in suggestion.aliases) {
                    entries += RankedAgentAlias(suggestion = suggestion, name = alias)
                }
            }
            val ranked = SlashCommandRanker.rank(entries, query, entries.size) { entry ->
                SlashRankableFields(
                    name = entry.name,
                    description = entry.suggestion.serverDescription,
                )
            }
            val listed = HashSet<String>()
            val outputLimit = if (limit < 0) candidates.size else limit
            if (outputLimit == 0) return emptyList()
            val results = ArrayList<AgentSlashCommandSuggestion>(minOf(outputLimit, candidates.size))
            for (entry in ranked) {
                if (listed.add(entry.suggestion.id)) results += entry.suggestion
                if (results.size == outputLimit) break
            }
            return results
        }
    }
}

private data class RankedAgentAlias(
    val suggestion: AgentSlashCommandSuggestion,
    val name: String,
)

private fun String?.nonEmpty(): String? = this?.trim()?.takeIf(String::isNotEmpty)
