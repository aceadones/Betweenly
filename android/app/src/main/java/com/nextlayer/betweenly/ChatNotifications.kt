package com.nextlayer.betweenly

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject

/**
 * Local notices for chat, drawings, pings, and listen-together invites.
 * The words come from [notificationText]. Tapping a message opens Chat.
 * A drawing opens Activity. A listen-together invite opens Duet, or the
 * Duet releases page when Duet is not installed. A ping opens Location.
 *
 * DATA-FLOW: nothing leaves the phone. These are local notifications.
 * On mode may put the decrypted message, already stored on this phone, into the shade.
 * The lock screen uses a generic public version.
 */
object ChatNotifications {
    private const val CHANNEL = "messages"
    private const val NOTIFICATION_ID = 1003
    private const val DRAW_CHANNEL = "drawings"
    private const val DRAW_NOTIFICATION_ID = 1004
    internal const val MUSIC_NOTIFICATION_ID = 1005
    private const val PING_ID = 1006

    /**
     * [json] is the decrypted chat, attachment, or link message.
     * Null still posts a notice; On mode then has no message body to show.
     */
    fun onIncoming(context: Context, json: JSONObject? = null) {
        if (AppState.foreground) return
        val copy = notificationText(
            context,
            event = eventOf(json),
            sender = Store.partnerName(context),
            detail = detailOf(json),
        )
        post(
            context = context,
            channelId = CHANNEL,
            channelName = R.string.channel_messages,
            channelDesc = R.string.notif_channel_messages_desc,
            notificationId = NOTIFICATION_ID,
            copy = copy,
            open = "chat",
            requestCode = 30,
            alertEveryTime = false,
        )
    }

    /**
     * Drawing notice. [DrawInbound] calls this after a drawing is saved, and
     * only the drawing switch can suppress it. The words still follow the
     * message setting. The picture is not attached.
     * DATA-FLOW: nothing leaves the phone.
     */
    fun onDrawing(context: Context) {
        if (AppState.foreground) return
        if (!Store.notifyDrawings(context)) return
        val copy = notificationText(
            context,
            event = NotifEvent.Drawing,
            sender = Store.partnerName(context),
        )
        post(
            context = context,
            channelId = DRAW_CHANNEL,
            channelName = R.string.channel_drawings,
            channelDesc = R.string.notif_channel_drawings_desc,
            notificationId = DRAW_NOTIFICATION_ID,
            copy = copy,
            open = "activity",
            requestCode = 31,
            alertEveryTime = false,
        )
    }

    /**
     * One short notice for `{"t":"ping"}`. Uses the message channel's sound
     * and vibration, does not loop, and goes away when tapped.
     * Shown even while the app is open, so the nudge is audible.
     * DATA-FLOW: nothing leaves the phone.
     */
    fun onPing(context: Context) {
        val copy = notificationText(
            context,
            event = NotifEvent.Ping,
            sender = Store.partnerName(context),
        )
        post(
            context = context,
            channelId = CHANNEL,
            channelName = R.string.channel_messages,
            channelDesc = R.string.notif_channel_messages_desc,
            notificationId = PING_ID,
            copy = copy,
            open = null,
            requestCode = 33,
            alertEveryTime = true,
        )
    }

    /**
     * Listen-together invite. Same wording rules as a message. No song title.
     * The notice and its button open Duet, or the releases page when Duet is absent.
     * DATA-FLOW: nothing leaves this process. The button is an activity intent.
     */
    fun onMusicInvite(context: Context) {
        val app = context.applicationContext
        val copy = notificationText(
            context,
            event = NotifEvent.MusicInvite,
            sender = Store.partnerName(context),
        )
        val installed = DuetBridge.installed(app)
        val label = app.getString(if (installed) R.string.duet_open else R.string.duet_install)
        post(
            context = context,
            channelId = CHANNEL,
            channelName = R.string.channel_messages,
            channelDesc = R.string.notif_channel_messages_desc,
            notificationId = MUSIC_NOTIFICATION_ID,
            copy = copy,
            open = null,
            requestCode = 32,
            alertEveryTime = false,
            target = DuetBridge.viewIntent(app),
            actionLabel = label,
        )
    }

    fun cancelMusic(context: Context) {
        cancelId(context, MUSIC_NOTIFICATION_ID)
    }

    fun cancel(context: Context) {
        cancelId(context, NOTIFICATION_ID)
    }

    private fun cancelId(context: Context, id: Int) {
        try {
            context.applicationContext
                .getSystemService(NotificationManager::class.java)
                ?.cancel(id)
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
        copy: NotifCopy,
        open: String?,
        requestCode: Int,
        alertEveryTime: Boolean,
        target: Intent? = null,
        actionLabel: String? = null,
    ) {
        val app = context.applicationContext
        try {
            val manager = app.getSystemService(NotificationManager::class.java) ?: return
            if (!canPost(app, manager)) return
            ensureChannel(manager, app, channelId, channelName, channelDesc)
            val intent = target ?: Intent(app, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            if (target == null && open != null) intent.putExtra(MainActivity.EXTRA_OPEN, open)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val pending = PendingIntent.getActivity(
                app,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val builder = NotificationCompat.Builder(app, channelId)
                .setSmallIcon(R.drawable.ic_stat_heart)
                .setContentTitle(copy.title.ifBlank { app.getString(R.string.app_name) })
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setPublicVersion(discreetPublicNotification(app, channelId))
                .setLocalOnly(true)
                .setAutoCancel(true)
                .setOnlyAlertOnce(!alertEveryTime)
                .setContentIntent(pending)
            if (copy.text.isNotEmpty()) {
                builder.setContentText(copy.text)
                builder.setStyle(NotificationCompat.BigTextStyle().bigText(copy.text))
            }
            if (!actionLabel.isNullOrBlank()) {
                builder.addAction(R.drawable.ic_stat_heart, actionLabel, pending)
            }
            manager.notify(notificationId, builder.build())
        } catch (_: Exception) {
            // Permission revoked or a missing channel. The message is still saved.
        }
    }

    private fun ensureChannel(
        manager: NotificationManager,
        app: Context,
        channelId: String,
        nameRes: Int,
        descRes: Int,
    ) {
        val desc = app.getString(descRes)
        val existing = manager.getNotificationChannel(channelId)
        if (existing == null) {
            val channel = NotificationChannel(
                channelId,
                app.getString(nameRes),
                NotificationManager.IMPORTANCE_HIGH,
            )
            channel.description = desc
            channel.lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            manager.createNotificationChannel(channel)
            return
        }
        if (existing.description != desc) {
            existing.description = desc
            manager.createNotificationChannel(existing)
        }
    }

    private fun canPost(context: Context, manager: NotificationManager): Boolean {
        if (!manager.areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT < 33) return true
        return ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun eventOf(json: JSONObject?): NotifEvent {
        if (json == null) return NotifEvent.Message
        return when (json.optString("t")) {
            "att" -> when (json.optString("kind")) {
                "photo" -> NotifEvent.Photo
                "video" -> NotifEvent.Video
                "sticker" -> NotifEvent.Sticker
                "gif" -> NotifEvent.Gif
                else -> NotifEvent.File
            }
            "link" -> NotifEvent.Link
            else -> NotifEvent.Message
        }
    }

    /** Decrypted text already on this phone. Attachments use a label instead. */
    private fun detailOf(json: JSONObject?): String {
        if (json == null) return ""
        return when (json.optString("t")) {
            "chat" -> json.optString("text")
            "link" -> json.optString("text").ifBlank {
                json.optString("title")
            }.ifBlank {
                json.optString("url")
            }
            else -> ""
        }
    }
}
