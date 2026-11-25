package com.nextlayer.betweenly.music

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import org.json.JSONObject
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.max

/**
 * Playlists, likes, queue, and the offline index. Callers open this only
 * after the music screen is up, or from a playlist message that already arrived.
 *
 * DATA-FLOW: none. Sending a shared edit is [com.nextlayer.betweenly.music.MusicSession].
 */
internal object MusicLibrary {
    private const val KEY_KEEP = "keep_bg"
    private const val KEY_CAP = "cap_mb"
    private const val KEY_SHUFFLE = "shuffle"
    private const val KEY_REPEAT = "repeat"
    private const val KEY_POS = "pos"
    private const val KEY_NOW = "now"
    private const val DEFAULT_CAP_MB = 500

    fun newId(): String = UUID.randomUUID().toString().replace("-", "")

    fun keepBackground(context: Context): Boolean = meta(context, KEY_KEEP) == "1"

    fun setKeepBackground(context: Context, on: Boolean) {
        writeMeta(context, KEY_KEEP, if (on) "1" else "0")
    }

    fun offlineCap(context: Context): Long {
        val mb = meta(context, KEY_CAP)?.toIntOrNull() ?: DEFAULT_CAP_MB
        return mb.coerceIn(50, 4_000).toLong() * 1024L * 1024L
    }

    fun setOfflineCapMb(context: Context, mb: Int) {
        writeMeta(context, KEY_CAP, mb.coerceIn(50, 4_000).toString())
    }

    fun playlists(context: Context): List<PlaylistRow> = read(context, emptyList()) { db ->
        val sql = "SELECT p.id, p.name, p.shared, " +
            "(SELECT COUNT(*) FROM playlist_tracks t WHERE t.playlist_id = p.id AND t.removed = 0) AS n " +
            "FROM playlists p WHERE p.deleted = 0 ORDER BY p.name COLLATE NOCASE LIMIT 100"
        val out = ArrayList<PlaylistRow>()
        db.rawQuery(sql, null).use { c ->
            while (c.moveToNext()) {
                out.add(
                    PlaylistRow(
                        id = c.getString(0),
                        name = c.getString(1),
                        shared = c.getInt(2) != 0,
                        count = c.getInt(3),
                    ),
                )
            }
        }
        out
    }

    fun playlist(context: Context, id: String): PlaylistRow? =
        playlists(context).firstOrNull { it.id == id }

    fun tracks(context: Context, playlistId: String): List<Track> = read(context, emptyList()) { db ->
        val sql = "SELECT source_id, track_id, title, artist, duration_ms, extra " +
            "FROM playlist_tracks WHERE playlist_id = ? AND removed = 0 ORDER BY position ASC, ts ASC LIMIT 200"
        val out = ArrayList<Track>()
        db.rawQuery(sql, arrayOf(playlistId)).use { c ->
            while (c.moveToNext()) out.add(readTrack(c))
        }
        out
    }

    fun create(context: Context, name: String): PlaylistRow? {
        val clean = clipName(name)
        if (clean.isEmpty()) return null
        val id = newId()
        val ts = System.currentTimeMillis()
        val ok = write(context) { db ->
            db.insert("playlists", null, playlistValues(id, clean, shared = false, ts = ts, deleted = false)) > 0
        }
        return if (ok) PlaylistRow(id, clean, shared = false, count = 0) else null
    }

    fun rename(context: Context, id: String, name: String, ts: Long): Boolean {
        val clean = clipName(name)
        if (clean.isEmpty() || !idOk(id)) return false
        return write(context) { db ->
            val values = ContentValues()
            values.put("name", clean)
            values.put("ts", ts)
            db.update("playlists", values, "id = ? AND deleted = 0", arrayOf(id)) > 0
        }
    }

    fun delete(context: Context, id: String, ts: Long): Boolean {
        if (!idOk(id)) return false
        return write(context) { db ->
            val values = ContentValues()
            values.put("deleted", 1)
            values.put("ts", ts)
            db.update("playlists", values, "id = ?", arrayOf(id)) > 0
        }
    }

    fun setShared(context: Context, id: String, shared: Boolean, ts: Long): Boolean {
        if (!idOk(id)) return false
        return write(context) { db ->
            val values = ContentValues()
            values.put("shared", if (shared) 1 else 0)
            values.put("ts", ts)
            db.update("playlists", values, "id = ? AND deleted = 0", arrayOf(id)) > 0
        }
    }

    fun addTrack(context: Context, playlistId: String, track: Track, ts: Long): Boolean {
        if (!idOk(playlistId) || track.id.isBlank() || track.sourceId.isBlank()) return false
        return write(context) { db ->
            upsertTrack(db, playlistId, track, ts, index = -1)
        }
    }

    fun removeTrack(context: Context, playlistId: String, track: Track, ts: Long): Boolean {
        if (!idOk(playlistId)) return false
        return write(context) { db ->
            markTrackRemoved(db, playlistId, track, ts)
        }
    }

    fun moveTrack(context: Context, playlistId: String, index: Int, delta: Int): Boolean {
        val current = tracks(context, playlistId)
        val to = index + delta
        if (index !in current.indices || to !in current.indices) return false
        val next = current.toMutableList()
        val item = next.removeAt(index)
        next.add(to, item)
        return write(context) { db ->
            db.beginTransaction()
            try {
                next.forEachIndexed { position, track ->
                    val values = ContentValues()
                    values.put("position", position)
                    db.update(
                        "playlist_tracks",
                        values,
                        "playlist_id = ? AND source_id = ? AND track_id = ? AND removed = 0",
                        arrayOf(playlistId, track.sourceId, track.id),
                    )
                }
                db.setTransactionSuccessful()
                true
            } finally {
                db.endTransaction()
            }
        }
    }

    fun isLiked(context: Context, track: Track): Boolean = read(context, false) { db ->
        db.rawQuery(
            "SELECT 1 FROM likes WHERE source_id = ? AND track_id = ? LIMIT 1",
            arrayOf(track.sourceId, track.id),
        ).use { it.moveToFirst() }
    }

    fun liked(context: Context): List<Track> = read(context, emptyList()) { db ->
        val sql = "SELECT source_id, track_id, title, artist, duration_ms, extra " +
            "FROM likes ORDER BY ts DESC LIMIT 500"
        val out = ArrayList<Track>()
        db.rawQuery(sql, null).use { c ->
            while (c.moveToNext()) out.add(readTrack(c))
        }
        out
    }

    fun likedKeys(context: Context): Set<String> = read(context, emptySet()) { db ->
        val out = HashSet<String>()
        db.rawQuery("SELECT source_id, track_id FROM likes LIMIT 500", null).use { c ->
            while (c.moveToNext()) {
                out.add(c.getString(0) + "\u0000" + c.getString(1))
            }
        }
        out
    }

    fun setLiked(context: Context, track: Track, on: Boolean) {
        if (track.id.isBlank() || track.sourceId.isBlank()) return
        write(context) { db ->
            if (!on) {
                db.delete(
                    "likes",
                    "source_id = ? AND track_id = ?",
                    arrayOf(track.sourceId, track.id),
                )
            } else {
                val values = trackValues(track)
                values.put("ts", System.currentTimeMillis())
                db.insertWithOnConflict("likes", null, values, SQLiteDatabase.CONFLICT_REPLACE)
            }
            true
        }
    }

    fun savePlayback(
        context: Context,
        queue: List<Track>,
        track: Track?,
        positionMs: Long,
        shuffle: Boolean,
        repeat: RepeatMode,
    ) {
        write(context) { db ->
            db.beginTransaction()
            try {
                db.delete("queue", null, null)
                queue.take(100).forEachIndexed { index, item ->
                    val values = trackValues(item)
                    values.put("position", index)
                    db.insert("queue", null, values)
                }
                putMeta(db, KEY_NOW, encode(track))
                putMeta(db, KEY_POS, positionMs.coerceAtLeast(0L).toString())
                putMeta(db, KEY_SHUFFLE, if (shuffle) "1" else "0")
                putMeta(db, KEY_REPEAT, when (repeat) {
                    RepeatMode.All -> "all"
                    RepeatMode.One -> "one"
                    RepeatMode.Off -> "off"
                })
                db.setTransactionSuccessful()
                true
            } finally {
                db.endTransaction()
            }
        }
    }

    fun loadPlayback(context: Context): SavedPlayback = read(context, SavedPlayback()) { db ->
        val queue = ArrayList<Track>()
        db.rawQuery(
            "SELECT source_id, track_id, title, artist, duration_ms, extra FROM queue ORDER BY position ASC LIMIT 100",
            null,
        ).use { c ->
            while (c.moveToNext()) queue.add(readTrack(c))
        }
        val now = decode(getMeta(db, KEY_NOW).orEmpty())
        val repeat = when (getMeta(db, KEY_REPEAT)) {
            "all" -> RepeatMode.All
            "one" -> RepeatMode.One
            else -> RepeatMode.Off
        }
        SavedPlayback(
            queue = queue,
            track = now,
            positionMs = getMeta(db, KEY_POS)?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L,
            shuffle = getMeta(db, KEY_SHUFFLE) == "1",
            repeat = repeat,
        )
    }

    /**
     * Applies one sealed playlist edit. Last writer wins per track, by [ts].
     * A playlist this phone has unshared is left alone.
     */
    fun applyRemote(context: Context, json: JSONObject): Boolean {
        if (json.optString("t") != "plist") return false
        val op = json.optString("op")
        if (op !in PlaylistWire.OPS) return false
        val id = json.optString("id").trim().lowercase()
        if (!idOk(id)) return false
        val ts = json.optLong("ts")
        if (ts <= 0L) return false
        val name = clipName(json.optString("name"))
        val items = PlaylistWire.items(json)
        return write(context) { db ->
            db.beginTransaction()
            try {
                val row = readPlaylist(db, id)
                val ignored = (row != null && !row.deleted && !row.shared) ||
                    (row != null && row.deleted && ts <= row.ts && op != "snapshot" && op != "create")
                val changed = if (ignored) {
                    false
                } else {
                    applyOp(db, op, id, name, ts, row, items)
                }
                db.setTransactionSuccessful()
                changed
            } finally {
                db.endTransaction()
            }
        }
    }

    private fun applyOp(
        db: SQLiteDatabase,
        op: String,
        id: String,
        name: String,
        ts: Long,
        row: PlaylistRaw?,
        items: List<PlaylistWire.Item>,
    ): Boolean {
        var changed = false
        when (op) {
            "delete" -> {
                if (row == null || ts >= row.ts) {
                    upsertPlaylist(db, id, name.ifBlank { row?.name ?: "Playlist" }, ts, shared = true, deleted = true)
                    changed = true
                }
            }
            "create", "rename" -> {
                if (row == null || ts >= row.ts) {
                    upsertPlaylist(
                        db,
                        id,
                        name.ifBlank { row?.name ?: "Playlist" },
                        ts,
                        shared = true,
                        deleted = false,
                    )
                    changed = true
                }
            }
            "add" -> {
                val alive = row == null || !row.deleted || ts > row.ts
                if (alive && (row == null || row.deleted)) {
                    upsertPlaylist(db, id, name.ifBlank { row?.name ?: "Playlist" }, ts, shared = true, deleted = false)
                    changed = true
                }
                if (alive) {
                    for (item in items) {
                        if (upsertTrack(db, id, item.track, item.ts, index = -1)) changed = true
                    }
                }
            }
            "remove" -> {
                for (item in items) {
                    if (markTrackRemoved(db, id, item.track, item.ts)) changed = true
                }
            }
            "snapshot" -> {
                if (row == null || ts >= row.ts) {
                    upsertPlaylist(
                        db,
                        id,
                        name.ifBlank { row?.name ?: "Playlist" },
                        max(ts, row?.ts ?: ts),
                        shared = true,
                        deleted = false,
                    )
                    changed = true
                }
                val keep = HashSet<String>()
                items.forEachIndexed { index, item ->
                    keep.add(item.track.key())
                    if (upsertTrack(db, id, item.track, item.ts, index)) changed = true
                }
                if (dropMissing(db, id, keep, ts)) changed = true
            }
        }
        return changed
    }

    private fun upsertTrack(db: SQLiteDatabase, playlistId: String, track: Track, ts: Long, index: Int): Boolean {
        if (track.id.isBlank() || track.sourceId.isBlank() || ts <= 0L) return false
        val existing = readTrackRow(db, playlistId, track)
        if (existing != null && ts < existing.ts) return false
        val position = when {
            index >= 0 -> index
            existing != null -> existing.position
            else -> nextPosition(db, playlistId)
        }
        val extra = TrackExtra.mergeKeepLocal(existing?.extra.orEmpty(), track.extra)
        val values = ContentValues()
        values.put("playlist_id", playlistId)
        values.put("source_id", track.sourceId.take(32))
        values.put("track_id", track.id.take(128))
        values.put("title", (track.title.ifBlank { existing?.title.orEmpty() }).take(120))
        values.put("artist", (track.artist.ifBlank { existing?.artist.orEmpty() }).take(80))
        values.put("duration_ms", if (track.durationMs > 0L) track.durationMs else existing?.durationMs ?: 0L)
        values.put("extra", extra.take(4_000))
        values.put("position", position)
        values.put("ts", ts)
        values.put("removed", 0)
        db.insertWithOnConflict("playlist_tracks", null, values, SQLiteDatabase.CONFLICT_REPLACE)
        return true
    }

    private fun markTrackRemoved(db: SQLiteDatabase, playlistId: String, track: Track, ts: Long): Boolean {
        if (track.id.isBlank() || track.sourceId.isBlank()) return false
        val existing = readTrackRow(db, playlistId, track)
        if (existing != null && ts < existing.ts) return false
        val values = ContentValues()
        values.put("playlist_id", playlistId)
        values.put("source_id", track.sourceId.take(32))
        values.put("track_id", track.id.take(128))
        values.put("title", existing?.title ?: track.title.take(120))
        values.put("artist", existing?.artist ?: track.artist.take(80))
        values.put("duration_ms", existing?.durationMs ?: track.durationMs.coerceAtLeast(0L))
        values.put("extra", existing?.extra ?: "")
        values.put("position", existing?.position ?: 0)
        values.put("ts", ts)
        values.put("removed", 1)
        db.insertWithOnConflict("playlist_tracks", null, values, SQLiteDatabase.CONFLICT_REPLACE)
        return true
    }

    private fun dropMissing(db: SQLiteDatabase, playlistId: String, keep: Set<String>, ts: Long): Boolean {
        val stale = ArrayList<Pair<String, String>>()
        db.rawQuery(
            "SELECT source_id, track_id, ts FROM playlist_tracks WHERE playlist_id = ? AND removed = 0",
            arrayOf(playlistId),
        ).use { c ->
            while (c.moveToNext()) {
                val key = c.getString(0) + "\u0000" + c.getString(1)
                if (key !in keep && c.getLong(2) <= ts) stale.add(c.getString(0) to c.getString(1))
            }
        }
        if (stale.isEmpty()) return false
        val values = ContentValues()
        values.put("removed", 1)
        values.put("ts", ts)
        for (pair in stale) {
            db.update(
                "playlist_tracks",
                values,
                "playlist_id = ? AND source_id = ? AND track_id = ?",
                arrayOf(playlistId, pair.first, pair.second),
            )
        }
        return true
    }

    private fun nextPosition(db: SQLiteDatabase, playlistId: String): Int {
        db.rawQuery(
            "SELECT COALESCE(MAX(position), -1) FROM playlist_tracks WHERE playlist_id = ? AND removed = 0",
            arrayOf(playlistId),
        ).use { c ->
            return if (c.moveToFirst()) c.getInt(0) + 1 else 0
        }
    }

    private fun readTrackRow(db: SQLiteDatabase, playlistId: String, track: Track): TrackRow? {
        db.rawQuery(
            "SELECT title, artist, duration_ms, extra, position, ts, removed FROM playlist_tracks " +
                "WHERE playlist_id = ? AND source_id = ? AND track_id = ? LIMIT 1",
            arrayOf(playlistId, track.sourceId, track.id),
        ).use { c ->
            if (!c.moveToFirst()) return null
            return TrackRow(
                title = c.getString(0),
                artist = c.getString(1),
                durationMs = c.getLong(2),
                extra = c.getString(3),
                position = c.getInt(4),
                ts = c.getLong(5),
                removed = c.getInt(6) != 0,
            )
        }
    }

    private data class TrackRow(
        val title: String,
        val artist: String,
        val durationMs: Long,
        val extra: String,
        val position: Int,
        val ts: Long,
        val removed: Boolean,
    )

    private data class PlaylistRaw(
        val name: String,
        val shared: Boolean,
        val ts: Long,
        val deleted: Boolean,
    )

    private fun readPlaylist(db: SQLiteDatabase, id: String): PlaylistRaw? {
        db.rawQuery(
            "SELECT name, shared, ts, deleted FROM playlists WHERE id = ? LIMIT 1",
            arrayOf(id),
        ).use { c ->
            if (!c.moveToFirst()) return null
            return PlaylistRaw(c.getString(0), c.getInt(1) != 0, c.getLong(2), c.getInt(3) != 0)
        }
    }

    private fun upsertPlaylist(
        db: SQLiteDatabase,
        id: String,
        name: String,
        ts: Long,
        shared: Boolean,
        deleted: Boolean,
    ) {
        db.insertWithOnConflict(
            "playlists",
            null,
            playlistValues(id, clipName(name).ifBlank { "Playlist" }, shared, ts, deleted),
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    private fun playlistValues(id: String, name: String, shared: Boolean, ts: Long, deleted: Boolean): ContentValues {
        val values = ContentValues()
        values.put("id", id)
        values.put("name", name)
        values.put("shared", if (shared) 1 else 0)
        values.put("ts", ts)
        values.put("deleted", if (deleted) 1 else 0)
        return values
    }

    private fun trackValues(track: Track): ContentValues {
        val values = ContentValues()
        values.put("source_id", track.sourceId.take(32))
        values.put("track_id", track.id.take(128))
        values.put("title", track.title.take(120))
        values.put("artist", track.artist.take(80))
        values.put("duration_ms", track.durationMs.coerceAtLeast(0L))
        values.put("extra", track.extra.take(4_000))
        return values
    }

    private fun readTrack(c: Cursor): Track = Track(
        id = c.getString(1),
        title = c.getString(2).orEmpty(),
        artist = c.getString(3).orEmpty(),
        durationMs = c.getLong(4).coerceAtLeast(0L),
        sourceId = c.getString(0),
        extra = c.getString(5).orEmpty(),
    )

    private fun encode(track: Track?): String {
        if (track == null) return ""
        return JSONObject()
            .put("id", track.id.take(128))
            .put("title", track.title.take(120))
            .put("artist", track.artist.take(80))
            .put("dur", track.durationMs.coerceAtLeast(0L))
            .put("src", track.sourceId.take(32))
            .put("extra", track.extra.take(4_000))
            .toString()
    }

    private fun decode(raw: String): Track? {
        if (raw.isBlank() || raw.length > 8_000) return null
        return try {
            val obj = JSONObject(raw)
            val id = obj.optString("id").take(128)
            val source = obj.optString("src").take(32)
            if (id.isBlank() || source.isBlank()) null
            else Track(
                id = id,
                title = obj.optString("title").take(120),
                artist = obj.optString("artist").take(80),
                durationMs = obj.optLong("dur").coerceAtLeast(0L),
                sourceId = source,
                extra = obj.optString("extra").take(4_000),
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun meta(context: Context, key: String): String? = read(context, null) { db -> getMeta(db, key) }

    private fun writeMeta(context: Context, key: String, value: String) {
        write(context) { db ->
            putMeta(db, key, value)
            true
        }
    }

    private fun getMeta(db: SQLiteDatabase, key: String): String? {
        db.rawQuery("SELECT v FROM meta WHERE k = ? LIMIT 1", arrayOf(key)).use { c ->
            return if (c.moveToFirst()) c.getString(0) else null
        }
    }

    private fun putMeta(db: SQLiteDatabase, key: String, value: String) {
        val values = ContentValues()
        values.put("k", key)
        values.put("v", value)
        db.insertWithOnConflict("meta", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    private fun idOk(id: String): Boolean =
        id.length in 16..32 && id.all { it in '0'..'9' || it in 'a'..'f' }

    private fun clipName(name: String): String {
        val clean = name.replace('\n', ' ').replace('\r', ' ').trim()
        return if (clean.length <= 80) clean else clean.substring(0, 80)
    }

    private fun <T> read(context: Context, fallback: T, block: (SQLiteDatabase) -> T): T =
        try {
            MusicDb.use(context, block)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            fallback
        }

    private fun write(context: Context, block: (SQLiteDatabase) -> Boolean): Boolean =
        try {
            MusicDb.use(context, block)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
}
