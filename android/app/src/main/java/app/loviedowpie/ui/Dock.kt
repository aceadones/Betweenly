package app.loviedowpie.ui

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.loviedowpie.R
import app.loviedowpie.ui.icons.LuIcon
import kotlin.math.roundToInt

data class LdTab(
    val label: String,
    val icon: ImageVector,
    val selected: Boolean,
    val badge: Int = 0,
    val onClick: () -> Unit,
)

private data class Slot(val x: Int, val w: Int)

/**
 * Floating glass dock. Outer radius 28dp, padding 6dp, so the selection pill is 22dp.
 * The selected tab shows its icon and label. The others are an icon only.
 * The camera is not on this bar.
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
            val density = LocalDensity.current
            val spec: FiniteAnimationSpec<Dp> = if (primed) Motion.spring<Dp>() else snap<Dp>()
            val x by animateDpAsState(
                targetValue = with(density) { target.x.toDp() },
                animationSpec = spec,
                label = "dockX",
            )
            val w by animateDpAsState(
                targetValue = with(density) { target.w.toDp() },
                animationSpec = spec,
                label = "dockW",
            )
            SideEffect {
                if (!primed && target.w > 0) primed = true
            }
            if (target.w > 0) {
                val wash = LdColors.primary.copy(alpha = if (isSystemInDarkTheme()) 0.34f else 0.16f)
                Box(
                    Modifier
                        .offset(x = x)
                        .width(w)
                        .fillMaxHeight()
                        .background(wash, pill),
                )
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
    val c = LdColors.colors
    val tint = if (tab.selected) c.ink else c.navIdle
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
            )
        }
        if (tab.badge > 0) {
            BadgedBox(
                badge = {
                    Badge(containerColor = LdColors.heart, contentColor = LdColors.white) {
                        Text(if (tab.badge > 99) "99+" else tab.badge.toString())
                    }
                },
            ) { glyph() }
        } else {
            glyph()
        }
        if (tab.selected) {
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
