package app.loviedowpie.ui

import android.content.Context
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import app.loviedowpie.CameraCaps

/**
 * Remembered camera choices. The file name is the SharedPreferences name "camera".
 * Nothing here is secret: a resolution and a flash mode, not a picture.
 */
internal object CameraPrefs {
    private const val FILE = "camera"
    private const val PHOTO_W = "photo_w"
    private const val PHOTO_H = "photo_h"
    private const val VIDEO_Q = "video_q"
    private const val VIDEO_FPS = "video_fps"
    private const val LENS = "lens"
    private const val FLASH = "flash"
    private const val ASKED = "asked_camera"

    fun savedLens(context: Context): Int {
        val value = prefs(context).getInt(LENS, CameraSelector.LENS_FACING_BACK)
        return if (value == CameraSelector.LENS_FACING_FRONT) {
            CameraSelector.LENS_FACING_FRONT
        } else {
            CameraSelector.LENS_FACING_BACK
        }
    }

    fun saveLens(context: Context, facing: Int) {
        prefs(context).edit().putInt(LENS, facing).apply()
    }

    fun savedFlash(context: Context): Int {
        val value = prefs(context).getInt(FLASH, ImageCapture.FLASH_MODE_OFF)
        return when (value) {
            ImageCapture.FLASH_MODE_OFF,
            ImageCapture.FLASH_MODE_ON,
            ImageCapture.FLASH_MODE_AUTO,
            -> value
            else -> ImageCapture.FLASH_MODE_OFF
        }
    }

    fun saveFlash(context: Context, mode: Int) {
        prefs(context).edit().putInt(FLASH, mode).apply()
    }

    fun cameraAsked(context: Context): Boolean = prefs(context).getBoolean(ASKED, false)

    fun setCameraAsked(context: Context) {
        prefs(context).edit().putBoolean(ASKED, true).apply()
    }

    /**
     * The saved size when this camera still has it. Otherwise the 12 MP-ish default.
     * A front camera that lacks the saved size does not overwrite the back camera's choice.
     */
    fun resolvePhoto(context: Context, options: List<CameraCaps.PhotoChoice>): CameraCaps.PhotoChoice? {
        if (options.isEmpty()) return null
        val store = prefs(context)
        val width = store.getInt(PHOTO_W, -1)
        val height = store.getInt(PHOTO_H, -1)
        if (width > 0 && height > 0) {
            options.firstOrNull { it.width == width && it.height == height }?.let { return it }
        }
        return CameraCaps.defaultPhoto(options)
    }

    fun savePhoto(context: Context, choice: CameraCaps.PhotoChoice) {
        prefs(context).edit()
            .putInt(PHOTO_W, choice.width)
            .putInt(PHOTO_H, choice.height)
            .apply()
    }

    fun resolveVideo(context: Context, options: List<CameraCaps.VideoChoice>): CameraCaps.VideoChoice? {
        if (options.isEmpty()) return null
        val store = prefs(context)
        val key = store.getString(VIDEO_Q, null)
        val fps = store.getInt(VIDEO_FPS, 30)
        if (!key.isNullOrEmpty()) {
            options.firstOrNull { CameraCaps.qualityKey(it.quality) == key && it.fps == fps }?.let { return it }
        }
        return CameraCaps.defaultVideo(options)
    }

    fun saveVideo(context: Context, choice: CameraCaps.VideoChoice) {
        val key = CameraCaps.qualityKey(choice.quality)
        if (key.isEmpty()) return
        prefs(context).edit()
            .putString(VIDEO_Q, key)
            .putInt(VIDEO_FPS, choice.fps)
            .apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
