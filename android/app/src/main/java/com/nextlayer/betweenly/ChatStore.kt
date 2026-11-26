package com.nextlayer.betweenly

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException

/**
 * One stored chat message. [json] is the plaintext payload (already decrypted).
 * Local-only keys `rx` (partner reaction emoji), `mx` (this phone's reaction),
 * and `gal` (1 after a gallery copy) are stripped before anything is sent.
 * A tombstone is `t` = `gone`. A centered system line is `t` = `note`.
 */
class ChatMessage(
    val id: String,
    val ts: Long,
    val outgoing: Boolean,
    val status: String,
    val json: JSONObject,
)

/**
 * Chat history. One SQLite table in app-private storage. Each row is an id,
 * a timestamp, a direction (`in` or `out`), a status
 * (`sending`, `sent`, `delivered`, `read`, `failed`, `received`), and the message JSON sealed with
 * [Secure.encrypt]. The database file by itself is not readable.
 *
 * Attachment bytes are sealed the same way and stored at
 * `filesDir/attachments/{id}.bin` (Keystore AES-GCM: 12-byte IV, then
 * ciphertext). Plaintext is only in memory while a message is sent, shown, or
 * saved, plus a short-lived file in `cacheDir/chat-open` when another app is
 * handed a video or a file through FileProvider. [wipe] deletes the database,
 * the attachments, the wallpaper image, and that cache.
 *
 * A reaction that arrives before its message is a row id `rx` + the message id.
 * [list] does not return those rows; it copies the emoji onto the message.
 *
 * UI: collect [changes], then read again. The integer is only a signal.
 * DATA-FLOW: this object never talks to the network.
 */
object ChatStore {
    const val SENDING = "sending"
    const val SENT = "sent"
    const val DELIVERED = "delivered"
    const val READ = "read"
    const val FAILED = "failed"
    const val RECEIVED = "received"

    /** Local clock when the partner's last chat message was stored. Not sent. */
    @Volatile
    var partnerHeardAt: Long = 0L
        private set
    const val DIR_IN = "in"
    const val DIR_OUT = "out"

    private const val DB_NAME = "chat.db"
    private const val TABLE = "messages"

    private val lock = Any()
    private val epoch = AtomicInteger(0)
    private val written = MutableStateFlow(0)
    private val failedMut = MutableStateFlow<Set<String>>(emptySet())
    private val gate = Mutex()
    private var helper: Helper? = null

    /** Increments on every successful write. Collect it; do not write it. */
    val changes: StateFlow<Int> = written

    /** Message ids whose attachment download failed and can be retried. */
    val failedDownloads: StateFlow<Set<String>> = failedMut

    private const val PREFS = "app"
    private const val UNREAD = "chat_unread"
    /** Partner unsends that arrived before the message. Cleared on wipe. */
    private const val EARLY = "chat_unsend_early"
    /** Newest rows kept in memory. Older rows stay in the database. */
    private const val MAX_LIST = 500
    private val unreadLock = Any()
    private val unreadMut = MutableStateFlow(0)
    private var earlyMem: MutableList<String>? = null

    /**
     * Messages received since the Chat tab was last open. Persisted in the
     * app prefs so a process restart keeps the badge. Collect this; do not write it.
     */
    val unreadCount: StateFlow<Int> = unreadMut

    fun newId(): String = randomHex(8)

    /** Reads the saved unread count into [unreadCount]. Safe to call more than once. */
    fun loadUnread(context: Context) {
        synchronized(unreadLock) {
            unreadMut.value = readUnread(context)
        }
    }

    /** Clears the badge. Called when the Chat tab is on screen. */
    fun markRead(context: Context) {
        synchronized(unreadLock) {
            writeUnread(context, 0)
            unreadMut.value = 0
        }
    }

    /**
     * One new partner message while Chat is not open. A reaction is not a
     * message. If Chat opened before this lock was taken, the count stays put.
     */
    fun noteIncoming(context: Context) {
        synchronized(unreadLock) {
            if (AppState.chatOpen) return
            val next = (readUnread(context) + 1).coerceAtMost(999)
            writeUnread(context, next)
            unreadMut.value = next
        }
    }

    private fun appPrefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun readUnread(context: Context): Int = try {
        appPrefs(context).getInt(UNREAD, 0).coerceIn(0, 999)
    } catch (_: Exception) {
        0
    }

    private fun writeUnread(context: Context, count: Int) {
        try {
            appPrefs(context).edit().putInt(UNREAD, count.coerceIn(0, 999)).commit()
        } catch (_: Exception) {
            // The in-memory count still updates so this process shows the badge.
        }
    }

    fun normalizeId(id: String): String? {
        val clean = id.trim().lowercase()
        if (clean.length != 16) return null
        if (!clean.all { it in '0'..'9' || it in 'a'..'f' }) return null
        return clean
    }

    fun list(context: Context): List<ChatMessage> {
        val e = epoch.get()
        if (!Store.isPaired(context)) return emptyList()
        synchronized(lock) {
            if (epoch.get() != e) return emptyList()
            val database = db(context) ?: return emptyList()
            return try {
                val pending = HashMap<String, String>()
                val out = ArrayList<ChatMessage>()
                database.query(TABLE, null, "id LIKE 'rx%'", null, null, null, "ts DESC", "80").use { cursor ->
                    while (cursor.moveToNext()) {
                        val blob = cursor.blob("body") ?: continue
                        val json = openBody(blob) ?: continue
                        val target = normalizeId(json.optString("id")) ?: continue
                        val emoji = json.optString("emoji")
                        if (emoji.isNotEmpty()) pending[target] = emoji
                    }
                }
                database.query(
                    TABLE,
                    null,
                    "id NOT LIKE 'rx%'",
                    null,
                    null,
                    null,
                    "ts DESC, rowid DESC",
                    MAX_LIST.toString(),
                ).use { cursor ->
                    while (cursor.moveToNext()) {
                        val blob = cursor.blob("body") ?: continue
                        val json = openBody(blob) ?: continue
                        if (json.optString("t") == "react") continue
                        val message = messageFrom(cursor, json) ?: continue
                        out.add(message)
                    }
                }
                if (pending.isNotEmpty()) {
                    for (message in out) {
                        val emoji = pending[message.id] ?: continue
                        if (message.json.optString("rx").isEmpty()) message.json.put("rx", emoji)
                    }
                }
                out
            } catch (_: Exception) {
                emptyList()
            }
        }
    }

    fun get(context: Context, id: String): ChatMessage? {
        val safe = normalizeId(id) ?: return null
        val e = epoch.get()
        if (!Store.isPaired(context)) return null
        synchronized(lock) {
            if (epoch.get() != e) return null
            val database = db(context) ?: return null
            return try {
                readMessage(database, safe)
            } catch (_: Exception) {
                null
            }
        }
    }

    fun insert(context: Context, message: ChatMessage): Boolean {
        val id = normalizeId(message.id) ?: return false
        val copy = try {
            JSONObject(message.json.toString())
        } catch (_: Exception) {
            return false
        }
        copy.put("id", id)
        val status = if (statusOk(message.status)) message.status else SENDING
        return edit(context) { insertLocked(context, id, message.ts, message.outgoing, status, copy) } == true
    }

    /** Stores a partner message. False when the id is bad, it is already stored, or it could not be sealed. */
    fun insertIncoming(context: Context, json: JSONObject): Boolean {
        val id = normalizeId(json.optString("id")) ?: return false
        val type = json.optString("t")
        if (type != "chat" && type != "att" && type != "link") return false
        val copy = try {
            JSONObject(json.toString())
        } catch (_: Exception) {
            return false
        }
        val rawTs = copy.optLong("ts", 0L)
        val ts = if (rawTs > 0L) rawTs else System.currentTimeMillis()
        copy.put("id", id)
        if (rawTs <= 0L) copy.put("ts", ts)
        return edit(context) { insertIncomingLocked(context, id, ts, copy) } == true
    }

    fun setStatus(context: Context, id: String, status: String): Boolean {
        val safe = normalizeId(id) ?: return false
        if (!statusOk(status)) return false
        return edit(context) { setStatusLocked(context, safe, status) } == true
    }

    /**
     * Moves one of this phone's messages forward: sent, then delivered, then read.
     * A missing row, a partner message, a failure, or a step backward is left as it is.
     * Old rows with an empty or unknown status are not promoted and do not throw.
     */
    fun advanceStatus(context: Context, id: String, status: String): Boolean {
        val safe = normalizeId(id) ?: return false
        if (status != DELIVERED && status != READ && status != SENT) return false
        return edit(context) { advanceStatusLocked(context, safe, status) } == true
    }

    /** Partner delivery or read receipt. Ids that are not ours are ignored. */
    fun applyReceipt(context: Context, json: JSONObject, read: Boolean) {
        val ids = json.optJSONArray("ids") ?: return
        val next = if (read) READ else DELIVERED
        val cap = minOf(ids.length(), 80)
        for (i in 0 until cap) {
            advanceStatus(context, ids.optString(i), next)
        }
    }

    /**
     * Local-only flag on a stored message (`sv` seen receipt sent, `dv` delivery receipt sent).
     * Never put on the wire. See [com.nextlayer.betweenly.ui.ChatController].
     */
    fun markLocal(context: Context, ids: List<String>, key: String): Boolean {
        if (key != "sv" && key != "dv") return false
        val clean = ids.mapNotNull { normalizeId(it) }.distinct()
        if (clean.isEmpty()) return false
        return edit(context) { markLocalLocked(context, clean, key) } == true
    }

    fun updateJson(context: Context, id: String, json: JSONObject): Boolean {
        val safe = normalizeId(id) ?: return false
        return edit(context) { updateJsonLocked(context, safe, json) } == true
    }

    fun delete(context: Context, id: String): Boolean {
        val safe = normalizeId(id) ?: return false
        return edit(context) { deleteLocked(context, safe) } == true
    }

    /**
     * The partner's reaction on [id]. The same emoji as the one already stored
     * clears it. A different emoji replaces it. A reaction that arrives before
     * the message waits on a side row until [insertIncoming].
     */
    fun setReaction(context: Context, id: String, emoji: String): Boolean {
        val safe = normalizeId(id) ?: return false
        val clean = emoji.trim().filter { it >= ' ' }.take(16)
        if (clean.isEmpty()) return false
        return edit(context) { setReactionLocked(context, safe, clean) } == true
    }

    /**
     * This phone's reaction. An empty [emoji] clears it. Not sent by itself;
     * the caller sends the separate `react` message.
     */
    fun setMyReaction(context: Context, id: String, emoji: String): Boolean {
        val safe = normalizeId(id) ?: return false
        val clean = emoji.trim().filter { it >= ' ' }.take(16)
        return edit(context) { setMineLocked(context, safe, clean) } == true
    }

    /**
     * Replaces one row with a tombstone and drops its attachment files, cached
     * open-file, link preview (it lived in the JSON), and reactions.
     * [onlyIncoming] is a partner unsend: rows this phone sent are left alone.
     * The sender passes false and the row must be outgoing.
     */
    fun tombstone(context: Context, id: String, onlyIncoming: Boolean): Boolean {
        val safe = normalizeId(id) ?: return false
        return edit(context) { tombstoneLocked(context, safe, onlyIncoming) } == true
    }

    /** True when this id is already a delete-for-everyone tombstone. */
    fun isGone(context: Context, id: String): Boolean {
        val message = get(context, id) ?: return false
        return message.json.optString("t") == "gone"
    }

    /** Centered system line, encrypted like any other row. It is not a message from either person. */
    fun insertNote(context: Context, text: String): Boolean {
        val clean = text.trim().filter { it >= ' ' }.take(200)
        if (clean.isEmpty()) return false
        val id = newId()
        val ts = System.currentTimeMillis()
        val json = JSONObject().put("t", "note").put("id", id).put("ts", ts).put("text", clean)
        return edit(context) {
            insertLocked(context, id, ts, outgoing = false, status = RECEIVED, json = json)
        } == true
    }

    /**
     * Deletes every message and cached attachment on this phone. The shared
     * wallpaper file and its prefs stay. Safe when the chat screen is open.
     */
    fun clearHistory(context: Context): Boolean {
        val e = epoch.get()
        if (!Store.isPaired(context)) return false
        val app = context.applicationContext
        val ok = synchronized(lock) {
            if (epoch.get() != e) return@synchronized false
            try {
                val database = db(app) ?: return@synchronized false
                database.delete(TABLE, null, null)
                val dir = File(app.filesDir, "attachments")
                if (dir.isDirectory) dir.listFiles()?.forEach { file -> file.delete() }
                deleteQuiet(File(app.cacheDir, "chat-open"))
                deleteQuiet(File(app.cacheDir, "chat-anim"))
                failedMut.value = emptySet()
                synchronized(unreadLock) {
                    try {
                        appPrefs(app).edit().putInt(UNREAD, 0).commit()
                    } catch (_: Exception) {
                        // The badge is cleared in memory either way.
                    }
                    unreadMut.value = 0
                }
                clearEarlyLocked(app)
                bump()
                true
            } catch (_: Exception) {
                false
            }
        }
        if (ok) {
            try {
                ChatNotifications.cancel(app)
            } catch (_: Exception) {
                // The rows are already gone.
            }
        }
        return ok
    }

    fun hasAttachment(context: Context, id: String): Boolean {
        val safe = normalizeId(id) ?: return false
        synchronized(lock) {
            return try {
                val file = File(context.applicationContext.filesDir, "attachments/$safe.bin")
                file.exists() && file.length() > 0L
            } catch (_: Exception) {
                false
            }
        }
    }

    /** Seals [plain] and writes `attachments/{id}.bin`. False if the keystore or the disk refuses. */
    fun writeAttachment(context: Context, id: String, plain: ByteArray): Boolean {
        val safe = normalizeId(id) ?: return false
        if (plain.isEmpty()) return false
        val sealed = Secure.encrypt(plain) ?: return false
        return edit(context) { writeAttachmentLocked(context, safe, sealed) } == true
    }

    fun readAttachment(context: Context, id: String): ByteArray? {
        val safe = normalizeId(id) ?: return null
        val packed = synchronized(lock) {
            try {
                val file = File(context.applicationContext.filesDir, "attachments/$safe.bin")
                if (!file.exists() || file.length() <= 0L) null else file.readBytes()
            } catch (_: Exception) {
                null
            }
        } ?: return null
        return Secure.decrypt(packed)
    }

    /**
     * Writes the decrypted attachment into the cache so FileProvider can hand it
     * to another app. The file is plaintext and is removed by [wipe].
     */
    fun exportOpenFile(context: Context, id: String, name: String): File? {
        val safe = normalizeId(id) ?: return null
        val plain = readAttachment(context, safe) ?: return null
        return try {
            val dir = File(context.applicationContext.cacheDir, "chat-open")
            if (!dir.exists() && !dir.mkdirs()) return null
            val file = File(dir, "$safe-${sanitizeName(name)}")
            file.writeBytes(plain)
            file
        } catch (_: Exception) {
            null
        } catch (_: OutOfMemoryError) {
            null
        }
    }

    /**
     * Downloads an attachment that is not already on disk. One download runs at
     * a time. Null when the relay does not have it or the key does not open it.
     */
    suspend fun fetch(context: Context, id: String): ByteArray? = withContext(Dispatchers.IO) {
        val safe = normalizeId(id) ?: return@withContext null
        readAttachment(context, safe)?.let { return@withContext it }
        gate.withLock {
            readAttachment(context, safe)?.let { return@withLock it }
            val message = get(context, safe) ?: return@withLock null
            val fid = message.json.optString("fid").trim().lowercase()
            val key = message.json.optString("key")
            if (fid.length != 32 || key.isEmpty()) return@withLock null
            failedMut.update { it - safe }
            val plain = try {
                FileTransfer.download(context, fid, key)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            if (plain == null || plain.isEmpty()) {
                failedMut.update { it + safe }
                return@withLock null
            }
            if (!writeAttachment(context, safe, plain)) {
                failedMut.update { it + safe }
                return@withLock null
            }
            failedMut.update { it - safe }
            ChatGallery.maybe(context, safe, message.json, plain)
            plain
        }
    }

    fun saveWallpaper(context: Context, jpeg: ByteArray): Boolean {
        if (jpeg.isEmpty() || jpeg.size > 3_000_000) return false
        val e = epoch.get()
        if (!Store.isPaired(context)) return false
        synchronized(lock) {
            if (epoch.get() != e) return false
            return try {
                val dest = wallpaperFile(context)
                val tmp = File(dest.parentFile, "chat_wallpaper.jpg.tmp")
                tmp.writeBytes(jpeg)
                if (dest.exists()) dest.delete()
                if (!tmp.renameTo(dest)) {
                    dest.writeBytes(jpeg)
                    tmp.delete()
                }
                true
            } catch (_: Exception) {
                false
            }
        }
    }

    fun readWallpaper(context: Context): ByteArray? {
        return try {
            val file = wallpaperFile(context)
            if (!file.exists() || file.length() <= 0L || file.length() > 3_000_000L) {
                null
            } else {
                file.readBytes()
            }
        } catch (_: Exception) {
            null
        }
    }

    fun wallpaperFile(context: Context): File =
        File(context.applicationContext.filesDir, "chat_wallpaper.jpg")

    /** Drops the shared wallpaper JPEG. Color and default backgrounds have no file. */
    fun deleteWallpaper(context: Context) {
        synchronized(lock) {
            deleteQuiet(wallpaperFile(context))
            deleteQuiet(File(context.applicationContext.filesDir, "chat_wallpaper.jpg.tmp"))
        }
    }

    /**
     * Deletes the chat database, attachment files, wallpaper, and the plaintext
     * cache. Safe to call from [Store.unpair] while that lock is held: this
     * method does not call back into [Store].
     */
    fun wipe(context: Context) {
        val app = context.applicationContext
        synchronized(lock) {
            epoch.incrementAndGet()
            try {
                helper?.close()
            } catch (_: Exception) {
                // The files are deleted next either way.
            }
            helper = null
            try {
                app.deleteDatabase(DB_NAME)
            } catch (_: Exception) {
                // A locked file is removed on the next attempt.
            }
            deleteQuiet(File(app.filesDir, "attachments"))
            deleteQuiet(wallpaperFile(app))
            deleteQuiet(File(app.filesDir, "chat_wallpaper.jpg.tmp"))
            deleteQuiet(File(app.cacheDir, "chat-open"))
            deleteQuiet(File(app.cacheDir, "chat-anim"))
            failedMut.value = emptySet()
            synchronized(unreadLock) {
                try {
                    appPrefs(app).edit().putInt(UNREAD, 0).commit()
                } catch (_: Exception) {
                    // The badge is cleared in memory either way.
                }
                unreadMut.value = 0
            }
            clearEarlyLocked(app)
            partnerHeardAt = 0L
            bump()
        }
        try {
            ChatNotifications.cancel(app)
        } catch (_: Exception) {
            // The rows are already gone.
        }
    }

    internal fun alreadySaved(context: Context, id: String): Boolean =
        get(context, id)?.json?.optInt("gal") == 1

    internal fun markSaved(context: Context, id: String) {
        edit(context) { markSavedLocked(context, id) }
    }

    private fun insertLocked(
        context: Context,
        id: String,
        ts: Long,
        outgoing: Boolean,
        status: String,
        json: JSONObject,
    ): Boolean {
        val database = db(context) ?: return false
        if (exists(database, id)) return false
        val blob = seal(json) ?: return false
        val direction = if (outgoing) DIR_OUT else DIR_IN
        if (!insertRow(database, id, ts, direction, status, blob)) return false
        bump()
        return true
    }

    private fun insertIncomingLocked(context: Context, id: String, ts: Long, json: JSONObject): Boolean {
        val database = db(context) ?: return false
        if (exists(database, id)) return false
        if (hasEarlyLocked(context, id)) {
            val gone = JSONObject().put("t", "gone").put("id", id).put("ts", ts)
            val goneBlob = seal(gone) ?: return false
            if (!insertRow(database, id, ts, DIR_IN, RECEIVED, goneBlob)) return false
            dropEarlyLocked(context, id)
            bump()
            // False so the caller does not notify or download the removed bytes.
            return false
        }
        val pendingId = "rx$id"
        if (exists(database, pendingId)) {
            val pending = readJson(database, pendingId)
            val emoji = pending?.optString("emoji").orEmpty()
            if (emoji.isNotEmpty() && json.optString("rx").isEmpty()) json.put("rx", emoji)
            database.delete(TABLE, "id=?", arrayOf(pendingId))
        }
        val blob = seal(json) ?: return false
        if (!insertRow(database, id, ts, DIR_IN, RECEIVED, blob)) return false
        partnerHeardAt = System.currentTimeMillis()
        bump()
        return true
    }

    private fun advanceStatusLocked(context: Context, id: String, status: String): Boolean {
        val database = db(context) ?: return false
        val current = readMessage(database, id) ?: return false
        if (!current.outgoing) return false
        val type = current.json.optString("t")
        if (type == "gone" || type == "note") return false
        if (current.status == FAILED) return false
        if (statusRank(status) <= statusRank(current.status)) return false
        val values = ContentValues().apply { put("status", status) }
        if (database.update(TABLE, values, "id=?", arrayOf(id)) <= 0) return false
        bump()
        return true
    }

    private fun markLocalLocked(context: Context, ids: List<String>, key: String): Boolean {
        val database = db(context) ?: return false
        var changed = false
        for (id in ids) {
            val current = readMessage(database, id) ?: continue
            if (current.json.optInt(key) == 1) continue
            current.json.put(key, 1)
            val blob = seal(current.json) ?: continue
            val values = ContentValues().apply { put("body", blob) }
            if (database.update(TABLE, values, "id=?", arrayOf(id)) > 0) changed = true
        }
        if (changed) bump()
        return changed
    }

    private fun setStatusLocked(context: Context, id: String, status: String): Boolean {
        val database = db(context) ?: return false
        val current = readMessage(database, id) ?: return false
        if (current.json.optString("t") == "gone") return false
        val values = ContentValues().apply { put("status", status) }
        if (database.update(TABLE, values, "id=?", arrayOf(id)) <= 0) return false
        bump()
        return true
    }

    private fun updateJsonLocked(context: Context, id: String, json: JSONObject): Boolean {
        val database = db(context) ?: return false
        val current = readMessage(database, id) ?: return false
        if (current.json.optString("t") == "gone") return false
        if (current.json.optInt("gal") == 1) json.put("gal", 1)
        if (current.json.optInt("sv") == 1) json.put("sv", 1)
        if (current.json.optInt("dv") == 1) json.put("dv", 1)
        if (json.optString("rx").isEmpty()) {
            val existing = current.json.optString("rx")
            if (existing.isNotEmpty()) json.put("rx", existing)
        }
        if (json.optString("mx").isEmpty()) {
            val mine = current.json.optString("mx")
            if (mine.isNotEmpty()) json.put("mx", mine)
        }
        val blob = seal(json) ?: return false
        val values = ContentValues().apply { put("body", blob) }
        if (database.update(TABLE, values, "id=?", arrayOf(id)) <= 0) return false
        bump()
        return true
    }

    private fun deleteLocked(context: Context, id: String): Boolean {
        val database = db(context) ?: return false
        database.delete(TABLE, "id=?", arrayOf(id))
        database.delete(TABLE, "id=?", arrayOf("rx$id"))
        deleteAttachmentFiles(context, id)
        bump()
        return true
    }

    private fun setMineLocked(context: Context, id: String, emoji: String): Boolean {
        val database = db(context) ?: return false
        val current = readMessage(database, id) ?: return false
        val type = current.json.optString("t")
        if (type == "gone" || type == "note") return false
        if (current.json.optString("mx") == emoji) return true
        if (emoji.isEmpty()) current.json.remove("mx") else current.json.put("mx", emoji)
        val blob = seal(current.json) ?: return false
        val values = ContentValues().apply { put("body", blob) }
        if (database.update(TABLE, values, "id=?", arrayOf(id)) <= 0) return false
        bump()
        return true
    }

    private fun tombstoneLocked(context: Context, id: String, onlyIncoming: Boolean): Boolean {
        val database = db(context) ?: return false
        val current = readMessage(database, id)
        if (current == null) {
            if (!onlyIncoming) return false
            rememberEarlyLocked(context, id)
            return true
        }
        if (onlyIncoming && current.outgoing) return false
        if (!onlyIncoming && !current.outgoing) return false
        if (current.json.optString("t") != "gone") {
            val gone = JSONObject().put("t", "gone").put("id", id).put("ts", current.ts)
            val blob = seal(gone) ?: return false
            val values = ContentValues().apply { put("body", blob) }
            if (database.update(TABLE, values, "id=?", arrayOf(id)) <= 0) return false
        }
        database.delete(TABLE, "id=?", arrayOf("rx$id"))
        deleteAttachmentFiles(context, id)
        bump()
        return true
    }

    private fun deleteAttachmentFiles(context: Context, id: String) {
        try {
            File(context.applicationContext.filesDir, "attachments/$id.bin").delete()
            File(context.applicationContext.filesDir, "attachments/$id.bin.tmp").delete()
            val cache = File(context.applicationContext.cacheDir, "chat-open")
            cache.listFiles()?.forEach { file ->
                if (file.name.startsWith("$id-")) file.delete()
            }
            File(context.applicationContext.cacheDir, "chat-anim/$id.img").delete()
        } catch (_: Exception) {
            // The row is already a tombstone even if a file is not.
        }
    }

    private fun setReactionLocked(context: Context, id: String, emoji: String): Boolean {
        val database = db(context) ?: return false
        val current = readMessage(database, id)
        if (current != null) {
            val type = current.json.optString("t")
            if (type == "gone" || type == "note") return false
            if (current.json.optString("rx") == emoji) {
                current.json.remove("rx")
            } else {
                current.json.put("rx", emoji)
            }
            val blob = seal(current.json) ?: return false
            val values = ContentValues().apply { put("body", blob) }
            if (database.update(TABLE, values, "id=?", arrayOf(id)) <= 0) return false
            database.delete(TABLE, "id=?", arrayOf("rx$id"))
        } else {
            val pendingId = "rx$id"
            val pending = readJson(database, pendingId)
            val existing = pending?.optString("emoji").orEmpty()
            if (existing == emoji) {
                database.delete(TABLE, "id=?", arrayOf(pendingId))
            } else {
                val pendingJson = JSONObject().put("t", "react").put("id", id).put("emoji", emoji)
                val blob = seal(pendingJson) ?: return false
                if (!insertRow(database, pendingId, System.currentTimeMillis(), DIR_IN, RECEIVED, blob)) {
                    val values = ContentValues().apply { put("body", blob) }
                    if (database.update(TABLE, values, "id=?", arrayOf(pendingId)) <= 0) return false
                }
            }
        }
        bump()
        return true
    }

    private fun markSavedLocked(context: Context, id: String): Boolean {
        val safe = normalizeId(id) ?: return false
        val database = db(context) ?: return false
        val current = readMessage(database, safe) ?: return false
        if (current.json.optInt("gal") == 1) return true
        current.json.put("gal", 1)
        val blob = seal(current.json) ?: return false
        val values = ContentValues().apply { put("body", blob) }
        if (database.update(TABLE, values, "id=?", arrayOf(safe)) <= 0) return false
        bump()
        return true
    }

    private fun writeAttachmentLocked(context: Context, id: String, sealed: ByteArray): Boolean {
        val dir = File(context.applicationContext.filesDir, "attachments")
        if (!dir.exists() && !dir.mkdirs()) return false
        val dest = File(dir, "$id.bin")
        val tmp = File(dir, "$id.bin.tmp")
        return try {
            tmp.writeBytes(sealed)
            if (dest.exists()) dest.delete()
            if (!tmp.renameTo(dest)) {
                dest.writeBytes(sealed)
                tmp.delete()
            }
            bump()
            true
        } catch (_: Exception) {
            tmp.delete()
            false
        }
    }

    /**
     * Runs [block] only if this pair generation is still current. Callers must
     * not touch [Store] from inside [block]: [Store.unpair] holds the store
     * lock and then this lock.
     */
    private fun edit(context: Context, block: () -> Boolean): Boolean? {
        val e = epoch.get()
        if (!Store.isPaired(context)) return null
        return synchronized(lock) {
            if (epoch.get() != e) {
                null
            } else {
                try {
                    block()
                } catch (_: Exception) {
                    null
                }
            }
        }
    }

    private fun db(context: Context): SQLiteDatabase? {
        return try {
            val existing = helper
            val open = existing ?: Helper(context.applicationContext).also { helper = it }
            open.writableDatabase
        } catch (_: Exception) {
            null
        }
    }

    private fun exists(database: SQLiteDatabase, id: String): Boolean {
        database.query(TABLE, arrayOf("id"), "id=?", arrayOf(id), null, null, null).use { cursor ->
            return cursor.moveToFirst()
        }
    }

    private fun readJson(database: SQLiteDatabase, id: String): JSONObject? {
        database.query(TABLE, arrayOf("body"), "id=?", arrayOf(id), null, null, null).use { cursor ->
            if (!cursor.moveToFirst()) return null
            val blob = cursor.blob("body") ?: return null
            return openBody(blob)
        }
    }

    private fun readMessage(database: SQLiteDatabase, id: String): ChatMessage? {
        database.query(TABLE, null, "id=?", arrayOf(id), null, null, null).use { cursor ->
            if (!cursor.moveToFirst()) return null
            val blob = cursor.blob("body") ?: return null
            val json = openBody(blob) ?: return null
            return messageFrom(cursor, json)
        }
    }

    private fun messageFrom(cursor: Cursor, json: JSONObject): ChatMessage? {
        val id = cursor.string("id") ?: return null
        if (normalizeId(id) == null) return null
        if (json.optString("t") == "react") return null
        val ts = cursor.long("ts")
        val outgoing = cursor.string("direction") == DIR_OUT
        val status = cursor.string("status") ?: RECEIVED
        return ChatMessage(id, ts, outgoing, status, json)
    }

    private fun insertRow(
        database: SQLiteDatabase,
        id: String,
        ts: Long,
        direction: String,
        status: String,
        blob: ByteArray,
    ): Boolean {
        val values = ContentValues().apply {
            put("id", id)
            put("ts", ts)
            put("direction", direction)
            put("status", status)
            put("body", blob)
        }
        return database.insertWithOnConflict(TABLE, null, values, SQLiteDatabase.CONFLICT_REPLACE) >= 0
    }

    private fun seal(json: JSONObject): ByteArray? {
        val plain = try {
            json.toString().toByteArray(Charsets.UTF_8)
        } catch (_: Exception) {
            return null
        }
        if (plain.isEmpty()) return null
        return Secure.encrypt(plain)
    }

    private fun openBody(blob: ByteArray): JSONObject? {
        val plain = Secure.decrypt(blob) ?: return null
        return try {
            JSONObject(String(plain, Charsets.UTF_8))
        } catch (_: Exception) {
            null
        }
    }

    private fun statusOk(status: String): Boolean =
        status == SENDING || status == SENT || status == DELIVERED || status == READ ||
            status == FAILED || status == RECEIVED

    /** Higher means further along. Unknown and empty statuses stay at zero so old rows do not crash. */
    private fun statusRank(status: String): Int = when (status) {
        READ -> 3
        DELIVERED -> 2
        SENT -> 1
        else -> 0
    }

    private fun bump() {
        written.update { it + 1 }
    }

    /** Partner unsend for a message that has not arrived yet. */
    private fun earlyLocked(context: Context): MutableList<String> {
        earlyMem?.let { return it }
        val raw = try {
            appPrefs(context).getString(EARLY, "").orEmpty()
        } catch (_: Exception) {
            ""
        }
        val list = raw.split(',').mapNotNull { normalizeId(it) }.distinct().toMutableList()
        earlyMem = list
        return list
    }

    private fun persistEarly(context: Context) {
        val list = earlyMem ?: return
        try {
            appPrefs(context).edit().putString(EARLY, list.joinToString(",")).commit()
        } catch (_: Exception) {
            // The in-memory set still applies for this process.
        }
    }

    private fun rememberEarlyLocked(context: Context, id: String) {
        val list = earlyLocked(context)
        if (id in list) return
        list.add(id)
        while (list.size > 64) list.removeAt(0)
        persistEarly(context)
    }

    private fun hasEarlyLocked(context: Context, id: String): Boolean = id in earlyLocked(context)

    private fun dropEarlyLocked(context: Context, id: String) {
        val list = earlyLocked(context)
        if (list.remove(id)) persistEarly(context)
    }

    private fun clearEarlyLocked(context: Context) {
        earlyMem = mutableListOf()
        try {
            appPrefs(context).edit().remove(EARLY).commit()
        } catch (_: Exception) {
            // Memory is already empty.
        }
    }

    private fun deleteQuiet(file: File) {
        try {
            if (file.isDirectory) file.deleteRecursively() else file.delete()
        } catch (_: Exception) {
            // Best effort. Unpair still drops the session.
        }
    }

    private fun randomHex(nbytes: Int): String {
        val bytes = ByteArray(nbytes)
        SecureRandom().nextBytes(bytes)
        val hex = "0123456789abcdef".toCharArray()
        val out = CharArray(bytes.size * 2)
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xff
            out[i * 2] = hex[v ushr 4]
            out[i * 2 + 1] = hex[v and 0x0f]
        }
        return String(out)
    }

    private class Helper(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE $TABLE (
                    id TEXT PRIMARY KEY NOT NULL,
                    ts INTEGER NOT NULL,
                    direction TEXT NOT NULL,
                    status TEXT NOT NULL,
                    body BLOB NOT NULL
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE INDEX messages_ts ON $TABLE(ts)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }
}

/** Copies photos and videos into the system gallery. Stickers, GIFs, and files are never copied by [maybe]. */
object ChatGallery {
    /** Saves one photo, video, sticker, or GIF the person asked to keep. False if the gallery refuses. */
    fun saveToGallery(context: Context, name: String, mime: String, bytes: ByteArray, video: Boolean): Boolean {
        if (bytes.isEmpty()) return false
        return try {
            val fallback = if (video) "video.mp4" else "photo.jpg"
            writeMedia(context, sanitizeDisplay(name).ifBlank { fallback }, mime, bytes, video)
        } catch (_: Exception) {
            false
        }
    }

    fun maybe(context: Context, id: String, json: JSONObject, bytes: ByteArray) {
        try {
            if (!Store.chatAutoSaveMedia(context)) return
            val kind = json.optString("kind")
            if (kind != "photo" && kind != "video") return
            if (bytes.isEmpty()) return
            if (ChatStore.alreadySaved(context, id)) return
            val name = storedName(json.optString("name"), id, kind)
            val mime = json.optString("mime").ifBlank {
                if (kind == "video") "video/mp4" else "image/jpeg"
            }
            if (!writeMedia(context, name, mime, bytes, video = kind == "video")) return
            ChatStore.markSaved(context, id)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Missing permission or a full disk. The chat copy remains.
        }
    }

    /** Saves an arbitrary file into Downloads/Betweenly. API 28 has no app-owned Downloads insert. */
    fun saveDownload(context: Context, name: String, mime: String, bytes: ByteArray): Boolean {
        if (Build.VERSION.SDK_INT < 29 || bytes.isEmpty()) return false
        return try {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, sanitizeDisplay(name))
                put(MediaStore.MediaColumns.MIME_TYPE, mime.ifBlank { "application/octet-stream" })
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Betweenly")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return false
            writeStream(resolver, uri, bytes)
        } catch (_: Exception) {
            false
        }
    }

    private fun writeMedia(
        context: Context,
        name: String,
        mime: String,
        bytes: ByteArray,
        video: Boolean,
    ): Boolean {
        return try {
            val resolver = context.contentResolver
            val collection = if (Build.VERSION.SDK_INT >= 29) {
                if (video) {
                    MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                } else {
                    MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                }
            } else if (video) {
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            } else {
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            }
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                if (Build.VERSION.SDK_INT >= 29) {
                    val folder = if (video) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "$folder/Betweenly")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
            }
            val uri = resolver.insert(collection, values) ?: return false
            writeStream(resolver, uri, bytes)
        } catch (_: Exception) {
            false
        }
    }

    private fun writeStream(
        resolver: android.content.ContentResolver,
        uri: android.net.Uri,
        bytes: ByteArray,
    ): Boolean {
        return try {
            val stream = resolver.openOutputStream(uri)
            if (stream == null) {
                try {
                    resolver.delete(uri, null, null)
                } catch (_: Exception) {
                    // The pending row may already be gone.
                }
                return false
            }
            stream.use { it.write(bytes) }
            if (Build.VERSION.SDK_INT >= 29) {
                val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                resolver.update(uri, done, null, null)
            }
            true
        } catch (_: Exception) {
            try {
                resolver.delete(uri, null, null)
            } catch (_: Exception) {
                // The pending row may already be gone.
            }
            false
        }
    }

    private fun storedName(name: String, id: String, kind: String): String {
        val fallback = if (kind == "video") "video.mp4" else "photo.jpg"
        val clean = sanitizeDisplay(name).ifBlank { fallback }
        val dot = clean.lastIndexOf('.')
        val suffix = id.take(4)
        return if (dot in 1 until clean.length - 1 && clean.length - dot <= 8) {
            clean.substring(0, dot) + "-" + suffix + clean.substring(dot)
        } else {
            "$clean-$suffix"
        }
    }

    private fun sanitizeDisplay(name: String): String {
        val cut = name.substringAfterLast('/').substringAfterLast('\\').trim()
        val clean = cut.filter { it >= ' ' && it != '/' && it != '\\' }.take(60)
        return clean
    }
}

private fun Cursor.string(column: String): String? {
    val index = getColumnIndex(column)
    if (index < 0 || isNull(index)) return null
    return getString(index)
}

private fun Cursor.long(column: String): Long {
    val index = getColumnIndex(column)
    if (index < 0 || isNull(index)) return 0L
    return getLong(index)
}

private fun Cursor.blob(column: String): ByteArray? {
    val index = getColumnIndex(column)
    if (index < 0 || isNull(index)) return null
    return getBlob(index)
}

private fun sanitizeName(name: String): String {
    val cut = name.substringAfterLast('/').substringAfterLast('\\').trim()
    val clean = cut.filter { it.isLetterOrDigit() || it == '.' || it == '-' || it == '_' }.take(60)
    return clean.ifBlank { "file" }
}
