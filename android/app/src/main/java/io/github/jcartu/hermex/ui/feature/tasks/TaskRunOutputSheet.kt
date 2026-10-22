package io.github.jcartu.hermex.ui.feature.tasks

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.jcartu.hermex.model.CronRunHistoryItem
import io.github.jcartu.hermex.ui.components.ErrorState
import io.github.jcartu.hermex.ui.components.LoadingState
import io.github.jcartu.hermex.ui.theme.CodeTextStyle
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * One run, read in full: webui's output file, or a Hermes run's final reply.
 *
 * The dialog renders `output` only when it belongs to the run on screen, so a
 * slow request that lands after the user has moved to another run can never
 * put the wrong transcript under the wrong heading.
 * Port of `Features/Tasks/TaskRunOutputSheet.swift`.
 */
@Composable
fun TaskRunOutputSheet(
    run: CronRunHistoryItem,
    output: CronRunOutput?,
    isLoading: Boolean,
    errorMessage: String?,
    retry: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val matchingText = output?.takeIf { it.filename == run.filename }?.text

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        androidx.compose.material3.Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onDismiss) { Text("Done") }
                    Text(
                        title(run),
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(
                        onClick = { copyToClipboard(context, matchingText) },
                        enabled = !matchingText.isNullOrEmpty(),
                    ) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = "Copy")
                    }
                }

                Box(Modifier.weight(1f)) {
                    when {
                        !matchingText.isNullOrEmpty() -> SelectionContainer {
                            Text(
                                matchingText,
                                style = CodeTextStyle,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .verticalScroll(rememberScrollState())
                                    .padding(16.dp),
                            )
                        }
                        isLoading -> LoadingState(label = "Loading output...")
                        errorMessage != null -> ErrorState(
                            message = errorMessage,
                            retryLabel = "Try Again",
                            onRetry = retry,
                        )
                        else -> io.github.jcartu.hermex.ui.components.EmptyState(
                            title = "Empty Output",
                            message = "No output for this run.",
                        )
                    }
                }
            }
        }
    }
}

private fun title(run: CronRunHistoryItem): String {
    val date = run.date ?: return run.filename
    val zoned = ZonedDateTime.ofInstant(date, ZoneId.systemDefault())
    return DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).format(zoned)
}

private fun copyToClipboard(context: Context, text: String?) {
    if (text.isNullOrEmpty()) return
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("Run output", text))
}
