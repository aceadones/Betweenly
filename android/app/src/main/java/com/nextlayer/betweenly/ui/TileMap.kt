package com.nextlayer.betweenly.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Matrix as AndroidMatrix
import android.graphics.Paint as AndroidPaint
import android.graphics.Shader
import android.os.SystemClock
import android.util.LruCache
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix as ComposeColorMatrix
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.nextlayer.betweenly.BuildConfig
import com.nextlayer.betweenly.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.URL
import java.util.PriorityQueue
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.HttpsURLConnection
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.math.tan

/**
 * OpenStreetMap raster map. No map SDK.
 *
 * Pan is a drag. Pinch scales the tiles already on screen and only changes the
 * tile zoom after the fingers lift and the zoom has settled, so a gesture does
 * not request a new set per frame. Until that set arrives, the previous zoom
 * stays underneath, with parent and child tiles from the cache filling gaps.
 * Double-tap zooms in one level toward the tap.
 *
 * DATA-FLOW: see [TileCache.fetch]. A tile request tells the tile server which
 * square is on screen and this phone's IP. It does not name the partner.
 */
@Composable
fun TileMap(
    latitude: Double,
    longitude: Double,
    marker: Bitmap?,
    markerLetter: String,
    recenterToken: Int,
    onMarkerClick: () -> Unit,
    onMapClick: () -> Unit,
    modifier: Modifier = Modifier,
    gestures: Boolean = true,
    startZoom: Float = 15f,
) {
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()
    val placeholder = if (dark) LdColors.mapPlaceholderDark else LdColors.mapPlaceholderLight
    val radar = LdColors.primary
    val tileFilter = if (dark) darkTileFilter else null
    val ring = MaterialTheme.colorScheme.primary
    val fill = MaterialTheme.colorScheme.primaryContainer
    val onFill = MaterialTheme.colorScheme.onPrimaryContainer
    val halo = MaterialTheme.colorScheme.surface
    val mapCd = stringResource(R.string.map_cd)
    val latState = rememberUpdatedState(latitude)
    val lonState = rememberUpdatedState(longitude)
    val markerClick = rememberUpdatedState(onMarkerClick)
    val mapClick = rememberUpdatedState(onMapClick)
    val markerState = rememberUpdatedState(marker)
    val scope = rememberCoroutineScope()
    val snapJob = remember { JobSlot() }
    val animJob = remember { JobSlot() }

    var follow by remember { mutableStateOf(true) }
    var camLat by remember { mutableDoubleStateOf(latitude) }
    var camLon by remember { mutableDoubleStateOf(longitude) }
    var zoom by remember { mutableFloatStateOf(startZoom.coerceIn(MIN_ZOOM, MAX_ZOOM)) }
    var tileZoom by remember {
        mutableIntStateOf(startZoom.roundToInt().coerceIn(MIN_ZOOM.toInt(), MAX_ZOOM.toInt()))
    }
    var view by remember { mutableStateOf(IntSize.Zero) }
    var frame by remember { mutableStateOf<GestureFrame?>(null) }
    var blockLoad by remember { mutableStateOf(false) }
    val rev by TileCache.revision.collectAsState()

    DisposableEffect(Unit) {
        TileCache.retain()
        onDispose { TileCache.release() }
    }

    LaunchedEffect(latitude, longitude) {
        if (follow && frame == null) {
            camLat = latitude
            camLon = longitude
        }
    }
    LaunchedEffect(recenterToken) {
        if (recenterToken == 0) return@LaunchedEffect
        animJob.job?.cancel()
        snapJob.job?.cancel()
        frame = null
        blockLoad = false
        follow = true
        camLat = latitude
        camLon = longitude
    }

    fun scheduleSnap() {
        snapJob.job?.cancel()
        blockLoad = true
        snapJob.job = scope.launch {
            delay(240)
            val next = zoom.roundToInt().coerceIn(MIN_ZOOM.toInt(), MAX_ZOOM.toInt())
            val width = view.width.toFloat()
            val height = view.height.toFloat()
            if (next != tileZoom && width > 1f && height > 1f) {
                TileCache.pin(
                    tileZoom,
                    tileDraws(Camera(camLat, camLon, zoom), width, height, tileZoom),
                )
                tileZoom = next
            }
            frame = null
            blockLoad = false
        }
    }

    fun animateTo(dest: Camera) {
        animJob.job?.cancel()
        snapJob.job?.cancel()
        frame = null
        blockLoad = true
        follow = false
        val fromLat = camLat
        val fromLon = camLon
        val fromZoom = zoom
        animJob.job = scope.launch {
            if (!Motion.enabled) {
                camLat = dest.lat
                camLon = dest.lon
                zoom = dest.zoom
            } else {
                val started = SystemClock.uptimeMillis()
                while (isActive) {
                    val p = ((SystemClock.uptimeMillis() - started) / 220f).coerceIn(0f, 1f)
                    val eased = Motion.easeOut.transform(p)
                    zoom = fromZoom + (dest.zoom - fromZoom) * eased
                    camLat = fromLat + (dest.lat - fromLat) * eased
                    camLon = fromLon + (dest.lon - fromLon) * eased
                    if (p >= 1f) break
                    delay(16)
                }
            }
            if (isActive) scheduleSnap()
        }
    }

    val drawCam = frame?.origin ?: Camera(camLat, camLon, zoom)
    val widthPx = view.width.toFloat()
    val heightPx = view.height.toFloat()
    val draws = if (widthPx <= 1f || heightPx <= 1f) {
        emptyList()
    } else {
        tileDraws(drawCam, widthPx, heightPx, tileZoom)
    }
    val underZ = TileCache.pinnedZoom()
    val underlay = if (
        widthPx > 1f &&
        heightPx > 1f &&
        underZ in MIN_ZOOM.toInt()..MAX_ZOOM.toInt() &&
        underZ != tileZoom
    ) {
        tileDraws(drawCam, widthPx, heightPx, underZ)
    } else {
        emptyList()
    }

    LaunchedEffect(tileZoom, camLat, camLon, zoom, view.width, view.height, blockLoad) {
        if (blockLoad || view.width <= 1 || view.height <= 1) return@LaunchedEffect
        delay(120)
        val list = tileDraws(
            Camera(camLat, camLon, zoom),
            view.width.toFloat(),
            view.height.toFloat(),
            tileZoom,
        )
        TileCache.request(context, list, view.width.toFloat(), view.height.toFloat())
    }
    LaunchedEffect(rev, tileZoom, camLat, camLon, zoom, view.width, view.height) {
        if (view.width <= 1 || view.height <= 1) return@LaunchedEffect
        val list = tileDraws(
            Camera(camLat, camLon, zoom),
            view.width.toFloat(),
            view.height.toFloat(),
            tileZoom,
        )
        if (list.isNotEmpty() && list.all { TileCache.peek(it.z, it.fetchX, it.y) != null }) {
            TileCache.clearPin()
        }
    }

    val layerMod = run {
        val live = frame
        if (live == null || widthPx <= 1f || heightPx <= 1f) {
            Modifier
        } else {
            val scale = 2.0.pow((live.preview.zoom - live.origin.zoom).toDouble()).toFloat()
            val projected = markerOffset(live.origin.lat, live.origin.lon, live.preview, widthPx, heightPx)
            val safe = scale.isFinite() && scale > 0f
            Modifier.graphicsLayer {
                scaleX = if (safe) scale else 1f
                scaleY = if (safe) scale else 1f
                transformOrigin = TransformOrigin.Center
                if (projected.x.isFinite() && projected.y.isFinite()) {
                    translationX = projected.x - size.width / 2f
                    translationY = projected.y - size.height / 2f
                }
            }
        }
    }

    Box(
        modifier
            .background(placeholder)
            .onSizeChanged { view = it }
            .semantics { contentDescription = mapCd },
    ) {
        Canvas(
            Modifier
                .fillMaxSize()
                .clipToBounds()
                .then(layerMod)
                .pointerInput(gestures) {
                    if (!gestures) return@pointerInput
                    val slop = viewConfiguration.touchSlop
                    val markerRadius = 22.dp.toPx()
                    val doubleSlop = 48.dp.toPx()
                    var lastTapAt = 0L
                    var lastTapPos = Offset.Zero
                    var pendingTap: Job? = null
                    fun finishTap(point: Offset, cam: Camera, lat: Double, lon: Double, w: Float, h: Float) {
                        val markerAt = markerOffset(lat, lon, cam, w, h)
                        if ((point - markerAt).getDistance() <= markerRadius + 16f) {
                            markerClick.value()
                        } else {
                            mapClick.value()
                        }
                    }
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val start = down.position
                        var pastSlop = false
                        var acc = Offset.Zero
                        var origin = Camera(camLat, camLon, zoom)
                        var preview = origin
                        var pressed = true
                        while (pressed) {
                            val event = awaitPointerEvent()
                            val pan = event.calculatePan()
                            val zoomChange = event.calculateZoom()
                            val fingers = event.changes.count { it.pressed }
                            val zooming = fingers >= 2 &&
                                zoomChange.isFinite() &&
                                abs(zoomChange - 1f) > 0.001f
                            if (!pastSlop) {
                                acc += pan
                                if (acc.getDistance() > slop || zooming) {
                                    pastSlop = true
                                    pendingTap?.cancel()
                                    animJob.job?.cancel()
                                    snapJob.job?.cancel()
                                    blockLoad = true
                                    follow = false
                                    origin = Camera(camLat, camLon, zoom)
                                    preview = origin
                                    val w = view.width.toFloat()
                                    val h = view.height.toFloat()
                                    if (acc.x != 0f || acc.y != 0f) {
                                        preview = panCamera(preview, acc.x, acc.y, w, h)
                                    }
                                    if (zooming) {
                                        preview = zoomCamera(
                                            preview,
                                            zoomChange,
                                            event.calculateCentroid(useCurrent = true).x,
                                            event.calculateCentroid(useCurrent = true).y,
                                            w,
                                            h,
                                        )
                                    }
                                    frame = GestureFrame(origin, preview)
                                }
                            } else {
                                val w = view.width.toFloat()
                                val h = view.height.toFloat()
                                if (pan.x != 0f || pan.y != 0f) {
                                    preview = panCamera(preview, pan.x, pan.y, w, h)
                                }
                                if (zoomChange.isFinite() && zoomChange > 0f && abs(zoomChange - 1f) > 0.0005f) {
                                    val focal = event.calculateCentroid(useCurrent = true)
                                    preview = zoomCamera(preview, zoomChange, focal.x, focal.y, w, h)
                                }
                                frame = GestureFrame(origin, preview)
                            }
                            if (pastSlop) {
                                event.changes.forEach { change ->
                                    if (change.positionChanged()) change.consume()
                                }
                            }
                            pressed = event.changes.any { it.pressed }
                        }
                        if (pastSlop) {
                            val nextZoom = preview.zoom.roundToInt().coerceIn(MIN_ZOOM.toInt(), MAX_ZOOM.toInt())
                            camLat = preview.lat
                            camLon = preview.lon
                            zoom = preview.zoom
                            if (nextZoom != tileZoom) {
                                // Keep the pinch frame until the tile zoom snaps, so the
                                // grid does not reshuffle while the new level is chosen.
                                frame = GestureFrame(origin, preview)
                                scheduleSnap()
                            } else {
                                frame = null
                                blockLoad = false
                            }
                        } else if (view.width > 0 && view.height > 0) {
                            val nowTap = SystemClock.uptimeMillis()
                            val second = nowTap - lastTapAt < 280L &&
                                (start - lastTapPos).getDistance() < doubleSlop
                            if (second) {
                                lastTapAt = 0L
                                pendingTap?.cancel()
                                val w = view.width.toFloat()
                                val h = view.height.toFloat()
                                val target = (zoom + 1f).coerceIn(MIN_ZOOM, MAX_ZOOM)
                                val factor = 2.0.pow((target - zoom).toDouble()).toFloat()
                                if (factor.isFinite() && abs(factor - 1f) > 0.01f) {
                                    animateTo(zoomCamera(Camera(camLat, camLon, zoom), factor, start.x, start.y, w, h))
                                }
                            } else {
                                lastTapAt = nowTap
                                lastTapPos = start
                                val cam = Camera(camLat, camLon, zoom)
                                val lat = latState.value
                                val lon = lonState.value
                                val w = view.width.toFloat()
                                val h = view.height.toFloat()
                                pendingTap?.cancel()
                                pendingTap = scope.launch {
                                    delay(280)
                                    if (!isActive) return@launch
                                    finishTap(start, cam, lat, lon, w, h)
                                }
                            }
                        }
                    }
                },
        ) {
            if (rev < 0) return@Canvas
            drawRect(placeholder)
            for (tile in underlay) {
                val bmp = TileCache.peek(tile.z, tile.fetchX, tile.y) ?: continue
                blit(bmp, tile.left, tile.top, tile.size, tile.size, tileFilter)
            }
            for (tile in draws) {
                paintTile(tile, tileFilter)
            }
            if (size.width > 0f && size.height > 0f) {
                val center = markerOffset(
                    latitude,
                    longitude,
                    drawCam,
                    size.width,
                    size.height,
                )
                if (center.x.isFinite() && center.y.isFinite()) {
                    drawMarker(
                        center = center,
                        radius = 22.dp.toPx(),
                        bitmap = markerState.value,
                        letter = markerLetter,
                        fill = fill,
                        onFill = onFill,
                        ring = ring,
                        halo = halo,
                        radar = radar,
                    )
                }
            }
        }
        Text(
            stringResource(R.string.map_attribution),
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 8.dp, bottom = 8.dp, end = 88.dp)
                .darkGlass(RoundedCornerShape(12.dp))
                .padding(horizontal = 6.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = LdColors.onGlass,
        )
    }
}

private class JobSlot {
    var job: Job? = null
}

private data class Camera(val lat: Double, val lon: Double, val zoom: Float)

private data class GestureFrame(val origin: Camera, val preview: Camera)

internal data class TileDraw(
    val z: Int,
    val fetchX: Int,
    val y: Int,
    val left: Float,
    val top: Float,
    val size: Float,
)

private const val MIN_ZOOM = 3f
private const val MAX_ZOOM = 19f
private const val MAX_LAT = 85.05112878

private fun worldSize(zoom: Float): Double = 256.0 * 2.0.pow(zoom.toDouble())

private fun wrapLon(lon: Double): Double {
    var value = (lon + 180.0) % 360.0
    if (value < 0) value += 360.0
    return value - 180.0
}

private fun lonToX(lon: Double, zoom: Float): Double =
    (wrapLon(lon) + 180.0) / 360.0 * worldSize(zoom)

private fun latToY(lat: Double, zoom: Float): Double {
    val clamped = lat.coerceIn(-MAX_LAT, MAX_LAT)
    val rad = Math.toRadians(clamped)
    val merc = ln(tan(rad) + 1.0 / cos(rad))
    return (1.0 - merc / PI) / 2.0 * worldSize(zoom)
}

private fun xToLon(x: Double, zoom: Float): Double {
    val size = worldSize(zoom)
    if (size <= 0.0) return 0.0
    return wrapLon(x / size * 360.0 - 180.0)
}

private fun yToLat(y: Double, zoom: Float): Double {
    val size = worldSize(zoom)
    if (size <= 0.0) return 0.0
    val clamped = y.coerceIn(0.0, size)
    val t = PI * (1.0 - 2.0 * clamped / size)
    return Math.toDegrees(atan(sinh(t))).coerceIn(-MAX_LAT, MAX_LAT)
}

private fun screenToLatLon(
    sx: Float,
    sy: Float,
    width: Float,
    height: Float,
    camLat: Double,
    camLon: Double,
    zoom: Float,
): Pair<Double, Double> {
    val cx = lonToX(camLon, zoom)
    val cy = latToY(camLat, zoom)
    val wx = cx + (sx - width / 2.0)
    val wy = cy + (sy - height / 2.0)
    return yToLat(wy, zoom) to xToLon(wx, zoom)
}

private fun settled(cam: Camera): Camera {
    val lat = if (cam.lat.isFinite()) cam.lat.coerceIn(-MAX_LAT, MAX_LAT) else 0.0
    val lon = if (cam.lon.isFinite()) wrapLon(cam.lon) else 0.0
    val z = if (cam.zoom.isFinite()) cam.zoom.coerceIn(MIN_ZOOM, MAX_ZOOM) else 15f
    return Camera(lat, lon, z)
}

private fun panCamera(cam: Camera, panX: Float, panY: Float, width: Float, height: Float): Camera {
    if (width <= 1f || height <= 1f) return cam
    val (lat, lon) = screenToLatLon(
        width / 2f - panX,
        height / 2f - panY,
        width,
        height,
        cam.lat,
        cam.lon,
        cam.zoom,
    )
    return settled(cam.copy(lat = lat, lon = lon))
}

private fun zoomCamera(
    cam: Camera,
    factor: Float,
    focalX: Float,
    focalY: Float,
    width: Float,
    height: Float,
): Camera {
    if (!factor.isFinite() || factor <= 0f || factor == 1f) return cam
    if (width <= 1f || height <= 1f) return cam
    val (lat, lon) = screenToLatLon(focalX, focalY, width, height, cam.lat, cam.lon, cam.zoom)
    val newZoom = (cam.zoom + (ln(factor.toDouble()) / ln(2.0)).toFloat()).coerceIn(MIN_ZOOM, MAX_ZOOM)
    val fx = lonToX(lon, newZoom)
    val fy = latToY(lat, newZoom)
    val camX = fx - (focalX - width / 2.0)
    val camY = fy - (focalY - height / 2.0)
    return settled(Camera(yToLat(camY, newZoom), xToLon(camX, newZoom), newZoom))
}

private fun markerOffset(lat: Double, lon: Double, cam: Camera, width: Float, height: Float): Offset {
    var dx = lonToX(lon, cam.zoom) - lonToX(cam.lon, cam.zoom)
    val world = worldSize(cam.zoom)
    if (dx > world / 2) dx -= world
    if (dx < -world / 2) dx += world
    val dy = latToY(lat, cam.zoom) - latToY(cam.lat, cam.zoom)
    return Offset((width / 2.0 + dx).toFloat(), (height / 2.0 + dy).toFloat())
}

private fun tileDraws(cam: Camera, width: Float, height: Float, tileZ: Int): List<TileDraw> {
    if (width <= 1f || height <= 1f) return emptyList()
    val z = tileZ.coerceIn(MIN_ZOOM.toInt(), MAX_ZOOM.toInt())
    val scale = 2.0.pow((cam.zoom - z).toDouble())
    if (!scale.isFinite() || scale <= 0.0) return emptyList()
    val camX = lonToX(cam.lon, z.toFloat())
    val camY = latToY(cam.lat, z.toFloat())
    val left = camX - (width / 2.0) / scale
    val top = camY - (height / 2.0) / scale
    val right = left + width / scale
    val bottom = top + height / scale
    var x0 = floor(left / 256.0).toInt() - 1
    var x1 = floor(right / 256.0).toInt() + 1
    var y0 = floor(top / 256.0).toInt() - 1
    var y1 = floor(bottom / 256.0).toInt() + 1
    if (x1 - x0 > 24) {
        val mid = (x0 + x1) / 2
        x0 = mid - 12
        x1 = mid + 12
    }
    if (y1 - y0 > 24) {
        val mid = (y0 + y1) / 2
        y0 = mid - 12
        y1 = mid + 12
    }
    val n = 1 shl z
    val size = (256.0 * scale).toFloat()
    val out = ArrayList<TileDraw>()
    for (tx in x0..x1) {
        val fetchX = Math.floorMod(tx, n)
        val sx = ((tx * 256.0 - left) * scale).toFloat()
        for (ty in y0..y1) {
            if (ty < 0 || ty >= n) continue
            val sy = ((ty * 256.0 - top) * scale).toFloat()
            out.add(TileDraw(z, fetchX, ty, sx, sy, size))
        }
    }
    return out
}

private fun DrawScope.blit(
    bmp: Bitmap,
    left: Float,
    top: Float,
    w: Float,
    h: Float,
    filter: ColorFilter?,
    src: IntOffset = IntOffset.Zero,
    srcSize: IntSize = IntSize(bmp.width, bmp.height),
) {
    if (bmp.isRecycled || bmp.width < 1 || bmp.height < 1 || w < 1f || h < 1f) return
    if (src.x < 0 || src.y < 0 || srcSize.width < 1 || srcSize.height < 1) return
    if (src.x + srcSize.width > bmp.width || src.y + srcSize.height > bmp.height) return
    val l = left.roundToInt()
    val t = top.roundToInt()
    val dw = ((left + w).roundToInt() - l + 1).coerceAtLeast(1)
    val dh = ((top + h).roundToInt() - t + 1).coerceAtLeast(1)
    drawImage(
        image = bmp.asImageBitmap(),
        srcOffset = src,
        srcSize = srcSize,
        dstOffset = IntOffset(l, t),
        dstSize = IntSize(dw, dh),
        colorFilter = filter,
        filterQuality = FilterQuality.Medium,
    )
}

private fun DrawScope.paintTile(tile: TileDraw, filter: ColorFilter?) {
    val exact = TileCache.peek(tile.z, tile.fetchX, tile.y)
    if (exact != null) {
        blit(exact, tile.left, tile.top, tile.size, tile.size, filter)
        return
    }
    blitParent(tile, filter)
    blitChildren(tile, filter)
}

/** Closest cached ancestor, cropped to this tile. Drawn under any child tiles. */
private fun DrawScope.blitParent(tile: TileDraw, filter: ColorFilter?) {
    val minZ = MIN_ZOOM.toInt()
    for (level in 1..6) {
        val z = tile.z - level
        if (z < minZ) return
        val span = 1 shl level
        val bmp = TileCache.peek(z, tile.fetchX shr level, tile.y shr level) ?: continue
        val subW = bmp.width / span
        val subH = bmp.height / span
        if (subW < 1 || subH < 1) continue
        val sx = (tile.fetchX and (span - 1)) * subW
        val sy = (tile.y and (span - 1)) * subH
        blit(
            bmp,
            tile.left,
            tile.top,
            tile.size,
            tile.size,
            filter,
            IntOffset(sx, sy),
            IntSize(subW, subH),
        )
        return
    }
}

/** One zoom closer, four quadrants, from the cache only. */
private fun DrawScope.blitChildren(tile: TileDraw, filter: ColorFilter?) {
    val z = tile.z + 1
    if (z > MAX_ZOOM.toInt()) return
    val half = tile.size / 2f
    if (half < 1f) return
    for (dx in 0..1) {
        for (dy in 0..1) {
            val bmp = TileCache.peek(z, tile.fetchX * 2 + dx, tile.y * 2 + dy) ?: continue
            blit(bmp, tile.left + dx * half, tile.top + dy * half, half, half, filter)
        }
    }
}

private fun DrawScope.drawMarker(
    center: Offset,
    radius: Float,
    bitmap: Bitmap?,
    letter: String,
    fill: Color,
    onFill: Color,
    ring: Color,
    halo: Color,
    radar: Color,
) {
    drawCircle(color = radar.copy(alpha = 0.16f), radius = radius * 2.7f, center = center)
    drawCircle(
        color = radar.copy(alpha = 0.32f),
        radius = radius * 2.05f,
        center = center,
        style = Stroke(width = 1.5.dp.toPx()),
    )
    drawCircle(
        color = radar.copy(alpha = 0.45f),
        radius = radius * 1.5f,
        center = center,
        style = Stroke(width = 1.5.dp.toPx()),
    )
    drawCircle(color = halo, radius = radius + 3.dp.toPx(), center = center)
    val bmp = bitmap
    if (bmp != null && !bmp.isRecycled && bmp.width > 0 && bmp.height > 0) {
        drawIntoCanvas { canvas ->
            val shader = BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            val side = min(bmp.width, bmp.height).toFloat().coerceAtLeast(1f)
            val scale = (radius * 2f) / side
            val matrix = AndroidMatrix()
            val dx = (bmp.width - side) / 2f
            val dy = (bmp.height - side) / 2f
            matrix.setScale(scale, scale)
            matrix.postTranslate(center.x - radius - dx * scale, center.y - radius - dy * scale)
            shader.setLocalMatrix(matrix)
            val paint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG)
            paint.shader = shader
            canvas.nativeCanvas.drawCircle(center.x, center.y, radius, paint)
        }
    } else {
        drawCircle(color = fill, radius = radius, center = center)
        drawIntoCanvas { canvas ->
            val paint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG)
            paint.color = onFill.toArgb()
            paint.textAlign = AndroidPaint.Align.CENTER
            paint.textSize = radius
            val text = letter.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
            val y = center.y - (paint.descent() + paint.ascent()) / 2f
            canvas.nativeCanvas.drawText(text, center.x, y, paint)
        }
    }
    drawCircle(color = ring, radius = radius, center = center, style = Stroke(width = 2.dp.toPx()))
}

/**
 * Invert, then hue-rotate 180°, so a dark map keeps water blue-ish and parks green-ish.
 * Built once. Applied only to tiles, not to the marker or the attribution.
 */
private val darkTileFilter: ColorFilter by lazy {
    val invert = android.graphics.ColorMatrix(
        floatArrayOf(
            -1f, 0f, 0f, 0f, 255f,
            0f, -1f, 0f, 0f, 255f,
            0f, 0f, -1f, 0f, 255f,
            0f, 0f, 0f, 1f, 0f,
        ),
    )
    invert.postConcat(hueRotate(180f))
    ColorFilter.colorMatrix(ComposeColorMatrix(invert.array.copyOf()))
}

private fun hueRotate(degrees: Float): android.graphics.ColorMatrix {
    val rad = Math.toRadians(degrees.toDouble())
    val cos = cos(rad).toFloat()
    val sin = sin(rad).toFloat()
    val lr = 0.213f
    val lg = 0.715f
    val lb = 0.072f
    return android.graphics.ColorMatrix(
        floatArrayOf(
            lr + cos * (1f - lr) + sin * (-lr),
            lg + cos * (-lg) + sin * (-lg),
            lb + cos * (-lb) + sin * (1f - lb),
            0f,
            0f,
            lr + cos * (-lr) + sin * 0.143f,
            lg + cos * (1f - lg) + sin * 0.140f,
            lb + cos * (-lb) + sin * (-0.283f),
            0f,
            0f,
            lr + cos * (-lr) + sin * -(1f - lr),
            lg + cos * (-lg) + sin * lg,
            lb + cos * (1f - lb) + sin * lb,
            0f,
            0f,
            0f,
            0f,
            0f,
            1f,
            0f,
        ),
    )
}

/**
 * Disk + memory cache for map tiles. The composable asks for the tiles on screen.
 * At most two downloads run at once, which is the OpenStreetMap tile-usage limit.
 *
 * DATA-FLOW: [fetch] is the only network call. See the comment on the connection.
 */
internal object TileCache {
    private val userAgent = "Betweenly/${BuildConfig.VERSION_NAME} (Android; com.nextlayer.betweenly)"
    private const val DISK_CAP = 30L * 1024L * 1024L
    private const val MAX_TILE_BYTES = 512 * 1024
    private const val MAX_REQUESTS = 96
    private const val WORKERS = 2

    private val mem = object : LruCache<String, Bitmap>(18 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = (value.byteCount / 1024).coerceAtLeast(1)
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private val dirLock = Any()
    private val queue = PriorityQueue<TileReq>(compareBy<TileReq> { it.rank }.thenBy { it.seq })
    private val wanted = HashSet<String>()
    private val inflight = HashMap<String, HttpsURLConnection>()
    private val pinned = LinkedHashMap<String, Bitmap>()
    private val retryAt = HashMap<String, Long>()
    private val fails = HashMap<String, Int>()
    private val seq = AtomicInteger()
    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision

    private var generation = 0
    private var workers = 0
    private var users = 0
    private var pinnedZ = -1

    fun peek(z: Int, x: Int, y: Int): Bitmap? = fresh(key(z, x, y))

    fun pinnedZoom(): Int = synchronized(lock) { pinnedZ }

    fun pin(z: Int, tiles: List<TileDraw>) {
        synchronized(lock) {
            pinned.clear()
            pinnedZ = z
            for (tile in tiles) {
                if (tile.z != z) continue
                val bmp = fresh(key(tile.z, tile.fetchX, tile.y)) ?: continue
                pinned[key(tile.z, tile.fetchX, tile.y)] = bmp
                if (pinned.size >= 80) break
            }
        }
    }

    fun clearPin() {
        synchronized(lock) {
            pinned.clear()
            pinnedZ = -1
        }
    }

    fun retain() {
        synchronized(lock) { users++ }
    }

    fun release() {
        val drop = ArrayList<HttpsURLConnection>()
        synchronized(lock) {
            users = (users - 1).coerceAtLeast(0)
            if (users > 0) return
            generation++
            wanted.clear()
            queue.clear()
            drop.addAll(inflight.values)
            inflight.clear()
        }
        for (conn in drop) {
            try {
                conn.disconnect()
            } catch (_: Exception) {
                // The read unblocks and the worker stops.
            }
        }
    }

    fun request(context: android.content.Context, tiles: List<TileDraw>, viewW: Float, viewH: Float) {
        val app = context.applicationContext
        val cx = viewW / 2f
        val cy = viewH / 2f
        val ordered = tiles
            .distinctBy { key(it.z, it.fetchX, it.y) }
            .sortedBy { tile ->
                val dx = tile.left + tile.size / 2f - cx
                val dy = tile.top + tile.size / 2f - cy
                dx * dx + dy * dy
            }
            .take(MAX_REQUESTS)
        val drop = ArrayList<HttpsURLConnection>()
        synchronized(lock) {
            generation++
            val gen = generation
            wanted.clear()
            queue.clear()
            val now = System.currentTimeMillis()
            var rank = 0
            for (tile in ordered) {
                val cacheKey = key(tile.z, tile.fetchX, tile.y)
                wanted.add(cacheKey)
                if (fresh(cacheKey) != null) continue
                val hold = retryAt[cacheKey] ?: 0L
                if (hold > now) continue
                queue.add(TileReq(tile.z, tile.fetchX, tile.y, rank, seq.incrementAndGet(), gen))
                rank++
            }
            val gone = inflight.keys.filter { it !in wanted }
            for (cacheKey in gone) {
                inflight.remove(cacheKey)?.let { drop.add(it) }
            }
            repeat(WORKERS) { ensureWorker(app) }
        }
        for (conn in drop) {
            try {
                conn.disconnect()
            } catch (_: Exception) {
                // The worker sees the tile leave the wanted set and stops.
            }
        }
    }

    private fun fresh(cacheKey: String): Bitmap? = synchronized(lock) {
        pinned[cacheKey]?.takeIf { !it.isRecycled }?.let { return it }
        mem.get(cacheKey)?.takeIf { !it.isRecycled }
    }

    private fun ensureWorker(context: android.content.Context) {
        synchronized(lock) {
            if (workers >= WORKERS) return
            workers++
        }
        scope.launch {
            try {
                while (isActive) {
                    val job = synchronized(lock) { queue.poll() } ?: break
                    if (!current(job)) continue
                    val bitmap = try {
                        loadOne(context, job)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        null
                    }
                    if (bitmap == null) continue
                    val cacheKey = key(job.z, job.x, job.y)
                    mem.put(cacheKey, bitmap)
                    val show = current(job)
                    synchronized(lock) {
                        fails.remove(cacheKey)
                        retryAt.remove(cacheKey)
                    }
                    if (show) _revision.update { it + 1 }
                }
            } finally {
                val again = synchronized(lock) {
                    workers = (workers - 1).coerceAtLeast(0)
                    queue.isNotEmpty() && workers < WORKERS
                }
                if (again) ensureWorker(context)
            }
        }
    }

    private fun current(job: TileReq): Boolean = synchronized(lock) {
        job.gen == generation && key(job.z, job.x, job.y) in wanted
    }

    private suspend fun loadOne(context: android.content.Context, job: TileReq): Bitmap? {
        val cacheKey = key(job.z, job.x, job.y)
        fresh(cacheKey)?.let { return it }
        val file = File(folder(context), "${job.z}_${job.x}_${job.y}.png")
        readDisk(file)?.let { return it }
        var wait = 700L
        repeat(4) { attempt ->
            if (!current(job)) return null
            when (val result = fetch(job, file)) {
                is FetchResult.Ready -> return result.bitmap
                FetchResult.Missing -> {
                    synchronized(lock) { retryAt[cacheKey] = System.currentTimeMillis() + 6L * 60L * 60L * 1000L }
                    return null
                }
                is FetchResult.Again -> {
                    if (attempt == 3 || !current(job)) {
                        synchronized(lock) {
                            val n = ((fails[cacheKey] ?: 0) + 1).coerceAtMost(6)
                            fails[cacheKey] = n
                            retryAt[cacheKey] = System.currentTimeMillis() + 20_000L * n
                        }
                        return null
                    }
                    delay(maxOf(wait, result.holdMs))
                    wait = (wait * 2).coerceAtMost(8_000L)
                }
            }
        }
        return null
    }

    private fun key(z: Int, x: Int, y: Int) = "$z/$x/$y"

    private fun folder(context: android.content.Context): File =
        File(context.applicationContext.cacheDir, "tiles").apply { mkdirs() }

    private fun readDisk(file: File): Bitmap? {
        synchronized(dirLock) {
            if (!file.isFile) return null
            val bitmap = BitmapFactory.decodeFile(file.absolutePath, decodeOpts())
            if (bitmap == null) {
                file.delete()
                return null
            }
            file.setLastModified(System.currentTimeMillis())
            return bitmap
        }
    }

    private fun fetch(job: TileReq, dest: File): FetchResult {
        val cacheKey = key(job.z, job.x, job.y)
        if (!current(job)) return FetchResult.Again(0L)
        val wrapped = Math.floorMod(job.x, 1 shl job.z)
        val url = URL("https://tile.openstreetmap.org/${job.z}/$wrapped/${job.y}.png")
        val connection = url.openConnection()
        if (connection !is HttpsURLConnection) return FetchResult.Again(0L)
        val skipped = synchronized(lock) {
            if (!current(job)) {
                true
            } else {
                inflight[cacheKey] = connection
                false
            }
        }
        if (skipped) {
            connection.disconnect()
            return FetchResult.Again(0L)
        }
        return try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.instanceFollowRedirects = true
            connection.useCaches = false
            // DATA-FLOW: one map tile PNG, from tile.openstreetmap.org, not encrypted.
            // The tile server sees this phone's IP and the square on screen (z/x/y).
            // It does not receive the partner's name or id. The partner's coordinates
            // are not sent as a location; a tile is fetched only if that square is on screen.
            // At most two of these run at once. The User-Agent names this app.
            connection.setRequestProperty("User-Agent", userAgent)
            connection.setRequestProperty("Accept", "image/png")
            val code = connection.responseCode
            when {
                code == 404 || code == 400 || code == 410 -> FetchResult.Missing
                code == 429 || code == 503 -> FetchResult.Again(5_000L)
                code !in 200..299 -> FetchResult.Again(0L)
                connection.contentLengthLong > MAX_TILE_BYTES -> FetchResult.Again(0L)
                !current(job) -> FetchResult.Again(0L)
                else -> {
                    val bytes = connection.inputStream.use { readCap(it, MAX_TILE_BYTES) }
                    if (bytes == null || !current(job)) {
                        FetchResult.Again(0L)
                    } else {
                        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOpts())
                        if (bitmap == null) {
                            FetchResult.Again(0L)
                        } else {
                            write(dest, bytes)
                            FetchResult.Ready(bitmap)
                        }
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            FetchResult.Again(0L)
        } finally {
            synchronized(lock) { if (inflight[cacheKey] === connection) inflight.remove(cacheKey) }
            connection.disconnect()
        }
    }

    private fun write(dest: File, bytes: ByteArray) {
        synchronized(dirLock) {
            val folder = dest.parentFile ?: return
            folder.mkdirs()
            val part = File(folder, dest.name + ".part")
            try {
                part.writeBytes(bytes)
                if (!part.renameTo(dest)) {
                    part.copyTo(dest, overwrite = true)
                    part.delete()
                }
                trim(folder)
            } catch (_: Exception) {
                part.delete()
            }
        }
    }

    private fun trim(folder: File) {
        val files = folder.listFiles() ?: return
        var total = 0L
        val tiles = ArrayList<File>()
        for (file in files) {
            if (file.name.endsWith(".part")) {
                file.delete()
                continue
            }
            total += file.length()
            tiles.add(file)
        }
        if (total <= DISK_CAP) return
        tiles.sortBy { it.lastModified() }
        for (file in tiles) {
            if (total <= DISK_CAP) break
            val len = file.length()
            if (file.delete()) total -= len
        }
    }

    private fun readCap(input: InputStream, max: Int): ByteArray? {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var total = 0
        while (total < max) {
            val n = input.read(buf, 0, minOf(buf.size, max - total))
            if (n < 0) break
            out.write(buf, 0, n)
            total += n
        }
        if (total >= max && input.read() >= 0) return null
        if (total == 0) return null
        return out.toByteArray()
    }

    private fun decodeOpts() = BitmapFactory.Options().apply {
        inPreferredConfig = Bitmap.Config.ARGB_8888
        inScaled = false
    }
}

private data class TileReq(
    val z: Int,
    val x: Int,
    val y: Int,
    val rank: Int,
    val seq: Int,
    val gen: Int,
)

private sealed interface FetchResult {
    class Ready(val bitmap: Bitmap) : FetchResult
    data object Missing : FetchResult
    class Again(val holdMs: Long) : FetchResult
}
