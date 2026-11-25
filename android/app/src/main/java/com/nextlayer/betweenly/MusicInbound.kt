package com.nextlayer.betweenly

import android.content.Context
import com.nextlayer.betweenly.music.MusicBridge
import com.nextlayer.betweenly.music.MusicLibrary
import org.json.JSONObject

/**
 * Listening-together messages (`t` = music) and shared playlists (`t` = plist).
 *
 * This does not start a player, a thread, or a poll. [Inbox] has already
 * decrypted the blob. Playback exists only while the music screen is open.
 * A playlist edit is written to the local database and, if the screen is
 * open, the list refreshes. Nothing here starts audio.
 *
 * DATA-FLOW: none here. Sending is in MusicSession. Titles, keys, and
 * positions are not written to a log.
 */
object MusicInbound {
    fun handle(context: Context, json: JSONObject) {
        try {
            if (!Store.isPaired(context)) return
            when (json.optString("t")) {
                "music" -> MusicBridge.dispatch(context, json)
                "plist" -> if (MusicLibrary.applyRemote(context, json)) {
                    MusicBridge.notifyLibrary(context)
                }
            }
        } catch (_: Exception) {
            // A bad music payload must not crash the inbox drain.
        }
    }
}
