package app.loviedowpie.ui

import androidx.compose.ui.graphics.nativeCanvas
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Matrix as AndroidMatrix
import android.graphics.Paint as AndroidPaint
import android.graphics.Shader
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix as ComposeColorMatrix
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
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
import app.loviedowpie.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.URL
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
 * Pan with a drag, pinch to zoom between 3 and 19. Tiles are cached in memory
 * and under cacheDir/tiles (about 30 MB). In dark mode the tiles are drawn
 * through an invert + hue-rotate filter so roads stay readable.
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

    var follow by remember { mutableStateOf(true) }
    var camLat by remember { mutableDoubleStateOf(latitude) }
    var camLon by remember { mutableDoubleStateOf(longitude) }
    var zoom by remember { mutableFloatStateOf(15f) }
    var view by remember { mutableStateOf(IntSize.Zero) }
    var epoch by remember { mutableIntStateOf(0) }

    LaunchedEffect(latitude, longitude) {
        if (follow) {
            camLat = latitude
            camLon = longitude
        }
    }
    LaunchedEffect(recenterToken) {
        if (recenterToken == 0) return@LaunchedEffect
        follow = true
        camLat = latitude
        camLon = longitude
    }

    val draws = if (view.width == 0 || view.height == 0) {
        emptyList()
    } else {
        tileDraws(Camera(camLat, camLon, zoom), view.width.toFloat(), view.height.toFloat())
    }
    val tileKey = draws.joinToString(";") { "${it.z}/${it.fetchX}/${it.y}" }
    LaunchedEffect(tileKey) {
        if (draws.isEmpty()) return@LaunchedEffect
        val wanted = draws.distinctBy { "${it.z}/${it.fetchX}/${it.y}" }
        coroutineScope {
            for (tile in wanted) {
                launch {
                    val had = TileCache.peek(tile.z, tile.fetchX, tile.y) != null
                    TileCache.load(context, tile.z, tile.fetchX, tile.y)
                    if (!had) epoch++
                }
            }
        }
    }
    // epoch is read so a finished download redraws the canvas.
    val redraw = epoch

    Box(
        modifier
            .background(placeholder)
            .onSizeChanged { view = it }
            .semantics { contentDescription = mapCd },
    ) {
        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    val slop = viewConfiguration.touchSlop
                    val markerRadius = 22.dp.toPx()
                    fun applyGesture(pan: Offset, zoomChange: Float, focal: Offset) {
                        val width = view.width.toFloat()
                        val height = view.height.toFloat()
                        if (width <= 1f || height <= 1f) return
                        var cam = Camera(camLat, camLon, zoom)
                        if (pan.x != 0f || pan.y != 0f) {
                            cam = panCamera(cam, pan.x, pan.y, width, height)
                        }
                        if (zoomChange.isFinite() && zoomChange > 0f && zoomChange != 1f) {
                            cam = zoomCamera(cam, zoomChange, focal.x, focal.y, width, height)
                        }
                        camLat = cam.lat
                        camLon = cam.lon
                        zoom = cam.zoom
                    }
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val start = down.position
                        var pastSlop = false
                        var acc = Offset.Zero
                        var pressed = true
                        while (pressed) {
                            val event = awaitPointerEvent()
                            val pan = event.calculatePan()
                            val zoomChange = event.calculateZoom()
                            val fingers = event.changes.count { it.pressed }
                            val zooming = fingers >= 2 &&
                                zoomChange.isFinite() &&
                                abs(zoomChange - 1f) > 0.01f
                            if (!pastSlop) {
                                acc += pan
                                if (acc.getDistance() > slop || zooming) {
                                    pastSlop = true
                                    follow = false
                                    applyGesture(
                                        acc,
                                        if (zooming) zoomChange else 1f,
                                        event.calculateCentroid(useCurrent = true),
                                    )
                                }
                            } else {
                                applyGesture(pan, zoomChange, event.calculateCentroid(useCurrent = true))
                            }
                            if (pastSlop) {
                                event.changes.forEach { change ->
                                    if (change.positionChanged()) change.consume()
                                }
                            }
                            pressed = event.changes.any { it.pressed }
                        }
                        if (!pastSlop && view.width > 0 && view.height > 0) {
                            val point = markerOffset(
                                latState.value,
                                lonState.value,
                                Camera(camLat, camLon, zoom),
                                view.width.toFloat(),
                                view.height.toFloat(),
                            )
                            if ((start - point).getDistance() <= markerRadius + 16f) {
                                markerClick.value()
                            } else {
                                mapClick.value()
                            }
                        }
                    }
                },
        ) {
            val unused = redraw
            if (unused < 0) return@Canvas
            drawRect(placeholder)
            for (tile in draws) {
                val bmp = TileCache.peek(tile.z, tile.fetchX, tile.y)
                if (bmp == null || bmp.isRecycled) {
                    drawRect(
                        color = placeholder,
                        topLeft = Offset(tile.left, tile.top),
                        size = Size(tile.size, tile.size),
                    )
                } else {
                    val side = tile.size.roundToInt().coerceAtLeast(1) + 1
                    drawImage(
                        image = bmp.asImageBitmap(),
                        dstOffset = IntOffset(tile.left.roundToInt(), tile.top.roundToInt()),
                        dstSize = IntSize(side, side),
                        colorFilter = tileFilter,
                        filterQuality = FilterQuality.Low,
                    )
                }
            }
            if (size.width > 0f && size.height > 0f) {
                val center = markerOffset(
                    latitude,
                    longitude,
                    Camera(camLat, camLon, zoom),
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
        Surface(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 8.dp, bottom = 8.dp, end = 88.dp),
            shape = RoundedCornerShape(12.dp),
            color = LdColors.colors.glassStrong,
            shadowElevation = 0.dp,
            tonalElevation = 0.dp,
        ) {
            Text(
                stringResource(R.string.map_attribution),
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                style = MaterialTheme.typography.labelSmall,
                color = LdColors.colors.ink,
            )
        }
    }
}

private data class Camera(val lat: Double, val lon: Double, val zoom: Float)

private data class TileDraw(
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

private fun tileDraws(cam: Camera, width: Float, height: Float): List<TileDraw> {
    if (width <= 1f || height <= 1f) return emptyList()
    val tileZ = cam.zoom.toInt().coerceIn(MIN_ZOOM.toInt(), MAX_ZOOM.toInt())
    val scale = 2.0.pow((cam.zoom - tileZ).toDouble())
    if (!scale.isFinite() || scale <= 0.0) return emptyList()
    val camX = lonToX(cam.lon, tileZ.toFloat())
    val camY = latToY(cam.lat, tileZ.toFloat())
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
    val n = 1 shl tileZ
    val size = (256.0 * scale).toFloat()
    val out = ArrayList<TileDraw>()
    for (tx in x0..x1) {
        val fetchX = Math.floorMod(tx, n)
        val sx = ((tx * 256.0 - left) * scale).toFloat()
        for (ty in y0..y1) {
            if (ty < 0 || ty >= n) continue
            val sy = ((ty * 256.0 - top) * scale).toFloat()
            out.add(TileDraw(tileZ, fetchX, ty, sx, sy, size))
        }
    }
    return out
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawMarker(
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
 * Disk + memory cache for map tiles. The composable above asks for the tiles
 * currently on screen.
 *
 * DATA-FLOW: [fetch] is the only network call. See the comment on the connection.
 */
internal object TileCache {
    private const val USER_AGENT = "Loviedowpie/1.0"
    private const val DISK_CAP = 30L * 1024L * 1024L
    private const val MAX_TILE_BYTES = 512 * 1024

    private val mem = object : LruCache<String, Bitmap>(8 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = (value.byteCount / 1024).coerceAtLeast(1)
    }
    private val gate = Semaphore(4)
    private val dirLock = Any()

    fun peek(z: Int, x: Int, y: Int): Bitmap? = mem.get(key(z, x, y))

    suspend fun load(context: android.content.Context, z: Int, x: Int, y: Int): Bitmap? {
        val cacheKey = key(z, x, y)
        mem.get(cacheKey)?.let { return it }
        val file = File(folder(context), "${z}_${x}_${y}.png")
        readDisk(file)?.let { bitmap ->
            mem.put(cacheKey, bitmap)
            return bitmap
        }
        return withContext(Dispatchers.IO) {
            gate.withPermit {
                if (!isActive) return@withPermit null
                mem.get(cacheKey)?.let { return@withPermit it }
                readDisk(file)?.let { bitmap ->
                    mem.put(cacheKey, bitmap)
                    return@withPermit bitmap
                }
                val bitmap = fetch(z, x, y, file) ?: return@withPermit null
                mem.put(cacheKey, bitmap)
                bitmap
            }
        }
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

    private fun fetch(z: Int, x: Int, y: Int, dest: File): Bitmap? {
        val wrapped = Math.floorMod(x, 1 shl z)
        val url = URL("https://tile.openstreetmap.org/$z/$wrapped/$y.png")
        val connection = url.openConnection()
        if (connection !is HttpsURLConnection) return null
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
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.setRequestProperty("Accept", "image/png")
            if (connection.responseCode !in 200..299) return null
            if (connection.contentLengthLong > MAX_TILE_BYTES) return null
            val bytes = connection.inputStream.use { readCap(it, MAX_TILE_BYTES) } ?: return null
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOpts()) ?: return null
            write(dest, bytes)
            bitmap
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } finally {
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
