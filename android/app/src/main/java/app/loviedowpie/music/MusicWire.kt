package app.loviedowpie.music

import org.json.JSONArray
import org.json.JSONObject

/**
 * The `music` JSON inside the sealed envelope.
 *
 * ```
 * {"t":"music","op":"play","ts":0,"pos":0,"track":{...},"queue":[...]}
 * ```
 *
 * `echo` is optional. Accept sends the invite's `ts` so the inviter can
 * estimate the clock offset from the round trip.
 *
 * DATA-FLOW: none. Bytes are sealed later by [app.loviedowpie.Outbox].
 */
internal object MusicWire {
    private val OPS = setOf(
        "invite", "accept", "leave", "queue", "track", "play", "pause", "seek", "sync",
    )

    fun build(
        op: String,
        ts: Long,
        pos: Long,
        track: Track?,
        queue: List<Track>,
        echo: Long = 0L,
    ): JSONObject {
        val json = JSONObject()
        json.put("t", "music")
        json.put("op", if (op in OPS) op else "sync")
        json.put("ts", ts)
        json.put("pos", pos.coerceAtLeast(0L))
        if (echo > 0L) json.put("echo", echo)
        if (track != null) json.put("track", trackJson(track))
        val arr = JSONArray()
        for (item in queue.take(20)) arr.put(trackJson(item))
        if (arr.length() > 0) json.put("queue", arr)
        return json
    }

    fun track(obj: JSONObject?): Track? {
        if (obj == null) return null
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

    fun queue(json: JSONObject): List<Track> {
        val arr = json.optJSONArray("queue") ?: return emptyList()
        val out = ArrayList<Track>(arr.length().coerceAtMost(20))
        val n = minOf(arr.length(), 20)
        for (i in 0 until n) {
            val item = track(arr.optJSONObject(i)) ?: continue
            out.add(item)
        }
        return out
    }

    private fun trackJson(track: Track): JSONObject =
        JSONObject()
            .put("id", clip(track.id, 128))
            .put("title", clip(track.title, 120))
            .put("artist", clip(track.artist, 80))
            .put("dur", track.durationMs.coerceAtLeast(0L))
            .put("src", clip(track.sourceId, 32))
            .put("extra", TrackExtra.forWire(track.extra))

    private fun clip(value: String, max: Int): String {
        val clean = value.replace('\n', ' ').replace('\r', ' ').trim()
        return if (clean.length <= max) clean else clean.substring(0, max)
    }
}
