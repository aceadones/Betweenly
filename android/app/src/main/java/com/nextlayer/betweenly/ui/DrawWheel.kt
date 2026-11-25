package com.nextlayer.betweenly.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.nextlayer.betweenly.R
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * Hue ring, saturation/value square, hex field, and a live preview.
 * [onColor] runs while the finger is down. [onDone] only closes the sheet.
 */
@Composable
internal fun DrawWheelBody(
    colorArgb: Int,
    onColor: (Int) -> Unit,
    onDone: () -> Unit,
) {
    val start = remember {
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(colorArgb, hsv)
        hsv
    }
    var hue by remember { mutableFloatStateOf(start[0]) }
    var sat by remember { mutableFloatStateOf(start[1]) }
    var value by remember { mutableFloatStateOf(start[2]) }
    var hex by remember { mutableStateOf(hexText(colorArgb)) }
    val picked = DrawInk.hsv(hue, sat, value)
    fun fromWheel(nextHue: Float, nextSat: Float, nextValue: Float) {
        hue = nextHue
        sat = nextSat
        value = nextValue
        val argb = DrawInk.hsv(nextHue, nextSat, nextValue).toArgb()
        hex = hexText(argb)
        onColor(argb)
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 440.dp)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.draw_picker), style = LdType.Title, color = LdColors.colors.ink)
        HueRing(hue = hue, color = picked, onHue = { fromWheel(it, sat, value) })
        SvSquare(hue = hue, sat = sat, value = value, onChange = { s, v -> fromWheel(hue, s, v) })
        HexRow(
            hex = hex,
            color = picked,
            onHex = { next ->
                hex = next
                val argb = parseHex(next) ?: return@HexRow
                val hsv = FloatArray(3)
                android.graphics.Color.colorToHSV(argb, hsv)
                hue = hsv[0]
                sat = hsv[1]
                value = hsv[2]
                onColor(argb)
            },
        )
        SheetTextButton(stringResource(R.string.draw_done), onDone)
    }
}

@Composable
private fun HexRow(hex: String, color: Color, onHex: (String) -> Unit) {
    val ink = LdColors.colors.ink
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(color)
                .border(1.dp, LdColors.colors.ink.copy(alpha = 0.35f), CircleShape),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.draw_hex), style = LdType.Caption, color = LdColors.colors.inkMuted)
            BasicTextField(
                value = hex,
                onValueChange = { incoming ->
                    val body = incoming.uppercase().filter { it in '0'..'9' || it in 'A'..'F' }.take(6)
                    onHex("#$body")
                },
                modifier = Modifier.fillMaxWidth(),
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = ink),
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Characters,
                    keyboardType = KeyboardType.Ascii,
                ),
                cursorBrush = SolidColor(LdColors.primary),
                decorationBox = { inner ->
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .border(1.dp, LdColors.colors.menuBorder, RoundedCornerShape(12.dp))
                            .padding(horizontal = 12.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) { inner() }
                },
            )
        }
    }
}

@Composable
private fun HueRing(hue: Float, color: Color, onHue: (Float) -> Unit) {
    val stroke = 22.dp
    val hueState = rememberUpdatedState(onHue)
    val marker = LdColors.colors.menu
    Canvas(
        Modifier
            .size(188.dp)
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
        drawCircle(brush = Brush.sweepGradient(DrawInk.hueStops), style = Stroke(width = strokePx))
        val ringRadius = size.minDimension / 2f - strokePx / 2f
        val rad = Math.toRadians(hue.toDouble())
        val at = Offset(
            center.x + cos(rad).toFloat() * ringRadius,
            center.y + sin(rad).toFloat() * ringRadius,
        )
        drawCircle(marker, radius = strokePx / 2f + 2.dp.toPx(), center = at)
        drawCircle(color, radius = strokePx / 2f - 2.dp.toPx(), center = at)
    }
}

@Composable
private fun SvSquare(hue: Float, sat: Float, value: Float, onChange: (Float, Float) -> Unit) {
    val hueStep = hue.toInt().coerceIn(0, 359)
    val bitmap = remember { Bitmap.createBitmap(SQUARE, SQUARE, Bitmap.Config.ARGB_8888) }
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    val pixels = remember { IntArray(SQUARE * SQUARE) }
    val stamp = remember(hueStep) {
        fillSv(pixels, hueStep.toFloat())
        bitmap.setPixels(pixels, 0, SQUARE, 0, 0, SQUARE, SQUARE)
        hueStep
    }
    val view = LocalView.current
    DisposableEffect(bitmap) {
        onDispose { recycleLater(view, bitmap) }
    }
    val shape = RoundedCornerShape(12.dp)
    val changeState = rememberUpdatedState(onChange)
    Canvas(
        Modifier
            .size(156.dp)
            .clip(shape)
            .border(1.dp, LdColors.colors.menuBorder, shape)
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
        if (stamp < 0 || bitmap.isRecycled) return@Canvas
        image.prepareToDraw()
        drawImage(
            image = image,
            dstSize = IntSize(size.width.toInt().coerceAtLeast(1), size.height.toInt().coerceAtLeast(1)),
        )
        val center = Offset(sat * size.width, (1f - value) * size.height)
        drawCircle(LdColors.black, radius = 9.dp.toPx(), center = center, style = Stroke(width = 1.dp.toPx()))
        drawCircle(LdColors.white, radius = 8.dp.toPx(), center = center, style = Stroke(width = 2.dp.toPx()))
    }
}

@Composable
internal fun SheetTextButton(text: String, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(LdRadius.inner(LdRadius.card, 16.dp))
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .pressScale(interaction)
            .heightIn(min = 48.dp)
            .clip(shape)
            .background(LdColors.primary.copy(alpha = 0.12f))
            .clickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
                role = Role.Button,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = LdType.Title, color = LdColors.colors.ink)
    }
}

private fun hexText(argb: Int): String =
    "#" + (argb and 0xFFFFFF).toString(16).uppercase().padStart(6, '0')

private fun parseHex(text: String): Int? {
    val raw = text.trim().removePrefix("#")
    if (raw.length != 6) return null
    val rgb = raw.toIntOrNull(16) ?: return null
    return rgb or 0xFF000000.toInt()
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
