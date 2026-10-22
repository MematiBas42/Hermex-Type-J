package io.github.jcartu.hermex.ui.feature.chat

import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.text.AnnotatedString

/**
 * Compose counterpart of `ResponseTextSelection.swift`: keeps native selection
 * handles while adding an action that sends exactly the selected response text
 * to `ChatViewModel.askAboutSelection`.
 */
@Composable
fun ResponseSelectionContainer(
    onAskHermex: (String) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val delegate = LocalTextToolbar.current
    val toolbar = remember(delegate) { ResponseSelectionToolbar(delegate) }
    @Suppress("DEPRECATION") val clipboard = LocalClipboardManager.current
    val context = LocalContext.current

    Column(modifier = modifier.fillMaxWidth()) {
        CompositionLocalProvider(LocalTextToolbar provides toolbar) {
            SelectionContainer { content() }
        }
        AnimatedVisibility(toolbar.isSelectionMenuVisible && toolbar.canCopy) {
            AssistChip(
                onClick = {
                    val previous = clipboard.getText()
                    toolbar.copySelection()
                    val selected = clipboard.getText()?.text?.trim().orEmpty()
                    restoreClipboard(context, clipboard, previous)
                    toolbar.hide()
                    if (selected.isNotEmpty()) onAskHermex(selected)
                },
                label = { Text("Ask Hermex about selection") },
                leadingIcon = { Icon(Icons.Rounded.AutoAwesome, contentDescription = null) },
                colors = AssistChipDefaults.assistChipColors(),
            )
        }
    }
}

@Suppress("DEPRECATION")
private fun restoreClipboard(
    context: Context,
    clipboard: androidx.compose.ui.platform.ClipboardManager,
    previous: AnnotatedString?,
) {
    if (previous != null) {
        clipboard.setText(previous)
    } else if (Build.VERSION.SDK_INT >= 28) {
        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).clearPrimaryClip()
    }
}

@Suppress("DEPRECATION")
private class ResponseSelectionToolbar(
    private val delegate: TextToolbar,
) : TextToolbar {
    var isSelectionMenuVisible by mutableStateOf(false)
        private set
    var canCopy by mutableStateOf(false)
        private set
    private var onCopyRequested: (() -> Unit)? = null

    override val status: TextToolbarStatus
        get() = delegate.status

    override fun showMenu(
        rect: Rect,
        onCopyRequested: (() -> Unit)?,
        onPasteRequested: (() -> Unit)?,
        onCutRequested: (() -> Unit)?,
        onSelectAllRequested: (() -> Unit)?,
    ) {
        this.onCopyRequested = onCopyRequested
        canCopy = onCopyRequested != null
        isSelectionMenuVisible = true
        delegate.showMenu(
            rect = rect,
            onCopyRequested = onCopyRequested,
            onPasteRequested = onPasteRequested,
            onCutRequested = onCutRequested,
            onSelectAllRequested = onSelectAllRequested,
        )
    }

    override fun hide() {
        isSelectionMenuVisible = false
        canCopy = false
        onCopyRequested = null
        delegate.hide()
    }

    fun copySelection() {
        onCopyRequested?.invoke()
    }
}
