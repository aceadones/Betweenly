package com.nextlayer.betweenly

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
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
 * - `filesDir/drawings/saved/<epochMillis>.png` — composites this phone saved
 * - `filesDir/drawings/shared/page` — current shared page number (from 1)
 * - `filesDir/drawings/shared/mine.png` — this phone's transparent layer
 * - `filesDir/drawings/shared/partner.png` — the partner's transparent layer
 * - `filesDir/drawings/shared/partner.ts` — that layer's sender timestamp
 * - `filesDir/drawings/separate.png` — this phone's private page, separate mode
 *
 * Paper colors used when compositing match the studio: White `0xFFFFFFFF`,
 * Cream `0xFFF3E6D0`, Dark `0xFF17191F`, Clear (no fill). The pref key is `paper`
 * in the `draw` preferences file.
 *
 * DATA-FLOW: this object never talks to the network. Bytes here were
 * decrypted on this phone, or drawn on this phone.
 */
object DrawStore {
    private const val ROOT = "drawings"
    private const val SAVED = "saved"
    private const val SHARED = "shared"
    private const val PARTNER = "partner_latest.png"
    private const val PARTNER_TS = "partner_latest.ts"
    private const val MINE = "mine.png"
    private const val PARTNER_LAYER = "partner.png"
    private const val LAYER_TS = "partner.ts"
    private const val PAGE = "page"
    private const val SEPARATE = "separate.png"
    private const val PREFS = "draw"
    private const val PREF_PAPER = "paper"
    private const val MAX_PNG = 24 * 1024 * 1024
    private const val MAX_PAGE = 1_000_000

    private val lock = Any()
    private val written = MutableStateFlow(0)
    private val partnerTick = MutableStateFlow(0)

    /** Increments when a drawing is saved, replaced, or deleted. */
    val changes: StateFlow<Int> = written

    /**
     * Increments when the partner's shared layer or the shared page changes.
     * A local stroke does not. The studio reloads their layer from this, not from a poll.
     */
    val partnerArrivals: StateFlow<Int> = partnerTick

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

    /** Deletes partner drawings, shared layers, and saved drawings. */
    fun wipe(context: Context) {
        synchronized(lock) {
            try {
                File(context.applicationContext.filesDir, ROOT).deleteRecursively()
            } catch (_: Exception) {
                // Best effort. [Store.unpair] still proceeds.
            }
            bumpPartner()
        }
    }

    /** Shared page number. Missing file means page 1, and does not create the file. */
    fun page(context: Context): Int = synchronized(lock) { readPage(context) }

    /** Sender time of the partner layer on the current shared page. 0 when there is none. */
    fun partnerStamp(context: Context): Long = synchronized(lock) {
        readStamp(File(sharedFolder(context), LAYER_TS))
    }

    /**
     * The partner's layer for the current shared page, or null.
     * Before a shared page exists, this is their last full drawing, so the
     * studio can show it under the first strokes on this phone.
     */
    fun sharedPartner(context: Context): File? = synchronized(lock) {
        partnerLayerFile(context)
    }

    /**
     * This phone's ink for the shared page ([shared] true) or the private page.
     * Null when that page has no strokes on disk.
     */
    fun mineFile(context: Context, shared: Boolean): File? = synchronized(lock) {
        val file = if (shared) layerFile(context, MINE) else separateFile(context)
        file.takeIf { it.isFile && it.length() > 0L }
    }

    /**
     * Writes [bitmap] as this phone's transparent layer.
     * The first shared write also records page 1 when no page file exists yet.
     */
    fun writeMine(context: Context, shared: Boolean, bitmap: Bitmap): Boolean = synchronized(lock) {
        try {
            if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) return false
            val dest = if (shared) {
                ensurePage(context)
                layerFile(context, MINE)
            } else {
                separateFile(context)
            }
            val parent = dest.parentFile
            if (parent != null && !parent.exists() && !parent.mkdirs()) return false
            val ok = writePng(bitmap, dest)
            if (ok) bump()
            ok
        } catch (_: Exception) {
            false
        } catch (_: OutOfMemoryError) {
            false
        }
    }

    /** Removes this phone's layer file for the shared page or the private page. */
    fun deleteMine(context: Context, shared: Boolean) {
        synchronized(lock) {
            val dest = if (shared) layerFile(context, MINE) else separateFile(context)
            val existed = dest.isFile
            deleteQuiet(dest)
            if (existed) bump()
        }
    }

    /**
     * Stores a partner drawing.
     *
     * [onShared] is true for `layer` = `shared` and for a draw message with no
     * `layer` field (a full drawing, treated as their layer on the current page).
     * [page] <= 0 means the current page. A higher page archives this page's
     * composite, then starts that page. A lower page is ignored.
     *
     * [expectedGeneration] is [Store.generation] captured before the download.
     * A write that loses the race with [Store.unpair] is rolled back.
     */
    fun saveIncoming(
        context: Context,
        png: ByteArray,
        ts: Long,
        page: Int,
        onShared: Boolean,
        expectedGeneration: Int,
    ): Boolean {
        if (!onShared) return savePartnerLatest(context, png, ts, expectedGeneration)
        return synchronized(lock) {
            try {
                if (Store.generation() != expectedGeneration || !Store.isPaired(context)) return false
                if (!isPng(png)) return false
                val current = readPage(context)
                val target = if (page in 1..MAX_PAGE) page else current
                if (target < current) return false
                var moved = false
                if (target > current) {
                    archiveLocked(context)
                    if (Store.generation() != expectedGeneration || !Store.isPaired(context)) return false
                    if (!writePage(context, target)) return false
                    deleteQuiet(layerFile(context, MINE))
                    deleteQuiet(layerFile(context, PARTNER_LAYER))
                    deleteQuiet(File(sharedFolder(context), LAYER_TS))
                    moved = true
                }
                val stamp = readStamp(File(sharedFolder(context), LAYER_TS))
                if (ts > 0L && stamp > ts) {
                    if (moved) bumpPartner()
                    return moved
                }
                if (ts <= 0L && stamp > 0L && layerFile(context, PARTNER_LAYER).isFile) {
                    if (moved) bumpPartner()
                    return moved
                }
                if (!writeBytes(png, layerFile(context, PARTNER_LAYER))) {
                    if (moved) bumpPartner()
                    return false
                }
                if (Store.generation() != expectedGeneration || !Store.isPaired(context)) {
                    deleteQuiet(layerFile(context, PARTNER_LAYER))
                    if (moved) bumpPartner()
                    return false
                }
                writeBytes(png, File(root(context), PARTNER))
                if (Store.generation() != expectedGeneration || !Store.isPaired(context)) {
                    deleteQuiet(layerFile(context, PARTNER_LAYER))
                    deleteQuiet(File(root(context), PARTNER))
                    if (moved) bumpPartner()
                    return false
                }
                if (ts > 0L) {
                    try {
                        File(sharedFolder(context), LAYER_TS).writeText(ts.toString())
                        File(root(context), PARTNER_TS).writeText(ts.toString())
                    } catch (_: Exception) {
                        // The png is already in place. A missing stamp only weakens ordering.
                    }
                }
                bumpPartner()
                true
            } catch (_: Exception) {
                false
            } catch (_: OutOfMemoryError) {
                false
            }
        }
    }

    /**
     * Partner asked to open [page]. Archives the current composite, clears both
     * layers, and stores [page] when it is newer than the page on disk.
     */
    fun applyRemoteNewPage(context: Context, page: Int, expectedGeneration: Int): Boolean {
        if (page !in 1..MAX_PAGE) return false
        return synchronized(lock) {
            try {
                if (Store.generation() != expectedGeneration || !Store.isPaired(context)) return false
                val current = readPage(context)
                if (page <= current) return false
                archiveLocked(context)
                if (Store.generation() != expectedGeneration || !Store.isPaired(context)) return false
                if (!writePage(context, page)) return false
                deleteQuiet(layerFile(context, MINE))
                deleteQuiet(layerFile(context, PARTNER_LAYER))
                deleteQuiet(File(sharedFolder(context), LAYER_TS))
                bumpPartner()
                true
            } catch (_: Exception) {
                false
            } catch (_: OutOfMemoryError) {
                false
            }
        }
    }

    /**
     * After a new-page message has been sent: save [composite] into Saved
     * drawings when it is non-null, then move to [newPage] and clear both layers.
     * [composite] is the caller's bitmap and is not recycled here.
     */
    fun commitNewPage(context: Context, newPage: Int, composite: Bitmap?): Boolean {
        if (newPage !in 1..MAX_PAGE) return false
        return synchronized(lock) {
            try {
                if (!Store.isPaired(context)) return false
                val current = readPage(context)
                if (newPage <= current) return false
                if (composite != null && !composite.isRecycled) {
                    save(context, composite)
                }
                if (!writePage(context, newPage)) return false
                deleteQuiet(layerFile(context, MINE))
                deleteQuiet(layerFile(context, PARTNER_LAYER))
                deleteQuiet(File(sharedFolder(context), LAYER_TS))
                bumpPartner()
                true
            } catch (_: Exception) {
                false
            } catch (_: OutOfMemoryError) {
                false
            }
        }
    }

    /**
     * Bitmap for the Activity card and the widget.
     * Shared layers win. A shared page with no strokes yet stays empty
     * (no fallback to an older picture). Before any shared page exists,
     * the partner's latest file, then this phone's newest save, is used.
     * Null means the empty state. The caller recycles the bitmap.
     *
     * DATA-FLOW: none.
     */
    fun loadPreview(context: Context, maxEdge: Int): Bitmap? {
        if (maxEdge <= 0) return null
        val kind = synchronized(lock) { previewLocked(context, maxEdge) }
        return when (kind) {
            is Preview.Ready -> kind.bitmap
            Preview.Blank -> null
            Preview.Legacy -> {
                val file = latestFromPartner(context) ?: myLatest(context) ?: return null
                decode(file, maxEdge)
            }
        }
    }

    /** True when [loadPreview] has something to decode. */
    fun hasPreview(context: Context): Boolean {
        val shared = synchronized(lock) {
            val dir = File(root(context), SHARED)
            val mine = File(dir, MINE)
            val partner = File(dir, PARTNER_LAYER)
            val pageFile = File(dir, PAGE)
            when {
                (mine.isFile && mine.length() > 0L) || (partner.isFile && partner.length() > 0L) -> true
                pageFile.isFile -> false
                else -> null
            }
        }
        if (shared != null) return shared
        return latestFromPartner(context) != null || myLatest(context) != null
    }

    /**
     * Decodes [file] and paints the current paper behind it, so a transparent
     * layer stays visible. Clear paper keeps the original alpha. The caller
     * recycles the result. [file] is not modified.
     */
    fun decodeOnPaper(context: Context, file: File, maxEdge: Int): Bitmap? {
        val decoded = decode(file, maxEdge) ?: return null
        val bg = paperFill(context)
        if (bg == null) return decoded
        val out = try {
            Bitmap.createBitmap(decoded.width.coerceAtLeast(1), decoded.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        } catch (_: OutOfMemoryError) {
            return decoded
        }
        return try {
            val canvas = Canvas(out)
            canvas.drawColor(bg)
            canvas.drawBitmap(decoded, 0f, 0f, null)
            if (!decoded.isRecycled) decoded.recycle()
            out
        } catch (_: Exception) {
            if (!out.isRecycled) out.recycle()
            decoded
        } catch (_: OutOfMemoryError) {
            if (!out.isRecycled) out.recycle()
            decoded
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

    private fun bumpPartner() {
        bump()
        partnerTick.update { it + 1 }
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

    private sealed class Preview {
        class Ready(val bitmap: Bitmap?) : Preview()
        object Blank : Preview()
        object Legacy : Preview()
    }

    private fun previewLocked(context: Context, maxEdge: Int): Preview {
        return try {
            val dir = File(root(context), SHARED)
            val mine = File(dir, MINE)
            val partner = File(dir, PARTNER_LAYER)
            val pageFile = File(dir, PAGE)
            val hasLayer = (mine.isFile && mine.length() > 0L) || (partner.isFile && partner.length() > 0L)
            when {
                hasLayer -> Preview.Ready(renderSharedLocked(context, maxEdge))
                pageFile.isFile -> Preview.Blank
                else -> Preview.Legacy
            }
        } catch (_: Exception) {
            Preview.Ready(null)
        } catch (_: OutOfMemoryError) {
            Preview.Ready(null)
        }
    }

    /** Paper, then the partner layer, then this phone's layer. Square. Caller recycles. */
    private fun renderSharedLocked(context: Context, maxEdge: Int): Bitmap? {
        val edge = maxEdge.coerceIn(1, 1024)
        val mine = layerFile(context, MINE).takeIf { it.isFile && it.length() > 0L }
        val partner = partnerLayerFile(context)
        if (mine == null && partner == null) return null
        val out = try {
            Bitmap.createBitmap(edge, edge, Bitmap.Config.ARGB_8888)
        } catch (_: OutOfMemoryError) {
            return null
        }
        return try {
            val canvas = Canvas(out)
            val bg = paperFill(context)
            if (bg == null) out.eraseColor(0) else canvas.drawColor(bg)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            val dst = Rect(0, 0, edge, edge)
            if (partner != null) drawFile(canvas, partner, edge, dst, paint)
            if (mine != null) drawFile(canvas, mine, edge, dst, paint)
            out
        } catch (_: Exception) {
            if (!out.isRecycled) out.recycle()
            null
        } catch (_: OutOfMemoryError) {
            if (!out.isRecycled) out.recycle()
            null
        }
    }

    private fun drawFile(canvas: Canvas, file: File, edge: Int, dst: Rect, paint: Paint) {
        val decoded = decode(file, edge) ?: return
        try {
            if (decoded.width > 0 && decoded.height > 0) {
                canvas.drawBitmap(decoded, null, dst, paint)
            }
        } finally {
            if (!decoded.isRecycled) decoded.recycle()
        }
    }

    /** Saves the current shared composite when either layer is on disk. Best effort. */
    private fun archiveLocked(context: Context) {
        var bmp: Bitmap? = null
        try {
            bmp = renderSharedLocked(context, 1024)
            if (bmp != null) save(context, bmp)
        } catch (_: Exception) {
            // The page still advances. A failed archive must not wedge the two phones.
        } catch (_: OutOfMemoryError) {
            // Same.
        } finally {
            val held = bmp
            if (held != null && !held.isRecycled) held.recycle()
        }
    }

    /**
     * White, cream, and dark match [com.nextlayer.betweenly.ui.PaperKind].
     * Clear paper returns null so the composite stays transparent.
     */
    private fun paperFill(context: Context): Int? {
        val name = try {
            context.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(PREF_PAPER, null)
        } catch (_: Exception) {
            null
        }
        return when (name) {
            "Cream" -> 0xFFF3E6D0.toInt()
            "Dark" -> 0xFF17191F.toInt()
            "Clear" -> null
            else -> 0xFFFFFFFF.toInt()
        }
    }

    private fun readPage(context: Context): Int {
        val file = File(sharedFolder(context), PAGE)
        val raw = try {
            if (!file.isFile) 1 else file.readText().trim().toIntOrNull() ?: 1
        } catch (_: Exception) {
            1
        }
        return raw.coerceIn(1, MAX_PAGE)
    }

    private fun ensurePage(context: Context) {
        val file = File(sharedFolder(context), PAGE)
        if (file.isFile) return
        try {
            file.writeText("1")
        } catch (_: Exception) {
            // The layer can still be stored. The page number stays 1.
        }
        // Their last drawing becomes the partner layer of page 1, once.
        val layer = File(sharedFolder(context), PARTNER_LAYER)
        if (layer.isFile && layer.length() > 0L) return
        val latest = File(root(context), PARTNER)
        if (!latest.isFile || latest.length() <= 0L) return
        try {
            latest.copyTo(layer, overwrite = false)
            val stamp = File(root(context), PARTNER_TS)
            if (stamp.isFile) {
                stamp.copyTo(File(sharedFolder(context), LAYER_TS), overwrite = false)
            }
        } catch (_: Exception) {
            // The page still starts. Their older file remains for the viewer.
        }
    }

    /**
     * Partner layer on the current page. A page that already exists and has no
     * layer file is blank (New page cleared it). Before any page file, their
     * last drawing stands in.
     */
    private fun partnerLayerFile(context: Context): File? {
        val dir = File(root(context), SHARED)
        val layer = File(dir, PARTNER_LAYER)
        if (layer.isFile && layer.length() > 0L) return layer
        if (File(dir, PAGE).isFile) return null
        return File(root(context), PARTNER).takeIf { it.isFile && it.length() > 0L }
    }

    private fun writePage(context: Context, page: Int): Boolean {
        return try {
            val file = File(sharedFolder(context), PAGE)
            file.writeText(page.coerceIn(1, MAX_PAGE).toString())
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun writeBytes(bytes: ByteArray, dest: File): Boolean {
        val parent = dest.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs()) return false
        val tmp = File(parent, dest.name + ".tmp")
        return try {
            FileOutputStream(tmp).use { it.write(bytes) }
            replace(tmp, dest)
        } catch (_: Exception) {
            tmp.delete()
            false
        }
    }

    private fun deleteQuiet(file: File) {
        try {
            if (file.exists()) file.delete()
        } catch (_: Exception) {
            // A leftover file is overwritten on the next save.
        }
    }

    private fun layerFile(context: Context, name: String): File =
        File(sharedFolder(context), name)

    private fun separateFile(context: Context): File =
        File(root(context), SEPARATE)

    private fun sharedFolder(context: Context): File {
        val dir = File(root(context), SHARED)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }
}

/** One drawing this phone saved. [ts] is the file name and the sort key. */
data class SavedDrawing(val ts: Long, val file: File)
