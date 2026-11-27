@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.nextlayer.betweenly.ui

import android.content.Context
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nextlayer.betweenly.ChatMessage
import com.nextlayer.betweenly.ChatStore
import com.nextlayer.betweenly.R
import com.nextlayer.betweenly.ui.icons.Lu
import com.nextlayer.betweenly.ui.icons.LuIcon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.max

/** A voice note waiting in the composer. Not sent until the person taps Send. */
class VoiceClip(val bytes: ByteArray, val durationMs: Int, val wave: String)

/**
 * AAC in an m4a, 24 kbps, mono, 16 kHz. The file stays in cache until
 * [finish] reads it or [cancel] deletes it.
 */
class VoiceSession(context: Context) {
    private val app = context.applicationContext
    private var recorder: MediaRecorder? = null
    private var file: File? = null
    private var startedAt = 0L
    private val peaks = ArrayList<Int>(64)

    fun start(): Boolean {
        releaseRecorder(keepFile = false)
        val dir = File(app.cacheDir, "voice")
        if (!dir.exists() && !dir.mkdirs()) return false
        val dest = File(dir, "rec-${System.currentTimeMillis()}.m4a")
        val rec = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(app) else MediaRecorder()
        return try {
            rec.setAudioSource(MediaRecorder.AudioSource.MIC)
            rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            rec.setAudioEncodingBitRate(24_000)
            rec.setAudioSamplingRate(16_000)
            rec.setAudioChannels(1)
            rec.setOutputFile(dest.absolutePath)
            rec.prepare()
            rec.start()
            recorder = rec
            file = dest
            startedAt = System.currentTimeMillis()
            peaks.clear()
            true
        } catch (_: Exception) {
            try {
                rec.release()
            } catch (_: Exception) {
                // The recorder never started.
            }
            dest.delete()
            false
        }
    }

    fun elapsed(): Int {
        if (startedAt <= 0L) return 0
        return (System.currentTimeMillis() - startedAt).toInt().coerceIn(0, 5 * 60 * 1000)
    }

    /** Latest bar heights, 0–15, for the live visualiser. Also stored for the sent note. */
    fun sample(): List<Int> {
        val amp = try {
            recorder?.maxAmplitude ?: 0
        } catch (_: Exception) {
            0
        }
        val level = ((amp / 32767f) * 15f).toInt().coerceIn(0, 15)
        peaks.add(level)
        return peaks.takeLast(40)
    }

    fun finish(): VoiceClip? {
        val dest = file
        val dur = elapsed()
        val wave = waveString()
        releaseRecorder(keepFile = true)
        if (dest == null || !dest.isFile || dest.length() < 32) {
            dest?.delete()
            return null
        }
        val bytes = try {
            dest.readBytes()
        } catch (_: Exception) {
            null
        }
        dest.delete()
        if (bytes == null || bytes.isEmpty()) return null
        return VoiceClip(bytes, dur, wave)
    }

    fun cancel() {
        val dest = file
        releaseRecorder(keepFile = false)
        dest?.delete()
    }

    private fun releaseRecorder(keepFile: Boolean) {
        val rec = recorder
        recorder = null
        if (!keepFile) file = null
        startedAt = 0L
        try {
            rec?.stop()
        } catch (_: Exception) {
            // stop throws if start never really began. The file is still readable when it did.
        }
        try {
            rec?.release()
        } catch (_: Exception) {
            // Already released.
        }
    }

    private fun waveString(): String {
        if (peaks.isEmpty()) return ""
        val target = 48
        if (peaks.size <= target) return peaks.joinToString(",")
        val out = ArrayList<Int>(target)
        val step = peaks.size.toFloat() / target.toFloat()
        for (i in 0 until target) {
            val start = (i * step).toInt()
            val end = ((i + 1) * step).toInt().coerceAtMost(peaks.size)
            var high = 0
            for (j in start until end) high = max(high, peaks[j])
            out.add(high)
        }
        return out.joinToString(",")
    }
}

/** One voice note playing in the open chat. Stopped when the chat leaves the screen. */
object VoicePlay {
    class State(val id: String?, val playing: Boolean, val position: Int, val speed: Float)

    private val stateMut = MutableStateFlow(State(null, false, 0, 1f))
    val state: StateFlow<State> = stateMut
    private var player: MediaPlayer? = null
    private var activeId: String? = null
    private var speed = 1f

    fun toggle(id: String, file: File) {
        val existing = player
        if (activeId == id && existing != null) {
            try {
                if (existing.isPlaying) {
                    existing.pause()
                    publish(playing = false, position = existing.currentPosition)
                } else {
                    applySpeed(existing)
                    existing.start()
                    publish(playing = true, position = existing.currentPosition)
                }
            } catch (_: Exception) {
                release()
            }
            return
        }
        release()
        val next = MediaPlayer()
        next.setOnErrorListener { _, _, _ ->
            release()
            true
        }
        next.setOnCompletionListener { mp ->
            try {
                mp.pause()
                mp.seekTo(0)
            } catch (_: Exception) {
                // The player is already gone.
            }
            publish(playing = false, position = 0)
        }
        try {
            next.setDataSource(file.absolutePath)
            next.setOnPreparedListener { mp ->
                applySpeed(mp)
                mp.start()
                activeId = id
                publish(playing = true, position = 0)
            }
            next.prepareAsync()
        } catch (_: Exception) {
            next.release()
            return
        }
        player = next
        activeId = id
        publish(playing = false, position = 0)
    }

    fun cycleSpeed() {
        speed = when (speed) {
            1f -> 1.5f
            1.5f -> 2f
            else -> 1f
        }
        val mp = player
        if (mp != null) {
            try {
                if (mp.isPlaying) applySpeed(mp)
            } catch (_: Exception) {
                // The next start applies the new speed.
            }
        }
        val current = stateMut.value
        stateMut.value = State(current.id, current.playing, current.position, speed)
    }

    fun tick() {
        val mp = player ?: return
        val id = activeId ?: return
        val pos = try {
            mp.currentPosition
        } catch (_: Exception) {
            return
        }
        val playing = try {
            mp.isPlaying
        } catch (_: Exception) {
            false
        }
        stateMut.value = State(id, playing, pos, speed)
    }

    fun stop() {
        release()
    }

    private fun applySpeed(mp: MediaPlayer) {
        try {
            val params = mp.playbackParams.setSpeed(speed)
            mp.playbackParams = params
        } catch (_: Exception) {
            // Some builds reject a speed change before the first start. Playback stays at 1x.
        }
    }

    private fun publish(playing: Boolean, position: Int) {
        stateMut.value = State(activeId, playing, position, speed)
    }

    private fun release() {
        val mp = player
        player = null
        activeId = null
        try {
            mp?.stop()
        } catch (_: Exception) {
            // Not started.
        }
        try {
            mp?.release()
        } catch (_: Exception) {
            // Already released.
        }
        val current = stateMut.value
        stateMut.value = State(null, false, 0, current.speed)
    }
}

fun parseWave(raw: String): List<Int> {
    if (raw.isBlank()) return emptyList()
    return raw.split(',').mapNotNull { part ->
        part.trim().toIntOrNull()?.coerceIn(0, 15)
    }.take(64)
}

fun voiceClock(ms: Int): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val min = total / 60
    val sec = total % 60
    return "%d:%02d".format(min, sec)
}

fun voiceSpeedLabel(speed: Float): String = when (speed) {
    1.5f -> "1.5x"
    2f -> "2x"
    else -> "1x"
}

@Composable
fun WaveBars(
    levels: List<Int>,
    progress: Float,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val bars = if (levels.isEmpty()) List(28) { 2 } else levels
    Canvas(modifier) {
        val count = bars.size.coerceAtLeast(1)
        val gap = 2.dp.toPx()
        val barW = ((size.width - gap * (count - 1)) / count).coerceAtLeast(1.5f)
        val minH = 3.dp.toPx()
        bars.forEachIndexed { index, level ->
            val frac = (level.coerceIn(0, 15) / 15f)
            val h = minH + frac * (size.height - minH)
            val x = index * (barW + gap)
            val played = (index + 1f) / count <= progress.coerceIn(0f, 1f)
            drawRoundRect(
                color = if (played) color else color.copy(alpha = 0.35f),
                topLeft = Offset(x, (size.height - h) / 2f),
                size = Size(barW, h),
                cornerRadius = CornerRadius(barW / 2f, barW / 2f),
            )
        }
    }
}

@Composable
fun VoiceRecordBar(ms: Int, levels: List<Int>, cancel: Boolean, locked: Boolean, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            voiceClock(ms),
            color = if (cancel) Color(0xFFFFB4AB) else LdColors.onGlass,
            style = MaterialTheme.typography.labelLarge,
        )
        WaveBars(
            levels,
            progress = 0f,
            color = LdColors.onGlass,
            modifier = Modifier.weight(1f).height(28.dp).padding(horizontal = 8.dp),
        )
        Text(
            stringResource(
                when {
                    cancel -> R.string.chat_voice_release
                    locked -> R.string.chat_voice_stop
                    else -> R.string.chat_voice_slide
                },
            ),
            color = if (cancel) Color(0xFFFFB4AB) else LdColors.onGlassMuted,
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

@Composable
fun VoiceDraftBar(clip: VoiceClip, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val player = remember(clip) { MediaPlayer() }
    val main = remember { Handler(Looper.getMainLooper()) }
    var playing by remember(clip) { mutableStateOf(false) }
    var position by remember(clip) { mutableIntStateOf(0) }
    DisposableEffect(clip) {
        val file = File(context.cacheDir, "voice-draft.m4a")
        try {
            file.writeBytes(clip.bytes)
            player.setDataSource(file.absolutePath)
            player.setOnCompletionListener {
                main.post {
                    playing = false
                    position = 0
                    try {
                        player.seekTo(0)
                    } catch (_: Exception) {
                        // Already reset.
                    }
                }
            }
            player.prepare()
        } catch (_: Exception) {
            // The bars still show. Play stays quiet.
        }
        onDispose {
            try {
                player.release()
            } catch (_: Exception) {
                // Already released.
            }
            file.delete()
        }
    }
    LaunchedEffect(playing) {
        while (isActive && playing) {
            position = try {
                player.currentPosition
            } catch (_: Exception) {
                0
            }
            delay(80)
        }
    }
    val duration = clip.durationMs.coerceAtLeast(1)
    val fraction = if (playing || position > 0) position / duration.toFloat() else 0f
    Row(modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        val press = remember { MutableInteractionSource() }
        Box(
            Modifier
                .size(40.dp)
                .pressScale(press)
                .clickable(
                    interactionSource = press,
                    indication = androidx.compose.foundation.LocalIndication.current,
                    onClick = {
                        try {
                            if (player.isPlaying) {
                                player.pause()
                                playing = false
                            } else {
                                player.start()
                                playing = true
                            }
                        } catch (_: Exception) {
                            playing = false
                        }
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            LuIcon(
                if (playing) Lu.Pause else Lu.Play,
                contentDescription = stringResource(if (playing) R.string.chat_voice_pause else R.string.chat_voice_play),
                tint = LdColors.onGlass,
            )
        }
        WaveBars(
            parseWave(clip.wave),
            fraction,
            LdColors.onGlass,
            Modifier.weight(1f).height(28.dp),
        )
        Text(
            voiceClock(if (playing) position else clip.durationMs),
            modifier = Modifier.padding(start = 8.dp),
            color = LdColors.onGlassMuted,
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

@Composable
fun ChatVoiceBubble(
    message: ChatMessage,
    ready: Boolean,
    downloadFailed: Boolean,
    progress: Float?,
    onLongPress: () -> Unit,
    onRetry: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val play by VoicePlay.state.collectAsState()
    val active = play.id == message.id
    val playing = active && play.playing
    val duration = message.json.optInt("dur", 0).coerceAtLeast(0)
    val position = if (active) play.position else 0
    val wave = remember(message.id, message.json.optString("wave")) { parseWave(message.json.optString("wave")) }
    val fraction = if (active && duration > 0) position / duration.toFloat() else 0f
    val ink = ChatTheme.ink(message.outgoing)
    LaunchedEffect(active, playing) {
        if (!active || !playing) return@LaunchedEffect
        while (isActive) {
            VoicePlay.tick()
            delay(80)
        }
    }
    Column(
        Modifier
            .widthIn(min = 180.dp, max = 260.dp)
            .combinedClickable(onClick = {}, onLongClick = onLongPress),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val press = remember(message.id) { MutableInteractionSource() }
            Box(
                Modifier
                    .size(36.dp)
                    .pressScale(press)
                    .clickable(
                        interactionSource = press,
                        indication = null,
                        enabled = ready,
                        onClick = {
                            scope.launch {
                                try {
                                    val file = withContext(Dispatchers.IO) {
                                        val bytes = ChatStore.readAttachment(context, message.id) ?: return@withContext null
                                        val dir = File(context.cacheDir, "voice-play")
                                        if (!dir.exists() && !dir.mkdirs()) return@withContext null
                                        val out = File(dir, message.id + ".m4a")
                                        if (!out.isFile || out.length() != bytes.size.toLong()) out.writeBytes(bytes)
                                        out
                                    } ?: return@launch
                                    VoicePlay.toggle(message.id, file)
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (_: Exception) {
                                    // The button stays. A later tap tries again.
                                }
                            }
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                LuIcon(
                    if (playing) Lu.Pause else Lu.Play,
                    contentDescription = stringResource(if (playing) R.string.chat_voice_pause else R.string.chat_voice_play),
                    tint = ink,
                    size = 22.dp,
                )
            }
            WaveBars(wave, fraction, ink, Modifier.weight(1f).height(28.dp))
            Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(start = 6.dp)) {
                Text(
                    voiceClock(if (playing || (active && position > 0)) position else duration),
                    color = ink.copy(alpha = 0.8f),
                    style = MaterialTheme.typography.labelSmall,
                )
                val speed = play.speed
                val label = voiceSpeedLabel(speed)
                Text(
                    label,
                    modifier = Modifier.clickable { VoicePlay.cycleSpeed() },
                    color = ink,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
        if (!ready) {
            // Reuse the shared overlay by leaving a short status. The parent also draws progress.
            if (downloadFailed) {
                Text(
                    stringResource(R.string.chat_download_failed),
                    color = if (message.outgoing) LdColors.white else Color(0xFFFFB4AB),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    stringResource(R.string.chat_download_retry),
                    modifier = Modifier.clickable(onClick = onRetry),
                    color = ink,
                    style = MaterialTheme.typography.labelLarge,
                )
            } else if (progress != null && progress > 0f) {
                Text(
                    stringResource(R.string.chat_downloading),
                    color = ink.copy(alpha = 0.75f),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}
