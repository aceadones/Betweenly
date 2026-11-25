package com.nextlayer.betweenly

import android.app.Notification
import android.content.Context
import androidx.core.app.NotificationCompat

/** How a message-like notice is worded. Stored on this phone only. */
enum class NotifMode {
    /** Partner name and the decrypted text, or a short label for an attachment. */
    On,

    /** Title "Betweenly", text "New message". */
    Off,

    /** The saved disguise is the whole notice. */
    Custom,
}

/** Which local notice is being built. The wire type stays separate. */
enum class NotifEvent {
    Message,
    Photo,
    Video,
    Sticker,
    Gif,
    File,
    Link,
    Drawing,
    Ping,
    MusicInvite,
    CallRing,
}

/** Title and body for one local notification. Either string may be blank. */
data class NotifCopy(val title: String, val text: String)

/** Cap for the custom message line and the alarm disguise. */
const val NOTIF_TEXT_MAX = 48

/**
 * One line, no control characters, not trimmed, so a field can hold a trailing space
 * until Save. Emoji stay. Newlines become spaces.
 */
fun clipNotifDraft(raw: String): String =
    raw.replace('\n', ' ').replace('\r', ' ').filter { it >= ' ' }.take(NOTIF_TEXT_MAX)

/**
 * Title and body for one local notice.
 *
 * [mode], [custom], and [alarm] override the saved prefs so Profile can preview a
 * draft. Call ring uses [alarm] only. Every other event uses the message mode:
 * Off and Custom ignore [sender] and [detail].
 *
 * DATA-FLOW: nothing leaves the phone. On mode reads the partner name and the
 * decrypted text already held on this phone. The custom line and the alarm
 * disguise are prefs on this phone.
 */
fun notificationText(
    context: Context,
    event: NotifEvent,
    sender: String = "",
    detail: String = "",
    mode: NotifMode? = null,
    custom: String? = null,
    alarm: String? = null,
): NotifCopy {
    if (event == NotifEvent.CallRing) {
        val disguise = clipNotifDraft(alarm ?: Store.notifAlarmText(context)).trim()
        val title = disguise.ifEmpty { context.getString(R.string.notif_alarm_title) }
        return NotifCopy(title, context.getString(R.string.notif_alarm_body))
    }
    val chosen = mode ?: Store.notifMode(context)
    val customText = clipNotifDraft(custom ?: Store.notifCustom(context)).trim()
    val appName = context.getString(R.string.app_name)
    val generic = context.getString(R.string.notif_new_message)
    when (chosen) {
        NotifMode.Off -> return NotifCopy(appName, generic)
        NotifMode.Custom -> {
            return if (customText.isEmpty()) NotifCopy(appName, generic) else NotifCopy(customText, "")
        }
        NotifMode.On -> Unit
    }
    val name = sender.trim().ifBlank { Store.partnerName(context).trim() }
        .ifBlank { context.getString(R.string.partner_fallback) }
    val body = when (event) {
        NotifEvent.Message, NotifEvent.Link -> oneLine(detail).ifBlank { generic }
        NotifEvent.Photo -> context.getString(R.string.chat_reply_photo)
        NotifEvent.Video -> context.getString(R.string.chat_reply_video)
        NotifEvent.Sticker -> context.getString(R.string.chat_reply_sticker)
        NotifEvent.Gif -> context.getString(R.string.chat_reply_gif)
        NotifEvent.File -> context.getString(R.string.chat_reply_file)
        NotifEvent.Drawing -> context.getString(R.string.notif_new_drawing)
        NotifEvent.Ping -> generic
        NotifEvent.MusicInvite -> context.getString(R.string.music_invited)
        NotifEvent.CallRing -> context.getString(R.string.notif_alarm_body)
    }
    return NotifCopy(name, body)
}

/**
 * Lock-screen stand-in. No name, no message, and not the word "ring".
 * Posted only as [NotificationCompat.Builder.setPublicVersion].
 * DATA-FLOW: nothing leaves the phone.
 */
internal fun discreetPublicNotification(context: Context, channelId: String): Notification {
    return NotificationCompat.Builder(context, channelId)
        .setSmallIcon(R.drawable.ic_stat_heart)
        .setContentTitle(context.getString(R.string.app_name))
        .setContentText(context.getString(R.string.notif_lock_generic))
        .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        .setLocalOnly(true)
        .build()
}

private fun oneLine(raw: String): String =
    raw.replace('\n', ' ').replace('\r', ' ').filter { it >= ' ' }.trim().take(140)
