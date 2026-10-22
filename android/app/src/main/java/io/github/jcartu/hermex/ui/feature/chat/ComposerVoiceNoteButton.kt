package io.github.jcartu.hermex.ui.feature.chat

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import io.github.jcartu.hermex.core.media.VoiceNoteRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun ComposerVoiceNoteButton(
    enabled: Boolean,
    isProcessing: Boolean,
    actions: ChatComposerActions,
    importVoiceNote: ((ByteArray, String) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val recorder = remember(context) { VoiceNoteRecorder(context) }
    val scope = rememberCoroutineScope()
    var recording by remember { mutableStateOf(false) }
    var startedAt by remember { mutableLongStateOf(0L) }
    var elapsed by remember { mutableLongStateOf(0L) }

    fun startRecording() {
        recorder.start().onSuccess {
            recording = true
            startedAt = System.currentTimeMillis()
        }.onFailure { actions.setUploadAttachmentError(it.message ?: "Could not start recording.") }
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startRecording() else actions.setUploadAttachmentError("Microphone permission is required for voice notes.")
    }

    LaunchedEffect(recording) {
        while (recording) {
            elapsed = System.currentTimeMillis() - startedAt
            delay(250)
        }
        elapsed = 0
    }

    DisposableEffect(recorder) {
        onDispose { recorder.cancel() }
    }

    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(
            enabled = enabled && !isProcessing,
            onClick = {
                if (recording) {
                    recording = false
                    recorder.stop().onSuccess { note ->
                        scope.launch {
                            val bytes = runCatching { withContext(Dispatchers.IO) { note.file.readBytes() } }
                                .getOrElse {
                                    actions.setUploadAttachmentError(it.message ?: "Could not read voice note.")
                                    note.file.delete()
                                    return@launch
                                }
                            (importVoiceNote ?: actions::importVoiceNote)(bytes, note.filename)
                            note.file.delete()
                        }
                    }.onFailure { actions.setUploadAttachmentError(it.message ?: "Could not finish voice note.") }
                } else if (
                    ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                ) {
                    startRecording()
                } else {
                    permission.launch(Manifest.permission.RECORD_AUDIO)
                }
            },
        ) {
            when {
                isProcessing -> CircularProgressIndicator(strokeWidth = 2.dp)
                recording -> Icon(Icons.Filled.StopCircle, "Stop voice note", tint = MaterialTheme.colorScheme.error)
                else -> Icon(Icons.Filled.GraphicEq, "Record voice note")
            }
        }
        if (recording) Text(formatDuration(elapsed), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
    }
}

internal fun formatDuration(milliseconds: Long): String {
    val totalSeconds = (milliseconds.coerceAtLeast(0) / 1_000).toInt()
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}
