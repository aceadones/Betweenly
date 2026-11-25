package com.nextlayer.betweenly.ui

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nextlayer.betweenly.ui.icons.LuIcon

/** Primary, secondary, and tertiary actions. A tap, not a swipe. */
enum class LdButtonStyle { Primary, Secondary, Tertiary }

/**
 * Shared button. Primary is a 56dp accent pill. Secondary is glass with accent
 * text. Tertiary is a text button. [icon] is a small trailing mark only when
 * it names the action. Disabled is a neutral control at 40% alpha.
 */
@Composable
fun LdButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: LdButtonStyle = LdButtonStyle.Primary,
    enabled: Boolean = true,
    loading: Boolean = false,
    icon: ImageVector? = null,
    contentColor: Color? = null,
) {
    val c = LdColors.colors
    val shape = RoundedCornerShape(LdRadius.chip)
    val interaction = remember(text) { MutableInteractionSource() }
    val live = enabled && !loading
    val accent = LdColors.primary
    val onAccent = LdColors.white
    val label = when (style) {
        LdButtonStyle.Primary -> if (enabled) onAccent else c.ink
        LdButtonStyle.Secondary -> contentColor ?: accent
        LdButtonStyle.Tertiary -> contentColor ?: c.textPrimary
    }
    val height = if (style == LdButtonStyle.Tertiary) 48.dp else 56.dp
    var plate = modifier
        .height(height)
        .alpha(if (enabled) 1f else 0.4f)
    plate = when (style) {
        LdButtonStyle.Primary -> {
            if (enabled) {
                plate
                    .shadow(
                        elevation = 12.dp,
                        shape = shape,
                        clip = false,
                        ambientColor = accent.copy(alpha = 0.12f),
                        spotColor = LdColors.glow.copy(alpha = 0.12f),
                    )
                    .clip(shape)
                    .background(Brush.verticalGradient(listOf(accent, LdColors.deep)))
                    .background(
                        Brush.verticalGradient(
                            0f to Color.White.copy(alpha = 0.22f),
                            0.42f to Color.White.copy(alpha = 0.05f),
                            1f to Color.Transparent,
                        ),
                    )
            } else {
                plate.clip(shape).background(c.inkMuted)
            }
        }
        LdButtonStyle.Secondary -> plate.ldGlass(shape, strong = true)
        LdButtonStyle.Tertiary -> plate.clip(shape)
    }
    Box(
        plate
            .pressScale(interaction)
            .clickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
                enabled = live,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = label,
                )
                Spacer(Modifier.width(10.dp))
            }
            Text(
                text,
                color = label,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (icon != null && !loading) {
                Spacer(Modifier.width(8.dp))
                LuIcon(icon, contentDescription = null, tint = label, size = 18.dp, duo = true)
            }
        }
    }
}
