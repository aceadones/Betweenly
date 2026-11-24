package app.loviedowpie.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import app.loviedowpie.R
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * Hue ring plus a saturation/value square. No color-picker library:
 * the ring is a sweep gradient, the square is a small HSV bitmap.
 */
@Composable
fun DrawColorDialog(
    initialArgb: Int,
    onChange: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val initial = remember(initialArgb) {
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(initialArgb, hsv)
        hsv
    }
    var hue by remember { mutableFloatStateOf(initial[0]) }
    var sat by remember { mutableFloatStateOf(initial[1]) }
    var value by remember { mutableFloatStateOf(initial[2]) }
    val picked = DrawInk.hsv(hue, sat, value)
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                modifier = Modifier
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(stringResource(R.string.draw_picker), style = MaterialTheme.typography.titleMedium)
                HueRing(
                    hue = hue,
                    color = picked,
                    marker = MaterialTheme.colorScheme.surface,
                    onHue = { next ->
                        hue = next
                        onChange(DrawInk.hsv(next, sat, value).toArgb())
                    },
                )
                SvSquare(
                    hue = hue,
                    sat = sat,
                    value = value,
                    onChange = { s, v ->
                        sat = s
                        value = v
                        onChange(DrawInk.hsv(hue, s, v).toArgb())
                    },
                )
                TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.draw_done))
                }
            }
        }
    }
}

@Composable
private fun HueRing(
    hue: Float,
    color: Color,
    marker: Color,
    onHue: (Float) -> Unit,
) {
    val stroke = 28.dp
    val hueState = rememberUpdatedState(onHue)
    Canvas(
        Modifier
            .size(220.dp)
            .pointerInput(Unit) {
                val strokePx = stroke.toPx()
                val slop = 12.dp.toPx()
                awaitEachGesture {
                    val down = awaitFirstDown()
                    fun hueAt(offset: Offset): Float? {
                        val dx = offset.x - size.width / 2f
                        val dy = offset.y - size.height / 2f
                        val dist = hypot(dx, dy)
                        val outer = min(size.width, size.height) / 2f
                        val inner = outer - strokePx
                        if (dist < inner - slop || dist > outer + slop) return null
                        var deg = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
                        if (deg < 0f) deg += 360f
                        return deg
                    }
                    val first = hueAt(down.position) ?: return@awaitEachGesture
                    hueState.value(first)
                    down.consume()
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        hueAt(change.position)?.let { hueState.value(it) }
                        change.consume()
                        if (!change.pressed) break
                    }
                }
            },
    ) {
        val strokePx = stroke.toPx()
        drawCircle(
            brush = Brush.sweepGradient(DrawInk.hueStops),
            style = Stroke(width = strokePx),
        )
        val ringRadius = size.minDimension / 2f - strokePx / 2f
        val rad = Math.toRadians(hue.toDouble())
        val at = Offset(
            center.x + cos(rad).toFloat() * ringRadius,
            center.y + sin(rad).toFloat() * ringRadius,
        )
        drawCircle(marker, radius = strokePx / 2f + 2.dp.toPx(), center = at)
        drawCircle(color, radius = strokePx / 2f - 2.dp.toPx(), center = at)
        drawCircle(color, radius = 28.dp.toPx())
        drawCircle(marker, radius = 28.dp.toPx(), style = Stroke(width = 2.dp.toPx()))
    }
}

@Composable
private fun SvSquare(
    hue: Float,
    sat: Float,
    value: Float,
    onChange: (Float, Float) -> Unit,
) {
    val hueStep = hue.toInt().coerceIn(0, 359)
    val bitmap = remember {
        Bitmap.createBitmap(SQUARE, SQUARE, Bitmap.Config.ARGB_8888)
    }
    val pixels = remember { IntArray(SQUARE * SQUARE) }
    val stamp = remember(hueStep) {
        fillSv(pixels, hueStep.toFloat())
        bitmap.setPixels(pixels, 0, SQUARE, 0, 0, SQUARE, SQUARE)
        hueStep
    }
    DisposableEffect(bitmap) {
        onDispose {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }
    val outline = MaterialTheme.colorScheme.outline
    val changeState = rememberUpdatedState(onChange)
    Canvas(
        Modifier
            .size(180.dp)
            .border(1.dp, outline, MaterialTheme.shapes.small)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    fun apply(offset: Offset) {
                        val s = (offset.x / size.width).coerceIn(0f, 1f)
                        val v = (1f - offset.y / size.height).coerceIn(0f, 1f)
                        changeState.value(s, v)
                    }
                    apply(down.position)
                    down.consume()
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        apply(change.position)
                        change.consume()
                        if (!change.pressed) break
                    }
                }
            },
    ) {
        if (stamp < 0) return@Canvas
        drawImage(
            image = bitmap.asImageBitmap(),
            dstSize = IntSize(size.width.toInt().coerceAtLeast(1), size.height.toInt().coerceAtLeast(1)),
        )
        val center = Offset(sat * size.width, (1f - value) * size.height)
        drawCircle(LdColors.black, radius = 9.dp.toPx(), center = center, style = Stroke(width = 1.dp.toPx()))
        drawCircle(LdColors.white, radius = 8.dp.toPx(), center = center, style = Stroke(width = 2.dp.toPx()))
    }
}

private const val SQUARE = 128

private fun fillSv(pixels: IntArray, hue: Float) {
    val hsv = FloatArray(3)
    hsv[0] = hue
    val last = (SQUARE - 1).toFloat()
    for (y in 0 until SQUARE) {
        hsv[2] = 1f - y / last
        val row = y * SQUARE
        for (x in 0 until SQUARE) {
            hsv[1] = x / last
            pixels[row + x] = android.graphics.Color.HSVToColor(hsv)
        }
    }
}
