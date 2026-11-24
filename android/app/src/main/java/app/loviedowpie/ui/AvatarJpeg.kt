package app.loviedowpie.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import app.loviedowpie.Store
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.min

/**
 * Turns a photo the person picked into a small JPEG the profile message can carry.
 * Center-crops to 256×256 and compresses at quality 80. If that is still over
 * [Store.MAX_AVATAR_BYTES], quality is lowered until it fits.
 *
 * DATA-FLOW: reads a local image. Nothing is uploaded here. The bytes are stored
 * with [Store.setMyAvatar] and later sealed by [app.loviedowpie.Outbox.sendProfile].
 */
object AvatarJpeg {
    private const val EDGE = 256

    suspend fun fromUri(context: Context, uri: Uri): ByteArray? = withContext(Dispatchers.IO) {
        try {
            encode(context.applicationContext, uri)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } catch (_: OutOfMemoryError) {
            null
        }
    }

    private fun encode(context: Context, uri: Uri): ByteArray? {
        val orientation = readOrientation(context, uri)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val sample = sampleSize(bounds.outWidth, bounds.outHeight)
        val decoded = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val sampled = context.contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, decoded)
        } ?: return null
        val trash = ArrayList<Bitmap>(3)
        trash.add(sampled)
        return try {
            val rotated = rotate(sampled, orientation)
            if (rotated !== sampled) trash.add(rotated)
            val cropped = centerCrop(rotated, EDGE)
            if (cropped !== rotated) trash.add(cropped)
            var quality = 80
            var bytes = compress(cropped, quality)
            while (bytes != null && bytes.size > Store.MAX_AVATAR_BYTES && quality > 40) {
                quality -= 10
                bytes = compress(cropped, quality)
            }
            bytes?.takeIf { it.isNotEmpty() && it.size <= Store.MAX_AVATAR_BYTES }
        } finally {
            val seen = HashSet<Bitmap>()
            for (bitmap in trash) {
                if (seen.add(bitmap) && !bitmap.isRecycled) {
                    try {
                        bitmap.recycle()
                    } catch (_: Exception) {
                        // Already recycled.
                    }
                }
            }
        }
    }

    private fun readOrientation(context: Context, uri: Uri): Int {
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                ExifInterface(input).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL,
                )
            } ?: ExifInterface.ORIENTATION_NORMAL
        } catch (_: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }
    }

    /** Keeps the shorter side at about 512px before the final 256 crop. */
    private fun sampleSize(width: Int, height: Int): Int {
        val minSide = min(width, height).coerceAtLeast(1)
        var sample = 1
        while (minSide / (sample * 2) >= 512) sample *= 2
        return sample
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

    private fun centerCrop(src: Bitmap, size: Int): Bitmap {
        val side = min(src.width, src.height).coerceAtLeast(1)
        val x = ((src.width - side) / 2).coerceAtLeast(0)
        val y = ((src.height - side) / 2).coerceAtLeast(0)
        val square = Bitmap.createBitmap(src, x, y, side, side)
        if (square.width == size && square.height == size) return square
        return Bitmap.createScaledBitmap(square, size, size, true).also { scaled ->
            if (scaled !== square && square !== src && !square.isRecycled) square.recycle()
        }
    }

    private fun compress(bitmap: Bitmap, quality: Int): ByteArray? {
        val out = ByteArrayOutputStream()
        val ok = bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
        if (!ok) return null
        return out.toByteArray()
    }
}
