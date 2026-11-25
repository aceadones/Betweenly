package app.loviedowpie.ui

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.view.View
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * One brush, drawn with [android.graphics.Canvas] so a stroke can grow one
 * segment at a time. Replay builds the same pixels: every dot, grain speck,
 * and nib stamp comes from [unitHash], not from a clock.
 *
 * Highlighter and marker stay SrcOver. A real Multiply or Darken blend against
 * a transparent ink layer drops the color, so the paper would never show through.
 * Low alpha on a wide stroke is the multiply-like look, and overlaps darken
 * as the alphas stack. The eraser uses [PorterDuff.Mode.CLEAR] on this same
 * layer, which reveals whatever paper is composited underneath.
 */
internal class StrokeStyle(
    val kind: BrushKind,
    val colorArgb: Int,
    val sizePx: Float,
    val opacity: Float,
    val erase: Boolean,
)

internal class StrokePainter(
    private val canvas: Canvas,
    private val style: StrokeStyle,
    private val seed: Int,
) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val oval = RectF()
    private val fade = style.opacity.coerceIn(0.05f, 1f)
    private val width = widthFor(style)
    private var started = false
    private var lastX = 0f
    private var lastY = 0f
    private var midX = 0f
    private var midY = 0f
    private var seg = 0
    private var carry = 0f

    init {
        paint.strokeJoin = if (!style.erase && (style.kind == BrushKind.Marker || style.kind == BrushKind.Highlighter)) {
            Paint.Join.BEVEL
        } else {
            Paint.Join.ROUND
        }
        paint.strokeCap = capFor(style)
        paint.strokeWidth = width
        paint.color = style.colorArgb
        paint.style = Paint.Style.STROKE
        if (style.erase) {
            paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        } else if (style.kind == BrushKind.Watercolor) {
            paint.maskFilter = blur(style.sizePx * 0.42f, 22f)
        } else if (style.kind == BrushKind.Neon) {
            glow.style = Paint.Style.STROKE
            glow.strokeCap = Paint.Cap.ROUND
            glow.strokeJoin = Paint.Join.ROUND
            glow.color = style.colorArgb
            glow.strokeWidth = (style.sizePx * 2.2f).coerceAtLeast(2f)
            glow.maskFilter = blur(style.sizePx * 0.65f, 26f)
            glow.alpha = (fade * 220f).toInt().coerceIn(1, 255)
            paint.color = lighten(style.colorArgb, 0.78f)
            paint.strokeWidth = (style.sizePx * 0.38f).coerceAtLeast(1f)
        }
    }

    fun start(x: Float, y: Float) {
        started = true
        lastX = x
        lastY = y
        midX = x
        midY = y
        carry = 0f
        seg = 0
        stamp(x, y)
    }

    fun extend(x: Float, y: Float) {
        if (!started) {
            start(x, y)
            return
        }
        val nx = (lastX + x) * 0.5f
        val ny = (lastY + y) * 0.5f
        seg++
        drawSegment(midX, midY, lastX, lastY, nx, ny, line = false, seg = seg)
        midX = nx
        midY = ny
        lastX = x
        lastY = y
    }

    fun finish() {
        if (!started) return
        seg++
        drawSegment(midX, midY, lastX, lastY, lastX, lastY, line = true, seg = seg)
    }

    private fun drawSegment(
        x0: Float,
        y0: Float,
        cx: Float,
        cy: Float,
        x1: Float,
        y1: Float,
        line: Boolean,
        seg: Int,
    ) {
        if (!style.erase && (style.kind == BrushKind.Spray || style.kind == BrushKind.Dotted || style.kind == BrushKind.Calligraphy)) {
            trace(x0, y0, cx, cy, x1, y1, line, seg)
            return
        }
        if (line && hypot(x1 - x0, y1 - y0) < 0.4f) return
        applyAlpha(seg)
        path.rewind()
        path.moveTo(x0, y0)
        if (line) path.lineTo(x1, y1) else path.quadTo(cx, cy, x1, y1)
        if (!style.erase && style.kind == BrushKind.Neon) canvas.drawPath(path, glow)
        canvas.drawPath(path, paint)
        if (!style.erase && style.kind == BrushKind.Pencil) grain(x1, y1, seg)
    }

    private fun trace(
        x0: Float,
        y0: Float,
        cx: Float,
        cy: Float,
        x1: Float,
        y1: Float,
        line: Boolean,
        token: Int,
    ) {
        val spacing = spacing()
        if (line) {
            walk(x0, y0, x1, y1, token, spacing)
            return
        }
        val approx = hypot(cx - x0, cy - y0) + hypot(x1 - cx, y1 - cy)
        val steps = (approx / 2.5f).toInt().coerceIn(1, 32)
        var px = x0
        var py = y0
        for (i in 1..steps) {
            val t = i / steps.toFloat()
            val x = bez(t, x0, cx, x1)
            val y = bez(t, y0, cy, y1)
            walk(px, py, x, y, token * 32 + i, spacing)
            px = x
            py = y
        }
    }

    private fun walk(x0: Float, y0: Float, x1: Float, y1: Float, token: Int, spacing: Float) {
        val dx = x1 - x0
        val dy = y1 - y0
        val dist = hypot(dx, dy)
        if (dist < 0.01f || spacing < 0.5f) return
        val ux = dx / dist
        val uy = dy / dist
        var traveled = 0f
        var i = 0
        while (i < 800) {
            val need = spacing - carry
            if (need <= 0.01f) {
                emit(x0 + ux * traveled, y0 + uy * traveled, token + i)
                carry = 0f
                i++
                continue
            }
            if (traveled + need > dist) {
                carry += dist - traveled
                return
            }
            traveled += need
            carry = 0f
            emit(x0 + ux * traveled, y0 + uy * traveled, token + i)
            i++
        }
    }

    private fun emit(x: Float, y: Float, token: Int) {
        when (style.kind) {
            BrushKind.Spray -> cluster(x, y, token)
            BrushKind.Dotted -> {
                applyAlpha(token)
                fillCircle(x, y, style.sizePx * 0.34f)
            }
            BrushKind.Calligraphy -> {
                applyAlpha(token)
                nib(x, y)
            }
            else -> Unit
        }
    }

    private fun stamp(x: Float, y: Float) {
        applyAlpha(0)
        if (style.erase) {
            fillCircle(x, y, paint.strokeWidth / 2f)
            return
        }
        when (style.kind) {
            BrushKind.Marker -> flat(x, y, paint.strokeWidth, paint.strokeWidth * 0.72f)
            BrushKind.Highlighter -> flat(x, y, paint.strokeWidth, paint.strokeWidth * 0.42f)
            BrushKind.Spray -> cluster(x, y, 0)
            BrushKind.Dotted -> fillCircle(x, y, style.sizePx * 0.34f)
            BrushKind.Calligraphy -> nib(x, y)
            BrushKind.Neon -> {
                glow.style = Paint.Style.FILL
                canvas.drawCircle(x, y, glow.strokeWidth / 2f, glow)
                glow.style = Paint.Style.STROKE
                fillCircle(x, y, paint.strokeWidth / 2f)
            }
            else -> fillCircle(x, y, paint.strokeWidth / 2f)
        }
    }

    private fun fillCircle(x: Float, y: Float, radius: Float) {
        paint.style = Paint.Style.FILL
        canvas.drawCircle(x, y, radius.coerceAtLeast(0.5f), paint)
        paint.style = Paint.Style.STROKE
    }

    private fun flat(x: Float, y: Float, w: Float, h: Float) {
        paint.style = Paint.Style.FILL
        canvas.drawRect(x - w / 2f, y - h / 2f, x + w / 2f, y + h / 2f, paint)
        paint.style = Paint.Style.STROKE
    }

    private fun nib(x: Float, y: Float) {
        val rx = style.sizePx * 0.55f
        val ry = (style.sizePx * 0.15f).coerceAtLeast(0.7f)
        canvas.save()
        canvas.translate(x, y)
        canvas.rotate(-36f)
        oval.set(-rx, -ry, rx, ry)
        paint.style = Paint.Style.FILL
        canvas.drawOval(oval, paint)
        canvas.restore()
        paint.style = Paint.Style.STROKE
    }

    private fun cluster(x: Float, y: Float, token: Int) {
        val spread = style.sizePx * 0.52f
        val radius = (style.sizePx * 0.075f).coerceAtLeast(0.6f)
        val count = (4f + style.sizePx / 8f).toInt().coerceIn(4, 12)
        paint.style = Paint.Style.FILL
        for (k in 0 until count) {
            val ang = unitHash(seed, token, k) * 6.2831855f
            val dist = sqrt(unitHash(seed, token, k + 40)) * spread
            paint.alpha = (fade * (0.25f + 0.75f * unitHash(seed, token, k + 80)) * 255f).toInt().coerceIn(1, 255)
            canvas.drawCircle(x + cos(ang) * dist, y + sin(ang) * dist, radius, paint)
        }
        paint.style = Paint.Style.STROKE
    }

    private fun grain(x: Float, y: Float, seg: Int) {
        paint.style = Paint.Style.FILL
        for (k in 0 until 3) {
            val ox = (unitHash(seed, seg, 10 + k) - 0.5f) * style.sizePx * 0.9f
            val oy = (unitHash(seed, seg, 20 + k) - 0.5f) * style.sizePx * 0.9f
            paint.alpha = (fade * unitHash(seed, seg, 30 + k) * 120f).toInt().coerceIn(0, 255)
            if (paint.alpha == 0) continue
            canvas.drawCircle(x + ox, y + oy, (style.sizePx * 0.06f).coerceAtLeast(0.4f), paint)
        }
        paint.style = Paint.Style.STROKE
    }

    private fun applyAlpha(seg: Int) {
        val scale = when {
            style.erase -> 1f
            style.kind == BrushKind.Pencil -> 0.55f + 0.45f * unitHash(seed, seg, 1)
            style.kind == BrushKind.Marker -> 0.62f
            style.kind == BrushKind.Highlighter -> 0.34f
            style.kind == BrushKind.Watercolor -> 0.22f
            else -> 1f
        }
        paint.alpha = (fade * scale * 255f).toInt().coerceIn(1, 255)
    }

    private fun spacing(): Float {
        val s = style.sizePx.coerceIn(1f, 64f)
        return when (style.kind) {
            BrushKind.Spray -> (s * 0.4f).coerceAtLeast(2f)
            BrushKind.Dotted -> (s * 1.65f).coerceAtLeast(3f)
            BrushKind.Calligraphy -> (s * 0.14f).coerceAtLeast(1.2f)
            else -> s
        }
    }
}

internal fun replayStroke(canvas: Canvas, stroke: DrawStroke, side: Int) {
    val pts = stroke.points
    if (pts.isEmpty() || side <= 1) return
    val painter = StrokePainter(
        canvas,
        StrokeStyle(stroke.kind, stroke.colorArgb, stroke.sizePx, stroke.opacity, stroke.erase),
        stroke.seed,
    )
    painter.start(pts[0].x * side, pts[0].y * side)
    for (i in 1 until pts.size) painter.extend(pts[i].x * side, pts[i].y * side)
    painter.finish()
}

/** A short sample of [kind] in [colorArgb], for the brush sheet. */
internal fun brushPreviewBitmap(kind: BrushKind, colorArgb: Int): Bitmap {
    val bitmap = try {
        Bitmap.createBitmap(168, 56, Bitmap.Config.ARGB_8888)
    } catch (_: OutOfMemoryError) {
        return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
    }
    return try {
        val canvas = Canvas(bitmap)
        val size = when (kind) {
            BrushKind.Highlighter -> 14f
            BrushKind.Watercolor -> 12f
            BrushKind.Spray -> 10f
            BrushKind.Pencil -> 5f
            else -> 7f
        }
        val painter = StrokePainter(canvas, StrokeStyle(kind, colorArgb, size, 1f, false), 11)
        val xs = floatArrayOf(10f, 36f, 70f, 108f, 156f)
        val ys = floatArrayOf(40f, 18f, 36f, 16f, 30f)
        painter.start(xs[0], ys[0])
        for (i in 1 until xs.size) painter.extend(xs[i], ys[i])
        painter.finish()
        bitmap
    } catch (_: Exception) {
        bitmap
    } catch (_: OutOfMemoryError) {
        bitmap
    }
}

/** Two traversals later, so a bitmap is not recycled on the frame that still draws it. */
internal fun recycleLater(view: View, bitmap: Bitmap) {
    view.post {
        view.post {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }
}

private fun widthFor(style: StrokeStyle): Float {
    val s = style.sizePx.coerceIn(1f, 64f)
    val w = when {
        style.erase -> s
        style.kind == BrushKind.Pencil -> s * 0.45f
        style.kind == BrushKind.Marker -> s * 1.2f
        style.kind == BrushKind.Highlighter -> s * 2.75f
        style.kind == BrushKind.Watercolor -> s * 1.65f
        style.kind == BrushKind.Neon -> s * 0.38f
        else -> s
    }
    return w.coerceAtLeast(1f)
}

private fun capFor(style: StrokeStyle): Paint.Cap {
    if (style.erase) return Paint.Cap.ROUND
    return when (style.kind) {
        BrushKind.Marker, BrushKind.Highlighter -> Paint.Cap.SQUARE
        else -> Paint.Cap.ROUND
    }
}

private fun blur(radius: Float, cap: Float): BlurMaskFilter? = try {
    BlurMaskFilter(radius.coerceIn(1f, cap), BlurMaskFilter.Blur.NORMAL)
} catch (_: Exception) {
    null
}

private fun lighten(color: Int, towardWhite: Float): Int {
    val t = towardWhite.coerceIn(0f, 1f)
    val r = (color shr 16) and 0xFF
    val g = (color shr 8) and 0xFF
    val b = color and 0xFF
    fun mix(c: Int) = (c + (255 - c) * t).toInt().coerceIn(0, 255)
    return (0xFF shl 24) or (mix(r) shl 16) or (mix(g) shl 8) or mix(b)
}

private fun bez(t: Float, a: Float, b: Float, c: Float): Float {
    val u = 1f - t
    return u * u * a + 2f * u * t * b + t * t * c
}

/** Stable 0..1 value. Same inputs always return the same speck. */
private fun unitHash(a: Int, b: Int, c: Int): Float {
    var x = a * 0x27BB2EE6 + b * 0x165667B1 + c * 0x1B873593
    x = x xor (x ushr 16)
    x *= 0x7FEB352D
    x = x xor (x ushr 15)
    x *= 0x846CA68BL.toInt()
    x = x xor (x ushr 16)
    return (x and 0xFFFFFF) / 16777215f
}
