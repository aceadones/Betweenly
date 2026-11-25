package com.nextlayer.betweenly.ui.music

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nextlayer.betweenly.R
import com.nextlayer.betweenly.music.AlbumRow
import com.nextlayer.betweenly.music.MusicUiState
import com.nextlayer.betweenly.music.Track
import com.nextlayer.betweenly.ui.GlassIconButton
import com.nextlayer.betweenly.ui.LdColors
import com.nextlayer.betweenly.ui.LdType
import com.nextlayer.betweenly.ui.SecondaryPill
import com.nextlayer.betweenly.ui.icons.Lu

@Composable
internal fun MusicBrowse(
    place: String,
    title: String,
    state: MusicUiState,
    picking: Boolean,
    showPermission: Boolean,
    permanent: Boolean,
    onBack: () -> Unit,
    onSearch: (String) -> Unit,
    onPlay: (Track, List<Track>) -> Unit,
    onMenu: (Track) -> Unit,
    onAlbum: (AlbumRow) -> Unit,
    onAllow: () -> Unit,
    onSettings: () -> Unit,
    onMove: (Int, Int) -> Unit,
    onRemove: (Int) -> Unit,
    onClear: () -> Unit,
    onClearDownloads: () -> Unit,
    onShare: (Boolean) -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onAddSongs: () -> Unit,
) {
    val palette = LdColors.colors
    val songs = if (place == "queue") state.queue else state.results
    val needsAudio = place == "songs" || place == "albums" || place == "album"
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 112.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                GlassIconButton(onClick = onBack, icon = Lu.ChevronLeft, description = stringResource(R.string.back))
                Text(
                    title,
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                    style = LdType.Title,
                    color = palette.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (place == "playlist") {
                    IconTap(Lu.Plus, stringResource(R.string.music_add_playlist), onAddSongs, MusicPaint.accent)
                }
                if (place == "queue" && state.queue.isNotEmpty()) {
                    IconTap(Lu.Trash2, stringResource(R.string.music_clear_queue), onClear, palette.inkMuted)
                }
                if (place == "offline" && state.results.isNotEmpty()) {
                    IconTap(Lu.Trash2, stringResource(R.string.music_clear_offline), onClearDownloads, palette.inkMuted)
                }
            }
        }
        if (place == "songs") {
            item {
                MusicSearch(state.query, onSearch, stringResource(R.string.music_search))
            }
        }
        if (picking) {
            item {
                Text(stringResource(R.string.music_pick_help), style = LdType.Caption, color = palette.inkMuted)
            }
        }
        if (place == "playlist") {
            item {
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(stringResource(R.string.music_share_playlist), style = LdType.Body, color = palette.ink)
                        Text(stringResource(R.string.music_share_help), style = LdType.Caption, color = palette.inkMuted)
                    }
                    Switch(
                        checked = state.playlistShared,
                        onCheckedChange = onShare,
                        colors = SwitchDefaults.colors(
                            checkedTrackColor = MusicPaint.accent,
                            checkedThumbColor = Color.White,
                        ),
                    )
                }
            }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SecondaryPill(stringResource(R.string.music_rename), onRename, Modifier.weight(1f))
                    SecondaryPill(stringResource(R.string.music_delete_playlist), onDelete, Modifier.weight(1f))
                }
            }
        }
        if (place == "offline") {
            item {
                Text(stringResource(R.string.music_local_offline), style = LdType.Caption, color = palette.inkMuted)
            }
        }
        if (needsAudio && showPermission) {
            item {
                PermissionNote(permanent, onAllow, onSettings)
            }
            return@LazyColumn
        }
        if (place == "albums") {
            if (!state.searching && state.albums.isEmpty()) {
                item { EmptyBlock(Lu.Disc3, stringResource(R.string.music_empty_albums)) }
            }
            itemsIndexed(state.albums, key = { _, album -> album.id }) { _, album ->
                SectionRow(
                    Lu.Disc3,
                    album.name.ifBlank { stringResource(R.string.music_unknown_album) },
                    album.artist.ifBlank { stringResource(R.string.music_unknown_artist) },
                    { onAlbum(album) },
                )
            }
            return@LazyColumn
        }
        if (!state.searching && songs.isEmpty()) {
            item {
                EmptyBlock(
                    if (place == "liked") Lu.Heart else if (place == "offline") Lu.Download else Lu.Music,
                    stringResource(
                        when {
                            place == "songs" && state.query.isNotBlank() -> R.string.music_empty_search
                            place == "liked" -> R.string.music_empty_liked
                            place == "offline" -> R.string.music_empty_offline
                            place == "playlist" -> R.string.music_empty_playlist
                            place == "queue" -> R.string.music_empty_queue
                            else -> R.string.music_empty_library
                        },
                    ),
                )
            }
        }
        itemsIndexed(songs, key = { index, track -> "$place:$index:${track.key()}" }) { index, track ->
            SongLine(
                track = track,
                current = state.track?.matches(track) == true,
                dark = false,
                onClick = { onPlay(track, songs) },
            ) {
                if (place == "queue" || place == "playlist") {
                    IconTap(Lu.ChevronUp, stringResource(R.string.music_up), { onMove(index, -1) }, palette.inkMuted, index > 0, box = 40.dp)
                    IconTap(Lu.ChevronDown, stringResource(R.string.music_down), { onMove(index, 1) }, palette.inkMuted, index < songs.lastIndex, box = 40.dp)
                    IconTap(Lu.X, stringResource(R.string.music_remove), { onRemove(index) }, palette.inkMuted, box = 40.dp)
                } else if (!picking) {
                    IconTap(Lu.Ellipsis, stringResource(R.string.music_menu), { onMenu(track) }, palette.inkMuted)
                }
            }
        }
    }
}

@Composable
private fun PermissionNote(permanent: Boolean, onAllow: () -> Unit, onSettings: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.music_perm_title), style = LdType.Title, color = LdColors.colors.ink)
        Text(
            stringResource(if (permanent) R.string.music_perm_denied else R.string.music_perm_body),
            style = LdType.Caption,
            color = LdColors.colors.inkMuted,
        )
        SecondaryPill(
            stringResource(if (permanent) R.string.perm_settings else R.string.perm_allow),
            if (permanent) onSettings else onAllow,
            Modifier.fillMaxWidth(),
        )
    }
}
