package com.nextlayer.betweenly.ui.music

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nextlayer.betweenly.R
import com.nextlayer.betweenly.music.MusicBanner
import com.nextlayer.betweenly.music.MusicHit
import com.nextlayer.betweenly.music.MusicUiState
import com.nextlayer.betweenly.music.Together
import com.nextlayer.betweenly.ui.GlassIconButton
import com.nextlayer.betweenly.ui.LdColors
import com.nextlayer.betweenly.ui.LdType
import com.nextlayer.betweenly.ui.icons.Lu

@Composable
internal fun MusicHome(
    state: MusicUiState,
    partner: String,
    onClose: () -> Unit,
    onSearch: (String) -> Unit,
    onOpenSongs: () -> Unit,
    onOpenAlbums: () -> Unit,
    onOpenLiked: () -> Unit,
    onOpenQueue: () -> Unit,
    onOpenOffline: () -> Unit,
    onOpenPlaylist: (String) -> Unit,
    onCreate: () -> Unit,
    onSources: () -> Unit,
    onSettings: () -> Unit,
    onHit: (MusicHit) -> Unit,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
    onAllow: () -> Unit,
    audioBlocked: Boolean,
) {
    val palette = LdColors.colors
    val liked = state.likedKeys.size
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 112.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                GlassIconButton(
                    onClick = onClose,
                    icon = Lu.ChevronLeft,
                    description = stringResource(R.string.back),
                )
                Text(
                    stringResource(R.string.music_screen),
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                    style = LdType.PageTitle,
                    color = palette.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                GlassIconButton(
                    onClick = onSources,
                    icon = Lu.Disc3,
                    description = stringResource(R.string.music_sources),
                )
                GlassIconButton(
                    onClick = onSettings,
                    icon = Lu.EllipsisVertical,
                    description = stringResource(R.string.music_menu),
                )
            }
        }
        item {
            MusicSearch(
                value = state.query,
                onValue = onSearch,
                hint = stringResource(R.string.music_search_all),
            )
        }
        if (state.together == Together.Invited) {
            item {
                InviteBlock(
                    title = state.inviteTrack?.title ?: state.track?.title.orEmpty(),
                    partner = partner,
                    onAccept = onAccept,
                    onDecline = onDecline,
                )
            }
        }
        if (state.banner != MusicBanner.None) {
            item {
                val banner = musicBanner(state.banner)
                if (banner != null) BannerLine(banner)
            }
        }
        if (state.query.isNotBlank()) {
            if (audioBlocked) {
                item {
                    SectionRow(Lu.Headphones, stringResource(R.string.music_perm_title), stringResource(R.string.music_perm_body), onAllow, 0)
                }
            }
            if (!state.searching && state.hits.isEmpty() && !audioBlocked) {
                item { EmptyBlock(Lu.Search, stringResource(R.string.music_empty_search)) }
            }
            items(state.hits, key = { hitKey(it) }) { hit ->
                when (hit) {
                    is MusicHit.Song -> SongLine(hit.track, current = state.track?.matches(hit.track) == true, dark = false, onClick = { onHit(hit) })
                    is MusicHit.AlbumHit -> SectionRow(
                        Lu.Disc3,
                        hit.album.name.ifBlank { stringResource(R.string.music_unknown_album) },
                        hit.album.artist,
                        { onHit(hit) },
                    )
                    is MusicHit.PlaylistHit -> SectionRow(Lu.ListMusic, hit.row.name, stringResource(R.string.music_playlists), { onHit(hit) })
                }
            }
            return@LazyColumn
        }
        item {
            Text(
                stringResource(R.string.music_library),
                style = LdType.Caption,
                color = palette.inkMuted,
                modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
            )
        }
        item {
            SectionRow(
                Lu.Music,
                stringResource(R.string.music_songs),
                pluralStringResource(R.plurals.music_song_count, state.songCount, state.songCount),
                onOpenSongs,
                0,
            )
        }
        item {
            SectionRow(Lu.Disc3, stringResource(R.string.music_albums), "", onOpenAlbums, 1)
        }
        item {
            SectionRow(
                Lu.Heart,
                stringResource(R.string.music_liked),
                pluralStringResource(R.plurals.music_song_count, liked, liked),
                onOpenLiked,
                2,
            )
        }
        item {
            Row(
                Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.music_playlists), style = LdType.Caption, color = palette.inkMuted, modifier = Modifier.weight(1f))
                IconTap(Lu.Plus, stringResource(R.string.music_new_playlist), onCreate, MusicPaint.accent)
            }
        }
        if (state.playlists.isEmpty()) {
            item { EmptyBlock(Lu.ListMusic, stringResource(R.string.music_empty_playlists)) }
        }
        items(state.playlists, key = { it.id }) { row ->
            SectionRow(
                if (row.shared) Lu.Users else Lu.ListMusic,
                row.name,
                if (row.shared) {
                    stringResource(R.string.music_shared_row)
                } else {
                    pluralStringResource(R.plurals.music_song_count, row.count, row.count)
                },
                { onOpenPlaylist(row.id) },
            )
        }
        item {
            Text(
                stringResource(R.string.music_queue),
                style = LdType.Caption,
                color = palette.inkMuted,
                modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
            )
        }
        item {
            SectionRow(
                Lu.ListMusic,
                stringResource(R.string.music_queue),
                pluralStringResource(R.plurals.music_song_count, state.queue.size, state.queue.size),
                onOpenQueue,
                3,
            )
        }
        item {
            Text(
                stringResource(R.string.music_offline),
                style = LdType.Caption,
                color = palette.inkMuted,
                modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
            )
        }
        item {
            SectionRow(
                Lu.Download,
                stringResource(R.string.music_offline),
                stringResource(R.string.music_storage_used, formatBytes(state.offlineBytes), formatBytes(state.offlineCap)),
                onOpenOffline,
                4,
            )
        }
    }
}

@Composable
private fun InviteBlock(title: String, partner: String, onAccept: () -> Unit, onDecline: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.music_invited), style = LdType.Title, color = LdColors.colors.ink)
        if (title.isNotBlank()) {
            Text(title, style = LdType.Body, color = LdColors.colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(partner, style = LdType.Caption, color = LdColors.colors.inkMuted, maxLines = 1)
        RoseButton(stringResource(R.string.music_accept), onAccept, Modifier.fillMaxWidth())
        RoseButton(stringResource(R.string.music_decline), onDecline, Modifier.fillMaxWidth(), filled = false)
    }
}

private fun hitKey(hit: MusicHit): String = when (hit) {
    is MusicHit.Song -> "s:" + hit.track.key()
    is MusicHit.AlbumHit -> "a:" + hit.album.id
    is MusicHit.PlaylistHit -> "p:" + hit.row.id
}

@Composable
internal fun musicBanner(banner: MusicBanner): String? = when (banner) {
    MusicBanner.None -> null
    MusicBanner.Timeout -> stringResource(R.string.music_timeout)
    MusicBanner.PartnerLeft -> stringResource(R.string.music_partner_left)
    MusicBanner.TooBig -> stringResource(R.string.music_too_big)
    MusicBanner.SendFailed -> stringResource(R.string.music_send_failed)
    MusicBanner.DownloadFailed -> stringResource(R.string.music_download_failed)
    MusicBanner.PlayFailed -> stringResource(R.string.music_play_failed)
    MusicBanner.NotPaired -> stringResource(R.string.music_not_paired)
    MusicBanner.OfflineFull -> stringResource(R.string.music_offline_full)
    MusicBanner.OfflineFailed -> stringResource(R.string.music_offline_fail)
}
