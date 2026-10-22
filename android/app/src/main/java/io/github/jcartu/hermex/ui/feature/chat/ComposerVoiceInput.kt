package io.github.jcartu.hermex.ui.feature.chat

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.LocaleList
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.WindowManager
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
import androidx.compose.runtime.mutableIntStateOf
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
import java.util.Locale

private enum class ComposerVoicePhase { IDLE, LISTENING }

/**
 * Android dictation control matching `ComposerVoiceInputController.swift`.
 * Partial results replace one captured baseline rather than repeatedly appending.
 * Unsupported current-language models retry the user's other languages and
 * Android 14+ language switching is enabled for the same candidate set.
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

    val languageTags = remember {
        val preferred = buildList {
            val locales = LocaleList.getDefault()
            for (index in 0 until locales.size()) add(locales[index].toLanguageTag())
        }
        composerSpeechLanguageCandidates(
            currentLanguageTag = Locale.getDefault().toLanguageTag(),
            preferredLanguageTags = preferred,
        )
    }

    var phase by remember { mutableStateOf(ComposerVoicePhase.IDLE) }
    var baselineDraft by remember { mutableStateOf("") }
    var pendingBaselineDraft by remember { mutableStateOf("") }
    var languageAttempt by remember { mutableIntStateOf(0) }
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

    lateinit var startRecognition: (String, Int) -> Unit
    startRecognition = { baseDraft, attempt ->
        baselineDraft = baseDraft
        languageAttempt = attempt.coerceIn(0, languageTags.lastIndex.coerceAtLeast(0))
        val recognitionIntent = composerRecognitionIntent(languageTags, languageAttempt)
        runCatching { recognizer.startListening(recognitionIntent) }
            .onSuccess { phase = ComposerVoicePhase.LISTENING }
            .onFailure { phase = ComposerVoicePhase.IDLE }
    }

    DisposableEffect(recognizer, languageTags) {
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                phase = ComposerVoicePhase.LISTENING
            }

            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit

            override fun onEndOfSpeech() {
                // Audio capture ended; final recognition may still arrive.
                phase = ComposerVoicePhase.IDLE
            }

            override fun onError(error: Int) {
                val shouldTryNextLanguage =
                    (error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED ||
                        error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE) &&
                        languageAttempt + 1 < languageTags.size
                if (shouldTryNextLanguage) {
                    runCatching { recognizer.cancel() }
                    startRecognition(baselineDraft, languageAttempt + 1)
                    return
                }

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

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            permissionDenied = false
            startRecognition(pendingBaselineDraft, 0)
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

    val isListening = phase == ComposerVoicePhase.LISTENING
    DictationDeviceLease(active = isListening)

    val hasRecordPermission = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.RECORD_AUDIO,
    ) == PackageManager.PERMISSION_GRANTED
    if (permissionDenied && !hasRecordPermission) return

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
                    startRecognition(draft, 0)
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

/**
 * Keeps the display awake and locks the current orientation only for active
 * microphone capture. Previous window/orientation policy is restored exactly.
 */
@Composable
private fun DictationDeviceLease(active: Boolean) {
    val activity = LocalContext.current.findActivity()
    DisposableEffect(active, activity) {
        if (!active || activity == null) return@DisposableEffect onDispose { }

        val keepScreenOnWasSet = activity.window.attributes.flags and
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0
        val previousOrientation = activity.requestedOrientation
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED

        onDispose {
            if (!keepScreenOnWasSet) {
                activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            activity.requestedOrientation = previousOrientation
        }
    }
}

internal fun composerRecognitionIntent(
    languageTags: List<String>,
    activeIndex: Int,
): Intent {
    val tags = languageTags.ifEmpty { listOf("en-US") }
    val selected = tags[activeIndex.coerceIn(0, tags.lastIndex)]
    return Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, selected)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, selected)
        if (Build.VERSION.SDK_INT >= 34 && tags.size > 1) {
            putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_SWITCH, RecognizerIntent.LANGUAGE_SWITCH_BALANCED)
            putStringArrayListExtra(
                RecognizerIntent.EXTRA_LANGUAGE_SWITCH_ALLOWED_LANGUAGES,
                ArrayList(tags),
            )
            putStringArrayListExtra(
                RecognizerIntent.EXTRA_LANGUAGE_DETECTION_ALLOWED_LANGUAGES,
                ArrayList(tags),
            )
        }
    }
}

/** Port of `ComposerSpeechLocalePolicy.candidates` (current, three preferred, en-US). */
internal fun composerSpeechLanguageCandidates(
    currentLanguageTag: String,
    preferredLanguageTags: List<String>,
): List<String> {
    val output = ArrayList<String>(5)
    val seen = HashSet<String>()

    fun add(tag: String) {
        val normalized = normalizeLanguageTag(tag)
        if (normalized.isNotEmpty() && seen.add(normalized)) output += normalized
    }

    add(currentLanguageTag)
    var acceptedPreferred = 0
    for (tag in preferredLanguageTags) {
        val before = output.size
        add(tag)
        if (output.size != before && normalizeLanguageTag(tag) != normalizeLanguageTag(currentLanguageTag)) {
            acceptedPreferred += 1
            if (acceptedPreferred == 3) break
        }
    }
    add("en-US")
    return output
}

private fun normalizeLanguageTag(raw: String): String {
    val locale = Locale.forLanguageTag(raw.replace('_', '-'))
    val language = locale.language.takeIf(String::isNotEmpty) ?: return ""
    val region = locale.country.takeIf(String::isNotEmpty)
    return if (region == null) language.lowercase() else "${language.lowercase()}-${region.uppercase()}"
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** Exact port of `ComposerVoiceDraftComposer.composedDraft`. */
internal fun composeVoiceDraft(baseDraft: String, transcript: String): String {
    val trimmedTranscript = transcript.trim()
    if (trimmedTranscript.isEmpty()) return baseDraft

    val trimmedDraft = baseDraft.trim()
    if (trimmedDraft.isEmpty()) return trimmedTranscript

    return "$trimmedDraft $trimmedTranscript"
}
