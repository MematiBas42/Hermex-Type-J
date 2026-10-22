package io.github.jcartu.hermex.ui.feature.chat

/** Which composer autocomplete owns the caret. */
enum class ComposerTriggerKind { SLASH, FILE }

/**
 * The `/…` or `@…` run ending at the caret.
 *
 * Port of `ComposerSlashTrigger.swift` and `ComposerFileTrigger.swift`. Ranges
 * use Kotlin/Compose UTF-16 offsets and are end-exclusive.
 */
data class ComposerCaretTrigger(
    val kind: ComposerTriggerKind,
    val start: Int,
    val endExclusive: Int,
    val text: String,
    val startsDraft: Boolean,
) {
    val query: String get() = text.drop(1)

    /** Replaces only this trigger, preserving text on both sides and returning the new caret. */
    fun applying(replacement: String, draft: String): ComposerCompletion {
        val safeStart = start.coerceIn(0, draft.length)
        val safeEnd = endExclusive.coerceIn(safeStart, draft.length)
        var inserted = replacement
        var caretOffset = 0
        if (
            inserted.endsWith(' ') &&
            safeEnd < draft.length &&
            draft[safeEnd].isHorizontalWhitespace()
        ) {
            inserted = inserted.dropLast(1)
            caretOffset = 1
        }
        val updated = draft.replaceRange(safeStart, safeEnd, inserted)
        return ComposerCompletion(
            draft = updated,
            caret = safeStart + inserted.length + caretOffset,
        )
    }
}

data class ComposerCompletion(val draft: String, val caret: Int)

/**
 * Detects the autocomplete trigger at a collapsed caret. `@` wins over `/`,
 * matching the iOS composer when a file reference appears in a free-form slash
 * argument. Email addresses and path slashes do not trigger because a marker
 * must start the draft or follow whitespace.
 */
fun detectComposerCaretTrigger(
    draft: String,
    selectionStart: Int,
    selectionEnd: Int = selectionStart,
    slashScope: SlashCommandScope = SlashCommandScope.WEBUI,
): ComposerCaretTrigger? {
    if (selectionStart != selectionEnd) return null
    if (selectionStart !in 0..draft.length) return null
    return detectFileTrigger(draft, selectionStart)
        ?: detectSlashTrigger(draft, selectionStart, slashScope)
}

private fun detectFileTrigger(draft: String, caret: Int): ComposerCaretTrigger? {
    var index = caret - 1
    while (index >= 0) {
        val character = draft[index]
        if (character.isWhitespace()) return null
        if (character == '@' && (index == 0 || draft[index - 1].isWhitespace())) {
            return ComposerCaretTrigger(
                kind = ComposerTriggerKind.FILE,
                start = index,
                endExclusive = caret,
                text = draft.substring(index, caret),
                startsDraft = draft.substring(0, index).all(Char::isWhitespace),
            )
        }
        index -= 1
    }
    return null
}

private fun detectSlashTrigger(draft: String, caret: Int, scope: SlashCommandScope): ComposerCaretTrigger? {
    val starts = ArrayList<Int>()
    var index = caret - 1
    while (index >= 0) {
        val character = draft[index]
        if (character == '\n' || character == '\r') break
        if (character == '/' && (index == 0 || draft[index - 1].isWhitespace())) starts += index
        index -= 1
    }

    // Starts were collected nearest-first. Test the outermost first so
    // `/workspace /Users/me` belongs to the command, not the inner path slash.
    for (start in starts.asReversed()) {
        val triggerText = draft.substring(start, caret)
        val startsDraft = draft.substring(0, start).all(Char::isWhitespace)
        if (isSlashTriggerText(triggerText, startsDraft, scope)) {
            return ComposerCaretTrigger(
                kind = ComposerTriggerKind.SLASH,
                start = start,
                endExclusive = caret,
                text = triggerText,
                startsDraft = startsDraft,
            )
        }
    }
    return null
}

private fun isSlashTriggerText(
    text: String,
    startsDraft: Boolean,
    scope: SlashCommandScope,
): Boolean {
    val body = text.drop(1)
    val firstWhitespace = body.indexOfFirst(Char::isWhitespace)
    if (firstWhitespace < 0) return true
    if (!startsDraft) return false

    val name = body.substring(0, firstWhitespace)
    val command = scope.command(named = name) ?: return scope.completesOnHost(name)
    if (command.subArgs == SlashCommandSubArgs.NONE) return false
    if (command.subArgs.allowsSpaces) return true

    val argument = body.substring(firstWhitespace + 1).trimStart()
    return argument.none(Char::isWhitespace)
}

private val SlashCommandSubArgs.allowsSpaces: Boolean
    get() = when (this) {
        SlashCommandSubArgs.WORKSPACES,
        SlashCommandSubArgs.PERSONALITIES,
        SlashCommandSubArgs.SKILLS,
        -> true
        SlashCommandSubArgs.MODELS,
        SlashCommandSubArgs.REASONING_LEVELS,
        SlashCommandSubArgs.GOAL_ACTIONS,
        SlashCommandSubArgs.NONE,
        -> false
    }

private fun Char.isHorizontalWhitespace(): Boolean = isWhitespace() && this != '\n' && this != '\r'

/** Options shown by a long press on Send. */
enum class ComposerSendModeChoice { QUEUE, STEER, STOP_AND_SEND }

/**
 * The menu exists only while a run is active and a payload is ready to send.
 * Port of the long-press send choices in `ChatComposerView.swift` (1.8.0).
 */
fun composerSendModeChoices(
    isRunActive: Boolean,
    hasPayload: Boolean,
    allowedChoices: Set<ComposerSendModeChoice>? = null,
): List<ComposerSendModeChoice> = if (isRunActive && hasPayload) {
    listOf(
        ComposerSendModeChoice.QUEUE,
        ComposerSendModeChoice.STEER,
        ComposerSendModeChoice.STOP_AND_SEND,
    ).filter { allowedChoices == null || it in allowedChoices }
} else {
    emptyList()
}

/** ↑ recall must never replace text already present in the composer. */
fun canRecallLastUserMessage(draft: String): Boolean = draft.isEmpty()
