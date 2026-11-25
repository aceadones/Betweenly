package com.nextlayer.betweenly

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
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.launch

/**
 * Plays the default alarm tone, at alarm volume, until Dismiss is tapped,
 * a matching ringstop arrives, or two minutes pass.
 *
 * The notice does not say "ring" and does not include a name. The lock screen
 * shows a generic public version. The tone is the system alarm sound, played
 * locally. Nothing is uploaded while the tone plays. Dismissing it here sends
 * a small encrypted ringack so the sender's button can return to idle.
 * A ringstop does not send anything back.
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

    private var lastStartId = 0

    private val autoStop = Runnable { shutdown(ack = true, startId = lastStartId) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        if (intent?.action == ACTION_STOP) {
            val stopRid = canonicalRid(intent.getStringExtra(EXTRA_RID))
            val ack = intent.getBooleanExtra(EXTRA_ACK, true)
            if (stopRid != null && incomingRid != null && stopRid != incomingRid) {
                // A stop for an older alarm. The one that is playing keeps going.
                if (!running) stopSelf(startId)
                return START_NOT_STICKY
            }
            shutdown(ack, startId)
            return START_NOT_STICKY
        }
        // A new alarm can arrive on the same instance after Dismiss called stopSelf.
        stopping = false
        val rid = canonicalRid(intent?.getStringExtra(EXTRA_RID))
        incomingRid = rid
        if (!enterForeground()) {
            incomingRid = null
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (!running) {
            running = true
            startRinging()
        } else {
            holdWake()
        }
        handler.removeCallbacks(autoStop)
        handler.postDelayed(autoStop, LIMIT_MS)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        quiet()
    }

    private fun enterForeground(): Boolean {
        return try {
            val manager = getSystemService(NotificationManager::class.java) ?: return false
            try {
                manager.deleteNotificationChannel(LEGACY_CHANNEL)
            } catch (_: Exception) {
                // The old channel was already gone.
            }
            val existing = manager.getNotificationChannel(CHANNEL)
            if (existing == null) {
                val channel = NotificationChannel(
                    CHANNEL,
                    getString(R.string.channel_alarm),
                    NotificationManager.IMPORTANCE_HIGH,
                )
                channel.setSound(null, null)
                channel.enableVibration(false)
                channel.lockscreenVisibility = Notification.VISIBILITY_PRIVATE
                channel.description = getString(R.string.channel_alarm_desc)
                manager.createNotificationChannel(channel)
            } else if (existing.description != getString(R.string.channel_alarm_desc)) {
                existing.description = getString(R.string.channel_alarm_desc)
                manager.createNotificationChannel(existing)
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
            Intent(this, RingService::class.java)
                .setAction(ACTION_STOP)
                .putExtra(EXTRA_ACK, true),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val copy = notificationText(this, NotifEvent.CallRing)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_heart)
            .setContentTitle(copy.title)
            .setContentText(copy.text)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(discreetPublicNotification(this, CHANNEL))
            .setLocalOnly(true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .setFullScreenIntent(open, true)
            .addAction(R.drawable.ic_stat_heart, getString(R.string.notif_dismiss), stop)
            .build()
    }

    private fun startRinging() {
        holdWake()
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

    private fun holdWake() {
        val held = wakeLock
        if (held != null) {
            try {
                held.acquire(LIMIT_MS)
                return
            } catch (_: Exception) {
                // Take a fresh lock below.
            }
        }
        val pm = getSystemService(PowerManager::class.java) ?: return
        val lock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "com.nextlayer.betweenly:ring")
        lock.setReferenceCounted(false)
        try {
            lock.acquire(LIMIT_MS)
            wakeLock = lock
        } catch (_: Exception) {
            // The foreground service still keeps the process up.
        }
    }

    private fun shutdown(ack: Boolean, startId: Int) {
        if (stopping) {
            stopSelf(startId)
            return
        }
        val rid = incomingRid
        quiet()
        if (ack && rid != null) {
            val app = applicationContext
            AppScope.scope.launch {
                try {
                    // DATA-FLOW: ringack for this id, encrypted, to my partner. No wake ping. No location and no name.
                    Outbox.sendRingAck(app, rid)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // The sender's own two-minute cap still clears their button.
                }
            }
        }
        // stopSelf(id) leaves a newer start (a new alarm) running.
        stopSelf(startId)
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
        incomingRid = null
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
        const val ACTION_STOP = "com.nextlayer.betweenly.action.RING_STOP"
        private const val EXTRA_ACK = "ack"
        private const val EXTRA_RID = "rid"
        private const val CHANNEL = "alarms"
        private const val LEGACY_CHANNEL = "ring"
        private const val NOTIF_ID = 1002

        /** Receiver and sender both give up after this long. */
        const val LIMIT_MS = 120_000L

        @Volatile
        var running: Boolean = false
            private set

        /** Id of the alarm playing now. Null for a plain ring with no id. */
        @Volatile
        private var incomingRid: String? = null

        fun start(context: Context, rid: String? = null) {
            val clean = canonicalRid(rid)
            incomingRid = clean
            try {
                val intent = Intent(context, RingService::class.java)
                if (clean != null) intent.putExtra(EXTRA_RID, clean)
                ContextCompat.startForegroundService(context, intent)
            } catch (_: Exception) {
                incomingRid = null
                // Background start was refused. The wake is lost; the next pull can try again.
            }
        }

        /**
         * Stops the alarm without telling the sender.
         * A non-empty [rid] that is not the one playing is ignored.
         */
        fun stop(context: Context, rid: String = "") {
            val clean = canonicalRid(rid)
            val active = incomingRid
            if (clean != null && active != null && clean != active) return
            try {
                val intent = Intent(context, RingService::class.java)
                    .setAction(ACTION_STOP)
                    .putExtra(EXTRA_ACK, false)
                if (clean != null) intent.putExtra(EXTRA_RID, clean)
                context.startService(intent)
            } catch (_: Exception) {
                incomingRid = null
                running = false
            }
        }
    }
}
