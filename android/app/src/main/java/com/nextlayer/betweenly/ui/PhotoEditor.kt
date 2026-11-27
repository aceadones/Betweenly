package com.nextlayer.betweenly.ui

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Paint as NativePaint
import android.graphics.Typeface
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nextlayer.betweenly.R
import com.nextlayer.betweenly.ui.icons.Lu
import com.nextlayer.betweenly.ui.icons.LuIcon
import java.io.ByteArrayOutputStream
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Edits one photo before it is sent: crop, rotate, text, and drawing.
 * Everything is plain Canvas work on a downsized copy of the picture, so there
 * is no extra library and no network use. The result is a new JPEG.
 *
 * DATA-FLOW: none. The photo stays on this phone until the user presses send in chat.
 */
private class EditText(var text: String, var u: Float, var v: Float, var size: Float, var color: Int)

private class EditStroke(val color: Int, val width: Float, val pts: MutableList<Offset> = mutableListOf())

private enum class Tool { Crop, Text, Draw }

private val editColors = listOf(
    0xFFFFFFFF.toInt(), 0xFF111111.toInt(), 0xFFD62448.toInt(), 0xFFFFC21F.toInt(),
    0xFF2FB36B.toInt(), 0xFF2E7BE8.toInt(), 0xFFFF7BA8.toInt(), 0xFF8E4DDB.toInt(),
)

@Composable
fun PhotoEditor(source: Bitmap, onDone: (ByteArray) -> Unit, onCancel: () -> Unit) {
    BackHandler(onBack = onCancel)
    var bmp by remember { mutableStateOf(source) }
    var tool by remember { mutableStateOf(Tool.Draw) }
    var cl by remember { mutableStateOf(0f) }
    var ct by remember { mutableStateOf(0f) }
    var cr by remember { mutableStateOf(1f) }
    var cb by remember { mutableStateOf(1f) }
    val texts = remember { mutableStateListOf<EditText>() }
    val strokes = remember { mutableStateListOf<EditStroke>() }
    var selected by remember { mutableIntStateOf(-1) }
    var color by remember { mutableIntStateOf(editColors[2]) }
    var brush by remember { mutableStateOf(0.012f) }
    var adding by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }
    var tick by remember { mutableIntStateOf(0) }

    fun set4(l: Float, t: Float, r: Float, b: Float) { cl = l; ct = t; cr = r; cb = b }

    fun rotate() {
        val m = Matrix().apply { postRotate(90f) }
        bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        for (t in texts) { val u = t.u; t.u = 1f - t.v; t.v = u }
        for (s in strokes) for (i in s.pts.indices) { val p = s.pts[i]; s.pts[i] = Offset(1f - p.y, p.x) }
        val l = cl; val t0 = ct; val r = cr; val b = cb
        cl = 1f - b; ct = l; cr = 1f - t0; cb = r
        tick++
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF0E0709))
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            GlassIconButton(onCancel, Lu.X, stringResource(R.string.chat_close), solid = true)
            Spacer(Modifier.weight(1f))
            GlassIconButton(
                {
                    if (strokes.isNotEmpty()) strokes.removeAt(strokes.lastIndex)
                    else if (texts.isNotEmpty()) texts.removeAt(texts.lastIndex)
                    selected = -1
                },
                Lu.Undo2, stringResource(R.string.draw_undo), solid = true,
            )
            Spacer(Modifier.size(8.dp))
            val done = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
            Box(
                Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(LdColors.primary)
                    .clickable(done, null) {
                        onDone(export(bmp, cl, ct, cr, cb, texts, strokes))
                    },
                contentAlignment = Alignment.Center,
            ) { LuIcon(Lu.Check, stringResource(R.string.photo_edit_done), tint = Color.White) }
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp)) {
            val boxW = constraints.maxWidth.toFloat()
            val boxH = constraints.maxHeight.toFloat()
            val iw = bmp.width.toFloat()
            val ih = bmp.height.toFloat()
            // Region of the picture shown: all of it while cropping, the crop otherwise.
            val rl = if (tool == Tool.Crop) 0f else cl
            val rt = if (tool == Tool.Crop) 0f else ct
            val rr = if (tool == Tool.Crop) 1f else cr
            val rb = if (tool == Tool.Crop) 1f else cb
            val regW = (rr - rl) * iw
            val regH = (rb - rt) * ih
            val fit = min(boxW / regW, boxH / regH)
            val dw = regW * fit
            val dh = regH * fit
            val dx = (boxW - dw) / 2f
            val dy = (boxH - dh) / 2f
            val sx = dw / (rr - rl)
            val sy = dh / (rb - rt)
            fun toX(u: Float) = dx + (u - rl) * sx
            fun toY(v: Float) = dy + (v - rt) * sy
            fun fromX(x: Float) = rl + (x - dx) / sx
            fun fromY(y: Float) = rt + (y - dy) / sy
            val handle = 36f
            var drag by remember(tool) { mutableIntStateOf(-1) }
            Canvas(
                Modifier
                    .fillMaxSize()
                    .pointerInput(tool, bmp, color, brush) {
                        when (tool) {
                            Tool.Draw -> detectDragGestures(
                                onDragStart = { o ->
                                    strokes.add(EditStroke(color, brush).also { it.pts.add(Offset(fromX(o.x), fromY(o.y))) })
                                },
                                onDrag = { change, _ ->
                                    change.consume()
                                    strokes.lastOrNull()?.pts?.add(Offset(fromX(change.position.x), fromY(change.position.y)))
                                    tick++
                                },
                            )
                            Tool.Text -> detectDragGestures(
                                onDragStart = { o ->
                                    val u = fromX(o.x); val v = fromY(o.y)
                                    selected = texts.indices.minByOrNull { hypot(texts[it].u - u, texts[it].v - v) }
                                        ?.takeIf { hypot(texts[it].u - u, texts[it].v - v) < 0.2f } ?: -1
                                },
                                onDrag = { change, drag2 ->
                                    change.consume()
                                    texts.getOrNull(selected)?.let {
                                        it.u = (it.u + drag2.x / sx).coerceIn(0f, 1f)
                                        it.v = (it.v + drag2.y / sy).coerceIn(0f, 1f)
                                        tick++
                                    }
                                },
                            )
                            Tool.Crop -> detectDragGestures(
                                onDragStart = { o ->
                                    val l = toX(cl); val t = toY(ct); val r = toX(cr); val b = toY(cb)
                                    drag = when {
                                        hypot(o.x - l, o.y - t) < handle * 2 -> 0
                                        hypot(o.x - r, o.y - t) < handle * 2 -> 1
                                        hypot(o.x - l, o.y - b) < handle * 2 -> 2
                                        hypot(o.x - r, o.y - b) < handle * 2 -> 3
                                        o.x in l..r && o.y in t..b -> 4
                                        else -> -1
                                    }
                                },
                                onDrag = { change, d ->
                                    change.consume()
                                    val du = d.x / sx; val dv = d.y / sy
                                    val minS = 0.12f
                                    when (drag) {
                                        0 -> { cl = (cl + du).coerceIn(0f, cr - minS); ct = (ct + dv).coerceIn(0f, cb - minS) }
                                        1 -> { cr = (cr + du).coerceIn(cl + minS, 1f); ct = (ct + dv).coerceIn(0f, cb - minS) }
                                        2 -> { cl = (cl + du).coerceIn(0f, cr - minS); cb = (cb + dv).coerceIn(ct + minS, 1f) }
                                        3 -> { cr = (cr + du).coerceIn(cl + minS, 1f); cb = (cb + dv).coerceIn(ct + minS, 1f) }
                                        4 -> {
                                            val w = cr - cl; val h = cb - ct
                                            cl = (cl + du).coerceIn(0f, 1f - w); ct = (ct + dv).coerceIn(0f, 1f - h)
                                            cr = cl + w; cb = ct + h
                                        }
                                    }
                                },
                            )
                        }
                    },
            ) {
                tick.let { }
                drawIntoCanvas { c ->
                    val n = c.nativeCanvas
                    val src = android.graphics.Rect(
                        (rl * iw).toInt(), (rt * ih).toInt(), (rr * iw).toInt(), (rb * ih).toInt(),
                    )
                    val dst = android.graphics.RectF(dx, dy, dx + dw, dy + dh)
                    n.drawBitmap(bmp, src, dst, NativePaint(NativePaint.FILTER_BITMAP_FLAG))
                    n.save()
                    n.clipRect(dst)
                    paintOverlays(n, texts, strokes, iw, { toX(it) }, { toY(it) }, fit, if (tool == Tool.Text) selected else -1)
                    n.restore()
                }
                if (tool == Tool.Crop) {
                    val l = toX(cl); val t = toY(ct); val r = toX(cr); val b = toY(cb)
                    val dim = Color(0x99000000)
                    drawRect(dim, Offset(dx, dy), Size(dw, t - dy))
                    drawRect(dim, Offset(dx, b), Size(dw, dy + dh - b))
                    drawRect(dim, Offset(dx, t), Size(l - dx, b - t))
                    drawRect(dim, Offset(r, t), Size(dx + dw - r, b - t))
                    drawRect(Color.White, Offset(l, t), Size(r - l, b - t), style = Stroke(2.dp.toPx()))
                    for (p in listOf(Offset(l, t), Offset(r, t), Offset(l, b), Offset(r, b))) {
                        drawCircle(Color.White, 9.dp.toPx(), p)
                    }
                }
            }
        }
        // Tool controls.
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (tool == Tool.Draw || tool == Tool.Text) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    editColors.forEach { c ->
                        Box(
                            Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(Color(c))
                                .border(if (c == color) 3.dp else 1.dp, if (c == color) Color.White else Color(0x66FFFFFF), CircleShape)
                                .clickable {
                                    color = c
                                    texts.getOrNull(selected)?.let { it.color = c; tick++ }
                                },
                        )
                    }
                }
            }
            if (tool == Tool.Draw) {
                Slider(value = brush, onValueChange = { brush = it }, valueRange = 0.004f..0.04f)
            }
            if (tool == Tool.Text) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Pill(stringResource(R.string.photo_edit_add_text)) { adding = true; draft = "" }
                    if (selected in texts.indices) {
                        Slider(
                            value = texts[selected].size,
                            onValueChange = { texts[selected].size = it; tick++ },
                            valueRange = 0.03f..0.2f,
                            modifier = Modifier.weight(1f),
                        )
                        GlassIconButton({ texts.removeAt(selected); selected = -1 }, Lu.Trash2, stringResource(R.string.chat_preview_remove), solid = true)
                    }
                }
            }
            if (tool == Tool.Crop) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Pill(stringResource(R.string.photo_edit_rotate)) { rotate() }
                    Pill("1:1") { squareCrop(bmp) { l, t, r, b -> set4(l, t, r, b) } }
                    Pill(stringResource(R.string.photo_edit_reset)) { cl = 0f; ct = 0f; cr = 1f; cb = 1f }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                ToolTab(Lu.Maximize2, stringResource(R.string.photo_edit_crop), tool == Tool.Crop) { tool = Tool.Crop }
                ToolTab(null, "Aa", tool == Tool.Text) { tool = Tool.Text }
                ToolTab(Lu.Pencil, stringResource(R.string.photo_edit_draw), tool == Tool.Draw) { tool = Tool.Draw }
            }
        }
    }
    if (adding) {
        val focus = remember { androidx.compose.ui.focus.FocusRequester() }
        androidx.compose.runtime.LaunchedEffect(Unit) { focus.requestFocus() }
        Box(
            Modifier.fillMaxSize().background(Color(0xE6000000)).clickable { adding = false },
            contentAlignment = Alignment.Center,
        ) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                BasicTextField(
                    value = draft,
                    onValueChange = { draft = it.take(80) },
                    textStyle = androidx.compose.material3.MaterialTheme.typography.headlineMedium.copy(color = Color.White),
                    cursorBrush = SolidColor(Color.White),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focus),
                )
                Pill(stringResource(R.string.photo_edit_done)) {
                    if (draft.isNotBlank()) {
                        texts.add(EditText(draft.trim(), (cl + cr) / 2f, (ct + cb) / 2f, 0.08f, color))
                        selected = texts.lastIndex
                    }
                    adding = false
                }
            }
        }
    }
}

private fun squareCrop(bmp: Bitmap, set: (Float, Float, Float, Float) -> Unit) {
    val iw = bmp.width.toFloat(); val ih = bmp.height.toFloat()
    if (iw >= ih) {
        val w = ih / iw; val l = (1f - w) / 2f
        set(l, 0f, l + w, 1f)
    } else {
        val h = iw / ih; val t = (1f - h) / 2f
        set(0f, t, 1f, t + h)
    }
}

@Composable
private fun Pill(label: String, onClick: () -> Unit) {
    Text(
        label,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(Color(0x33FFFFFF))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        color = Color.White,
    )
}

@Composable
private fun ToolTab(icon: androidx.compose.ui.graphics.vector.ImageVector?, label: String, on: Boolean, onClick: () -> Unit) {
    Column(
        Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(if (on) Color(0x33FFFFFF) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (icon != null) LuIcon(icon, label, tint = Color.White) else Text("Aa", color = Color.White, style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
        Text(label, color = Color.White.copy(alpha = if (on) 1f else 0.7f), style = androidx.compose.material3.MaterialTheme.typography.labelSmall)
    }
}

/** Draws strokes then text. [scale] is screen pixels per picture pixel. */
private fun paintOverlays(
    n: android.graphics.Canvas,
    texts: List<EditText>,
    strokes: List<EditStroke>,
    imageWidth: Float,
    toX: (Float) -> Float,
    toY: (Float) -> Float,
    scale: Float,
    selected: Int,
) {
    val p = NativePaint(NativePaint.ANTI_ALIAS_FLAG)
    p.style = NativePaint.Style.STROKE
    p.strokeCap = NativePaint.Cap.ROUND
    p.strokeJoin = NativePaint.Join.ROUND
    for (s in strokes) {
        p.color = s.color
        p.strokeWidth = s.width * imageWidth * scale
        val path = android.graphics.Path()
        val pts = s.pts
        if (pts.isEmpty()) continue
        path.moveTo(toX(pts[0].x), toY(pts[0].y))
        if (pts.size == 1) path.lineTo(toX(pts[0].x) + 0.1f, toY(pts[0].y))
        for (i in 1 until pts.size) {
            val a = pts[i - 1]; val b = pts[i]
            path.quadTo(toX(a.x), toY(a.y), (toX(a.x) + toX(b.x)) / 2f, (toY(a.y) + toY(b.y)) / 2f)
        }
        n.drawPath(path, p)
    }
    val t = NativePaint(NativePaint.ANTI_ALIAS_FLAG)
    t.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    t.textAlign = NativePaint.Align.CENTER
    texts.forEachIndexed { i, it ->
        t.textSize = it.size * imageWidth * scale
        val x = toX(it.u)
        val y = toY(it.v) - (t.ascent() + t.descent()) / 2f
        t.style = NativePaint.Style.STROKE
        t.strokeWidth = t.textSize * 0.12f
        t.color = 0x99000000.toInt()
        n.drawText(it.text, x, y, t)
        t.style = NativePaint.Style.FILL
        t.color = it.color
        n.drawText(it.text, x, y, t)
        if (i == selected) {
            val w = t.measureText(it.text)
            val box = NativePaint().apply { style = NativePaint.Style.STROKE; color = 0xFFFFFFFF.toInt(); strokeWidth = 2f }
            n.drawRect(x - w / 2 - 8, y + t.ascent() - 8, x + w / 2 + 8, y + t.descent() + 8, box)
        }
    }
}

/** Renders the crop with strokes and text into a JPEG. Largest edge is capped at 1600. */
private fun export(
    bmp: Bitmap, cl: Float, ct: Float, cr: Float, cb: Float,
    texts: List<EditText>, strokes: List<EditStroke>,
): ByteArray {
    val iw = bmp.width.toFloat(); val ih = bmp.height.toFloat()
    val cw = (cr - cl) * iw; val ch = (cb - ct) * ih
    val k = min(1f, 1600f / max(cw, ch))
    val ow = max(1, (cw * k).toInt()); val oh = max(1, (ch * k).toInt())
    val out = Bitmap.createBitmap(ow, oh, Bitmap.Config.ARGB_8888)
    val n = android.graphics.Canvas(out)
    n.drawBitmap(
        bmp,
        android.graphics.Rect((cl * iw).toInt(), (ct * ih).toInt(), (cr * iw).toInt(), (cb * ih).toInt()),
        android.graphics.Rect(0, 0, ow, oh),
        NativePaint(NativePaint.FILTER_BITMAP_FLAG),
    )
    paintOverlays(
        n, texts, strokes, iw,
        { (it - cl) * iw * k }, { (it - ct) * ih * k }, k, -1,
    )
    val bytes = ByteArrayOutputStream()
    out.compress(Bitmap.CompressFormat.JPEG, 90, bytes)
    out.recycle()
    return bytes.toByteArray()
}
