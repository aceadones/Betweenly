package com.nextlayer.betweenly.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector2D
import androidx.compose.animation.core.TwoWayConverter
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nextlayer.betweenly.R
import com.nextlayer.betweenly.ui.icons.LuIcon
import kotlin.math.roundToInt

data class LdTab(
    val label: String,
    val icon: ImageVector,
    val selected: Boolean,
    val badge: Int = 0,
    val onClick: () -> Unit,
)

private data class Slot(val x: Int, val w: Int)

private data class Span(val x: Float, val w: Float)

private val SpanVector = TwoWayConverter<Span, AnimationVector2D>(
    convertToVector = { AnimationVector2D(it.x, it.w) },
    convertFromVector = { Span(it.v1, it.v2) },
)

/**
 * Floating glass dock. Outer radius 28dp, padding 6dp, so the selection pill is 22dp.
 * One spring moves the pill's offset and width. The selected icon scales up and its
 * label fades in. The camera is not on this bar.
 */
@Composable
fun LdBottomBar(tabs: List<LdTab>) {
    if (tabs.isEmpty()) return
    val outer = LdRadius.card
    val inset = 6.dp
    val pill = concentricShape(outer, inset)
    val selected = tabs.indexOfFirst { it.selected }.coerceAtLeast(0)
    Box(
        Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(64.dp)
                .ldGlass(RoundedCornerShape(outer), strong = true)
                .padding(inset),
        ) {
            var slots by remember(tabs.size) { mutableStateOf(List(tabs.size) { Slot(0, 0) }) }
            var primed by remember { mutableStateOf(false) }
            val target = slots.getOrElse(selected) { Slot(0, 0) }
            val span = remember { Animatable(Span(0f, 0f), SpanVector) }
            LaunchedEffect(target.x, target.w) {
                if (target.w <= 0) return@LaunchedEffect
                val next = Span(target.x.toFloat(), target.w.toFloat())
                if (!primed) {
                    span.snapTo(next)
                    primed = true
                } else {
                    span.animateTo(
                        next,
                        Motion.spring(dampingRatio = 0.8f, stiffness = 400f),
                    )
                }
            }
            val wash = Color.White
            val drawn = span.value
            if (drawn.w > 1f) {
                Canvas(Modifier.fillMaxSize()) {
                    drawRoundRect(
                        color = wash,
                        topLeft = Offset(drawn.x, 0f),
                        size = Size(drawn.w, size.height),
                        cornerRadius = CornerRadius(size.height / 2f, size.height / 2f),
                    )
                }
            }
            Row(
                Modifier.fillMaxSize(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                tabs.forEachIndexed { index, tab ->
                    DockTab(
                        tab = tab,
                        pill = pill,
                        weight = if (tab.selected) 1.7f else 1f,
                        modifier = Modifier.onGloballyPositioned { coords ->
                            val next = Slot(
                                coords.positionInParent().x.roundToInt(),
                                coords.size.width,
                            )
                            if (slots.getOrNull(index) != next) {
                                slots = slots.toMutableList().also { it[index] = next }
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun RowScope.DockTab(
    tab: LdTab,
    pill: androidx.compose.foundation.shape.CornerBasedShape,
    weight: Float,
    modifier: Modifier,
) {
    val tint = if (tab.selected) LdColors.navy else LdColors.onGlass
    val interaction = remember(tab.label) { MutableInteractionSource() }
    val spoken = if (tab.badge > 0) {
        stringResource(R.string.tab_unread, tab.label, tab.badge)
    } else {
        tab.label
    }
    Row(
        modifier
            .weight(weight)
            .fillMaxHeight()
            .pressScale(interaction)
            .clip(pill)
            .clickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
                role = Role.Tab,
                onClick = tab.onClick,
            )
            .semantics {
                selected = tab.selected
                contentDescription = spoken
            },
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val glyph = @Composable {
            LuIcon(
                icon = tab.icon,
                contentDescription = null,
                tint = tint,
                selected = tab.selected,
                secondaryAlpha = if (tab.selected) 0.38f else 0.28f,
            )
        }
        if (tab.badge > 0) {
            BadgedBox(
                badge = {
                    Badge(containerColor = LdColors.primary, contentColor = LdColors.white) {
                        Text(if (tab.badge > 99) "99+" else tab.badge.toString())
                    }
                },
            ) { glyph() }
        } else {
            glyph()
        }
        AnimatedVisibility(
            visible = tab.selected,
            enter = fadeIn(Motion.tween(220, Motion.easeOut)) +
                expandHorizontally(Motion.tween(220, Motion.easeOut), expandFrom = Alignment.Start),
            exit = fadeOut(Motion.tween(140, Motion.easeOut)) +
                shrinkHorizontally(Motion.tween(140, Motion.easeOut), shrinkTowards = Alignment.Start),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.width(8.dp))
                Text(
                    tab.label,
                    color = tint,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
