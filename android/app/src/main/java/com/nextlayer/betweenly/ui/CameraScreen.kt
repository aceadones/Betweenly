package com.nextlayer.betweenly.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings
import android.view.Surface
import android.view.View
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nextlayer.betweenly.CameraCaps
import com.nextlayer.betweenly.R
import com.nextlayer.betweenly.ui.icons.Lu
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * In-app camera. The picture stays in cache until [onResult]. Nothing is uploaded here.
 *
 * [onResult] receives a file Uri under cacheDir/camera. [onClose] leaves without one.
 */
@Composable
fun CameraScreen(onResult: (uri: Uri, isVideo: Boolean) -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    // Constructed here so release builds do not depend on reflection to find the Application constructor.
    val vm: CameraViewModel = viewModel {
        CameraViewModel(context.applicationContext as android.app.Application)
    }
    KeepCameraAwake()
    DisposableEffect(vm) {
        onDispose {
            if (vm.handedOff) vm.clearHandOff()
        }
    }
    BackHandler {
        vm.session.discard()
        vm.discard()
        onClose()
    }

    var tick by remember { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) tick++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val granted = remember(tick) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    }

    Box(Modifier.fillMaxSize().background(CameraChrome.background)) {
        val clip = vm.clipPath
        when {
            clip != null -> CameraConfirm(
                path = clip,
                isVideo = vm.clipIsVideo,
                sendEnabled = !vm.handedOff,
                onRetake = { vm.retake() },
                onSend = {
                    val video = vm.clipIsVideo
                    val file = vm.handOff() ?: return@CameraConfirm
                    if (!file.exists() || file.length() <= 0L || !file.canRead()) {
                        vm.clearHandOff()
                        vm.fail()
                        return@CameraConfirm
                    }
                    onResult(CameraFiles.uriOf(context, file), video)
                },
                onClose = {
                    vm.session.discard()
                    vm.discard()
                    onClose()
                },
            )
            !granted -> CameraPermission(
                asked = CameraPrefs.cameraAsked(context),
                onAsked = { CameraPrefs.setCameraAsked(context) },
                onGranted = { tick++ },
                onClose = {
                    vm.session.discard()
                    vm.discard()
                    onClose()
                },
            )
            else -> CapturePane(
                vm = vm,
                onClose = {
                    vm.session.discard()
                    vm.discard()
                    onClose()
                },
            )
        }
    }
}

@Composable
private fun KeepCameraAwake() {
    val view = LocalView.current
    val context = LocalContext.current
    DisposableEffect(view) {
        val previousKeep = view.keepScreenOn
        view.keepScreenOn = true
        val window = context.findActivity()?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        val lightStatus = controller?.isAppearanceLightStatusBars
        val lightNav = controller?.isAppearanceLightNavigationBars
        controller?.isAppearanceLightStatusBars = false
        controller?.isAppearanceLightNavigationBars = false
        onDispose {
            view.keepScreenOn = previousKeep
            if (lightStatus != null) controller.isAppearanceLightStatusBars = lightStatus
            if (lightNav != null) controller.isAppearanceLightNavigationBars = lightNav
        }
    }
}

@Composable
private fun CameraPermission(
    asked: Boolean,
    onAsked: () -> Unit,
    onGranted: () -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val activity = context.findActivity()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        onGranted()
    }
    val settings = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        onGranted()
    }
    val permanent = asked && activity != null &&
        !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.CAMERA)
    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
    ) {
        RoundIconButton(
            onClick = onClose,
            description = stringResource(R.string.camera_close),
            icon = Lu.X,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.camera_perm_title),
            style = MaterialTheme.typography.headlineMedium,
            color = CameraChrome.content,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.camera_perm_body),
            style = MaterialTheme.typography.bodyLarge,
            color = CameraChrome.content,
        )
        if (asked) {
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(R.string.camera_perm_denied),
                style = MaterialTheme.typography.bodyMedium,
                color = LdColors.cameraError,
            )
        }
        Spacer(Modifier.height(24.dp))
        if (permanent) {
            LdButton(
                text = stringResource(R.string.perm_settings),
                onClick = { openAppSettings(context, settings::launch) },
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            LdButton(
                text = stringResource(R.string.perm_allow),
                onClick = {
                    onAsked()
                    try {
                        launcher.launch(Manifest.permission.CAMERA)
                    } catch (_: Exception) {
                        openAppSettings(context, settings::launch)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun CapturePane(vm: CameraViewModel, onClose: () -> Unit) {
    val context = LocalContext.current
    val session = vm.session
    val lifecycleOwner = LocalLifecycleOwner.current
    var facing by rememberSaveable { mutableIntStateOf(CameraPrefs.savedLens(context)) }
    var videoMode by rememberSaveable { mutableStateOf(false) }
    var skipAudio by rememberSaveable { mutableStateOf(false) }
    var pendingVideo by rememberSaveable { mutableStateOf(false) }
    var askAudio by remember { mutableStateOf(false) }
    var showQuality by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    var capsFacing by remember { mutableIntStateOf(-1) }
    var photos by remember { mutableStateOf<List<CameraCaps.PhotoChoice>>(emptyList()) }
    var videos by remember { mutableStateOf<List<CameraCaps.VideoChoice>>(emptyList()) }
    var selectedPhoto by remember { mutableStateOf<CameraCaps.PhotoChoice?>(null) }
    var selectedVideo by remember { mutableStateOf<CameraCaps.VideoChoice?>(null) }
    var focusAt by remember { mutableStateOf<Offset?>(null) }
    var elapsed by remember { mutableLongStateOf(0L) }
    var resumed by remember(lifecycleOwner) {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }

    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
    }

    DisposableEffect(lifecycleOwner, session) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> resumed = true
                Lifecycle.Event.ON_PAUSE -> {
                    resumed = false
                    session.onHostPause()
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            session.onHostPause()
        }
    }

    LaunchedEffect(attempt) {
        val provider = awaitCameraProvider(context.applicationContext)
        if (provider == null) session.markProviderMissing() else session.attach(provider)
    }

    LaunchedEffect(session.attached) {
        if (!session.attached || session.noCamera) return@LaunchedEffect
        if (session.hasFacing(facing)) return@LaunchedEffect
        val corrected = when {
            session.hasFacing(CameraSelector.LENS_FACING_BACK) -> CameraSelector.LENS_FACING_BACK
            session.hasFacing(CameraSelector.LENS_FACING_FRONT) -> CameraSelector.LENS_FACING_FRONT
            else -> return@LaunchedEffect
        }
        facing = corrected
        CameraPrefs.saveLens(context, corrected)
    }

    LaunchedEffect(session.attached, facing, session.noCamera) {
        if (!session.attached || session.noCamera) return@LaunchedEffect
        val info = session.findInfo(facing)
        val photoOptions = if (info == null) emptyList() else CameraCaps.photoChoices(info)
        val videoOptions = if (info == null) emptyList() else CameraCaps.videoChoices(info)
        photos = photoOptions
        videos = videoOptions
        selectedPhoto = CameraPrefs.resolvePhoto(context, photoOptions)
        selectedVideo = CameraPrefs.resolveVideo(context, videoOptions)
        capsFacing = facing
    }

    LaunchedEffect(resumed, capsFacing, facing, videoMode, selectedPhoto, selectedVideo, attempt, session.stopping) {
        if (!resumed || capsFacing != facing || session.noCamera) return@LaunchedEffect
        val photo = selectedPhoto
        val video = selectedVideo
        val knownVideos = videos
        val result = session.bind(
            lifecycleOwner = lifecycleOwner,
            previewView = previewView,
            facing = facing,
            videoMode = videoMode,
            photo = photo,
            video = video,
            token = attempt,
        ) ?: return@LaunchedEffect
        if (!result.ok || !videoMode) return@LaunchedEffect
        val fps = result.videoFps ?: return@LaunchedEffect
        if (video != null && video.fps != fps) {
            val replacement = knownVideos.firstOrNull { it.quality == video.quality && it.fps == fps }
            if (replacement != null) {
                selectedVideo = replacement
                CameraPrefs.saveVideo(context, replacement)
            }
        }
    }

    LaunchedEffect(session.recording) {
        if (!session.recording) {
            elapsed = 0L
            return@LaunchedEffect
        }
        val start = SystemClock.elapsedRealtime()
        while (isActive) {
            elapsed = SystemClock.elapsedRealtime() - start
            delay(200)
        }
    }

    LaunchedEffect(focusAt) {
        if (focusAt != null) {
            delay(900)
            focusAt = null
        }
    }

    val audioLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        if (!pendingVideo) return@rememberLauncherForActivityResult
        pendingVideo = false
        if (!allowed) skipAudio = true
        session.startVideo(previewView.display?.rotation ?: Surface.ROTATION_0, withAudio = allowed)
    }

    fun rotationOf(): Int = previewView.display?.rotation ?: Surface.ROTATION_0

    fun onShutter() {
        if (session.recording) {
            session.stop()
            return
        }
        if (!session.ready || session.busy || session.stopping) return
        vm.clearFailure()
        if (!videoMode) {
            session.takePhoto(rotationOf())
            return
        }
        val mic = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        if (mic || skipAudio) {
            session.startVideo(rotationOf(), withAudio = mic)
        } else {
            askAudio = true
        }
    }

    // showSwitch stays true once two cameras are seen. Reading it here subscribes
    // the preview to that state, including after a late hasCamera() result.
    val showSwitch = session.showSwitch
    LaunchedEffect(session.attached, session.ready) {
        if (session.attached) session.refreshCameras()
    }
    val qualityLabel = if (videoMode) selectedVideo?.label else selectedPhoto?.label
    val onTap = rememberUpdatedState { offset: Offset ->
        focusAt = offset
        session.focus(previewView, offset.x, offset.y)
    }
    val onZoom = rememberUpdatedState { factor: Float -> session.pinch(factor) }

    Box(Modifier.fillMaxSize()) {
        when {
            !session.attached -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = CameraChrome.content)
                }
                Box(Modifier.windowInsetsPadding(WindowInsets.safeDrawing)) {
                    RoundIconButton(
                        onClick = onClose,
                        description = stringResource(R.string.camera_close),
                        icon = Lu.X,
                    )
                }
            }
            session.noCamera -> CameraNotice(
                message = stringResource(R.string.camera_no_camera),
                action = null,
                onAction = null,
                onClose = onClose,
            )
            else -> {
                AndroidView(
                    factory = { previewView },
                    modifier = Modifier.fillMaxSize(),
                    update = { view ->
                        val rotation = view.display?.rotation ?: return@AndroidView
                        session.updateRotation(rotation)
                    },
                )
                Box(
                    Modifier
                        .fillMaxSize()
                        .zIndex(1f)
                        .pointerInput(previewView) {
                            previewGestures(onTap = { onTap.value(it) }, onZoom = { onZoom.value(it) })
                        },
                )
                val point = focusAt
                if (point != null) FocusRing(point)
                if (session.unavailable) {
                    CameraNotice(
                        message = stringResource(R.string.camera_unavailable),
                        action = stringResource(R.string.camera_try_again),
                        onAction = {
                            session.unavailable = false
                            attempt++
                        },
                        onClose = onClose,
                    )
                }
                CameraOverlay(
                    session = session,
                    videoMode = videoMode,
                    showSwitch = showSwitch,
                    qualityLabel = qualityLabel,
                    elapsedMs = elapsed,
                    failed = vm.captureFailed,
                    silent = videoMode && skipAudio,
                    onClose = onClose,
                    onFlip = {
                        if (session.recording || session.stopping) return@CameraOverlay
                        val next = if (facing == CameraSelector.LENS_FACING_FRONT) {
                            CameraSelector.LENS_FACING_BACK
                        } else {
                            CameraSelector.LENS_FACING_FRONT
                        }
                        facing = next
                        CameraPrefs.saveLens(context, next)
                    },
                    onShutter = ::onShutter,
                    onFlash = {
                        session.cycleFlash(videoMode)
                        if (!videoMode) CameraPrefs.saveFlash(context, session.flashMode)
                    },
                    onPhotoMode = {
                        if (videoMode && !session.recording && !session.stopping) {
                            session.setTorch(false)
                            videoMode = false
                        }
                    },
                    onVideoMode = {
                        if (!videoMode && !session.recording && !session.stopping) videoMode = true
                    },
                    onQuality = { if (!session.recording && !session.stopping) showQuality = true },
                )
            }
        }
    }

    if (askAudio) {
        AlertDialog(
            onDismissRequest = { askAudio = false },
            modifier = Modifier.ldPopupFrame(),
            shape = MaterialTheme.shapes.extraLarge,
            containerColor = LdColors.glassFill,
            tonalElevation = 0.dp,
            titleContentColor = LdColors.onGlass,
            textContentColor = LdColors.onGlassMuted,
            title = { Text(stringResource(R.string.camera_audio_title)) },
            text = { Text(stringResource(R.string.camera_audio_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        askAudio = false
                        pendingVideo = true
                        try {
                            audioLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        } catch (_: Exception) {
                            pendingVideo = false
                            skipAudio = true
                            session.startVideo(rotationOf(), withAudio = false)
                        }
                    },
                ) { Text(stringResource(R.string.camera_audio_allow), color = LdColors.onGlass) }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        askAudio = false
                        skipAudio = true
                        session.startVideo(rotationOf(), withAudio = false)
                    },
                ) { Text(stringResource(R.string.camera_audio_skip), color = LdColors.onGlass) }
            },
        )
    }

    val currentPhoto = selectedPhoto
    val currentVideo = selectedVideo
    if (showQuality && ((videoMode && videos.isNotEmpty()) || (!videoMode && photos.isNotEmpty()))) {
        QualityDialog(
            videoMode = videoMode,
            photos = photos,
            videos = videos,
            photo = currentPhoto,
            video = currentVideo,
            onPhoto = { choice ->
                selectedPhoto = choice
                CameraPrefs.savePhoto(context, choice)
                showQuality = false
            },
            onVideo = { choice ->
                selectedVideo = choice
                CameraPrefs.saveVideo(context, choice)
                showQuality = false
            },
            onDismiss = { showQuality = false },
        )
    }
}

@Composable
private fun CameraNotice(
    message: String,
    action: String?,
    onAction: (() -> Unit)?,
    onClose: () -> Unit,
) {
    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        RoundIconButton(
            onClick = onClose,
            description = stringResource(R.string.camera_close),
            icon = Lu.X,
        )
        Column(
            Modifier.align(Alignment.Center).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(message, color = CameraChrome.content, style = MaterialTheme.typography.bodyLarge)
            if (action != null && onAction != null) {
                Spacer(Modifier.height(16.dp))
                LdButton(text = action, onClick = onAction)
            }
        }
    }
}

private fun openAppSettings(context: Context, launch: (Intent) -> Unit) {
    val intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", context.packageName, null),
    )
    try {
        launch(intent)
    } catch (_: Exception) {
        // This device has no app-details screen. The message stays on screen.
    }
}

private fun Context.findActivity(): Activity? {
    var current: Context = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}
