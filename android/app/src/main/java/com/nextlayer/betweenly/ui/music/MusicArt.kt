package com.nextlayer.betweenly.ui.music

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nextlayer.betweenly.R
import com.nextlayer.betweenly.music.AlbumArt
import com.nextlayer.betweenly.music.Track
import com.nextlayer.betweenly.ui.LdType
import kotlin.math.atan2
import kotlin.math.hypot

@Composable
internal fun TrackThumb(
    track: Track?,
    modifier: Modifier = Modifier,
    corner: Dp = 12.dp,
    circle: Boolean = false,
) {
    val shape = if (circle) CircleShape else RoundedCornerShape(corner)
    val seed = track?.key() ?: "quiet"
    val context = LocalContext.current
    var art by remember(seed) { mutableStateOf(track?.let { AlbumArt.peek(it.key()) }) }
    LaunchedEffect(seed) {
        val item = track ?: return@LaunchedEffect
        art = AlbumArt.peek(item.key()) ?: AlbumArt.load(context, item)
    }
    val bitmap = art
    Box(modifier.clip(shape).background(artBrush(seed)), contentAlignment = Alignment.Center) {
        if (bitmap != null && !bitmap.isRecycled) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = stringResource(R.string.music_artwork),
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
    }
}

@Composable
internal fun SeekRing(
    track: Track?,
    fraction: Float,
    remaining: String,
    onPreview: (Float) -> Unit,
    onCommit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val view = LocalView.current
    val seekLabel = stringResource(R.string.music_seek)
    val remainLabel = stringResource(R.string.music_remaining)
    var tick by remember { mutableIntStateOf(-1) }
    val clock = LdType.Caption.copy(fontFeatureSettings = "tnum", color = MusicPaint.ink)
    Box(modifier.size(260.dp), contentAlignment = Alignment.Center) {
        Canvas(
            Modifier
                .fillMaxSize()
                .semantics { contentDescription = seekLabel }
                .pointerInput(track?.key()) {
                    detectDragGestures(
                        onDragStart = { tick = -1 },
                        onDragEnd = { onCommit() },
                        onDragCancel = { onCommit() },
                    ) { change, _ ->
                        val center = Offset(size.width / 2f, size.height / 2f)
                        val dx = change.position.x - center.x
                        val dy = change.position.y - center.y
                        val dist = hypot(dx.toDouble(), dy.toDouble()).toFloat()
                        val outer = minOf(size.width, size.height) / 2f
                        if (dist < outer * 0.46f) return@detectDragGestures
                        var deg = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat() + 90f
                        if (deg < 0f) deg += 360f
                        val next = (deg / 360f).coerceIn(0f, 0.998f)
                        val bucket = (next * 24f).toInt()
                        if (bucket != tick) {
                            tick = bucket
                            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        }
                        onPreview(next)
                        change.consume()
                    }
                },
        ) {
            val stroke = 8.dp.toPx()
            val radius = size.minDimension / 2f - stroke
            val center = Offset(size.width / 2f, size.height / 2f)
            drawCircle(
                color = MusicPaint.glow.copy(alpha = 0.34f),
                radius = radius * 0.78f,
                center = center,
            )
            drawCircle(
                color = MusicPaint.ring,
                radius = radius,
                center = center,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
            if (fraction > 0f) {
                drawArc(
                    color = MusicPaint.accent,
                    startAngle = -90f,
                    sweepAngle = 360f * fraction.coerceIn(0f, 1f),
                    useCenter = false,
                    topLeft = Offset(center.x - radius, center.y - radius),
                    size = Size(radius * 2f, radius * 2f),
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
            }
        }
        TrackThumb(track = track, modifier = Modifier.size(188.dp), circle = true)
        Text(
            remaining,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 8.dp)
                .semantics { contentDescription = remainLabel },
            style = clock,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
internal fun clockStyle(color: Color): TextStyle =
    LdType.Caption.copy(fontFeatureSettings = "tnum", color = color)
