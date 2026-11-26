package com.nextlayer.betweenly

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Foreground location service for "live" sharing. Updates are requested every
 * 5 seconds at balanced-power accuracy, at least 5 meters apart. A point is
 * sent only when the phone has moved at least 5 meters or 30 seconds have
 * passed, so GPS jitter does not become a message. Each message carries
 * battery and live=true. Stopping sends one last point with live=false.
 *
 * The ongoing notification says only "Live location is on". It never contains
 * coordinates.
 *
 * DATA-FLOW: the messages themselves go out through [Outbox] (encrypted, to the
 * partner's mailbox). Live ticks do not ask the relay to wake the partner.
 */
class LiveLocationService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client by lazy { LocationServices.getFusedLocationProviderClient(this) }
    private var updatesOn = false
    private var stopping = false
    private var lastLat = Double.NaN
    private var lastLon = Double.NaN
    private var lastSentAt = 0L
    private var lastFix: Location? = null

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val loc = result.lastLocation ?: return
            onFix(loc)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_QUIET -> {
                beginStop(sendFinal = false)
                return START_NOT_STICKY
            }
            ACTION_STOP -> {
                Store.setLiveOn(this, false)
                beginStop(sendFinal = true)
                return START_NOT_STICKY
            }
        }
        if (!Store.isPaired(this) || !Store.shareOn(this)) {
            Store.setLiveOn(this, false)
            beginStop(sendFinal = false)
            return START_NOT_STICKY
        }
        if (!enterForeground()) {
            Store.setLiveOn(this, false)
            if (Store.shareOn(this)) SharingScheduler.schedule(this)
            stopSelf()
            return START_NOT_STICKY
        }
        if (cancelStart || !Store.liveOn(this)) {
            Store.setLiveOn(this, false)
            if (Store.shareOn(this)) SharingScheduler.schedule(this)
            beginStop(sendFinal = false)
            return START_NOT_STICKY
        }
        running = true
        SharingScheduler.cancel(this)
        if (cancelStart) {
            running = false
            Store.setLiveOn(this, false)
            if (Store.shareOn(this)) SharingScheduler.schedule(this)
            beginStop(sendFinal = false)
            return START_NOT_STICKY
        }
        Store.setLiveOn(this, true)
        return if (requestUpdates()) START_STICKY else START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        updatesOn = false
        try {
            client.removeLocationUpdates(callback)
        } catch (_: Exception) {
            // Already removed, or Play services is missing.
        }
        if (!stopping) scope.cancel()
    }

    private fun enterForeground(): Boolean {
        return try {
            val manager = getSystemService(NotificationManager::class.java) ?: return false
            if (manager.getNotificationChannel(CHANNEL) == null) {
                val channel = NotificationChannel(
                    CHANNEL,
                    getString(R.string.channel_live),
                    NotificationManager.IMPORTANCE_LOW,
                )
                channel.setSound(null, null)
                channel.description = getString(R.string.live_location_on)
                manager.createNotificationChannel(channel)
            }
            ServiceCompat.startForeground(
                this,
                NOTIF_ID,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
            )
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this,
            4,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, LiveLocationService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_logo)
            .setColor(ContextCompat.getColor(this, R.color.notif_accent))
            .setContentTitle(getString(R.string.live_location_on))
            .setContentText(getString(R.string.live_location_on))
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(R.drawable.ic_stat_logo, getString(R.string.stop), stop)
            .build()
    }

    private fun requestUpdates(): Boolean {
        if (updatesOn) return true
        val allowed = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (!allowed) {
            Store.setLiveOn(this, false)
            if (Store.shareOn(this)) SharingScheduler.schedule(this)
            beginStop(sendFinal = false)
            return false
        }
        val request = LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 5_000L)
            .setMinUpdateIntervalMillis(5_000L)
            .setMinUpdateDistanceMeters(5f)
            .setWaitForAccurateLocation(false)
            .build()
        return try {
            client.requestLocationUpdates(request, callback, Looper.getMainLooper())
            updatesOn = true
            true
        } catch (_: SecurityException) {
            Store.setLiveOn(this, false)
            if (Store.shareOn(this)) SharingScheduler.schedule(this)
            beginStop(sendFinal = false)
            false
        }
    }

    private fun onFix(loc: Location) {
        lastFix = loc
        val now = System.currentTimeMillis()
        if (!shouldSend(loc, now)) return
        lastLat = loc.latitude
        lastLon = loc.longitude
        lastSentAt = now
        val lat = loc.latitude
        val lon = loc.longitude
        val acc = if (loc.hasAccuracy()) loc.accuracy else 0f
        scope.launch {
            val (bat, chg) = readBattery(this@LiveLocationService)
            // DATA-FLOW: live latitude, longitude, accuracy and battery, encrypted "loc", to my partner. No wake ping.
            val sent = Outbox.send(
                applicationContext,
                Msg.loc(lat, lon, acc, bat, chg, System.currentTimeMillis(), live = true),
                wake = false,
            )
            if (sent) BatteryUpdates.markSent(applicationContext, bat, chg)
        }
    }

    private fun shouldSend(loc: Location, now: Long): Boolean {
        if (lastSentAt == 0L || lastLat.isNaN() || lastLon.isNaN()) return true
        val moved = metersBetween(lastLat, lastLon, loc.latitude, loc.longitude)
        return moved >= 5.0 || now - lastSentAt >= 30_000L
    }

    private fun beginStop(sendFinal: Boolean) {
        if (stopping) return
        stopping = true
        running = false
        updatesOn = false
        try {
            client.removeLocationUpdates(callback)
        } catch (_: Exception) {
            // Play services missing, or updates were never requested.
        }
        scope.launch {
            try {
                if (sendFinal && Store.isPaired(applicationContext)) {
                    sendFinalFix()
                }
            } catch (_: Exception) {
                // The partner keeps the previous point.
            } finally {
                withContext(Dispatchers.Main) {
                    try {
                        ServiceCompat.stopForeground(
                            this@LiveLocationService,
                            ServiceCompat.STOP_FOREGROUND_REMOVE,
                        )
                    } catch (_: Exception) {
                        // Not in the foreground.
                    }
                    stopSelf()
                }
            }
        }
    }

    private suspend fun sendFinalFix() {
        val fix = lastFix
        val (bat, chg) = readBattery(this)
        val now = System.currentTimeMillis()
        if (fix != null) {
            val acc = if (fix.hasAccuracy()) fix.accuracy else 0f
            // DATA-FLOW: last latitude and longitude with live=false, encrypted, to my partner, with an empty wake ping so their screen can leave live mode.
            val sent = Outbox.send(
                applicationContext,
                Msg.loc(fix.latitude, fix.longitude, acc, bat, chg, now, live = false),
                wake = true,
            )
            if (sent) BatteryUpdates.markSent(applicationContext, bat, chg)
        } else {
            // DATA-FLOW: battery only, encrypted "bat" message, to my partner, with an empty wake ping. No coordinates (none were taken).
            val sent = Outbox.send(applicationContext, Msg.bat(bat, chg, now), wake = true)
            if (sent) BatteryUpdates.markSent(applicationContext, bat, chg)
        }
    }

    companion object {
        const val ACTION_STOP = "com.nextlayer.betweenly.action.LIVE_STOP"
        const val ACTION_QUIET = "com.nextlayer.betweenly.action.LIVE_QUIET"
        private const val CHANNEL = "live"
        private const val NOTIF_ID = 1001

        @Volatile
        var running: Boolean = false
            private set

        /** Set when stop() wins the race against onStartCommand. */
        @Volatile
        var cancelStart: Boolean = false
            private set

        fun allowStart() {
            cancelStart = false
        }

        fun stop(context: Context, sendFinal: Boolean) {
            cancelStart = true
            if (!running) return
            val action = if (sendFinal) ACTION_STOP else ACTION_QUIET
            try {
                context.startService(Intent(context, LiveLocationService::class.java).setAction(action))
            } catch (_: Exception) {
                running = false
            }
        }
    }
}
