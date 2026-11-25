package app.loviedowpie

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicInteger

/**
 * The partner's last known place and phone status, as we last decrypted it.
 *
 * [lat]/[lon]/[acc] are null when the partner has sent battery only (location
 * was off) so the previous point is kept. [ts] is their clock. [lastSeen] is
 * our clock at the moment we accepted the message. [live] is true while they
 * are in live sharing.
 */
data class PartnerLoc(
    val lat: Double?,
    val lon: Double?,
    val acc: Float?,
    val bat: Int,
    val chg: Boolean,
    val ts: Long,
    val live: Boolean,
    val lastSeen: Long,
)

/**
 * Local app state. The session blob is Keystore-wrapped via [Secure]; everything
 * else is private SharedPreferences ("app"), not backed up (see the manifest).
 *
 * Pair-related writes no-op once the session is gone, so an in-flight location
 * update cannot write the partner's place back after [unpair].
 *
 * UI: collect [changes], then read the getters again. The integer is only a
 * signal that something was written.
 *
 * DATA-FLOW: this object never talks to the network. Names, avatars and the
 * partner's last location sit in app-private storage on this phone.
 */
object Store {
    private const val PREFS = "app"
    private const val SESSION = "session"
    private const val MY_NAME = "my_name"
    private const val MY_AVATAR = "my_avatar"
    private const val PARTNER_NAME = "partner_name"
    private const val PARTNER_AVATAR = "partner_avatar"
    private const val PARTNER_PROFILE = "partner_profile"
    private const val HAS_LOC = "partner_has_loc"
    private const val LAT = "partner_lat"
    private const val LON = "partner_lon"
    private const val ACC = "partner_acc"
    private const val HAS_STATUS = "partner_has_status"
    private const val BAT = "partner_bat"
    private const val CHG = "partner_chg"
    private const val TS = "partner_ts"
    private const val LIVE = "partner_live"
    private const val SEEN = "partner_last_seen"
    private const val PARTNER_SHARING = "partner_sharing"
    private const val SHARE = "share_on"
    private const val LIVE_ON = "live_on"
    private const val UPDATE = "last_update_check"
    private const val FCM = "fcm_token"
    private const val CHAT_WALLPAPER = "chat_wallpaper"
    private const val CHAT_AUTOSAVE = "chat_autosave"
    private const val NOTIFY_MSG = "notify_msg"
    private const val NOTIFY_DRAW = "notify_draw"
    private const val ANIM = "animations_enabled"

    /** Avatars larger than this are refused. The wire format cannot carry more. */
    const val MAX_AVATAR_BYTES = 48 * 1024

    private val lock = Any()
    private val pairGeneration = AtomicInteger(0)
    private val written = MutableStateFlow(0)
    private val animationsState = MutableStateFlow(true)
    @Volatile private var animationsLoaded = false

    /**
     * Increments on every successful write. Collect it from Compose; do not write it.
     */
    val changes: StateFlow<Int> = written

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun bump() {
        written.update { it + 1 }
    }

    /** Captured by in-flight seal operations so they cannot save a session after [unpair]. */
    internal fun generation(): Int = synchronized(lock) { pairGeneration.get() }

    fun isPaired(context: Context): Boolean = session(context) != null

    fun session(context: Context): ByteArray? = synchronized(lock) { readSession(context) }

    fun setSession(context: Context, session: ByteArray) {
        synchronized(lock) {
            if (session.size != Core.SESSION_LEN) return
            writeSession(context, session)
        }
    }

    /**
     * Writes [session] only if [unpair] has not run since [expectedGeneration]
     * was read. Returns false when the write did not stick.
     */
    internal fun setSessionIf(context: Context, session: ByteArray, expectedGeneration: Int): Boolean {
        synchronized(lock) {
            if (pairGeneration.get() != expectedGeneration) return false
            if (session.size != Core.SESSION_LEN) return false
            return writeSession(context, session)
        }
    }

    private fun readSession(context: Context): ByteArray? {
        val raw = Secure.get(context, SESSION) ?: return null
        return if (raw.size == Core.SESSION_LEN) raw else null
    }

    private fun writeSession(context: Context, session: ByteArray): Boolean {
        Secure.put(context, SESSION, session.copyOf())
        val stored = Secure.get(context, SESSION)
        val ok = stored != null && stored.contentEquals(session)
        if (ok) bump()
        return ok
    }

    /** Safety number ("123 456") both people can compare aloud, or "" if unpaired. */
    fun safetyNumber(context: Context): String = info(context, 0)

    /** Relay mailbox I read from. 32 hex chars, or "". */
    fun inboxId(context: Context): String = info(context, 1)

    /** Relay mailbox I write to (my partner's inbox). 32 hex chars, or "". */
    fun outboxId(context: Context): String = info(context, 2)

    private fun info(context: Context, which: Long): String {
        val current = session(context) ?: return ""
        return try {
            Core.sessionInfo(current, which)
        } catch (_: Exception) {
            ""
        }
    }

    fun myName(context: Context): String = prefs(context).getString(MY_NAME, "") ?: ""

    fun setMyName(context: Context, name: String) {
        val clean = name.replace("\n", " ").replace("\r", " ").take(80)
        synchronized(lock) {
            if (clean == (prefs(context).getString(MY_NAME, "") ?: "")) return
            prefs(context).edit().putString(MY_NAME, clean).commit()
            bump()
        }
    }

    fun myAvatar(context: Context): ByteArray? = readAvatar(context, MY_AVATAR)

    fun setMyAvatar(context: Context, jpeg: ByteArray?) {
        writeAvatar(context, MY_AVATAR, jpeg, pairState = false)
    }

    fun partnerName(context: Context): String = prefs(context).getString(PARTNER_NAME, "") ?: ""

    /** True after at least one profile message has been decrypted. */
    fun partnerProfileKnown(context: Context): Boolean = prefs(context).getBoolean(PARTNER_PROFILE, false)

    fun setPartnerName(context: Context, name: String) {
        val clean = name.replace("\n", " ").replace("\r", " ").take(80)
        editPair(context) { putString(PARTNER_NAME, clean); putBoolean(PARTNER_PROFILE, true) }
    }

    fun partnerAvatar(context: Context): ByteArray? = readAvatar(context, PARTNER_AVATAR)

    fun setPartnerAvatar(context: Context, jpeg: ByteArray?) {
        writeAvatar(context, PARTNER_AVATAR, jpeg, pairState = true)
    }

    fun partnerLoc(context: Context): PartnerLoc? = synchronized(lock) {
        val p = prefs(context)
        if (!p.getBoolean(HAS_STATUS, false) && !p.getBoolean(HAS_LOC, false)) {
            null
        } else {
            val hasLoc = p.getBoolean(HAS_LOC, false)
            PartnerLoc(
                lat = if (hasLoc) Double.fromBits(p.getLong(LAT, 0L)) else null,
                lon = if (hasLoc) Double.fromBits(p.getLong(LON, 0L)) else null,
                acc = if (hasLoc) p.getFloat(ACC, 0f) else null,
                bat = p.getInt(BAT, -1),
                chg = p.getBoolean(CHG, false),
                ts = p.getLong(TS, 0L),
                live = p.getBoolean(LIVE, false),
                lastSeen = p.getLong(SEEN, 0L),
            )
        }
    }

    fun setPartnerLoc(context: Context, loc: PartnerLoc) {
        editPair(context) {
            if (loc.lat != null && loc.lon != null) {
                putBoolean(HAS_LOC, true)
                putLong(LAT, loc.lat.toRawBits())
                putLong(LON, loc.lon.toRawBits())
                putFloat(ACC, loc.acc ?: 0f)
            }
            putBoolean(HAS_STATUS, true)
            putInt(BAT, loc.bat)
            putBoolean(CHG, loc.chg)
            putLong(TS, loc.ts)
            putBoolean(LIVE, loc.live)
            putLong(SEEN, loc.lastSeen)
        }
    }

    /** Partner's own sharing switch, from their last "share" message (and set true when a location arrives). */
    fun partnerSharing(context: Context): Boolean = prefs(context).getBoolean(PARTNER_SHARING, false)

    fun setPartnerSharing(context: Context, on: Boolean) {
        editPair(context) { putBoolean(PARTNER_SHARING, on) }
    }

    /** This phone's sharing switch. Off until the user turns it on. */
    fun shareOn(context: Context): Boolean = prefs(context).getBoolean(SHARE, false)

    fun setShareOn(context: Context, on: Boolean) {
        synchronized(lock) {
            prefs(context).edit().putBoolean(SHARE, on).commit()
            bump()
        }
    }

    /** This phone's live-sharing switch. The foreground service is the thing that actually runs. */
    fun liveOn(context: Context): Boolean = prefs(context).getBoolean(LIVE_ON, false)

    fun setLiveOn(context: Context, on: Boolean) {
        synchronized(lock) {
            prefs(context).edit().putBoolean(LIVE_ON, on).commit()
            bump()
        }
    }

    fun lastUpdateCheck(context: Context): Long = prefs(context).getLong(UPDATE, 0L)

    fun setLastUpdateCheck(context: Context, epochMillis: Long) {
        synchronized(lock) {
            prefs(context).edit().putLong(UPDATE, epochMillis).commit()
            bump()
        }
    }

    fun fcmToken(context: Context): String = prefs(context).getString(FCM, "") ?: ""

    fun setFcmToken(context: Context, token: String) {
        val clean = token.trim()
        synchronized(lock) {
            if (clean == (prefs(context).getString(FCM, "") ?: "")) return
            prefs(context).edit().putString(FCM, clean).commit()
            bump()
        }
    }

    /**
     * Chat wallpaper. "default", "image" (file from [ChatStore.saveWallpaper]),
     * or "color:0" … "color:5". Anything else reads back as "default".
     */
    fun chatWallpaper(context: Context): String {
        val value = prefs(context).getString(CHAT_WALLPAPER, "default") ?: "default"
        return if (wallpaperOk(value)) value else "default"
    }

    fun setChatWallpaper(context: Context, value: String) {
        if (!wallpaperOk(value)) return
        synchronized(lock) {
            if (value == chatWallpaper(context)) return
            prefs(context).edit().putString(CHAT_WALLPAPER, value).commit()
            bump()
        }
    }

    /** New-message notices. Default on. The notice text is only "New message". */
    fun notifyMessages(context: Context): Boolean = prefs(context).getBoolean(NOTIFY_MSG, true)

    fun setNotifyMessages(context: Context, on: Boolean) {
        synchronized(lock) {
            if (on == prefs(context).getBoolean(NOTIFY_MSG, true)) return
            prefs(context).edit().putBoolean(NOTIFY_MSG, on).commit()
            bump()
        }
    }

    /** New-drawing notices. Default on. The notice text is only "New drawing". */
    fun notifyDrawings(context: Context): Boolean = prefs(context).getBoolean(NOTIFY_DRAW, true)

    fun setNotifyDrawings(context: Context, on: Boolean) {
        synchronized(lock) {
            if (on == prefs(context).getBoolean(NOTIFY_DRAW, true)) return
            prefs(context).edit().putBoolean(NOTIFY_DRAW, on).commit()
            bump()
        }
    }

    /**
     * Motion on this phone. Default on. Profile's "Reduce animations" switch
     * stores the opposite. Survives unpair, like the notification switches.
     * DATA-FLOW: this phone only. Never sent.
     */
    fun animationsFlow(context: Context): StateFlow<Boolean> {
        if (!animationsLoaded) {
            synchronized(lock) {
                if (!animationsLoaded) {
                    animationsState.value = prefs(context).getBoolean(ANIM, true)
                    animationsLoaded = true
                }
            }
        }
        return animationsState
    }

    fun animationsEnabled(context: Context): Boolean = animationsFlow(context).value

    fun setAnimationsEnabled(context: Context, on: Boolean) {
        synchronized(lock) {
            animationsLoaded = true
            if (on == prefs(context).getBoolean(ANIM, true)) {
                animationsState.value = on
                return
            }
            prefs(context).edit().putBoolean(ANIM, on).commit()
            animationsState.value = on
            bump()
        }
    }

    /** When true, photos and videos (not stickers, GIFs, or files) are copied to the gallery. Default off. */
    fun chatAutoSaveMedia(context: Context): Boolean = prefs(context).getBoolean(CHAT_AUTOSAVE, false)

    fun setChatAutoSaveMedia(context: Context, on: Boolean) {
        synchronized(lock) {
            if (on == prefs(context).getBoolean(CHAT_AUTOSAVE, false)) return
            prefs(context).edit().putBoolean(CHAT_AUTOSAVE, on).commit()
            bump()
        }
    }

    private fun wallpaperOk(value: String): Boolean {
        if (value == "default" || value == "image") return true
        if (!value.startsWith("color:")) return false
        val index = value.removePrefix("color:").toIntOrNull() ?: return false
        return index in 0..5
    }

    /**
     * Ends the pair. Deletes the session, the chat history, attachment files,
     * and everything learned about the partner, stops sharing and ringing, and
     * cancels the location alarm.
     *
     * Kept on purpose: this phone's own name, avatar, FCM token, the last
     * update-check time, the notification switches, and the animations
     * preference. Those are not the relationship. The FCM token is also still
     * registered with Google; wiping the local copy would not revoke it.
     */
    fun unpair(context: Context) {
        val app = context.applicationContext
        synchronized(lock) {
            pairGeneration.incrementAndGet()
            SharingScheduler.cancel(app)
            val name = prefs(app).getString(MY_NAME, "") ?: ""
            val avatar = readAvatarLocked(prefs(app), MY_AVATAR)
            val token = prefs(app).getString(FCM, "") ?: ""
            val checked = prefs(app).getLong(UPDATE, 0L)
            val notifyMsg = prefs(app).getBoolean(NOTIFY_MSG, true)
            val notifyDraw = prefs(app).getBoolean(NOTIFY_DRAW, true)
            val animations = prefs(app).getBoolean(ANIM, true)
            Secure.remove(app, SESSION)
            prefs(app).edit().clear().commit()
            val editor = prefs(app).edit()
            editor.putString(MY_NAME, name)
            editor.putString(FCM, token)
            editor.putLong(UPDATE, checked)
            editor.putBoolean(NOTIFY_MSG, notifyMsg)
            editor.putBoolean(NOTIFY_DRAW, notifyDraw)
            editor.putBoolean(ANIM, animations)
            if (avatar != null) {
                editor.putString(MY_AVATAR, Base64.encodeToString(avatar, Base64.NO_WRAP))
            }
            editor.commit()
            // Chat database, encrypted attachments, wallpaper file, and the open-file cache.
            ChatStore.wipe(app)
            // Partner drawings and saved drawings. DrawStore does not take this lock.
            DrawStore.wipe(app)
            bump()
        }
        LiveLocationService.stop(app, sendFinal = false)
        RingService.stop(app)
    }

    private fun editPair(context: Context, block: SharedPreferences.Editor.() -> Unit) {
        synchronized(lock) {
            if (readSession(context) == null) return
            val editor = prefs(context).edit()
            editor.block()
            if (readSession(context) == null) return
            editor.commit()
            bump()
        }
    }

    private fun readAvatar(context: Context, key: String): ByteArray? =
        readAvatarLocked(prefs(context), key)

    private fun readAvatarLocked(p: SharedPreferences, key: String): ByteArray? {
        val encoded = p.getString(key, null) ?: return null
        return try {
            val bytes = Base64.decode(encoded, Base64.NO_WRAP)
            if (bytes.isEmpty() || bytes.size > MAX_AVATAR_BYTES) null else bytes
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun writeAvatar(context: Context, key: String, jpeg: ByteArray?, pairState: Boolean) {
        if (jpeg != null && jpeg.size > MAX_AVATAR_BYTES) return
        val write = {
            val editor = prefs(context).edit()
            if (jpeg == null || jpeg.isEmpty()) editor.remove(key) else {
                editor.putString(key, Base64.encodeToString(jpeg, Base64.NO_WRAP))
            }
            editor.commit()
            bump()
        }
        synchronized(lock) {
            if (pairState && readSession(context) == null) return
            write()
        }
    }
}
