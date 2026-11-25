package app.loviedowpie.music

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.coroutines.cancellation.CancellationException

/**
 * Songs already on this phone, via [MediaStore.Audio].
 * Search filters those rows. It does not download anything.
 *
 * DATA-FLOW: none. Titles and durations are read from this phone's media store.
 * Bytes leave the phone only later, and only through [app.loviedowpie.FileTransfer].
 */
class LocalSource(private val context: Context) : MusicSource {
    override val id: String = ID
    override val displayName: String = "Local"

    override suspend fun search(query: String): List<Track> = withContext(Dispatchers.IO) {
        if (!permitted(context)) return@withContext emptyList()
        val collection = collectionUri()
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.SIZE,
        )
        val trimmed = query.trim().replace("%", "").replace("_", "").take(80)
        val selection: String
        val args: Array<String>?
        if (trimmed.isEmpty()) {
            selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
            args = null
        } else {
            selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND (" +
                "${MediaStore.Audio.Media.TITLE} LIKE ? OR ${MediaStore.Audio.Media.ARTIST} LIKE ?)"
            val like = "%$trimmed%"
            args = arrayOf(like, like)
        }
        val sort = "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"
        val out = ArrayList<Track>(64)
        try {
            context.contentResolver.query(collection, projection, selection, args, sort)?.use { cursor ->
                val idCol = cursor.getColumnIndex(MediaStore.Audio.Media._ID)
                val titleCol = cursor.getColumnIndex(MediaStore.Audio.Media.TITLE)
                val artistCol = cursor.getColumnIndex(MediaStore.Audio.Media.ARTIST)
                val durCol = cursor.getColumnIndex(MediaStore.Audio.Media.DURATION)
                val sizeCol = cursor.getColumnIndex(MediaStore.Audio.Media.SIZE)
                if (idCol < 0) return@use
                while (cursor.moveToNext() && out.size < MAX_RESULTS) {
                    val mediaId = cursor.getLong(idCol)
                    val uri = ContentUris.withAppendedId(collection, mediaId)
                    val title = if (titleCol >= 0) cursor.getString(titleCol).orEmpty() else ""
                    val artist = if (artistCol >= 0) cursor.getString(artistCol).orEmpty() else ""
                    val duration = if (durCol >= 0) cursor.getLong(durCol) else 0L
                    val size = if (sizeCol >= 0) cursor.getLong(sizeCol) else -1L
                    out.add(
                        Track(
                            id = mediaId.toString(),
                            title = title.ifBlank { "" },
                            artist = artist.cleanArtist(),
                            durationMs = duration.coerceAtLeast(0L),
                            sourceId = ID,
                            extra = TrackExtra.local(uri.toString(), size),
                        ),
                    )
                }
            }
            out
        } catch (e: CancellationException) {
            throw e
        } catch (_: SecurityException) {
            emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    override suspend fun resolveStreamUrl(track: Track): String? {
        TrackExtra.uri(track.extra)?.let { return it }
        val mediaId = track.id.toLongOrNull() ?: return null
        return ContentUris.withAppendedId(collectionUri(), mediaId).toString()
    }

    private fun collectionUri(): Uri =
        if (Build.VERSION.SDK_INT >= 29) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }

    companion object {
        const val ID = "local"
        private const val MAX_RESULTS = 200

        fun permission(): String =
            if (Build.VERSION.SDK_INT >= 33) {
                Manifest.permission.READ_MEDIA_AUDIO
            } else {
                Manifest.permission.READ_EXTERNAL_STORAGE
            }

        fun permitted(context: Context): Boolean =
            ContextCompat.checkSelfPermission(context, permission()) == PackageManager.PERMISSION_GRANTED
    }
}

internal sealed class LocalRead {
    class Ok(val bytes: ByteArray) : LocalRead()
    data object TooBig : LocalRead()
    data object Failed : LocalRead()
}

/** Reads a media-store URI this app is allowed to open. Caps the size before sharing. */
internal object LocalAudio {
    const val MAX_SHARE = 20 * 1024 * 1024

    fun canRead(context: Context, uriString: String): Boolean {
        if (!uriString.startsWith("content://media/")) return false
        if (!LocalSource.permitted(context)) return false
        return try {
            context.contentResolver.openAssetFileDescriptor(Uri.parse(uriString), "r")?.use { desc ->
                desc.length != 0L
            } == true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }

    fun read(context: Context, uri: Uri, max: Int): LocalRead {
        if (uri.scheme != "content") return LocalRead.Failed
        return try {
            val declared = context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
            if (declared > max) return LocalRead.TooBig
            context.contentResolver.openInputStream(uri)?.use { input ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(8192)
                var total = 0
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > max) return LocalRead.TooBig
                    out.write(buf, 0, n)
                }
                val bytes = out.toByteArray()
                if (bytes.isEmpty()) LocalRead.Failed else LocalRead.Ok(bytes)
            } ?: LocalRead.Failed
        } catch (e: CancellationException) {
            throw e
        } catch (_: OutOfMemoryError) {
            LocalRead.Failed
        } catch (_: SecurityException) {
            LocalRead.Failed
        } catch (_: Exception) {
            LocalRead.Failed
        }
    }
}

private fun String.cleanArtist(): String {
    val trimmed = trim()
    if (trimmed.isEmpty() || trimmed.equals("<unknown>", ignoreCase = true)) return ""
    return trimmed
}
