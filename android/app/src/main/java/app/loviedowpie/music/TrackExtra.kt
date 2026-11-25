package app.loviedowpie.music

import org.json.JSONObject

/**
 * The [Track.extra] JSON. Local playback uses `uri`. A shared copy uses `fid`
 * and `key` (the key never travels outside the sealed music message).
 *
 * DATA-FLOW: none. This only reads strings already on this phone.
 */
internal object TrackExtra {
    fun local(uri: String, bytes: Long): String {
        val o = JSONObject()
        o.put("uri", uri)
        if (bytes >= 0L) o.put("bytes", bytes)
        return o.toString()
    }

    fun withFile(extra: String, fid: String, key: String): String {
        val o = parse(extra)
        o.put("fid", fid.lowercase())
        o.put("key", key)
        return o.toString()
    }

    /** Extra sent to the partner: file id and key only. The content URI stays on this phone. */
    fun forWire(extra: String): String {
        val fid = fid(extra) ?: return ""
        val key = key(extra) ?: return ""
        return JSONObject().put("fid", fid).put("key", key).toString()
    }

    fun parse(extra: String): JSONObject {
        if (extra.isBlank() || extra.length > 4_000) return JSONObject()
        return try {
            JSONObject(extra)
        } catch (_: Exception) {
            JSONObject()
        }
    }

    fun uri(extra: String): String? {
        val value = parse(extra).optString("uri")
        if (!value.startsWith("content://media/")) return null
        return value
    }

    fun fid(extra: String): String? {
        val value = parse(extra).optString("fid").lowercase()
        return value.takeIf { isFid(it) }
    }

    fun key(extra: String): String? {
        val value = parse(extra).optString("key")
        if (value.length !in 16..200) return null
        if (value.any { it == '\n' || it == '\r' || it == ' ' }) return null
        return value
    }

    fun bytes(extra: String): Long = parse(extra).optLong("bytes", -1L)

    fun isFid(value: String): Boolean =
        value.length == 32 && value.all { it in '0'..'9' || it in 'a'..'f' }
}
