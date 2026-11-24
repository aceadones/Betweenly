package app.loviedowpie

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

/**
 * Content-free notices. A new message says "New message". A new drawing says
 * "New drawing". Neither includes a name, a message, or a picture.
 *
 * Tapping a message notice opens [MainActivity] with extra `open` = `chat`.
 * Tapping a drawing notice uses `open` = `activity`.
 * DATA-FLOW: nothing leaves the phone. These are local notifications.
 */
object ChatNotifications {
    private const val CHANNEL = "messages"
    private const val NOTIFICATION_ID = 1003
    private const val DRAW_CHANNEL = "drawings"
    private const val DRAW_NOTIFICATION_ID = 1004

    fun onIncoming(context: Context) {
        if (AppState.foreground) return
        if (!Store.notifyMessages(context)) return
        // Text is only "New message". No partner name, no message body, no unread count.
        post(
            context,
            channelId = CHANNEL,
            channelName = R.string.channel_messages,
            channelDesc = R.string.chat_notif_channel_desc,
            notificationId = NOTIFICATION_ID,
            title = R.string.notif_new_message,
            open = "chat",
            requestCode = 30,
        )
    }

    /**
     * Content-free "New drawing" notice. [DrawInbound] calls this only after a
     * drawing was saved and only when the app is in the background.
     * DATA-FLOW: nothing leaves the phone. This is a local notification.
     */
    fun onDrawing(context: Context) {
        if (AppState.foreground) return
        if (!Store.notifyDrawings(context)) return
        post(
            context,
            channelId = DRAW_CHANNEL,
            channelName = R.string.channel_drawings,
            channelDesc = R.string.draw_notif_channel_desc,
            notificationId = DRAW_NOTIFICATION_ID,
            title = R.string.notif_new_drawing,
            open = "activity",
            requestCode = 31,
        )
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

    private fun post(
        context: Context,
        channelId: String,
        channelName: Int,
        channelDesc: Int,
        notificationId: Int,
        title: Int,
        open: String,
        requestCode: Int,
    ) {
        val app = context.applicationContext
        try {
            val manager = app.getSystemService(NotificationManager::class.java) ?: return
            if (!canPost(app, manager)) return
            if (manager.getNotificationChannel(channelId) == null) {
                val channel = NotificationChannel(
                    channelId,
                    app.getString(channelName),
                    NotificationManager.IMPORTANCE_HIGH,
                )
                channel.description = app.getString(channelDesc)
                manager.createNotificationChannel(channel)
            }
            val pending = PendingIntent.getActivity(
                app,
                requestCode,
                Intent(app, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    .putExtra(MainActivity.EXTRA_OPEN, open),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val notification = NotificationCompat.Builder(app, channelId)
                .setSmallIcon(R.drawable.ic_stat_heart)
                .setContentTitle(app.getString(title))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(pending)
                .build()
            manager.notify(notificationId, notification)
        } catch (_: Exception) {
            // Permission revoked or a missing channel. The message is still saved.
        }
    }

    private fun canPost(context: Context, manager: NotificationManager): Boolean {
        if (!manager.areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT < 33) return true
        return ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }
}
