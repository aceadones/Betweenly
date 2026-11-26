package com.nextlayer.betweenly.ui

import android.util.LruCache
import com.nextlayer.betweenly.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import javax.net.ssl.HttpsURLConnection
import kotlin.coroutines.cancellation.CancellationException

/** One Tenor result. Preview is the small GIF. Send is the file we encrypt. */
class TenorGif(val id: String, val previewUrl: String, val sendUrl: String)

/**
 * Tenor search and Google's public emoji-kitchen PNGs.
 * Both stay in memory. Nothing here is written to disk.
 */
object ChatRemote {
    private const val TENOR = "https://tenor.googleapis.com/v2/"
    private const val KITCHEN = "https://www.gstatic.com/android/keyboard/emojikitchen/"
    private val dates = listOf(
        "20201001",
        "20210218",
        "20210521",
        "20211115",
        "20220203",
        "20220406",
        "20220815",
        "20230301",
        "20230418",
    )

    private val lock = Any()
    private val queryCache = object : LinkedHashMap<String, List<TenorGif>>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<TenorGif>>?): Boolean = size > 8
    }
    private val bytes = object : LruCache<String, ByteArray>(6 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ByteArray): Int = value.size.coerceAtLeast(1)
    }
    private val kitchenMiss = LinkedHashSet<String>()

    fun tenorReady(): Boolean = BuildConfig.TENOR_API_KEY.isNotEmpty()

    suspend fun featured(): List<TenorGif> = withContext(Dispatchers.IO) { tenor("featured", "") }

    suspend fun search(raw: String): List<TenorGif> = withContext(Dispatchers.IO) {
        val query = raw.filter { it >= ' ' }.trim().take(40)
        if (query.isEmpty()) tenor("featured", "") else tenor("search", query)
    }

    /**
     * DATA-FLOW: the GIF url from Tenor (a public CDN address, no user or partner id)
     * is fetched as bytes. Those bytes are then sent on the encrypted attachment path.
     */
    suspend fun bytes(url: String, max: Int): ByteArray? = withContext(Dispatchers.IO) {
        synchronized(lock) { bytes.get(url) }?.let { return@withContext it }
        val data = httpsBytes(url, max, "image/gif,image/*,*/*") ?: return@withContext null
        synchronized(lock) { bytes.put(url, data) }
        data
    }

    /**
     * DATA-FLOW: the two emoji codepoints are requested from gstatic. No name,
     * no account, no partner. A hit is cached in memory, then sent as a sticker.
     */
    suspend fun kitchen(left: String, right: String): ByteArray? = withContext(Dispatchers.IO) {
        val a = token(left)
        val b = token(right)
        if (a.isEmpty() || b.isEmpty()) return@withContext null
        val key = "$a|$b"
        synchronized(lock) { bytes.get(key) }?.let { return@withContext it }
        synchronized(lock) { if (key in kitchenMiss) return@withContext null }
        val hit = probe(a, b) ?: probe(b, a)
        synchronized(lock) {
            if (hit == null) {
                kitchenMiss.add(key)
                kitchenMiss.add("$b|$a")
                while (kitchenMiss.size > 240) {
                    val first = kitchenMiss.iterator().next()
                    kitchenMiss.remove(first)
                }
            } else {
                bytes.put(key, hit)
                bytes.put("$b|$a", hit)
            }
        }
        hit
    }

    fun kitchenToken(emoji: String): String = token(emoji)

    private fun tenor(mode: String, query: String): List<TenorGif> {
        val key = BuildConfig.TENOR_API_KEY
        if (key.isEmpty()) return emptyList()
        val cacheKey = "$mode:$query"
        synchronized(lock) { queryCache[cacheKey] }?.let { return it }
        val q = if (query.isEmpty()) "" else "&q=" + URLEncoder.encode(query, "UTF-8")
        // DATA-FLOW: the search word (or none, for trending) goes to Tenor with the app key.
        // Nothing about this person or their partner is included. The JSON is cached in memory only.
        val url = TENOR + mode + "?key=" + URLEncoder.encode(key, "UTF-8") +
            "&client_key=betweenly&limit=24&media_filter=tinygif,gif" + q
        val body = httpsBytes(url, 512 * 1024, "application/json") ?: return emptyList()
        val parsed = parseTenor(body)
        synchronized(lock) { queryCache[cacheKey] = parsed }
        return parsed
    }

    private fun parseTenor(body: ByteArray): List<TenorGif> {
        val root = try {
            JSONObject(String(body, Charsets.UTF_8))
        } catch (_: Exception) {
            return emptyList()
        }
        val results = root.optJSONArray("results") ?: return emptyList()
        val out = ArrayList<TenorGif>(results.length())
        for (i in 0 until results.length()) {
            val item = results.optJSONObject(i) ?: continue
            val formats = item.optJSONObject("media_formats") ?: continue
            val tiny = formats.optJSONObject("tinygif") ?: formats.optJSONObject("gif") ?: continue
            val full = formats.optJSONObject("gif") ?: tiny
            val preview = tiny.optString("url")
            val send = full.optString("url")
            if (!preview.startsWith("https://") || !send.startsWith("https://")) continue
            val id = item.optString("id").ifBlank { preview }
            out.add(TenorGif(id, preview, send))
        }
        return out
    }

    private fun probe(left: String, right: String): ByteArray? {
        for (date in dates) {
            val url = KITCHEN + date + "/" + left + "/" + left + "_" + right + ".png"
            val data = httpsBytes(url, 700 * 1024, "image/png,image/*")
            if (data != null && data.size > 32) return data
        }
        return null
    }

    private fun token(emoji: String): String {
        val parts = ArrayList<String>(4)
        var i = 0
        while (i < emoji.length) {
            val cp = emoji.codePointAt(i)
            i += Character.charCount(cp)
            if (cp == 0xFE0F) continue
            parts.add("u" + Integer.toHexString(cp))
        }
        return parts.joinToString("-")
    }

    private fun httpsBytes(url: String, max: Int, accept: String): ByteArray? {
        if (!url.startsWith("https://")) return null
        val conn = try {
            URL(url).openConnection()
        } catch (_: Exception) {
            return null
        }
        if (conn !is HttpsURLConnection) return null
        return try {
            conn.instanceFollowRedirects = true
            conn.connectTimeout = 8_000
            conn.readTimeout = 12_000
            conn.setRequestProperty("Accept", accept)
            conn.setRequestProperty("User-Agent", "Betweenly")
            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) return null
            if (conn.url?.protocol != "https") return null
            val declared = conn.contentLengthLong
            if (declared > max) return null
            val stream = conn.inputStream ?: return null
            stream.use { input ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(8 * 1024)
                var total = 0
                while (total < max) {
                    val n = input.read(buf, 0, minOf(buf.size, max - total))
                    if (n < 0) break
                    out.write(buf, 0, n)
                    total += n
                }
                val extra = if (total >= max) input.read() else -1
                if (extra >= 0) null else out.toByteArray().takeIf { it.isNotEmpty() }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } finally {
            conn.disconnect()
        }
    }
}

/** Emoji that show up in the kitchen and the sticker tab. Classic set, no network. */
internal val chatEmoji: List<String> = listOf(
    "😀", "😃", "😄", "😁", "😆", "😅", "😂", "🤣", "🙂", "😉",
    "😊", "😍", "😘", "😗", "😋", "😜", "🤪", "🤗", "🤔", "😐",
    "😏", "🙄", "😌", "😴", "😷", "🤠", "😎", "🤓", "😮", "😲",
    "😳", "🥺", "😢", "😭", "😱", "😤", "😡", "🤯", "👍", "👎",
    "👏", "🙏", "👋", "💪", "✌️", "🤞", "👌", "🔥", "✨", "🎉",
    "💯", "⭐", "❤️", "🧡", "💛", "💚", "💙", "💜", "🖤", "💔",
    "💕", "🌹", "👀", "💀", "👻", "💩", "😺", "😸", "😻", "🙈",
)
