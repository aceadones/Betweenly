package app.loviedowpie.music

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import app.loviedowpie.MainActivity
import app.loviedowpie.R

/**
 * Content-free invite notice. The words are "Listen together?".
 * It uses the existing messages channel. Tapping opens the Activity tab.
 * The music screen is not a launch route, so the invite waits until that
 * screen is opened.
 *
 * DATA-FLOW: nothing leaves the phone. This is a local notification.
 */
internal object MusicNotify {
    private const val CHANNEL = "messages"
    private const val NOTIFICATION_ID = 1005

    fun showInvite(context: Context) {
        val app = context.applicationContext
        try {
            val manager = app.getSystemService(NotificationManager::class.java) ?: return
            if (!canPost(app, manager)) return
            if (manager.getNotificationChannel(CHANNEL) == null) {
                val channel = NotificationChannel(
                    CHANNEL,
                    app.getString(R.string.channel_messages),
                    NotificationManager.IMPORTANCE_HIGH,
                )
                channel.description = app.getString(R.string.chat_notif_channel_desc)
                manager.createNotificationChannel(channel)
            }
            val pending = PendingIntent.getActivity(
                app,
                32,
                Intent(app, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    .putExtra(MainActivity.EXTRA_OPEN, "activity"),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val notification = NotificationCompat.Builder(app, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_heart)
                .setContentTitle(app.getString(R.string.music_invited))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(pending)
                .build()
            manager.notify(NOTIFICATION_ID, notification)
        } catch (_: Exception) {
            // Permission revoked or a missing channel. The invite is still saved.
        }
    }

    fun cancel(context: Context) {
        try {
            context.applicationContext
                .getSystemService(NotificationManager::class.java)
                ?.cancel(NOTIFICATION_ID)
        } catch (_: Exception) {
            // Already gone.
        }
    }

    private fun canPost(context: Context, manager: NotificationManager): Boolean {
        if (!manager.areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT < 33) return true
        return ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }
}
