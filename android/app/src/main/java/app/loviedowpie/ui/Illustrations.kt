package app.loviedowpie.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import app.loviedowpie.ui.icons.LuIcon
import kotlin.math.atan2

/**
 * Hand-drawn scenes. No bitmaps leave the phone.
 * DATA-FLOW: none.
 *
 * A looping clock still ticks when Reduce animations is on, but the drawing
 * uses the resting frame so nothing travels.
 */
@Composable
private fun drift(label: String, from: Float, to: Float, ms: Int, reverse: Boolean): Float {
    val transition = rememberInfiniteTransition(label = label)
    val value by transition.animateFloat(
        initialValue = from,
        targetValue = to,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = ms, easing = Motion.easeInOut),
            repeatMode = if (reverse) RepeatMode.Reverse else RepeatMode.Restart,
        ),
        label = label,
    )
    return if (Motion.enabled) value else from
}

/** Full-bleed sky, globe, dotted arc, two hearts, and a paper plane. */
@Composable
fun WelcomeScene(modifier: Modifier = Modifier) {
    val dark = isSystemInDarkTheme()
    val along = drift("plane", 0.16f, 0.84f, 2800, reverse = true)
    val dash = drift("dash", 0f, 28f, 1600, reverse = false)
    val sky = if (dark) {
        listOf(Color(0xFF071428), Color(0xFF12305F), Color(0xFF0B1B3A))
    } else {
        listOf(Color(0xFF8EC8FF), Color(0xFFD7EEFF), Color(0xFFF7FBFF))
    }
    val ink = if (dark) Color(0xFFF4F8FF) else LdColors.navy
    val glow = if (dark) Color(0xFF7EB6FF) else Color(0xFFFFFFFF)
    Canvas(modifier) {
        drawRect(Brush.verticalGradient(sky))
        drawCircle(
            color = if (dark) Color(0x332F80FF) else Color(0x55FFFFFF),
            radius = size.minDimension * 0.42f,
            center = Offset(size.width * 0.12f, size.height * 0.08f),
        )
        drawCircle(
            color = if (dark) Color(0x22143A6E) else Color(0x66FFFFFF),
            radius = size.minDimension * 0.28f,
            center = Offset(size.width * 0.88f, size.height * 0.16f),
        )
        cloud(Offset(size.width * 0.22f, size.height * 0.16f), size.minDimension * 0.055f, glow.copy(alpha = 0.85f))
        cloud(Offset(size.width * 0.72f, size.height * 0.12f), size.minDimension * 0.04f, glow.copy(alpha = 0.7f))
        cloud(Offset(size.width * 0.84f, size.height * 0.28f), size.minDimension * 0.03f, glow.copy(alpha = 0.55f))
        spark(Offset(size.width * 0.16f, size.height * 0.28f), 7f, ink.copy(alpha = if (dark) 0.8f else 0.35f))
        spark(Offset(size.width * 0.9f, size.height * 0.22f), 5f, ink.copy(alpha = if (dark) 0.7f else 0.28f))
        spark(Offset(size.width * 0.48f, size.height * 0.1f), 4f, ink.copy(alpha = if (dark) 0.65f else 0.25f))
        val cx = size.width * 0.5f
        val cy = size.height * 0.62f
        val rx = size.width * 0.46f
        val ry = size.height * 0.16f
        drawOval(
            brush = Brush.radialGradient(
                colors = if (dark) {
                    listOf(Color(0xFF1C4E96), Color(0xFF0E2A52))
                } else {
                    listOf(Color(0xFFFFFFFF), Color(0xFFB9DCFF))
                },
                center = Offset(cx, cy - ry * 0.2f),
                radius = rx,
            ),
            topLeft = Offset(cx - rx, cy - ry),
            size = Size(rx * 2f, ry * 2f),
        )
        drawOval(
            color = ink.copy(alpha = if (dark) 0.45f else 0.28f),
            topLeft = Offset(cx - rx, cy - ry),
            size = Size(rx * 2f, ry * 2f),
            style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round),
        )
        val left = Offset(size.width * 0.2f, size.height * 0.4f)
        val right = Offset(size.width * 0.8f, size.height * 0.38f)
        val control = Offset(size.width * 0.5f, size.height * 0.16f)
        val arc = Path().apply {
            moveTo(left.x, left.y)
            quadraticBezierTo(control.x, control.y, right.x, right.y)
        }
        drawPath(
            arc,
            color = LdColors.primary.copy(alpha = if (dark) 0.35f else 0.28f),
            style = Stroke(width = 8.dp.toPx(), cap = StrokeCap.Round),
        )
        drawPath(
            arc,
            color = if (dark) Color(0xFFD6E8FF) else LdColors.navy.copy(alpha = 0.75f),
            style = Stroke(
                width = 2.4.dp.toPx(),
                cap = StrokeCap.Round,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(2f, 14f), dash),
            ),
        )
        heart(left, size.minDimension * 0.045f, LdColors.heart)
        heart(right, size.minDimension * 0.04f, LdColors.heart)
        val spot = quad(along, left, control, right)
        val next = quad((along + 0.04f).coerceAtMost(1f), left, control, right)
        val angle = Math.toDegrees(atan2((next.y - spot.y).toDouble(), (next.x - spot.x).toDouble())).toFloat()
        rotate(angle, spot) {
            plane(spot, size.minDimension * 0.045f, if (dark) Color.White else LdColors.navy)
        }
    }
}

/** Soft gradient tile with a Lucide icon. Outer 28dp, the icon sits in the middle. */
@Composable
fun IllustrationTile(icon: ImageVector, modifier: Modifier = Modifier) {
    val dark = isSystemInDarkTheme()
    val ink = if (dark) Color(0xFFF4F8FF) else LdColors.navy
    Box(
        modifier.clip(RoundedCornerShape(LdRadius.card)),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawRoundRect(
                brush = Brush.linearGradient(
                    if (dark) {
                        listOf(Color(0xFF16386E), Color(0xFF0E2448))
                    } else {
                        listOf(Color(0xFFFFFFFF), Color(0xFFD4EBFF))
                    },
                ),
                cornerRadius = CornerRadius(28.dp.toPx(), 28.dp.toPx()),
            )
            drawCircle(
                color = LdColors.primary.copy(alpha = if (dark) 0.28f else 0.16f),
                radius = size.minDimension * 0.28f,
                center = Offset(size.width * 0.7f, size.height * 0.35f),
            )
            drawCircle(
                color = LdColors.heart.copy(alpha = 0.12f),
                radius = size.minDimension * 0.18f,
                center = Offset(size.width * 0.28f, size.height * 0.7f),
            )
        }
        LuIcon(icon, contentDescription = null, tint = ink, size = 64.dp)
    }
}

/** Ticket leaving a phone. Used on "Show my code". */
@Composable
fun TicketArt(modifier: Modifier = Modifier) {
    val dark = isSystemInDarkTheme()
    val ink = if (dark) Color(0xFFF4F8FF) else LdColors.navy
    val paper = if (dark) Color(0xFF1A3E78) else Color.White
    Canvas(modifier) {
        drawRoundRect(
            brush = Brush.linearGradient(
                if (dark) listOf(Color(0xFF1A4C96), Color(0xFF102848)) else listOf(Color(0xFFE7F3FF), Color(0xFFB9D9FF)),
            ),
            cornerRadius = CornerRadius(20.dp.toPx(), 20.dp.toPx()),
        )
        val phone = Size(size.width * 0.42f, size.height * 0.62f)
        val origin = Offset(size.width * 0.14f, size.height * 0.2f)
        drawRoundRect(
            color = paper,
            topLeft = origin,
            size = phone,
            cornerRadius = CornerRadius(12.dp.toPx(), 12.dp.toPx()),
        )
        drawRoundRect(
            color = ink.copy(alpha = 0.7f),
            topLeft = origin,
            size = phone,
            cornerRadius = CornerRadius(12.dp.toPx(), 12.dp.toPx()),
            style = Stroke(width = 2.dp.toPx()),
        )
        val ticket = Path().apply {
            val l = size.width * 0.4f
            val t = size.height * 0.28f
            val r = size.width * 0.86f
            val b = size.height * 0.72f
            moveTo(l, t)
            lineTo(r, t)
            lineTo(r, b)
            lineTo(l, b)
            close()
        }
        drawPath(ticket, if (dark) Color(0xFF0E2C58) else Color(0xFFFFF8F2))
        drawPath(ticket, ink.copy(alpha = 0.75f), style = Stroke(width = 2.dp.toPx(), join = StrokeJoin.Round))
        val dashY = size.height * 0.5f
        drawLine(
            ink.copy(alpha = 0.45f),
            Offset(size.width * 0.46f, dashY),
            Offset(size.width * 0.8f, dashY),
            strokeWidth = 1.6.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f), 0f),
        )
        plane(Offset(size.width * 0.72f, size.height * 0.38f), size.minDimension * 0.12f, LdColors.primary)
    }
}

/** A phone receiving a short code. Used on "Enter their code". */
@Composable
fun InboxArt(modifier: Modifier = Modifier) {
    val dark = isSystemInDarkTheme()
    val ink = if (dark) Color(0xFFF4F8FF) else LdColors.navy
    Canvas(modifier) {
        drawRoundRect(
            brush = Brush.linearGradient(
                if (dark) listOf(Color(0xFF14325F), Color(0xFF0C203C)) else listOf(Color(0xFFFFF6F8), Color(0xFFD5E8FF)),
            ),
            cornerRadius = CornerRadius(20.dp.toPx(), 20.dp.toPx()),
        )
        val phone = Size(size.width * 0.46f, size.height * 0.7f)
        val origin = Offset(size.width * 0.27f, size.height * 0.15f)
        drawRoundRect(
            color = if (dark) Color(0xFF102848) else Color.White,
            topLeft = origin,
            size = phone,
            cornerRadius = CornerRadius(14.dp.toPx(), 14.dp.toPx()),
        )
        drawRoundRect(
            color = ink.copy(alpha = 0.75f),
            topLeft = origin,
            size = phone,
            cornerRadius = CornerRadius(14.dp.toPx(), 14.dp.toPx()),
            style = Stroke(width = 2.dp.toPx()),
        )
        val gap = phone.width / 5f
        repeat(4) { index ->
            val x = origin.x + gap * (index + 1f) - 4.dp.toPx()
            drawCircle(
                color = if (index < 3) LdColors.primary else ink.copy(alpha = 0.35f),
                radius = 3.2.dp.toPx(),
                center = Offset(x, origin.y + phone.height * 0.42f),
            )
        }
        drawLine(
            color = LdColors.primary,
            start = Offset(origin.x + phone.width * 0.3f, origin.y + phone.height * 0.62f),
            end = Offset(origin.x + phone.width * 0.7f, origin.y + phone.height * 0.62f),
            strokeWidth = 2.dp.toPx(),
            cap = StrokeCap.Round,
        )
    }
}

/** Mini radar under the partner's name. Not a map, and it sends nothing. */
@Composable
fun RadarStrip(hasPoint: Boolean, modifier: Modifier = Modifier) {
    val dark = isSystemInDarkTheme()
    val sweep = drift("radar", 0f, 360f, 4200, reverse = false)
    val ink = if (dark) Color(0xFFD6E4F7) else LdColors.navy
    Canvas(modifier) {
        val c = Offset(size.width * 0.5f, size.height * 0.78f)
        val radii = listOf(0.28f, 0.5f, 0.74f)
        radii.forEach { scale ->
            drawArc(
                color = ink.copy(alpha = 0.28f),
                startAngle = 200f,
                sweepAngle = 140f,
                useCenter = false,
                topLeft = Offset(c.x - size.width * scale, c.y - size.height * scale),
                size = Size(size.width * scale * 2f, size.height * scale * 2f),
                style = Stroke(width = 1.6.dp.toPx(), cap = StrokeCap.Round),
            )
        }
        if (Motion.enabled) {
            rotate(sweep, c) {
                drawLine(
                    color = LdColors.primary.copy(alpha = 0.55f),
                    start = c,
                    end = Offset(c.x, c.y - size.height * 0.7f),
                    strokeWidth = 2.dp.toPx(),
                    cap = StrokeCap.Round,
                )
            }
        }
        if (hasPoint) {
            val dot = Offset(size.width * 0.62f, size.height * 0.42f)
            drawCircle(LdColors.primary.copy(alpha = 0.25f), radius = 10.dp.toPx(), center = dot)
            drawCircle(LdColors.heart, radius = 4.dp.toPx(), center = dot)
        }
    }
}

/** Dotted arc the two avatars meet over. */
@Composable
fun MeetArc(modifier: Modifier = Modifier) {
    val dark = isSystemInDarkTheme()
    val ink = if (dark) Color(0xFFD6E8FF) else LdColors.navy
    Canvas(modifier) {
        val path = Path().apply {
            moveTo(size.width * 0.16f, size.height * 0.62f)
            quadraticBezierTo(size.width * 0.5f, size.height * 0.08f, size.width * 0.84f, size.height * 0.62f)
        }
        drawPath(
            path,
            color = LdColors.primary.copy(alpha = 0.25f),
            style = Stroke(width = 8.dp.toPx(), cap = StrokeCap.Round),
        )
        drawPath(
            path,
            color = ink.copy(alpha = 0.8f),
            style = Stroke(
                width = 2.2.dp.toPx(),
                cap = StrokeCap.Round,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(2f, 12f), 0f),
            ),
        )
    }
}

/** Waiting pulse. Static when Reduce animations is on. */
@Composable
fun WaitingArc(modifier: Modifier = Modifier) {
    val dark = isSystemInDarkTheme()
    val pulse = drift("wait", 0.35f, 1f, 1100, reverse = true)
    val ink = if (dark) Color(0xFFD6E8FF) else LdColors.navy
    Canvas(modifier.size(120.dp)) {
        val c = Offset(size.width / 2f, size.height * 0.62f)
        drawArc(
            color = ink.copy(alpha = 0.35f * pulse),
            startAngle = 200f,
            sweepAngle = 140f,
            useCenter = false,
            topLeft = Offset(8.dp.toPx(), 8.dp.toPx()),
            size = Size(size.width - 16.dp.toPx(), size.height - 16.dp.toPx()),
            style = Stroke(
                width = 2.4.dp.toPx(),
                cap = StrokeCap.Round,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(2f, 10f), 0f),
            ),
        )
        drawCircle(LdColors.primary.copy(alpha = 0.2f * pulse), radius = 6.dp.toPx(), center = c)
        drawCircle(LdColors.primary, radius = 3.dp.toPx(), center = c)
    }
}

private fun quad(t: Float, a: Offset, b: Offset, c: Offset): Offset {
    val u = 1f - t
    return Offset(
        u * u * a.x + 2f * u * t * b.x + t * t * c.x,
        u * u * a.y + 2f * u * t * b.y + t * t * c.y,
    )
}

private fun DrawScope.heart(center: Offset, s: Float, color: Color) {
    val x = center.x
    val y = center.y
    val path = Path().apply {
        moveTo(x, y + s * 0.72f)
        cubicTo(x - s * 1.5f, y - s * 0.15f, x - s * 0.85f, y - s * 1.35f, x, y - s * 0.45f)
        cubicTo(x + s * 0.85f, y - s * 1.35f, x + s * 1.5f, y - s * 0.15f, x, y + s * 0.72f)
        close()
    }
    drawPath(path, color.copy(alpha = 0.9f))
    drawPath(
        path,
        Color.White.copy(alpha = 0.9f),
        style = Stroke(width = 1.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
    )
}

private fun DrawScope.plane(at: Offset, s: Float, color: Color) {
    val path = Path().apply {
        moveTo(at.x - s, at.y)
        lineTo(at.x + s * 1.1f, at.y - s * 0.45f)
        lineTo(at.x - s * 0.15f, at.y)
        close()
    }
    drawPath(path, color)
    drawLine(
        color,
        Offset(at.x - s * 0.15f, at.y),
        Offset(at.x + s * 0.15f, at.y + s * 0.55f),
        strokeWidth = 1.8.dp.toPx(),
        cap = StrokeCap.Round,
    )
}

private fun DrawScope.cloud(at: Offset, s: Float, color: Color) {
    drawCircle(color, radius = s * 0.7f, center = Offset(at.x - s * 0.45f, at.y))
    drawCircle(color, radius = s, center = Offset(at.x, at.y - s * 0.25f))
    drawCircle(color, radius = s * 0.75f, center = Offset(at.x + s * 0.7f, at.y))
}

private fun DrawScope.spark(at: Offset, s: Float, color: Color) {
    drawLine(color, Offset(at.x - s, at.y), Offset(at.x + s, at.y), 1.6.dp.toPx(), cap = StrokeCap.Round)
    drawLine(color, Offset(at.x, at.y - s), Offset(at.x, at.y + s), 1.6.dp.toPx(), cap = StrokeCap.Round)
    val d = s * 0.55f
    drawLine(color, Offset(at.x - d, at.y - d), Offset(at.x + d, at.y + d), 1.6.dp.toPx(), cap = StrokeCap.Round)
    drawLine(color, Offset(at.x - d, at.y + d), Offset(at.x + d, at.y - d), 1.6.dp.toPx(), cap = StrokeCap.Round)
}
