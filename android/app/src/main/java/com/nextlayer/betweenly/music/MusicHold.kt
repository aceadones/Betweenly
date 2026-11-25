package com.nextlayer.betweenly.music

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * The latest listen-together invite, kept so it is still there if this process
 * was woken by the empty ping and then the screen opens.
 * Nothing here starts playback. Invites older than 10 minutes are dropped.
 *
 * DATA-FLOW: none. The file is app-private. It is not uploaded.
 */
internal object MusicHold {
    private const val MAX_AGE_MS = 10 * 60_000L
    private val lock = Any()
    private var memory: Pair<String, Long>? = null

    fun save(context: Context, json: JSONObject) {
        if (json.optString("op") != "invite") return
        val text = try {
            json.toString()
        } catch (_: Exception) {
            return
        }
        if (text.length > 65_000) return
        val at = System.currentTimeMillis()
        synchronized(lock) { memory = text to at }
        try {
            val dir = folder(context)
            if (!dir.exists()) dir.mkdirs()
            File(dir, "invite.json").writeText(at.toString() + "\n" + text)
        } catch (_: Exception) {
            // The in-memory copy is enough while this process stays up.
        }
    }

    fun peek(context: Context): JSONObject? {
        val held = synchronized(lock) { memory } ?: read(context) ?: return null
        if (System.currentTimeMillis() - held.second > MAX_AGE_MS) {
            clear(context)
            return null
        }
        return try {
            JSONObject(held.first)
        } catch (_: Exception) {
            null
        }
    }

    fun clear(context: Context) {
        synchronized(lock) { memory = null }
        try {
            File(folder(context), "invite.json").delete()
        } catch (_: Exception) {
            // Already gone.
        }
    }

    private fun read(context: Context): Pair<String, Long>? {
        return try {
            val file = File(folder(context), "invite.json")
            if (!file.isFile) return null
            val text = file.readText()
            val split = text.indexOf('\n')
            if (split <= 0 || text.length > 66_000) return null
            val at = text.substring(0, split).toLongOrNull() ?: return null
            val body = text.substring(split + 1)
            val pair = body to at
            synchronized(lock) { if (memory == null) memory = pair }
            pair
        } catch (_: Exception) {
            null
        }
    }

    private fun folder(context: Context): File = File(context.applicationContext.filesDir, "music")
}
