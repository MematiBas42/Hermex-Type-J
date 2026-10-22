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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
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
internal data class ComposerCaretRequest(val id: Long, val caret: Int)

/**
 * Native multiline composer editor.
 *
 * Port of `ChatComposerTextInputView.swift` and `ComposerChipTextView.swift`.
 * [BasicTextField] retains platform selection/IME/gesture behavior; the visual
 * transformation never changes document length, so chip rendering has identity
 * caret mapping and manual scrolling remains native. A compact-height window
 * caps the editor at three lines for the shorter landscape composer (1.8.0).
 */
@Composable
internal fun ChatComposerTextInput(
    draft: String,
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
    val currentRecall by rememberUpdatedState(recallLastUserMessage)
    val currentOnDraftChange by rememberUpdatedState(onDraftChange)
    val currentOnSelectionChange by rememberUpdatedState(onSelectionChange)

    LaunchedEffect(draft, requestedCaret?.id) {
        val requestedSelection = requestedCaret?.caret
            ?.coerceIn(0, draft.length)
            ?.let(::TextRange)
        if (draft != fieldValue.text || requestedSelection != null) {
            val nextSelection = requestedSelection ?: TextRange(draft.length)
            fieldValue = TextFieldValue(text = draft, selection = nextSelection)
            currentOnSelectionChange(nextSelection)
        }
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
        onValueChange = { updated ->
            val textChanged = updated.text != fieldValue.text
            val selectionChanged = updated.selection != fieldValue.selection
            fieldValue = updated
            if (textChanged) currentOnDraftChange(updated.text)
            if (selectionChanged || textChanged) currentOnSelectionChange(updated.selection)
        },
        modifier = modifier
            .heightIn(min = 48.dp, max = maximumHeight)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false

                val isKeyboardSend = event.key == Key.Enter && event.isCtrlPressed
                if (isKeyboardSend && keyboardSendEnabled) {
                    onKeyboardSend()
                    return@onPreviewKeyEvent true
                }

                if (event.key == Key.DirectionUp && canRecallLastUserMessage(fieldValue.text)) {
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
                innerTextField()
            }
        },
    )
}
