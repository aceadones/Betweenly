package com.nextlayer.betweenly.music

import android.content.Context
import com.nextlayer.betweenly.FileTransfer
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import kotlin.coroutines.cancellation.CancellationException

/**
 * Partner copies of a shared song. Files live in `cacheDir/music` and are
 * removed when the music screen closes.
 *
 * DATA-FLOW: see [download].
 */
internal object MusicCache {
    private val gates = HashMap<String, Mutex>()

    private fun gate(fid: String): Mutex = synchronized(gates) {
        gates.getOrPut(fid) { Mutex() }
    }

    fun existing(context: Context, fid: String): String? {
        val file = file(context, fid) ?: return null
        return file.takeIf { it.isFile && it.length() > 0L }?.absolutePath
    }

    suspend fun download(context: Context, fid: String, key: String): String? {
        if (!TrackExtra.isFid(fid) || key.length !in 16..200) return null
        return gate(fid).withLock {
            existing(context, fid)?.let { return@withLock it }
            val bytes = try {
                // DATA-FLOW: encrypted audio ciphertext, GET relay /file/{fid}. Decrypted on this phone with the key from the sealed music message. The relay stored ciphertext only and cannot read the song. Bytes stay in app cache.
                FileTransfer.download(context, fid, key)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            } ?: return@withLock null
            if (bytes.isEmpty() || bytes.size > LocalAudio.MAX_SHARE) return@withLock null
            val folder = File(context.cacheDir, "music")
            if (!folder.exists() && !folder.mkdirs()) return@withLock null
            val out = File(folder, fid)
            val part = File(folder, "$fid.part")
            try {
                part.writeBytes(bytes)
                if (!part.renameTo(out)) {
                    part.copyTo(out, overwrite = true)
                    part.delete()
                }
                out.takeIf { it.isFile && it.length() > 0L }?.absolutePath
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                try {
                    part.delete()
                } catch (_: Exception) {
                    // The partial file is unnamed as a song. The next open wipes the folder.
                }
                null
            }
        }
    }

    /** DATA-FLOW: none. Deletes decrypted audio from this phone's cache. */
    fun wipe(context: Context) {
        val folder = File(context.cacheDir, "music")
        val files = folder.listFiles() ?: return
        for (file in files) {
            try {
                file.delete()
            } catch (_: Exception) {
                // A file still open will be removed on a later close.
            }
        }
        synchronized(gates) { gates.clear() }
    }

    private fun file(context: Context, fid: String): File? {
        if (!TrackExtra.isFid(fid)) return null
        return File(File(context.cacheDir, "music"), fid)
    }
}
