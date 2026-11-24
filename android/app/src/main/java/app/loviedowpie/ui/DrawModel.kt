package app.loviedowpie.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel

/** A point in 0..1 of the canvas, so a rotation does not stretch the line. */
data class NormPoint(val x: Float, val y: Float)

/**
 * One finished stroke.
 * [widthFraction] is the stroke width divided by the shorter side of the canvas
 * at the moment the stroke started, so export can redraw it at another size.
 * [erase] is resolved at draw time: white on white paper, clear on transparent.
 */
data class DrawStroke(
    val points: List<NormPoint>,
    val colorArgb: Int,
    val widthFraction: Float,
    val erase: Boolean,
)

/**
 * The in-progress drawing. Survives rotation and tab switches because it is
 * stored on the activity. A new [Store][app.loviedowpie.Store] generation
 * (unpair) gets a new instance via the viewModel key in [ActivityScreen].
 */
class DrawingViewModel : ViewModel() {
    var strokes by mutableStateOf<List<DrawStroke>>(emptyList())
        private set

    var canUndo by mutableStateOf(false)
        private set

    /** Bumped when the finger moves, so the canvas redraws without copying the point list. */
    var activeVersion by mutableIntStateOf(0)
        private set

    private val activePoints = ArrayList<NormPoint>()
    private val past = ArrayDeque<List<DrawStroke>>()
    private var activeColor: Int = 0xFF000000.toInt()
    private var activeFraction: Float = 0.02f
    private var activeErase: Boolean = false

    fun activePoints(): List<NormPoint> = activePoints

    fun begin(colorArgb: Int, widthFraction: Float, erase: Boolean) {
        activeColor = colorArgb
        activeFraction = widthFraction.coerceIn(0.002f, 0.25f)
        activeErase = erase
        activePoints.clear()
        activeVersion++
    }

    fun add(point: NormPoint) {
        if (activePoints.size >= MAX_POINTS) return
        val x = point.x.coerceIn(0f, 1f)
        val y = point.y.coerceIn(0f, 1f)
        if (activePoints.isNotEmpty()) {
            val last = activePoints.last()
            val dx = last.x - x
            val dy = last.y - y
            if (dx * dx + dy * dy < MIN_DIST_SQ) return
        }
        activePoints.add(NormPoint(x, y))
        activeVersion++
    }

    /** Commits the finger-down stroke. Safe to call twice. */
    fun end() {
        if (activePoints.isEmpty()) return
        val copy = activePoints.toList()
        activePoints.clear()
        pushPast()
        strokes = strokes + DrawStroke(copy, activeColor, activeFraction, activeErase)
        canUndo = past.isNotEmpty()
        activeVersion++
    }

    fun undo() {
        if (past.isEmpty()) return
        strokes = past.removeLast()
        canUndo = past.isNotEmpty()
    }

    fun clear() {
        if (strokes.isEmpty() && activePoints.isEmpty()) return
        if (strokes.isNotEmpty()) pushPast()
        strokes = emptyList()
        activePoints.clear()
        canUndo = past.isNotEmpty()
        activeVersion++
    }

    private fun pushPast() {
        past.addLast(strokes)
        while (past.size > MAX_UNDO) past.removeFirst()
    }

    private companion object {
        const val MAX_UNDO = 40
        const val MAX_POINTS = 8_000
        const val MIN_DIST_SQ = 0.0000025f
    }
}
