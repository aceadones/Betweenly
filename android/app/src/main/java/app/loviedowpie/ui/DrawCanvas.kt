package app.loviedowpie.ui

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import java.io.ByteArrayOutputStream
import kotlin.math.min
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Path as AndroidPath

/**
 * The finger canvas. Strokes are smoothed with quadratic segments through
 * midpoints. An offscreen layer lets the eraser punch holes without clearing
 * the rest of the window.
 */
@Composable
fun DrawCanvas(
    model: DrawingViewModel,
    colorArgb: Int,
    widthDp: Float,
    erase: Boolean,
    whitePaper: Boolean,
    contentDescription: String,
    modifier: Modifier = Modifier,
) {
    val colorState = rememberUpdatedState(colorArgb)
    val widthState = rememberUpdatedState(widthDp)
    val eraseState = rememberUpdatedState(erase)
    val shape = MaterialTheme.shapes.large
    val paper = if (whitePaper) LdColors.paper else LdColors.clear
    val behind = MaterialTheme.colorScheme.surfaceVariant
    val outline = MaterialTheme.colorScheme.outline
    val version = model.activeVersion
    Canvas(
        modifier
            .fillMaxSize()
            .border(1.dp, outline, shape)
            .background(behind, shape)
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .semantics { this.contentDescription = contentDescription }
            .pointerInput(model) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val minSide = min(size.width, size.height).toFloat()
                    if (minSide < 2f) return@awaitEachGesture
                    val fraction = (widthState.value * density / minSide).coerceIn(0.002f, 0.25f)
                    model.begin(colorState.value, fraction, eraseState.value)
                    try {
                        fun accept(offset: Offset) {
                            val w = size.width.toFloat()
                            val h = size.height.toFloat()
                            if (w < 2f || h < 2f) return
                            model.add(NormPoint(offset.x / w, offset.y / h))
                        }
                        down.historical.forEach { accept(it.position) }
                        accept(down.position)
                        down.consume()
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            change.historical.forEach { accept(it.position) }
                            accept(change.position)
                            change.consume()
                            if (!change.pressed) break
                        }
                    } finally {
                        model.end()
                    }
                }
            },
    ) {
        // [version] is read so a new point redraws this canvas only.
        if (version < 0) return@Canvas
        if (whitePaper) drawRect(paper)
        model.strokes.forEach { stroke ->
            drawStroke(stroke.points, stroke.colorArgb, stroke.widthFraction, stroke.erase, whitePaper)
        }
        if (size.minDimension >= 2f) {
            val fraction = (widthDp * density / size.minDimension).coerceIn(0.002f, 0.25f)
            drawStroke(model.activePoints(), colorArgb, fraction, erase, whitePaper)
        }
    }
}

private fun DrawScope.drawStroke(
    points: List<NormPoint>,
    colorArgb: Int,
    widthFraction: Float,
    erase: Boolean,
    whitePaper: Boolean,
) {
    if (points.isEmpty()) return
    val minSide = min(size.width, size.height)
    val strokePx = (widthFraction * minSide).coerceAtLeast(1f)
    val offsets = ArrayList<Offset>(points.size)
    for (point in points) {
        offsets.add(Offset(point.x * size.width, point.y * size.height))
    }
    val color = when {
        erase && whitePaper -> LdColors.paper
        erase -> LdColors.clear
        else -> Color(colorArgb)
    }
    val mode = if (erase && !whitePaper) BlendMode.Clear else BlendMode.SrcOver
    if (offsets.size == 1) {
        drawCircle(color = color, radius = strokePx / 2f, center = offsets[0], blendMode = mode)
        return
    }
    drawPath(
        path = smoothPath(offsets),
        color = color,
        style = Stroke(width = strokePx, cap = StrokeCap.Round, join = StrokeJoin.Round),
        blendMode = mode,
    )
}

/** Quadratic segments through the midpoint of each pair, then a short line to the last point. */
internal fun smoothPath(points: List<Offset>): Path {
    val path = Path()
    path.moveTo(points[0].x, points[0].y)
    if (points.size == 2) {
        path.lineTo(points[1].x, points[1].y)
        return path
    }
    for (i in 1 until points.lastIndex) {
        val control = points[i]
        val next = points[i + 1]
        path.quadraticBezierTo(
            control.x,
            control.y,
            (control.x + next.x) / 2f,
            (control.y + next.y) / 2f,
        )
    }
    val last = points.last()
    path.lineTo(last.x, last.y)
    return path
}

/** Longest side of an export, in pixels. Keeps a drawing well under the relay's 24 MB cap. */
fun exportPixels(widthPx: Int, heightPx: Int): Pair<Int, Int> {
    if (widthPx <= 1 || heightPx <= 1) return 0 to 0
    val maxEdge = 1280
    val longest = maxOf(widthPx, heightPx)
    val scale = min(1f, maxEdge.toFloat() / longest.toFloat())
    return (widthPx * scale).toInt().coerceAtLeast(1) to (heightPx * scale).toInt().coerceAtLeast(1)
}

/**
 * Renders [strokes] into a PNG-ready bitmap. White paper is opaque white.
 * Transparent paper keeps the eraser as real alpha.
 */
fun renderDrawing(
    strokes: List<DrawStroke>,
    widthPx: Int,
    heightPx: Int,
    whitePaper: Boolean,
): Bitmap? {
    if (strokes.isEmpty() || widthPx <= 1 || heightPx <= 1) return null
    return try {
        drawToBitmap(strokes, widthPx, heightPx, whitePaper)
            ?: drawToBitmap(strokes, widthPx / 2, heightPx / 2, whitePaper)
    } catch (_: Exception) {
        null
    } catch (_: OutOfMemoryError) {
        null
    }
}

private fun drawToBitmap(
    strokes: List<DrawStroke>,
    widthPx: Int,
    heightPx: Int,
    whitePaper: Boolean,
): Bitmap? {
    if (widthPx <= 1 || heightPx <= 1) return null
    val bitmap = try {
        Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
    } catch (_: OutOfMemoryError) {
        return null
    }
    try {
        val canvas = AndroidCanvas(bitmap)
        if (whitePaper) {
            canvas.drawColor(LdColors.paper.toArgb())
        } else {
            canvas.drawColor(LdColors.clear.toArgb(), PorterDuff.Mode.CLEAR)
        }
        val minSide = min(widthPx, heightPx).toFloat()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        for (stroke in strokes) {
            if (stroke.points.isEmpty()) continue
            paint.strokeWidth = (stroke.widthFraction * minSide).coerceAtLeast(1f)
            if (stroke.erase && !whitePaper) {
                paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
                paint.color = LdColors.clear.toArgb()
            } else if (stroke.erase) {
                paint.xfermode = null
                paint.color = LdColors.paper.toArgb()
            } else {
                paint.xfermode = null
                paint.color = stroke.colorArgb
            }
            if (stroke.points.size == 1) {
                val p = stroke.points[0]
                paint.style = Paint.Style.FILL
                canvas.drawCircle(p.x * widthPx, p.y * heightPx, paint.strokeWidth / 2f, paint)
                paint.style = Paint.Style.STROKE
                continue
            }
            canvas.drawPath(androidSmoothPath(stroke.points, widthPx.toFloat(), heightPx.toFloat()), paint)
        }
        return bitmap
    } catch (_: Exception) {
        bitmap.recycle()
        return null
    } catch (_: OutOfMemoryError) {
        bitmap.recycle()
        return null
    }
}

private fun androidSmoothPath(points: List<NormPoint>, width: Float, height: Float): AndroidPath {
    val path = AndroidPath()
    fun x(i: Int) = points[i].x * width
    fun y(i: Int) = points[i].y * height
    path.moveTo(x(0), y(0))
    if (points.size == 2) {
        path.lineTo(x(1), y(1))
        return path
    }
    for (i in 1 until points.lastIndex) {
        val nx = x(i + 1)
        val ny = y(i + 1)
        path.quadTo(x(i), y(i), (x(i) + nx) / 2f, (y(i) + ny) / 2f)
    }
    path.lineTo(x(points.lastIndex), y(points.lastIndex))
    return path
}

fun bitmapToPng(bitmap: Bitmap): ByteArray? {
    return try {
        val out = ByteArrayOutputStream()
        if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) return null
        out.toByteArray().takeIf { it.isNotEmpty() }
    } catch (_: Exception) {
        null
    }
}
