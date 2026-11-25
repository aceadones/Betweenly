package com.nextlayer.betweenly

import com.nextlayer.betweenly.ui.ChatMedia
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import kotlin.coroutines.cancellation.CancellationException

/**
 * Title, description, and a small JPEG for one https link.
 * [jpeg] is null when the page has no usable image.
 */
class LinkCard(
    val url: String,
    val title: String,
    val description: String,
    val jpeg: ByteArray?,
)

/**
 * Builds a link card on the sender's phone. The receiver never contacts the site.
 *
 * DATA-FLOW: an HTTPS fetch of a page the sender is sharing, and of its
 * og:image. Only this phone's IP address touches that website. The partner
 * receives the title, description, and a small JPEG inside the encrypted message.
 */
object LinkPreview {
    private const val TIMEOUT_MS = 5_000
    private const val MAX_HTML = 512 * 1024
    private const val MAX_IMAGE = 2 * 1024 * 1024
    private const val MAX_REDIRECTS = 3
    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    private val urlPattern = Regex("""https://[^\s<>"']{1,2000}""", RegexOption.IGNORE_CASE)

    /** First https URL in [text], without a trailing punctuation mark, or null. */
    fun firstHttpsUrl(text: String): String? {
        val match = urlPattern.find(text) ?: return null
        var url = match.value
        while (url.length > "https://".length && url.last() in ".,);:]}>\"'") {
            url = url.dropLast(1)
        }
        if (!url.startsWith("https://", ignoreCase = true) || url.length <= "https://".length + 3) {
            return null
        }
        return url
    }

    suspend fun fetch(pageUrl: String): LinkCard? = withContext(Dispatchers.IO) {
        val start = firstHttpsUrl(pageUrl) ?: pageUrl.trim()
        if (!start.startsWith("https://", ignoreCase = true)) return@withContext null
        try {
            // DATA-FLOW: sender's phone fetches this https page (HTML only, 512 KB cap) to build a link card. The partner does not contact this site.
            val page = get(start, MAX_HTML, image = false) ?: return@withContext null
            val html = try {
                String(page.body, charset(page.charset))
            } catch (_: Exception) {
                String(page.body, Charsets.UTF_8)
            }
            val title = clean(meta(html, "og:title") ?: titleTag(html) ?: "")
            val description = clean(meta(html, "og:description") ?: "")
            val imageRef = meta(html, "og:image")
            var jpeg: ByteArray? = null
            if (!imageRef.isNullOrBlank()) {
                val imageUrl = try {
                    URL(URL(page.finalUrl), imageRef.trim()).toString()
                } catch (_: Exception) {
                    ""
                }
                if (imageUrl.startsWith("https://", ignoreCase = true)) {
                    // DATA-FLOW: sender's phone fetches the og:image over HTTPS. The partner receives only a small JPEG inside the encrypted message.
                    val image = get(imageUrl, MAX_IMAGE, image = true)
                    if (image != null && image.body.isNotEmpty()) {
                        jpeg = ChatMedia.fitJpeg(image.body, 320, 30 * 1024)
                    }
                }
            }
            LinkCard(
                url = start,
                title = title.take(180),
                description = description.take(300),
                jpeg = jpeg,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } catch (_: OutOfMemoryError) {
            null
        }
    }

    private class Body(val finalUrl: String, val body: ByteArray, val charset: String)

    private fun get(start: String, maxBytes: Int, image: Boolean): Body? {
        var current = start
        var hops = 0
        while (hops <= MAX_REDIRECTS) {
            if (!current.startsWith("https://", ignoreCase = true)) return null
            val conn = open(current) ?: return null
            try {
                val code = conn.responseCode
                if (code in 300..399) {
                    if (hops == MAX_REDIRECTS) return null
                    val location = conn.getHeaderField("Location") ?: return null
                    current = try {
                        URL(URL(current), location.trim()).toString()
                    } catch (_: Exception) {
                        return null
                    }
                    hops++
                    continue
                }
                if (code !in 200..299) return null
                val declared = conn.contentLengthLong
                if (image && declared > maxBytes) return null
                val bytes = readCap(conn, maxBytes) ?: return null
                val charset = charsetOf(conn.contentType)
                return Body(current, bytes, charset)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return null
            } finally {
                conn.disconnect()
            }
        }
        return null
    }

    private fun open(url: String): HttpsURLConnection? {
        return try {
            val connection = URL(url).openConnection()
            if (connection !is HttpsURLConnection) return null
            connection.instanceFollowRedirects = false
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.setRequestProperty("Accept", "text/html,application/xhtml+xml,image/*,*/*;q=0.8")
            connection.setRequestProperty("Accept-Language", "en")
            connection
        } catch (_: Exception) {
            null
        }
    }

    private fun readCap(conn: HttpURLConnection, max: Int): ByteArray? {
        val stream = try {
            conn.inputStream
        } catch (_: Exception) {
            null
        } ?: return null
        return stream.use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(8192)
            var total = 0
            while (total < max) {
                val n = input.read(buf, 0, minOf(buf.size, max - total))
                if (n < 0) break
                out.write(buf, 0, n)
                total += n
            }
            if (total == 0) null else out.toByteArray()
        }
    }

    private fun charsetOf(contentType: String?): String {
        if (contentType.isNullOrBlank()) return "UTF-8"
        val parts = contentType.split(';')
        for (part in parts) {
            val trimmed = part.trim()
            if (trimmed.startsWith("charset=", ignoreCase = true)) {
                val name = trimmed.substringAfter('=').trim().trim('"')
                if (name.equals("utf-8", true) || name.equals("utf8", true)) return "UTF-8"
                if (name.equals("iso-8859-1", true) || name.equals("latin1", true)) return "ISO-8859-1"
                if (name.equals("windows-1252", true)) return "windows-1252"
            }
        }
        return "UTF-8"
    }

    private fun meta(html: String, property: String): String? {
        val lower = html.lowercase()
        var from = 0
        while (from < lower.length) {
            val start = lower.indexOf("<meta", from)
            if (start < 0) return null
            val end = lower.indexOf('>', start)
            if (end < 0) return null
            val tag = html.substring(start, end + 1)
            val tagLower = lower.substring(start, end + 1)
            val isOg = tagLower.contains("property=\"$property\"") ||
                tagLower.contains("property='$property'") ||
                tagLower.contains("name=\"$property\"") ||
                tagLower.contains("name='$property'")
            if (isOg) {
                val content = attr(tag, "content")
                if (!content.isNullOrBlank()) return decodeEntities(content)
            }
            from = end + 1
        }
        return null
    }

    private fun titleTag(html: String): String? {
        val lower = html.lowercase()
        val start = lower.indexOf("<title")
        if (start < 0) return null
        val open = lower.indexOf('>', start)
        if (open < 0) return null
        val end = lower.indexOf("</title", open)
        if (end < 0) return null
        return decodeEntities(html.substring(open + 1, end))
    }

    private fun attr(tag: String, name: String): String? {
        val lower = tag.lowercase()
        val key = name.lowercase()
        var from = 0
        while (from < lower.length) {
            val at = lower.indexOf(key, from)
            if (at < 0) return null
            val beforeOk = at == 0 || !lower[at - 1].isLetterOrDigit()
            var i = at + key.length
            if (beforeOk && i <= tag.length) {
                while (i < tag.length && tag[i].isWhitespace()) i++
                if (i < tag.length && tag[i] == '=') {
                    i++
                    while (i < tag.length && tag[i].isWhitespace()) i++
                    if (i < tag.length && (tag[i] == '"' || tag[i] == '\'')) {
                        val quote = tag[i]
                        val valueStart = i + 1
                        val valueEnd = tag.indexOf(quote, valueStart)
                        if (valueEnd > valueStart) return tag.substring(valueStart, valueEnd)
                    }
                }
            }
            from = at + key.length
        }
        return null
    }

    private fun decodeEntities(text: String): String {
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            if (text[i] != '&') {
                out.append(text[i])
                i++
                continue
            }
            val semi = text.indexOf(';', i + 1)
            if (semi < 0 || semi - i > 12) {
                out.append('&')
                i++
                continue
            }
            val token = text.substring(i + 1, semi)
            val replaced = when (token) {
                "amp" -> "&"
                "lt" -> "<"
                "gt" -> ">"
                "quot" -> "\""
                "apos", "#39" -> "'"
                else -> when {
                    token.startsWith("#x") || token.startsWith("#X") -> codePoint(token.drop(2), 16)
                    token.startsWith("#") -> codePoint(token.drop(1), 10)
                    else -> null
                }
            }
            if (replaced == null) {
                out.append('&')
                i++
            } else {
                out.append(replaced)
                i = semi + 1
            }
        }
        return out.toString()
    }

    private fun codePoint(raw: String, radix: Int): String? {
        val code = raw.toIntOrNull(radix) ?: return null
        if (code !in 1..0x10FFFF) return null
        return try {
            String(Character.toChars(code))
        } catch (_: Exception) {
            null
        }
    }

    private fun clean(raw: String): String {
        if (raw.isEmpty()) return ""
        val stripped = raw.replace(Regex("<[^>]{0,200}>"), " ")
        return stripped.replace(Regex("\\s+"), " ").trim()
    }
}
