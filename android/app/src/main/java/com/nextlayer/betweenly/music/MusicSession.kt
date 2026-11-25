package com.nextlayer.betweenly.music

import android.content.Context
import android.net.Uri
import com.nextlayer.betweenly.AppScope
import com.nextlayer.betweenly.AppState
import com.nextlayer.betweenly.FileTransfer
import com.nextlayer.betweenly.Outbox
import com.nextlayer.betweenly.Store
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

private const val DRIFT_MS = 400L
private const val SYNC_EVERY_MS = 10_000L
private const val SEND_DEBOUNCE_MS = 160L
private const val APPLY_DEBOUNCE_MS = 80L
private const val INVITE_TIMEOUT_MS = 45_000L
private const val MAX_QUEUE = 20
private const val LOCAL_QUEUE = 100
private const val PAUSE_HOLD_MS = 10 * 60_000L

enum class Together { Solo, Inviting, Invited, Active }

enum class RepeatMode { Off, All, One }

enum class MusicTransfer { Idle, Sending, Downloading }

enum class MusicBanner {
    None,
    Timeout,
    PartnerLeft,
    TooBig,
    SendFailed,
    DownloadFailed,
    PlayFailed,
    NotPaired,
    OfflineFull,
    OfflineFailed,
}

data class SourceOption(val id: String, val label: String)

data class MusicUiState(
    val together: Together = Together.Solo,
    val track: Track? = null,
    val inviteTrack: Track? = null,
    val queue: List<Track> = emptyList(),
    val results: List<Track> = emptyList(),
    val query: String = "",
    val searching: Boolean = false,
    val playing: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val shuffle: Boolean = false,
    val repeat: RepeatMode = RepeatMode.Off,
    val transfer: MusicTransfer = MusicTransfer.Idle,
    val banner: MusicBanner = MusicBanner.None,
    val sourceId: String = LocalSource.ID,
    val sources: List<SourceOption> = emptyList(),
    val audioGranted: Boolean = false,
    val seeking: Boolean = false,
    val playlists: List<PlaylistRow> = emptyList(),
    val albums: List<AlbumRow> = emptyList(),
    val hits: List<MusicHit> = emptyList(),
    val songCount: Int = 0,
    val likedKeys: Set<String> = emptySet(),
    val offlineKeys: Set<String> = emptySet(),
    val offlineBytes: Long = 0L,
    val offlineCap: Long = 500L * 1024L * 1024L,
    val keepBackground: Boolean = false,
    val sleepMinutes: Int = 0,
    val browseKind: String = "",
    val playlistId: String = "",
    val playlistTitle: String = "",
    val playlistShared: Boolean = false,
)

/**
 * Playback and the listen-together session for one open music screen.
 * Constructed from that screen, not from process start. [close] releases the
 * player unless "Keep playing in background" is on. Solo playback does not
 * touch the network. fastPoll is set only while a listen-together session
 * is inviting, invited, or active.
 *
 * DATA-FLOW: see [post], [sendPlaylist], [uploadLocal], and [MusicCache.download].
 */
class MusicSession(private val app: Context) {
    private var rawState by mutableStateOf(MusicUiState())

    var state: MusicUiState
        get() = rawState
        private set(value) {
            val prev = rawState
            rawState = value
            val trackChanged = prev.track?.sourceId != value.track?.sourceId ||
                prev.track?.id != value.track?.id ||
                prev.track?.title != value.track?.title ||
                prev.track?.artist != value.track?.artist ||
                prev.playing != value.playing
            if (trackChanged) {
                MusicBridge.publish(
                    value.track?.let { MusicGlance(it.title, it.artist, value.playing) },
                )
            }
        }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val clock = PlayClock()
    private var player: MusicPlayer? = null
    private var catalog: List<MusicSource> = emptyList()
    private var opened = false
    private var visible = false
    private var together = Together.Solo
    private var loadedKey = ""
    private var playGen = 0
    private var commandGen = 0
    private var lastRemoteTs = 0L
    private var localGuardUntil = 0L
    private var inviteSentAt = 0L
    private var inviteRemoteTs = 0L
    private var queuedSeek = -1L
    private var hiddenAt = 0L
    private var hiddenPos = 0L
    private var stash: JSONObject? = null
    private var inboundLatest: JSONObject? = null
    private var ticker: Job? = null
    private var sendJob: Job? = null
    private var resolveJob: Job? = null
    private var syncJob: Job? = null
    private var inviteJob: Job? = null
    private var inboundJob: Job? = null
    private var searchJob: Job? = null
    @Volatile
    private var oweLeave = false
    private var skipShareKey = ""
    private var uiAttached = false
    private var heldOutput = false
    private var idleJob: Job? = null
    private var sleepJob: Job? = null

    private val playerListener = object : MusicPlayer.Listener {
        override fun onReady(durationMs: Int) {
            if (!opened) return
            val dur = if (durationMs > 0) durationMs.toLong() else state.track?.durationMs ?: 0L
            state = state.copy(durationMs = dur)
        }

        override fun onEnded() {
            if (opened) onTrackEnded()
        }

        override fun onError() {
            if (!opened) return
            loadedKey = ""
            state = state.copy(playing = false, banner = MusicBanner.PlayFailed, transfer = MusicTransfer.Idle)
            holdBackground()
        }

        override fun onFocusLost() = pauseFromSystem()

        override fun onNoisy() = pauseFromSystem()
    }

    internal fun isUsable(): Boolean = opened

    internal fun playingNow(): Boolean = state.playing

    fun open() {
        uiAttached = true
        idleJob?.cancel()
        if (opened) {
            MusicBridge.attach(this)
            MusicNotify.cancel(app)
            MusicPlayService.stop(app)
            reloadHome()
            return
        }
        opened = true
        MusicBridge.attach(this)
        MusicNotify.cancel(app)
        player = MusicPlayer(app, playerListener)
        loadCatalog()
        val saved = try {
            MusicLibrary.loadPlayback(app)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            SavedPlayback()
        }
        state = state.copy(
            audioGranted = LocalSource.permitted(app),
            queue = saved.queue,
            track = saved.track,
            positionMs = saved.positionMs,
            durationMs = saved.track?.durationMs ?: 0L,
            shuffle = saved.shuffle,
            repeat = saved.repeat,
            playing = false,
            keepBackground = MusicLibrary.keepBackground(app),
            offlineCap = MusicLibrary.offlineCap(app),
            offlineBytes = OfflineStore.used(app),
            likedKeys = MusicLibrary.likedKeys(app),
            offlineKeys = OfflineStore.keys(app),
        )
        val pending = MusicHold.peek(app)
        if (pending != null) onMessage(pending)
        reloadHome()
        syncJob = scope.launch {
            while (isActive) {
                delay(SYNC_EVERY_MS)
                if (!opened || together != Together.Active) continue
                if (player?.isPlaying() != true || sendJob?.isActive == true) continue
                try {
                    deliver("sync", commandGen)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // The next tick tries again. Sync is not shown as an error.
                }
            }
        }
    }

    fun close(configChange: Boolean) {
        if (configChange || !opened) return
        uiAttached = false
        visible = false
        ticker?.cancel()
        ticker = null
        persist()
        val keep = state.keepBackground && (state.playing || state.track != null)
        if (keep) {
            if (state.playing) {
                heldOutput = true
                if (!MusicPlayService.start(app)) {
                    heldOutput = false
                    player?.pauseUser()
                    state = state.copy(playing = false)
                    releaseFully()
                    return
                }
                idleJob?.cancel()
            } else {
                heldOutput = false
                player?.pauseOutput()
                if (MusicPlayService.start(app)) armIdle() else releaseFully()
            }
            return
        }
        releaseFully()
    }

    fun onShown() {
        if (!opened) return
        refreshPermission()
        val resume = !visible && state.playing
        val gap = if (resume && hiddenAt > 0L) System.currentTimeMillis() - hiddenAt else 0L
        val held = heldOutput
        visible = true
        uiAttached = true
        idleJob?.cancel()
        MusicPlayService.stop(app)
        if (resume) {
            if (!held && together == Together.Active && gap > 0L && player?.hasPrepared() == true) {
                val dur = player?.duration()?.takeIf { it > 0L } ?: state.durationMs
                val at = if (dur > 0L) (hiddenPos + gap).coerceIn(0L, dur) else hiddenPos + gap
                player?.seekTo(at)
                state = state.copy(positionMs = at)
            }
            if (!held) player?.resumeOutput()
        }
        heldOutput = false
        hiddenAt = 0L
        startTicker()
    }

    fun onHidden() {
        if (!opened || !visible) return
        visible = false
        hiddenAt = System.currentTimeMillis()
        hiddenPos = state.positionMs
        ticker?.cancel()
        ticker = null
        persist()
        if (state.keepBackground && (state.playing || state.track != null)) {
            if (state.playing) {
                heldOutput = true
                if (!MusicPlayService.start(app)) {
                    heldOutput = false
                    player?.pauseOutput()
                    state = state.copy(playing = false)
                }
            } else {
                heldOutput = false
                player?.pauseOutput()
                if (MusicPlayService.start(app)) armIdle()
            }
            return
        }
        heldOutput = false
        player?.pauseOutput()
    }

    fun refreshPermission() {
        if (!opened) return
        val granted = LocalSource.permitted(app)
        val gained = granted && !state.audioGranted
        state = state.copy(audioGranted = granted)
        if (gained && state.sourceId == LocalSource.ID) setQuery(state.query)
    }

    fun invite() {
        if (!opened) return
        if (!Store.isPaired(app)) {
            state = state.copy(banner = MusicBanner.NotPaired)
            return
        }
        if (together == Together.Active || together == Together.Inviting) return
        clearBanner()
        together = Together.Inviting
        AppState.fastPoll = true
        inviteSentAt = System.currentTimeMillis()
        state = state.copy(together = Together.Inviting)
        startInviteTimeout()
        enqueueSend("invite", immediate = true)
    }

    fun accept() {
        if (!opened || together != Together.Invited) return
        val echo = inviteRemoteTs
        val track = state.track
        val queue = state.queue
        val pending = stash
        stash = null
        together = Together.Active
        AppState.fastPoll = true
        MusicHold.clear(app)
        MusicNotify.cancel(app)
        state = state.copy(together = Together.Active, inviteTrack = null, banner = MusicBanner.None)
        scope.launch {
            val ok = post(
                MusicWire.build("accept", System.currentTimeMillis(), 0L, track, queue, echo),
                wake = false,
                bannerOnFail = true,
            )
            if (!ok || !opened) return@launch
            if (pending != null) {
                applyTransport(pending)
            } else if (track != null && TrackExtra.fid(track.extra) != null) {
                beginResolve(track, 0L, start = false, remote = true)
            }
        }
    }

    fun leave() {
        if (!opened || together == Together.Solo) return
        val json = MusicWire.build(
            "leave",
            System.currentTimeMillis(),
            player?.position() ?: state.positionMs,
            state.track,
            state.queue,
        )
        player?.pauseUser()
        state = state.copy(playing = false)
        oweLeave = true
        dropSession(showLeft = false)
        state = state.copy(banner = MusicBanner.None)
        AppScope.scope.launch {
            val ok = try {
                // DATA-FLOW: music leave. Padded and sealed by Outbox. The relay stores ciphertext only. wake is off.
                Outbox.send(app, json, false)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                false
            }
            if (ok) {
                oweLeave = false
            } else if (opened) {
                state = state.copy(banner = MusicBanner.SendFailed)
            }
        }
    }

    fun toggle() {
        if (!opened) return
        clearBanner()
        if (state.playing) {
            resolveJob?.cancel()
            playGen++
            player?.pauseUser()
            state = state.copy(
                playing = false,
                positionMs = player?.position() ?: state.positionMs,
                transfer = MusicTransfer.Idle,
            )
            if (together == Together.Active) enqueueSend("pause", immediate = false)
            persist()
            holdBackground()
            return
        }
        val track = state.track ?: state.queue.firstOrNull() ?: state.results.firstOrNull() ?: return
        if (loadedKey == keyOf(track) && player?.hasPrepared() == true) {
            player?.resume()
            state = state.copy(playing = true, track = track)
            if (together == Together.Active) enqueueSend("play", immediate = false)
            holdBackground()
        } else {
            val at = if (state.track?.matches(track) == true) state.positionMs else 0L
            play(track, at)
        }
    }

    fun next() = skip(forward = true, fromUser = true)

    fun previous() {
        if (!opened) return
        val pos = if (player?.hasPrepared() == true) player?.position() ?: 0L else state.positionMs
        if (pos > 3_000L && state.track != null) {
            player?.seekTo(0L)
            state = state.copy(positionMs = 0L)
            if (together == Together.Active) enqueueSend("seek", immediate = false)
            return
        }
        skip(forward = false, fromUser = true)
    }

    fun toggleShuffle() {
        if (!opened) return
        state = state.copy(shuffle = !state.shuffle)
        persist()
    }

    fun cycleRepeat() {
        if (!opened) return
        val next = when (state.repeat) {
            RepeatMode.Off -> RepeatMode.All
            RepeatMode.All -> RepeatMode.One
            RepeatMode.One -> RepeatMode.Off
        }
        state = state.copy(repeat = next)
        persist()
    }

    fun previewSeek(ms: Long) {
        if (!opened) return
        val dur = state.durationMs
        val clamped = if (dur > 0L) ms.coerceIn(0L, dur) else ms.coerceAtLeast(0L)
        state = state.copy(seeking = true, positionMs = clamped)
    }

    fun commitSeek() {
        if (!opened) return
        val pos = state.positionMs
        state = state.copy(seeking = false)
        if (player?.hasPrepared() == true) player?.seekTo(pos) else queuedSeek = pos
        if (together == Together.Active) enqueueSend("seek", immediate = false)
        persist()
    }

    fun play(track: Track, at: Long = 0L) {
        if (!opened) return
        clearBanner()
        val cap = queueCap()
        val queue = if (state.queue.any { it.matches(track) }) {
            state.queue
        } else {
            (listOf(track) + state.queue).take(cap)
        }
        val startAt = at.coerceAtLeast(0L)
        queuedSeek = -1L
        state = state.copy(
            queue = queue,
            track = track,
            playing = true,
            positionMs = startAt,
            durationMs = track.durationMs.coerceAtLeast(0L),
            seeking = false,
        )
        beginResolve(track, startAt, start = true, remote = false)
        if (together == Together.Active) enqueueSend("play", immediate = false)
        persist()
        holdBackground()
    }

    fun playFrom(list: List<Track>, track: Track) {
        if (!opened) return
        val start = list.indexOfFirst { it.matches(track) }.coerceAtLeast(0)
        val from = if (list.isEmpty()) listOf(track) else list.drop(start).ifEmpty { listOf(track) }
        state = state.copy(queue = from.take(queueCap()))
        play(track, 0L)
    }

    fun playNext(track: Track) {
        if (!opened) return
        val cap = queueCap()
        val queue = state.queue.filterNot { it.matches(track) }.toMutableList()
        if (queue.size >= cap) return
        val index = queue.indexOfFirst { it.matches(state.track) }
        val at = if (index >= 0) index + 1 else 0
        queue.add(at.coerceAtMost(queue.size), track)
        state = state.copy(queue = queue)
        if (together == Together.Active) enqueueSend("queue", immediate = false)
        persist()
    }

    fun removeQueued(index: Int) {
        if (!opened || index !in state.queue.indices) return
        val queue = state.queue.toMutableList()
        val removed = queue.removeAt(index)
        state = state.copy(queue = queue)
        if (state.track?.matches(removed) == true) {
            val next = queue.firstOrNull()
            if (next == null) {
                player?.pauseUser()
                state = state.copy(track = null, playing = false, positionMs = 0L)
                if (together == Together.Active) enqueueSend("pause", immediate = false)
                persist()
                holdBackground()
            } else {
                play(next)
            }
            return
        }
        if (together == Together.Active) enqueueSend("queue", immediate = false)
        persist()
    }

    fun moveQueued(index: Int, delta: Int) {
        if (!opened) return
        val to = index + delta
        if (index !in state.queue.indices || to !in state.queue.indices) return
        val queue = state.queue.toMutableList()
        val item = queue.removeAt(index)
        queue.add(to, item)
        state = state.copy(queue = queue)
        if (together == Together.Active) enqueueSend("queue", immediate = false)
        persist()
    }

    fun clearQueue() {
        if (!opened) return
        player?.pauseUser()
        loadedKey = ""
        state = state.copy(queue = emptyList(), track = null, playing = false, positionMs = 0L, transfer = MusicTransfer.Idle)
        if (together == Together.Active) enqueueSend("queue", immediate = false)
        persist()
        holdBackground()
    }

    fun enqueue(track: Track) {
        if (!opened) return
        val cap = queueCap()
        if (state.queue.any { it.matches(track) } || state.queue.size >= cap) return
        state = state.copy(queue = state.queue + track)
        if (together == Together.Active) enqueueSend("queue", immediate = false)
        persist()
    }

    fun setQuery(raw: String) {
        if (!opened) return
        val query = raw.take(80)
        state = state.copy(query = query)
        searchJob?.cancel()
        val source = catalog.firstOrNull { it.id == state.sourceId } ?: return
        if (source.id == LocalSource.ID && !LocalSource.permitted(app)) {
            state = state.copy(results = emptyList(), searching = false, audioGranted = false)
            return
        }
        state = state.copy(searching = true)
        searchJob = scope.launch {
            delay(250)
            val list = try {
                source.search(state.query)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                emptyList()
            }
            if (!opened) return@launch
            state = state.copy(results = list, searching = false, audioGranted = LocalSource.permitted(app))
        }
    }

    fun selectSource(id: String) {
        if (!opened || id == state.sourceId) return
        if (catalog.none { it.id == id }) return
        state = state.copy(sourceId = id, query = "", results = emptyList())
        setQuery("")
    }

    internal fun onMessage(json: JSONObject) {
        if (!opened) return
        try {
            if (json.optString("t") != "music") return
            when (json.optString("op")) {
                "invite" -> onInvite(json)
                "accept" -> onAccept(json)
                "leave" -> onLeave()
                "queue", "track", "play", "pause", "seek", "sync" -> stashOrApply(json)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // A bad field must not crash the screen.
        }
    }

    private fun onInvite(json: JSONObject) {
        val track = MusicWire.track(json.optJSONObject("track"))
        val queue = MusicWire.queue(json)
        val ts = json.optLong("ts")
        when (together) {
            Together.Active -> {
                MusicHold.clear(app)
                return
            }
            Together.Inviting -> {
                clock.noteIfNeeded(ts, System.currentTimeMillis())
                inviteJob?.cancel()
                MusicHold.clear(app)
                together = Together.Active
                AppState.fastPoll = true
                if (queue.isNotEmpty()) {
                    state = state.copy(queue = queue.map { mergeTrack(it) ?: it })
                }
                state = state.copy(together = Together.Active, banner = MusicBanner.None)
                scope.launch {
                    post(
                        MusicWire.build(
                            "accept",
                            System.currentTimeMillis(),
                            player?.position() ?: 0L,
                            state.track,
                            state.queue,
                            ts,
                        ),
                        wake = false,
                        bannerOnFail = true,
                    )
                }
                if (state.playing && state.track != null) enqueueSend("play", immediate = true)
            }
            else -> {
                resolveJob?.cancel()
                playGen++
                player?.pauseUser()
                together = Together.Invited
                AppState.fastPoll = true
                inviteRemoteTs = ts
                clock.noteIfNeeded(ts, System.currentTimeMillis())
                MusicHold.save(app, json)
                val shown = mergeTrack(track) ?: state.track
                state = state.copy(
                    together = Together.Invited,
                    playing = false,
                    inviteTrack = shown,
                    track = shown,
                    queue = if (queue.isNotEmpty()) queue.map { mergeTrack(it) ?: it } else state.queue,
                    banner = MusicBanner.None,
                    durationMs = shown?.durationMs?.coerceAtLeast(0L) ?: state.durationMs,
                )
            }
        }
    }

    private fun onAccept(json: JSONObject) {
        if (together != Together.Inviting && together != Together.Active) return
        val echo = json.optLong("echo")
        if (together == Together.Inviting && echo > 0L && inviteSentAt > 0L && abs(echo - inviteSentAt) > 120_000L) {
            return
        }
        val now = System.currentTimeMillis()
        clock.noteRtt(json.optLong("ts"), now, inviteSentAt)
        if (together == Together.Active) return
        inviteJob?.cancel()
        val pending = stash
        stash = null
        together = Together.Active
        AppState.fastPoll = true
        state = state.copy(together = Together.Active, banner = MusicBanner.None, inviteTrack = null)
        if (pending != null) {
            applyTransport(pending)
        } else if (state.track != null) {
            enqueueSend(if (state.playing) "play" else "pause", immediate = true)
        }
    }

    private fun onLeave() {
        inboundJob?.cancel()
        inboundLatest = null
        stash = null
        if (together == Together.Solo) return
        player?.pauseUser()
        state = state.copy(playing = false)
        dropSession(showLeft = true)
    }

    private fun stashOrApply(json: JSONObject) {
        if (together == Together.Invited || together == Together.Inviting) {
            val ts = json.optLong("ts")
            val prev = stash
            if (prev == null || ts >= prev.optLong("ts")) stash = json
            val track = mergeTrack(MusicWire.track(json.optJSONObject("track")))
            if (track != null && together == Together.Invited) {
                state = state.copy(
                    inviteTrack = track,
                    track = track,
                    durationMs = track.durationMs.coerceAtLeast(0L),
                )
            }
            return
        }
        if (together != Together.Active) return
        val ts = json.optLong("ts")
        val current = inboundLatest
        if (current != null && current.optLong("ts") > ts) return
        inboundLatest = json
        inboundJob?.cancel()
        inboundJob = scope.launch {
            delay(APPLY_DEBOUNCE_MS)
            val msg = inboundLatest ?: return@launch
            inboundLatest = null
            applyTransport(msg)
        }
    }

    private fun applyTransport(json: JSONObject) {
        if (together != Together.Active || !opened) return
        val ts = json.optLong("ts")
        if (ts <= 0L) return
        val now = System.currentTimeMillis()
        clock.noteIfNeeded(ts, now)
        if (!shouldApply(ts)) return
        val op = json.optString("op")
        val pos = json.optLong("pos").coerceAtLeast(0L)
        val incoming = mergeTrack(MusicWire.track(json.optJSONObject("track")))
        val queue = MusicWire.queue(json).map { mergeTrack(it) ?: it }
        if (queue.isNotEmpty()) state = state.copy(queue = queue)
        if (op == "queue") {
            if (incoming != null) state = state.copy(track = incoming)
            return
        }
        val track = incoming ?: state.track
        val advancing = when (op) {
            "pause" -> false
            "seek", "track" -> state.playing
            else -> true
        }
        val target = if (!advancing) pos else clock.target(pos, ts, now, true)
        when (op) {
            "pause" -> {
                resolveJob?.cancel()
                playGen++
                player?.pauseUser()
                if (player?.hasPrepared() == true) player?.seekTo(pos)
                state = state.copy(
                    playing = false,
                    positionMs = pos,
                    track = track ?: state.track,
                    transfer = MusicTransfer.Idle,
                )
            }
            "seek" -> {
                if (player?.hasPrepared() == true && track?.matches(state.track) == true) {
                    player?.seekTo(target)
                    state = state.copy(positionMs = target, track = track)
                } else if (track != null) {
                    beginResolve(track, target, start = state.playing, remote = true)
                }
            }
            "track" -> {
                if (track != null) {
                    state = state.copy(track = track, durationMs = track.durationMs.coerceAtLeast(0L))
                    beginResolve(track, target, start = state.playing, remote = true)
                }
            }
            "play", "sync" -> {
                state = state.copy(playing = true, track = track ?: state.track, positionMs = target)
                if (track != null) beginResolve(track, target, start = true, remote = true)
                else player?.resume()
            }
        }
    }

    private fun shouldApply(ts: Long): Boolean {
        if (ts <= lastRemoteTs) return false
        val reject = clock.ready && localGuardUntil > 0L && (ts - clock.offset) + 350L < localGuardUntil
        lastRemoteTs = ts
        return !reject
    }

    private fun beginResolve(track: Track, at: Long, start: Boolean, remote: Boolean) {
        val key = keyOf(track)
        if (key == loadedKey && player?.hasPrepared() == true) {
            val cur = player?.position() ?: 0L
            if (abs(cur - at) > DRIFT_MS) player?.seekTo(at)
            if (start && player?.isPlaying() != true) player?.resume()
            state = state.copy(
                playing = if (start) true else state.playing,
                positionMs = if (abs(cur - at) > DRIFT_MS) at else cur,
                track = track,
                durationMs = if (track.durationMs > 0L) track.durationMs else state.durationMs,
                banner = clearPlayBanner(state.banner),
            )
            maybeShareOwnFile(track, remote)
            return
        }
        resolveJob?.cancel()
        val gen = ++playGen
        resolveJob = scope.launch {
            val url = try {
                resolveUrl(track)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            if (gen != playGen || !opened) return@launch
            if (url == null) {
                val banner = if (TrackExtra.fid(track.extra) != null) {
                    MusicBanner.DownloadFailed
                } else {
                    MusicBanner.PlayFailed
                }
                loadedKey = ""
                state = state.copy(playing = false, transfer = MusicTransfer.Idle, banner = banner)
                return@launch
            }
            val atMs = if (queuedSeek >= 0L) queuedSeek.also { queuedSeek = -1L } else at
            loadedKey = key
            ensurePlayer()?.play(url, atMs, start)
            state = state.copy(
                transfer = if (state.transfer == MusicTransfer.Downloading) MusicTransfer.Idle else state.transfer,
                positionMs = atMs,
                playing = start,
                track = track,
                durationMs = if (track.durationMs > 0L) track.durationMs else state.durationMs,
                banner = clearPlayBanner(state.banner),
            )
            maybeShareOwnFile(track, remote)
        }
    }

    private fun mergeTrack(incoming: Track?): Track? {
        if (incoming == null) return null
        val local = state.queue.firstOrNull { it.matches(incoming) }
            ?: state.track?.takeIf { it.matches(incoming) }
            ?: return incoming
        val fid = TrackExtra.fid(incoming.extra)
        val key = TrackExtra.key(incoming.extra)
        val extra = when {
            fid != null && key != null -> TrackExtra.withFile(local.extra, fid, key)
            TrackExtra.fid(local.extra) != null && fid == null -> local.extra
            incoming.extra.isNotBlank() -> incoming.extra
            else -> local.extra
        }
        return incoming.copy(
            title = incoming.title.ifBlank { local.title },
            artist = incoming.artist.ifBlank { local.artist },
            durationMs = if (incoming.durationMs > 0L) incoming.durationMs else local.durationMs,
            extra = extra,
        )
    }

    private fun maybeShareOwnFile(track: Track, remote: Boolean) {
        if (!remote || together != Together.Active) return
        if (track.sourceId != LocalSource.ID || TrackExtra.fid(track.extra) != null) return
        val key = keyOf(track)
        if (skipShareKey == key) return
        skipShareKey = key
        enqueueSend("play", immediate = false)
    }

    private suspend fun resolveUrl(track: Track): String? {
        if (track.sourceId == LocalSource.ID) {
            val direct = TrackExtra.uri(track.extra)
            if (direct != null && withContext(Dispatchers.IO) { LocalAudio.canRead(app, direct) }) return direct
            val own = catalog.filterIsInstance<LocalSource>().firstOrNull() ?: LocalSource(app)
            val resolved = try {
                own.resolveStreamUrl(track)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            if (resolved != null && withContext(Dispatchers.IO) { LocalAudio.canRead(app, resolved) }) {
                return resolved
            }
        }
        val fid = TrackExtra.fid(track.extra)
        val key = TrackExtra.key(track.extra)
        if (fid != null && key != null) {
            MusicCache.existing(app, fid)?.let { return it }
            state = state.copy(transfer = MusicTransfer.Downloading)
            val path = try {
                MusicCache.download(app, fid, key)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            if (state.transfer == MusicTransfer.Downloading) {
                state = state.copy(transfer = MusicTransfer.Idle)
            }
            return path
        }
        if (track.sourceId == LocalSource.ID) return null
        val source = catalog.firstOrNull { it.id == track.sourceId } ?: return null
        return try {
            source.resolveStreamUrl(track)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    private fun onTrackEnded() {
        if (!opened) return
        skip(forward = true, fromUser = false)
    }

    private fun skip(forward: Boolean, fromUser: Boolean) {
        if (!opened) return
        val queue = state.queue
        val current = state.track
        if (!fromUser && state.repeat == RepeatMode.One && current != null) {
            play(current)
            return
        }
        if (queue.isEmpty()) {
            if (!fromUser) stopAtEnd()
            return
        }
        val index = queue.indexOfFirst { it.matches(current) }.coerceAtLeast(0)
        val dest: Int? = when {
            forward && state.shuffle && queue.size > 1 -> queue.indices.filter { it != index }.random()
            forward && index + 1 < queue.size -> index + 1
            forward && (state.repeat == RepeatMode.All || state.shuffle) -> 0
            forward && queue.size == 1 && fromUser -> 0
            forward && !fromUser -> {
                stopAtEnd()
                return
            }
            forward -> null
            index > 0 -> index - 1
            state.repeat == RepeatMode.All -> queue.lastIndex
            else -> 0
        }
        val next = dest ?: return
        val track = queue.getOrNull(next) ?: return
        play(track)
    }

    private fun stopAtEnd() {
        player?.pauseUser()
        state = state.copy(playing = false)
        if (together == Together.Active) enqueueSend("pause", immediate = false)
        persist()
        holdBackground()
    }

    private fun enqueueSend(op: String, immediate: Boolean) {
        if (!canSend(op)) return
        noteLocal()
        val gen = ++commandGen
        sendJob?.cancel()
        sendJob = scope.launch {
            try {
                if (!immediate) delay(SEND_DEBOUNCE_MS)
                if (gen != commandGen) return@launch
                deliver(op, gen)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (opened && op != "sync") state = state.copy(banner = MusicBanner.SendFailed)
            }
        }
    }

    private fun canSend(op: String): Boolean = when (op) {
        "leave" -> true
        "invite" -> Store.isPaired(app) && (together == Together.Inviting || together == Together.Active)
        else -> together == Together.Active && Store.isPaired(app)
    }

    private suspend fun deliver(op: String, gen: Int) {
        if (gen != commandGen || !opened) return
        var track = state.track
        val shouldUpload = track != null && together == Together.Active &&
            (op == "play" || op == "track" || (op == "sync" && player?.isPlaying() == true))
        if (shouldUpload && track != null) {
            val updated = uploadLocal(track, gen) ?: return
            if (gen != commandGen || !opened) return
            track = updated
        }
        if (gen != commandGen || !opened) return
        val pos = if (state.seeking) state.positionMs else player?.position() ?: state.positionMs
        val json = MusicWire.build(op, System.currentTimeMillis(), pos, track, state.queue)
        val ok = post(json, wake = op == "invite", bannerOnFail = op != "sync")
        if (!ok && op == "invite" && together == Together.Inviting) {
            dropSession(showLeft = false)
            state = state.copy(banner = MusicBanner.SendFailed)
        }
    }

    private suspend fun uploadLocal(track: Track, gen: Int): Track? {
        if (track.sourceId != LocalSource.ID) return track
        if (TrackExtra.fid(track.extra) != null) return track
        if (gen != commandGen) return null
        val uriString = TrackExtra.uri(track.extra)
            ?: (catalog.filterIsInstance<LocalSource>().firstOrNull() ?: LocalSource(app)).resolveStreamUrl(track)
        if (uriString == null || !uriString.startsWith("content://media/")) return track
        if (TrackExtra.bytes(track.extra) > LocalAudio.MAX_SHARE) {
            state = state.copy(banner = MusicBanner.TooBig)
            return track
        }
        state = state.copy(transfer = MusicTransfer.Sending)
        val read = withContext(Dispatchers.IO) {
            try {
                LocalAudio.read(app, Uri.parse(uriString), LocalAudio.MAX_SHARE)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                LocalRead.Failed
            }
        }
        if (gen != commandGen || !opened) {
            state = state.copy(transfer = MusicTransfer.Idle)
            return null
        }
        when (read) {
            LocalRead.TooBig -> {
                state = state.copy(transfer = MusicTransfer.Idle, banner = MusicBanner.TooBig)
                return track
            }
            LocalRead.Failed -> {
                state = state.copy(transfer = MusicTransfer.Idle, banner = MusicBanner.SendFailed)
                return track
            }
            is LocalRead.Ok -> {
                val uploaded = try {
                    // DATA-FLOW: local audio bytes, encrypted on this phone inside FileTransfer (Core.fileEncrypt), PUT relay /file/{fid}. The relay stores ciphertext for 7 days and cannot read the song. The file id and key go only inside the sealed music message. Songs over 20 MB are not uploaded.
                    FileTransfer.upload(app, read.bytes)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
                if (gen != commandGen || !opened) {
                    state = state.copy(transfer = MusicTransfer.Idle)
                    return null
                }
                if (uploaded == null) {
                    state = state.copy(transfer = MusicTransfer.Idle, banner = MusicBanner.SendFailed)
                    return track
                }
                val updated = track.copy(extra = TrackExtra.withFile(track.extra, uploaded.first, uploaded.second))
                val queue = state.queue.map { item -> if (item.matches(updated)) updated else item }
                val current = if (state.track?.matches(updated) == true) updated else state.track
                state = state.copy(queue = queue, track = current, transfer = MusicTransfer.Idle)
                return updated
            }
        }
    }

    private suspend fun post(json: JSONObject, wake: Boolean, bannerOnFail: Boolean): Boolean {
        var body = json
        if (body.has("queue") && body.toString().toByteArray(Charsets.UTF_8).size > 60_000) {
            body = JSONObject(body.toString())
            body.remove("queue")
        }
        val ok = try {
            // DATA-FLOW: music control (op, timestamps, track ids, and for a shared song the file id and key). Padded and sealed by Outbox. The relay stores ciphertext only. Invite sets wake so the relay asks Google for an empty ping. The ping has no song and no name.
            Outbox.send(app, body, wake)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
        if (!ok && bannerOnFail && opened) state = state.copy(banner = MusicBanner.SendFailed)
        return ok
    }

    private fun dropSession(showLeft: Boolean) {
        together = Together.Solo
        AppState.fastPoll = false
        inviteJob?.cancel()
        inboundJob?.cancel()
        inboundLatest = null
        stash = null
        clock.reset()
        lastRemoteTs = 0L
        localGuardUntil = 0L
        MusicHold.clear(app)
        MusicNotify.cancel(app)
        state = state.copy(
            together = Together.Solo,
            inviteTrack = null,
            banner = if (showLeft) MusicBanner.PartnerLeft else state.banner,
        )
    }

    private fun pauseFromSystem() {
        if (!opened) return
        state = state.copy(playing = false, positionMs = player?.position() ?: state.positionMs)
        if (together == Together.Active) enqueueSend("pause", immediate = true)
        persist()
        holdBackground()
    }

    private fun startInviteTimeout() {
        inviteJob?.cancel()
        inviteJob = scope.launch {
            delay(INVITE_TIMEOUT_MS)
            if (together == Together.Inviting && opened) {
                state = state.copy(banner = MusicBanner.Timeout)
            }
        }
    }

    private fun startTicker() {
        if (ticker?.isActive == true) return
        ticker = scope.launch {
            while (isActive) {
                delay(250)
                if (!visible || state.seeking || !opened) continue
                val current = player ?: continue
                if (!current.isPlaying()) continue
                val pos = current.position()
                val dur = current.duration().takeIf { it > 0L } ?: state.durationMs
                if (pos != state.positionMs || dur != state.durationMs) {
                    state = state.copy(positionMs = pos, durationMs = dur, playing = true)
                }
            }
        }
    }

    private fun loadCatalog() {
        catalog = MusicSources.all(app)
        val options = catalog.map { SourceOption(it.id, it.displayName) }
        val id = options.firstOrNull { it.id == state.sourceId }?.id ?: LocalSource.ID
        state = state.copy(sources = options, sourceId = id)
    }

    private fun clearBanner() {
        if (state.banner != MusicBanner.None) state = state.copy(banner = MusicBanner.None)
    }

    private fun clearPlayBanner(current: MusicBanner): MusicBanner =
        if (current == MusicBanner.PlayFailed || current == MusicBanner.DownloadFailed) {
            MusicBanner.None
        } else {
            current
        }

    private fun noteLocal() {
        localGuardUntil = System.currentTimeMillis()
    }

    private fun keyOf(track: Track): String = track.sourceId + ":" + track.id

    private fun queueCap(): Int = if (together == Together.Solo) LOCAL_QUEUE else MAX_QUEUE

    private fun ensurePlayer(): MusicPlayer? {
        if (!opened) return null
        player?.let { return it }
        return MusicPlayer(app, playerListener).also { player = it }
    }

    fun reloadHome() {
        if (!opened) return
        scope.launch {
            val granted = LocalSource.permitted(app)
            val loaded = try {
                withContext(Dispatchers.IO) {
                    LibraryHome(
                        playlists = MusicLibrary.playlists(app),
                        songCount = if (granted) LocalSource(app).count() else 0,
                        likedKeys = MusicLibrary.likedKeys(app),
                        offlineKeys = OfflineStore.keys(app),
                        offlineBytes = OfflineStore.used(app),
                        offlineCap = MusicLibrary.offlineCap(app),
                        keep = MusicLibrary.keepBackground(app),
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            if (!opened || loaded == null) return@launch
            state = state.copy(
                playlists = loaded.playlists,
                songCount = loaded.songCount,
                likedKeys = loaded.likedKeys,
                offlineKeys = loaded.offlineKeys,
                offlineBytes = loaded.offlineBytes,
                offlineCap = loaded.offlineCap,
                keepBackground = loaded.keep,
                audioGranted = LocalSource.permitted(app),
            )
        }
    }

    fun searchAll(raw: String) {
        if (!opened) return
        val query = raw.take(80)
        state = state.copy(query = query)
        searchJob?.cancel()
        val captured = query.trim()
        if (captured.isEmpty()) {
            state = state.copy(hits = emptyList(), searching = false)
            return
        }
        state = state.copy(searching = true)
        searchJob = scope.launch {
            delay(250)
            if (!opened || state.query.trim() != captured) return@launch
            val hits = ArrayList<MusicHit>()
            try {
                if (LocalSource.permitted(app)) {
                    LocalSource(app).search(captured).forEach { hits.add(MusicHit.Song(it)) }
                }
                val albums = state.albums.ifEmpty { LocalSource(app).albums() }
                albums.filter { row ->
                    row.name.contains(captured, ignoreCase = true) || row.artist.contains(captured, ignoreCase = true)
                }.take(20).forEach { hits.add(MusicHit.AlbumHit(it)) }
                MusicLibrary.playlists(app)
                    .filter { it.name.contains(captured, ignoreCase = true) }
                    .forEach { hits.add(MusicHit.PlaylistHit(it)) }
                val seen = hits.mapNotNull { (it as? MusicHit.Song)?.track?.key() }.toMutableSet()
                val extra = MusicLibrary.liked(app) + OfflineStore.tracks(app)
                for (track in extra) {
                    if (track.key() in seen) continue
                    val title = track.title.contains(captured, ignoreCase = true)
                    val artist = track.artist.contains(captured, ignoreCase = true)
                    if (title || artist) {
                        hits.add(MusicHit.Song(track))
                        seen.add(track.key())
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Partial results still show.
            }
            if (!opened || state.query.trim() != captured) return@launch
            state = state.copy(hits = hits.take(80), searching = false, audioGranted = LocalSource.permitted(app))
        }
    }

    fun loadAlbums() {
        if (!opened) return
        searchJob?.cancel()
        state = state.copy(searching = true, browseKind = "albums", query = "", playlistId = "")
        searchJob = scope.launch {
            val list = try {
                LocalSource(app).albums()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                emptyList()
            }
            if (!opened) return@launch
            state = state.copy(albums = list, searching = false, audioGranted = LocalSource.permitted(app))
        }
    }

    fun loadAlbum(id: String) {
        if (!opened) return
        searchJob?.cancel()
        state = state.copy(searching = true, browseKind = "album", query = "", playlistId = "")
        searchJob = scope.launch {
            val list = try {
                LocalSource(app).tracksInAlbum(id)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                emptyList()
            }
            if (!opened) return@launch
            state = state.copy(results = list, searching = false, audioGranted = LocalSource.permitted(app))
        }
    }

    fun loadLiked() {
        if (!opened) return
        loadStored("liked") { MusicLibrary.liked(app) }
    }

    fun loadOffline() {
        if (!opened) return
        loadStored("offline") { OfflineStore.tracks(app) }
    }

    fun loadPlaylist(id: String) {
        if (!opened) return
        val row = state.playlists.firstOrNull { it.id == id } ?: MusicLibrary.playlist(app, id)
        state = state.copy(
            searching = true,
            browseKind = "playlist",
            playlistId = id,
            playlistTitle = row?.name.orEmpty(),
            playlistShared = row?.shared == true,
            query = "",
        )
        searchJob?.cancel()
        searchJob = scope.launch {
            val list = withContext(Dispatchers.IO) { MusicLibrary.tracks(app, id) }
            if (!opened) return@launch
            state = state.copy(results = list, searching = false)
        }
    }

    fun toggleLike(track: Track?) {
        val item = track ?: return
        if (!opened || item.id.isBlank()) return
        val on = item.key() !in state.likedKeys
        MusicLibrary.setLiked(app, item, on)
        state = state.copy(likedKeys = if (on) state.likedKeys + item.key() else state.likedKeys - item.key())
        if (state.browseKind == "liked") loadLiked()
    }

    fun createPlaylist(name: String): PlaylistRow? {
        if (!opened) return null
        val row = MusicLibrary.create(app, name) ?: return null
        reloadHome()
        return row
    }

    fun renamePlaylist(id: String, name: String) {
        if (!opened) return
        val clean = name.trim().replace('\n', ' ').take(80)
        if (clean.isEmpty()) return
        val ts = System.currentTimeMillis()
        if (!MusicLibrary.rename(app, id, clean, ts)) return
        val shared = MusicLibrary.playlist(app, id)?.shared == true
        if (shared) sendPlaylist(PlaylistWire.rename(id, clean, ts))
        if (state.playlistId == id) state = state.copy(playlistTitle = clean)
        reloadHome()
    }

    fun deletePlaylist(id: String) {
        if (!opened) return
        val shared = MusicLibrary.playlist(app, id)?.shared == true
        val ts = System.currentTimeMillis()
        MusicLibrary.delete(app, id, ts)
        if (shared) sendPlaylist(PlaylistWire.delete(id, ts))
        if (state.playlistId == id) {
            state = state.copy(playlistId = "", playlistTitle = "", results = emptyList(), browseKind = "")
        }
        reloadHome()
    }

    fun setPlaylistShared(id: String, on: Boolean) {
        if (!opened) return
        scope.launch {
            val ts = System.currentTimeMillis()
            if (!MusicLibrary.setShared(app, id, on, ts)) return@launch
            state = state.copy(playlistShared = if (state.playlistId == id) on else state.playlistShared)
            if (on && Store.isPaired(app)) {
                val tracks = withContext(Dispatchers.IO) { MusicLibrary.tracks(app, id) }
                val prepared = ArrayList<Track>(tracks.size)
                var uploads = 0
                for (track in tracks) {
                    if (!opened) return@launch
                    val needsFile = track.sourceId == LocalSource.ID && TrackExtra.fid(track.extra) == null
                    if (needsFile && uploads < 15) {
                        uploads++
                        val updated = uploadLocal(track, commandGen)
                        if (!opened) return@launch
                        val stored = updated ?: track
                        if (TrackExtra.fid(stored.extra) != null) {
                            MusicLibrary.addTrack(app, id, stored, System.currentTimeMillis())
                        }
                        prepared.add(stored)
                    } else {
                        prepared.add(track)
                    }
                }
                val name = MusicLibrary.playlist(app, id)?.name.orEmpty()
                sendPlaylist(PlaylistWire.snapshot(id, name, prepared, System.currentTimeMillis()))
            }
            reloadHome()
            if (state.playlistId == id) loadPlaylist(id)
        }
    }

    fun addToPlaylist(id: String, track: Track) {
        if (!opened) return
        scope.launch {
            val row = MusicLibrary.playlist(app, id) ?: return@launch
            var stored = track
            if (row.shared && track.sourceId == LocalSource.ID && TrackExtra.fid(track.extra) == null) {
                val updated = uploadLocal(track, commandGen)
                if (!opened) return@launch
                if (updated != null) stored = updated
            }
            val ts = System.currentTimeMillis()
            MusicLibrary.addTrack(app, id, stored, ts)
            if (row.shared && Store.isPaired(app)) sendPlaylist(PlaylistWire.add(id, stored, ts))
            reloadHome()
            if (state.playlistId == id) loadPlaylist(id)
        }
    }

    fun removeFromPlaylist(id: String, track: Track) {
        if (!opened) return
        val ts = System.currentTimeMillis()
        val shared = MusicLibrary.playlist(app, id)?.shared == true
        MusicLibrary.removeTrack(app, id, track, ts)
        if (shared) sendPlaylist(PlaylistWire.remove(id, track, ts))
        reloadHome()
        if (state.playlistId == id) loadPlaylist(id)
    }

    fun moveInPlaylist(id: String, index: Int, delta: Int) {
        if (!opened) return
        if (!MusicLibrary.moveTrack(app, id, index, delta)) return
        val shared = MusicLibrary.playlist(app, id)?.shared == true
        if (shared && Store.isPaired(app)) {
            val tracks = MusicLibrary.tracks(app, id)
            val name = MusicLibrary.playlist(app, id)?.name.orEmpty()
            sendPlaylist(PlaylistWire.snapshot(id, name, tracks, System.currentTimeMillis()))
        }
        if (state.playlistId == id) loadPlaylist(id)
    }

    fun downloadOffline(track: Track) {
        if (!opened) return
        scope.launch {
            val result = try {
                OfflineStore.pin(app, track) { resolveUrl(track) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                PinResult.Failed
            }
            if (!opened) return@launch
            when (result) {
                PinResult.Full -> state = state.copy(banner = MusicBanner.OfflineFull)
                PinResult.Failed -> state = state.copy(banner = MusicBanner.OfflineFailed)
                PinResult.Saved, PinResult.Already -> state = state.copy(
                    offlineBytes = OfflineStore.used(app),
                    offlineKeys = OfflineStore.keys(app),
                    banner = if (state.banner == MusicBanner.OfflineFull || state.banner == MusicBanner.OfflineFailed) {
                        MusicBanner.None
                    } else {
                        state.banner
                    },
                )
            }
            if (state.browseKind == "offline") loadOffline()
        }
    }

    fun clearOffline() {
        if (!opened) return
        OfflineStore.clear(app)
        state = state.copy(
            offlineBytes = 0L,
            offlineKeys = emptySet(),
            results = if (state.browseKind == "offline") emptyList() else state.results,
        )
    }

    fun setOfflineCapMb(mb: Int) {
        if (!opened) return
        MusicLibrary.setOfflineCapMb(app, mb)
        state = state.copy(offlineCap = MusicLibrary.offlineCap(app))
    }

    fun setKeepBackground(on: Boolean) {
        if (!opened) return
        MusicLibrary.setKeepBackground(app, on)
        state = state.copy(keepBackground = on)
        if (!on) {
            MusicPlayService.stop(app)
            idleJob?.cancel()
            if (!visible) {
                heldOutput = false
                player?.pauseOutput()
                state = state.copy(playing = false)
            }
        }
    }

    fun setSleep(minutes: Int) {
        if (!opened) return
        sleepJob?.cancel()
        if (minutes <= 0) {
            state = state.copy(sleepMinutes = 0)
            return
        }
        val chosen = minutes.coerceIn(1, 180)
        state = state.copy(sleepMinutes = chosen)
        sleepJob = scope.launch {
            delay(chosen * 60_000L)
            if (opened) stopForSleep()
        }
    }

    internal fun onLibraryChanged() {
        if (!opened) return
        val kind = state.browseKind
        val openId = state.playlistId
        scope.launch {
            val lists = withContext(Dispatchers.IO) { MusicLibrary.playlists(app) }
            val liked = withContext(Dispatchers.IO) { MusicLibrary.likedKeys(app) }
            val offline = withContext(Dispatchers.IO) { OfflineStore.keys(app) }
            val browse = when (kind) {
                "playlist" -> withContext(Dispatchers.IO) { MusicLibrary.tracks(app, openId) }
                "liked" -> withContext(Dispatchers.IO) { MusicLibrary.liked(app) }
                "offline" -> withContext(Dispatchers.IO) { OfflineStore.tracks(app) }
                else -> null
            }
            if (!opened) return@launch
            val row = lists.firstOrNull { it.id == openId }
            state = state.copy(
                playlists = lists,
                likedKeys = liked,
                offlineKeys = offline,
                offlineBytes = OfflineStore.used(app),
                results = browse ?: state.results,
                playlistTitle = row?.name ?: state.playlistTitle,
                playlistShared = row?.shared ?: state.playlistShared,
            )
        }
    }

    private fun loadStored(kind: String, load: () -> List<Track>) {
        searchJob?.cancel()
        state = state.copy(searching = true, browseKind = kind, query = "", playlistId = "")
        searchJob = scope.launch {
            val list = try {
                withContext(Dispatchers.IO) { load() }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                emptyList()
            }
            if (!opened) return@launch
            state = state.copy(results = list, searching = false)
        }
    }

    private fun stopForSleep() {
        sleepJob?.cancel()
        state = state.copy(sleepMinutes = 0)
        if (together != Together.Solo) {
            leave()
        } else {
            player?.pauseUser()
            state = state.copy(playing = false)
        }
        val dead = player
        player = null
        loadedKey = ""
        dead?.release()
        MusicPlayService.stop(app)
        AlbumArt.clear()
        AppScope.scope.launch {
            try {
                MusicCache.wipe(app)
            } catch (_: Exception) {
                // Cache cleanup is best-effort.
            }
        }
        if (!uiAttached) releaseFully()
    }

    private fun holdBackground() {
        if (!opened || (uiAttached && visible)) return
        if (!state.keepBackground) return
        if (MusicBridge.commanding()) {
            if (state.playing) idleJob?.cancel() else armIdle()
            return
        }
        if (state.track == null && !state.playing) {
            MusicPlayService.stop(app)
            return
        }
        if (!MusicPlayService.start(app)) {
            if (state.playing) {
                player?.pauseUser()
                state = state.copy(playing = false)
            }
            return
        }
        if (state.playing) idleJob?.cancel() else armIdle()
    }

    private fun armIdle() {
        idleJob?.cancel()
        idleJob = scope.launch {
            delay(PAUSE_HOLD_MS)
            if (!opened || state.playing) return@launch
            if (uiAttached && visible) return@launch
            releaseFully()
        }
    }

    private fun persist() {
        try {
            val pos = if (state.seeking) state.positionMs else player?.position() ?: state.positionMs
            MusicLibrary.savePlayback(app, state.queue, state.track, pos, state.shuffle, state.repeat)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // The queue stays in memory until the next successful save.
        }
    }

    private fun sendPlaylist(json: JSONObject) {
        if (!opened || !Store.isPaired(app)) return
        scope.launch {
            val ok = try {
                var body = json
                if (body.toString().toByteArray(Charsets.UTF_8).size > 60_000) {
                    body = PlaylistWire.stripExtras(body)
                }
                // DATA-FLOW: shared playlist edit (op, playlist id, name, track titles and ids, and for a shared file the file id and key). Padded and sealed by Outbox. wake is off. The relay stores ciphertext only. Solo playback does not send this.
                Outbox.send(app, body, false)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                false
            }
            if (!ok && opened) state = state.copy(banner = MusicBanner.SendFailed)
        }
    }

    private fun releaseFully() {
        if (!opened && player == null) {
            MusicPlayService.stop(app)
            MusicBridge.publish(null)
            return
        }
        val parting = (together != Together.Solo || oweLeave) && Store.isPaired(app)
        oweLeave = false
        val leave = if (parting) {
            MusicWire.build(
                "leave",
                System.currentTimeMillis(),
                player?.position() ?: state.positionMs,
                state.track,
                state.queue,
            )
        } else {
            null
        }
        persist()
        opened = false
        visible = false
        uiAttached = false
        heldOutput = false
        idleJob?.cancel()
        sleepJob?.cancel()
        together = Together.Solo
        AppState.fastPoll = false
        state = state.copy(together = Together.Solo, playing = false)
        MusicBridge.detach(this)
        MusicBridge.publish(null)
        MusicHold.clear(app)
        MusicNotify.cancel(app)
        MusicPlayService.stop(app)
        AlbumArt.clear()
        val releasedPlayer = player
        player = null
        scope.cancel()
        releasedPlayer?.release()
        if (leave != null) {
            AppScope.scope.launch {
                try {
                    // DATA-FLOW: music leave. Padded and sealed by Outbox. The relay stores ciphertext only. wake is off.
                    Outbox.send(app, leave, false)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // The partner stops when their next poll sees no sync, or when they leave.
                }
            }
        }
        AppScope.scope.launch {
            try {
                MusicCache.wipe(app)
            } catch (_: Exception) {
                // Cache cleanup is best-effort.
            }
        }
    }
}

private data class LibraryHome(
    val playlists: List<PlaylistRow>,
    val songCount: Int,
    val likedKeys: Set<String>,
    val offlineKeys: Set<String>,
    val offlineBytes: Long,
    val offlineCap: Long,
    val keep: Boolean,
)

private class PlayClock {
    var offset: Long = 0L
    var ready: Boolean = false

    fun reset() {
        offset = 0L
        ready = false
    }

    fun noteRtt(theirTs: Long, now: Long, sentAt: Long) {
        val rtt = now - sentAt
        if (sentAt > 0L && rtt in 0L..60_000L) {
            offset = theirTs - (now - rtt / 2)
            ready = true
        } else {
            noteIfNeeded(theirTs, now)
        }
    }

    fun noteIfNeeded(theirTs: Long, now: Long) {
        if (!ready && theirTs > 0L) {
            offset = theirTs - now
            ready = true
        }
    }

    fun target(pos: Long, theirTs: Long, now: Long, advancing: Boolean): Long {
        val base = pos.coerceAtLeast(0L)
        if (!advancing || !ready) return base
        val elapsed = (now + offset - theirTs).coerceIn(0L, 15_000L)
        return base + elapsed
    }
}

object MusicBridge {
    private val gate = Any()
    private var live: MusicSession? = null

    var glance by mutableStateOf<MusicGlance?>(null)
        private set

    internal fun publish(value: MusicGlance?) {
        if (glance != value) glance = value
    }

    fun attached(): MusicSession? = synchronized(gate) { live?.takeIf { it.isUsable() } }

    internal fun session(): MusicSession? = attached()

    private var commanding = false

    /** True while a notification action is running, so playback does not start the service again. */
    internal fun commanding(): Boolean = commanding

    internal fun inCommand(block: () -> Unit) {
        commanding = true
        try {
            block()
        } finally {
            commanding = false
        }
    }

    fun attach(session: MusicSession) {
        synchronized(gate) { live = session }
    }

    fun detach(session: MusicSession) {
        synchronized(gate) { if (live === session) live = null }
    }

    internal fun notifyLibrary(context: Context) {
        val session = attached() ?: return
        context.applicationContext.mainExecutor.execute {
            try {
                session.onLibraryChanged()
            } catch (_: Exception) {
                // The screen reloads the next time it opens.
            }
        }
    }

    fun dispatch(context: Context, json: JSONObject) {
        val op = json.optString("op")
        val app = context.applicationContext
        val copy = try {
            JSONObject(json.toString())
        } catch (_: Exception) {
            return
        }
        if (op == "invite") {
            MusicHold.save(app, copy)
        } else if (op == "leave") {
            MusicHold.clear(app)
            MusicNotify.cancel(app)
        }
        val session = synchronized(gate) { live?.takeIf { it.isUsable() } }
        if (session != null) {
            app.mainExecutor.execute {
                try {
                    session.onMessage(copy)
                } catch (_: Exception) {
                    // The screen ignores a bad payload.
                }
            }
            if (op == "invite") MusicNotify.cancel(app)
            return
        }
        if (op == "invite") MusicNotify.showInvite(app)
    }
}
