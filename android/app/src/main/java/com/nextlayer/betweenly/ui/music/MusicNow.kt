package com.nextlayer.betweenly.ui.music

import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nextlayer.betweenly.R
import com.nextlayer.betweenly.music.MusicTransfer
import com.nextlayer.betweenly.music.MusicUiState
import com.nextlayer.betweenly.music.RepeatMode
import com.nextlayer.betweenly.music.Together
import com.nextlayer.betweenly.music.Track
import com.nextlayer.betweenly.ui.LdType
import com.nextlayer.betweenly.ui.Motion
import com.nextlayer.betweenly.ui.icons.Lu
import com.nextlayer.betweenly.ui.icons.LuIcon
import com.nextlayer.betweenly.ui.pressScale
import kotlinx.coroutines.delay

@Composable
internal fun MusicNow(
    state: MusicUiState,
    onCollapse: () -> Unit,
    onToggle: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onShuffle: () -> Unit,
    onRepeat: () -> Unit,
    onLike: () -> Unit,
    onPreview: (Float) -> Unit,
    onCommit: () -> Unit,
    onMenu: () -> Unit,
    onTogether: () -> Unit,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
    onRemove: (Int) -> Unit,
    onPlay: (Track) -> Unit,
) {
    val view = LocalView.current
    val track = state.track
    val liked = track != null && track.key() in state.likedKeys
    val dur = state.durationMs.coerceAtLeast(0L)
    val fraction = if (dur <= 0L) 0f else (state.positionMs.toFloat() / dur.toFloat()).coerceIn(0f, 1f)
    val remain = formatTrackClock((dur - state.positionMs).coerceAtLeast(0L))
    val start = state.queue.indexOfFirst { it.matches(track) }
    val upcoming = if (start >= 0) state.queue.drop(start + 1) else state.queue.filter { track == null || !it.matches(track) }
    val base = if (start >= 0) start + 1 else 0
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(MusicPaint.bg, MusicPaint.bgLift, MusicPaint.bg))),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp),
        ) {
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconTap(Lu.ChevronDown, stringResource(R.string.music_close_player), onCollapse, MusicPaint.ink)
                Box(Modifier.weight(1f))
                IconTap(Lu.EllipsisVertical, stringResource(R.string.music_menu), onMenu, MusicPaint.ink)
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        track?.title?.ifBlank { null } ?: stringResource(R.string.music_nothing_playing),
                        style = LdType.PageTitle,
                        color = MusicPaint.ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (track != null) {
                        Text(
                            track.artist.ifBlank { stringResource(R.string.music_unknown_artist) },
                            style = LdType.Body,
                            color = MusicPaint.muted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                IconTap(
                    Lu.Heart,
                    stringResource(if (liked) R.string.music_unlike else R.string.music_like),
                    onLike,
                    if (liked) MusicPaint.accent else MusicPaint.muted,
                )
            }
            Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                SeekRing(
                    track = track,
                    fraction = fraction,
                    remaining = remain,
                    onPreview = onPreview,
                    onCommit = onCommit,
                )
            }
            val transfer = when (state.transfer) {
                MusicTransfer.Sending -> stringResource(R.string.music_sending)
                MusicTransfer.Downloading -> stringResource(R.string.music_downloading)
                MusicTransfer.Idle -> null
            }
            if (transfer != null) {
                Text(transfer, style = LdType.Caption, color = MusicPaint.muted, modifier = Modifier.padding(bottom = 8.dp))
            }
            Transport(
                state = state,
                onShuffle = onShuffle,
                onPrev = onPrev,
                onToggle = {
                    view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                    onToggle()
                },
                onNext = onNext,
                onRepeat = onRepeat,
            )
            TogetherPill(state.together, state.sleepMinutes, onTogether, onAccept, onDecline)
            val banner = musicBanner(state.banner)
            if (banner != null) {
                BannerLine(banner, dark = true)
            }
            Text(
                stringResource(R.string.music_next_songs),
                style = LdType.Title,
                color = MusicPaint.ink,
                modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
            )
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                if (upcoming.isEmpty()) {
                    item { EmptyBlock(Lu.ListMusic, stringResource(R.string.music_empty_queue), dark = true) }
                }
                itemsIndexed(upcoming, key = { index, item -> "up:$index:${item.key()}" }) { index, item ->
                    SwipeAway(onRemove = { onRemove(base + index) }) {
                        SongLine(
                            track = item,
                            current = false,
                            dark = true,
                            onClick = { onPlay(item) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SwipeAway(onRemove: () -> Unit, content: @Composable () -> Unit) {
    var drag by remember { mutableFloatStateOf(0f) }
    val max = with(LocalDensity.current) { 88.dp.toPx() }
    Box(Modifier.fillMaxWidth().heightIn(min = 64.dp)) {
        Box(
            Modifier
                .matchParentSize()
                .clip(RoundedCornerShape(12.dp))
                .background(MusicPaint.deep),
            contentAlignment = Alignment.CenterEnd,
        ) {
            LuIcon(
                Lu.Trash2,
                contentDescription = stringResource(R.string.music_remove),
                tint = MusicPaint.white,
                modifier = Modifier.padding(end = 16.dp),
            )
        }
        Box(
            Modifier
                .fillMaxWidth()
                .graphicsLayer { translationX = drag }
                .background(MusicPaint.bg)
                .pointerInput(onRemove) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            if (drag < -max * 0.55f) onRemove()
                            drag = 0f
                        },
                        onHorizontalDrag = { change, amount ->
                            change.consume()
                            drag = (drag + amount).coerceIn(-max, 0f)
                        },
                    )
                },
        ) { content() }
    }
}

@Composable
private fun Transport(
    state: MusicUiState,
    onShuffle: () -> Unit,
    onPrev: () -> Unit,
    onToggle: () -> Unit,
    onNext: () -> Unit,
    onRepeat: () -> Unit,
) {
    val canSkip = state.track != null || state.queue.isNotEmpty()
    val repeatLabel = when (state.repeat) {
        RepeatMode.Off -> stringResource(R.string.music_repeat)
        RepeatMode.All -> stringResource(R.string.music_repeat_all)
        RepeatMode.One -> stringResource(R.string.music_repeat_one)
    }
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        IconTap(Lu.Sparkles, stringResource(R.string.music_shuffle), onShuffle, if (state.shuffle) MusicPaint.accent else MusicPaint.ink)
        IconTap(Lu.SkipBack, stringResource(R.string.music_previous), onPrev, MusicPaint.ink, canSkip)
        PlayOrb(state.playing, onToggle)
        IconTap(Lu.SkipForward, stringResource(R.string.music_next), onNext, MusicPaint.ink, canSkip)
        Box {
            IconTap(Lu.RefreshCw, repeatLabel, onRepeat, if (state.repeat != RepeatMode.Off) MusicPaint.accent else MusicPaint.ink)
            if (state.repeat == RepeatMode.One) {
                Text(
                    "1",
                    modifier = Modifier.align(Alignment.TopEnd).padding(top = 6.dp, end = 6.dp),
                    style = clockStyle(MusicPaint.accent),
                )
            }
        }
    }
}

@Composable
private fun PlayOrb(playing: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    var armed by remember { androidx.compose.runtime.mutableStateOf(!Motion.enabled) }
    LaunchedEffect(playing) {
        if (!Motion.enabled) {
            armed = true
            return@LaunchedEffect
        }
        armed = false
        delay(16)
        armed = true
    }
    val scale by animateFloatAsState(
        targetValue = if (!Motion.enabled || armed) 1f else 0.94f,
        animationSpec = Motion.spring<Float>(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "orb",
    )
    Box(
        Modifier
            .pressScale(interaction)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .size(72.dp)
            .clip(CircleShape)
            .background(MusicPaint.white)
            .clickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
                role = Role.Button,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        LuIcon(
            if (playing) Lu.Pause else Lu.Play,
            contentDescription = stringResource(if (playing) R.string.music_pause else R.string.music_play),
            tint = MusicPaint.bg,
            size = 28.dp,
        )
    }
}

@Composable
private fun TogetherPill(
    together: Together,
    sleepMinutes: Int,
    onTogether: () -> Unit,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
) {
    val label = when (together) {
        Together.Solo -> stringResource(R.string.music_together)
        Together.Inviting -> stringResource(R.string.music_waiting_short)
        Together.Active -> stringResource(R.string.music_together_on)
        Together.Invited -> stringResource(R.string.music_invited)
    }
    val interaction = remember(together) { MutableInteractionSource() }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (together == Together.Invited) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill(stringResource(R.string.music_accept), onAccept, filled = true, modifier = Modifier.weight(1f))
                Pill(stringResource(R.string.music_decline), onDecline, filled = false, modifier = Modifier.weight(1f))
            }
        } else {
            Pill(label, onTogether, filled = together != Together.Solo, modifier = Modifier.fillMaxWidth(), interaction = interaction)
        }
        if (sleepMinutes > 0) {
            Text(
                stringResource(R.string.music_sleep_set),
                style = LdType.Caption,
                color = MusicPaint.muted,
            )
        }
    }
}

@Composable
private fun Pill(
    text: String,
    onClick: () -> Unit,
    filled: Boolean,
    modifier: Modifier = Modifier,
    interaction: MutableInteractionSource = remember { MutableInteractionSource() },
) {
    Box(
        modifier
            .pressScale(interaction)
            .heightIn(min = 44.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(if (filled) MusicPaint.accent else MusicPaint.accent.copy(alpha = 0.16f))
            .clickable(interaction, LocalIndication.current, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = LdType.Body, color = if (filled) MusicPaint.white else MusicPaint.ink, maxLines = 1)
    }
}
