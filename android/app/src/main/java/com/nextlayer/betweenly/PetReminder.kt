package com.nextlayer.betweenly

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.time.ZoneId

/**
 * One local reminder a day at 8:30 pm IST (the streak day ends at 9:30 pm IST): if there is a streak and you have not both
 * messaged yet, there is one hour left. The alarm is inexact and does not wake
 * the phone more than once a day.
 *
 * DATA-FLOW: none. Local alarm and local notification.
 */
object PetReminder {
    private const val CHANNEL = "pet"
    private const val ID = 1010

    /** Schedules the next 23:00 reminder. Safe to call often. */
    fun schedule(context: Context) {
        val app = context.applicationContext
        try {
            val am = app.getSystemService(AlarmManager::class.java) ?: return
            // One hour before the streak day ends: 8:30 pm Indian Standard Time, for both phones.
            val ist = ZoneId.of("Asia/Kolkata")
            val now = java.time.ZonedDateTime.now(ist)
            var at = now.withHour(20).withMinute(30).withSecond(0).withNano(0)
            if (!at.isAfter(now)) at = at.plusDays(1)
            val millis = at.toInstant().toEpochMilli()
            val pi = PendingIntent.getBroadcast(
                app,
                7001,
                Intent(app, PetReminderReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pi)
        } catch (_: Exception) {
            // No alarm service. The streak card still shows the state.
        }
    }

    /** Posts the notice when a streak is at risk. */
    fun fire(context: Context) {
        val app = context.applicationContext
        try {
            val st = PetStore.state(app)
            if (st.streak <= 0 || (st.mineToday && st.theirsToday)) return
            val manager = app.getSystemService(NotificationManager::class.java) ?: return
            if (!manager.areNotificationsEnabled()) return
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(app, android.Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) return
            if (manager.getNotificationChannel(CHANNEL) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(CHANNEL, app.getString(R.string.pet_channel), NotificationManager.IMPORTANCE_DEFAULT),
                )
            }
            val open = PendingIntent.getActivity(
                app,
                7002,
                Intent(app, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    .putExtra(MainActivity.EXTRA_OPEN, "chat"),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val notification = NotificationCompat.Builder(app, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_logo)
                .setColor(ContextCompat.getColor(app, R.color.notif_accent))
                .setContentTitle(app.getString(R.string.pet_reminder_title))
                .setContentText(app.getString(R.string.pet_reminder_text, st.streak))
                .setAutoCancel(true)
                .setLocalOnly(true)
                .setContentIntent(open)
                .build()
            manager.notify(ID, notification)
        } catch (_: Exception) {
        }
    }
}

/** Fires at 23:00, posts the reminder when needed, and books the next day. */
class PetReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        PetReminder.fire(context)
        PetReminder.schedule(context)
    }
}
