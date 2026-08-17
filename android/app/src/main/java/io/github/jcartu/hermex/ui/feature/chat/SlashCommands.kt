package io.github.jcartu.hermex.ui.feature.chat

import io.github.jcartu.hermex.model.SessionSummary

/**
 * Port of `SlashCommand.swift`. Identity/equality follows the Swift custom
 * `==`, which compares names only — two catalog entries are the same command
 * iff their names match.
 */
data class SlashCommand(
    val name: String,
    val description: String,
    val argHint: String? = null,
    val noEcho: Boolean = false,
    val handler: SlashCommandHandler = SlashCommandHandler.Unsupported,
    val subArgs: SlashCommandSubArgs = SlashCommandSubArgs.NONE,
) {
    override fun equals(other: Any?): Boolean = other is SlashCommand && other.name == name

    override fun hashCode(): Int = name.hashCode()
}

/** Port of `SlashCommandHandler` from `SlashCommand.swift`. */
sealed class SlashCommandHandler {
    object Unsupported : SlashCommandHandler()
    data class ClientSide(val action: ClientSideAction) : SlashCommandHandler()
    data class ServerSide(val action: ServerSideAction) : SlashCommandHandler()
}

/** Port of `ClientSideAction` from `SlashCommand.swift`. */
enum class ClientSideAction { CLEAR, STOP, NEW, HELP }

/** Port of `ServerSideAction` from `SlashCommand.swift`. */
enum class ServerSideAction {
    MODEL,
    WORKSPACE,
    REASONING,
    TITLE,
    PERSONALITY,
    SKILLS,
    COMPRESS,
    RETRY,
    UNDO,
    BRANCH,
    QUEUE,
    STEER,
    INTERRUPT,
    STATUS,
    BTW,
    BACKGROUND,
    GOAL,
}

/** Port of `SlashCommandSubArgs` from `SlashCommand.swift`. */
enum class SlashCommandSubArgs {
    NONE,
    MODELS,
    PERSONALITIES,
    REASONING_LEVELS,
    WORKSPACES,
    SKILLS,
    GOAL_ACTIONS,
}

/**
 * Port of `SlashCommandCatalog.swift` — the full static command list
 * (lines 4-172 of the Swift file), order preserved.
 */
object SlashCommandCatalog {
    val allCommands: List<SlashCommand> = listOf(
        SlashCommand(
            name = "help",
            description = "Show available slash commands",
            noEcho = false,
            handler = SlashCommandHandler.ClientSide(ClientSideAction.HELP),
        ),
        SlashCommand(
            name = "clear",
            description = "Clear the current conversation",
            noEcho = true,
            handler = SlashCommandHandler.ClientSide(ClientSideAction.CLEAR),
        ),
        SlashCommand(
            name = "model",
            description = "Switch the active model",
            argHint = "model_name",
            noEcho = true,
            handler = SlashCommandHandler.ServerSide(ServerSideAction.MODEL),
            subArgs = SlashCommandSubArgs.MODELS,
        ),
        SlashCommand(
            name = "workspace",
            description = "Switch the active workspace",
            argHint = "path",
            noEcho = true,
            handler = SlashCommandHandler.ServerSide(ServerSideAction.WORKSPACE),
            subArgs = SlashCommandSubArgs.WORKSPACES,
        ),
        SlashCommand(
            name = "reasoning",
            description = "Set reasoning effort level",
            argHint = "level",
            noEcho = true,
            handler = SlashCommandHandler.ServerSide(ServerSideAction.REASONING),
            subArgs = SlashCommandSubArgs.REASONING_LEVELS,
        ),
        SlashCommand(
            name = "new",
            description = "Start a new session",
            noEcho = true,
            handler = SlashCommandHandler.ClientSide(ClientSideAction.NEW),
        ),
        SlashCommand(
            name = "stop",
            description = "Stop the current response",
            noEcho = true,
            handler = SlashCommandHandler.ClientSide(ClientSideAction.STOP),
        ),
        SlashCommand(
            name = "title",
            description = "Rename the current session",
            argHint = "name",
            noEcho = false,
            handler = SlashCommandHandler.ServerSide(ServerSideAction.TITLE),
        ),
        SlashCommand(
            name = "personality",
            description = "Set the session personality",
            argHint = "name",
            noEcho = false,
            handler = SlashCommandHandler.ServerSide(ServerSideAction.PERSONALITY),
            subArgs = SlashCommandSubArgs.PERSONALITIES,
        ),
        SlashCommand(
            name = "skills",
            description = "Search available skills",
            argHint = "query",
            noEcho = false,
            handler = SlashCommandHandler.ServerSide(ServerSideAction.SKILLS),
            subArgs = SlashCommandSubArgs.SKILLS,
        ),
        SlashCommand(
            name = "compress",
            description = "Compress session context",
            argHint = "focus topic",
            noEcho = true,
            handler = SlashCommandHandler.ServerSide(ServerSideAction.COMPRESS),
        ),
        SlashCommand(
            name = "compact",
            description = "Alias for /compress",
            argHint = "focus topic",
            noEcho = true,
            handler = SlashCommandHandler.ServerSide(ServerSideAction.COMPRESS),
        ),
        SlashCommand(
            name = "retry",
            description = "Retry the last turn",
            noEcho = true,
            handler = SlashCommandHandler.ServerSide(ServerSideAction.RETRY),
        ),
        SlashCommand(
            name = "undo",
            description = "Undo the last exchange",
            noEcho = true,
            handler = SlashCommandHandler.ServerSide(ServerSideAction.UNDO),
        ),
        SlashCommand(
            name = "branch",
            description = "Fork the conversation",
            argHint = "name",
            noEcho = true,
            handler = SlashCommandHandler.ServerSide(ServerSideAction.BRANCH),
        ),
        SlashCommand(
            name = "fork",
            description = "Alias for /branch",
            argHint = "name",
            noEcho = true,
            handler = SlashCommandHandler.ServerSide(ServerSideAction.BRANCH),
        ),
        SlashCommand(
            name = "queue",
            description = "Queue a message for the next turn",
            argHint = "message",
            noEcho = true,
            handler = SlashCommandHandler.ServerSide(ServerSideAction.QUEUE),
        ),
        SlashCommand(
            name = "steer",
            description = "Steer the active response",
            argHint = "message",
            noEcho = true,
            handler = SlashCommandHandler.ServerSide(ServerSideAction.STEER),
        ),
        SlashCommand(
            name = "interrupt",
            description = "Stop the response and send a new message",
            argHint = "message",
            noEcho = true,
            handler = SlashCommandHandler.ServerSide(ServerSideAction.INTERRUPT),
        ),
        SlashCommand(
            name = "status",
            description = "Show session status",
            noEcho = false,
            handler = SlashCommandHandler.ServerSide(ServerSideAction.STATUS),
        ),
        SlashCommand(
            name = "goal",
            description = "Set or inspect a persistent goal",
            argHint = "[status|pause|resume|clear|text]",
            noEcho = true,
            handler = SlashCommandHandler.ServerSide(ServerSideAction.GOAL),
            subArgs = SlashCommandSubArgs.GOAL_ACTIONS,
        ),
        SlashCommand(
            name = "btw",
            description = "Ask a side question",
            argHint = "question",
            noEcho = true,
            handler = SlashCommandHandler.ServerSide(ServerSideAction.BTW),
        ),
        SlashCommand(
            name = "background",
            description = "Run a parallel task",
            argHint = "prompt",
            noEcho = true,
            handler = SlashCommandHandler.ServerSide(ServerSideAction.BACKGROUND),
        ),
        SlashCommand(
            name = "bg",
            description = "Alias for /background",
            argHint = "prompt",
            noEcho = true,
            handler = SlashCommandHandler.ServerSide(ServerSideAction.BACKGROUND),
        ),
    )

    fun matching(query: String): List<SlashCommand> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return allCommands
        val lower = trimmed.lowercase()
        return allCommands.filter {
            it.name.lowercase().startsWith(lower) ||
                it.description.lowercase().contains(lower)
        }
    }

    fun command(named: String): SlashCommand? =
        allCommands.firstOrNull { it.name.equals(named, ignoreCase = true) }

    val reasoningLevels: List<String> =
        listOf("show", "hide", "none", "minimal", "low", "medium", "high", "xhigh")

    val goalActions: List<String> = listOf("status", "pause", "resume", "clear")
}

/** Port of `ParsedSlashCommand` from `SlashCommandExecutor.swift`. */
data class ParsedSlashCommand(
    val command: SlashCommand?,
    val name: String,
    val args: String,
)

/** Port of `SlashCommandExecutionResult` from `SlashCommandExecutor.swift`. */
sealed class SlashCommandExecutionResult {
    data class Executed(val message: String?) : SlashCommandExecutionResult()
    data class OpenedSession(val session: SessionSummary) : SlashCommandExecutionResult()
    object SendAsMessage : SlashCommandExecutionResult()
    data class Unsupported(val friendlyMessage: String) : SlashCommandExecutionResult()
    object NeedsSubArg : SlashCommandExecutionResult()
}

/**
 * Port of `SlashCommandExecutor.swift`. Parsing and the unsupported-command
 * vocabulary live here; execution itself lives on [ChatViewModel]
 * (`executeSlashCommandText`), which mirrors the Swift `execute(text:viewModel:)`.
 */
object SlashCommandExecutor {

    fun parse(text: String): ParsedSlashCommand? {
        val trimmed = text.trim()
        if (!trimmed.startsWith("/")) return null

        val withoutSlash = trimmed.drop(1)
        if (withoutSlash.isEmpty()) {
            return ParsedSlashCommand(command = null, name = "", args = "")
        }

        val parts = withoutSlash.split(" ", limit = 2).filter { it.isNotEmpty() }
        val name = parts.firstOrNull() ?: ""
        val args = parts.getOrNull(1)?.trim() ?: ""

        return ParsedSlashCommand(
            command = SlashCommandCatalog.command(named = name),
            name = name,
            args = args,
        )
    }

    fun unsupportedMessage(commandName: String): String = when (commandName.lowercase()) {
        "terminal" -> "Terminal is not available in the mobile app."
        "theme" -> "Theme switching is not available from mobile slash commands."
        "voice" -> "Voice commands are not available in the mobile app."
        "yolo" -> "YOLO mode is not available in the mobile app."
        else -> "This command is not available in the mobile app."
    }

    fun isKnownUnsupportedCommand(commandName: String): Boolean = when (commandName.lowercase()) {
        "terminal", "theme", "voice", "yolo" -> true
        else -> false
    }
}
