package com.nextlayer.betweenly

import android.content.Context
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException

/**
 * What the sender's ring button is doing.
 * [Ringing.since] is this phone's clock when the call ring was sent.
 */
sealed class RingState {
    data object Idle : RingState()
    data object Starting : RingState()
    data class Ringing(val since: Long) : RingState()
    data object Stopping : RingState()
}

/** 8 hex chars, or null. Used to match ring, ringstop, and ringack. */
internal fun canonicalRid(raw: String?): String? {
    val value = raw?.trim()?.lowercase() ?: return null
    if (value.length != 8) return null
    if (value.any { it !in '0'..'9' && it !in 'a'..'f' }) return null
    return value
}

/**
 * Sender side of a call ring, and the one-shot ping.
 *
 * [startCall] seals `{"t":"ring","rid":"<8 hex>","mode":"call"}` with a wake and
 * moves to [RingState.Ringing]. The same control then calls [stopCall], which
 * seals `ringstop` with a wake. [sendPing] seals `{"t":"ping"}` with a wake and
 * does not change [state].
 *
 * [state] is restored from [Store] after process death. It returns to
 * [RingState.Idle] when two minutes pass, when [stopCall] is accepted, or when
 * the partner dismisses the alarm and this phone opens their `ringack`.
 * A failed stop leaves the button on Ringing so it can be tried again.
 * The partner's own two-minute cap still ends the sound.
 *
 * A plain `{"t":"ring"}` with no id, from an older button, still starts the alarm.
 * It does not move [state], because there is no id to stop.
 *
 * DATA-FLOW: the three sends are encrypted mailbox posts. The wake is an empty
 * FCM ping with no message body. `ringack` is not sent from here.
 */
object RingController {
    private val _state = MutableStateFlow<RingState>(RingState.Idle)

    /** Collect this. Do not write it. */
    val state: StateFlow<RingState> = _state

    private val gate = Mutex()
    private val hydrateLock = Any()
    private val random = SecureRandom()
    private val pinging = AtomicBoolean(false)
    private var timeoutJob: kotlinx.coroutines.Job? = null

    @Volatile private var hydrated = false
    @Volatile private var lastPingMs = 0L

    /**
     * Restores [state] from the saved send time. Safe to call more than once.
     * No network. No wake.
     */
    fun ensure(context: Context) {
        if (hydrated) return
        val app = context.applicationContext
        synchronized(hydrateLock) {
            if (hydrated) return
            hydrated = true
            val rid = Store.outgoingRingRid(app)
            val since = Store.outgoingRingSince(app)
            val now = System.currentTimeMillis()
            val valid = canonicalRid(rid) != null &&
                since > 0L &&
                now >= since &&
                now - since < RingService.LIMIT_MS
            if (!valid) {
                if (rid.isNotEmpty() || since != 0L) Store.clearOutgoingRing(app)
                _state.value = RingState.Idle
                return
            }
            _state.value = RingState.Ringing(since)
            armTimeout(app, rid, since)
        }
    }

    /**
     * Ends a call that has already run for two minutes. Called from the inbox
     * pull, which already runs while the screen is open, so this is not a new timer.
     */
    fun tick(context: Context) {
        val app = context.applicationContext
        ensure(app)
        val since = Store.outgoingRingSince(app)
        if (since <= 0L) return
        if (System.currentTimeMillis() - since < RingService.LIMIT_MS) return
        timeoutJob?.cancel()
        Store.clearOutgoingRing(app)
        val current = _state.value
        if (current is RingState.Ringing || current is RingState.Starting || current is RingState.Stopping) {
            _state.value = RingState.Idle
        }
    }

    /** Sends the call ring. No-op unless the button is idle and this phone is paired. */
    fun startCall(context: Context) {
        val app = context.applicationContext
        ensure(app)
        if (!Store.isPaired(app)) return
        AppScope.scope.launch {
            gate.withLock {
                if (_state.value !is RingState.Idle) return@withLock
                val rid = newRid()
                val since = System.currentTimeMillis()
                _state.value = RingState.Starting
                if (!Store.setOutgoingRing(app, rid, since)) {
                    if (Store.outgoingRingRid(app) == rid) Store.clearOutgoingRing(app)
                    _state.value = RingState.Idle
                    return@withLock
                }
                val ok = try {
                    // DATA-FLOW: call ring (id + mode), encrypted, to my partner, plus an empty wake ping. No location and no name.
                    Outbox.sendRing(app, rid)
                } catch (e: CancellationException) {
                    if (Store.outgoingRingRid(app) == rid) Store.clearOutgoingRing(app)
                    _state.value = RingState.Idle
                    throw e
                } catch (_: Exception) {
                    false
                }
                if (!ok || Store.outgoingRingRid(app) != rid || System.currentTimeMillis() - since >= RingService.LIMIT_MS) {
                    if (Store.outgoingRingRid(app) == rid) Store.clearOutgoingRing(app)
                    _state.value = RingState.Idle
                    return@withLock
                }
                _state.value = RingState.Ringing(since)
                armTimeout(app, rid, since)
            }
        }
    }

    /**
     * Sends ringstop and returns to [RingState.Idle] when the post is accepted.
     * A failed post returns to [RingState.Ringing] while the two minutes remain.
     */
    fun stopCall(context: Context) {
        val app = context.applicationContext
        ensure(app)
        AppScope.scope.launch {
            gate.withLock {
                val rid = Store.outgoingRingRid(app)
                val since = Store.outgoingRingSince(app)
                if (canonicalRid(rid) == null) {
                    timeoutJob?.cancel()
                    if (rid.isNotEmpty() || since != 0L) Store.clearOutgoingRing(app)
                    _state.value = RingState.Idle
                    return@withLock
                }
                _state.value = RingState.Stopping
                timeoutJob?.cancel()
                val ok = try {
                    // DATA-FLOW: ring stop for this id, encrypted, to my partner, plus an empty wake ping. No location and no name.
                    Outbox.sendRingStop(app, rid)
                } catch (e: CancellationException) {
                    restoreRinging(app, rid, since)
                    throw e
                } catch (_: Exception) {
                    false
                }
                if (Store.outgoingRingRid(app) != rid) {
                    if (_state.value is RingState.Stopping) _state.value = RingState.Idle
                    return@withLock
                }
                if (!ok) {
                    restoreRinging(app, rid, since)
                    return@withLock
                }
                Store.clearOutgoingRing(app)
                _state.value = RingState.Idle
            }
        }
    }

    /**
     * One short notice on the partner's phone. Does not change [state].
     * A second tap within two seconds, or while the first send is still going, is ignored.
     */
    fun sendPing(context: Context) {
        val app = context.applicationContext
        ensure(app)
        if (!Store.isPaired(app)) return
        val now = System.currentTimeMillis()
        if (now - lastPingMs < 2_000L) return
        if (!pinging.compareAndSet(false, true)) return
        lastPingMs = now
        AppScope.scope.launch {
            try {
                // DATA-FLOW: a ping, encrypted, to my partner, plus an empty wake ping. No location, no name, no message text.
                Outbox.sendPing(app)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // The tap can still show its own confirmation. The ping did not leave.
            } finally {
                pinging.set(false)
            }
        }
    }

    /** Partner dismissed the alarm. Ignored when the id is for some other call. */
    internal fun onPartnerAck(context: Context, rid: String) {
        val app = context.applicationContext
        ensure(app)
        val clean = canonicalRid(rid)
        val active = canonicalRid(Store.outgoingRingRid(app)) ?: return
        if (clean != null && clean != active) return
        AppScope.scope.launch {
            gate.withLock {
                val current = canonicalRid(Store.outgoingRingRid(app)) ?: return@withLock
                if (clean != null && clean != current) return@withLock
                timeoutJob?.cancel()
                Store.clearOutgoingRing(app)
                _state.value = RingState.Idle
            }
        }
    }

    /** Drops in-memory sender state. Prefs are already cleared by [Store.unpair]. */
    fun onUnpaired() {
        timeoutJob?.cancel()
        timeoutJob = null
        _state.value = RingState.Idle
        hydrated = true
    }

    private fun restoreRinging(app: Context, rid: String, since: Long) {
        if (Store.outgoingRingRid(app) != rid) {
            _state.value = RingState.Idle
            return
        }
        if (since <= 0L || System.currentTimeMillis() - since >= RingService.LIMIT_MS) {
            Store.clearOutgoingRing(app)
            _state.value = RingState.Idle
            return
        }
        _state.value = RingState.Ringing(since)
        armTimeout(app, rid, since)
    }

    private fun armTimeout(app: Context, rid: String, since: Long) {
        timeoutJob?.cancel()
        val wait = (since + RingService.LIMIT_MS - System.currentTimeMillis()).coerceAtLeast(0L)
        timeoutJob = AppScope.scope.launch {
            delay(wait)
            gate.withLock {
                if (Store.outgoingRingRid(app) != rid) return@withLock
                Store.clearOutgoingRing(app)
                _state.value = RingState.Idle
            }
        }
    }

    private fun newRid(): String {
        val bytes = ByteArray(4)
        random.nextBytes(bytes)
        val digits = "0123456789abcdef"
        val out = CharArray(8)
        for (index in bytes.indices) {
            val b = bytes[index].toInt() and 0xff
            out[index * 2] = digits[b ushr 4]
            out[index * 2 + 1] = digits[b and 0x0f]
        }
        return String(out)
    }
}
