package app.loviedowpie.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import app.loviedowpie.R
import kotlin.math.min

/**
 * The paper. The ink bitmap is clipped to the same rounded rect as the card,
 * so a stroke cannot poke out of the corner. Export stays square; this clip is only on screen.
 */
@Composable
internal fun StudioPaper(
    model: DrawingViewModel,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(LdRadius.card)
    val description = stringResource(R.string.draw_canvas_cd)
    val border = when (model.paper) {
        PaperKind.Dark -> Color.White.copy(alpha = 0.22f)
        else -> Color.Black.copy(alpha = 0.10f)
    }
    val shadow = LdColors.colors.shadow
    Canvas(
        modifier
            .shadow(12.dp, shape, clip = false, ambientColor = shadow, spotColor = shadow)
            .clip(shape)
            .border(1.dp, border, shape)
            .semantics { contentDescription = description }
            .pointerInput(enabled, model) {
                if (!enabled || !model.canDraw()) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val pointerId = down.id
                    val w = size.width.toFloat().coerceAtLeast(1f)
                    val h = size.height.toFloat().coerceAtLeast(1f)
                    var started = false
                    fun accept(offset: Offset) {
                        val nx = (offset.x / w).coerceIn(0f, 1f)
                        val ny = (offset.y / h).coerceIn(0f, 1f)
                        if (!started) {
                            model.beginStroke(nx, ny)
                            started = true
                        } else {
                            model.moveStroke(nx, ny)
                        }
                    }
                    try {
                        accept(down.position)
                        down.consume()
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == pointerId } ?: break
                            accept(change.position)
                            change.consume()
                            if (!change.pressed) break
                        }
                    } finally {
                        model.endStroke()
                    }
                }
            }
            .fillMaxSize(),
    ) {
        val frame = model.frame
        if (frame < 0 || !model.canDraw()) return@Canvas
        model.image.prepareToDraw()
        if (model.paper == PaperKind.Clear) {
            drawChecker(16.dp.toPx())
        } else {
            val argb = model.paper.argb
            if (argb != null) drawRect(Color(argb))
        }
        drawImage(
            image = model.image,
            dstSize = IntSize(size.width.toInt().coerceAtLeast(1), size.height.toInt().coerceAtLeast(1)),
            filterQuality = FilterQuality.Medium,
        )
        val cursor = model.cursor
        if (model.eraser && model.drawing && cursor != null && model.side > 1) {
            val radius = (model.sizePx / model.side.toFloat()) * min(size.width, size.height) / 2f
            val center = Offset(cursor.x * size.width, cursor.y * size.height)
            drawCircle(Color.Black.copy(alpha = 0.55f), radius, center, style = Stroke(width = 1.5.dp.toPx()))
            drawCircle(Color.White.copy(alpha = 0.92f), radius, center, style = Stroke(width = 1.dp.toPx()))
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawChecker(cell: Float) {
    val dark = Color(0xFFD9D9DE)
    val light = Color(0xFFF4F4F6)
    val step = cell.coerceAtLeast(4f)
    var y = 0f
    var row = 0
    while (y < size.height) {
        var x = 0f
        var col = 0
        while (x < size.width) {
            drawRect(
                color = if ((row + col) % 2 == 0) light else dark,
                topLeft = Offset(x, y),
                size = Size(step, step),
            )
            x += step
            col++
        }
        y += step
        row++
    }
}
