package com.nextlayer.betweenly

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject

/**
 * The live "typing" signal. Sending is one tiny encrypted message at most every
 * 3 seconds while someone types, plus one when they stop. Receiving only sets a
 * short timer that shows three dots. Nothing is stored.
 *
 * DATA-FLOW: `{"t":"typing","on":bool}`, encrypted like any message, to the partner via the relay.
 * It never wakes the other phone (no FCM ping) and is skipped when "Hide activity status" is on.
 */
object Typing {
    private const val HOLD_MS = 6_000L

    private val until = MutableStateFlow(0L)

    /** When the partner's dots should disappear. In the past means not typing. */
    val partnerUntil: StateFlow<Long> get() = until

    /** Last time any typing signal arrived. Lets the chat poll faster during a live exchange. */
    @Volatile
    var lastIncoming: Long = 0L

    @Volatile
    var lastSentOn: Long = 0L

    @Volatile
    var sentOn: Boolean = false

    fun onIncoming(json: JSONObject) {
        val now = System.currentTimeMillis()
        lastIncoming = now
        until.value = if (json.optBoolean("on", false)) now + HOLD_MS else 0L
    }

    fun message(on: Boolean): JSONObject = JSONObject().put("t", "typing").put("on", on)

    fun clear() {
        until.value = 0L
    }
}
