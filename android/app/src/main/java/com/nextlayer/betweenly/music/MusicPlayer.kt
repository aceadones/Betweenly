package com.nextlayer.betweenly.music

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build

/**
 * One [MediaPlayer] for the music screen. Created when that screen opens,
 * released when it closes. There is no playback service: leaving the screen
 * stops the audio. Decoding starts at [play], not before.
 * Volume is the system media stream. This class never calls setVolume.
 *
 * DATA-FLOW: none by itself. A https URL, if a future source returns one, is
 * read by the platform player and is not sent to the relay.
 */
internal class MusicPlayer(
    private val context: Context,
    private val listener: Listener,
) {
    interface Listener {
        fun onReady(durationMs: Int)
        fun onEnded()
        fun onError()
        fun onFocusLost()
        fun onNoisy()
    }

    private val main = context.mainExecutor
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

    private var mp: MediaPlayer? = null
    private var focus: AudioFocusRequest? = null
    private var generation = 0
    private var prepared = false
    private var wantPlay = false
    private var transientLoss = false
    private var released = false
    private var registered = false

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        main.execute {
            if (released) return@execute
            when (change) {
                AudioManager.AUDIOFOCUS_LOSS -> {
                    transientLoss = false
                    pauseUser()
                    listener.onFocusLost()
                }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                    transientLoss = wantPlay
                    try {
                        mp?.pause()
                    } catch (_: Exception) {
                        // Already stopped.
                    }
                }
                AudioManager.AUDIOFOCUS_GAIN -> {
                    if (transientLoss) {
                        transientLoss = false
                        resume()
                    }
                }
            }
        }
    }

    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent?.action != AudioManager.ACTION_AUDIO_BECOMING_NOISY) return
            main.execute {
                if (released) return@execute
                pauseUser()
                listener.onNoisy()
            }
        }
    }

    fun play(src: String, atMs: Long, start: Boolean) {
        if (released || src.isBlank()) {
            listener.onError()
            return
        }
        val mine = ++generation
        wantPlay = start
        prepared = false
        val player = obtain() ?: run {
            listener.onError()
            return
        }
        try {
            try {
                if (player.isPlaying) player.pause()
            } catch (_: Exception) {
                // Idle players throw. reset() is next.
            }
            player.reset()
            player.setAudioAttributes(attributes)
            player.setOnPreparedListener { ready ->
                main.execute {
                    if (mine != generation || released) return@execute
                    prepared = true
                    if (atMs > 0L) seekOn(ready, atMs)
                    if (wantPlay) {
                        if (!requestFocus() || !startIfWanted(ready)) {
                            wantPlay = false
                            listener.onFocusLost()
                        }
                    }
                    val dur = try {
                        ready.duration
                    } catch (_: Exception) {
                        0
                    }
                    listener.onReady(dur)
                }
            }
            player.setOnCompletionListener {
                main.execute {
                    if (mine != generation || released) return@execute
                    wantPlay = false
                    listener.onEnded()
                }
            }
            player.setOnErrorListener { _, _, _ ->
                main.execute {
                    if (mine != generation || released) return@execute
                    prepared = false
                    wantPlay = false
                    listener.onError()
                }
                true
            }
            setSource(player, src)
            player.prepareAsync()
        } catch (_: Exception) {
            prepared = false
            wantPlay = false
            if (mine == generation) listener.onError()
        }
    }

    fun resume() {
        if (released) return
        wantPlay = true
        val player = mp
        if (player == null || !prepared) return
        if (!requestFocus()) {
            wantPlay = false
            listener.onFocusLost()
            return
        }
        if (!startIfWanted(player)) listener.onError()
    }

    /** Pauses output but keeps [wantPlay], so a hidden screen can resume. */
    fun pauseOutput() {
        try {
            if (mp?.isPlaying == true) mp?.pause()
        } catch (_: Exception) {
            // The player was already idle.
        }
    }

    fun resumeOutput() {
        if (wantPlay) resume()
    }

    fun pauseUser() {
        wantPlay = false
        transientLoss = false
        try {
            mp?.pause()
        } catch (_: Exception) {
            // Not in the started state.
        }
    }

    fun seekTo(ms: Long) {
        val player = mp ?: return
        if (!prepared || released) return
        seekOn(player, ms)
    }

    fun hasPrepared(): Boolean = prepared && !released

    fun isPlaying(): Boolean = try {
        mp?.isPlaying == true
    } catch (_: Exception) {
        false
    }

    fun position(): Long = try {
        mp?.currentPosition?.toLong()?.coerceAtLeast(0L) ?: 0L
    } catch (_: Exception) {
        0L
    }

    fun duration(): Long = try {
        val value = mp?.duration ?: 0
        if (value > 0) value.toLong() else 0L
    } catch (_: Exception) {
        0L
    }

    fun release() {
        if (released) return
        released = true
        generation++
        wantPlay = false
        prepared = false
        transientLoss = false
        abandonFocus()
        unregisterNoisy()
        val player = mp
        mp = null
        try {
            player?.reset()
        } catch (_: Exception) {
            // Already released.
        }
        try {
            player?.release()
        } catch (_: Exception) {
            // Already released.
        }
    }

    private fun obtain(): MediaPlayer? {
        mp?.let { return it }
        return try {
            val created = MediaPlayer()
            mp = created
            registerNoisy()
            created
        } catch (_: Exception) {
            null
        }
    }

    private fun setSource(player: MediaPlayer, src: String) {
        when {
            src.startsWith("content://") || src.startsWith("file://") ->
                player.setDataSource(context, Uri.parse(src))
            src.startsWith("https://") -> player.setDataSource(src)
            src.startsWith("/") -> player.setDataSource(src)
            else -> throw IllegalArgumentException("unsupported source")
        }
    }

    private fun seekOn(player: MediaPlayer, ms: Long) {
        val clamped = ms.coerceIn(0L, Int.MAX_VALUE.toLong())
        try {
            player.seekTo(clamped, MediaPlayer.SEEK_CLOSEST)
        } catch (_: Exception) {
            // Not prepared yet. The next play() applies the position.
        }
    }

    private fun startIfWanted(player: MediaPlayer): Boolean = try {
        if (!player.isPlaying) player.start()
        true
    } catch (_: Exception) {
        false
    }

    private fun requestFocus(): Boolean {
        val manager = context.getSystemService(AudioManager::class.java) ?: return false
        val request = focus ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener(focusListener)
            .setWillPauseWhenDucked(false)
            .build()
            .also { focus = it }
        return try {
            manager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } catch (_: Exception) {
            false
        }
    }

    private fun abandonFocus() {
        val request = focus ?: return
        focus = null
        val manager = context.getSystemService(AudioManager::class.java) ?: return
        try {
            manager.abandonAudioFocusRequest(request)
        } catch (_: Exception) {
            // The system already dropped it.
        }
    }

    private fun registerNoisy() {
        if (registered) return
        val filter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(noisy, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                context.registerReceiver(noisy, filter)
            }
            registered = true
        } catch (_: Exception) {
            registered = false
        }
    }

    private fun unregisterNoisy() {
        if (!registered) return
        registered = false
        try {
            context.unregisterReceiver(noisy)
        } catch (_: Exception) {
            // Not registered on this context.
        }
    }
}
