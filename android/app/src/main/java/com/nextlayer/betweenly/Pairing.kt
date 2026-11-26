package com.nextlayer.betweenly

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException

/**
 * Outcome of [Pairing.startAsInitiator] or [Pairing.joinWithCode].
 * [Success.safetyNumber] is the short number both phones should show the same.
 */
sealed class PairingResult {
    data class Success(val safetyNumber: String) : PairingResult()

    /** The first message could not be opened, so the two phones do not share a key. */
    data object CodesDidNotMatch : PairingResult()

    data object TimedOut : PairingResult()
    data object Cancelled : PairingResult()
    data object InvalidCode : PairingResult()
    data class Failed(val message: String) : PairingResult()
}

/**
 * Pairs this phone with one other. The initiator shows a code; the joiner types it.
 * The code is never sent. Both phones run SPAKE2 and exchange the handshake
 * through the relay under a one-way id derived from the code.
 *
 * A wrong code usually means the two phones wait on different rendezvous ids and
 * this returns [PairingResult.TimedOut]. SPAKE2 can also finish with mismatched
 * keys (a substituted handshake). That is detected when the partner's first
 * profile message does not open, and reported as [PairingResult.CodesDidNotMatch].
 * Show [Success.safetyNumber] and ask both people to read it aloud.
 *
 * Call [cancel] to stop a running attempt. It returns within about 100ms.
 */
object Pairing {
    private const val WAIT_MS = 10L * 60L * 1000L
    private const val POLL_MS = 2_000L
    private const val CONFIRM_MS = 20_000L

    private val running = AtomicBoolean(false)
    private val cancelled = AtomicBoolean(false)

    fun cancel() {
        cancelled.set(true)
    }

    /**
     * Generates a code, shows it through [onCode], publishes this phone's PAKE
     * message as role "a", and waits up to 10 minutes for the joiner.
     */
    suspend fun startAsInitiator(context: Context, onCode: (String) -> Unit): PairingResult {
        if (!running.compareAndSet(false, true)) {
            return PairingResult.Failed("Already pairing")
        }
        cancelled.set(false)
        return try {
            val code = Core.generateCode()
            if (code.isEmpty()) return PairingResult.Failed("Could not make a code")
            withContext(Dispatchers.Main.immediate) { onCode(code) }
            run(context.applicationContext, code, role = "a", initiator = true)
        } catch (e: CancellationException) {
            throw e
        } finally {
            running.set(false)
            cancelled.set(false)
        }
    }

    /** Joins a code shown on the other phone. Role "b". */
    suspend fun joinWithCode(context: Context, code: String): PairingResult {
        if (!running.compareAndSet(false, true)) {
            return PairingResult.Failed("Already pairing")
        }
        cancelled.set(false)
        return try {
            val canonical = Core.normalizeCode(code)
            if (canonical.isEmpty()) return PairingResult.InvalidCode
            run(context.applicationContext, canonical, role = "b", initiator = false)
        } catch (e: CancellationException) {
            throw e
        } finally {
            running.set(false)
            cancelled.set(false)
        }
    }

    private suspend fun run(
        context: Context,
        code: String,
        role: String,
        initiator: Boolean,
    ): PairingResult {
        val rv = Core.rendezvousHex(code)
        if (rv.length != 32) return PairingResult.InvalidCode
        val started = Core.pairStart(code)
        if (started.size <= 8) return PairingResult.Failed("Pairing failed")
        val handle = readHandle(started)
        var finished = false
        return try {
            val mine = started.copyOfRange(8, started.size)
            val mineB64 = Base64.encodeToString(mine, Base64.NO_WRAP)
            // DATA-FLOW: my PAKE message, to the relay, not the pairing code.
            if (!Relay.pairPut(rv, role, mineB64)) {
                return PairingResult.Failed("Could not reach the relay")
            }
            val theirsB64 = poll(rv, role) ?: return if (cancelled.get()) {
                PairingResult.Cancelled
            } else {
                PairingResult.TimedOut
            }
            val theirs = try {
                Base64.decode(theirsB64, Base64.DEFAULT)
            } catch (_: IllegalArgumentException) {
                return PairingResult.Failed("Pairing failed")
            }
            val session = Core.pairFinish(handle, theirs, initiator)
            finished = true
            if (session.size != Core.SESSION_LEN) return PairingResult.Failed("Pairing failed")
            val safety = Core.sessionInfo(session, 0)
            confirm(context, session, safety)
        } catch (e: PairingBurnedException) {
            PairingResult.Failed("This code was tried too many times. Make a new one.")
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            PairingResult.Failed("Could not reach the relay")
        } finally {
            if (!finished) discard(handle)
        }
    }

    /**
     * GET uses the same role we PUT. The relay returns the other side's message
     * for that URL. Polling the opposite role would return our own handshake.
     */
    private suspend fun poll(rv: String, role: String): String? {
        val deadline = System.currentTimeMillis() + WAIT_MS
        while (System.currentTimeMillis() < deadline) {
            currentCoroutineContext().ensureActive()
            if (cancelled.get()) return null
            val started = System.currentTimeMillis()
            // DATA-FLOW: the other phone's PAKE message, from the relay, not encrypted.
            val msg = Relay.pairGet(rv, role)
            if (msg != null) return msg
            val spent = System.currentTimeMillis() - started
            if (!pause(POLL_MS - spent)) return null
        }
        return null
    }

    private suspend fun confirm(context: Context, session: ByteArray, safety: String): PairingResult {
        Inbox.holdForPairing = true
        var committed = false
        try {
            val profile = Msg.profile(Store.myName(context), Store.myAvatar(context))
            // DATA-FLOW: my profile, encrypted, to my partner, so they can open it and confirm the key.
            Outbox.installAndSend(context, session, profile, wake = true)
            if (!Store.isPaired(context)) return PairingResult.Failed("Could not save the session")
            committed = true
            val deadline = System.currentTimeMillis() + CONFIRM_MS
            while (System.currentTimeMillis() < deadline) {
                currentCoroutineContext().ensureActive()
                if (cancelled.get()) {
                    Store.unpair(context)
                    return PairingResult.Cancelled
                }
                when (Inbox.confirmPartner(context)) {
                    InboxOutcome.OpenFailed -> {
                        Store.unpair(context)
                        return PairingResult.CodesDidNotMatch
                    }
                    InboxOutcome.Applied -> return succeed(context, safety)
                    InboxOutcome.Empty -> if (!pause(POLL_MS)) {
                        Store.unpair(context)
                        return PairingResult.Cancelled
                    }
                }
            }
            return when (Inbox.confirmPartner(context)) {
                InboxOutcome.OpenFailed -> {
                    Store.unpair(context)
                    PairingResult.CodesDidNotMatch
                }
                else -> succeed(context, safety)
            }
        } catch (e: CancellationException) {
            if (committed) Store.unpair(context)
            throw e
        } finally {
            Inbox.holdForPairing = false
        }
    }

    private suspend fun succeed(context: Context, safety: String): PairingResult {
        registerToken(context)
        if (Store.isPaired(context) && Store.shareOn(context)) {
            if (Store.liveOn(context)) {
                LocationSharing.setLive(context, true)
            } else {
                SharingScheduler.schedule(context)
                AppScope.scope.launch { LocationSender.sendOnce(context) }
            }
        }
        if (Store.isPaired(context)) BatteryUpdates.arm(context)
        return PairingResult.Success(safety)
    }

    private suspend fun registerToken(context: Context) {
        // DATA-FLOW: FCM registration token, from Google if configured, then to the relay under my inbox id.
        val fetched = if (Fcm.enabled) withTimeoutOrNull(8_000) { Fcm.fetchToken() } else null
        val token = fetched?.takeIf { it.length in 20..400 } ?: Store.fcmToken(context)
        if (token.length !in 20..400) return
        Store.setFcmToken(context, token)
        val inbox = Store.inboxId(context)
        if (inbox.length == 32) Relay.tokenPut(inbox, token)
    }

    /** Returns false when the user cancelled. Wakes within about 100ms. */
    private suspend fun pause(ms: Long): Boolean {
        var left = ms
        while (left > 0) {
            currentCoroutineContext().ensureActive()
            if (cancelled.get()) return false
            val slice = minOf(100L, left)
            delay(slice)
            left -= slice
        }
        return !cancelled.get()
    }

    private fun readHandle(bytes: ByteArray): Long {
        var value = 0L
        for (i in 0 until 8) {
            value = (value shl 8) or (bytes[i].toLong() and 0xffL)
        }
        return value
    }

    /** Drops the in-memory SPAKE2 state the core is holding for [handle]. */
    private fun discard(handle: Long) {
        try {
            Core.pairFinish(handle, ByteArray(0), false)
        } catch (_: Exception) {
            // Already removed, or the core rejected the empty message after removing it.
        }
    }
}
