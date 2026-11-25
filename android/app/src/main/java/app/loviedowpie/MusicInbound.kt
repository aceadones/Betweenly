package app.loviedowpie

import android.content.Context
import app.loviedowpie.music.MusicBridge
import org.json.JSONObject

/**
 * Listening-together messages (`t` = music).
 *
 * This does not start a player, a thread, or a poll. [Inbox] has already
 * decrypted the blob. Playback exists only while the music screen is open.
 *
 * DATA-FLOW: none here. Sending is in MusicSession. Titles, keys, and
 * positions are not written to a log.
 */
object MusicInbound {
    fun handle(context: Context, json: JSONObject) {
        try {
            if (json.optString("t") != "music" || !Store.isPaired(context)) return
            MusicBridge.dispatch(context, json)
        } catch (_: Exception) {
            // A bad music payload must not crash the inbox drain.
        }
    }
}
