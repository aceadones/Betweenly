package com.nextlayer.betweenly

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Location
import android.os.BatteryManager
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.android.gms.tasks.Tasks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Turns this phone's location sharing on and off, and answers "do we have permission?"
 * for the UI. The actual fixes are taken by [LocationAlarmReceiver] (about every
 * 10 minutes) or [LiveLocationService] (live mode).
 *
 * [setShare] and [setLive] return immediately. The encrypted message is sent on
 * [AppScope]. Neither function asks for a permission; if location is denied, the
 * partner still gets battery updates.
 */
object LocationSharing {
    fun hasLocationPermission(context: Context): Boolean {
        val fine = ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.ACCESS_COARSE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    /** On Android 10+ the background alarm needs this in addition to the foreground permission. */
    fun hasBackgroundLocation(context: Context): Boolean {
        if (!hasLocationPermission(context)) return false
        if (Build.VERSION.SDK_INT < 29) return true
        return ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.ACCESS_BACKGROUND_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun batteryOptimizationsIgnored(context: Context): Boolean {
        val pm = context.getSystemService(android.os.PowerManager::class.java) ?: return false
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * Opens the system screen that lets this app run its ~10 minute alarm during
     * doze. No-op if the user already allowed it, or if the screen is missing.
     */
    fun requestUnrestrictedBattery(context: Context) {
        if (batteryOptimizationsIgnored(context)) return
        val intent = Intent(
            android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            android.net.Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (_: Exception) {
            // The periodic alarm still runs; doze may delay it.
        }
    }

    /**
     * Records the switch, tells the partner, and starts or stops the scheduler.
     * Turning sharing off also stops live mode. The "share" message is the only
     * thing sent when turning off, so the last coordinates are not published again.
     */
    fun setShare(context: Context, on: Boolean) {
        val app = context.applicationContext
        Store.setShareOn(app, on)
        if (!on) {
            Store.setLiveOn(app, false)
            SharingScheduler.cancel(app)
            LiveLocationService.stop(app, sendFinal = false)
        } else if (!Store.liveOn(app)) {
            SharingScheduler.schedule(app)
            AppScope.scope.launch { LocationSender.sendOnce(app) }
        }
        AppScope.scope.launch {
            if (!Store.isPaired(app)) return@launch
            // DATA-FLOW: sharing on/off, inside an encrypted "share" message, to my partner, with an empty wake ping.
            Outbox.send(app, Msg.share(on), wake = true)
        }
    }

    /**
     * Live mode replaces the 10 minute alarm with [LiveLocationService].
     * Turning it on also turns sharing on. If the system refuses the foreground
     * service, we fall back to the slow alarm.
     */
    fun setLive(context: Context, on: Boolean) {
        val app = context.applicationContext
        if (on) {
            if (!Store.shareOn(app)) {
                Store.setShareOn(app, true)
                AppScope.scope.launch {
                    if (Store.isPaired(app)) {
                        // DATA-FLOW: sharing on, encrypted "share" message, to my partner, with an empty wake ping.
                        Outbox.send(app, Msg.share(true), wake = true)
                    }
                }
            }
            Store.setLiveOn(app, true)
            SharingScheduler.cancel(app)
            LiveLocationService.allowStart()
            try {
                ContextCompat.startForegroundService(app, Intent(app, LiveLocationService::class.java))
            } catch (_: Exception) {
                Store.setLiveOn(app, false)
                if (Store.shareOn(app)) SharingScheduler.schedule(app)
            }
        } else {
            Store.setLiveOn(app, false)
            if (Store.shareOn(app)) SharingScheduler.schedule(app)
            LiveLocationService.stop(app, sendFinal = true)
        }
    }
}

/**
 * One inexact alarm, about 10 minutes out, re-armed by [LocationAlarmReceiver]
 * after each tick. [AlarmManager.setAndAllowWhileIdle] is the non-exact API that
 * is still allowed to fire in doze (at most about once per 10 minutes while idle).
 * A repeating alarm would not.
 */
object SharingScheduler {
    const val INTERVAL_MS = 10L * 60L * 1000L
    private const val ACTION = "com.nextlayer.betweenly.LOCATION_TICK"

    fun schedule(context: Context) {
        val app = context.applicationContext
        val manager = app.getSystemService(AlarmManager::class.java) ?: return
        val at = SystemClock.elapsedRealtime() + INTERVAL_MS
        try {
            manager.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pending(app))
        } catch (_: Exception) {
            // Rejected (for example after a force-stop). The next app open tries again.
        }
    }

    fun cancel(context: Context) {
        val app = context.applicationContext
        val manager = app.getSystemService(AlarmManager::class.java) ?: return
        try {
            manager.cancel(pending(app))
        } catch (_: Exception) {
            // Already gone.
        }
    }

    private fun pending(context: Context): PendingIntent {
        val intent = Intent(context, LocationAlarmReceiver::class.java).setAction(ACTION)
        return PendingIntent.getBroadcast(
            context,
            42,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}

/**
 * Takes a single balanced-power fix and sends it. Used by the alarm and by the
 * moment sharing is turned on, so the partner does not wait 10 minutes for the
 * first point.
 */
internal object LocationSender {
    private const val LAST_FIX_MAX_AGE_MS = 30L * 60L * 1000L

    suspend fun sendOnce(context: Context) {
        withContext(Dispatchers.IO) {
            if (!Store.isPaired(context) || !Store.shareOn(context) || Store.liveOn(context)) return@withContext
            val (bat, chg) = readBattery(context)
            val fix = readFix(context)
            val now = System.currentTimeMillis()
            if (fix != null) {
                val whenFix = if (fix.time > 0L) fix.time else now
                val acc = if (fix.hasAccuracy()) fix.accuracy else 0f
                // DATA-FLOW: latitude, longitude, accuracy and battery, inside an encrypted "loc" message, to my partner. No wake ping.
                Outbox.send(
                    context,
                    Msg.loc(fix.latitude, fix.longitude, acc, bat, chg, whenFix, live = false),
                    wake = false,
                )
            } else {
                // DATA-FLOW: battery only, inside an encrypted "bat" message, to my partner. Coordinates are not included. No wake ping.
                Outbox.send(context, Msg.bat(bat, chg, now), wake = false)
            }
        }
    }

    /**
     * One current fix, or the last one if it is under 30 minutes old.
     * Older cached fixes are dropped so a stale place is not shown as fresh;
     * the caller sends battery only and the partner keeps the previous point.
     */
    private fun readFix(context: Context): Location? {
        if (!LocationSharing.hasLocationPermission(context)) return null
        val client = LocationServices.getFusedLocationProviderClient(context)
        val cancel = CancellationTokenSource()
        return try {
            val current = try {
                Tasks.await(
                    client.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, cancel.token),
                    8,
                    TimeUnit.SECONDS,
                )
            } catch (_: Exception) {
                null
            }
            if (current != null) return current
            val last = try {
                Tasks.await(client.lastLocation, 3, TimeUnit.SECONDS)
            } catch (_: Exception) {
                null
            } ?: return null
            val age = System.currentTimeMillis() - last.time
            if (last.time <= 0L || age > LAST_FIX_MAX_AGE_MS) null else last
        } catch (_: SecurityException) {
            null
        } finally {
            cancel.cancel()
        }
    }
}

/** Battery percent (0..100, or -1 if unknown) and whether it is charging. No network. */
@SuppressLint("UnspecifiedRegisterReceiverFlag")
internal fun readBattery(context: Context): Pair<Int, Boolean> {
    val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
    val sticky = try {
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(null, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(null, filter)
        }
    } catch (_: Exception) {
        null
    } ?: return -1 to false
    val level = sticky.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
    val scale = sticky.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
    val pct = if (level >= 0 && scale > 0) {
        ((level * 100f) / scale.toFloat()).roundToInt().coerceIn(0, 100)
    } else {
        -1
    }
    val status = sticky.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
    val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
        status == BatteryManager.BATTERY_STATUS_FULL
    return pct to charging
}

/** Great-circle distance in meters. Used to skip tiny live-location jitter. */
internal fun metersBetween(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val r = 6_371_000.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dLat / 2) * sin(dLat / 2) +
        cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
    return 2 * r * atan2(sqrt(a), sqrt(1.0 - a))
}

/**
 * Fires the single sharing fix, then arms the next alarm. Registered in the
 * manifest so it can run after the process has been killed.
 */
class LocationAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val pending = goAsync()
        val app = context.applicationContext
        AppScope.scope.launch {
            try {
                if (Store.isPaired(app) && Store.shareOn(app) && !Store.liveOn(app)) {
                    LocationSender.sendOnce(app)
                }
            } finally {
                if (Store.isPaired(app) && Store.shareOn(app) && !Store.liveOn(app)) {
                    SharingScheduler.schedule(app)
                }
                pending.finish()
            }
        }
    }
}

/**
 * After reboot the in-memory service is gone. If this phone is paired and
 * sharing, arm the slow alarm again. Live mode is not restarted here: starting
 * a location foreground service from boot is blocked on current Android, and
 * [App] retries it when the process comes up in the foreground.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        val app = context.applicationContext
        AppScope.scope.launch {
            try {
                if (!Store.isPaired(app) || !Store.shareOn(app)) return@launch
                if (Store.liveOn(app)) {
                    Store.setLiveOn(app, false)
                }
                SharingScheduler.schedule(app)
            } finally {
                pending.finish()
            }
        }
    }
}
