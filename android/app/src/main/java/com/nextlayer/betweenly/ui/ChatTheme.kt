package com.nextlayer.betweenly.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

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
    fun mine(): Color = LdColors.bubbleMine

    @Composable
    fun onMine(): Color = LdColors.white

    @Composable
    fun theirs(): Color = LdColors.colors.bubbleTheirs

    /** Dark text. Theirs stays a light glass in both themes so this stays readable. */
    @Composable
    fun onTheirs(): Color = LdColors.navy

    @Composable
    fun ink(outgoing: Boolean): Color = if (outgoing) onMine() else onTheirs()

    @Composable
    fun meta(outgoing: Boolean): Color = if (outgoing) LdColors.onMineMuted else LdColors.metaTheirs

    @Composable
    fun timestamp(): Color = meta(outgoing = false)

    @Composable
    fun bar(): Color = LdColors.clear

    fun onWallpaper(color: Color): Color {
        val luminance = color.red * 0.299f + color.green * 0.587f + color.blue * 0.114f
        return if (luminance > 0.62f) LdColors.navy else LdColors.white
    }
}
