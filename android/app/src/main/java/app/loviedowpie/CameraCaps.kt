@file:OptIn(androidx.camera.camera2.interop.ExperimentalCamera2Interop::class)

package app.loviedowpie

import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.media.MediaCodec
import android.media.MediaRecorder
import android.util.Range
import android.util.Size
import android.view.SurfaceHolder
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import android.hardware.camera2.params.StreamConfigurationMap
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * What this phone's camera can actually capture.
 *
 * Video fps is not guessed from the model name. 60fps is offered only when a
 * size for that quality can do it: either a high-speed range that contains 60,
 * or a normal stream whose minimum frame time is about 60fps and whose AE
 * target ranges also contain 60. Otherwise the quality is listed at 30fps,
 * which is what CameraX records by default.
 *
 * Both 30 and 60 are listed when 60 is real, so a phone with 1080p60 can still
 * default to 1080p30 for chat.
 */
object CameraCaps {
    data class PhotoChoice(val width: Int, val height: Int, val label: String) {
        val pixels: Long get() = width.toLong() * height.toLong()
    }

    data class VideoChoice(val quality: Quality, val fps: Int, val label: String)

    /** The camera [CameraSelector] of this facing would open, or null. */
    fun find(provider: ProcessCameraProvider, facing: Int): CameraInfo? {
        return try {
            val selector = CameraSelector.Builder().requireLensFacing(facing).build()
            selector.filter(provider.availableCameraInfos).firstOrNull()
        } catch (_: Exception) {
            null
        }
    }

    fun photoChoices(info: CameraInfo): List<PhotoChoice> {
        return try {
            val map = streamMap(info) ?: return emptyList()
            val sizes = map.getOutputSizes(ImageFormat.JPEG) ?: return emptyList()
            pickPhotoSizes(sizes)
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun videoChoices(info: CameraInfo): List<VideoChoice> {
        return try {
            val qualities = try {
                QualitySelector.getSupportedQualities(info)
            } catch (_: Exception) {
                emptyList()
            }
            if (qualities.isEmpty()) return emptyList()
            val map = streamMap(info)
            val ae = aeRanges(info)
            val out = ArrayList<VideoChoice>(8)
            for (quality in ORDER) {
                if (qualities.none { it == quality }) continue
                val name = qualityLabel(quality)
                if (name.isEmpty()) continue
                if (canRecord60(quality, map, ae)) {
                    out.add(VideoChoice(quality, 60, "$name 60fps"))
                }
                out.add(VideoChoice(quality, 30, "$name 30fps"))
            }
            out
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Closest still size to 12 megapixels, which keeps a chat photo small. */
    fun defaultPhoto(options: List<PhotoChoice>): PhotoChoice? {
        if (options.isEmpty()) return null
        return options.minByOrNull { abs(it.pixels - TWELVE_MP) }
    }

    /** 1080p at 30fps when this camera has it. */
    fun defaultVideo(options: List<VideoChoice>): VideoChoice? {
        if (options.isEmpty()) return null
        options.firstOrNull { it.quality == Quality.FHD && it.fps == 30 }?.let { return it }
        options.firstOrNull { it.quality == Quality.FHD }?.let { return it }
        options.firstOrNull { it.fps == 30 }?.let { return it }
        return options.firstOrNull()
    }

    fun qualityKey(quality: Quality): String = when (quality) {
        Quality.UHD -> "UHD"
        Quality.FHD -> "FHD"
        Quality.HD -> "HD"
        Quality.SD -> "SD"
        else -> ""
    }

    fun qualityLabel(quality: Quality): String = when (quality) {
        Quality.UHD -> "4K"
        Quality.FHD -> "1080p"
        Quality.HD -> "720p"
        Quality.SD -> "480p"
        else -> ""
    }

    private fun streamMap(info: CameraInfo): StreamConfigurationMap? {
        return try {
            Camera2CameraInfo.from(info)
                .getCameraCharacteristic(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        } catch (_: Exception) {
            null
        }
    }

    private fun aeRanges(info: CameraInfo): Array<Range<Int>>? {
        return try {
            Camera2CameraInfo.from(info)
                .getCameraCharacteristic(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
        } catch (_: Exception) {
            null
        }
    }

    private fun canRecord60(quality: Quality, map: StreamConfigurationMap?, ae: Array<Range<Int>>?): Boolean {
        if (map == null) return false
        val sizes = (videoOutputSizes(map) + highSpeedSizes(map))
            .distinctBy { it.width to it.height }
            .filter { matchesQuality(it, quality) }
        for (size in sizes) {
            if (highSpeedContains(map, size, 60)) return true
            if (aeContains(ae, 60) && frameTimeAllows(map, size, 60)) return true
        }
        return false
    }

    private fun videoOutputSizes(map: StreamConfigurationMap): List<Size> {
        val classes = listOf(MediaRecorder::class.java, MediaCodec::class.java, SurfaceHolder::class.java)
        for (klass in classes) {
            val sizes = try {
                map.getOutputSizes(klass)
            } catch (_: Exception) {
                null
            }
            if (!sizes.isNullOrEmpty()) return sizes.toList()
        }
        return emptyList()
    }

    private fun highSpeedSizes(map: StreamConfigurationMap): List<Size> {
        return try {
            map.highSpeedVideoSizes?.toList().orEmpty()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun highSpeedContains(map: StreamConfigurationMap, size: Size, fps: Int): Boolean {
        val ranges = try {
            map.getHighSpeedVideoFpsRangesFor(size)
        } catch (_: Exception) {
            null
        } ?: return false
        if (ranges.any { it.lower <= fps && it.upper >= fps }) return true
        // Some devices only list the range globally and the sizes per range.
        val all = try {
            map.highSpeedVideoFpsRanges
        } catch (_: Exception) {
            null
        } ?: return false
        for (range in all) {
            if (range.lower > fps || range.upper < fps) continue
            val sized = try {
                map.getHighSpeedVideoSizesFor(range)
            } catch (_: Exception) {
                null
            } ?: continue
            if (sized.any { it.width == size.width && it.height == size.height }) return true
        }
        return false
    }

    /**
     * Minimum frame duration for this size. Reject absurd values (0, or under 1ms):
     * those mean "unknown", not "infinitely fast".
     */
    private fun frameTimeAllows(map: StreamConfigurationMap, size: Size, fps: Int): Boolean {
        val duration = minFrameDurationNs(map, size)
        if (duration < 1_000_000L) return false
        val limit = (1_000_000_000.0 / fps * 1.1).toLong()
        return duration <= limit
    }

    private fun minFrameDurationNs(map: StreamConfigurationMap, size: Size): Long {
        val fromClass = listOf(MediaRecorder::class.java, MediaCodec::class.java)
        for (klass in fromClass) {
            val duration = try {
                map.getOutputMinFrameDuration(klass, size)
            } catch (_: Exception) {
                0L
            }
            if (duration > 0L) return duration
        }
        return try {
            map.getOutputMinFrameDuration(ImageFormat.PRIVATE, size)
        } catch (_: Exception) {
            0L
        }
    }

    private fun aeContains(ranges: Array<Range<Int>>?, fps: Int): Boolean {
        if (ranges.isNullOrEmpty()) return false
        return ranges.any { it.lower <= fps && it.upper >= fps }
    }

    private fun matchesQuality(size: Size, quality: Quality): Boolean {
        val spec = specOf(quality) ?: return false
        val short = min(size.width, size.height)
        val long = max(size.width, size.height)
        val specShort = min(spec.width, spec.height)
        val specLong = max(spec.width, spec.height)
        if (specShort <= 0 || specLong <= 0) return false
        // 20% on the short side, 25% on the long side. 1920x1088 still counts as 1080p.
        val shortOk = abs(short - specShort) * 5 <= specShort
        val longOk = abs(long - specLong) * 4 <= specLong
        return shortOk && longOk
    }

    private fun specOf(quality: Quality): Size? = when (quality) {
        Quality.UHD -> Size(3840, 2160)
        Quality.FHD -> Size(1920, 1080)
        Quality.HD -> Size(1280, 720)
        Quality.SD -> Size(720, 480)
        else -> null
    }

    private fun pickPhotoSizes(sizes: Array<Size>): List<PhotoChoice> {
        val pool = sizes
            .filter { it.width >= 640 && it.height >= 480 }
            .distinctBy { it.width to it.height }
            .sortedByDescending { it.pixels }
        if (pool.isEmpty()) return emptyList()
        val picked = ArrayList<Size>(5)
        fun add(size: Size?) {
            if (size == null || picked.size >= 5) return
            if (picked.any { sameBand(it, size) }) return
            picked.add(size)
        }
        add(pool.first())
        for (target in longArrayOf(50_000_000L, 12_000_000L, 8_000_000L, 3_000_000L)) {
            val candidate = pool
                .filter { it.pixels <= target * 5 / 4 }
                .minByOrNull { abs(it.pixels - target) }
                ?: continue
            val miss = abs(candidate.pixels - target).toDouble() / target.toDouble()
            if (miss > 0.40) continue
            val sibling = pool
                .filter { sameBand(it, candidate) }
                .filter { abs(it.pixels - target).toDouble() / target.toDouble() <= 0.40 }
                .minWithOrNull(compareBy<Size> { aspectDistance(it) }.thenBy { abs(it.pixels - target) })
            add(sibling ?: candidate)
        }
        return labelPhotos(picked)
    }

    private fun labelPhotos(sizes: List<Size>): List<PhotoChoice> {
        val raw = sizes.map { it to mpLabel(it.pixels) }
        val duplicated = raw.groupingBy { it.second }.eachCount().filterValues { it > 1 }.keys
        return raw.map { (size, label) ->
            val text = if (label in duplicated) "$label (${size.width}×${size.height})" else label
            PhotoChoice(size.width, size.height, text)
        }
    }

    private fun mpLabel(pixels: Long): String {
        val mp = pixels / 1_000_000.0
        val text = if (mp >= 10.0) {
            mp.roundToInt().toString()
        } else {
            val tenths = (mp * 10.0).roundToInt() / 10.0
            val whole = tenths.toInt()
            if (abs(tenths - whole) < 0.05) whole.toString() else String.format(Locale.US, "%.1f", tenths)
        }
        return "$text MP"
    }

    /** Within 12% pixel count: one entry per band, not every nearby JPEG size. */
    private fun sameBand(a: Size, b: Size): Boolean {
        val bigger = max(a.pixels, b.pixels).toDouble()
        if (bigger <= 0.0) return true
        return abs(a.pixels - b.pixels).toDouble() / bigger < 0.12
    }

    private fun aspectDistance(size: Size): Float {
        val longSide = max(size.width, size.height).toFloat()
        val shortSide = min(size.width, size.height).coerceAtLeast(1).toFloat()
        return abs(longSide / shortSide - 4f / 3f)
    }

    private val Size.pixels: Long
        get() = width.toLong() * height.toLong()

    private val ORDER = listOf(Quality.UHD, Quality.FHD, Quality.HD, Quality.SD)
    private const val TWELVE_MP = 12_000_000L
}
