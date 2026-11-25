package com.nextlayer.betweenly.music

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

/**
 * Embedded album art, decoded on demand and downsampled. Nothing is downloaded.
 * A miss falls back to a generated wash in the player.
 *
 * DATA-FLOW: none. Bytes are read from a content URI or an app-private file.
 */
internal object AlbumArt {
    private val cache = object : LruCache<String, Bitmap>(2 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount.coerceAtLeast(1)
    }

    fun peek(key: String): Bitmap? = cache.get(key)

    fun clear() {
        cache.evictAll()
    }

    suspend fun load(context: Context, track: Track): Bitmap? {
        val key = track.key()
        cache.get(key)?.let { return it }
        return withContext(Dispatchers.IO) {
            cache.get(key)?.let { return@withContext it }
            val bytes = embedded(context, track) ?: return@withContext null
            val bitmap = decode(bytes) ?: return@withContext null
            cache.put(key, bitmap)
            bitmap
        }
    }

    private fun embedded(context: Context, track: Track): ByteArray? {
        val retriever = MediaMetadataRetriever()
        return try {
            val uri = TrackExtra.uri(track.extra)
            when {
                uri != null -> retriever.setDataSource(context, Uri.parse(uri))
                else -> {
                    val file = OfflineStore.peekFile(context, track) ?: return null
                    retriever.setDataSource(file.absolutePath)
                }
            }
            retriever.embeddedPicture
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
                // Already released.
            }
        }
    }

    private fun decode(bytes: ByteArray): Bitmap? {
        if (bytes.isEmpty() || bytes.size > 8 * 1024 * 1024) return null
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (bounds.outWidth / sample > 384 && bounds.outHeight / sample > 384 && sample < 32) {
                sample *= 2
            }
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        } catch (e: CancellationException) {
            throw e
        } catch (_: OutOfMemoryError) {
            null
        } catch (_: Exception) {
            null
        }
    }
}
