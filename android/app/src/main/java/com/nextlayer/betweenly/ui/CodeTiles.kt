package com.nextlayer.betweenly.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nextlayer.betweenly.Core
import com.nextlayer.betweenly.ui.icons.LuIcon
import androidx.compose.ui.graphics.vector.ImageVector

/** Letters and digits only, at most 8, uppercased. The space is for reading. */
internal fun groupCode(raw: String): String {
    val chars = raw.uppercase().filter { it.isLetterOrDigit() }.take(8)
    return if (chars.length <= 4) chars else chars.substring(0, 4) + " " + chars.substring(4)
}

internal fun compactCode(code: String): String = code.filter { it.isLetterOrDigit() }

internal fun codeAccepted(raw: String): Boolean = try {
    Core.normalizeCode(raw).isNotEmpty()
} catch (_: Exception) {
    false
}

/** Eight tiles, two groups of four. Each tile flips in. Empty slots stay as placeholders. */
@Composable
fun CodeShowTiles(code: String, modifier: Modifier = Modifier) {
    val chars = compactCode(code).uppercase()
    Column(
        modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        TileRow(chars, 0)
        TileRow(chars, 4)
    }
}

@Composable
private fun TileRow(chars: String, start: Int) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        repeat(4) { offset ->
            val index = start + offset
            val shown = chars.getOrNull(index)?.toString() ?: ""
            val populated = shown.isNotEmpty()
            val turn by animateFloatAsState(
                targetValue = if (populated) 0f else 90f,
                animationSpec = Motion.tween(
                    220,
                    Motion.easeOut,
                    delayMs = if (populated) index * 35 else 0,
                ),
                label = "flip",
            )
            // Empty tiles stay face-up. A new letter flips in from the side.
            val facing = if (populated) turn else 0f
            Box(
                Modifier
                    .weight(1f)
                    .heightIn(min = 64.dp)
                    .graphicsLayer {
                        rotationY = facing
                        cameraDistance = 12f * density
                    }
                    .darkGlass(RoundedCornerShape(LdRadius.tile))
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    shown,
                    style = MaterialTheme.typography.headlineSmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 26.sp,
                    ),
                    color = LdColors.onGlass,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/**
 * Eight boxes over one field so paste, backspace, and the IME behave as a single code.
 * A full code that [Core.normalizeCode] rejects shakes once. Typing itself does not animate.
 */
@Composable
fun CodeEntry(
    value: String,
    onValue: (String) -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val chars = compactCode(value).uppercase()
    val invalid = chars.length >= 8 && !codeAccepted(value)
    val focus = remember { FocusRequester() }
    val shake = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        try {
            focus.requestFocus()
        } catch (_: Exception) {
            // The field can still be tapped.
        }
    }
    LaunchedEffect(chars, invalid) {
        if (!invalid) return@LaunchedEffect
        if (!Motion.enabled) {
            shake.snapTo(0f)
            return@LaunchedEffect
        }
        shake.snapTo(0f)
        shake.animateTo(12f, Motion.tween(50, Motion.easeInOut))
        shake.animateTo(-12f, Motion.tween(70, Motion.easeInOut))
        shake.animateTo(7f, Motion.tween(60, Motion.easeInOut))
        shake.animateTo(0f, Motion.tween(70, Motion.easeOut))
    }
    BasicTextField(
        value = chars,
        onValueChange = { incoming -> onValue(groupCode(incoming)) },
        modifier = modifier
            .fillMaxWidth()
            .focusRequester(focus)
            .graphicsLayer { translationX = shake.value },
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = LdColors.clear),
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.Characters,
            keyboardType = KeyboardType.Ascii,
            imeAction = ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        cursorBrush = SolidColor(LdColors.clear),
        decorationBox = { inner ->
            Box {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    EntryRow(chars, 0)
                    EntryRow(chars, 4)
                }
                Box(Modifier.matchParentSize().alpha(0f)) { inner() }
            }
        },
    )
}

@Composable
private fun EntryRow(chars: String, start: Int) {
    val active = chars.length.coerceIn(0, 7)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        repeat(4) { offset ->
            val index = start + offset
            val letter = chars.getOrNull(index)?.toString() ?: ""
            val on = index == active && chars.length < 8
            val shape = RoundedCornerShape(LdRadius.tile)
            Box(
                Modifier
                    .weight(1f)
                    .heightIn(min = 56.dp)
                    .darkGlass(shape)
                    .border(
                        width = if (on) 1.5.dp else 0.dp,
                        color = if (on) LdColors.white.copy(alpha = 0.70f) else LdColors.clear,
                        shape = shape,
                    )
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    letter,
                    style = MaterialTheme.typography.headlineSmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 24.sp,
                    ),
                    color = LdColors.onGlass,
                )
            }
        }
    }
}

@Composable
fun IconTextButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier
            .heightIn(min = 48.dp)
            .pressScale(interaction)
            .ldGlass(RoundedCornerShape(LdRadius.chip), strong = true)
            .clickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        LuIcon(icon, contentDescription = null, tint = LdColors.onGlass, size = 20.dp)
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.titleSmall, color = LdColors.onGlass)
    }
}
