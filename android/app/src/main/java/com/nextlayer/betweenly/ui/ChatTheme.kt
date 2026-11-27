package com.nextlayer.betweenly.ui

import android.content.Context
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.nextlayer.betweenly.Store
import kotlin.math.pow

/**
 * Chat colors and shapes. Screens read these instead of hardcoding a color,
 * so a later visual pass can retune the theme in one place.
 */
object ChatTheme {
    /** Outer bubble corner. Media inset 4dp uses [LdRadius.inner] so the inner corner is 16dp. */
    val bubbleRadius = 20.dp
    val bubble = RoundedCornerShape(bubbleRadius)
    val composerRadius = 28.dp
    val chip = RoundedCornerShape(50)
    val swatch = RoundedCornerShape(12.dp)
    val photoMax = 260.dp
    val stickerMax = 180.dp

    /** Solid wallpaper choices, in the same order as Store's "color:0" … "color:5". */
    val wallpapers: List<Color> = LdColors.wallpapers

    val viewerScrim = LdColors.cameraBg
    val viewerContent = LdColors.cameraContent

    @Composable
    fun mine(): Color = rememberBubbleLook().minePaint()

    @Composable
    fun onMine(): Color = ink(outgoing = true)

    @Composable
    fun theirs(): Color = rememberBubbleLook().theirsPaint()

    @Composable
    fun onTheirs(): Color = ink(outgoing = false)

    /** White, navy, or black, whichever clears the bubble fill. At least one of white or black is above 4.5:1. */
    @Composable
    fun ink(outgoing: Boolean): Color {
        val look = rememberBubbleLook()
        if (outgoing && look.mine == 0 && !look.mineGlass) return Color.White
        if (!outgoing && look.theirs == 0 && look.theirsGlass) return LdColors.onGlass
        val bg = if (outgoing) look.minePaint() else look.theirsPaint()
        return contrastInk(bg)
    }

    @Composable
    fun meta(outgoing: Boolean): Color {
        val look = rememberBubbleLook()
        if (outgoing && look.mine == 0 && !look.mineGlass) return LdColors.onMineMuted
        if (!outgoing && look.theirs == 0 && look.theirsGlass) return LdColors.onGlassMuted
        val bg = if (outgoing) look.minePaint() else look.theirsPaint()
        return contrastMeta(bg, contrastInk(bg))
    }

    @Composable
    fun timestamp(): Color = meta(outgoing = false)

    @Composable
    fun bar(): Color = LdColors.clear

    fun onWallpaper(color: Color): Color {
        val luminance = color.red * 0.299f + color.green * 0.587f + color.blue * 0.114f
        return if (luminance > 0.62f) LdColors.navy else LdColors.white
    }
}

/** Local bubble colors. 0 keeps the built-in mine gradient or their dark glass. */
internal data class BubbleLook(
    val mine: Int,
    val theirs: Int,
    val mineGlass: Boolean,
    val theirsGlass: Boolean,
) {
    fun minePaint(): Color = if (mine == 0) LdColors.bubbleMineStart else Color(mine)

    fun theirsPaint(): Color = if (theirs == 0) Color(0xFF2A1218) else Color(theirs)

    companion object {
        fun read(context: Context): BubbleLook = BubbleLook(
            mine = Store.chatBubbleMine(context),
            theirs = Store.chatBubbleTheirs(context),
            mineGlass = Store.chatBubbleMineGlass(context),
            theirsGlass = Store.chatBubbleTheirsGlass(context),
        )
    }
}

@Composable
internal fun rememberBubbleLook(): BubbleLook {
    val context = LocalContext.current
    val rev by Store.changes.collectAsState()
    return remember(rev) { BubbleLook.read(context) }
}

private fun channelLinear(value: Float): Float {
    val c = value.coerceIn(0f, 1f)
    return if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)
}

private fun luminance(color: Color): Float {
    return 0.2126f * channelLinear(color.red) +
        0.7152f * channelLinear(color.green) +
        0.0722f * channelLinear(color.blue)
}

internal fun contrastRatio(a: Color, b: Color): Float {
    val left = luminance(a)
    val right = luminance(b)
    val lighter = maxOf(left, right)
    val darker = minOf(left, right)
    return (lighter + 0.05f) / (darker + 0.05f)
}

/** Picks white, navy, or black. White or black always clears 4.5:1, so the winner does too. */
internal fun contrastInk(background: Color): Color {
    val choices = arrayOf(Color.White, LdColors.navy, Color.Black)
    var best = choices[0]
    var score = contrastRatio(background, best)
    for (index in 1 until choices.size) {
        val next = contrastRatio(background, choices[index])
        if (next > score) {
            score = next
            best = choices[index]
        }
    }
    return best
}

internal fun contrastMeta(background: Color, ink: Color): Color {
    val mixed = Color(
        red = ink.red * 0.82f + background.red * 0.18f,
        green = ink.green * 0.82f + background.green * 0.18f,
        blue = ink.blue * 0.82f + background.blue * 0.18f,
        alpha = 1f,
    )
    return if (contrastRatio(background, mixed) >= 4.5f) mixed else ink
}
