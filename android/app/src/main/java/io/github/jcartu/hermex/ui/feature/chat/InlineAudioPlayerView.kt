package io.github.jcartu.hermex.ui.feature.chat

import android.media.MediaPlayer
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.io.File
import java.io.FileInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Telegram-style lazy audio bar ported from `InlineAudioPlayerView.swift`. */
@Composable
fun InlineAudioPlayerView(
    title: String,
    load: suspend () -> ByteArray?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var player by remember(title) { mutableStateOf<MediaPlayer?>(null) }
    var cacheFile by remember(title) { mutableStateOf<File?>(null) }
    var loading by remember(title) { mutableStateOf(false) }
    var failed by remember(title) { mutableStateOf(false) }
    var playing by remember(title) { mutableStateOf(false) }
    var duration by remember(title) { mutableFloatStateOf(0f) }
    var position by remember(title) { mutableFloatStateOf(0f) }

    fun startOrPause() {
        val current = player
        if (current != null) {
            if (current.isPlaying) {
                current.pause()
                playing = false
            } else {
                AudioAttachmentPlaybackCenter.claim(current)
                current.start()
                playing = true
            }
            return
        }
        if (loading) return
        loading = true
        failed = false
        scope.launch {
            val prepared = runCatching {
                withContext(Dispatchers.IO) {
                    val bytes = load() ?: error("Audio unavailable")
                    val file = File.createTempFile("hermex-audio-", ".bin", context.cacheDir)
                    file.writeBytes(bytes)
                    val mediaPlayer = MediaPlayer()
                    FileInputStream(file).use { input -> mediaPlayer.setDataSource(input.fd) }
                    mediaPlayer.prepare()
                    file to mediaPlayer
                }
            }.getOrNull()
            loading = false
            if (prepared == null) {
                failed = true
                return@launch
            }
            cacheFile = prepared.first
            player = prepared.second.apply {
                setOnCompletionListener {
                    playing = false
                    position = 0f
                    seekTo(0)
                }
            }
            duration = prepared.second.duration.coerceAtLeast(0) / 1_000f
            AudioAttachmentPlaybackCenter.claim(prepared.second)
            prepared.second.start()
            playing = true
        }
    }

    LaunchedEffect(playing, player) {
        while (playing) {
            position = runCatching { player?.currentPosition?.coerceAtLeast(0)?.div(1_000f) }.getOrNull() ?: 0f
            delay(250)
        }
    }

    DisposableEffect(title) {
        onDispose {
            player?.let(AudioAttachmentPlaybackCenter::release)
            player?.release()
            cacheFile?.delete()
        }
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            IconButton(onClick = ::startOrPause, modifier = Modifier.size(40.dp)) {
                when {
                    loading -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    failed -> Icon(Icons.Rounded.ErrorOutline, "Audio unavailable")
                    playing -> Icon(Icons.Rounded.Pause, "Pause $title")
                    else -> Icon(Icons.Rounded.PlayArrow, "Play $title")
                }
            }
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Slider(
                    value = position.coerceIn(0f, duration.coerceAtLeast(0.001f)),
                    onValueChange = { value ->
                        position = value
                        player?.seekTo((value * 1_000).toInt())
                    },
                    valueRange = 0f..duration.coerceAtLeast(0.001f),
                    enabled = player != null,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(formatDuration(position), style = MaterialTheme.typography.labelSmall)
                    Spacer(Modifier.width(8.dp))
                    Text(formatDuration(duration), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

private object AudioAttachmentPlaybackCenter {
    private var active: MediaPlayer? = null

    fun claim(player: MediaPlayer) {
        if (active !== player) runCatching { active?.pause() }
        active = player
    }

    fun release(player: MediaPlayer) {
        if (active === player) active = null
    }
}

internal fun formatDuration(seconds: Float): String {
    val whole = seconds.coerceAtLeast(0f).toInt()
    return "%d:%02d".format(whole / 60, whole % 60)
}
