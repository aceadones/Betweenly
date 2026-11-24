package app.loviedowpie.ui

import androidx.compose.ui.graphics.Color

/**
 * Paint for the drawing canvas. These are ink colors, not app chrome.
 * Screens take swatches, brush sizes, and the hue spectrum from here.
 */
object DrawInk {
    val swatches: List<Color> = listOf(
        LdColors.inkRed,
        LdColors.inkOrange,
        LdColors.inkYellow,
        LdColors.inkGreen,
        LdColors.inkBlue,
        LdColors.inkPurple,
        LdColors.paper,
        LdColors.black,
    )

    /** Four brush diameters, in dp. */
    val brushSizesDp: List<Float> = listOf(3f, 8f, 16f, 28f)

    /** Hue ring, clockwise from 3 o'clock, matching [android.graphics.SweepGradient]. */
    val hueStops: List<Color> = listOf(
        LdColors.hueRed,
        LdColors.hueYellow,
        LdColors.hueGreen,
        LdColors.hueCyan,
        LdColors.hueBlue,
        LdColors.hueMagenta,
        LdColors.hueRed,
    )

    fun hsv(h: Float, s: Float, v: Float): Color {
        val argb = android.graphics.Color.HSVToColor(
            floatArrayOf((h % 360f + 360f) % 360f, s.coerceIn(0f, 1f), v.coerceIn(0f, 1f)),
        )
        return Color(argb)
    }
}
