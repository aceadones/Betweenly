package app.loviedowpie

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import kotlin.coroutines.cancellation.CancellationException

/**
 * The one chat background both people share. The newest timestamp wins.
 * A photo is an encrypted file plus a sealed pointer. A color or the default
 * is only the sealed pointer.
 *
 * DATA-FLOW: network happens in [publish] and [downloadImage]. The JPEG and
 * the kind, color index, and timestamp also stay in app-private storage.
 */
data class SharedWall(
    val kind: String,
    val color: Int,
    val ts: Long,
    val revision: Int,
)

object ChatWallpaperSync {
    private const val PREFS = "app"
    private const val KIND = "chat_wall_kind"
    private const val COLOR = "chat_wall_color"
    private const val TS = "chat_wall_ts"
    private const val P_TS = "chat_wall_pend_ts"
    private const val P_FID = "chat_wall_pend_fid"
    private const val P_KEY = "chat_wall_pend_key"

    private val lock = Any()
    private var ready = false
    private var rev = 0
    private val wallMut = MutableStateFlow(SharedWall("default", 0, 0L, 0))

    /** Collect this. Call [refresh] once when the chat screen appears. */
    val wall: StateFlow<SharedWall> = wallMut

    fun refresh(context: Context) {
        synchronized(lock) {
            val loaded = read(context)
            val cur = wallMut.value
            if (!ready || cur.kind != loaded.kind || cur.color != loaded.color || cur.ts != loaded.ts) {
                rev += 1
                wallMut.value = loaded.copy(revision = rev)
            }
            ready = true
        }
    }

    /** Partner's wall message. Image bytes are fetched off the session lock. */
    fun onIncoming(context: Context, json: JSONObject) {
        try {
            val kind = json.optString("kind")
            if (kind != "default" && kind != "color" && kind != "image") return
            val ts = json.optLong("ts", 0L)
            if (ts <= 0L) return
            val color = json.optInt("color", 0).let { if (it in 0..5) it else 0 }
            refresh(context)
            if (ts <= currentTs()) return
            val app = context.applicationContext
            if (kind == "image") {
                val fid = json.optString("fid").trim().lowercase()
                val key = json.optString("key")
                if (!hex32(fid) || key.isEmpty()) return
                writePending(app, ts, fid, key)
                AppScope.scope.launch {
                    try {
                        downloadImage(app, ts, fid, key)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        // Pending stays so the next chat open can try again.
                    }
                }
            } else {
                clearPending(app)
                val name = Store.partnerName(app).ifBlank { app.getString(R.string.partner_fallback) }
                if (commit(app, kind, color, ts, name)) ChatStore.deleteWallpaper(app)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // A bad wallpaper must not drop the rest of the mailbox.
        }
    }

    /** Tries a wallpaper photo that did not finish downloading. */
    suspend fun retryPending(context: Context) {
        val app = context.applicationContext
        val pending = synchronized(lock) { pendingOf(app) } ?: return
        if (pending.ts <= currentTs()) {
            clearPending(app)
            return
        }
        try {
            downloadImage(app, pending.ts, pending.fid, pending.key)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Leave the pending id for the next open.
        }
    }

    suspend fun publishDefault(context: Context): Boolean = publish(context, "default", 0, null)

    suspend fun publishColor(context: Context, index: Int): Boolean {
        if (index !in 0..5) return false
        return publish(context, "color", index, null)
    }

    suspend fun publishImage(context: Context, jpeg: ByteArray): Boolean {
        if (jpeg.isEmpty() || jpeg.size > 3_000_000) return false
        return publish(context, "image", 0, jpeg)
    }

    private suspend fun publish(context: Context, kind: String, color: Int, jpeg: ByteArray?): Boolean {
        if (!Store.isPaired(context)) return false
        val app = context.applicationContext
        refresh(app)
        val ts = nextTs()
        var fid = ""
        var key = ""
        if (kind == "image") {
            val bytes = jpeg ?: return false
            // DATA-FLOW: wallpaper JPEG, encrypted by FileTransfer.upload, PUT to relay /file/{fid}. The key is not in this request. The relay stores ciphertext for 7 days.
            val uploaded = try {
                FileTransfer.upload(app, bytes)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            } ?: return false
            fid = uploaded.first
            key = uploaded.second
        }
        val json = JSONObject()
            .put("t", "wall")
            .put("kind", kind)
            .put("color", color)
            .put("fid", fid)
            .put("key", key)
            .put("ts", ts)
        if (Msg.pad(json) == null) return false
        val sent = try {
            // DATA-FLOW: shared wallpaper (kind, color index, timestamp, and for a photo the file id plus key — not the image bytes), padded and sealed, posted to my relay outbox. wake is off, so no FCM ping. The relay stores ciphertext only.
            Outbox.send(app, json, wake = false)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
        if (!sent) return false
        if (kind == "image") {
            val bytes = jpeg ?: return false
            if (!ChatStore.saveWallpaper(app, bytes)) return false
        }
        clearPending(app)
        val name = Store.myName(app).ifBlank { app.getString(R.string.chat_wall_you) }
        val applied = commit(app, kind, color, ts, name)
        if (applied && kind != "image") ChatStore.deleteWallpaper(app)
        return applied
    }

    private suspend fun downloadImage(context: Context, ts: Long, fid: String, key: String) {
        if (ts < currentTs()) return
        // DATA-FLOW: wallpaper ciphertext, GET relay /file/{fid}, decrypted on this phone. The key arrived inside the sealed wall message. FileTransfer retries for about a minute.
        val bytes = try {
            FileTransfer.download(context, fid, key)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } ?: return
        if (bytes.isEmpty() || ts < currentTs()) return
        val newerPending = synchronized(lock) {
            val pending = pendingOf(context)
            pending != null && pending.ts > ts
        }
        if (newerPending) return
        if (!ChatStore.saveWallpaper(context, bytes)) return
        if (ts < currentTs()) return
        clearPending(context)
        val name = Store.partnerName(context).ifBlank { context.getString(R.string.partner_fallback) }
        commit(context, "image", 0, ts, name)
    }

    private fun commit(context: Context, kind: String, color: Int, ts: Long, name: String): Boolean {
        val writeNote = synchronized(lock) {
            val prev = if (ready) wallMut.value.ts else read(context).ts
            if (ts < prev) return false
            val cur = wallMut.value
            if (ready && cur.kind == kind && cur.color == color && cur.ts == ts) return true
            try {
                prefs(context).edit()
                    .putString(KIND, kind)
                    .putInt(COLOR, if (color in 0..5) color else 0)
                    .putLong(TS, ts)
                    .commit()
            } catch (_: Exception) {
                return false
            }
            rev += 1
            wallMut.value = SharedWall(kind, if (color in 0..5) color else 0, ts, rev)
            ready = true
            true
        }
        if (!writeNote) return false
        val line = try {
            context.getString(R.string.chat_wall_changed, name)
        } catch (_: Exception) {
            ""
        }
        if (line.isNotEmpty()) ChatStore.insertNote(context, line)
        return true
    }

    private fun nextTs(): Long {
        val now = System.currentTimeMillis()
        val prev = currentTs()
        return if (now > prev) now else prev + 1
    }

    private fun currentTs(): Long = synchronized(lock) {
        if (ready) wallMut.value.ts else 0L
    }

    private class Pending(val ts: Long, val fid: String, val key: String)

    private fun pendingOf(context: Context): Pending? {
        val p = prefs(context)
        val ts = p.getLong(P_TS, 0L)
        val fid = p.getString(P_FID, "").orEmpty()
        val key = p.getString(P_KEY, "").orEmpty()
        if (ts <= 0L || !hex32(fid) || key.isEmpty()) return null
        return Pending(ts, fid, key)
    }

    private fun writePending(context: Context, ts: Long, fid: String, key: String) {
        try {
            prefs(context).edit()
                .putLong(P_TS, ts)
                .putString(P_FID, fid)
                .putString(P_KEY, key)
                .commit()
        } catch (_: Exception) {
            // The download still runs in this process.
        }
    }

    private fun clearPending(context: Context) {
        try {
            prefs(context).edit().remove(P_TS).remove(P_FID).remove(P_KEY).commit()
        } catch (_: Exception) {
            // A leftover pending download is ignored once a newer wall is stored.
        }
    }

    private fun read(context: Context): SharedWall {
        val p = prefs(context)
        if (p.contains(KIND) || p.getLong(TS, 0L) > 0L) {
            val kind = p.getString(KIND, "default").orEmpty()
            val color = p.getInt(COLOR, 0).let { if (it in 0..5) it else 0 }
            val ts = p.getLong(TS, 0L)
            val safe = if (kind == "color" || kind == "image" || kind == "default") kind else "default"
            return SharedWall(safe, color, ts, 0)
        }
        val legacy = try {
            Store.chatWallpaper(context)
        } catch (_: Exception) {
            "default"
        }
        return when {
            legacy == "image" -> SharedWall("image", 0, 0L, 0)
            legacy.startsWith("color:") -> {
                val index = legacy.removePrefix("color:").toIntOrNull()
                if (index != null && index in 0..5) SharedWall("color", index, 0L, 0) else SharedWall("default", 0, 0L, 0)
            }
            else -> SharedWall("default", 0, 0L, 0)
        }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun hex32(value: String): Boolean =
        value.length == 32 && value.all { it in '0'..'9' || it in 'a'..'f' }
}
