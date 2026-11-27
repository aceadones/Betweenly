package com.nextlayer.betweenly

import android.content.Context
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The shared pet's streak. A day counts when BOTH of you sent at least one
 * chat message that day. Only whole day numbers are kept, never message text.
 *
 * DATA-FLOW: nothing leaves the phone. Two day numbers and a counter are
 * stored in app-private preferences. The partner's day is learned from the
 * messages that already arrive for chat.
 */
object PetStore {
    private const val FILE = "pet_v1"
    private const val MINE = "mine_day"
    private const val THEIRS = "theirs_day"
    private const val STREAK = "streak"
    private const val STREAK_DAY = "streak_day"
    private val lock = Any()
    private val rev = MutableStateFlow(0)

    /** Ticks when the streak changes so screens can reload. */
    val changes: StateFlow<Int> get() = rev

    data class State(
        val streak: Int,
        val mineToday: Boolean,
        val theirsToday: Boolean,
        /** The lost streak that can still be brought back, or 0. */
        val restorable: Int = 0,
    )

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private fun today(): Long = LocalDate.now().toEpochDay()

    /** Call once per stored chat message. [mine] is true for messages this phone sent. */
    fun record(context: Context, mine: Boolean) {
        synchronized(lock) {
            val p = prefs(context)
            val day = today()
            val e = p.edit()
            e.putLong(if (mine) MINE else THEIRS, day)
            val mineDay = if (mine) day else p.getLong(MINE, -1L)
            val theirsDay = if (mine) p.getLong(THEIRS, -1L) else day
            if (mineDay == day && theirsDay == day) {
                val last = p.getLong(STREAK_DAY, -1L)
                if (last != day) {
                    val next = if (last == day - 1) p.getInt(STREAK, 0) + 1 else 1
                    e.putInt(STREAK, next)
                    e.putLong(STREAK_DAY, day)
                }
            }
            e.apply()
        }
        rev.value = rev.value + 1
        PetReminder.schedule(context)
    }

    /** The streak as it stands now. A missed day resets it to 0. */
    fun state(context: Context): State {
        val p = prefs(context)
        val day = today()
        val last = p.getLong(STREAK_DAY, -1L)
        val alive = last >= day - 1
        val lost = if (!alive) p.getInt(STREAK, 0) else 0
        val canRestore = lost > 0 && p.getLong(RESTORE_DAY, -100L) <= day - 7
        return State(
            streak = if (alive) p.getInt(STREAK, 0) else 0,
            mineToday = p.getLong(MINE, -1L) == day,
            theirsToday = p.getLong(THEIRS, -1L) == day,
            restorable = if (canRestore) lost else 0,
        )
    }

    private const val RESTORE_DAY = "restore_day"

    /** Brings back a lost streak. Allowed once every 7 days. It then continues when you both message today. */
    fun restore(context: Context): Boolean {
        val st = state(context)
        if (st.restorable <= 0) return false
        val day = today()
        prefs(context).edit().putLong(STREAK_DAY, day - 1).putLong(RESTORE_DAY, day).apply()
        rev.value = rev.value + 1
        return true
    }

    private const val SIDE = "overlay_side"
    private const val Y_FRAC = "overlay_y"
    private const val SHOW = "overlay_show"

    /** Where the floating pet was left: side (0 left, 1 right) and height as a 0..1 fraction. */
    fun overlayPos(context: Context): Pair<Int, Float> {
        val p = prefs(context)
        return p.getInt(SIDE, 1) to p.getFloat(Y_FRAC, 0.55f)
    }

    fun saveOverlayPos(context: Context, side: Int, yFrac: Float) {
        prefs(context).edit().putInt(SIDE, side).putFloat(Y_FRAC, yFrac).apply()
    }

    fun overlayShown(context: Context): Boolean = prefs(context).getBoolean(SHOW, true)

    fun setOverlayShown(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(SHOW, on).apply()
        rev.value = rev.value + 1
    }

    private const val SKIN = "skin"
    private const val SKIN_TS = "skin_ts"
    private const val OUTFIT = "outfit"

    /** The shared pet's look: skin 0 bunny / 1 kitten, and one accessory id (or -1) per slot. */
    data class Look(val skin: Int, val outfit: List<Int>)

    private fun parseOutfit(text: String?): List<Int> {
        val parts = (text ?: "").split(",").map { it.trim().toIntOrNull() ?: -1 }
        return List(4) { i -> parts.getOrNull(i)?.takeIf { it in -1..8 } ?: -1 }
    }

    fun look(context: Context): Look {
        val p = prefs(context)
        return Look(p.getInt(SKIN, 0).coerceIn(0, 1), parseOutfit(p.getString(OUTFIT, "")))
    }

    fun skin(context: Context): Int = look(context).skin

    /** Saves a new look and returns its timestamp for the partner message. */
    private fun save(context: Context, look: Look): Long {
        val ts = System.currentTimeMillis()
        prefs(context).edit()
            .putInt(SKIN, look.skin)
            .putString(OUTFIT, look.outfit.joinToString(","))
            .putLong(SKIN_TS, ts)
            .apply()
        rev.value = rev.value + 1
        return ts
    }

    /** Picks bunny or kitten. Returns the timestamp to send, or -1 when unchanged. */
    fun chooseSkin(context: Context, skin: Int): Long {
        val cur = look(context)
        if (skin == cur.skin) return -1L
        return save(context, cur.copy(skin = skin.coerceIn(0, 1)))
    }

    /** Puts an accessory in its slot, or takes it off when it is already worn. Returns the timestamp to send. */
    fun toggleItem(context: Context, slot: Int, item: Int): Long {
        val cur = look(context)
        val next = cur.outfit.toMutableList()
        next[slot] = if (next[slot] == item) -1 else item
        return save(context, cur.copy(outfit = next))
    }

    /** The partner's look, applied when it is newer than mine. */
    fun applyRemote(context: Context, skin: Int, outfit: String?, ts: Long) {
        val p = prefs(context)
        if (ts <= p.getLong(SKIN_TS, 0L)) return
        p.edit()
            .putInt(SKIN, skin.coerceIn(0, 1))
            .putString(OUTFIT, parseOutfit(outfit).joinToString(","))
            .putLong(SKIN_TS, ts)
            .apply()
        rev.value = rev.value + 1
    }

    /** The current look as the encrypted partner message. */
    fun message(context: Context, ts: Long): org.json.JSONObject {
        val l = look(context)
        return Msg.pet(l.skin, l.outfit.joinToString(","), ts)
    }

    /** Clears the pet when the pair is removed. */
    fun reset(context: Context) {
        val keepShow = overlayShown(context)
        prefs(context).edit().clear().putBoolean(SHOW, keepShow).apply()
        rev.value = rev.value + 1
    }
}
