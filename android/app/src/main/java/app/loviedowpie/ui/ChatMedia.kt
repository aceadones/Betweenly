package app.loviedowpie.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import kotlin.math.max
import kotlin.math.min

/** Bytes ready to upload, plus the fields the attachment message needs. */
class PreparedMedia(
    val bytes: ByteArray,
    val mime: String,
    val kind: String,
    val name: String,
    val width: Int,
    val height: Int,
    val thumb: String,
)

/** Outcome of reading something the user picked or the keyboard inserted. */
sealed class MediaResult {
    class Ok(val prepared: PreparedMedia) : MediaResult()
    class TooLarge(val video: Boolean) : MediaResult()
    object Failed : MediaResult()
}

/** Bytes read from a content URI, or why that read stopped. */
sealed class ReadBytes {
    class Ok(val data: ByteArray) : ReadBytes()
    object TooBig : ReadBytes()
    object Failed : ReadBytes()
}

/**
 * Turns a gallery item, a camera capture, a file, or a keyboard image into the
 * bytes we actually send. PNG and WebP from the keyboard are not re-encoded, so
 * transparency and animation survive. A PNG with transparency from the gallery
 * is sent unchanged for the same reason.
 *
 * DATA-FLOW: reads local bytes only. Nothing is uploaded here.
 */
object ChatMedia {
    const val MAX_EDGE = 2048
    const val JPEG_QUALITY = 85
    const val MAX_BYTES = 20 * 1024 * 1024
    private const val THUMB_MAX = 8 * 1024
    private const val TINY_BYTES = 300 * 1024
    private val ISO = Charset.forName("ISO-8859-1")

    fun fromUri(context: Context, uri: Uri, asVideo: Boolean, asFile: Boolean): MediaResult {
        return try {
            if (asFile) return fileFromUri(context, uri)
            val mime = context.contentResolver.getType(uri).orEmpty().lowercase()
            if (asVideo || mime.startsWith("video/")) return videoFromUri(context, uri, mime)
            if (mime == "image/gif") return gifFromUri(context, uri)
            if (mime == "image/png" || mime == "image/webp") return stillFromUri(context, uri, mime, fromKeyboard = false)
            val encoded = transcodeUri(context, uri, MAX_EDGE, JPEG_QUALITY) ?: return MediaResult.Failed
            val name = displayName(context, uri, "photo.jpg")
            val thumb = thumbBase64(encoded.bitmap)
            val result = MediaResult.Ok(
                PreparedMedia(encoded.bytes, "image/jpeg", "photo", ensureExt(name, "jpg"), encoded.width, encoded.height, thumb),
            )
            if (!encoded.bitmap.isRecycled) encoded.bitmap.recycle()
            result
        } catch (_: Exception) {
            MediaResult.Failed
        } catch (_: OutOfMemoryError) {
            MediaResult.Failed
        }
    }

    /** Keyboard sticker, GIF, or image. Original PNG, WebP, and GIF bytes are kept. */
    fun fromBytes(bytes: ByteArray, reportedMime: String): MediaResult {
        if (bytes.isEmpty()) return MediaResult.Failed
        return try {
            val mime = sniff(bytes, reportedMime)
            when {
                mime == "image/gif" || isGif(bytes) -> {
                    if (bytes.size > MAX_BYTES) return MediaResult.TooLarge(video = false)
                    val (w, h) = bounds(bytes)
                    MediaResult.Ok(PreparedMedia(bytes, "image/gif", "gif", "animation.gif", w, h, thumbFromBytes(bytes)))
                }
                mime == "image/png" || mime == "image/webp" || isPng(bytes) || isWebp(bytes) -> {
                    imageBytes(bytes, mime, fromKeyboard = true, name = "")
                }
                else -> {
                    val encoded = transcodeBytes(bytes, MAX_EDGE, JPEG_QUALITY) ?: return MediaResult.Failed
                    val thumb = thumbBase64(encoded.bitmap)
                    val result = MediaResult.Ok(
                        PreparedMedia(encoded.bytes, "image/jpeg", "photo", "photo.jpg", encoded.width, encoded.height, thumb),
                    )
                    if (!encoded.bitmap.isRecycled) encoded.bitmap.recycle()
                    result
                }
            }
        } catch (_: Exception) {
            MediaResult.Failed
        } catch (_: OutOfMemoryError) {
            MediaResult.Failed
        }
    }

    /**
     * Shared wallpaper: upright JPEG, at most 1080 px wide, quality 80.
     * DATA-FLOW: reads a local image only. Nothing is uploaded here.
     */
    fun shareWallpaperJpeg(context: Context, uri: Uri): ByteArray? {
        var bitmap: Bitmap? = null
        var scaled: Bitmap? = null
        return try {
            val decoded = decodeUri(context, uri, 1920) ?: return null
            bitmap = decoded
            val fitted = scaleToWidth(decoded, 1080)
            scaled = fitted
            var quality = 80
            var bytes = compressJpeg(fitted, quality)
            while (bytes != null && bytes.size > 2_500_000 && quality > 50) {
                quality -= 10
                bytes = compressJpeg(fitted, quality)
            }
            bytes?.takeIf { it.isNotEmpty() && it.size <= 3_000_000 }
        } catch (_: Exception) {
            null
        } catch (_: OutOfMemoryError) {
            null
        } finally {
            val fitted = scaled
            val decoded = bitmap
            if (fitted != null && fitted !== decoded && !fitted.isRecycled) fitted.recycle()
            if (decoded != null && !decoded.isRecycled) decoded.recycle()
        }
    }

    /** Wallpaper copy: upright JPEG, longest side at most 1440, no blur. */
    fun wallpaperJpeg(context: Context, uri: Uri): ByteArray? {
        val encoded = transcodeUri(context, uri, 1440, JPEG_QUALITY) ?: return null
        val bytes = encoded.bytes
        if (!encoded.bitmap.isRecycled) encoded.bitmap.recycle()
        return bytes
    }

    /** JPEG no wider or taller than [maxEdge] and no larger than [maxBytes], or null. */
    fun fitJpeg(source: ByteArray, maxEdge: Int, maxBytes: Int): ByteArray? {
        val bitmap = decodeBytes(source, maxEdge) ?: return null
        return try {
            jpegUnder(bitmap, maxBytes)
        } catch (_: Exception) {
            null
        } catch (_: OutOfMemoryError) {
            null
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    /** Bitmap for on-screen display. The caller owns it. */
    fun decodeForDisplay(bytes: ByteArray, maxEdge: Int): Bitmap? {
        return try {
            decodeBytes(bytes, maxEdge)
        } catch (_: Exception) {
            null
        } catch (_: OutOfMemoryError) {
            null
        }
    }

    fun readNow(context: Context, uri: Uri, max: Int): ReadBytes {
        val known = declaredSize(context, uri)
        if (known > max) return ReadBytes.TooBig
        var hitCap = false
        return try {
            val data = context.contentResolver.openInputStream(uri)?.use { input ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(16 * 1024)
                var total = 0
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    if (n > max - total) {
                        hitCap = true
                        return@use null
                    }
                    out.write(buf, 0, n)
                    total += n
                }
                if (total == 0) null else out.toByteArray()
            }
            when {
                hitCap -> ReadBytes.TooBig
                data == null || data.isEmpty() -> ReadBytes.Failed
                else -> ReadBytes.Ok(data)
            }
        } catch (_: Exception) {
            ReadBytes.Failed
        } catch (_: OutOfMemoryError) {
            ReadBytes.Failed
        }
    }

    /** A smaller JPEG for a thumb or link image that no longer fits the padded envelope. */
    fun shrinkJpegBase64(encoded: String): String {
        if (encoded.length > 120_000) return ""
        val bytes = try {
            Base64.decode(encoded, Base64.DEFAULT)
        } catch (_: IllegalArgumentException) {
            return ""
        }
        val smaller = fitJpeg(bytes, 160, maxOf(4 * 1024, bytes.size * 2 / 3)) ?: return ""
        if (smaller.size >= bytes.size) return ""
        return Base64.encodeToString(smaller, Base64.NO_WRAP)
    }

    private fun fileFromUri(context: Context, uri: Uri): MediaResult {
        return when (val read = readNow(context, uri, MAX_BYTES)) {
            is ReadBytes.TooBig -> MediaResult.TooLarge(video = false)
            is ReadBytes.Failed -> MediaResult.Failed
            is ReadBytes.Ok -> {
                if (read.data.isEmpty()) return MediaResult.Failed
                val mime = context.contentResolver.getType(uri).orEmpty().ifBlank { "application/octet-stream" }
                val name = displayName(context, uri, "file")
                MediaResult.Ok(PreparedMedia(read.data, cleanMime(mime), "file", name, 0, 0, ""))
            }
        }
    }

    private fun videoFromUri(context: Context, uri: Uri, mime: String): MediaResult {
        val known = declaredSize(context, uri)
        if (known > MAX_BYTES) return MediaResult.TooLarge(video = true)
        val (w, h, thumb) = videoMeta(context, uri)
        return when (val read = readNow(context, uri, MAX_BYTES)) {
            is ReadBytes.TooBig -> MediaResult.TooLarge(video = true)
            is ReadBytes.Failed -> MediaResult.Failed
            is ReadBytes.Ok -> {
                if (read.data.isEmpty()) return MediaResult.Failed
                val type = mime.ifBlank { "video/mp4" }
                val name = displayName(context, uri, "video.mp4")
                MediaResult.Ok(PreparedMedia(read.data, cleanMime(type), "video", name, w, h, thumb))
            }
        }
    }

    private fun gifFromUri(context: Context, uri: Uri): MediaResult {
        return when (val read = readNow(context, uri, MAX_BYTES)) {
            is ReadBytes.TooBig -> MediaResult.TooLarge(video = false)
            is ReadBytes.Failed -> MediaResult.Failed
            is ReadBytes.Ok -> {
                val (w, h) = bounds(read.data)
                val name = displayName(context, uri, "animation.gif")
                MediaResult.Ok(
                    PreparedMedia(read.data, "image/gif", "gif", ensureExt(name, "gif"), w, h, thumbFromBytes(read.data)),
                )
            }
        }
    }

    private fun stillFromUri(context: Context, uri: Uri, mime: String, fromKeyboard: Boolean): MediaResult {
        return when (val read = readNow(context, uri, MAX_BYTES)) {
            is ReadBytes.TooBig -> {
                val encoded = transcodeUri(context, uri, MAX_EDGE, JPEG_QUALITY) ?: return MediaResult.TooLarge(video = false)
                val thumb = thumbBase64(encoded.bitmap)
                val name = displayName(context, uri, "photo.jpg")
                val result = MediaResult.Ok(
                    PreparedMedia(encoded.bytes, "image/jpeg", "photo", ensureExt(name, "jpg"), encoded.width, encoded.height, thumb),
                )
                if (!encoded.bitmap.isRecycled) encoded.bitmap.recycle()
                result
            }
            is ReadBytes.Failed -> MediaResult.Failed
            is ReadBytes.Ok -> imageBytes(read.data, mime, fromKeyboard, displayName(context, uri, ""))
        }
    }

    private fun imageBytes(bytes: ByteArray, mime: String, fromKeyboard: Boolean, name: String): MediaResult {
        if (bytes.size > MAX_BYTES) return MediaResult.TooLarge(video = false)
        val png = mime == "image/png" || isPng(bytes)
        val webp = mime == "image/webp" || isWebp(bytes)
        val alpha = (png && pngHasAlpha(bytes)) || (webp && (webpHasAlpha(bytes) || imageHasAlpha(bytes)))
        val animated = webp && webpAnimated(bytes)
        val tiny = isTiny(bytes)
        val (w, h) = bounds(bytes)
        if (fromKeyboard && (png || webp) && (alpha || tiny || animated)) {
            val outMime = if (webp) "image/webp" else "image/png"
            val fallback = if (webp) "sticker.webp" else "sticker.png"
            return MediaResult.Ok(
                PreparedMedia(bytes, outMime, "sticker", name.ifBlank { fallback }, w, h, thumbFromBytes(bytes)),
            )
        }
        if (png && alpha) {
            return MediaResult.Ok(
                PreparedMedia(bytes, "image/png", "photo", name.ifBlank { "photo.png" }, w, h, thumbFromBytes(bytes)),
            )
        }
        if (webp && (alpha || animated)) {
            val kind = if (fromKeyboard || animated) "sticker" else "photo"
            return MediaResult.Ok(
                PreparedMedia(bytes, "image/webp", kind, name.ifBlank { "image.webp" }, w, h, thumbFromBytes(bytes)),
            )
        }
        if (fromKeyboard && (png || webp)) {
            val outMime = if (webp) "image/webp" else "image/png"
            val fallback = if (webp) "photo.webp" else "photo.png"
            return MediaResult.Ok(
                PreparedMedia(bytes, outMime, "photo", name.ifBlank { fallback }, w, h, thumbFromBytes(bytes)),
            )
        }
        val encoded = transcodeBytes(bytes, MAX_EDGE, JPEG_QUALITY) ?: return MediaResult.Failed
        val thumb = thumbBase64(encoded.bitmap)
        val result = MediaResult.Ok(
            PreparedMedia(
                encoded.bytes,
                "image/jpeg",
                "photo",
                ensureExt(name.ifBlank { "photo.jpg" }, "jpg"),
                encoded.width,
                encoded.height,
                thumb,
            ),
        )
        if (!encoded.bitmap.isRecycled) encoded.bitmap.recycle()
        return result
    }

    private class Encoded(val bytes: ByteArray, val width: Int, val height: Int, val bitmap: Bitmap)

    private fun transcodeUri(context: Context, uri: Uri, maxEdge: Int, quality: Int): Encoded? {
        val bitmap = decodeUri(context, uri, maxEdge) ?: return null
        val bytes = compressJpeg(bitmap, quality) ?: run {
            if (!bitmap.isRecycled) bitmap.recycle()
            return null
        }
        return Encoded(bytes, bitmap.width, bitmap.height, bitmap)
    }

    private fun transcodeBytes(bytes: ByteArray, maxEdge: Int, quality: Int): Encoded? {
        val bitmap = decodeBytes(bytes, maxEdge) ?: return null
        val jpeg = compressJpeg(bitmap, quality) ?: run {
            if (!bitmap.isRecycled) bitmap.recycle()
            return null
        }
        return Encoded(jpeg, bitmap.width, bitmap.height, bitmap)
    }

    private fun decodeUri(context: Context, uri: Uri, maxEdge: Int): Bitmap? {
        val orientation = try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                ExifInterface(input).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            }
        } catch (_: Exception) {
            null
        } ?: ExifInterface.ORIENTATION_NORMAL
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        try {
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        } catch (_: Exception) {
            return null
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxEdge)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = try {
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        } catch (_: Exception) {
            null
        } catch (_: OutOfMemoryError) {
            null
        } ?: return null
        return finishBitmap(decoded, orientation, maxEdge)
    }

    private fun decodeBytes(bytes: ByteArray, maxEdge: Int): Bitmap? {
        if (bytes.isEmpty()) return null
        val orientation = try {
            ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
        } catch (_: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxEdge)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = try {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        } catch (_: Exception) {
            null
        } catch (_: OutOfMemoryError) {
            null
        } ?: return null
        return finishBitmap(decoded, orientation, maxEdge)
    }

    private fun finishBitmap(src: Bitmap, orientation: Int, maxEdge: Int): Bitmap {
        val trash = ArrayList<Bitmap>(2)
        trash.add(src)
        val rotated = rotate(src, orientation)
        if (rotated !== src) trash.add(rotated)
        val longEdge = max(rotated.width, rotated.height).coerceAtLeast(1)
        val finalBitmap = if (longEdge <= maxEdge) {
            rotated
        } else {
            val scale = maxEdge.toFloat() / longEdge.toFloat()
            val w = (rotated.width * scale).toInt().coerceAtLeast(1)
            val h = (rotated.height * scale).toInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(rotated, w, h, true)
        }
        for (bitmap in trash) {
            if (bitmap !== finalBitmap && !bitmap.isRecycled) bitmap.recycle()
        }
        return finalBitmap
    }

    private fun rotate(src: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.preScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.preScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.preScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f)
                matrix.preScale(-1f, 1f)
            }
            else -> return src
        }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
    }

    private fun jpegUnder(src: Bitmap, maxBytes: Int): ByteArray? {
        var edge = max(src.width, src.height).coerceAtLeast(1)
        var current = src
        val extra = ArrayList<Bitmap>()
        try {
            var guard = 0
            while (guard < 5) {
                var quality = 80
                while (quality >= 40) {
                    val jpeg = compressJpeg(current, quality)
                    if (jpeg != null && jpeg.size <= maxBytes) return jpeg
                    quality -= 15
                }
                val next = (edge * 2 / 3).coerceAtLeast(48)
                if (next >= edge) break
                edge = next
                val scaled = scaleDown(current, edge)
                if (scaled !== current) extra.add(scaled)
                current = scaled
                guard++
            }
            return compressJpeg(current, 40)?.takeIf { it.size <= maxBytes }
        } finally {
            for (bitmap in extra) {
                if (bitmap !== src && !bitmap.isRecycled) bitmap.recycle()
            }
        }
    }

    private fun thumbBase64(src: Bitmap): String {
        var edge = min(240, max(src.width, src.height)).coerceAtLeast(1)
        var guard = 0
        while (guard < 5) {
            val scaled = if (max(src.width, src.height) <= edge) src else scaleDown(src, edge)
            try {
                var quality = 70
                while (quality >= 30) {
                    val jpeg = compressJpeg(scaled, quality)
                    if (jpeg != null && jpeg.size <= THUMB_MAX) {
                        return Base64.encodeToString(jpeg, Base64.NO_WRAP)
                    }
                    quality -= 10
                }
            } finally {
                if (scaled !== src && !scaled.isRecycled) scaled.recycle()
            }
            if (edge <= 32) break
            edge = (edge * 2 / 3).coerceAtLeast(32)
            guard++
        }
        return ""
    }

    private fun thumbFromBytes(bytes: ByteArray): String {
        val bitmap = decodeBytes(bytes, 240) ?: return ""
        return try {
            thumbBase64(bitmap)
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    private fun scaleToWidth(src: Bitmap, maxWidth: Int): Bitmap {
        if (src.width <= maxWidth || src.width <= 0) return src
        val h = (src.height * (maxWidth.toFloat() / src.width.toFloat())).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, maxWidth, h, true)
    }

    private fun scaleDown(src: Bitmap, maxEdge: Int): Bitmap {
        val longEdge = max(src.width, src.height)
        if (longEdge <= maxEdge || longEdge <= 0) return src
        val scale = maxEdge.toFloat() / longEdge.toFloat()
        val w = (src.width * scale).toInt().coerceAtLeast(1)
        val h = (src.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, w, h, true)
    }

    private fun compressJpeg(bitmap: Bitmap, quality: Int): ByteArray? {
        val out = ByteArrayOutputStream()
        val ok = bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
        if (!ok) return null
        val bytes = out.toByteArray()
        return bytes.takeIf { it.isNotEmpty() }
    }

    private fun sampleSize(width: Int, height: Int, maxEdge: Int): Int {
        var sample = 1
        val longEdge = max(width, height)
        while (longEdge / (sample * 2) > maxEdge) sample *= 2
        return sample
    }

    private fun videoMeta(context: Context, uri: Uri): Triple<Int, Int, String> {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val frame = try {
                retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            } catch (_: Exception) {
                null
            }
            val w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
                ?: frame?.width ?: 0
            val h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
                ?: frame?.height ?: 0
            val thumb = if (frame != null) thumbBase64(frame) else ""
            if (frame != null && !frame.isRecycled) frame.recycle()
            Triple(w, h, thumb)
        } catch (_: Exception) {
            Triple(0, 0, "")
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
                // The thumbnail is already taken or already empty.
            }
        }
    }

    private fun bounds(bytes: ByteArray): Pair<Int, Int> {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        return (opts.outWidth.coerceAtLeast(0)) to (opts.outHeight.coerceAtLeast(0))
    }

    private fun declaredSize(context: Context, uri: Uri): Long {
        try {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { fd ->
                if (fd.length >= 0L) return fd.length
            }
        } catch (_: Exception) {
            // Fall through to the display-name query.
        }
        return try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return@use -1L
                val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (index < 0 || cursor.isNull(index)) -1L else cursor.getLong(index)
            } ?: -1L
        } catch (_: Exception) {
            -1L
        }
    }

    private fun displayName(context: Context, uri: Uri, fallback: String): String {
        val raw = try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) null else cursor.getString(0)
            }
        } catch (_: Exception) {
            null
        }
        val clean = raw.orEmpty()
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .filter { it >= ' ' && it != '/' && it != '\\' }
            .take(80)
        return clean.ifBlank { fallback }
    }

    private fun ensureExt(name: String, ext: String): String {
        val clean = name.substringAfterLast('/').substringAfterLast('\\').ifBlank { "photo.$ext" }
        return if (clean.substringAfterLast('.', "").equals(ext, true)) clean else {
            val base = clean.substringBeforeLast('.', clean)
            "$base.$ext"
        }
    }

    private fun cleanMime(mime: String): String {
        val clean = mime.lowercase().filter { it.isLetterOrDigit() || it == '/' || it == '-' || it == '+' || it == '.' }
            .take(80)
        return clean.ifBlank { "application/octet-stream" }
    }

    private fun sniff(bytes: ByteArray, reported: String): String {
        if (isGif(bytes)) return "image/gif"
        if (isPng(bytes)) return "image/png"
        if (isWebp(bytes)) return "image/webp"
        if (isJpeg(bytes)) return "image/jpeg"
        return reported.lowercase().substringBefore(';').trim().ifBlank { "application/octet-stream" }
    }

    private fun isGif(bytes: ByteArray): Boolean {
        if (bytes.size < 6) return false
        val head = String(bytes, 0, 6, ISO)
        return head == "GIF87a" || head == "GIF89a"
    }

    private fun isPng(bytes: ByteArray): Boolean =
        bytes.size >= 8 &&
            bytes[0] == 0x89.toByte() &&
            bytes[1] == 0x50.toByte() &&
            bytes[2] == 0x4E.toByte() &&
            bytes[3] == 0x47.toByte()

    private fun isWebp(bytes: ByteArray): Boolean =
        bytes.size >= 12 &&
            String(bytes, 0, 4, ISO) == "RIFF" &&
            String(bytes, 8, 4, ISO) == "WEBP"

    private fun isJpeg(bytes: ByteArray): Boolean =
        bytes.size >= 3 &&
            bytes[0] == 0xFF.toByte() &&
            bytes[1] == 0xD8.toByte() &&
            bytes[2] == 0xFF.toByte()

    private fun pngHasAlpha(bytes: ByteArray): Boolean {
        if (!isPng(bytes) || bytes.size < 26) return false
        val colorType = bytes[25].toInt() and 0xff
        if (colorType == 4 || colorType == 6) return true
        var i = 8
        while (i + 8 <= bytes.size) {
            val len = readBe32(bytes, i)
            if (len < 0 || i + 12L + len > bytes.size) break
            val type = String(bytes, i + 4, 4, ISO)
            if (type == "tRNS") return true
            if (type == "IDAT" || type == "IEND") break
            i += 12 + len
        }
        return false
    }

    /** Chromium WebP feature bits: animation 0x02, alpha 0x10, stored in the VP8X flags byte. */
    private fun webpFlags(bytes: ByteArray): Int {
        if (!isWebp(bytes) || bytes.size < 21) return 0
        if (String(bytes, 12, 4, ISO) != "VP8X") return 0
        return bytes[20].toInt() and 0xff
    }

    private fun webpHasAlpha(bytes: ByteArray): Boolean = webpFlags(bytes) and 0x10 != 0

    private fun webpAnimated(bytes: ByteArray): Boolean {
        if (webpFlags(bytes) and 0x02 != 0) return true
        if (!isWebp(bytes)) return false
        val n = min(bytes.size, 4096)
        return String(bytes, 0, n, ISO).contains("ANIM")
    }

    private fun imageHasAlpha(bytes: ByteArray): Boolean {
        val opts = BitmapFactory.Options().apply {
            inSampleSize = 8
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bitmap = try {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        } catch (_: Exception) {
            null
        } catch (_: OutOfMemoryError) {
            null
        } ?: return false
        return try {
            bitmap.hasAlpha()
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    private fun isTiny(bytes: ByteArray): Boolean {
        if (bytes.size <= TINY_BYTES) return true
        val (w, h) = bounds(bytes)
        val edge = max(w, h)
        return edge in 1..512
    }

    private fun readBe32(bytes: ByteArray, offset: Int): Int {
        if (offset + 4 > bytes.size) return -1
        return ((bytes[offset].toInt() and 0xff) shl 24) or
            ((bytes[offset + 1].toInt() and 0xff) shl 16) or
            ((bytes[offset + 2].toInt() and 0xff) shl 8) or
            (bytes[offset + 3].toInt() and 0xff)
    }
}
