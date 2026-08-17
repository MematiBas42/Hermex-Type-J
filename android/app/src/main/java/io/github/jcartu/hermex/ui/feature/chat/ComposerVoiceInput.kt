package io.github.jcartu.hermex.ui.feature.chat

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

private enum class ComposerVoicePhase { IDLE, LISTENING }

/**
 * Android dictation control matching the iOS controller's idle/listening shape.
 * Each recognition session captures a baseline draft; every partial result is
 * composed from that baseline, replacing the prior dictation region rather
 * than repeatedly appending partial transcripts.
 */
@Composable
internal fun ComposerVoiceInput(
    draft: String,
    enabled: Boolean,
    actions: ChatComposerActions,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val recognitionAvailable = remember(appContext) {
        runCatching { SpeechRecognizer.isRecognitionAvailable(appContext) }.getOrDefault(false)
    }
    if (!recognitionAvailable) return

    var phase by remember { mutableStateOf(ComposerVoicePhase.IDLE) }
    var baselineDraft by remember { mutableStateOf("") }
    var pendingBaselineDraft by remember { mutableStateOf("") }
    var permissionDenied by rememberSaveable { mutableStateOf(false) }
    val currentActions by rememberUpdatedState(actions)

    val recognizer = remember(appContext) {
        runCatching { SpeechRecognizer.createSpeechRecognizer(appContext) }.getOrNull()
    } ?: return

    fun publishTranscript(results: Bundle?) {
        val transcript = results
            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()
            .orEmpty()
        if (transcript.isNotBlank()) {
            currentActions.updateDraft(composeVoiceDraft(baselineDraft, transcript))
        }
    }

    DisposableEffect(recognizer) {
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                phase = ComposerVoicePhase.LISTENING
            }

            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit

            override fun onEndOfSpeech() {
                // The recognizer has already closed its audio input at this
                // callback; final results may still arrive afterward.
                phase = ComposerVoicePhase.IDLE
            }

            override fun onError(error: Int) {
                phase = ComposerVoicePhase.IDLE
                if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                    permissionDenied = true
                }
            }

            override fun onResults(results: Bundle?) {
                publishTranscript(results)
                phase = ComposerVoicePhase.IDLE
            }

            override fun onPartialResults(partialResults: Bundle?) {
                publishTranscript(partialResults)
            }

            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })

        onDispose {
            runCatching { recognizer.cancel() }
            runCatching { recognizer.destroy() }
        }
    }

    fun startRecognition(baseDraft: String) {
        baselineDraft = baseDraft
        val recognitionIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        runCatching {
            recognizer.startListening(recognitionIntent)
        }.onSuccess {
            phase = ComposerVoicePhase.LISTENING
        }.onFailure {
            phase = ComposerVoicePhase.IDLE
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            permissionDenied = false
            startRecognition(pendingBaselineDraft)
        } else {
            permissionDenied = true
            phase = ComposerVoicePhase.IDLE
        }
    }

    LaunchedEffect(enabled) {
        if (!enabled && phase == ComposerVoicePhase.LISTENING) {
            runCatching { recognizer.stopListening() }
            phase = ComposerVoicePhase.IDLE
        }
    }

    val hasRecordPermission = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.RECORD_AUDIO,
    ) == PackageManager.PERMISSION_GRANTED
    if (permissionDenied && !hasRecordPermission) return

    val isListening = phase == ComposerVoicePhase.LISTENING
    val pulseScale = if (isListening) {
        val transition = rememberInfiniteTransition(label = "composer-mic-pulse")
        val scale by transition.animateFloat(
            initialValue = 0.9f,
            targetValue = 1.12f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 650),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "composer-mic-scale",
        )
        scale
    } else {
        1f
    }

    IconButton(
        onClick = {
            if (isListening) {
                runCatching { recognizer.stopListening() }
                phase = ComposerVoicePhase.IDLE
            } else if (enabled) {
                pendingBaselineDraft = draft
                if (hasRecordPermission) {
                    startRecognition(draft)
                } else {
                    permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
            }
        },
        modifier = modifier.scale(pulseScale),
        enabled = enabled || isListening,
        colors = IconButtonDefaults.iconButtonColors(
            contentColor = if (isListening) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        ),
    ) {
        Icon(
            imageVector = if (enabled || isListening) Icons.Filled.Mic else Icons.Filled.MicOff,
            contentDescription = if (isListening) "Stop dictation" else "Start dictation",
            modifier = Modifier.size(22.dp),
        )
    }
}

/** Exact port of `ComposerVoiceDraftComposer.composedDraft`. */
internal fun composeVoiceDraft(baseDraft: String, transcript: String): String {
    val trimmedTranscript = transcript.trim()
    if (trimmedTranscript.isEmpty()) return baseDraft

    val trimmedDraft = baseDraft.trim()
    if (trimmedDraft.isEmpty()) return trimmedTranscript

    return "$trimmedDraft $trimmedTranscript"
}
