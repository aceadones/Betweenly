package com.nextlayer.betweenly.ui.music

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nextlayer.betweenly.R
import com.nextlayer.betweenly.music.LocalSource
import com.nextlayer.betweenly.music.MusicUiState
import com.nextlayer.betweenly.music.Track
import com.nextlayer.betweenly.ui.LdRadius
import com.nextlayer.betweenly.ui.LdType
import com.nextlayer.betweenly.ui.Motion

private val sheetInk = Color(0xFF1A0A12)
private val sheetPaper = Color(0xFFFFF7F9)

@Composable
internal fun MusicOverlays(
    sheet: String?,
    state: MusicUiState,
    draft: String,
    menu: Track?,
    onDraft: (String) -> Unit,
    onDismiss: () -> Unit,
    onSelectSource: (String) -> Unit,
    onKeep: (Boolean) -> Unit,
    onCap: (Int) -> Unit,
    onClearAsk: () -> Unit,
    onClearOffline: () -> Unit,
    onSleep: (Int) -> Unit,
    onOpenSources: () -> Unit,
    onOpenSettings: () -> Unit,
    onPlay: (Track) -> Unit,
    onPlayNext: (Track) -> Unit,
    onEnqueue: (Track) -> Unit,
    onLike: (Track) -> Unit,
    onDownload: (Track) -> Unit,
    onAskAdd: () -> Unit,
    onAdd: (String) -> Unit,
    onCreate: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    when (sheet) {
        "create" -> NameDialog(
            title = stringResource(R.string.music_new_playlist),
            draft = draft,
            confirm = stringResource(R.string.music_create),
            onDraft = onDraft,
            onDismiss = onDismiss,
            onConfirm = onCreate,
        )
        "rename" -> NameDialog(
            title = stringResource(R.string.music_rename),
            draft = draft,
            confirm = stringResource(R.string.music_rename),
            onDraft = onDraft,
            onDismiss = onDismiss,
            onConfirm = onRename,
        )
        "delete" -> ConfirmDialog(
            title = stringResource(R.string.music_delete_playlist),
            body = stringResource(R.string.music_delete_confirm) +
                if (state.playlistShared) "\n" + stringResource(R.string.music_delete_shared) else "",
            confirm = stringResource(R.string.music_delete_playlist),
            onDismiss = onDismiss,
            onConfirm = onDelete,
        )
        "clear" -> ConfirmDialog(
            title = stringResource(R.string.music_clear_offline),
            body = stringResource(R.string.music_clear_offline_help),
            confirm = stringResource(R.string.music_clear_offline),
            onDismiss = onDismiss,
            onConfirm = onClearOffline,
        )
        null -> Unit
        else -> SheetPanel(onDismiss) {
            when (sheet) {
                "sources" -> SourcesBody(state, onSelectSource)
                "settings" -> SettingsBody(state, onKeep, onCap, onClearAsk)
                "player" -> PlayerBody(state, onSleep, onKeep, onOpenSources, onOpenSettings)
                "song" -> SongBody(menu, state, onPlay, onPlayNext, onEnqueue, onLike, onDownload, onAskAdd)
                "add" -> AddBody(state, onAdd)
            }
        }
    }
}

@Composable
private fun SheetPanel(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    var arrive by remember { mutableFloatStateOf(if (Motion.enabled) 0f else 1f) }
    LaunchedEffect(Unit) { arrive = 1f }
    val progress by animateFloatAsState(
        targetValue = arrive,
        animationSpec = Motion.tween(durationMs = Motion.MED, easing = Motion.easeOut),
        label = "sheet",
    )
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = 0.4f * progress }
                .background(Color.Black)
                .clickable(role = Role.Button, onClick = onDismiss),
        )
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .graphicsLayer {
                    alpha = progress
                    translationY = (1f - progress) * 24.dp.toPx()
                }
                .clip(RoundedCornerShape(topStart = LdRadius.card, topEnd = LdRadius.card))
                .background(sheetPaper)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                )
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 16.dp)
                .heightIn(max = 520.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) { content() }
    }
}

@Composable
private fun SourcesBody(state: MusicUiState, onSelect: (String) -> Unit) {
    Text(stringResource(R.string.music_sources), style = LdType.Title, color = sheetInk)
    val options = state.sources
    if (options.isEmpty()) {
        SheetLine(stringResource(R.string.music_source_local), stringResource(R.string.music_source_on), null)
    }
    options.forEach { option ->
        val label = option.label.ifBlank {
            if (option.id == LocalSource.ID) stringResource(R.string.music_source_local) else option.id
        }
        val on = option.id == state.sourceId
        SheetLine(label, if (on) stringResource(R.string.music_source_on) else "", { onSelect(option.id) })
    }
    if (options.none { it.id != LocalSource.ID }) {
        SheetLine(stringResource(R.string.music_source_online), stringResource(R.string.music_source_missing), null)
        Text(stringResource(R.string.music_sources_help), style = LdType.Caption, color = sheetInk.copy(alpha = 0.7f))
    }
}

@Composable
private fun SettingsBody(
    state: MusicUiState,
    onKeep: (Boolean) -> Unit,
    onCap: (Int) -> Unit,
    onClearAsk: () -> Unit,
) {
    Text(stringResource(R.string.music_settings), style = LdType.Title, color = sheetInk)
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(R.string.music_keep_bg), style = LdType.Body, color = sheetInk)
            Text(stringResource(R.string.music_keep_bg_help), style = LdType.Caption, color = sheetInk.copy(alpha = 0.7f))
        }
        Switch(
            checked = state.keepBackground,
            onCheckedChange = onKeep,
            colors = SwitchDefaults.colors(checkedTrackColor = MusicPaint.accent, checkedThumbColor = Color.White),
        )
    }
    Text(stringResource(R.string.music_storage), style = LdType.Body, color = sheetInk, modifier = Modifier.padding(top = 8.dp))
    Text(
        stringResource(R.string.music_storage_used, formatBytes(state.offlineBytes), formatBytes(state.offlineCap)),
        style = LdType.Caption,
        color = sheetInk.copy(alpha = 0.7f),
    )
    val current = (state.offlineCap / (1024L * 1024L)).toInt()
    FlowRow(
        Modifier.fillMaxWidth().padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        listOf(100, 250, 500, 1000, 2000).forEach { mb ->
            val on = mb == current
            Text(
                stringResource(R.string.music_cap, mb),
                modifier = Modifier
                    .clip(RoundedCornerShape(LdRadius.chip))
                    .background(if (on) MusicPaint.accent else MusicPaint.tint)
                    .clickable(role = Role.Button) { onCap(mb) }
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                style = LdType.Caption,
                color = if (on) MusicPaint.white else sheetInk,
            )
        }
    }
    RoseButton(stringResource(R.string.music_clear_offline), onClearAsk, Modifier.fillMaxWidth().padding(top = 12.dp), filled = false)
}

@Composable
private fun PlayerBody(
    state: MusicUiState,
    onSleep: (Int) -> Unit,
    onKeep: (Boolean) -> Unit,
    onSources: () -> Unit,
    onSettings: () -> Unit,
) {
    Text(stringResource(R.string.music_menu), style = LdType.Title, color = sheetInk)
    Text(stringResource(R.string.music_sleep), style = LdType.Body, color = sheetInk, modifier = Modifier.padding(top = 8.dp))
    FlowRow(
        Modifier.fillMaxWidth().padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SleepChip(stringResource(R.string.music_sleep_off), state.sleepMinutes <= 0) { onSleep(0) }
        listOf(15, 30, 45, 60).forEach { minutes ->
            SleepChip(stringResource(R.string.music_sleep_min, minutes), state.sleepMinutes == minutes) { onSleep(minutes) }
        }
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.music_keep_bg), style = LdType.Body, color = sheetInk, modifier = Modifier.weight(1f))
        Switch(
            checked = state.keepBackground,
            onCheckedChange = onKeep,
            colors = SwitchDefaults.colors(checkedTrackColor = MusicPaint.accent, checkedThumbColor = Color.White),
        )
    }
    SheetLine(stringResource(R.string.music_sources), "", onSources)
    SheetLine(stringResource(R.string.music_settings), "", onSettings)
}

@Composable
private fun SleepChip(label: String, on: Boolean, onClick: () -> Unit) {
    Text(
        label,
        modifier = Modifier
            .clip(RoundedCornerShape(LdRadius.chip))
            .background(if (on) MusicPaint.accent else MusicPaint.tint)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        style = LdType.Caption,
        color = if (on) MusicPaint.white else sheetInk,
        maxLines = 1,
    )
}

@Composable
private fun SongBody(
    track: Track?,
    state: MusicUiState,
    onPlay: (Track) -> Unit,
    onPlayNext: (Track) -> Unit,
    onEnqueue: (Track) -> Unit,
    onLike: (Track) -> Unit,
    onDownload: (Track) -> Unit,
    onAddList: () -> Unit,
) {
    val item = track ?: return
    val liked = item.key() in state.likedKeys
    Text(
        item.title.ifBlank { stringResource(R.string.music_nothing_playing) },
        style = LdType.Title,
        color = sheetInk,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
    SheetLine(stringResource(R.string.music_play), "", { onPlay(item) })
    SheetLine(stringResource(R.string.music_play_next), "", { onPlayNext(item) })
    SheetLine(stringResource(R.string.music_add), "", { onEnqueue(item) })
    SheetLine(stringResource(R.string.music_add_playlist), "", onAddList)
    SheetLine(stringResource(if (liked) R.string.music_unlike else R.string.music_like), "", { onLike(item) })
    if (item.sourceId == LocalSource.ID) {
        Text(stringResource(R.string.music_local_offline), style = LdType.Caption, color = sheetInk.copy(alpha = 0.7f))
    } else if (item.key() in state.offlineKeys) {
        Text(stringResource(R.string.music_downloaded), style = LdType.Caption, color = sheetInk.copy(alpha = 0.7f))
    } else {
        SheetLine(stringResource(R.string.music_download), "", { onDownload(item) })
    }
}

@Composable
private fun AddBody(state: MusicUiState, onAdd: (String) -> Unit) {
    Text(stringResource(R.string.music_add_playlist), style = LdType.Title, color = sheetInk)
    if (state.playlists.isEmpty()) {
        Text(stringResource(R.string.music_empty_playlists), style = LdType.Body, color = sheetInk.copy(alpha = 0.7f))
    }
    state.playlists.forEach { row ->
        SheetLine(row.name, if (row.shared) stringResource(R.string.music_shared) else "", { onAdd(row.id) })
    }
}

@Composable
private fun SheetLine(title: String, subtitle: String, onClick: (() -> Unit)?) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = LdType.Body, color = sheetInk, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (subtitle.isNotEmpty()) {
            Text(subtitle, style = LdType.Caption, color = MusicPaint.accent, maxLines = 1)
        }
    }
}

@Composable
private fun NameDialog(
    title: String,
    draft: String,
    confirm: String,
    onDraft: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = sheetPaper,
        title = { Text(title, color = sheetInk) },
        text = {
            BasicTextField(
                value = draft,
                onValueChange = { onDraft(it.take(80)) },
                textStyle = LdType.Body.copy(color = sheetInk),
                singleLine = true,
                cursorBrush = SolidColor(MusicPaint.accent),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(LdRadius.tile))
                    .background(MusicPaint.tint)
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                decorationBox = { inner ->
                    Box {
                        if (draft.isEmpty()) {
                            Text(stringResource(R.string.music_name_hint), style = LdType.Body, color = sheetInk.copy(alpha = 0.45f))
                        }
                        inner()
                    }
                },
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = draft.trim().isNotEmpty()) {
                Text(confirm, color = MusicPaint.accent)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.music_cancel), color = sheetInk) }
        },
    )
}

@Composable
private fun ConfirmDialog(
    title: String,
    body: String,
    confirm: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = sheetPaper,
        title = { Text(title, color = sheetInk) },
        text = { Text(body, color = sheetInk) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(confirm, color = MusicPaint.accent) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.music_cancel), color = sheetInk) }
        },
    )
}
