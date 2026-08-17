package io.github.jcartu.hermex.core.media

import android.content.Context
import android.media.MediaPlayer
import android.speech.tts.TextToSpeech
import io.github.jcartu.hermex.net.HermesApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.UUID

class AssistantSpeaker(
    context: Context,
    private val api: HermesApi,
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var player: MediaPlayer? = null
    private var audioFile: File? = null
    private var pendingFallback: String? = null
    private var ttsReady = false
    private var tts: TextToSpeech? = null

    init {
        tts = TextToSpeech(appContext) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) {
                tts?.language = Locale.getDefault()
                pendingFallback?.let { text -> pendingFallback = null; speakOnDevice(text) }
            }
        }
    }

    fun speak(raw: String) {
        val text = SpeechTextNormalizer.normalize(raw) ?: return
        scope.launch {
            stopPlayback()
            val serverAudio = if (text.length <= MAX_SERVER_TEXT_LENGTH) {
                runCatching { api.synthesizeSpeech(text, DEFAULT_SERVER_VOICE) }.getOrNull()?.takeIf(ByteArray::isNotEmpty)
            } else null
            if (serverAudio == null || !playServerAudio(serverAudio)) speakOnDevice(text)
        }
    }

    fun stop() {
        scope.launch { stopPlayback(); tts?.stop() }
    }

    fun release() {
        stop()
        tts?.shutdown()
        scope.cancel()
    }

    private suspend fun playServerAudio(bytes: ByteArray): Boolean {
        val directory = File(appContext.cacheDir, "type-j-tts").apply { mkdirs() }
        directory.listFiles()?.forEach { if (it.lastModified() < System.currentTimeMillis() - CACHE_MAX_AGE) it.delete() }
        val file = File(directory, "speech-${UUID.randomUUID()}.mp3")
        return runCatching {
            withContext(Dispatchers.IO) { file.writeBytes(bytes) }
            val prepared = withContext(Dispatchers.IO) {
                MediaPlayer().apply { setDataSource(file.absolutePath); prepare() }
            }
            player = prepared
            audioFile = file
            prepared.setOnCompletionListener { stopPlayback() }
            prepared.setOnErrorListener { _, _, _ -> stopPlayback(); true }
            prepared.start()
            true
        }.getOrElse { file.delete(); false }
    }

    private fun speakOnDevice(text: String) {
        if (!ttsReady) {
            pendingFallback = text
            return
        }
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "type-j-${UUID.randomUUID()}")
    }

    private fun stopPlayback() {
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
        audioFile?.delete()
        audioFile = null
    }

    companion object {
        const val MAX_SERVER_TEXT_LENGTH = 5_000
        const val DEFAULT_SERVER_VOICE = "en-US-AriaNeural"
        private const val CACHE_MAX_AGE = 24 * 60 * 60 * 1_000L
    }
}

object SpeechTextNormalizer {
    fun normalize(raw: String): String? {
        var text = raw
            .replace(Regex("```[\\s\\S]*?```"), " Code block. ")
            .replace(Regex("`([^`]+)`"), "${'$'}1")
            .replace(Regex("!\\[[^]]*]\\([^)]*\\)"), "")
            .replace(Regex("\\[([^]]+)]\\([^)]*\\)"), "${'$'}1")
            .replace(Regex("https?://\\S+"), " link ")
            .replace(Regex("(?m)^#{1,6}\\s*"), "")
            .replace(Regex("[*_~>|]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (text.length > 20_000) text = text.take(20_000)
        return text.takeIf(String::isNotEmpty)
    }
}
