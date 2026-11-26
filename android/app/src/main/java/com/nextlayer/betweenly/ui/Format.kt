package com.nextlayer.betweenly.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.nextlayer.betweenly.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * "12 min ago" and the battery line. Formatting only.
 * DATA-FLOW: none. The timestamp is already on this phone.
 */
fun lastSeenPhrase(context: Context, epochMillis: Long, now: Long = System.currentTimeMillis()): String {
    if (epochMillis <= 0L) return context.getString(R.string.no_update_yet)
    val minutes = ((now - epochMillis).coerceAtLeast(0L) / 60_000L).toInt()
    val week = 60 * 24 * 7
    return when {
        minutes < 1 -> context.getString(R.string.seen_just_now)
        minutes < 60 -> context.getString(R.string.seen_min, minutes)
        minutes < 60 * 24 -> context.getString(R.string.seen_hour, minutes / 60)
        minutes < week -> context.getString(R.string.seen_day, minutes / (60 * 24))
        else -> DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
            .withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochMilli(epochMillis))
    }
}

/**
 * When the battery value was accepted. [batSeen] is that clock. Older saves
 * only have [lastSeen], which included the battery on a location message.
 */
fun batterySeenAt(bat: Int, batSeen: Long, lastSeen: Long): Long {
    if (bat !in 0..100) return 0L
    return if (batSeen > 0L) batSeen else lastSeen
}

/** True once the battery reading is an hour old or more. */
fun batteryIsStale(seenAt: Long, now: Long): Boolean =
    seenAt > 0L && now - seenAt >= 60L * 60L * 1000L

/** Battery percent, with the word "charging" when the partner's phone is charging. */
fun batteryPhrase(context: Context, percent: Int, charging: Boolean): String {
    if (percent !in 0..100) return context.getString(R.string.battery_unknown)
    return if (charging) {
        context.getString(R.string.battery_charging, percent)
    } else {
        context.getString(R.string.battery_pct, percent)
    }
}

/** "± 25 m". Hidden by the caller when accuracy is missing. */
fun accuracyPhrase(context: Context, meters: Float): String {
    val rounded = meters.toInt().coerceAtLeast(0)
    return context.getString(R.string.accuracy_m, rounded)
}

/** First letter of a name, for the round placeholder when there is no photo. */
fun initialOf(name: String): String {
    val ch = name.trim().firstOrNull() ?: return "?"
    return ch.uppercaseChar().toString()
}

/**
 * Current time, refreshed twice a minute, so "Just now" becomes "1 min ago"
 * while a screen is open. DATA-FLOW: none.
 */
@Composable
fun rememberNow(periodMs: Long = 30_000L): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(periodMs) {
        while (isActive) {
            delay(periodMs)
            now = System.currentTimeMillis()
        }
    }
    return now
}
