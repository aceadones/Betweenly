package com.nextlayer.betweenly.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nextlayer.betweenly.ui.icons.LuIcon

/** Glass section. Children sit on the 8dp grid inside a 28dp card. */
@Composable
fun GlassGroup(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    CompositionLocalProvider(LocalOnGlass provides true) {
        Column(
            modifier
                .fillMaxWidth()
                .ldGlass(RoundedCornerShape(LdRadius.card))
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            content = content,
        )
    }
}

@Composable
fun SectionLabel(icon: ImageVector, text: String, modifier: Modifier = Modifier) {
    Row(
        modifier.heightIn(min = 40.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LuIcon(icon, contentDescription = null, tint = surfaceInk(), size = 20.dp)
        Spacer(Modifier.width(10.dp))
        Text(
            text,
            style = MaterialTheme.typography.titleMedium,
            color = surfaceInk(),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun BentoStat(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    visual: @Composable (() -> Unit)? = null,
) {
    val shape = RoundedCornerShape(LdRadius.card)
    CompositionLocalProvider(LocalOnGlass provides true) {
    Column(
        modifier
            .heightIn(min = 112.dp)
            .ldGlass(shape)
            .padding(14.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = LdColors.onGlassMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (visual != null) {
            Box(Modifier.padding(vertical = 6.dp)) { visual() }
        }
        Text(
            value,
            style = MaterialTheme.typography.titleMedium,
            color = LdColors.onGlass,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
    }
}

/** Large tap tile. Card radius 28dp, icon well is 28 − 16 = 12dp. */
@Composable
fun BentoAction(
    title: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(LdRadius.card)
    val well = RoundedCornerShape(LdRadius.inner(LdRadius.card, 16.dp))
    Column(
        modifier
            .heightIn(min = 120.dp)
            .pressScale(interaction)
            .ldGlass(shape, strong = true)
            .clickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(16.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Box(
            Modifier
                .size(44.dp)
                .background(Color.White.copy(alpha = 0.16f), well),
            contentAlignment = Alignment.Center,
        ) {
            LuIcon(icon, contentDescription = null, tint = LdColors.onGlass, size = 22.dp)
        }
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            color = LdColors.onGlass,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun BatteryRing(percent: Int, modifier: Modifier = Modifier, diameter: Dp = 52.dp) {
    val known = percent in 0..100
    val sweep = if (known) percent / 100f else 0f
    val color = when {
        !known -> if (LocalOnGlass.current) LdColors.onGlassMuted else LdColors.colors.inkMuted
        percent <= 15 -> LdColors.heart
        else -> LdColors.success
    }
    // LdColors.colors is composable; read it before the draw scope.
    val track = surfaceInk().copy(alpha = 0.22f)
    val strokeWidth = if (diameter < 32.dp) 2.5.dp else 5.dp
    Canvas(modifier.size(diameter)) {
        val stroke = Stroke(width = strokeWidth.toPx(), cap = StrokeCap.Round)
        val inset = 4.dp.toPx()
        drawArc(
            color = track,
            startAngle = -90f,
            sweepAngle = 360f,
            useCenter = false,
            topLeft = Offset(inset, inset),
            size = Size(size.width - inset * 2, size.height - inset * 2),
            style = stroke,
        )
        if (known) {
            drawArc(
                color = color,
                startAngle = -90f,
                sweepAngle = 360f * sweep,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = Size(size.width - inset * 2, size.height - inset * 2),
                style = stroke,
            )
        }
    }
}

/**
 * Borderless name field. The label lifts and the underline grows from the center.
 * Typing itself is not animated beyond that focus change.
 */
@Composable
fun UnderlineNameField(
    value: String,
    onValue: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    onDone: () -> Unit = {},
    onFocus: (Boolean) -> Unit = {},
) {
    var focused by remember { mutableStateOf(false) }
    val lifted by animateFloatAsState(
        targetValue = if (focused || value.isNotEmpty()) 1f else 0f,
        animationSpec = Motion.tween(160, Motion.easeOut),
        label = "label",
    )
    val line by animateFloatAsState(
        targetValue = if (focused) 1f else 0f,
        animationSpec = Motion.tween(180, Motion.easeOut),
        label = "line",
    )
    val ink = LdColors.colors.ink
    Column(modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(92.dp)) {
            BasicTextField(
                value = value,
                onValueChange = onValue,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .onFocusChanged {
                        focused = it.isFocused
                        onFocus(it.isFocused)
                    },
                textStyle = LdType.PageTitle.copy(color = ink),
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Words,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { onDone() }),
                cursorBrush = SolidColor(LdColors.primary),
            )
            Text(
                label,
                color = LdColors.colors.inkMuted,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.graphicsLayer {
                    val scale = 1f - 0.22f * lifted
                    scaleX = scale
                    scaleY = scale
                    translationY = (1f - lifted) * 36.dp.toPx()
                    transformOrigin = TransformOrigin(0f, 1f)
                },
            )
        }
        Canvas(Modifier.fillMaxWidth().height(3.dp)) {
            drawLine(
                color = ink.copy(alpha = 0.28f),
                start = Offset(0f, size.height / 2f),
                end = Offset(size.width, size.height / 2f),
                strokeWidth = 1.dp.toPx(),
                cap = StrokeCap.Round,
            )
            val w = size.width * line
            drawLine(
                color = LdColors.primary,
                start = Offset((size.width - w) / 2f, size.height / 2f),
                end = Offset((size.width + w) / 2f, size.height / 2f),
                strokeWidth = 2.4.dp.toPx(),
                cap = StrokeCap.Round,
            )
        }
    }
}

@Composable
fun DestructiveBlock(
    title: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dark = isSystemInDarkTheme()
    val bg = if (dark) Color(0xFF3A1821) else Color(0xFFFFE8E6)
    val fg = if (dark) Color(0xFFFFB4AB) else Color(0xFF9F1C1C)
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(LdRadius.card)
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .pressScale(interaction)
            .background(bg, shape)
            .clickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LuIcon(icon, contentDescription = null, tint = fg, size = 22.dp)
        Spacer(Modifier.width(12.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, color = fg)
    }
}

@Composable
fun QuietButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    LdButton(
        text = text,
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        style = LdButtonStyle.Tertiary,
    )
}
