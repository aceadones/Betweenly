package app.loviedowpie.ui

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import app.loviedowpie.DrawStore
import app.loviedowpie.R
import app.loviedowpie.ui.icons.Lu
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The partner's latest drawing, full screen. Pinch zooms. A downward swipe closes it
 * when the picture is not zoomed.
 *
 * DATA-FLOW: none. The file is already in app-private storage.
 */
@Composable
internal fun PartnerDrawViewer(onClose: () -> Unit) {
    val context = LocalContext.current
    val view = LocalView.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val file = remember { DrawStore.latestFromPartner(context) }
    val slot = remember { arrayOfNulls<Bitmap>(1) }
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var failed by remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        onDispose { slot[0]?.let { recycleLater(view, it) } }
    }
    LaunchedEffect(file) {
        if (file == null) {
            failed = true
            return@LaunchedEffect
        }
        val decoded = withContext(Dispatchers.IO) {
            val bmp = try {
                DrawStore.decode(file, 1600)
            } catch (_: Exception) {
                null
            }
            if (!isActive && bmp != null) {
                if (!bmp.isRecycled) bmp.recycle()
                null
            } else {
                bmp
            }
        }
        if (decoded == null) {
            failed = true
        } else {
            slot[0] = decoded
            bitmap = decoded
        }
    }
    if (file == null || failed) {
        LaunchedEffect(failed) { onClose() }
        return
    }

    val open = remember { Animatable(if (Motion.enabled) 0f else 1f) }
    val drag = remember { Animatable(0f) }
    var dragJob by remember { mutableStateOf<Job?>(null) }
    var scale by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var multi by remember { mutableStateOf(false) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val gate = remember { intArrayOf(0) }
    LaunchedEffect(Unit) {
        if (open.value < 1f) open.animateTo(1f, Motion.tween(260, Motion.easeOut))
    }
    fun finish() {
        if (gate[0] == 1) return
        gate[0] = 1
        dragJob?.cancel()
        dragJob = scope.launch {
            if (Motion.enabled && open.value > 0f) {
                open.animateTo(0f, Motion.tween(160, Motion.easeOut))
            }
            onClose()
        }
    }
    BackHandler(onBack = { finish() })

    val transform = rememberTransformableState { zoom, panChange, _ ->
        val next = (scale * zoom).coerceIn(1f, 5f)
        scale = next
        if (next <= 1.01f) {
            pan = Offset.Zero
        } else {
            val limitX = size.width * (next - 1f) / 2f
            val limitY = size.height * (next - 1f) / 2f
            pan = Offset(
                (pan.x + panChange.x).coerceIn(-limitX, limitX),
                (pan.y + panChange.y).coerceIn(-limitY, limitY),
            )
        }
    }
    val menu = LdColors.colors.menu
    val dragNow = drag.value
    val openNow = open.value
    val heightPx = size.height.toFloat().coerceAtLeast(1f)
    val scaleNow = rememberUpdatedState(scale)
    val multiNow = rememberUpdatedState(multi)
    val heightNow = rememberUpdatedState(heightPx)
    val dragFrac = (dragNow / heightPx).coerceIn(0f, 1f)
    val shown = openNow * (1f - dragFrac)
    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged { size = it }
            .background(menu.copy(alpha = 0.98f * shown.coerceIn(0f, 1f))),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val s = 0.94f + 0.06f * openNow
                    scaleX = s * (1f - 0.08f * dragFrac)
                    scaleY = scaleX
                    translationY = dragNow
                    alpha = shown.coerceIn(0f, 1f)
                    transformOrigin = TransformOrigin(0.5f, 0.78f)
                }
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val down = event.changes.count { it.pressed }
                            multi = down >= 2
                        }
                    }
                }
                .pointerInput(Unit) {
                    var pending = 0f
                    detectVerticalDragGestures(
                        onDragStart = { pending = drag.value },
                        onVerticalDrag = { _, dy ->
                            if (scaleNow.value > 1.05f || multiNow.value) return@detectVerticalDragGestures
                            pending = (pending + dy).coerceAtLeast(0f)
                            val target = pending
                            dragJob?.cancel()
                            dragJob = scope.launch { drag.snapTo(target) }
                        },
                        onDragEnd = {
                            val threshold = with(density) { 120.dp.toPx() }
                            dragJob?.cancel()
                            dragJob = scope.launch {
                                if (gate[0] == 0 && scaleNow.value <= 1.05f && !multiNow.value && drag.value > threshold) {
                                    gate[0] = 1
                                    drag.animateTo(heightNow.value, Motion.tween(160, Motion.easeOut))
                                    onClose()
                                } else {
                                    pending = 0f
                                    drag.animateTo(0f, Motion.spring())
                                }
                            }
                        },
                        onDragCancel = {
                            dragJob?.cancel()
                            dragJob = scope.launch {
                                pending = 0f
                                drag.animateTo(0f, Motion.spring())
                            }
                        },
                    )
                },
        ) {
            val shownBitmap = bitmap
            if (shownBitmap == null) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                    color = LdColors.primary,
                )
            } else {
                Image(
                    bitmap = shownBitmap.asImageBitmap(),
                    contentDescription = stringResource(R.string.draw_partner),
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            translationX = pan.x
                            translationY = pan.y
                        }
                        .transformable(state = transform, canPan = { scale > 1.01f })
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onDoubleTap = {
                                    if (scaleNow.value > 1.2f) {
                                        scale = 1f
                                        pan = Offset.Zero
                                    } else {
                                        scale = 2.5f
                                    }
                                },
                            )
                        },
                    contentScale = ContentScale.Fit,
                )
            }
            Box(Modifier.align(Alignment.TopStart).statusBarsPadding().padding(16.dp)) {
                GlassIconButton(
                    onClick = { finish() },
                    icon = Lu.X,
                    description = stringResource(R.string.chat_close),
                )
            }
        }
    }
}
