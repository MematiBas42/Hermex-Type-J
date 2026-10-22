package io.github.jcartu.hermex.ui.feature.chat

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.jcartu.hermex.model.PendingAttachment
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Native Android port of the iOS chat message composer. */
@Composable
fun ChatComposer(
    state: ChatUiState,
    actions: ChatComposerActions,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var activeSheet by remember { mutableStateOf<ChatComposerSelectorSheet?>(null) }
    var dismissedSendError by remember { mutableStateOf<String?>(null) }
    var dismissedConfigurationError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(state.sendErrorMessage) {
        if (state.sendErrorMessage == null) dismissedSendError = null
    }
    LaunchedEffect(state.composerConfigurationErrorMessage) {
        if (state.composerConfigurationErrorMessage == null) dismissedConfigurationError = null
    }

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents(),
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            for (uri in uris) {
                when (val result = withContext(Dispatchers.IO) { readPickedAttachment(context, uri) }) {
                    is PickedAttachmentResult.Success -> actions.uploadAttachment(result.bytes, result.filename)
                    is PickedAttachmentResult.TooLarge -> actions.setUploadAttachmentError(
                        PendingAttachment.uploadTooLargeMessage(result.filename),
                    )
                    is PickedAttachmentResult.Failure -> actions.setUploadAttachmentError(result.message)
                }
            }
        }
    }

    val cameraPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicturePreview(),
    ) { bitmap ->
        if (bitmap == null) return@rememberLauncherForActivityResult
        scope.launch {
            val bytes = withContext(Dispatchers.IO) { encodeCameraImage(bitmap) }
            when {
                bytes == null -> actions.setUploadAttachmentError("Couldn't prepare the camera image.")
                bytes.size > PendingAttachment.maximumUploadBytes -> actions.setUploadAttachmentError(
                    PendingAttachment.uploadTooLargeMessage("Camera image"),
                )
                else -> actions.uploadAttachment(
                    bytes = bytes,
                    filename = "camera-${System.currentTimeMillis()}.jpg",
                )
            }
        }
    }

    val isOfflineReadOnly = state.isReadOnly || state.isViewingCachedData
    val hasSendPayload = state.draft.isNotBlank() || state.pendingAttachments.isNotEmpty()
    // A running turn can be steered when the user has entered content; with no
    // payload the same trailing button is the iOS stop-response control.
    val showsStopButton = state.isStreaming && !hasSendPayload
    val actionButtonEnabled = if (showsStopButton) {
        !isOfflineReadOnly && !state.isCancellingStream
    } else {
        !isOfflineReadOnly &&
            hasSendPayload &&
            !state.isStartingChat &&
            !state.isUploadingAttachment &&
            !state.isLoadingComposerConfiguration
    }
    val configurationControlsEnabled = !isOfflineReadOnly &&
        !state.isStartingChat &&
        !state.isStreaming &&
        !state.isLoadingComposerConfiguration
    val attachmentControlsEnabled = configurationControlsEnabled && !state.isUploadingAttachment
    val voiceInputEnabled = attachmentControlsEnabled

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (isOfflineReadOnly) {
            ComposerStatusStrip(
                message = "Reconnect to send messages.",
                isError = false,
                onDismiss = null,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
        }

        state.sendErrorMessage
            ?.takeIf { it != dismissedSendError }
            ?.let { message ->
                ComposerStatusStrip(
                    message = message,
                    isError = true,
                    onDismiss = { dismissedSendError = message },
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
            }

        state.composerConfigurationErrorMessage
            ?.takeIf { it != dismissedConfigurationError }
            ?.let { message ->
                ComposerStatusStrip(
                    message = message,
                    isError = true,
                    onDismiss = { dismissedConfigurationError = message },
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
            }

        SlashCommandAutocomplete(
            state = state,
            actions = actions,
            modifier = Modifier.padding(horizontal = 12.dp),
        )

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            tonalElevation = 3.dp,
            shadowElevation = 5.dp,
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                ChatComposerAttachmentStrip(
                    attachments = state.pendingAttachments,
                    isUploading = state.isUploadingAttachment,
                    errorMessage = state.uploadAttachmentErrorMessage,
                    onRemove = actions::removePendingAttachment,
                    onDismissError = { actions.setUploadAttachmentError(null) },
                    modifier = Modifier.padding(top = 4.dp),
                )

                ChatComposerSelectorChips(
                    state = state,
                    enabled = configurationControlsEnabled,
                    onOpenModel = { activeSheet = ChatComposerSelectorSheet.MODEL },
                    onOpenProfile = { activeSheet = ChatComposerSelectorSheet.PROFILE },
                    onOpenWorkspace = { activeSheet = ChatComposerSelectorSheet.WORKSPACE },
                    onSelectReasoningEffort = actions::selectReasoningEffort,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    trailingContent = {
                        GoalControl(
                            enabled = configurationControlsEnabled,
                            actions = actions,
                        )
                    },
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 4.dp, end = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    IconButton(
                        onClick = {
                            runCatching { filePicker.launch("*/*") }
                                .onFailure {
                                    actions.setUploadAttachmentError("No file picker is available.")
                                }
                        },
                        enabled = attachmentControlsEnabled,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.AttachFile,
                            contentDescription = "Attach files",
                        )
                    }

                    IconButton(
                        onClick = {
                            runCatching { cameraPicker.launch(null) }
                                .onFailure {
                                    actions.setUploadAttachmentError("Camera is not available.")
                                }
                        },
                        enabled = attachmentControlsEnabled,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.CameraAlt,
                            contentDescription = "Take a photo",
                        )
                    }

                    ComposerVoiceInput(
                        draft = state.draft,
                        enabled = voiceInputEnabled,
                        actions = actions,
                    )
                    ComposerVoiceNoteButton(
                        enabled = voiceInputEnabled,
                        isProcessing = state.isProcessingVoiceNote,
                        actions = actions,
                    )

                    ChatComposerTextInput(
                        draft = state.draft,
                        enabled = !isOfflineReadOnly,
                        keyboardSendEnabled = actionButtonEnabled && !showsStopButton,
                        onDraftChange = actions::updateDraft,
                        onKeyboardSend = { actions.send() },
                        modifier = Modifier.weight(1f),
                    )

                    FilledIconButton(
                        onClick = {
                            if (showsStopButton) actions.stopStream() else actions.send()
                        },
                        enabled = actionButtonEnabled,
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                    ) {
                        when {
                            state.isStartingChat || state.isCancellingStream -> CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                            showsStopButton -> Icon(
                                imageVector = Icons.Filled.Stop,
                                contentDescription = "Stop response",
                            )
                            else -> Icon(
                                imageVector = Icons.Filled.ArrowUpward,
                                contentDescription = "Send",
                            )
                        }
                    }
                }
            }
        }
    }

    ChatComposerSelectorSheets(
        state = state,
        actions = actions,
        activeSheet = activeSheet,
        onDismiss = { activeSheet = null },
    )
}

@Composable
private fun ComposerStatusStrip(
    message: String,
    isError: Boolean,
    onDismiss: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = if (isError) {
            MaterialTheme.colorScheme.errorContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, top = 6.dp, bottom = 6.dp, end = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = message,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = if (isError) {
                    MaterialTheme.colorScheme.onErrorContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            if (onDismiss != null) {
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "Dismiss error",
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
        }
    }
}

@Composable
private fun GoalControl(
    enabled: Boolean,
    actions: ChatComposerActions,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var showsSetGoalDialog by remember { mutableStateOf(false) }
    var goalDraft by remember { mutableStateOf("") }

    fun submitGoal(args: String) {
        actions.updateDraft("/goal $args")
        actions.send()
    }

    Box {
        AssistChip(
            onClick = { menuOpen = true },
            enabled = enabled,
            label = { Text("Goal") },
            leadingIcon = {
                Icon(Icons.Filled.Flag, contentDescription = null, modifier = Modifier.size(18.dp))
            },
        )
        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
        ) {
            DropdownMenuItem(
                text = { Text("Set Goal") },
                onClick = {
                    menuOpen = false
                    showsSetGoalDialog = true
                },
            )
            listOf(
                "Status" to "status",
                "Pause" to "pause",
                "Resume" to "resume",
                "Mark Done" to "done",
                "Clear" to "clear",
                "Stop" to "stop",
            ).forEach { (title, command) ->
                DropdownMenuItem(
                    text = { Text(title) },
                    onClick = {
                        menuOpen = false
                        submitGoal(command)
                    },
                )
            }
        }
    }

    if (showsSetGoalDialog) {
        AlertDialog(
            onDismissRequest = { showsSetGoalDialog = false },
            title = { Text("Set Goal") },
            text = {
                OutlinedTextField(
                    value = goalDraft,
                    onValueChange = { goalDraft = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Describe the goal") },
                    minLines = 3,
                    maxLines = 6,
                )
            },
            dismissButton = {
                TextButton(onClick = { showsSetGoalDialog = false }) {
                    Text("Cancel")
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val submittedGoal = goalDraft.trim()
                        showsSetGoalDialog = false
                        goalDraft = ""
                        submitGoal(submittedGoal)
                    },
                    enabled = goalDraft.isNotBlank(),
                ) {
                    Text("Set")
                }
            },
        )
    }
}

private sealed interface PickedAttachmentResult {
    data class Success(val filename: String, val bytes: ByteArray) : PickedAttachmentResult
    data class TooLarge(val filename: String) : PickedAttachmentResult
    data class Failure(val message: String) : PickedAttachmentResult
}

private data class PickedAttachmentMetadata(
    val filename: String,
    val size: Long?,
)

private fun readPickedAttachment(context: Context, uri: Uri): PickedAttachmentResult {
    val metadata = pickedAttachmentMetadata(context, uri)
    if (metadata.size != null && metadata.size > PendingAttachment.maximumUploadBytes) {
        return PickedAttachmentResult.TooLarge(metadata.filename)
    }

    return try {
        val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
            input.readBounded(PendingAttachment.maximumUploadBytes)
        } ?: return PickedAttachmentResult.Failure("Couldn't read ${metadata.filename}.")

        if (bytes == null) {
            PickedAttachmentResult.TooLarge(metadata.filename)
        } else {
            PickedAttachmentResult.Success(metadata.filename, bytes)
        }
    } catch (_: SecurityException) {
        PickedAttachmentResult.Failure("Hermex-Type-J doesn't have permission to read ${metadata.filename}.")
    } catch (_: IOException) {
        PickedAttachmentResult.Failure("Couldn't read ${metadata.filename}.")
    }
}

private fun pickedAttachmentMetadata(context: Context, uri: Uri): PickedAttachmentMetadata {
    var filename: String? = null
    var size: Long? = null
    runCatching {
        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameColumn >= 0 && !cursor.isNull(nameColumn)) {
                    filename = cursor.getString(nameColumn)
                }
                val sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (sizeColumn >= 0 && !cursor.isNull(sizeColumn)) {
                    size = cursor.getLong(sizeColumn).takeIf { it >= 0L }
                }
            }
        }
    }

    val resolvedName = filename
        ?.trim()
        ?.takeIf(String::isNotEmpty)
        ?: uri.lastPathSegment
            ?.substringAfterLast('/')
            ?.takeIf(String::isNotEmpty)
        ?: "attachment"
    return PickedAttachmentMetadata(filename = resolvedName, size = size)
}

private fun InputStream.readBounded(maximumBytes: Int): ByteArray? {
    val output = ByteArrayOutputStream(minOf(maximumBytes, DEFAULT_BUFFER_SIZE))
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        if (count == 0) continue
        if (total > maximumBytes - count) return null
        output.write(buffer, 0, count)
        total += count
    }
    return output.toByteArray()
}

private fun encodeCameraImage(bitmap: Bitmap): ByteArray? {
    val output = ByteArrayOutputStream()
    return if (bitmap.compress(Bitmap.CompressFormat.JPEG, 85, output)) {
        output.toByteArray()
    } else {
        null
    }
}
