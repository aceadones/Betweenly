package com.nextlayer.betweenly.ui.music

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nextlayer.betweenly.R
import com.nextlayer.betweenly.music.MusicUiState
import com.nextlayer.betweenly.music.Track
import com.nextlayer.betweenly.ui.LdButton
import com.nextlayer.betweenly.ui.LdButtonStyle
import com.nextlayer.betweenly.ui.LdColors
import com.nextlayer.betweenly.ui.LdRadius
import com.nextlayer.betweenly.ui.LdType
import com.nextlayer.betweenly.ui.appear
import com.nextlayer.betweenly.ui.icons.Lu
import com.nextlayer.betweenly.ui.icons.LuIcon
import com.nextlayer.betweenly.ui.ldGlass
import com.nextlayer.betweenly.ui.pressScale

@Composable
internal fun MusicSearch(
    value: String,
    onValue: (String) -> Unit,
    hint: String,
    dark: Boolean = false,
) {
    val ink = if (dark) MusicPaint.ink else LdColors.colors.ink
    val muted = if (dark) MusicPaint.muted else LdColors.colors.inkMuted
    val style = LdType.Body.copy(color = ink)
    BasicTextField(
        value = value,
        onValueChange = { onValue(it.take(80)) },
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .then(
                if (dark) {
                    Modifier
                        .clip(RoundedCornerShape(LdRadius.tile))
                        .background(MusicPaint.bgLift)
                } else {
                    Modifier.ldGlass(RoundedCornerShape(LdRadius.tile), strong = true)
                },
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        textStyle = style,
        singleLine = true,
        cursorBrush = SolidColor(MusicPaint.accent),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        decorationBox = { inner ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                LuIcon(Lu.Search, contentDescription = null, tint = muted, size = 20.dp)
                Spacer(Modifier.width(8.dp))
                Box(Modifier.weight(1f)) {
                    if (value.isEmpty()) {
                        Text(hint, style = LdType.Body, color = muted, maxLines = 1)
                    }
                    inner()
                }
            }
        },
    )
}

@Composable
internal fun SongLine(
    track: Track,
    current: Boolean,
    dark: Boolean,
    onClick: () -> Unit,
    trailing: @Composable () -> Unit = {},
) {
    val ink = if (dark) MusicPaint.ink else LdColors.colors.ink
    val muted = if (dark) MusicPaint.muted else LdColors.colors.inkMuted
    val titleColor = if (current) MusicPaint.accent else ink
    val interaction = remember(track.key()) { MutableInteractionSource() }
    val corner = LdRadius.inner(LdRadius.tile, 0.dp).coerceAtLeast(12.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .pressScale(interaction)
            .clickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
                role = Role.Button,
                onClick = onClick,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TrackThumb(track, Modifier.size(48.dp), corner = corner)
        Column(
            Modifier
                .weight(1f)
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                track.title.ifBlank { stringResource(R.string.music_nothing_playing) },
                style = LdType.Body,
                color = titleColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                track.artist.ifBlank { stringResource(R.string.music_unknown_artist) },
                style = LdType.Caption,
                color = muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (track.durationMs > 0L) {
            Text(
                formatTrackClock(track.durationMs),
                style = clockStyle(muted),
                maxLines = 1,
            )
        }
        trailing()
    }
}

@Composable
internal fun IconTap(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    tint: androidx.compose.ui.graphics.Color,
    enabled: Boolean = true,
    box: androidx.compose.ui.unit.Dp = 48.dp,
) {
    val interaction = remember(description) { MutableInteractionSource() }
    Box(
        Modifier
            .pressScale(interaction)
            .size(box)
            .clip(CircleShape)
            .clickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        LuIcon(
            icon,
            contentDescription = description,
            tint = tint.copy(alpha = if (enabled) 1f else 0.4f),
            size = if (box < 48.dp) 20.dp else 24.dp,
        )
    }
}

@Composable
internal fun RoseButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    filled: Boolean = true,
) {
    LdButton(
        text = text,
        onClick = onClick,
        modifier = modifier,
        style = if (filled) LdButtonStyle.Primary else LdButtonStyle.Secondary,
    )
}

@Composable
internal fun SectionRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    index: Int = 0,
) {
    val palette = LdColors.colors
    val interaction = remember(title) { MutableInteractionSource() }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .appear(index)
            .pressScale(interaction)
            .clickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
                role = Role.Button,
                onClick = onClick,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(LdRadius.inner(LdRadius.tile, 8.dp)))
                .background(MusicPaint.accent.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            LuIcon(icon, contentDescription = null, tint = MusicPaint.accent)
        }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = LdType.Body, color = palette.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotEmpty()) {
                Text(subtitle, style = LdType.Caption, color = palette.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        LuIcon(Lu.ChevronRight, contentDescription = null, tint = palette.inkMuted, size = 20.dp)
    }
}

@Composable
internal fun EmptyBlock(icon: ImageVector, text: String, dark: Boolean = false) {
    val muted = if (dark) MusicPaint.muted else LdColors.colors.inkMuted
    val ink = if (dark) MusicPaint.ink else LdColors.colors.ink
    Column(
        Modifier.fillMaxWidth().padding(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.size(72.dp).then(
                if (dark) Modifier.clip(CircleShape).background(MusicPaint.bgLift)
                else Modifier.ldGlass(CircleShape, strong = true),
            ),
            contentAlignment = Alignment.Center,
        ) {
            LuIcon(icon, contentDescription = null, tint = ink, size = 28.dp)
        }
        Text(text, style = LdType.Body, color = muted, textAlign = TextAlign.Center)
    }
}

@Composable
internal fun MiniBar(
    state: MusicUiState,
    onOpen: () -> Unit,
    onToggle: () -> Unit,
) {
    val palette = LdColors.colors
    val track = state.track ?: return
    val shape = RoundedCornerShape(LdRadius.card)
    val artShape = LdRadius.inner(LdRadius.card, 8.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .ldGlass(shape, strong = true)
            .clickable(role = Role.Button, onClick = onOpen)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TrackThumb(track, Modifier.size(48.dp), corner = artShape)
        Column(
            Modifier
                .weight(1f)
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                track.title.ifBlank { stringResource(R.string.music_nothing_playing) },
                style = LdType.Body,
                color = palette.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                track.artist.ifBlank { stringResource(R.string.music_unknown_artist) },
                style = LdType.Caption,
                color = palette.inkMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box(
            Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(MusicPaint.accent)
                .clickable(role = Role.Button, onClick = onToggle),
            contentAlignment = Alignment.Center,
        ) {
            LuIcon(
                if (state.playing) Lu.Pause else Lu.Play,
                contentDescription = stringResource(if (state.playing) R.string.music_pause else R.string.music_play),
                tint = MusicPaint.white,
            )
        }
    }
}

@Composable
internal fun BannerLine(text: String, dark: Boolean = false) {
    val ink = if (dark) MusicPaint.ink else LdColors.colors.ink
    Row(
        Modifier
            .fillMaxWidth()
            .then(
                if (dark) Modifier.clip(RoundedCornerShape(LdRadius.tile)).background(MusicPaint.bgLift)
                else Modifier.ldGlass(RoundedCornerShape(LdRadius.tile), strong = true),
            )
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LuIcon(Lu.Info, contentDescription = null, tint = MusicPaint.accent, size = 20.dp)
        Text(text, style = LdType.Caption, color = ink)
    }
}
