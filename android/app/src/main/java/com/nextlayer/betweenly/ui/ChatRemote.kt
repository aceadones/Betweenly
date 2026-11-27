package com.nextlayer.betweenly.ui

import android.content.Context
import android.util.LruCache
import com.nextlayer.betweenly.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID
import javax.net.ssl.HttpsURLConnection
import kotlin.coroutines.cancellation.CancellationException

/** One GIF. [previewUrl] is the grid thumb. [sendUrl] is the file we encrypt. */
class GifHit(val id: String, val previewUrl: String, val sendUrl: String)

/** One page from a GIF search. [hasNext] is false when the provider says to stop. */
class GifPage(val hits: List<GifHit>, val hasNext: Boolean)

/**
 * GIF search. The panel asks for a page. Bytes stay in memory.
 * Nothing here is written to disk.
 */
interface GifProvider {
    fun ready(): Boolean

    /** Null when the request failed. An empty page is a real answer. */
    suspend fun load(context: Context, query: String, page: Int): GifPage?

    suspend fun bytes(url: String, max: Int): ByteArray?
}

/**
 * KLIPY GIF search. Tenor's public API shut down on 30 June 2026.
 * The key comes from [BuildConfig.KLIPY_API_KEY] (`klipy.apiKey` in local.properties).
 */
class KlipyProvider : GifProvider {
    private val lock = Any()
    private val pages = object : LinkedHashMap<String, GifPage>(12, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, GifPage>?): Boolean = size > 12
    }
    private val bytes = object : LruCache<String, ByteArray>(6 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ByteArray): Int = value.size.coerceAtLeast(1)
    }

    override fun ready(): Boolean = BuildConfig.KLIPY_API_KEY.isNotEmpty()

    override suspend fun load(context: Context, query: String, page: Int): GifPage? = withContext(Dispatchers.IO) {
        val key = BuildConfig.KLIPY_API_KEY
        if (key.isEmpty() || !key.matches(KEY)) return@withContext GifPage(emptyList(), false)
        val term = query.filter { it >= ' ' }.trim().take(40)
        val index = page.coerceAtLeast(1)
        val cacheKey = "$term#$index"
        synchronized(lock) { pages[cacheKey] }?.let { return@withContext it }
        val path = if (term.isEmpty()) "gifs/trending" else "gifs/search"
        val q = if (term.isEmpty()) "" else "&q=" + URLEncoder.encode(term, "UTF-8")
        val customer = URLEncoder.encode(customerId(context), "UTF-8")
        // DATA-FLOW: the search term (empty for trending) and a random per-install customer id
        // go to api.klipy.com. The id is not the partner, not an account, and not a message.
        // GIF bytes are downloaded later from KLIPY's CDN. Nothing about the chat is sent.
        val url = "https://api.klipy.com/api/v1/" + key + "/" + path +
            "?page=" + index + "&per_page=24&customer_id=" + customer + q
        val body = httpsBytes(url, 512 * 1024, "application/json") ?: return@withContext null
        val parsed = parse(body)
        synchronized(lock) { pages[cacheKey] = parsed }
        parsed
    }

    /**
     * DATA-FLOW: a KLIPY CDN url (no user or partner id) is fetched as bytes.
     * Those bytes are cached in memory, then sent on the encrypted attachment path.
     */
    override suspend fun bytes(url: String, max: Int): ByteArray? = withContext(Dispatchers.IO) {
        synchronized(lock) { bytes.get(url) }?.let { return@withContext it }
        val data = httpsBytes(url, max, "image/gif,image/webp,image/*,*/*") ?: return@withContext null
        synchronized(lock) { bytes.put(url, data) }
        data
    }

    private fun parse(body: ByteArray): GifPage {
        val root = try {
            JSONObject(String(body, Charsets.UTF_8))
        } catch (_: Exception) {
            return GifPage(emptyList(), false)
        }
        val data = root.optJSONObject("data") ?: return GifPage(emptyList(), false)
        val list = data.optJSONArray("data")
        val hasNext = data.optBoolean("has_next", false)
        if (list == null) return GifPage(emptyList(), hasNext)
        val out = ArrayList<GifHit>(list.length())
        for (i in 0 until list.length()) {
            val item = list.optJSONObject(i) ?: continue
            val file = item.optJSONObject("file") ?: continue
            val preview = fileUrl(file, "sm") ?: fileUrl(file, "xs") ?: continue
            val send = fileUrl(file, "md") ?: fileUrl(file, "hd") ?: preview
            if (!preview.startsWith("https://") || !send.startsWith("https://")) continue
            val id = when (val raw = item.opt("id")) {
                null, JSONObject.NULL -> ""
                else -> raw.toString()
            }.ifBlank { item.optString("slug") }.ifBlank { preview }
            out.add(GifHit(id, preview, send))
        }
        return GifPage(out, hasNext)
    }

    /** Prefer gif, then webp, inside one size bucket. Missing buckets are skipped. */
    private fun fileUrl(file: JSONObject, size: String): String? {
        val bucket = file.optJSONObject(size) ?: return null
        val gif = bucket.optJSONObject("gif")?.optString("url").orEmpty()
        if (gif.startsWith("https://")) return gif
        val webp = bucket.optJSONObject("webp")?.optString("url").orEmpty()
        if (webp.startsWith("https://")) return webp
        return null
    }

    /**
     * Random id for this install. Stored outside the pair prefs so unpair does
     * not mint a new one, and it is never copied from a name, key, or message.
     */
    private fun customerId(context: Context): String {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val saved = prefs.getString(ID, null)
        if (!saved.isNullOrBlank()) return saved
        val fresh = UUID.randomUUID().toString()
        prefs.edit().putString(ID, fresh).commit()
        return prefs.getString(ID, null) ?: fresh
    }

    private companion object {
        const val PREFS = "gif"
        const val ID = "id"
        val KEY = Regex("[A-Za-z0-9_\\-]+")
    }
}

object ChatRemote {
    val gifs: GifProvider = KlipyProvider()
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
