package com.nextlayer.betweenly.music

import org.json.JSONArray
import org.json.JSONObject

/**
 * A shared playlist edit inside the sealed envelope.
 *
 * ```
 * {"t":"plist","op":"add","id":"...","name":"...","tracks":[...],"ts":0}
 * ```
 *
 * `op` is create, rename, add, remove, delete, or snapshot.
 * Each track carries its own `ts`. The newer timestamp wins for that track.
 *
 * DATA-FLOW: none. Bytes are sealed later by [com.nextlayer.betweenly.Outbox].
 */
internal object PlaylistWire {
    val OPS = setOf("create", "rename", "add", "remove", "delete", "snapshot")

    data class Item(val track: Track, val ts: Long)

    fun create(id: String, name: String, ts: Long): JSONObject =
        base("create", id, name, ts, emptyList())

    fun rename(id: String, name: String, ts: Long): JSONObject =
        base("rename", id, name, ts, emptyList())

    fun delete(id: String, ts: Long): JSONObject =
        base("delete", id, "", ts, emptyList())

    fun add(id: String, track: Track, ts: Long): JSONObject =
        base("add", id, "", ts, listOf(Item(track, ts)))

    fun remove(id: String, track: Track, ts: Long): JSONObject =
        base("remove", id, "", ts, listOf(Item(track, ts)))

    fun snapshot(id: String, name: String, tracks: List<Track>, ts: Long): JSONObject =
        base("snapshot", id, name, ts, tracks.map { Item(it, ts) })

    fun items(json: JSONObject): List<Item> {
        val arr = json.optJSONArray("tracks") ?: return emptyList()
        val out = ArrayList<Item>(arr.length().coerceAtMost(MAX))
        val n = minOf(arr.length(), MAX)
        for (i in 0 until n) {
            val obj = arr.optJSONObject(i) ?: continue
            val track = track(obj) ?: continue
            val ts = obj.optLong("ts").takeIf { it > 0L } ?: json.optLong("ts")
            if (ts <= 0L) continue
            out.add(Item(track, ts))
        }
        return out
    }

    /** Drops per-track extras so a large snapshot still fits the mailbox. */
    fun stripExtras(json: JSONObject): JSONObject {
        val copy = JSONObject(json.toString())
        val arr = copy.optJSONArray("tracks") ?: return copy
        for (i in 0 until arr.length()) {
            arr.optJSONObject(i)?.put("extra", "")
        }
        return copy
    }

    private fun base(op: String, id: String, name: String, ts: Long, tracks: List<Item>): JSONObject {
        val json = JSONObject()
        json.put("t", "plist")
        json.put("op", if (op in OPS) op else "snapshot")
        json.put("id", clip(id.lowercase(), 32))
        if (name.isNotBlank()) json.put("name", clip(name, 80))
        json.put("ts", ts)
        if (tracks.isNotEmpty()) {
            val arr = JSONArray()
            for (item in tracks.take(MAX)) {
                arr.put(trackJson(item.track, item.ts))
            }
            json.put("tracks", arr)
        }
        return json
    }

    private fun track(obj: JSONObject): Track? {
        val id = clip(obj.optString("id"), 128)
        val source = clip(obj.optString("src"), 32)
        if (id.isEmpty() || source.isEmpty()) return null
        return Track(
            id = id,
            title = clip(obj.optString("title"), 120),
            artist = clip(obj.optString("artist"), 80),
            durationMs = obj.optLong("dur").coerceAtLeast(0L),
            sourceId = source,
            extra = obj.optString("extra").take(2_000),
        )
    }

    private fun trackJson(track: Track, ts: Long): JSONObject =
        JSONObject()
            .put("id", clip(track.id, 128))
            .put("title", clip(track.title, 120))
            .put("artist", clip(track.artist, 80))
            .put("dur", track.durationMs.coerceAtLeast(0L))
            .put("src", clip(track.sourceId, 32))
            .put("extra", TrackExtra.forWire(track.extra))
            .put("ts", ts)

    private fun clip(value: String, max: Int): String {
        val clean = value.replace('\n', ' ').replace('\r', ' ').trim()
        return if (clean.length <= max) clean else clean.substring(0, max)
    }

    private const val MAX = 80
}
