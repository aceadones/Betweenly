package app.loviedowpie

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * Plays the default alarm tone, at alarm volume, until the person taps Stop
 * or two minutes pass. Exists so a partner can find a phone that is in a bag.
 *
 * The notification is the only thing shown. It does not include a location,
 * a name, or a message. The ringtone is the system alarm sound, played locally;
 * nothing is uploaded while this service runs.
 */
class RingService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var ringtone: Ringtone? = null
    private var audio: AudioManager? = null
    private var savedVolume: Int = -1
    private var focus: AudioFocusRequest? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var stopping = false

    private val replay = object : Runnable {
        override fun run() {
            if (stopping) return
            val tone = ringtone
            if (tone != null && !tone.isPlaying) {
                try {
                    tone.play()
                } catch (_: Exception) {
                    // The vibrator and the notification still tell them.
                }
            }
            handler.postDelayed(this, 1_000L)
        }
    }

    private val autoStop = Runnable { shutdown() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shutdown()
            return START_NOT_STICKY
        }
        if (!enterForeground()) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!running) {
            running = true
            startRinging()
        }
        handler.removeCallbacks(autoStop)
        handler.postDelayed(autoStop, RING_MS)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        quiet()
    }

    private fun enterForeground(): Boolean {
        return try {
            val manager = getSystemService(NotificationManager::class.java) ?: return false
            if (manager.getNotificationChannel(CHANNEL) == null) {
                val channel = NotificationChannel(
                    CHANNEL,
                    getString(R.string.channel_ring),
                    NotificationManager.IMPORTANCE_HIGH,
                )
                channel.setSound(null, null)
                channel.enableVibration(false)
                channel.description = getString(R.string.ring_title)
                manager.createNotificationChannel(channel)
            }
            ServiceCompat.startForeground(
                this,
                NOTIF_ID,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
            )
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this,
            3,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            2,
            Intent(this, RingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_heart)
            .setContentTitle(getString(R.string.ring_title))
            .setContentText(getString(R.string.ring_body))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setContentIntent(open)
            .setFullScreenIntent(open, true)
            .addAction(R.drawable.ic_stat_heart, getString(R.string.stop), stop)
            .build()
    }

    private fun startRinging() {
        val pm = getSystemService(PowerManager::class.java)
        if (pm != null) {
            val lock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "app.loviedowpie:ring")
            lock.setReferenceCounted(false)
            try {
                lock.acquire(RING_MS)
                wakeLock = lock
            } catch (_: Exception) {
                // The foreground service still keeps the process up.
            }
        }
        val am = getSystemService(AudioManager::class.java)
        audio = am
        if (am != null) {
            try {
                if (savedVolume < 0) savedVolume = am.getStreamVolume(AudioManager.STREAM_ALARM)
                am.setStreamVolume(
                    AudioManager.STREAM_ALARM,
                    am.getStreamMaxVolume(AudioManager.STREAM_ALARM),
                    0,
                )
            } catch (_: Exception) {
                // Play at whatever volume the alarm stream already has.
            }
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                .setAudioAttributes(attrs)
                .build()
            focus = request
            try {
                am.requestAudioFocus(request)
            } catch (_: Exception) {
                focus = null
            }
        }
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        if (uri != null) {
            val tone = try {
                RingtoneManager.getRingtone(this, uri)
            } catch (_: Exception) {
                null
            }
            if (tone != null) {
                tone.audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                if (Build.VERSION.SDK_INT >= 28) tone.isLooping = true
                ringtone = tone
                try {
                    tone.play()
                } catch (_: Exception) {
                    ringtone = null
                }
            }
        }
        handler.postDelayed(replay, 1_000L)
        val vib = vibrator()
        if (vib != null && vib.hasVibrator()) {
            try {
                vib.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 600, 400), 0))
            } catch (_: Exception) {
                // No vibrator, or the effect was rejected.
            }
        }
    }

    private fun shutdown() {
        quiet()
        stopSelf()
    }

    /** Stops the tone and restores the alarm volume. Does not call stopSelf. */
    private fun quiet() {
        if (stopping) return
        stopping = true
        handler.removeCallbacks(replay)
        handler.removeCallbacks(autoStop)
        try {
            ringtone?.stop()
        } catch (_: Exception) {
            // Already stopped.
        }
        ringtone = null
        try {
            vibrator()?.cancel()
        } catch (_: Exception) {
            // Already cancelled.
        }
        val am = audio
        val volume = savedVolume
        if (am != null && volume >= 0) {
            try {
                am.setStreamVolume(AudioManager.STREAM_ALARM, volume, 0)
            } catch (_: Exception) {
                // The volume stays where the ring left it.
            }
        }
        savedVolume = -1
        val request = focus
        if (am != null && request != null) {
            try {
                am.abandonAudioFocusRequest(request)
            } catch (_: Exception) {
                // Focus is released when the process quiets down.
            }
        }
        focus = null
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Exception) {
            // Already released by the timeout.
        }
        wakeLock = null
        running = false
        try {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        } catch (_: Exception) {
            // Not in the foreground.
        }
    }

    private fun vibrator(): Vibrator? {
        return if (Build.VERSION.SDK_INT >= 31) {
            getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Vibrator::class.java)
        }
    }

    companion object {
        const val ACTION_STOP = "app.loviedowpie.action.RING_STOP"
        private const val CHANNEL = "ring"
        private const val NOTIF_ID = 1002
        private const val RING_MS = 120_000L

        @Volatile
        var running: Boolean = false
            private set

        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, RingService::class.java),
                )
            } catch (_: Exception) {
                // Background start was refused. The wake is lost; the next pull can try again.
            }
        }

        fun stop(context: Context) {
            try {
                context.startService(
                    Intent(context, RingService::class.java).setAction(ACTION_STOP),
                )
            } catch (_: Exception) {
                running = false
            }
        }
    }
}
