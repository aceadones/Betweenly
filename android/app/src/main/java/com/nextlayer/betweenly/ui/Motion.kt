package com.nextlayer.betweenly.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring as springSpec
import androidx.compose.animation.core.tween as tweenSpec
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp

/**
 * Shared motion. Every spec snaps when [enabled] is false (Profile → Reduce animations).
 * Enters use [easeOut]. On-screen moves use [easeInOut]. Exits are shorter than enters.
 * Animate alpha, scale, and translation only.
 */
object Motion {
    val easeOut = CubicBezierEasing(0.23f, 1f, 0.32f, 1f)
    val easeInOut = CubicBezierEasing(0.77f, 0f, 0.175f, 1f)

    const val FAST = 140
    const val MED = 220
    const val SLOW = 300

    private var gate by mutableStateOf(true)

    /** False while Reduce animations is on. Reading this subscribes the caller. */
    val enabled: Boolean get() = gate

    fun bind(on: Boolean) {
        if (gate != on) gate = on
    }

    fun <T> tween(
        durationMs: Int,
        easing: Easing = easeOut,
        delayMs: Int = 0,
    ): FiniteAnimationSpec<T> {
        if (!enabled) return snap()
        return tweenSpec(durationMillis = durationMs, delayMillis = delayMs, easing = easing)
    }

    fun <T> spring(
        dampingRatio: Float = Spring.DampingRatioNoBouncy,
        stiffness: Float = Spring.StiffnessMedium,
        visibilityThreshold: T? = null,
    ): FiniteAnimationSpec<T> {
        if (!enabled) return snap()
        return springSpec(
            dampingRatio = dampingRatio,
            stiffness = stiffness,
            visibilityThreshold = visibilityThreshold,
        )
    }

    /** Push onto a full-screen route: 24dp slide + fade, 280ms in / 200ms out. */
    fun screenEnter(slidePx: Int): ContentTransform = ContentTransform(
        targetContentEnter = fadeIn(tween<Float>(280, easeOut)) +
            slideInHorizontally(tween<IntOffset>(280, easeOut)) { slidePx },
        initialContentExit = fadeOut(tween<Float>(200, easeOut)) +
            slideOutHorizontally(tween<IntOffset>(200, easeOut)) { -slidePx / 3 },
        sizeTransform = null,
    )

    /** Pop a full-screen route. The leave is shorter than the push. */
    fun screenExit(slidePx: Int): ContentTransform = ContentTransform(
        targetContentEnter = fadeIn(tween<Float>(200, easeOut)) +
            slideInHorizontally(tween<IntOffset>(200, easeOut)) { -slidePx / 3 },
        initialContentExit = fadeOut(tween<Float>(180, easeOut)) +
            slideOutHorizontally(tween<IntOffset>(180, easeOut)) { slidePx },
        sizeTransform = null,
    )

    /**
     * Tab body: fade plus a 24dp slide, 220ms ease-out.
     * A positive [slidePx] starts the new page on the right, so it travels left.
     */
    fun tabTransition(slidePx: Int): ContentTransform = ContentTransform(
        targetContentEnter = fadeIn(tween<Float>(220, easeOut)) +
            slideInHorizontally(tween<IntOffset>(220, easeOut)) { slidePx },
        initialContentExit = fadeOut(tween<Float>(160, easeOut)) +
            slideOutHorizontally(tween<IntOffset>(160, easeOut)) { -slidePx },
        sizeTransform = null,
    )
}

/** Press scales to 0.97 in about 100ms and springs back. No-op when motion is off. */
@Composable
fun Modifier.pressScale(interactionSource: InteractionSource): Modifier {
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        animationSpec = if (pressed) {
            Motion.tween<Float>(100, Motion.easeOut)
        } else {
            Motion.spring<Float>(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium)
        },
        label = "press",
    )
    return graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

/**
 * First-list fade and a 6dp rise. Items stagger 35ms, and only the first 8 move.
 * Scroll and typing should not call this.
 */
@Composable
fun Modifier.appear(index: Int = 0): Modifier {
    val on = Motion.enabled
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val delayMs = index.coerceIn(0, 7) * 35
    val alpha by animateFloatAsState(
        targetValue = if (!on || shown) 1f else 0f,
        animationSpec = Motion.tween<Float>(Motion.MED, Motion.easeOut, delayMs = if (on) delayMs else 0),
        label = "appearA",
    )
    val dy by animateDpAsState(
        targetValue = if (!on || shown) 0.dp else 6.dp,
        animationSpec = Motion.tween<Dp>(Motion.MED, Motion.easeOut, delayMs = if (on) delayMs else 0),
        label = "appearY",
    )
    if (!on) return this
    return graphicsLayer {
        this.alpha = alpha
        translationY = dy.toPx()
    }
}

/**
 * Menu / sheet entrance from the trigger. Starts at scale 0.94 and alpha 0.
 * [origin] is the trigger (top, corner, or center), not a default center unless
 * the caller has no anchor.
 */
@Composable
fun PopupScale(
    visible: Boolean,
    origin: TransformOrigin,
    content: @Composable () -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(Motion.tween<Float>(160, Motion.easeOut)) + scaleIn(
            animationSpec = Motion.tween<Float>(160, Motion.easeOut),
            initialScale = 0.94f,
            transformOrigin = origin,
        ),
        exit = fadeOut(Motion.tween<Float>(110, Motion.easeOut)) + scaleOut(
            animationSpec = Motion.tween<Float>(110, Motion.easeOut),
            targetScale = 0.94f,
            transformOrigin = origin,
        ),
    ) {
        content()
    }
}
