package com.nextlayer.betweenly

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * The line Duet last reported for the Activity card.
 *
 * Memory plus the private prefs file `duet`. A line with no update for
 * [STALE_MS] is treated as idle. [at] is this phone's clock when the update
 * arrived, so a skewed clock inside Duet cannot pin the card.
 *
 * DATA-FLOW: none. Title and artist stay on this phone. They are not a key,
 * and this object does not write them to a log or to the relay.
 */
object DuetState {
    const val STALE_MS = 60_000L

    private const val PREFS = "duet"
    private const val TITLE = "title"
    private const val ARTIST = "artist"
    private const val PLAYING = "playing"
    private const val AT = "at"

    data class Now(
        val title: String,
        val artist: String,
        val playing: Boolean,
        val at: Long,
    )

    private val gate = Any()
    private var mem: Now? = null
    private var loaded = false
    private val written = MutableStateFlow(0)

    /** Bumps when Duet reports a new line. Collect it. Do not poll. */
    val changes: StateFlow<Int> = written

    fun update(context: Context, title: String, artist: String, playing: Boolean) {
        val now = Now(
            title = clip(title, 120),
            artist = clip(artist, 80),
            playing = playing,
            at = System.currentTimeMillis(),
        )
        val app = context.applicationContext
        try {
            synchronized(gate) {
                mem = now
                loaded = true
                app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .putString(TITLE, now.title)
                    .putString(ARTIST, now.artist)
                    .putBoolean(PLAYING, now.playing)
                    .putLong(AT, now.at)
                    .commit()
            }
        } catch (_: Exception) {
            return
        }
        written.update { it + 1 }
    }

    /** Null when Duet has not reported a line, or the last report is older than [STALE_MS]. */
    fun fresh(context: Context, nowMs: Long = System.currentTimeMillis()): Now? {
        val snap = load(context) ?: return null
        val age = nowMs - snap.at
        if (age < 0L || age > STALE_MS) return null
        return snap
    }

    private fun load(context: Context): Now? {
        synchronized(gate) {
            if (loaded) return mem
            loaded = true
            mem = try {
                val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                if (!prefs.contains(AT)) {
                    null
                } else {
                    Now(
                        title = prefs.getString(TITLE, "") ?: "",
                        artist = prefs.getString(ARTIST, "") ?: "",
                        playing = prefs.getBoolean(PLAYING, false),
                        at = prefs.getLong(AT, 0L),
                    )
                }
            } catch (_: Exception) {
                null
            }
            return mem
        }
    }

    private fun clip(raw: String, max: Int): String =
        raw.replace('\n', ' ').replace('\r', ' ').filter { it >= ' ' }.trim().take(max)
}
