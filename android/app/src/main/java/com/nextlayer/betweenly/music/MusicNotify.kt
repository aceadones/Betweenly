package com.nextlayer.betweenly.music

import android.app.NotificationManager
import android.content.Context
import com.nextlayer.betweenly.ChatNotifications

/**
 * Invite notice. The words come from [com.nextlayer.betweenly.notificationText]
 * (On shows the partner's name and "Listen together?", Off and Custom follow
 * Profile). Tapping opens the Activity tab. The music screen is not a launch
 * route, so the invite waits until that screen is opened.
 *
 * DATA-FLOW: nothing leaves the phone. This is a local notification.
 */
internal object MusicNotify {
    fun showInvite(context: Context) {
        ChatNotifications.onMusicInvite(context)
    }

    fun cancel(context: Context) {
        try {
            context.applicationContext
                .getSystemService(NotificationManager::class.java)
                ?.cancel(ChatNotifications.MUSIC_NOTIFICATION_ID)
        } catch (_: Exception) {
            // Already gone.
        }
    }
}
