package app.loviedowpie.music

import android.content.Context
import android.net.Uri
import app.loviedowpie.AppScope
import app.loviedowpie.AppState
import app.loviedowpie.FileTransfer
import app.loviedowpie.Outbox
import app.loviedowpie.Store
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
}

data class SourceOption(val id: String, val label: String)

data class MusicUiState(
    val together: Together = Together.Solo,
    val track: Track? = null,
    val inviteTrack: Track? = null,
    val queue: List<Track> = emptyList(),
    val results: List<Track> = emptyList(),
    val query: String = "",
    val searching: Boolean = true,
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
)

/**
 * Playback and the listen-together session for one open music screen.
 * Constructed from that screen, not from process start. [close] releases the
 * player, stops the fast inbox poll, and cancels this screen's coroutines.
 *
 * DATA-FLOW: see [post], [uploadLocal], and [MusicCache.download].
 */
class MusicSession(private val app: Context) {
    var state by mutableStateOf(MusicUiState())
        private set

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
        }

        override fun onFocusLost() = pauseFromSystem()

        override fun onNoisy() = pauseFromSystem()
    }

    internal fun isUsable(): Boolean = opened

    fun open() {
        if (opened) {
            MusicBridge.attach(this)
            MusicNotify.cancel(app)
            return
        }
        opened = true
        MusicBridge.attach(this)
        MusicNotify.cancel(app)
        player = MusicPlayer(app, playerListener)
        loadCatalog()
        state = state.copy(audioGranted = LocalSource.permitted(app))
        val pending = MusicHold.peek(app)
        if (pending != null) onMessage(pending)
        setQuery("")
        syncJob = scope.launch {
            while (isActive) {
                delay(SYNC_EVERY_MS)
                if (!opened || !visible || together != Together.Active) continue
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
        opened = false
        visible = false
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
        together = Together.Solo
        AppState.fastPoll = false
        MusicBridge.detach(this)
        MusicHold.clear(app)
        MusicNotify.cancel(app)
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

    fun onShown() {
        if (!opened) return
        refreshPermission()
        if (visible && ticker?.isActive == true) return
        val resume = !visible && state.playing
        val gap = if (resume && hiddenAt > 0L) System.currentTimeMillis() - hiddenAt else 0L
        visible = true
        if (resume) {
            if (together == Together.Active && gap > 0L && player?.hasPrepared() == true) {
                val dur = player?.duration()?.takeIf { it > 0L } ?: state.durationMs
                val at = if (dur > 0L) (hiddenPos + gap).coerceIn(0L, dur) else hiddenPos + gap
                player?.seekTo(at)
                state = state.copy(positionMs = at)
            }
            player?.resumeOutput()
        }
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
            return
        }
        val track = state.track ?: state.queue.firstOrNull() ?: state.results.firstOrNull() ?: return
        if (loadedKey == keyOf(track) && player?.hasPrepared() == true) {
            player?.resume()
            state = state.copy(playing = true, track = track)
            if (together == Together.Active) enqueueSend("play", immediate = false)
        } else {
            play(track)
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
    }

    fun cycleRepeat() {
        if (!opened) return
        val next = when (state.repeat) {
            RepeatMode.Off -> RepeatMode.All
            RepeatMode.All -> RepeatMode.One
            RepeatMode.One -> RepeatMode.Off
        }
        state = state.copy(repeat = next)
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
    }

    fun play(track: Track) {
        if (!opened) return
        clearBanner()
        val queue = if (state.queue.any { it.matches(track) }) {
            state.queue
        } else {
            (listOf(track) + state.queue).take(MAX_QUEUE)
        }
        queuedSeek = -1L
        state = state.copy(
            queue = queue,
            track = track,
            playing = true,
            positionMs = 0L,
            durationMs = track.durationMs.coerceAtLeast(0L),
            seeking = false,
        )
        beginResolve(track, 0L, start = true, remote = false)
        if (together == Together.Active) enqueueSend("play", immediate = false)
    }

    fun enqueue(track: Track) {
        if (!opened) return
        if (state.queue.any { it.matches(track) } || state.queue.size >= MAX_QUEUE) return
        state = state.copy(queue = state.queue + track)
        if (together == Together.Active) enqueueSend("queue", immediate = false)
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
            player?.play(url, atMs, start)
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
}

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

internal object MusicBridge {
    private val gate = Any()
    private var live: MusicSession? = null

    fun attached(): MusicSession? = synchronized(gate) { live?.takeIf { it.isUsable() } }

    fun attach(session: MusicSession) {
        synchronized(gate) { live = session }
    }

    fun detach(session: MusicSession) {
        synchronized(gate) { if (live === session) live = null }
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
