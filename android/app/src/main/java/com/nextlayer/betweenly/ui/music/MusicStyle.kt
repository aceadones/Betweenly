package com.nextlayer.betweenly.ui.music

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import java.util.Locale
import kotlin.math.abs

/** Rose palette for the music module. The player stays on these, not the old blue. */
internal object MusicPaint {
    val accent = Color(0xFFD6245A)
    val deep = Color(0xFFA3123E)
    val glow = Color(0xFFFF5C86)
    val tint = Color(0xFFFFE3EA)
    val bg = Color(0xFF14080E)
    val bgLift = Color(0xFF241018)
    val ink = Color(0xFFF7F1F3)
    val muted = Color(0xFFCDB8C0)
    val line = Color(0x33FFFFFF)
    val ring = Color(0xFF3A242C)
    val white = Color.White
}

internal fun formatTrackClock(ms: Long): String {
    val total = (ms.coerceAtLeast(0L) / 1000L).toInt()
    val seconds = total % 60
    val minutes = (total / 60) % 60
    val hours = total / 3600
    return if (hours > 0) {
        "$hours:${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"
    } else {
        "${total / 60}:${seconds.toString().padStart(2, '0')}"
    }
}

internal fun formatBytes(bytes: Long): String {
    if (bytes < 1024L) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024.0) return String.format(Locale.US, "%.0f KB", kb)
    val mb = kb / 1024.0
    return if (mb >= 100.0) "${mb.toInt()} MB" else String.format(Locale.US, "%.1f MB", mb)
}

/** Warm rose wash. Hues stay in the crimson range so album art never turns blue. */
internal fun artBrush(seed: String): Brush {
    var hash = 0
    for (ch in seed) hash = hash * 31 + ch.code
    val spread = abs(hash % 48)
    val hueA = 338f + spread
    val hueB = hueA - 18f
    return Brush.linearGradient(
        listOf(
            Color.hsl(hueA % 360f, 0.62f, 0.62f),
            Color.hsl(if (hueB < 0f) hueB + 360f else hueB, 0.55f, 0.32f),
        ),
    )
}
