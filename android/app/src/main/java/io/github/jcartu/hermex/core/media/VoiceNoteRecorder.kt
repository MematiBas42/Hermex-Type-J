package io.github.jcartu.hermex.core.media

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import java.io.File
import java.util.UUID


data class RecordedVoiceNote(
    val file: File,
    val durationMillis: Long,
    val filename: String,
)

/** Single-use AAC/MPEG-4 voice-note recorder. */
class VoiceNoteRecorder(context: Context) {
    private val appContext = context.applicationContext
    private val directory = File(appContext.cacheDir, "type-j-voice-notes")
    private var recorder: MediaRecorder? = null
    private var output: File? = null
    private var startedAtMillis: Long = 0

    val isRecording: Boolean get() = recorder != null

    @Suppress("DEPRECATION")
    fun start(): Result<Unit> = runCatching {
        check(recorder == null) { "A voice note is already recording." }
        cleanupOldFiles()
        directory.mkdirs()
        val file = File(directory, "type-j-${UUID.randomUUID()}.m4a")
        val mediaRecorder = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(appContext) else MediaRecorder()
        mediaRecorder.apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioEncodingBitRate(128_000)
            setAudioSamplingRate(44_100)
            setMaxDuration(MAX_DURATION_MILLIS.toInt())
            setOutputFile(file.absolutePath)
            prepare()
            start()
        }
        output = file
        recorder = mediaRecorder
        startedAtMillis = System.currentTimeMillis()
    }

    fun stop(): Result<RecordedVoiceNote> {
        val active = recorder ?: return Result.failure(IllegalStateException("No voice note is recording."))
        val file = output
        val duration = (System.currentTimeMillis() - startedAtMillis).coerceAtLeast(0)
        recorder = null
        output = null
        return runCatching {
            active.stop()
            active.release()
            require(duration >= MIN_DURATION_MILLIS) { "Voice note is too short." }
            require(file != null && file.isFile && file.length() > 0) { "Voice note was empty." }
            require(file.length() <= MAX_BYTES) { "Voice note is larger than 20 MB." }
            RecordedVoiceNote(file, duration, file.name)
        }.onFailure {
            runCatching { active.release() }
            file?.delete()
        }
    }

    fun cancel() {
        val active = recorder
        recorder = null
        runCatching { active?.stop() }
        runCatching { active?.release() }
        output?.delete()
        output = null
    }

    private fun cleanupOldFiles() {
        val threshold = System.currentTimeMillis() - MAX_CACHE_AGE_MILLIS
        directory.listFiles()?.forEach { if (it.lastModified() < threshold) it.delete() }
    }

    companion object {
        const val MIN_DURATION_MILLIS = 500L
        const val MAX_DURATION_MILLIS = 5 * 60 * 1_000L
        const val MAX_BYTES = 20L * 1_024L * 1_024L
        private const val MAX_CACHE_AGE_MILLIS = 24 * 60 * 60 * 1_000L
    }
}
