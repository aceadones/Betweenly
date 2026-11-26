package com.nextlayer.betweenly.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nextlayer.betweenly.R
import com.nextlayer.betweenly.ui.icons.Lu
import com.nextlayer.betweenly.ui.icons.LuIcon
import kotlin.math.roundToInt

@Composable
internal fun BrushSheet(model: DrawingViewModel, onPick: (BrushKind) -> Unit) {
    val kinds = BrushKind.entries
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 420.dp)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(stringResource(R.string.draw_brush), style = LdType.Title, color = surfaceInk())
        kinds.chunked(3).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                row.forEach { kind ->
                    BrushCell(
                        kind = kind,
                        colorArgb = model.colorArgb,
                        selected = kind == model.brush,
                        onPick = { onPick(kind) },
                        modifier = Modifier.weight(1f),
                    )
                }
                repeat(3 - row.size) { Box(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun BrushCell(
    kind: BrushKind,
    colorArgb: Int,
    selected: Boolean,
    onPick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bmp = remember(kind, colorArgb) { brushPreviewBitmap(kind, colorArgb) }
    val view = LocalView.current
    DisposableEffect(bmp) {
        onDispose { recycleLater(view, bmp) }
    }
    val interaction = remember(kind) { MutableInteractionSource() }
    val shape = RoundedCornerShape(16.dp)
    val plate = RoundedCornerShape(LdRadius.inner(16.dp, 8.dp))
    Column(
        modifier
            .pressScale(interaction)
            .clip(shape)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) Color.White.copy(alpha = 0.70f) else Color.White.copy(alpha = 0.16f),
                shape = shape,
            )
            .clickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
                role = Role.Button,
                onClick = onPick,
            )
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(36.dp)
                .clip(plate)
                .background(LdColors.previewPlate),
            contentAlignment = Alignment.Center,
        ) {
            if (!bmp.isRecycled) {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxWidth().height(36.dp),
                )
            }
        }
        Text(
            stringResource(kind.label()),
            style = LdType.Caption,
            color = surfaceInk(),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
internal fun ColorSheet(
    model: DrawingViewModel,
    onSwatch: (Int) -> Unit,
    onWheelPlaced: (Float, Float) -> Unit,
    onWheel: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 360.dp)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.draw_color), style = LdType.Title, color = surfaceInk())
        SwatchRow(colors = DrawInk.swatches.map { it.toArgb() }, selected = model.colorArgb, onSwatch = onSwatch)
        Text(stringResource(R.string.draw_recent), style = LdType.Caption, color = surfaceInk(muted = true))
        if (model.recents.isEmpty()) {
            Text(stringResource(R.string.draw_recent_none), style = LdType.Caption, color = surfaceInk(muted = true))
        } else {
            SwatchRow(colors = model.recents, selected = model.colorArgb, onSwatch = onSwatch)
        }
        val interaction = remember { MutableInteractionSource() }
        val shape = RoundedCornerShape(LdRadius.inner(LdRadius.card, 16.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .pressScale(interaction)
                .heightIn(min = 48.dp)
                .onGloballyPositioned { coords ->
                    val p = coords.positionInRoot()
                    onWheelPlaced(p.x + coords.size.width / 2f, p.y + coords.size.height / 2f)
                }
                .clip(shape)
                .background(LdColors.white.copy(alpha = 0.92f))
                .clickable(
                    interactionSource = interaction,
                    indication = LocalIndication.current,
                    role = Role.Button,
                    onClick = onWheel,
                )
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            LuIcon(Lu.Palette, contentDescription = null, tint = LdColors.navy)
            Text(stringResource(R.string.draw_picker), style = LdType.Title, color = LdColors.navy)
        }
    }
}

@Composable
private fun SwatchRow(colors: List<Int>, selected: Int, onSwatch: (Int) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        colors.forEach { color ->
            val on = sameRgb(color, selected)
            val interaction = remember(color) { MutableInteractionSource() }
            Box(
                modifier = Modifier
                    .pressScale(interaction)
                    .size(48.dp)
                    .clip(CircleShape)
                    .clickable(
                        interactionSource = interaction,
                        indication = LocalIndication.current,
                        role = Role.Button,
                        onClick = { onSwatch(color) },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(Color(color))
                        .border(
                            width = if (on) 2.dp else 1.dp,
                            color = if (on) Color.White.copy(alpha = 0.70f) else Color.White.copy(alpha = 0.28f),
                            shape = CircleShape,
                        ),
                )
            }
        }
    }
}

@Composable
internal fun SizeSheet(model: DrawingViewModel, onChange: (Float) -> Unit, onFinish: () -> Unit) {
    val px = model.sizePx.roundToInt().coerceIn(1, 64)
    Column(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.draw_size), style = LdType.Title, color = surfaceInk())
        PreviewDot(diameter = px.dp, color = Color(model.colorArgb), alpha = model.opacity)
        Text(stringResource(R.string.draw_size_px, px), style = LdType.Caption, color = surfaceInk(muted = true))
        Slider(
            value = px.toFloat(),
            onValueChange = { onChange(it.roundToInt().coerceIn(1, 64).toFloat()) },
            onValueChangeFinished = onFinish,
            valueRange = 1f..64f,
            steps = 62,
            colors = sliderColors(),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
internal fun OpacitySheet(model: DrawingViewModel, onChange: (Float) -> Unit, onFinish: () -> Unit) {
    val percent = (model.opacity * 100f).roundToInt().coerceIn(5, 100)
    Column(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.draw_opacity), style = LdType.Title, color = surfaceInk())
        Box(
            Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(12.dp)),
        ) {
            Checker()
            Box(
                Modifier
                    .size(48.dp)
                    .align(Alignment.Center)
                    .clip(CircleShape)
                    .background(Color(model.colorArgb).copy(alpha = percent / 100f))
                    .border(1.dp, surfaceInk().copy(alpha = 0.35f), CircleShape),
            )
        }
        Text(stringResource(R.string.draw_opacity_pct, percent), style = LdType.Caption, color = surfaceInk(muted = true))
        Slider(
            value = percent.toFloat(),
            onValueChange = { onChange(it.roundToInt().coerceIn(5, 100) / 100f) },
            onValueChangeFinished = onFinish,
            valueRange = 5f..100f,
            steps = 94,
            colors = sliderColors(),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun PreviewDot(diameter: androidx.compose.ui.unit.Dp, color: Color, alpha: Float) {
    Box(
        Modifier
            .size(72.dp)
            .clip(RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Checker()
        Box(
            Modifier
                .size(diameter.coerceAtLeast(4.dp))
                .clip(CircleShape)
                .background(color.copy(alpha = alpha))
                .border(1.dp, surfaceInk().copy(alpha = 0.35f), CircleShape),
        )
    }
}

@Composable
private fun Checker() {
    Row(Modifier.fillMaxWidth().height(72.dp)) {
        Box(Modifier.weight(1f).height(72.dp).background(LdColors.checkerLight))
        Box(Modifier.weight(1f).height(72.dp).background(LdColors.checkerDark))
    }
}

@Composable
private fun sliderColors() = SliderDefaults.colors(
    thumbColor = LdColors.white,
    activeTrackColor = LdColors.white.copy(alpha = 0.85f),
    inactiveTrackColor = Color.White.copy(alpha = 0.28f),
)

private fun sameRgb(a: Int, b: Int): Boolean = (a and 0xFFFFFF) == (b and 0xFFFFFF)

private fun BrushKind.label(): Int = when (this) {
    BrushKind.Solid -> R.string.draw_brush_solid
    BrushKind.Pencil -> R.string.draw_brush_pencil
    BrushKind.Marker -> R.string.draw_brush_marker
    BrushKind.Highlighter -> R.string.draw_brush_highlighter
    BrushKind.Spray -> R.string.draw_brush_spray
    BrushKind.Calligraphy -> R.string.draw_brush_calligraphy
    BrushKind.Dotted -> R.string.draw_brush_dotted
    BrushKind.Watercolor -> R.string.draw_brush_watercolor
    BrushKind.Neon -> R.string.draw_brush_neon
}

/** Swatches and the hue stops the color wheel paints with. */
internal object DrawInk {
    val swatches = listOf(
        LdColors.inkRed,
        LdColors.inkOrange,
        LdColors.inkYellow,
        LdColors.inkGreen,
        LdColors.inkBlue,
        LdColors.inkPurple,
        LdColors.white,
        LdColors.black,
    )
    val hueStops = listOf(
        LdColors.hueRed,
        LdColors.hueYellow,
        LdColors.hueGreen,
        LdColors.hueCyan,
        LdColors.hueBlue,
        LdColors.hueMagenta,
        LdColors.hueRed,
    )

    fun hsv(h: Float, s: Float, v: Float): Color {
        val hsv = floatArrayOf(
            ((h % 360f) + 360f) % 360f,
            s.coerceIn(0f, 1f),
            v.coerceIn(0f, 1f),
        )
        return Color(android.graphics.Color.HSVToColor(hsv))
    }
}
