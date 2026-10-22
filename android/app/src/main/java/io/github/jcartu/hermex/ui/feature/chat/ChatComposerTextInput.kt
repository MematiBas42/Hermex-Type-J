package io.github.jcartu.hermex.ui.feature.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import io.github.jcartu.hermex.ui.adaptive.LocalPaneCount

/** One-shot caret move requested after accepting an autocomplete row. */
internal data class ComposerCaretRequest(val id: Long, val caret: Int, val draft: String)

/** Reads at execution time so a delayed composition cannot restore an older local edit. */
internal fun reconcileComposerText(
    current: TextFieldValue,
    readDraft: () -> String,
    requestedCaret: ComposerCaretRequest?,
): TextFieldValue {
    val latest = readDraft()
    val selection = requestedCaret?.takeIf { it.draft == latest }
        ?.caret?.coerceIn(0, latest.length)?.let(::TextRange)
    return if (latest == current.text && selection == null) current
    else TextFieldValue(latest, selection ?: TextRange(latest.length))
}

/**
 * Native multiline composer editor.
 *
 * Port of `ChatComposerTextInputView.swift` and `ComposerChipTextView.swift`.
 * [BasicTextField] retains platform selection/IME/gesture behavior. Bot chips project their
 * friendly labels through an explicit source-offset mapping; copy/cut retain the original
 * tag and a partial delete removes the whole chip. Compact-height windows cap at three lines.
 */
@Composable
internal fun ChatComposerTextInput(
    draft: String,
    readDraft: () -> String,
    draftTokens: List<ComposerToken>,
    enabled: Boolean,
    keyboardSendEnabled: Boolean,
    requestedCaret: ComposerCaretRequest?,
    onDraftChange: (String) -> Unit,
    onSelectionChange: (TextRange) -> Unit,
    onKeyboardSend: () -> Unit,
    recallLastUserMessage: (() -> String?)?,
    modifier: Modifier = Modifier,
) {
    var fieldValue by remember {
        mutableStateOf(TextFieldValue(text = draft, selection = TextRange(draft.length)))
    }
    var appliedCaretRequestId by remember { mutableStateOf<Long?>(null) }
    var textLayout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val currentRecall by rememberUpdatedState(recallLastUserMessage)
    val currentOnDraftChange by rememberUpdatedState(onDraftChange)
    val currentOnSelectionChange by rememberUpdatedState(onSelectionChange)
    val readCurrentDraft by rememberUpdatedState(readDraft)

    LaunchedEffect(draft, requestedCaret?.id) {
        val pendingRequest = requestedCaret?.takeIf { it.id != appliedCaretRequestId }
        val next = reconcileComposerText(fieldValue, readCurrentDraft, pendingRequest)
        if (next != fieldValue) {
            fieldValue = next
            currentOnSelectionChange(next.selection)
        }
        if (pendingRequest != null) appliedCaretRequestId = pendingRequest.id
    }

    val colors = MaterialTheme.colorScheme
    val transformation = remember(draftTokens, colors.secondaryContainer, colors.onSecondaryContainer, colors.primary) {
        ComposerChipVisualTransformation(
            tokens = draftTokens,
            chipBackground = colors.secondaryContainer,
            pathContent = colors.onSecondaryContainer,
            skillContent = colors.primary,
        )
    }

    val density = LocalDensity.current
    val windowInfo = LocalWindowInfo.current
    val paneCount = LocalPaneCount.current
    val windowHeightDp = with(density) { windowInfo.containerSize.height.toDp() }
    val compactHeight = windowHeightDp < 500.dp
    val maxLines = when {
        compactHeight -> 3
        paneCount >= 3 -> 5
        else -> 6
    }
    val maximumHeight = when {
        compactHeight -> 76.dp
        paneCount >= 3 -> 128.dp
        else -> 160.dp
    }

    BasicTextField(
        value = fieldValue,
        onValueChange = { proposed ->
            val updated = applyAtomicBotChipEdit(fieldValue, proposed, draftTokens)
            val textChanged = updated.text != fieldValue.text
            val selectionChanged = updated.selection != fieldValue.selection
            fieldValue = updated
            if (textChanged) currentOnDraftChange(updated.text)
            if (selectionChanged || textChanged) currentOnSelectionChange(updated.selection)
        },
        onTextLayout = { textLayout = it },
        modifier = modifier
            .heightIn(min = 48.dp, max = maximumHeight)
            .onPreviewKeyEvent { event ->
                if (!enabled || event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false

                val isKeyboardSend = event.key == Key.Enter && event.isCtrlPressed
                if (isKeyboardSend && keyboardSendEnabled) {
                    onKeyboardSend()
                    return@onPreviewKeyEvent true
                }

                val isUnmodifiedUp = event.key == Key.DirectionUp &&
                    !event.isCtrlPressed && !event.isAltPressed && !event.isMetaPressed && !event.isShiftPressed
                if (isUnmodifiedUp && canRecallLastUserMessage(fieldValue.text)) {
                    val recalled = currentRecall?.invoke()?.takeIf(String::isNotEmpty)
                    if (recalled != null) {
                        val selection = TextRange(recalled.length)
                        fieldValue = TextFieldValue(recalled, selection)
                        currentOnDraftChange(recalled)
                        currentOnSelectionChange(selection)
                        return@onPreviewKeyEvent true
                    }
                }
                false
            },
        enabled = enabled,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface),
        minLines = 1,
        maxLines = maxLines,
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.Sentences,
            autoCorrectEnabled = true,
            imeAction = ImeAction.Default,
        ),
        cursorBrush = SolidColor(colors.primary),
        visualTransformation = transformation,
        decorationBox = { innerTextField ->
            Box(modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp)) {
                if (fieldValue.text.isEmpty()) {
                    Text(
                        text = "Ask anything... /commands",
                        style = MaterialTheme.typography.bodyLarge,
                        color = colors.onSurfaceVariant,
                    )
                }
                Box(Modifier.drawWithContent {
                    drawContent()
                    val layout = textLayout
                    if (layout != null && fieldValue.text == draftTokens.toServerText()) {
                        var offset = 0
                        clipRect {
                            for (token in draftTokens) {
                                val image = (token as? ComposerToken.BotChip)?.avatar
                                if (image != null && offset < layout.layoutInput.text.length) {
                                    val bounds = layout.getBoundingBox(offset)
                                    val side = minOf(bounds.width, bounds.height).toInt().coerceAtLeast(1)
                                    drawImage(image, dstOffset = IntOffset(bounds.left.toInt(), (bounds.top + (bounds.height - side) / 2).toInt()), dstSize = IntSize(side, side))
                                }
                                offset += if (token is ComposerToken.BotChip) token.displayName.length + 1 else token.serverText.length
                            }
                        }
                    }
                }) { innerTextField() }
            }
        },
    )
}

/** Port of `ComposerChipTextView`: deleting any part of an atomic bot reference deletes it whole. */
internal fun applyAtomicBotChipEdit(previous: TextFieldValue, proposed: TextFieldValue, tokens: List<ComposerToken>): TextFieldValue {
    if (previous.text == proposed.text || tokens.toServerText() != previous.text) return proposed
    var start = 0
    while (start < previous.text.length && start < proposed.text.length && previous.text[start] == proposed.text[start]) start++
    var end = previous.text.length
    var proposedEnd = proposed.text.length
    while (end > start && proposedEnd > start && previous.text[end - 1] == proposed.text[proposedEnd - 1]) { end--; proposedEnd-- }
    val affected = tokens.chipRanges().filter { range ->
        range.token is ComposerToken.BotChip && if (start == end) start > range.start && start < range.endExclusive
        else start < range.endExclusive && end > range.start
    }
    if (affected.isEmpty()) return proposed
    val replacement = proposed.text.substring(start, proposedEnd)
    val expandedStart = minOf(start, affected.minOf { it.start })
    val expandedEnd = maxOf(end, affected.maxOf { it.endExclusive })
    val text = previous.text.replaceRange(expandedStart, expandedEnd, replacement)
    return TextFieldValue(text, TextRange(expandedStart + replacement.length))
}
