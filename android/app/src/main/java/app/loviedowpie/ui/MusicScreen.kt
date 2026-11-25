package app.loviedowpie.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.view.HapticFeedbackConstants
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.loviedowpie.R
import app.loviedowpie.Store
import app.loviedowpie.music.LocalSource
import app.loviedowpie.music.MusicBanner
import app.loviedowpie.music.MusicBridge
import app.loviedowpie.music.MusicSession
import app.loviedowpie.music.MusicTransfer
import app.loviedowpie.music.MusicUiState
import app.loviedowpie.music.RepeatMode
import app.loviedowpie.music.SourceOption
import app.loviedowpie.music.Together
import app.loviedowpie.music.Track
import app.loviedowpie.ui.icons.Lu
import app.loviedowpie.ui.icons.LuIcon
import kotlinx.coroutines.delay

/**
 * Hear together. The session, player, and catalog exist only while this
 * screen is composed. Closing it leaves the session and releases the player.
 *
 * DATA-FLOW: none in this file. Sends and file transfer live in MusicSession.
 */
@Composable
fun MusicScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val host = context.findActivity()
    val controller = remember {
        MusicBridge.attached() ?: MusicSession(context.applicationContext)
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(controller) {
        controller.open()
        onDispose { controller.close(host?.isChangingConfigurations == true) }
    }
    DisposableEffect(lifecycle, controller) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> controller.onShown()
                Lifecycle.Event.ON_STOP -> {
                    if (host?.isChangingConfigurations != true) controller.onHidden()
                }
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) controller.onShown()
        onDispose { lifecycle.removeObserver(observer) }
    }

    val state = controller.state
    val revision by Store.changes.collectAsState()
    val partner = remember(revision) {
        Store.partnerName(context).ifBlank { context.getString(R.string.partner_fallback) }
    }
    val view = LocalView.current
    val palette = LdColors.colors
    var asked by rememberSaveable { mutableStateOf(false) }
    val permission = LocalSource.permission()
    val grant = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        asked = true
        controller.refreshPermission()
    }
    val settings = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        controller.refreshPermission()
    }
    val showPermission = state.sourceId == LocalSource.ID &&
        !state.audioGranted &&
        !LocalSource.permitted(context)
    val permanent = asked && showPermission && host != null &&
        !ActivityCompat.shouldShowRequestPermissionRationale(host, permission)
    val sources = state.sources.ifEmpty { listOf(SourceOption(LocalSource.ID, "")) }
    val seekLabel = stringResource(R.string.music_seek)

    Scaffold(
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets(0.dp, 0.dp, 0.dp, 0.dp),
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .windowInsetsPadding(WindowInsets.safeDrawing),
            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Header(
                    partner = partner,
                    revision = revision,
                    together = state.together,
                    onClose = onClose,
                    modifier = Modifier.appear(0),
                )
            }
            if (state.together == Together.Invited) {
                item {
                    InviteCard(
                        track = state.inviteTrack ?: state.track,
                        onAccept = { controller.accept() },
                        onDecline = { controller.leave() },
                        modifier = Modifier.appear(1),
                    )
                }
            }
            item {
                Artwork(state.track, Modifier.appear(2))
            }
            item {
                NowPlaying(state)
            }
            item {
                val dur = state.durationMs.coerceAtLeast(0L)
                val value = if (dur <= 0L) 0f else (state.positionMs.toFloat() / dur.toFloat()).coerceIn(0f, 1f)
                Slider(
                    value = value,
                    onValueChange = { fraction ->
                        if (dur > 0L) controller.previewSeek((fraction * dur).toLong())
                    },
                    onValueChangeFinished = { controller.commitSeek() },
                    enabled = dur > 0L,
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = seekLabel },
                    colors = SliderDefaults.colors(
                        thumbColor = LdColors.primary,
                        activeTrackColor = LdColors.primary,
                        inactiveTrackColor = palette.inkMuted.copy(alpha = 0.35f),
                    ),
                )
            }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(formatTrackClock(state.positionMs), style = LdType.Caption, color = palette.inkMuted)
                    Text(formatTrackClock(state.durationMs), style = LdType.Caption, color = palette.inkMuted)
                }
            }
            item {
                Controls(
                    state = state,
                    onShuffle = { controller.toggleShuffle() },
                    onPrevious = { controller.previous() },
                    onToggle = {
                        view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                        controller.toggle()
                    },
                    onNext = { controller.next() },
                    onRepeat = { controller.cycleRepeat() },
                    modifier = Modifier.appear(3),
                )
            }
            if (state.together != Together.Invited) {
                item {
                    val leaving = state.together == Together.Active || state.together == Together.Inviting
                    if (leaving) {
                        SecondaryPill(
                            text = stringResource(R.string.music_leave),
                            onClick = { controller.leave() },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        PrimaryPill(
                            text = stringResource(R.string.music_invite),
                            onClick = { controller.invite() },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
            if (state.banner != MusicBanner.None) {
                item {
                    val banner = bannerText(state.banner)
                    if (banner != null) Banner(banner)
                }
            }
            item {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    sources.forEach { option ->
                        val label = if (option.id == LocalSource.ID) {
                            stringResource(R.string.music_source_local)
                        } else {
                            option.label
                        }
                        SourceChip(
                            label = label,
                            selected = option.id == state.sourceId,
                            onClick = { controller.selectSource(option.id) },
                        )
                    }
                }
            }
            if (showPermission) {
                if (state.queue.isNotEmpty()) {
                    item { SectionLabel(stringResource(R.string.music_queue)) }
                    itemsIndexed(
                        state.queue,
                        key = { index, track -> "q:$index:${track.sourceId}:${track.id}" },
                    ) { index, track ->
                        SongLine(
                            track = track,
                            index = index,
                            animate = state.query.isEmpty(),
                            current = state.track?.matches(track) == true,
                            showAdd = false,
                            onPlay = {
                                view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                                controller.play(track)
                            },
                            onAdd = {},
                        )
                    }
                }
                item {
                    PermissionCard(
                        permanent = permanent,
                        onAllow = { grant.launch(permission) },
                        onSettings = { openSettings(context) { settings.launch(it) } },
                    )
                }
            } else {
                item {
                    SearchField(
                        value = state.query,
                        onValue = { controller.setQuery(it) },
                    )
                }
                if (state.query.isNotBlank()) {
                    if (state.queue.isNotEmpty()) {
                        item { SectionLabel(stringResource(R.string.music_queue)) }
                        itemsIndexed(
                            state.queue,
                            key = { index, track -> "q:$index:${track.sourceId}:${track.id}" },
                        ) { index, track ->
                            SongLine(
                                track = track,
                                index = index,
                                animate = false,
                                current = state.track?.matches(track) == true,
                                showAdd = false,
                                onPlay = {
                                    view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                                    controller.play(track)
                                },
                                onAdd = {},
                            )
                        }
                    }
                    item { SectionLabel(stringResource(R.string.music_results)) }
                    if (!state.searching && state.results.isEmpty()) {
                        item {
                            EmptyHint(Lu.Search, stringResource(R.string.music_empty_search))
                        }
                    }
                    itemsIndexed(
                        state.results,
                        key = { index, track -> "r:$index:${track.sourceId}:${track.id}" },
                    ) { _, track ->
                        SongLine(
                            track = track,
                            index = 8,
                            animate = false,
                            current = state.track?.matches(track) == true,
                            showAdd = state.queue.none { it.matches(track) },
                            onPlay = {
                                view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                                controller.play(track)
                            },
                            onAdd = { controller.enqueue(track) },
                        )
                    }
                } else {
                    if (state.queue.isNotEmpty()) {
                        item { SectionLabel(stringResource(R.string.music_queue)) }
                        itemsIndexed(
                            state.queue,
                            key = { index, track -> "q:$index:${track.sourceId}:${track.id}" },
                        ) { index, track ->
                            SongLine(
                                track = track,
                                index = index,
                                animate = true,
                                current = state.track?.matches(track) == true,
                                showAdd = false,
                                onPlay = {
                                    view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                                    controller.play(track)
                                },
                                onAdd = {},
                            )
                        }
                    }
                    item {
                        val custom = sources.firstOrNull { it.id == state.sourceId }?.label.orEmpty()
                        SectionLabel(
                            if (state.sourceId == LocalSource.ID) {
                                stringResource(R.string.music_on_phone)
                            } else {
                                custom.ifBlank { stringResource(R.string.music_results) }
                            },
                        )
                    }
                    if (!state.searching && state.results.isEmpty()) {
                        item {
                            val onPhone = state.sourceId == LocalSource.ID
                            EmptyHint(
                                if (!onPhone) Lu.Search else if (state.queue.isEmpty()) Lu.Headphones else Lu.Disc3,
                                stringResource(
                                    when {
                                        !onPhone -> R.string.music_empty_search
                                        state.queue.isEmpty() -> R.string.music_empty_library
                                        else -> R.string.music_empty_queue
                                    },
                                ),
                            )
                        }
                    }
                    itemsIndexed(
                        state.results,
                        key = { index, track -> "lib:$index:${track.sourceId}:${track.id}" },
                    ) { index, track ->
                        SongLine(
                            track = track,
                            index = index,
                            animate = true,
                            current = state.track?.matches(track) == true,
                            showAdd = state.queue.none { it.matches(track) },
                            onPlay = {
                                view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                                controller.play(track)
                            },
                            onAdd = { controller.enqueue(track) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Header(
    partner: String,
    revision: Int,
    together: Together,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LdColors.colors
    val context = LocalContext.current
    val label = when (together) {
        Together.Active -> stringResource(R.string.music_with, partner)
        Together.Inviting -> stringResource(R.string.music_waiting, partner)
        Together.Invited -> stringResource(R.string.music_invited)
        Together.Solo -> stringResource(R.string.music_solo)
    }
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        GlassIconButton(
            onClick = onClose,
            icon = Lu.ChevronLeft,
            description = stringResource(R.string.back),
        )
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.music_title),
                style = MaterialTheme.typography.headlineMedium,
                color = palette.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val shape = RoundedCornerShape(LdRadius.chip)
            Row(
                Modifier
                    .heightIn(min = 40.dp)
                    .clip(shape)
                    .background(if (together == Together.Solo) palette.glass else LdColors.primary.copy(alpha = 0.14f))
                    .border(1.dp, palette.glassBorder, shape)
                    .padding(start = 4.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (together != Together.Solo) {
                    RoundAvatar(revision, { Store.partnerAvatar(context) }, partner, 32.dp)
                    Spacer(Modifier.width(8.dp))
                } else {
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    label,
                    style = LdType.Caption,
                    color = palette.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun InviteCard(
    track: Track?,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LdColors.colors
    Column(
        modifier
            .fillMaxWidth()
            .ldGlass(RoundedCornerShape(LdRadius.card), strong = true)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(stringResource(R.string.music_invited), style = LdType.Title, color = palette.ink)
        if (track != null && track.title.isNotBlank()) {
            Text(
                track.title,
                style = LdType.Body,
                color = palette.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (track.artist.isNotBlank()) {
                Text(
                    track.artist,
                    style = LdType.Caption,
                    color = palette.inkMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        PrimaryPill(
            text = stringResource(R.string.music_accept),
            onClick = onAccept,
            modifier = Modifier.fillMaxWidth(),
        )
        SecondaryPill(
            text = stringResource(R.string.music_decline),
            onClick = onDecline,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun Artwork(track: Track?, modifier: Modifier = Modifier) {
    val outer = 32.dp
    val inset = 8.dp
    val brush = if (track == null) {
        Brush.linearGradient(listOf(LdColors.musicWashTop, LdColors.musicWashBottom))
    } else {
        artworkBrush(track.sourceId + ":" + track.id)
    }
    Box(
        modifier
            .fillMaxWidth()
            .height(224.dp)
            .ldGlass(RoundedCornerShape(outer), strong = true)
            .padding(inset),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .clip(concentricShape(outer, inset))
                .background(brush),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .size(80.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center,
            ) {
                LuIcon(
                    Lu.Disc3,
                    contentDescription = stringResource(R.string.music_artwork),
                    tint = Color.White,
                    size = 32.dp,
                )
            }
        }
    }
}

@Composable
private fun NowPlaying(state: MusicUiState) {
    val palette = LdColors.colors
    val title = state.track?.title?.takeIf { it.isNotBlank() }
        ?: stringResource(R.string.music_nothing_playing)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            title,
            style = LdType.Title,
            color = palette.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (state.track != null) {
            Text(
                state.track.artist.ifBlank { stringResource(R.string.music_unknown_artist) },
                style = LdType.Body,
                color = palette.inkMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        val transfer = when (state.transfer) {
            MusicTransfer.Sending -> stringResource(R.string.music_sending)
            MusicTransfer.Downloading -> stringResource(R.string.music_downloading)
            MusicTransfer.Idle -> null
        }
        if (transfer != null) {
            Text(transfer, style = LdType.Caption, color = palette.textPrimary)
        }
    }
}

@Composable
private fun Controls(
    state: MusicUiState,
    onShuffle: () -> Unit,
    onPrevious: () -> Unit,
    onToggle: () -> Unit,
    onNext: () -> Unit,
    onRepeat: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val canSkip = state.track != null || state.queue.isNotEmpty()
    val canToggle = state.playing || canSkip || state.results.isNotEmpty()
    val repeatLabel = when (state.repeat) {
        RepeatMode.Off -> stringResource(R.string.music_repeat)
        RepeatMode.All -> stringResource(R.string.music_repeat_all)
        RepeatMode.One -> stringResource(R.string.music_repeat_one)
    }
    Row(
        modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        RoundControl(
            icon = Lu.Sparkles,
            description = stringResource(R.string.music_shuffle),
            selected = state.shuffle,
            onClick = onShuffle,
        )
        RoundControl(
            icon = Lu.SkipBack,
            description = stringResource(R.string.music_previous),
            selected = false,
            enabled = canSkip,
            onClick = onPrevious,
        )
        PlayButton(playing = state.playing, enabled = canToggle, onClick = onToggle)
        RoundControl(
            icon = Lu.SkipForward,
            description = stringResource(R.string.music_next),
            selected = false,
            enabled = canSkip,
            onClick = onNext,
        )
        RoundControl(
            icon = Lu.RefreshCw,
            description = repeatLabel,
            selected = state.repeat != RepeatMode.Off,
            badge = if (state.repeat == RepeatMode.One) "1" else null,
            onClick = onRepeat,
        )
    }
}

@Composable
private fun PlayButton(playing: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val palette = LdColors.colors
    val interaction = remember { MutableInteractionSource() }
    var armed by remember { mutableStateOf(!Motion.enabled) }
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
        animationSpec = Motion.spring<Float>(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "playPop",
    )
    Box(
        Modifier
            .pressScale(interaction)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .size(72.dp)
            .alpha(if (enabled) 1f else 0.42f)
            .clip(CircleShape)
            .background(palette.pill)
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
            if (playing) Lu.Pause else Lu.Play,
            contentDescription = stringResource(if (playing) R.string.music_pause else R.string.music_play),
            tint = palette.onPill,
            size = 28.dp,
        )
    }
}

@Composable
private fun RoundControl(
    icon: ImageVector,
    description: String,
    selected: Boolean,
    onClick: () -> Unit,
    enabled: Boolean = true,
    badge: String? = null,
) {
    val palette = LdColors.colors
    val interaction = remember(description) { MutableInteractionSource() }
    Box(
        Modifier
            .pressScale(interaction)
            .size(48.dp)
            .alpha(if (enabled) 1f else 0.42f)
            .ldGlass(CircleShape, strong = true)
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
            tint = if (selected) palette.textPrimary else palette.ink,
            selected = selected,
        )
        if (badge != null) {
            Text(
                badge,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(10.dp),
                style = MaterialTheme.typography.labelSmall,
                color = palette.textPrimary,
            )
        }
    }
}

@Composable
private fun SearchField(value: String, onValue: (String) -> Unit) {
    val palette = LdColors.colors
    val style = LdType.Body.copy(color = palette.ink)
    BasicTextField(
        value = value,
        onValueChange = { onValue(it.take(80)) },
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .ldGlass(RoundedCornerShape(LdRadius.tile), strong = true)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        textStyle = style,
        singleLine = true,
        cursorBrush = SolidColor(LdColors.primary),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        decorationBox = { inner ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                LuIcon(Lu.Search, contentDescription = null, tint = palette.inkMuted, size = 20.dp)
                Spacer(Modifier.width(8.dp))
                Box(Modifier.weight(1f)) {
                    if (value.isEmpty()) {
                        Text(
                            stringResource(R.string.music_search),
                            style = LdType.Body,
                            color = palette.inkMuted,
                            maxLines = 1,
                        )
                    }
                    inner()
                }
            }
        },
    )
}

@Composable
private fun SourceChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val palette = LdColors.colors
    val interaction = remember(label) { MutableInteractionSource() }
    val shape = RoundedCornerShape(LdRadius.chip)
    Box(
        Modifier
            .pressScale(interaction)
            .heightIn(min = 48.dp)
            .clip(shape)
            .background(if (selected) palette.pill else palette.glass)
            .border(1.dp, if (selected) palette.pillBorder else palette.glassBorder, shape)
            .clickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = LdType.Caption,
            color = if (selected) palette.onPill else palette.ink,
            maxLines = 1,
        )
    }
}

@Composable
private fun SongLine(
    track: Track,
    index: Int,
    animate: Boolean,
    current: Boolean,
    showAdd: Boolean,
    onPlay: () -> Unit,
    onAdd: () -> Unit,
) {
    val row = if (animate && index < 8) Modifier.appear(index) else Modifier
    TrackRow(track, current, showAdd, onPlay, onAdd, row)
}

@Composable
private fun TrackRow(
    track: Track,
    current: Boolean,
    showAdd: Boolean,
    onPlay: () -> Unit,
    onAdd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LdColors.colors
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                track.title.ifBlank { stringResource(R.string.music_nothing_playing) },
                style = LdType.Body,
                color = if (current) palette.textPrimary else palette.ink,
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
        ActionText(stringResource(R.string.music_play), onPlay)
        if (showAdd) {
            ActionText(stringResource(R.string.music_add), onAdd)
        }
    }
}

@Composable
private fun ActionText(label: String, onClick: () -> Unit) {
    val interaction = remember(label) { MutableInteractionSource() }
    Box(
        Modifier
            .pressScale(interaction)
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(LdRadius.tile))
            .clickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = LdType.Caption, color = LdColors.colors.textPrimary, maxLines = 1)
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, style = LdType.Caption, color = LdColors.colors.inkMuted)
}

@Composable
private fun Banner(text: String) {
    val palette = LdColors.colors
    Row(
        Modifier
            .fillMaxWidth()
            .ldGlass(RoundedCornerShape(LdRadius.tile), strong = true)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        LuIcon(Lu.Info, contentDescription = null, tint = palette.textPrimary, size = 20.dp)
        Text(text, style = LdType.Caption, color = palette.ink)
    }
}

@Composable
private fun EmptyHint(icon: ImageVector, text: String) {
    val palette = LdColors.colors
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(
            Modifier
                .size(96.dp)
                .ldGlass(CircleShape, strong = true),
            contentAlignment = Alignment.Center,
        ) {
            LuIcon(icon, contentDescription = null, tint = palette.ink, size = 32.dp)
        }
        Text(text, style = LdType.Body, color = palette.inkMuted, textAlign = TextAlign.Center)
    }
}

@Composable
private fun PermissionCard(
    permanent: Boolean,
    onAllow: () -> Unit,
    onSettings: () -> Unit,
) {
    val palette = LdColors.colors
    Column(
        Modifier
            .fillMaxWidth()
            .ldGlass(RoundedCornerShape(LdRadius.card), strong = true)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier
                .size(48.dp)
                .clip(concentricShape(LdRadius.card, 16.dp))
                .background(LdColors.primary.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            LuIcon(Lu.Headphones, contentDescription = null, tint = palette.ink)
        }
        Text(stringResource(R.string.music_perm_title), style = LdType.Title, color = palette.ink)
        Text(
            stringResource(if (permanent) R.string.music_perm_denied else R.string.music_perm_body),
            style = LdType.Caption,
            color = palette.inkMuted,
        )
        SecondaryPill(
            text = stringResource(if (permanent) R.string.perm_settings else R.string.perm_allow),
            onClick = if (permanent) onSettings else onAllow,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun bannerText(banner: MusicBanner): String? = when (banner) {
    MusicBanner.None -> null
    MusicBanner.Timeout -> stringResource(R.string.music_timeout)
    MusicBanner.PartnerLeft -> stringResource(R.string.music_partner_left)
    MusicBanner.TooBig -> stringResource(R.string.music_too_big)
    MusicBanner.SendFailed -> stringResource(R.string.music_send_failed)
    MusicBanner.DownloadFailed -> stringResource(R.string.music_download_failed)
    MusicBanner.PlayFailed -> stringResource(R.string.music_play_failed)
    MusicBanner.NotPaired -> stringResource(R.string.music_not_paired)
}

private fun formatTrackClock(ms: Long): String {
    val total = (ms.coerceAtLeast(0L) / 1000L).toInt()
    val seconds = total % 60
    val minutes = total / 60
    return if (minutes >= 60) {
        val hours = minutes / 60
        val remain = minutes % 60
        "$hours:${remain.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"
    } else {
        "$minutes:${seconds.toString().padStart(2, '0')}"
    }
}

private fun artworkBrush(seed: String): Brush {
    var hash = 0
    for (ch in seed) hash = hash * 31 + ch.code
    val hueA = (hash and 0xFFFF).mod(360).toFloat()
    val hueB = (hueA + 42f).mod(360f)
    return Brush.linearGradient(
        listOf(
            Color.hsl(hueA, 0.45f, 0.74f),
            Color.hsl(hueB, 0.55f, 0.46f),
        ),
    )
}

private fun openSettings(context: Context, launch: (Intent) -> Unit) {
    val intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", context.packageName, null),
    )
    try {
        launch(intent)
    } catch (_: Exception) {
        // This device has no app-details screen. The message stays on screen.
    }
}

private fun Context.findActivity(): Activity? {
    var current: Context = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}
