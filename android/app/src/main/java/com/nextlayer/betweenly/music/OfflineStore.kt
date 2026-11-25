package com.nextlayer.betweenly.music

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import com.nextlayer.betweenly.FileTransfer
import kotlinx.coroutines.yield
import java.io.File
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import kotlin.coroutines.cancellation.CancellationException

internal enum class PinResult { Saved, Already, Full, Failed }

/**
 * Songs kept in `filesDir/music/offline` so they play with no network.
 * Local library files are not copied: they already live on the phone.
 * The folder is separate from the listen-together invite file.
 *
 * DATA-FLOW: see [pin]. A local song never calls the network from here.
 */
internal object OfflineStore {
    fun used(context: Context): Long {
        val dir = folder(context, create = false)
        val files = dir.listFiles() ?: return 0L
        var total = 0L
        for (file in files) {
            if (file.isFile) total += file.length()
        }
        return total
    }

    fun keys(context: Context): Set<String> = try {
        MusicDb.use(context) { db ->
            val out = HashSet<String>()
            db.rawQuery("SELECT source_id, track_id FROM offline LIMIT 500", null).use { c ->
                while (c.moveToNext()) out.add(c.getString(0) + "\u0000" + c.getString(1))
            }
            out
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        emptySet()
    }

    fun tracks(context: Context): List<Track> = try {
        MusicDb.use(context) { db ->
            val sql = "SELECT source_id, track_id, title, artist, duration_ms, extra " +
                "FROM offline ORDER BY title COLLATE NOCASE LIMIT 500"
            val out = ArrayList<Track>()
            db.rawQuery(sql, null).use { c ->
                while (c.moveToNext()) {
                    out.add(
                        Track(
                            id = c.getString(1),
                            title = c.getString(2).orEmpty(),
                            artist = c.getString(3).orEmpty(),
                            durationMs = c.getLong(4).coerceAtLeast(0L),
                            sourceId = c.getString(0),
                            extra = c.getString(5).orEmpty(),
                        ),
                    )
                }
            }
            out
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        emptyList()
    }

    fun peekFile(context: Context, track: Track): File? = try {
        val name = MusicDb.use(context) { db -> fileNameOf(db, track) } ?: return null
        file(context, name)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    fun clear(context: Context) {
        try {
            MusicDb.use(context) { db -> db.delete("offline", null, null) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // The files below are still removed.
        }
        val dir = folder(context, create = false)
        val files = dir.listFiles() ?: return
        for (file in files) {
            try {
                file.delete()
            } catch (_: Exception) {
                // A file still open is removed the next time.
            }
        }
    }

    suspend fun pin(context: Context, track: Track, resolve: suspend () -> String?): PinResult {
        if (track.id.isBlank() || track.sourceId.isBlank()) return PinResult.Failed
        if (track.sourceId == LocalSource.ID) return PinResult.Already
        peekFile(context, track)?.let { return PinResult.Already }
        val room = (MusicLibrary.offlineCap(context) - used(context)).coerceAtLeast(0L)
        if (room <= 0L) return PinResult.Full
        val name = storageName(track)
        val dest = file(context, name) ?: return PinResult.Failed
        return try {
            val cached = TrackExtra.fid(track.extra)?.let { MusicCache.existing(context, it) }
            if (cached != null) {
                val source = File(cached)
                if (allowed(context, source)) {
                    return copyFile(context, track, source, dest, name, room)
                }
            }
            val fid = TrackExtra.fid(track.extra)
            val key = TrackExtra.key(track.extra)
            if (fid != null && key != null) {
                val bytes = try {
                    // DATA-FLOW: encrypted audio ciphertext, GET relay /file/{fid}, for a song the user asked to keep offline. Decrypted on this phone with the key from the sealed message. The relay stored ciphertext only. The copy stays in app-private storage.
                    FileTransfer.download(context, fid, key)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
                return writeBytes(context, track, bytes, dest, name, room)
            }
            val url = try {
                resolve()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            } ?: return PinResult.Failed
            when {
                url.startsWith("content://media/") -> PinResult.Already
                url.startsWith("https://") -> streamHttps(context, track, url, dest, name, room)
                url.startsWith("file://") -> {
                    val source = File(Uri.parse(url).path.orEmpty())
                    if (!allowed(context, source)) PinResult.Failed
                    else copyFile(context, track, source, dest, name, room)
                }
                url.startsWith("/") -> {
                    val source = File(url)
                    if (!allowed(context, source)) PinResult.Failed
                    else copyFile(context, track, source, dest, name, room)
                }
                else -> PinResult.Failed
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            dest.delete()
            PinResult.Failed
        }
    }

    private fun copyFile(
        context: Context,
        track: Track,
        source: File,
        dest: File,
        name: String,
        room: Long,
    ): PinResult {
        if (!source.isFile) return PinResult.Failed
        val size = source.length()
        if (size <= 0L) return PinResult.Failed
        if (size > room) return PinResult.Full
        val part = File(dest.parentFile, "$name.part")
        return try {
            source.inputStream().use { input ->
                part.outputStream().use { out -> input.copyTo(out) }
            }
            if (!part.renameTo(dest)) {
                part.copyTo(dest, overwrite = true)
                part.delete()
            }
            remember(context, track, dest.length(), name)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            part.delete()
            PinResult.Failed
        }
    }

    private fun writeBytes(
        context: Context,
        track: Track,
        bytes: ByteArray?,
        dest: File,
        name: String,
        room: Long,
    ): PinResult {
        if (bytes == null || bytes.isEmpty()) return PinResult.Failed
        if (bytes.size.toLong() > room) return PinResult.Full
        val part = File(dest.parentFile, "$name.part")
        return try {
            part.writeBytes(bytes)
            if (!part.renameTo(dest)) {
                part.copyTo(dest, overwrite = true)
                part.delete()
            }
            remember(context, track, dest.length(), name)
        } catch (e: CancellationException) {
            throw e
        } catch (_: OutOfMemoryError) {
            part.delete()
            PinResult.Failed
        } catch (_: Exception) {
            part.delete()
            PinResult.Failed
        }
    }

    private suspend fun streamHttps(
        context: Context,
        track: Track,
        url: String,
        dest: File,
        name: String,
        room: Long,
    ): PinResult {
        if (!url.startsWith("https://")) return PinResult.Failed
        val part = File(dest.parentFile, "$name.part")
        // DATA-FLOW: user-requested offline copy of an https URL from a registered MusicSource. The built-in library does not call this. Bytes stay in app-private storage. The relay is not contacted. Redirects are not followed.
        val conn = (URL(url).openConnection() as HttpsURLConnection).apply {
            instanceFollowRedirects = false
            connectTimeout = 20_000
            readTimeout = 60_000
            setRequestProperty("User-Agent", "Betweenly")
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) return PinResult.Failed
            val declared = conn.contentLengthLong
            if (declared > room) return PinResult.Full
            conn.inputStream.use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(8192)
                    var total = 0L
                    var since = 0
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > room) {
                            part.delete()
                            return PinResult.Full
                        }
                        out.write(buf, 0, n)
                        since += n
                        if (since >= 65_536) {
                            since = 0
                            yield()
                        }
                    }
                    if (total <= 0L) return PinResult.Failed
                }
            }
            if (!part.renameTo(dest)) {
                part.copyTo(dest, overwrite = true)
                part.delete()
            }
            return remember(context, track, dest.length(), name)
        } catch (e: CancellationException) {
            part.delete()
            throw e
        } catch (_: Exception) {
            part.delete()
            return PinResult.Failed
        } finally {
            conn.disconnect()
        }
    }

    private fun remember(context: Context, track: Track, bytes: Long, name: String): PinResult {
        if (bytes <= 0L) return PinResult.Failed
        val values = ContentValues()
        values.put("source_id", track.sourceId.take(32))
        values.put("track_id", track.id.take(128))
        values.put("title", track.title.take(120))
        values.put("artist", track.artist.take(80))
        values.put("duration_ms", track.durationMs.coerceAtLeast(0L))
        values.put("extra", TrackExtra.forWire(track.extra).ifBlank { track.extra.take(4_000) })
        values.put("bytes", bytes)
        values.put("file_name", name)
        MusicDb.use(context) { db ->
            db.insertWithOnConflict("offline", null, values, SQLiteDatabase.CONFLICT_REPLACE)
        }
        return PinResult.Saved
    }

    private fun fileNameOf(db: SQLiteDatabase, track: Track): String? {
        db.rawQuery(
            "SELECT file_name FROM offline WHERE source_id = ? AND track_id = ? LIMIT 1",
            arrayOf(track.sourceId, track.id),
        ).use { c ->
            if (!c.moveToFirst()) return null
            return safeName(c.getString(0))
        }
    }

    private fun storageName(track: Track): String {
        TrackExtra.fid(track.extra)?.let { return it }
        var hash = 1125899906842597L
        for (ch in track.key()) hash = hash * 31 + ch.code
        return hash.toULong().toString(16)
    }

    private fun safeName(name: String?): String? {
        if (name == null || name.length !in 1..64) return null
        if (name.any { it !in '0'..'9' && it !in 'a'..'f' }) return null
        return name
    }

    private fun file(context: Context, name: String): File? {
        val safe = safeName(name) ?: return null
        val root = folder(context, create = true).canonicalFile
        val target = File(root, safe).canonicalFile
        val prefix = root.path + File.separator
        if (!target.path.startsWith(prefix)) return null
        return target
    }

    private fun allowed(context: Context, source: File): Boolean {
        if (!source.isFile) return false
        val target = try {
            source.canonicalFile
        } catch (_: Exception) {
            return false
        }
        val cache = context.applicationContext.cacheDir.canonicalFile.path + File.separator
        val files = context.applicationContext.filesDir.canonicalFile.path + File.separator
        val path = target.path
        return path.startsWith(cache) || path.startsWith(files)
    }

    private fun folder(context: Context, create: Boolean): File {
        val dir = File(File(context.applicationContext.filesDir, "music"), "offline")
        if (create && !dir.exists()) dir.mkdirs()
        return dir
    }
}
