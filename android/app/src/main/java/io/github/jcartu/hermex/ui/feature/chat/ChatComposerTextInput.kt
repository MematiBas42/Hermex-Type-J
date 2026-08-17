package io.github.jcartu.hermex.ui.feature.chat

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue

/**
 * Multiline composer input mirroring `ComposerTextInputView`.
 *
 * [draft] remains the source of truth. A local [TextFieldValue] preserves the
 * cursor and IME composition while typing, then resynchronizes when the draft
 * changes externally (slash completion, command execution, or steer recovery).
 */
@Composable
internal fun ChatComposerTextInput(
    draft: String,
    enabled: Boolean,
    keyboardSendEnabled: Boolean,
    onDraftChange: (String) -> Unit,
    onKeyboardSend: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var fieldValue by remember {
        mutableStateOf(
            TextFieldValue(
                text = draft,
                selection = TextRange(draft.length),
            ),
        )
    }

    LaunchedEffect(draft) {
        if (draft != fieldValue.text) {
            fieldValue = TextFieldValue(
                text = draft,
                selection = TextRange(draft.length),
            )
        }
    }

    TextField(
        value = fieldValue,
        onValueChange = { updated ->
            fieldValue = updated
            if (updated.text != draft) {
                onDraftChange(updated.text)
            }
        },
        modifier = modifier.onPreviewKeyEvent { event ->
            val isKeyboardSend = event.type == KeyEventType.KeyDown &&
                event.key == Key.Enter &&
                event.isCtrlPressed
            if (isKeyboardSend && keyboardSendEnabled) {
                onKeyboardSend()
                true
            } else {
                false
            }
        },
        enabled = enabled,
        placeholder = { Text("Ask anything... /commands") },
        textStyle = MaterialTheme.typography.bodyLarge,
        minLines = 1,
        maxLines = 6,
        singleLine = false,
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.Sentences,
            autoCorrectEnabled = true,
            imeAction = ImeAction.Default,
        ),
        shape = MaterialTheme.shapes.large,
    )
}
