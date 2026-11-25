package com.nextlayer.betweenly.music

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.nextlayer.betweenly.MainActivity
import com.nextlayer.betweenly.R

/**
 * Foreground notification for "Keep playing in background".
 * It does not own the player and it does not use a media session.
 * The words are "Music" and "Playing" or "Paused". No song title.
 * Started only after that switch is on. Default is off.
 *
 * DATA-FLOW: nothing leaves the phone.
 */
class MusicPlayService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val session = MusicBridge.session()
        if (session == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            MusicBridge.inCommand {
                when (intent?.action) {
                    ACTION_TOGGLE -> session.toggle()
                    ACTION_NEXT -> session.next()
                }
            }
        } catch (_: Exception) {
            // The screen still owns the player. A bad tap must not crash the service.
        }
        val live = MusicBridge.session()
        if (live == null || !enterForeground(live.playingNow())) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (Build.VERSION.SDK_INT >= 24) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private fun enterForeground(playing: Boolean): Boolean {
        return try {
            val manager = getSystemService(NotificationManager::class.java) ?: return false
            if (manager.getNotificationChannel(CHANNEL) == null) {
                val channel = NotificationChannel(
                    CHANNEL,
                    getString(R.string.music_notif_channel),
                    NotificationManager.IMPORTANCE_LOW,
                )
                channel.setSound(null, null)
                channel.enableVibration(false)
                channel.description = getString(R.string.music_notif_channel_desc)
                manager.createNotificationChannel(channel)
            }
            ServiceCompat.startForeground(
                this,
                NOTIF_ID,
                buildNotification(playing),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
            )
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun buildNotification(playing: Boolean): Notification {
        val open = PendingIntent.getActivity(
            this,
            64,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(MainActivity.EXTRA_OPEN, "activity"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val toggle = serviceIntent(ACTION_TOGGLE, 65)
        val next = serviceIntent(ACTION_NEXT, 66)
        val toggleIcon = if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        val toggleLabel = getString(if (playing) R.string.music_pause else R.string.music_play)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_heart)
            .setContentTitle(getString(R.string.music_notif_title))
            .setContentText(getString(if (playing) R.string.music_notif_playing else R.string.music_notif_paused))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(open)
            .addAction(toggleIcon, toggleLabel, toggle)
            .addAction(android.R.drawable.ic_media_next, getString(R.string.music_next), next)
            .build()
    }

    private fun serviceIntent(action: String, request: Int): PendingIntent =
        PendingIntent.getService(
            this,
            request,
            Intent(this, MusicPlayService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    companion object {
        private const val CHANNEL = "music_playback"
        private const val NOTIF_ID = 1006
        private const val ACTION_TOGGLE = "com.nextlayer.betweenly.music.TOGGLE"
        private const val ACTION_NEXT = "com.nextlayer.betweenly.music.NEXT"

        fun start(context: Context): Boolean = try {
            val intent = Intent(context.applicationContext, MusicPlayService::class.java)
            ContextCompat.startForegroundService(context.applicationContext, intent)
            true
        } catch (_: Exception) {
            false
        }

        fun stop(context: Context) {
            try {
                context.applicationContext.stopService(
                    Intent(context.applicationContext, MusicPlayService::class.java),
                )
            } catch (_: Exception) {
                // Already gone.
            }
        }
    }
}
