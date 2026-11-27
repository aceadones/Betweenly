package com.nextlayer.betweenly.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.ViewModel
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

/** A point in 0..1 of the paper, so a rotation does not stretch the line. */
internal data class DrawPt(val x: Float, val y: Float)

internal enum class BrushKind {
    Solid,
    Pencil,
    Marker,
    Highlighter,
    Spray,
    Calligraphy,
    Dotted,
    Watercolor,
    Neon,
}

/**
 * [argb] is null for a transparent export. The screen draws a checker behind it.
 * Disk composites in DrawStore use these same four values.
 */
internal enum class PaperKind(val argb: Int?) {
    White(0xFFFFFFFF.toInt()),
    Cream(0xFFF3E6D0.toInt()),
    Dark(0xFF17191F.toInt()),
    Clear(null),
}

/** Shared is the default page both people draw on. Separate keeps a private page. */
internal enum class CanvasMode { Shared, Separate }

/**
 * One finished stroke. [sizePx] is in the ink bitmap's pixels (the 1–64 slider),
 * not in screen dp, so export matches what was drawn.
 */
internal data class DrawStroke(
    val points: List<DrawPt>,
    val kind: BrushKind,
    val colorArgb: Int,
    val sizePx: Float,
    val opacity: Float,
    val erase: Boolean,
    val seed: Int,
)

/**
 * Tool choices only: a brush, a color, a size. Nothing here is a drawing or a secret.
 * The file name is the SharedPreferences name "draw".
 */
internal object DrawPrefs {
    private const val FILE = "draw"
    private const val BRUSH = "brush"
    private const val COLOR = "color"
    private const val SIZE = "size"
    private const val OPACITY = "opacity"
    private const val PAPER = "paper"
    private const val ERASER = "eraser"
    private const val RECENT = "recent"
    private const val MODE = "mode"

    data class Saved(
        val brush: BrushKind,
        val color: Int,
        val size: Float,
        val opacity: Float,
        val paper: PaperKind,
        val eraser: Boolean,
        val recents: List<Int>,
    )

    fun read(context: Context): Saved {
        val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val sizeRaw = prefs.getFloat(SIZE, 8f)
        val size = (if (sizeRaw.isFinite()) sizeRaw else 8f).roundToInt().coerceIn(1, 64).toFloat()
        val opacityRaw = prefs.getFloat(OPACITY, 1f)
        val opacity = if (opacityRaw.isFinite()) {
            (opacityRaw * 100f).roundToInt().coerceIn(5, 100) / 100f
        } else {
            1f
        }
        return Saved(
            brush = enumOr(prefs.getString(BRUSH, null), BrushKind.Solid),
            color = opaque(prefs.getInt(COLOR, DEFAULT_COLOR)),
            size = size,
            opacity = opacity,
            paper = enumOr(prefs.getString(PAPER, null), PaperKind.White),
            eraser = prefs.getBoolean(ERASER, false),
            recents = parseRecents(prefs.getString(RECENT, null)),
        )
    }

    fun readMode(context: Context): CanvasMode {
        val name = try {
            context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
                .getString(MODE, null)
        } catch (_: Exception) {
            null
        }
        return if (name == CanvasMode.Separate.name) CanvasMode.Separate else CanvasMode.Shared
    }

    fun writeMode(context: Context, mode: CanvasMode) {
        try {
            context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
                .putString(MODE, mode.name)
                .apply()
        } catch (_: Exception) {
            // The canvas stays in the mode already on screen.
        }
    }

    fun write(context: Context, model: DrawingViewModel) {
        try {
            val recent = model.recents.joinToString(",") { (it and 0xFFFFFF).toString(16).padStart(6, '0') }
            context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
                .putString(BRUSH, model.brush.name)
                .putInt(COLOR, model.colorArgb)
                .putFloat(SIZE, model.sizePx)
                .putFloat(OPACITY, model.opacity)
                .putString(PAPER, model.paper.name)
                .putBoolean(ERASER, model.eraser)
                .putString(RECENT, recent)
                .apply()
        } catch (_: Exception) {
            // A prefs failure only forgets the tool. The canvas stays.
        }
    }
}

/**
 * The in-progress drawing. Strokes are replayed from the list. After 200,
 * the oldest is baked into [base] so undo stays a short replay. The ink
 * bitmap is what the screen draws; it already includes [base].
 *
 * DATA-FLOW: none. Bytes leave the phone only when the screen calls [com.nextlayer.betweenly.DrawSend].
 */
internal class DrawingViewModel : ViewModel() {
    // Read-only from outside. A var would compile to setBrush/setOpacity/setPaper/setEraser
    // and clash with the methods below (same JVM signature).
    private var brushState by mutableStateOf(BrushKind.Solid)
    private var opacityState by mutableFloatStateOf(1f)
    private var paperState by mutableStateOf(PaperKind.White)
    private var eraserState by mutableStateOf(false)

    val brush: BrushKind get() = brushState
    var colorArgb by mutableIntStateOf(DEFAULT_COLOR)
        private set
    var sizePx by mutableFloatStateOf(8f)
        private set
    val opacity: Float get() = opacityState
    val paper: PaperKind get() = paperState
    val eraser: Boolean get() = eraserState
    var recents by mutableStateOf<List<Int>>(emptyList())
        private set

    var canUndo by mutableStateOf(false)
        private set
    var canRedo by mutableStateOf(false)
        private set
    var empty by mutableStateOf(true)
        private set
    var frame by mutableIntStateOf(0)
        private set
    var drawing by mutableStateOf(false)
        private set
    var cursor by mutableStateOf<DrawPt?>(null)
        private set

    private val ink: Bitmap
    private val base: Bitmap
    private val inkCanvas: Canvas
    private val baseCanvas: Canvas
    val image: ImageBitmap
    val side: Int
    val alive: Boolean

    private val strokes = ArrayList<DrawStroke>()
    private val undo = ArrayDeque<Undo>()
    private val redo = ArrayDeque<Undo>()
    private var baseInk = false
    private var active: ArrayList<DrawPt>? = null
    private var activePainter: StrokePainter? = null
    private var activeStyle: StrokeStyle? = null
    private var activeSeed = 1
    private var nextSeed = 1
    private var editGen = 0
    private var savedGen = 0
    private var loaded = false
    private var boundGen = Int.MIN_VALUE

    val canExport: Boolean
        get() = alive && (drawing || !empty)

    init {
        val pair = allocate(SIDE) ?: allocate(SIDE / 2)
        if (pair == null) {
            alive = false
            side = 1
            ink = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
            base = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        } else {
            alive = true
            side = pair.first.width
            ink = pair.first
            base = pair.second
        }
        ink.eraseColor(0)
        base.eraseColor(0)
        inkCanvas = Canvas(ink)
        baseCanvas = Canvas(base)
        image = ink.asImageBitmap()
    }

    fun canDraw(): Boolean = alive && side > 1 && !ink.isRecycled

    fun load(saved: DrawPrefs.Saved) {
        if (loaded) return
        loaded = true
        brushState = saved.brush
        colorArgb = saved.color
        sizePx = saved.size
        opacityState = saved.opacity
        paperState = saved.paper
        eraserState = saved.eraser
        recents = saved.recents
    }

    /** Wipes the canvas when the pair generation changes (unpair). */
    fun watchGeneration(gen: Int) {
        if (boundGen == Int.MIN_VALUE) {
            boundGen = gen
            return
        }
        if (boundGen == gen) return
        boundGen = gen
        wipe()
    }

    fun setBrush(kind: BrushKind) {
        brushState = kind
    }

    fun setColor(color: Int) {
        colorArgb = opaque(color)
    }

    fun setSize(px: Float) {
        sizePx = px.coerceIn(1f, 64f)
    }

    fun setOpacity(value: Float) {
        opacityState = value.coerceIn(0.05f, 1f)
    }

    fun setPaper(kind: PaperKind) {
        paperState = kind
    }

    fun setEraser(on: Boolean) {
        eraserState = on
    }

    fun pushRecent(color: Int) {
        val nextColor = opaque(color)
        val next = ArrayList<Int>(8)
        next.add(nextColor)
        for (old in recents) {
            if ((old and 0xFFFFFF) == (nextColor and 0xFFFFFF)) continue
            next.add(opaque(old))
            if (next.size == 8) break
        }
        recents = next
    }

    fun beginStroke(x: Float, y: Float) {
        if (!canDraw() || drawing) return
        val nx = x.coerceIn(0f, 1f)
        val ny = y.coerceIn(0f, 1f)
        val pts = ArrayList<DrawPt>(64)
        pts.add(DrawPt(nx, ny))
        val style = currentStyle()
        val seed = nextSeed++
        active = pts
        activeStyle = style
        activeSeed = seed
        activePainter = StrokePainter(Canvas(ink), style, seed, ink)
        activePainter?.start(nx * side, ny * side)
        drawing = true
        if (eraser) cursor = DrawPt(nx, ny)
        frame++
    }

    fun moveStroke(x: Float, y: Float) {
        val pts = active ?: return
        val painter = activePainter ?: return
        val nx = x.coerceIn(0f, 1f)
        val ny = y.coerceIn(0f, 1f)
        if (eraser) cursor = DrawPt(nx, ny)
        if (pts.size >= MAX_POINTS) {
            frame++
            return
        }
        val last = pts.last()
        val dx = (nx - last.x) * side
        val dy = (ny - last.y) * side
        if (dx * dx + dy * dy < MIN_DIST * MIN_DIST) {
            if (eraser) frame++
            return
        }
        pts.add(DrawPt(nx, ny))
        painter.extend(nx * side, ny * side)
        frame++
    }

    fun endStroke() {
        val pts = active ?: return
        val painter = activePainter
        val style = activeStyle
        val seed = activeSeed
        active = null
        activePainter = null
        activeStyle = null
        drawing = false
        cursor = null
        try {
            painter?.finish()
        } catch (_: Exception) {
            // A bad segment should not drop the points already stored.
        } catch (_: OutOfMemoryError) {
            // Same. The stroke list still undoes what did land.
        }
        if (style == null || pts.isEmpty()) {
            syncFlags()
            frame++
            return
        }
        commit(
            DrawStroke(
                points = pts.toList(),
                kind = style.kind,
                colorArgb = style.colorArgb,
                sizePx = style.sizePx,
                opacity = style.opacity,
                erase = style.erase,
                seed = seed,
            ),
        )
    }

    fun undo() {
        if (drawing) endStroke()
        if (undo.isEmpty()) return
        val op = undo.removeAt(undo.lastIndex)
        when (op) {
            is Undo.Add -> {
                strokes.removeAll { it === op.stroke }
                redo.add(op)
                rebuild()
            }
            is Undo.Clear -> {
                strokes.clear()
                strokes.addAll(op.strokes)
                restoreBase(op.base, op.hadBase)
                redo.add(op)
                rebuild()
            }
        }
        editGen++
    }

    fun redo() {
        if (drawing) endStroke()
        if (redo.isEmpty()) return
        val op = redo.removeAt(redo.lastIndex)
        when (op) {
            is Undo.Add -> {
                strokes.add(op.stroke)
                undo.add(op)
                rebuild()
            }
            is Undo.Clear -> {
                strokes.clear()
                wipeBase()
                undo.add(op)
                rebuild()
            }
        }
        editGen++
    }

    fun clear() {
        if (drawing) endStroke()
        if (!hasInk()) return
        val snap = copyBase()
        pushUndo(Undo.Clear(strokes.toList(), snap, snap != null))
        strokes.clear()
        wipeBase()
        ink.eraseColor(0)
        editGen++
        syncFlags()
        frame++
    }

    /** Drops pixels and history. Tool choices stay. */
    fun wipe() {
        active = null
        activePainter = null
        activeStyle = null
        drawing = false
        cursor = null
        strokes.clear()
        while (undo.isNotEmpty()) release(undo.removeAt(0))
        while (redo.isNotEmpty()) release(redo.removeAt(0))
        wipeBase()
        if (!ink.isRecycled) ink.eraseColor(0)
        editGen = 0
        savedGen = 0
        syncFlags()
        frame++
    }

    fun generation(): Int = editGen

    fun markSaved(gen: Int) {
        savedGen = gen
    }

    fun needsSave(): Boolean = editGen != savedGen && hasInk()

    /** True when this phone's layer has strokes. The partner layer is not included. */
    fun hasLayer(): Boolean = hasInk()

    /**
     * Replaces this phone's ink with [source], scaled to the paper.
     * Undo history is dropped. Tool choices stay. Call on the main thread.
     * A null source leaves the layer clear. Does not count as an unsaved edit.
     */
    fun installInk(source: Bitmap?) {
        if (!canDraw()) return
        active = null
        activePainter = null
        activeStyle = null
        drawing = false
        cursor = null
        strokes.clear()
        while (undo.isNotEmpty()) release(undo.removeAt(0))
        while (redo.isNotEmpty()) release(redo.removeAt(0))
        if (!base.isRecycled) base.eraseColor(0)
        if (!ink.isRecycled) ink.eraseColor(0)
        val usable = source != null && !source.isRecycled && source.width > 0 && source.height > 0
        if (usable && source != null) {
            try {
                val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
                val dst = Rect(0, 0, side, side)
                baseCanvas.drawBitmap(source, null, dst, paint)
                inkCanvas.drawBitmap(base, 0f, 0f, null)
                baseInk = true
            } catch (_: Exception) {
                if (!base.isRecycled) base.eraseColor(0)
                if (!ink.isRecycled) ink.eraseColor(0)
                baseInk = false
            } catch (_: OutOfMemoryError) {
                if (!base.isRecycled) base.eraseColor(0)
                if (!ink.isRecycled) ink.eraseColor(0)
                baseInk = false
            }
        } else {
            baseInk = false
        }
        editGen = 0
        savedGen = 0
        syncFlags()
        frame++
    }

    /**
     * A copy of this phone's ink only, transparent where there is no stroke.
     * Call on the main thread. The caller recycles the copy.
     */
    fun exportLayer(): Bitmap? {
        if (!canDraw()) return null
        endStroke()
        if (!hasInk()) return null
        return try {
            ink.copy(Bitmap.Config.ARGB_8888, false)
        } catch (_: Exception) {
            null
        } catch (_: OutOfMemoryError) {
            null
        }
    }

    /**
     * Paper, then [partner] (their layer), then this phone's ink on top.
     * Null when both layers are empty. Call on the main thread. The caller recycles it.
     * This is the picture Saved drawings keeps. It is never uploaded.
     */
    fun renderComposite(partner: Bitmap?): Bitmap? {
        if (!canDraw()) return null
        endStroke()
        val mine = hasInk()
        val theirs = partner != null && !partner.isRecycled && partner.width > 0 && partner.height > 0
        if (!mine && !theirs) return null
        val out = try {
            Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        } catch (_: OutOfMemoryError) {
            return null
        }
        return try {
            val canvas = Canvas(out)
            val bg = paper.argb
            if (bg == null) out.eraseColor(0) else canvas.drawColor(bg)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            if (theirs && partner != null) {
                canvas.drawBitmap(partner, null, Rect(0, 0, side, side), paint)
            }
            if (mine) canvas.drawBitmap(ink, 0f, 0f, null)
            out
        } catch (_: Exception) {
            if (!out.isRecycled) out.recycle()
            null
        } catch (_: OutOfMemoryError) {
            if (!out.isRecycled) out.recycle()
            null
        }
    }

    /** Paper composited under the ink. Call on the main thread; the copy is independent after return. */
    fun renderExport(): Bitmap? {
        if (!canDraw()) return null
        endStroke()
        if (!hasInk()) return null
        val out = try {
            Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        } catch (_: OutOfMemoryError) {
            return null
        }
        return try {
            val canvas = Canvas(out)
            val bg = paper.argb
            if (bg == null) out.eraseColor(0) else canvas.drawColor(bg)
            canvas.drawBitmap(ink, 0f, 0f, null)
            out
        } catch (_: Exception) {
            out.recycle()
            null
        } catch (_: OutOfMemoryError) {
            out.recycle()
            null
        }
    }

    override fun onCleared() {
        activePainter = null
        while (undo.isNotEmpty()) release(undo.removeAt(0))
        while (redo.isNotEmpty()) release(redo.removeAt(0))
        if (!ink.isRecycled) ink.recycle()
        if (base !== ink && !base.isRecycled) base.recycle()
    }

    private fun commit(stroke: DrawStroke) {
        pushUndo(Undo.Add(stroke))
        strokes.add(stroke)
        while (strokes.size > MAX_STROKES) {
            val old = strokes.first()
            try {
                replayStroke(baseCanvas, old, side)
            } catch (_: Exception) {
                break
            } catch (_: OutOfMemoryError) {
                break
            }
            strokes.removeAt(0)
            baseInk = true
        }
        editGen++
        syncFlags()
        frame++
    }

    private fun rebuild() {
        if (ink.isRecycled) return
        ink.eraseColor(0)
        if (baseInk && !base.isRecycled) inkCanvas.drawBitmap(base, 0f, 0f, null)
        for (stroke in strokes) {
            try {
                replayStroke(inkCanvas, stroke, side)
            } catch (_: Exception) {
                break
            } catch (_: OutOfMemoryError) {
                break
            }
        }
        syncFlags()
        frame++
    }

    private fun pushUndo(op: Undo) {
        undo.add(op)
        while (undo.size > MAX_UNDO) release(undo.removeAt(0))
        while (redo.isNotEmpty()) release(redo.removeAt(0))
    }

    private fun restoreBase(copy: Bitmap?, had: Boolean) {
        base.eraseColor(0)
        if (had && copy != null && !copy.isRecycled) {
            baseCanvas.drawBitmap(copy, 0f, 0f, null)
            baseInk = true
        } else {
            baseInk = false
        }
    }

    private fun wipeBase() {
        if (!base.isRecycled) base.eraseColor(0)
        baseInk = false
    }

    private fun copyBase(): Bitmap? {
        if (!baseInk || base.isRecycled) return null
        return try {
            base.copy(Bitmap.Config.ARGB_8888, true)
        } catch (_: Exception) {
            null
        } catch (_: OutOfMemoryError) {
            null
        }
    }

    private fun release(op: Undo) {
        if (op is Undo.Clear) {
            val bmp = op.base ?: return
            if (!bmp.isRecycled) bmp.recycle()
        }
    }

    private fun syncFlags() {
        canUndo = undo.isNotEmpty()
        canRedo = redo.isNotEmpty()
        empty = !hasInk()
    }

    private fun hasInk(): Boolean = baseInk || strokes.isNotEmpty()

    private fun currentStyle(): StrokeStyle = StrokeStyle(
        kind = if (eraser) BrushKind.Solid else brush,
        colorArgb = colorArgb,
        sizePx = sizePx.coerceIn(1f, 64f),
        opacity = opacity.coerceIn(0.05f, 1f),
        erase = eraser,
    )

    private sealed class Undo {
        class Add(val stroke: DrawStroke) : Undo()
        class Clear(val strokes: List<DrawStroke>, val base: Bitmap?, val hadBase: Boolean) : Undo()
    }

    private companion object {
        const val SIDE = 1024
        const val MAX_STROKES = 200
        const val MAX_UNDO = 40
        const val MAX_POINTS = 6_000
        const val MIN_DIST = 3f
    }
}

internal fun bitmapToPng(bitmap: Bitmap): ByteArray? {
    return try {
        val out = ByteArrayOutputStream()
        if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) return null
        out.toByteArray().takeIf { it.isNotEmpty() }
    } catch (_: Exception) {
        null
    } catch (_: OutOfMemoryError) {
        null
    }
}

private const val DEFAULT_COLOR = 0xFF3A7BD5.toInt()

private fun opaque(color: Int): Int = (color and 0xFFFFFF) or 0xFF000000.toInt()

private fun parseRecents(raw: String?): List<Int> {
    if (raw.isNullOrBlank()) return emptyList()
    val out = ArrayList<Int>(8)
    for (part in raw.split(',')) {
        val rgb = part.trim().toIntOrNull(16) ?: continue
        out.add(opaque(rgb))
        if (out.size == 8) break
    }
    return out
}

private inline fun <reified T : Enum<T>> enumOr(name: String?, default: T): T {
    if (name.isNullOrEmpty()) return default
    return try {
        enumValueOf(name)
    } catch (_: Exception) {
        default
    }
}

private fun allocate(size: Int): Pair<Bitmap, Bitmap>? {
    val ink = try {
        Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    } catch (_: OutOfMemoryError) {
        return null
    }
    val base = try {
        Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    } catch (_: OutOfMemoryError) {
        ink.recycle()
        return null
    }
    return ink to base
}
