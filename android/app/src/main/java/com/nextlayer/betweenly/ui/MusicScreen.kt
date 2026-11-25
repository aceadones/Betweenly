package com.nextlayer.betweenly.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.nextlayer.betweenly.R
import com.nextlayer.betweenly.Store
import com.nextlayer.betweenly.music.LocalSource
import com.nextlayer.betweenly.music.MusicBridge
import com.nextlayer.betweenly.music.MusicHit
import com.nextlayer.betweenly.music.MusicSession
import com.nextlayer.betweenly.music.Together
import com.nextlayer.betweenly.music.Track
import com.nextlayer.betweenly.ui.music.MiniBar
import com.nextlayer.betweenly.ui.music.MusicBrowse
import com.nextlayer.betweenly.ui.music.MusicHome
import com.nextlayer.betweenly.ui.music.MusicNow
import com.nextlayer.betweenly.ui.music.MusicOverlays

/**
 * Music. The session, player, and catalog exist only while this screen is
 * composed, unless the user turned on keep-playing. Solo playback does not
 * use the network. Listen together is a mode of the player.
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
    var place by rememberSaveable { mutableStateOf("home") }
    var arg by rememberSaveable { mutableStateOf("") }
    var argTitle by rememberSaveable { mutableStateOf("") }
    var playlistArg by rememberSaveable { mutableStateOf("") }
    var pickId by rememberSaveable { mutableStateOf("") }
    var sheet by remember { mutableStateOf<String?>(null) }
    var draft by rememberSaveable { mutableStateOf("") }
    var menu by remember { mutableStateOf<Track?>(null) }
    var shown by rememberSaveable { mutableStateOf(false) }
    var asked by rememberSaveable { mutableStateOf(false) }

    val permission = LocalSource.permission()
    val grant = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        asked = true
        controller.refreshPermission()
    }
    val settings = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        controller.refreshPermission()
    }
    val needsGrant = !state.audioGranted && !LocalSource.permitted(context)
    val permanent = asked && needsGrant && host != null &&
        !ActivityCompat.shouldShowRequestPermissionRationale(host, permission)

    val progress by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = Motion.spring<Float>(),
        label = "now",
    )
    val playerUp = shown || progress > 0.02f

    fun openPlayer() {
        shown = true
    }

    fun allowAudio() {
        if (permanent) {
            val intent = Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", context.packageName, null),
            )
            settings.launch(intent)
        } else {
            grant.launch(permission)
        }
    }

    BackHandler(enabled = sheet != null || shown || place != "home") {
        when {
            sheet != null -> sheet = null
            shown -> shown = false
            pickId.isNotEmpty() && place == "songs" -> {
                place = "playlist"
                arg = playlistArg
                pickId = ""
            }
            place == "album" -> {
                place = "albums"
                arg = ""
            }
            else -> {
                place = "home"
                arg = ""
                pickId = ""
            }
        }
    }

    LaunchedEffect(place, arg) {
        when (place) {
            "home" -> {
                controller.searchAll("")
                controller.reloadHome()
            }
            "songs" -> controller.setQuery("")
            "albums" -> controller.loadAlbums()
            "album" -> if (arg.isNotEmpty()) controller.loadAlbum(arg)
            "liked" -> controller.loadLiked()
            "offline" -> controller.loadOffline()
            "playlist" -> if (arg.isNotEmpty()) controller.loadPlaylist(arg)
            else -> Unit
        }
    }

    val songsTitle = stringResource(R.string.music_songs)
    val title = when (place) {
        "songs" -> songsTitle
        "albums" -> stringResource(R.string.music_albums)
        "album" -> argTitle.ifBlank { stringResource(R.string.music_unknown_album) }
        "liked" -> stringResource(R.string.music_liked)
        "offline" -> stringResource(R.string.music_offline)
        "playlist" -> state.playlistTitle.ifBlank { argTitle }.ifBlank { stringResource(R.string.music_playlists) }
        "queue" -> stringResource(R.string.music_queue)
        else -> stringResource(R.string.music_screen)
    }

    Scaffold(
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets(0.dp, 0.dp, 0.dp, 0.dp),
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            Box(
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing),
            ) {
            if (place == "home") {
                MusicHome(
                    state = state,
                    partner = partner,
                    onClose = onClose,
                    onSearch = controller::searchAll,
                    onOpenSongs = { place = "songs"; pickId = "" },
                    onOpenAlbums = { place = "albums" },
                    onOpenLiked = { place = "liked" },
                    onOpenQueue = { place = "queue" },
                    onOpenOffline = { place = "offline" },
                    onOpenPlaylist = { id ->
                        val row = state.playlists.firstOrNull { it.id == id }
                        arg = id
                        playlistArg = id
                        argTitle = row?.name.orEmpty()
                        pickId = ""
                        place = "playlist"
                    },
                    onCreate = { draft = ""; sheet = "create" },
                    onSources = { sheet = "sources" },
                    onSettings = { sheet = "settings" },
                    onHit = { hit ->
                        when (hit) {
                            is MusicHit.Song -> {
                                controller.play(hit.track)
                                openPlayer()
                            }
                            is MusicHit.AlbumHit -> {
                                arg = hit.album.id
                                argTitle = hit.album.name
                                place = "album"
                            }
                            is MusicHit.PlaylistHit -> {
                                arg = hit.row.id
                                playlistArg = hit.row.id
                                argTitle = hit.row.name
                                place = "playlist"
                            }
                        }
                    },
                    onAccept = controller::accept,
                    onDecline = controller::leave,
                    onAllow = { allowAudio() },
                    audioBlocked = needsGrant,
                )
            } else {
                MusicBrowse(
                    place = place,
                    title = title,
                    state = state,
                    picking = pickId.isNotEmpty() && place == "songs",
                    showPermission = needsGrant,
                    permanent = permanent,
                    onBack = {
                        when {
                            pickId.isNotEmpty() && place == "songs" -> {
                                place = "playlist"
                                arg = playlistArg
                                pickId = ""
                            }
                            place == "album" -> {
                                place = "albums"
                                arg = ""
                            }
                            else -> {
                                place = "home"
                                arg = ""
                                pickId = ""
                            }
                        }
                    },
                    onSearch = controller::setQuery,
                    onPlay = { track, list ->
                        if (pickId.isNotEmpty() && place == "songs") {
                            controller.addToPlaylist(pickId, track)
                            arg = pickId
                            place = "playlist"
                            pickId = ""
                        } else if (place == "queue") {
                            controller.play(track)
                            openPlayer()
                        } else {
                            controller.playFrom(list, track)
                            openPlayer()
                        }
                    },
                    onMenu = { track ->
                        menu = track
                        sheet = "song"
                    },
                    onAlbum = { album ->
                        arg = album.id
                        argTitle = album.name
                        place = "album"
                    },
                    onAllow = { allowAudio() },
                    onSettings = { allowAudio() },
                    onMove = { index, delta ->
                        if (place == "queue") controller.moveQueued(index, delta)
                        else controller.moveInPlaylist(arg, index, delta)
                    },
                    onRemove = { index ->
                        if (place == "queue") {
                            controller.removeQueued(index)
                        } else {
                            val track = state.results.getOrNull(index)
                            if (track != null) controller.removeFromPlaylist(arg, track)
                        }
                    },
                    onClear = controller::clearQueue,
                    onClearDownloads = { sheet = "clear" },
                    onShare = { on -> controller.setPlaylistShared(arg, on) },
                    onRename = {
                        draft = state.playlistTitle.ifBlank { argTitle }
                        sheet = "rename"
                    },
                    onDelete = { sheet = "delete" },
                    onAddSongs = {
                        pickId = arg
                        playlistArg = arg
                        place = "songs"
                    },
                )
            }
            if (state.track != null && progress < 0.98f) {
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .graphicsLayer { alpha = 1f - progress },
                ) {
                    MiniBar(state, onOpen = { openPlayer() }, onToggle = controller::toggle)
                }
            }
            }
            if (playerUp) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            val scale = 0.92f + 0.08f * progress
                            scaleX = scale
                            scaleY = scale
                            alpha = progress
                            translationY = (1f - progress) * 48.dp.toPx()
                            transformOrigin = TransformOrigin(0.5f, 1f)
                        },
                ) {
                    MusicNow(
                        state = state,
                        onCollapse = { shown = false },
                        onToggle = controller::toggle,
                        onPrev = controller::previous,
                        onNext = controller::next,
                        onShuffle = controller::toggleShuffle,
                        onRepeat = controller::cycleRepeat,
                        onLike = { controller.toggleLike(state.track) },
                        onPreview = { fraction ->
                            val dur = state.durationMs
                            if (dur > 0L) controller.previewSeek((fraction * dur).toLong())
                        },
                        onCommit = controller::commitSeek,
                        onMenu = { sheet = "player" },
                        onTogether = {
                            if (state.together == Together.Solo) controller.invite() else controller.leave()
                        },
                        onAccept = controller::accept,
                        onDecline = controller::leave,
                        onRemove = controller::removeQueued,
                        onPlay = { track -> controller.play(track) },
                    )
                }
            }
            MusicOverlays(
                sheet = sheet,
                state = state,
                draft = draft,
                menu = menu,
                onDraft = { draft = it },
                onDismiss = { sheet = null },
                onSelectSource = { id ->
                    controller.selectSource(id)
                    sheet = null
                },
                onKeep = controller::setKeepBackground,
                onCap = controller::setOfflineCapMb,
                onClearAsk = { sheet = "clear" },
                onClearOffline = {
                    controller.clearOffline()
                    sheet = null
                },
                onSleep = controller::setSleep,
                onOpenSources = { sheet = "sources" },
                onOpenSettings = { sheet = "settings" },
                onPlay = { track ->
                    controller.play(track)
                    sheet = null
                    openPlayer()
                },
                onPlayNext = { track ->
                    controller.playNext(track)
                    sheet = null
                },
                onEnqueue = { track ->
                    controller.enqueue(track)
                    sheet = null
                },
                onLike = { track ->
                    controller.toggleLike(track)
                    sheet = null
                },
                onDownload = { track ->
                    controller.downloadOffline(track)
                    sheet = null
                },
                onAskAdd = { sheet = "add" },
                onAdd = { id ->
                    val track = menu
                    if (track != null) controller.addToPlaylist(id, track)
                    sheet = null
                },
                onCreate = {
                    val row = controller.createPlaylist(draft)
                    sheet = null
                    draft = ""
                    if (row != null) {
                        arg = row.id
                        playlistArg = row.id
                        argTitle = row.name
                        pickId = ""
                        place = "playlist"
                    }
                },
                onRename = {
                    val id = playlistArg.ifBlank { arg }
                    controller.renamePlaylist(id, draft)
                    argTitle = draft.trim()
                    sheet = null
                },
                onDelete = {
                    val id = playlistArg.ifBlank { arg }
                    controller.deletePlaylist(id)
                    sheet = null
                    arg = ""
                    playlistArg = ""
                    argTitle = ""
                    place = "home"
                },
            )
        }
    }
}

private fun Context.findActivity(): Activity? {
    var current = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}
