@file:OptIn(androidx.camera.camera2.interop.ExperimentalCamera2Interop::class)

package app.loviedowpie.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.util.Range
import android.util.Size
import android.view.Surface
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.UseCase
import androidx.camera.core.ZoomState
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Observer
import app.loviedowpie.CameraCaps
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.suspendCancellableCoroutine

internal data class BindResult(val ok: Boolean, val videoFps: Int?)

/**
 * Opens one camera at a time and writes captures under cacheDir/camera.
 * Every method is called on the main thread. Failures are reported; they do not throw.
 *
 * The bytes stay in app cache until the screen hands the file to its caller.
 */
internal class CameraSession(
    private val context: Context,
    private val onClip: (File, Boolean) -> Unit,
    private val onFailed: () -> Unit,
) {
    var attached by mutableStateOf(false)
        private set
    var noCamera by mutableStateOf(false)
        private set
    var unavailable by mutableStateOf(false)
    var ready by mutableStateOf(false)
        private set
    var recording by mutableStateOf(false)
        private set
    var busy by mutableStateOf(false)
        private set
    var stopping by mutableStateOf(false)
        private set

    var hasFlash by mutableStateOf(false)
        private set
    var flashMode by mutableIntStateOf(ImageCapture.FLASH_MODE_OFF)
    var torchOn by mutableStateOf(false)
        private set

    var exposureSupported by mutableStateOf(false)
        private set
    var exposureIndex by mutableIntStateOf(0)
        private set
    var exposureMin by mutableIntStateOf(0)
        private set
    var exposureMax by mutableIntStateOf(0)
        private set
    var exposureStep by mutableFloatStateOf(0f)
        private set

    var zoomRatio by mutableFloatStateOf(1f)
        private set
    var zoomMin by mutableFloatStateOf(1f)
        private set
    var zoomMax by mutableFloatStateOf(1f)
        private set

    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var previewUseCase: Preview? = null
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null
    private var zoomObserver: Observer<ZoomState>? = null
    private val mainExecutor = ContextCompat.getMainExecutor(context)

    private var epoch = 0
    private var stopReason = Stop.User
    private var releaseAfterStop = false
    private var wantedZoom = 1f
    private var restoreZoom = false
    private var boundFacing = -1
    private var boundKey = ""
    private var activeFps: Int? = null

    /** True when the phone has more than one camera, so the flip control can switch. */
    var canFlip by mutableStateOf(false)
        private set
    private var knownFacings: Set<Int> = emptySet()

    init {
        flashMode = CameraPrefs.savedFlash(context)
    }

    fun attach(next: ProcessCameraProvider) {
        provider = next
        refreshCameras()
        noCamera = !hasFacing(CameraSelector.LENS_FACING_BACK) &&
            !hasFacing(CameraSelector.LENS_FACING_FRONT)
        attached = true
    }

    fun markProviderMissing() {
        noCamera = false
        unavailable = true
        ready = false
        attached = true
    }

    fun hasFacing(facing: Int): Boolean {
        if (facing in knownFacings) return true
        val current = provider ?: return false
        return probe(current, facing)
    }

    /**
     * Front and back, from CameraX and from CameraManager. CameraX's hasCamera
     * can miss the second lens on emulators even when the camera exists.
     * More than one camera id still counts, so the flip control is hidden only
     * on a phone with a single camera.
     */
    fun refreshCameras() {
        val found = HashSet<Int>()
        val current = provider
        if (current != null) {
            for (info in current.availableCameraInfos) {
                when (readLens(info)) {
                    CameraSelector.LENS_FACING_FRONT -> found.add(CameraSelector.LENS_FACING_FRONT)
                    CameraSelector.LENS_FACING_BACK -> found.add(CameraSelector.LENS_FACING_BACK)
                }
            }
            if (probe(current, CameraSelector.LENS_FACING_BACK)) found.add(CameraSelector.LENS_FACING_BACK)
            if (probe(current, CameraSelector.LENS_FACING_FRONT)) found.add(CameraSelector.LENS_FACING_FRONT)
        }
        var hardwareCount = 0
        try {
            val manager = context.getSystemService(CameraManager::class.java)
            val ids = manager?.cameraIdList ?: emptyArray()
            hardwareCount = ids.size
            for (id in ids) {
                val facing = try {
                    manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING)
                } catch (_: Exception) {
                    null
                }
                when (facing) {
                    CameraCharacteristics.LENS_FACING_FRONT -> found.add(CameraSelector.LENS_FACING_FRONT)
                    CameraCharacteristics.LENS_FACING_BACK -> found.add(CameraSelector.LENS_FACING_BACK)
                }
            }
        } catch (_: Exception) {
            // No camera service. CameraX's list still applies.
        }
        if (hardwareCount >= 2 && found.size < 2) {
            found.add(CameraSelector.LENS_FACING_BACK)
            found.add(CameraSelector.LENS_FACING_FRONT)
        }
        knownFacings = found
        canFlip = CameraSelector.LENS_FACING_BACK in found && CameraSelector.LENS_FACING_FRONT in found
    }

    fun findInfo(facing: Int): CameraInfo? {
        val current = provider ?: return null
        return CameraCaps.find(current, facing)
    }

    /**
     * Binds preview plus either still capture or video. Returns null when a recording
     * is still closing and the caller should try again. A repeated call with the same
     * options does not rebuild the camera.
     */
    fun bind(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        facing: Int,
        videoMode: Boolean,
        photo: CameraCaps.PhotoChoice?,
        video: CameraCaps.VideoChoice?,
        token: Int,
    ): BindResult? {
        if (activeRecording != null || stopping) return null
        val current = provider ?: run {
            unavailable = true
            ready = false
            return BindResult(false, null)
        }
        if (!hasFacing(facing)) {
            unavailable = true
            ready = false
            return BindResult(false, null)
        }
        val key = "$token|$facing|$videoMode|${photo?.width}x${photo?.height}|${video?.label}"
        if (ready && camera != null && key == boundKey) {
            return BindResult(true, if (videoMode) activeFps else null)
        }
        if (facing != boundFacing) {
            exposureIndex = 0
            wantedZoom = 1f
            zoomRatio = 1f
            torchOn = false
            boundFacing = facing
        }
        ready = false
        val rotation = previewView.display?.rotation ?: Surface.ROTATION_0
        val selector = selectorFor(facing)
        val opened = if (videoMode) {
            openVideo(current, lifecycleOwner, selector, previewView, rotation, video)
        } else {
            openPhoto(current, lifecycleOwner, selector, previewView, rotation, photo)
        }
        if (opened == null) {
            releaseUseCases()
            unavailable = true
            return BindResult(false, null)
        }
        wire(opened, lifecycleOwner, videoMode)
        boundKey = key
        ready = true
        unavailable = false
        return BindResult(true, if (videoMode) activeFps else null)
    }

    fun onHostPause() {
        releaseAfterStop = true
        if (activeRecording != null) {
            val reason = if (stopReason == Stop.Discard) Stop.Discard else Stop.Lifecycle
            stopInternal(reason)
        } else if (!stopping) {
            releaseUseCases()
        }
    }

    /** Drop an in-flight capture. A clip already handed to the caller is not deleted here. */
    fun discard() {
        epoch++
        stopReason = Stop.Discard
        releaseAfterStop = true
        if (activeRecording != null) {
            stopInternal(Stop.Discard)
        } else if (!stopping) {
            releaseUseCases()
        }
    }

    fun updateRotation(rotation: Int) {
        try {
            previewUseCase?.targetRotation = rotation
            imageCapture?.targetRotation = rotation
            videoCapture?.targetRotation = rotation
        } catch (_: Exception) {
            // Rotation is a hint. The preview keeps the last orientation.
        }
    }

    fun focus(preview: PreviewView, x: Float, y: Float) {
        val current = camera ?: return
        if (!x.isFinite() || !y.isFinite()) return
        try {
            val point = preview.meteringPointFactory.createPoint(x, y)
            val action = FocusMeteringAction.Builder(
                point,
                FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE,
            ).setAutoCancelDuration(3, TimeUnit.SECONDS).build()
            current.cameraControl.startFocusAndMetering(action)
        } catch (_: Exception) {
            // Tap-to-focus is optional. A camera without autofocus still previews.
        }
    }

    fun pinch(factor: Float) {
        if (!factor.isFinite() || factor <= 0f) return
        if (abs(factor - 1f) < 0.005f) return
        val minRatio = zoomMin
        val maxRatio = zoomMax
        if (maxRatio <= minRatio + 0.001f) return
        val next = (zoomRatio * factor).coerceIn(minRatio, maxRatio)
        wantedZoom = next
        zoomRatio = next
        try {
            camera?.cameraControl?.setZoomRatio(next)
        } catch (_: Exception) {
            // Zoom stays at the last ratio the camera accepted.
        }
    }

    fun changeExposure(index: Int) {
        val minIndex = exposureMin
        val maxIndex = exposureMax
        val clamped = if (maxIndex > minIndex) index.coerceIn(minIndex, maxIndex) else index
        exposureIndex = clamped
        try {
            camera?.cameraControl?.setExposureCompensationIndex(clamped)
        } catch (_: Exception) {
            // The slider still shows the value. The camera keeps the previous step.
        }
    }

    fun cycleFlash(videoMode: Boolean) {
        if (!hasFlash) return
        if (videoMode) {
            setTorch(!torchOn)
            return
        }
        flashMode = when (flashMode) {
            ImageCapture.FLASH_MODE_OFF -> ImageCapture.FLASH_MODE_ON
            ImageCapture.FLASH_MODE_ON -> ImageCapture.FLASH_MODE_AUTO
            else -> ImageCapture.FLASH_MODE_OFF
        }
        try {
            imageCapture?.flashMode = flashMode
        } catch (_: Exception) {
            // The icon still changes. The next photo uses whatever flash mode stuck.
        }
    }

    fun setTorch(on: Boolean) {
        torchOn = on
        if (!hasFlash) return
        try {
            camera?.cameraControl?.enableTorch(on)
        } catch (_: Exception) {
            // The light is optional.
        }
    }

    fun takePhoto(rotation: Int) {
        if (busy || stopping || recording || activeRecording != null) return
        val capture = imageCapture ?: return
        val file = CameraFiles.newFile(context, "jpg") ?: run {
            onFailed()
            return
        }
        val startedEpoch = epoch
        busy = true
        try {
            capture.targetRotation = rotation
            capture.flashMode = flashMode
            val options = ImageCapture.OutputFileOptions.Builder(file).build()
            capture.takePicture(
                options,
                mainExecutor,
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                        busy = false
                        if (startedEpoch != epoch) {
                            runCatching { file.delete() }
                            return
                        }
                        if (file.exists() && file.length() > 0L) {
                            deliver(file, false)
                        } else {
                            runCatching { file.delete() }
                            onFailed()
                        }
                    }

                    override fun onError(exception: ImageCaptureException) {
                        busy = false
                        runCatching { file.delete() }
                        if (startedEpoch == epoch) onFailed()
                    }
                },
            )
        } catch (_: Exception) {
            busy = false
            runCatching { file.delete() }
            onFailed()
        }
    }

    @SuppressLint("MissingPermission")
    fun startVideo(rotation: Int, withAudio: Boolean) {
        if (busy || stopping || recording || activeRecording != null) return
        val capture = videoCapture ?: return
        val file = CameraFiles.newFile(context, "mp4") ?: run {
            onFailed()
            return
        }
        val startedEpoch = epoch
        try {
            capture.targetRotation = rotation
            val options = FileOutputOptions.Builder(file).build()
            val mic = withAudio && ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.RECORD_AUDIO,
            ) == PackageManager.PERMISSION_GRANTED
            var pending = capture.output.prepareRecording(context, options)
            if (mic) pending = pending.withAudioEnabled()
            stopReason = Stop.User
            busy = true
            // Finalize can run inside start() before the handle is returned. Don't keep
            // that handle, or the shutter stays stuck on a recording that already ended.
            val finished = booleanArrayOf(false)
            val handle = pending.start(mainExecutor) { event ->
                when (event) {
                    is VideoRecordEvent.Start -> {
                        if (startedEpoch == epoch && !stopping && !finished[0]) {
                            busy = false
                            recording = true
                        }
                    }
                    is VideoRecordEvent.Finalize -> {
                        finished[0] = true
                        finishVideo(event, file, startedEpoch)
                    }
                    else -> Unit
                }
            }
            if (!finished[0]) {
                activeRecording = handle
                if (startedEpoch != epoch || stopReason == Stop.Discard) {
                    stopInternal(Stop.Discard)
                }
            }
        } catch (_: Exception) {
            busy = false
            recording = false
            activeRecording = null
            runCatching { file.delete() }
            onFailed()
        }
    }

    fun stop() = stopInternal(Stop.User)

    private fun finishVideo(event: VideoRecordEvent.Finalize, file: File, startedEpoch: Int) {
        recording = false
        busy = false
        stopping = false
        activeRecording = null
        val dropped = startedEpoch != epoch || stopReason == Stop.Discard
        val usable = videoUsable(event, file)
        if (dropped || !usable) {
            runCatching { file.delete() }
            if (!dropped && stopReason == Stop.User) onFailed()
        } else {
            deliver(file, true)
        }
        if (releaseAfterStop) {
            releaseAfterStop = false
            releaseUseCases()
        }
    }

    private fun videoUsable(event: VideoRecordEvent.Finalize, file: File): Boolean {
        if (!file.exists()) return false
        return when (event.error) {
            VideoRecordEvent.Finalize.ERROR_NONE -> file.length() > 0L
            VideoRecordEvent.Finalize.ERROR_SOURCE_INACTIVE,
            VideoRecordEvent.Finalize.ERROR_FILE_SIZE_LIMIT_REACHED,
            VideoRecordEvent.Finalize.ERROR_DURATION_LIMIT_REACHED,
            -> file.length() > 8192L
            else -> false
        }
    }

    private fun stopInternal(reason: Stop) {
        if (stopReason != Stop.Discard) stopReason = reason
        val rec = activeRecording ?: return
        activeRecording = null
        stopping = true
        busy = true
        try {
            rec.stop()
        } catch (_: Exception) {
            stopping = false
            busy = false
            recording = false
            if (releaseAfterStop) {
                releaseAfterStop = false
                releaseUseCases()
            }
        }
    }

    private fun openPhoto(
        current: ProcessCameraProvider,
        owner: LifecycleOwner,
        selector: CameraSelector,
        previewView: PreviewView,
        rotation: Int,
        photo: CameraCaps.PhotoChoice?,
    ): Camera? {
        // Last attempt drops the requested size so a bad resolution cannot brick the preview.
        val plans: List<Pair<CameraCaps.PhotoChoice?, Int>> = listOf(
            photo to ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY,
            photo to ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY,
            null to ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY,
        )
        for ((choice, mode) in plans) {
            releaseUseCases()
            val preview = Preview.Builder().setTargetRotation(rotation).build()
            val capture = ImageCapture.Builder()
                .setCaptureMode(mode)
                .setFlashMode(flashMode)
                .setJpegQuality(90)
                .setTargetRotation(rotation)
                .apply {
                    if (choice != null) setResolutionSelector(resolutionOf(choice))
                }
                .build()
            preview.setSurfaceProvider(previewView.surfaceProvider)
            val bound = bindTo(current, owner, selector, preview, capture) ?: continue
            previewUseCase = preview
            imageCapture = capture
            videoCapture = null
            activeFps = null
            return bound
        }
        return null
    }

    private fun openVideo(
        current: ProcessCameraProvider,
        owner: LifecycleOwner,
        selector: CameraSelector,
        previewView: PreviewView,
        rotation: Int,
        video: CameraCaps.VideoChoice?,
    ): Camera? {
        val quality = video?.quality ?: Quality.FHD
        val requested = video?.fps ?: 30
        val attempts = ArrayList<Pair<Int, Boolean>>(3)
        attempts.add(requested to true)
        if (requested != 30) attempts.add(30 to true)
        attempts.add(30 to false)
        for ((fps, strict) in attempts) {
            releaseUseCases()
            val recorder = try {
                Recorder.Builder()
                    .setQualitySelector(
                        QualitySelector.from(quality, FallbackStrategy.lowerQualityOrHigherThan(quality)),
                    )
                    .build()
            } catch (_: Exception) {
                continue
            }
            val capture = try {
                val builder = VideoCapture.Builder(recorder).setTargetRotation(rotation)
                if (strict) builder.setTargetFrameRate(Range(fps, fps))
                builder.build()
            } catch (_: Exception) {
                continue
            }
            val preview = Preview.Builder().setTargetRotation(rotation).build()
            preview.setSurfaceProvider(previewView.surfaceProvider)
            val bound = bindTo(current, owner, selector, preview, capture) ?: continue
            previewUseCase = preview
            imageCapture = null
            videoCapture = capture
            activeFps = fps
            return bound
        }
        return null
    }

    private fun bindTo(
        current: ProcessCameraProvider,
        owner: LifecycleOwner,
        selector: CameraSelector,
        preview: Preview,
        useCase: UseCase,
    ): Camera? {
        return try {
            current.bindToLifecycle(owner, selector, preview, useCase)
        } catch (_: Exception) {
            null
        }
    }

    private fun wire(bound: Camera, owner: LifecycleOwner, videoMode: Boolean) {
        camera = bound
        hasFlash = try {
            bound.cameraInfo.hasFlashUnit()
        } catch (_: Exception) {
            false
        }
        if (!videoMode) torchOn = false
        readExposure(bound)
        try {
            bound.cameraControl.enableTorch(videoMode && torchOn && hasFlash)
        } catch (_: Exception) {
            // Torch is optional.
        }
        try {
            imageCapture?.flashMode = flashMode
        } catch (_: Exception) {
            // Flash mode is also applied on the next photo.
        }
        bound.cameraInfo.zoomState.value?.let { state ->
            zoomMin = state.minZoomRatio
            zoomMax = state.maxZoomRatio.coerceAtLeast(state.minZoomRatio)
            zoomRatio = state.zoomRatio
        }
        restoreZoom = wantedZoom > 1.02f
        val observer = Observer<ZoomState> { state ->
            zoomMin = state.minZoomRatio
            zoomMax = state.maxZoomRatio.coerceAtLeast(state.minZoomRatio)
            zoomRatio = state.zoomRatio
            if (restoreZoom) {
                restoreZoom = false
                val want = wantedZoom.coerceIn(zoomMin, zoomMax)
                if (abs(state.zoomRatio - want) > 0.05f) {
                    try {
                        bound.cameraControl.setZoomRatio(want)
                    } catch (_: Exception) {
                        // Keep the camera's own ratio.
                    }
                }
            }
        }
        zoomObserver = observer
        try {
            bound.cameraInfo.zoomState.observe(owner, observer)
        } catch (_: Exception) {
            zoomObserver = null
        }
    }

    private fun readExposure(bound: Camera) {
        try {
            val state = bound.cameraInfo.exposureState
            val range = state.exposureCompensationRange
            val supported = state.isExposureCompensationSupported && range.lower < range.upper
            exposureSupported = supported
            if (!supported) return
            exposureMin = range.lower
            exposureMax = range.upper
            val step = state.exposureCompensationStep
            exposureStep = if (step.denominator == 0) 0f else step.numerator.toFloat() / step.denominator
            val index = exposureIndex.coerceIn(range.lower, range.upper)
            exposureIndex = index
            bound.cameraControl.setExposureCompensationIndex(index)
        } catch (_: Exception) {
            exposureSupported = false
        }
    }

    private fun resolutionOf(photo: CameraCaps.PhotoChoice): ResolutionSelector {
        val wide = max(photo.width, photo.height).toFloat()
        val narrow = min(photo.width, photo.height).coerceAtLeast(1).toFloat()
        val ratio = wide / narrow
        val aspect = if (abs(ratio - 16f / 9f) < abs(ratio - 4f / 3f)) {
            AspectRatio.RATIO_16_9
        } else {
            AspectRatio.RATIO_4_3
        }
        return ResolutionSelector.Builder()
            .setResolutionStrategy(
                ResolutionStrategy(
                    Size(photo.width, photo.height),
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                ),
            )
            .setAspectRatioStrategy(AspectRatioStrategy(aspect, AspectRatioStrategy.FALLBACK_RULE_AUTO))
            .build()
    }

    private fun releaseUseCases() {
        detachZoom()
        try {
            camera?.cameraControl?.enableTorch(false)
        } catch (_: Exception) {
            // The light is already going away with the camera.
        }
        try {
            provider?.unbindAll()
        } catch (_: Exception) {
            // The lifecycle owner may already have released it.
        }
        camera = null
        previewUseCase = null
        imageCapture = null
        videoCapture = null
        ready = false
        boundKey = ""
        releaseAfterStop = false
    }

    private fun detachZoom() {
        val current = camera ?: return
        val observer = zoomObserver ?: return
        try {
            current.cameraInfo.zoomState.removeObserver(observer)
        } catch (_: Exception) {
            // The owner may already have dropped the observer.
        }
        zoomObserver = null
    }

    private fun deliver(file: File, video: Boolean) {
        try {
            onClip(file, video)
        } catch (_: Exception) {
            runCatching { file.delete() }
        }
    }

    private fun selectorFor(facing: Int): CameraSelector {
        val lens = CameraSelector.Builder().requireLensFacing(facing).build()
        val current = provider
        if (current != null && probe(current, facing)) return lens
        val id = cameraIdFor(facing) ?: return lens
        return CameraSelector.Builder().addCameraFilter { infos ->
            infos.filter { cameraIdOf(it) == id }
        }.build()
    }

    private fun probe(current: ProcessCameraProvider, facing: Int): Boolean {
        return try {
            current.hasCamera(CameraSelector.Builder().requireLensFacing(facing).build())
        } catch (_: Exception) {
            false
        }
    }

    private fun readLens(info: CameraInfo): Int {
        return try {
            info.lensFacing
        } catch (_: Throwable) {
            CameraSelector.LENS_FACING_UNKNOWN
        }
    }

    private fun cameraIdOf(info: CameraInfo): String? {
        return try {
            Camera2CameraInfo.from(info).cameraId
        } catch (_: Throwable) {
            null
        }
    }

    private fun cameraIdFor(facing: Int): String? {
        val wanted = when (facing) {
            CameraSelector.LENS_FACING_FRONT -> CameraCharacteristics.LENS_FACING_FRONT
            CameraSelector.LENS_FACING_BACK -> CameraCharacteristics.LENS_FACING_BACK
            else -> return null
        }
        return try {
            val manager = context.getSystemService(CameraManager::class.java) ?: return null
            manager.cameraIdList.firstOrNull { id ->
                val value = try {
                    manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING)
                } catch (_: Exception) {
                    null
                }
                value == wanted
            }
        } catch (_: Exception) {
            null
        }
    }

    private enum class Stop { User, Lifecycle, Discard }
}

internal object CameraFiles {
    fun newFile(context: Context, extension: String): File? {
        return try {
            val dir = File(context.cacheDir, "camera")
            if (!dir.isDirectory && !dir.mkdirs()) return null
            var file = File(dir, "cap_${System.currentTimeMillis()}.$extension")
            var extra = 0
            while (file.exists() && extra < 20) {
                extra++
                file = File(dir, "cap_${System.currentTimeMillis()}_$extra.$extension")
            }
            file
        } catch (_: Exception) {
            null
        }
    }

    /**
     * A content Uri our own FileProvider can read. A raw file:// Uri often
     * fails when the chat code opens it, so that is only the fallback.
     */
    fun uriOf(context: Context, file: File): Uri {
        return try {
            FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
        } catch (_: Exception) {
            @Suppress("DEPRECATION")
            Uri.fromFile(file)
        }
    }
}

internal class CameraViewModel(app: android.app.Application) : androidx.lifecycle.AndroidViewModel(app) {
    var clipPath by mutableStateOf<String?>(null)
        private set
    var clipIsVideo by mutableStateOf(false)
        private set
    var captureFailed by mutableStateOf(false)
        private set
    var handedOff by mutableStateOf(false)
        private set

    val session: CameraSession = CameraSession(
        context = app,
        onClip = { file, video -> setClip(file.absolutePath, video) },
        onFailed = { fail() },
    )

    fun setClip(path: String, video: Boolean) {
        val previous = clipPath
        if (previous != null && previous != path) runCatching { File(previous).delete() }
        clipPath = path
        clipIsVideo = video
        captureFailed = false
    }

    fun fail() {
        captureFailed = true
    }

    fun clearFailure() {
        captureFailed = false
    }

    /**
     * Forget an unsent file. A file already returned from [handOff] stays on disk
     * so the caller can read it.
     */
    fun discard() {
        if (!handedOff) clipPath?.let { runCatching { File(it).delete() } }
        clipPath = null
        handedOff = false
        captureFailed = false
    }

    fun retake() {
        if (handedOff) return
        discard()
    }

    /** Give the file to the caller without deleting it. Confirm stays up until the screen closes. */
    fun handOff(): File? {
        if (handedOff) return null
        val path = clipPath ?: return null
        handedOff = true
        return File(path)
    }

    fun clearHandOff() {
        handedOff = false
        clipPath = null
        captureFailed = false
    }

    override fun onCleared() {
        try {
            session.discard()
        } catch (_: Exception) {
            // The activity is already going away.
        }
        discard()
    }
}

internal suspend fun awaitCameraProvider(context: Context): ProcessCameraProvider? {
    return suspendCancellableCoroutine { cont ->
        val future = try {
            ProcessCameraProvider.getInstance(context)
        } catch (_: Exception) {
            cont.resume(null)
            return@suspendCancellableCoroutine
        }
        future.addListener(
            {
                if (!cont.isActive) return@addListener
                val provider = try {
                    future.get()
                } catch (_: Exception) {
                    null
                }
                try {
                    if (cont.isActive) cont.resume(provider)
                } catch (_: Exception) {
                    // The screen left before the camera provider arrived.
                }
            },
            ContextCompat.getMainExecutor(context),
        )
        cont.invokeOnCancellation {
            future.cancel(false)
        }
    }
}
