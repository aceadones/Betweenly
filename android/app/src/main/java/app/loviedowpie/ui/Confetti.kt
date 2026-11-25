package app.loviedowpie.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp
import kotlin.random.Random

/**
 * A short fall of hearts and stars. With Reduce animations on, it stays on the
 * last frame (fully faded), so the screen does not play the burst.
 * DATA-FLOW: none.
 */
@Composable
fun SoftConfetti(modifier: Modifier = Modifier, seed: Int = 7) {
    val bits = remember(seed) {
        val random = Random(seed)
        List(12) {
            Bit(
                x = random.nextFloat(),
                delay = random.nextInt(280),
                heart = random.nextBoolean(),
                drift = (random.nextFloat() - 0.5f) * 48f,
                spin = random.nextFloat() * 40f - 20f,
                size = 7f + random.nextFloat() * 7f,
            )
        }
    }
    val progress = remember { Animatable(if (Motion.enabled) 0f else 1f) }
    LaunchedEffect(seed) {
        if (!Motion.enabled) {
            progress.snapTo(1f)
        } else {
            progress.snapTo(0f)
            progress.animateTo(1f, Motion.tween(1500, Motion.easeOut))
        }
    }
    Canvas(modifier) {
        val travel = progress.value
        bits.forEach { bit ->
            val local = ((travel * 1500f - bit.delay) / 1100f).coerceIn(0f, 1f)
            if (local <= 0f) return@forEach
            val alpha = (1f - local) * 0.9f
            if (alpha <= 0.02f) return@forEach
            val x = bit.x * size.width + bit.drift * local
            val y = -8f + local * (size.height * 0.92f)
            val color = if (bit.heart) LdColors.heart else LdColors.primary
            val at = Offset(x, y)
            if (bit.heart) {
                tinyHeart(at, bit.size, color.copy(alpha = alpha))
            } else {
                rotate(bit.spin * local, at) {
                    tinyStar(at, bit.size, color.copy(alpha = alpha))
                }
            }
        }
    }
}

private data class Bit(
    val x: Float,
    val delay: Int,
    val heart: Boolean,
    val drift: Float,
    val spin: Float,
    val size: Float,
)

private fun DrawScope.tinyHeart(at: Offset, s: Float, color: Color) {
    val path = Path().apply {
        moveTo(at.x, at.y + s * 0.7f)
        cubicTo(at.x - s * 1.4f, at.y - s * 0.2f, at.x - s * 0.7f, at.y - s * 1.3f, at.x, at.y - s * 0.4f)
        cubicTo(at.x + s * 0.7f, at.y - s * 1.3f, at.x + s * 1.4f, at.y - s * 0.2f, at.x, at.y + s * 0.7f)
        close()
    }
    drawPath(path, color)
}

private fun DrawScope.tinyStar(at: Offset, s: Float, color: Color) {
    val path = Path().apply {
        moveTo(at.x, at.y - s)
        lineTo(at.x + s * 0.22f, at.y - s * 0.22f)
        lineTo(at.x + s, at.y)
        lineTo(at.x + s * 0.22f, at.y + s * 0.22f)
        lineTo(at.x, at.y + s)
        lineTo(at.x - s * 0.22f, at.y + s * 0.22f)
        lineTo(at.x - s, at.y)
        lineTo(at.x - s * 0.22f, at.y - s * 0.22f)
        close()
    }
    drawPath(
        path,
        color,
        style = Stroke(width = 1.4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
    )
}
