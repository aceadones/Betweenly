package app.loviedowpie

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.io.FileOutputStream

/**
 * Drawings kept in app-private storage. Encrypt-at-rest is not required:
 * the directory is [Context.getFilesDir], which other apps cannot read,
 * and backup is off.
 *
 * Layout:
 * - `filesDir/drawings/partner_latest.png` — the newest drawing we decrypted
 * - `filesDir/drawings/partner_latest.ts` — that drawing's sender timestamp
 * - `filesDir/drawings/saved/<epochMillis>.png` — drawings this phone saved
 *
 * DATA-FLOW: this object never talks to the network. Bytes here were
 * decrypted on this phone, or drawn on this phone.
 */
object DrawStore {
    private const val ROOT = "drawings"
    private const val SAVED = "saved"
    private const val PARTNER = "partner_latest.png"
    private const val PARTNER_TS = "partner_latest.ts"
    private const val MAX_PNG = 24 * 1024 * 1024

    private val lock = Any()
    private val written = MutableStateFlow(0)

    /** Increments when a drawing is saved, replaced, or deleted. */
    val changes: StateFlow<Int> = written

    fun list(context: Context): List<SavedDrawing> = synchronized(lock) {
        val dir = savedDir(context)
        val files = dir.listFiles() ?: return emptyList()
        files.mapNotNull { file ->
            val name = file.name
            if (!name.endsWith(".png")) return@mapNotNull null
            val ts = name.removeSuffix(".png").toLongOrNull() ?: return@mapNotNull null
            if (!file.isFile || file.length() <= 0L) return@mapNotNull null
            SavedDrawing(ts, file)
        }.sortedByDescending { it.ts }
    }

    /** Writes [bitmap] as `saved/<now>.png`. Returns the timestamp, or null if the disk write failed. */
    fun save(context: Context, bitmap: Bitmap): Long? = synchronized(lock) {
        try {
            val dir = savedDir(context)
            if (!dir.exists() && !dir.mkdirs()) return null
            var ts = System.currentTimeMillis()
            var dest = File(dir, "$ts.png")
            while (dest.exists()) {
                ts += 1
                dest = File(dir, "$ts.png")
            }
            if (!writePng(bitmap, dest)) return null
            bump()
            ts
        } catch (_: Exception) {
            null
        } catch (_: OutOfMemoryError) {
            null
        }
    }

    /** Deletes one saved drawing. The timestamp is the file name. */
    fun delete(context: Context, ts: Long): Boolean = synchronized(lock) {
        if (ts <= 0L) return false
        val file = File(savedDir(context), "$ts.png")
        val ok = try {
            !file.exists() || file.delete()
        } catch (_: Exception) {
            false
        }
        if (ok) bump()
        ok
    }

    /** The partner's latest drawing, or null when they have not sent one. */
    fun latestFromPartner(context: Context): File? = synchronized(lock) {
        val file = File(root(context), PARTNER)
        file.takeIf { it.isFile && it.length() > 0L }
    }

    /** This phone's newest saved drawing, or null. */
    fun myLatest(context: Context): File? = list(context).firstOrNull()?.file

    /**
     * Stores [png] as the partner's latest drawing when it is a real PNG and
     * not older than the one already on disk.
     *
     * [expectedGeneration] is [Store.generation] captured before the download.
     * A write that loses the race with [Store.unpair] is rolled back so a
     * late download cannot resurrect a drawing after unpair.
     */
    fun savePartnerLatest(
        context: Context,
        png: ByteArray,
        ts: Long,
        expectedGeneration: Int,
    ): Boolean = synchronized(lock) {
        try {
            if (Store.generation() != expectedGeneration || !Store.isPaired(context)) return false
            if (!isPng(png)) return false
            val dir = root(context)
            if (!dir.exists() && !dir.mkdirs()) return false
            val dest = File(dir, PARTNER)
            val stamp = File(dir, PARTNER_TS)
            val prev = readStamp(stamp)
            if (ts > 0L && prev > ts) return false
            if (ts <= 0L && dest.isFile && dest.length() > 0L && prev > 0L) return false
            val tmp = File(dir, "$PARTNER.tmp")
            FileOutputStream(tmp).use { it.write(png) }
            if (!replace(tmp, dest)) return false
            if (Store.generation() != expectedGeneration) {
                dest.delete()
                stamp.delete()
                return false
            }
            if (ts > 0L) {
                try {
                    stamp.writeText(ts.toString())
                } catch (_: Exception) {
                    // The png is already in place. A missing stamp only weakens ordering.
                }
            }
            bump()
            true
        } catch (_: Exception) {
            false
        }
    }

    /** Deletes partner drawings and saved drawings. */
    fun wipe(context: Context) {
        synchronized(lock) {
            try {
                File(context.applicationContext.filesDir, ROOT).deleteRecursively()
            } catch (_: Exception) {
                // Best effort. [Store.unpair] still proceeds.
            }
            bump()
        }
    }

    /**
     * Decodes [file] so its longer edge is at most [maxEdge] pixels.
     * Null when the file is missing, not an image, or memory is short.
     */
    fun decode(file: File, maxEdge: Int): Bitmap? {
        if (maxEdge <= 0 || !file.isFile) return null
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            val sample = sampleSize(bounds.outWidth, bounds.outHeight, maxEdge, maxEdge)
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            BitmapFactory.decodeFile(file.absolutePath, opts)
        } catch (_: Exception) {
            null
        } catch (_: OutOfMemoryError) {
            null
        }
    }

    internal fun isPng(bytes: ByteArray): Boolean {
        if (bytes.size < 24 || bytes.size > MAX_PNG) return false
        if (bytes[0] != 0x89.toByte() || bytes[1] != 0x50.toByte() ||
            bytes[2] != 0x4E.toByte() || bytes[3] != 0x47.toByte()
        ) {
            return false
        }
        return try {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            opts.outWidth > 0 && opts.outHeight > 0
        } catch (_: Exception) {
            false
        }
    }

    private fun writePng(bitmap: Bitmap, dest: File): Boolean {
        val tmp = File(dest.parentFile, dest.name + ".tmp")
        try {
            FileOutputStream(tmp).use { out ->
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                    tmp.delete()
                    return false
                }
            }
            return replace(tmp, dest)
        } catch (_: Exception) {
            tmp.delete()
            return false
        }
    }

    private fun replace(tmp: File, dest: File): Boolean {
        if (tmp.renameTo(dest)) return true
        return try {
            tmp.copyTo(dest, overwrite = true)
            tmp.delete()
            dest.isFile && dest.length() > 0L
        } catch (_: Exception) {
            tmp.delete()
            false
        }
    }

    private fun readStamp(file: File): Long {
        return try {
            if (!file.isFile) 0L else file.readText().trim().toLongOrNull() ?: 0L
        } catch (_: Exception) {
            0L
        }
    }

    private fun root(context: Context): File =
        File(context.applicationContext.filesDir, ROOT)

    private fun savedDir(context: Context): File {
        val dir = File(root(context), SAVED)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun bump() {
        written.update { it + 1 }
    }

    private fun sampleSize(srcW: Int, srcH: Int, reqW: Int, reqH: Int): Int {
        var sample = 1
        val width = reqW.coerceAtLeast(1)
        val height = reqH.coerceAtLeast(1)
        while (srcW / (sample * 2) >= width && srcH / (sample * 2) >= height) {
            sample *= 2
        }
        return sample
    }
}

/** One drawing this phone saved. [ts] is the file name and the sort key. */
data class SavedDrawing(val ts: Long, val file: File)
