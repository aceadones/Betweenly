package com.nextlayer.betweenly

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * Thrown when the relay has burned a pairing rendezvous (too many reads).
 * The code cannot be used again; generate a new one.
 */
class PairingBurnedException : Exception()

/**
 * Result of asking the relay whether a newer APK exists.
 * [httpCode] is -1 when the network failed (distinct from 404 "nothing published").
 */
internal data class UpdateQuery(val httpCode: Int, val json: JSONObject?)

/**
 * HTTPS client for the blind relay. Every function is suspend and runs on
 * [Dispatchers.IO]; none of them touch the main thread.
 *
 * Timeouts are 10s. Redirects are refused so a mailbox id in the path cannot
 * be leaked to another host. Cleartext URLs are refused. The User-Agent is the
 * constant "Betweenly" (no device identifier).
 *
 * Failures return null / false / an empty list. They are not logged: the path
 * contains mailbox ids, which are secrets.
 */
object Relay {
    private const val TIMEOUT_MS = 10_000
    private const val USER_AGENT = "Betweenly"

    /**
     * DATA-FLOW: my PAKE message (base64 text, not the pairing code), to the relay,
     * not encrypted. The code itself never leaves the phone; SPAKE2 hides it.
     * Returns true when the relay stored it.
     */
    suspend fun pairPut(rv: String, role: String, msgB64: String): Boolean = io {
        if (!hex32(rv) || !roleOk(role) || msgB64.isEmpty() || msgB64.length > 512) return@io false
        val conn = open("/pair/$rv/$role", "PUT") ?: return@io false
        try {
            writeText(conn, msgB64)
            conn.responseCode in 200..299
        } catch (_: Exception) {
            false
        } finally {
            conn.disconnect()
        }
    }

    /**
     * DATA-FLOW: the other phone's PAKE message (base64 text), from the relay, not
     * encrypted. Null means "not there yet" or a network failure.
     * Throws [PairingBurnedException] on HTTP 429.
     *
     * The relay's GET /pair/{rv}/{role} returns the *other* role's message
     * (see relay/src/index.ts). Pass the same role you used in [pairPut].
     */
    suspend fun pairGet(rv: String, role: String): String? = io {
        if (!hex32(rv) || !roleOk(role)) return@io null
        val conn = open("/pair/$rv/$role", "GET") ?: return@io null
        try {
            when (conn.responseCode) {
                200 -> JSONObject(readLimited(conn, 8 * 1024)).optString("msg").takeIf { it.isNotEmpty() }
                404 -> null
                429 -> throw PairingBurnedException()
                else -> null
            }
        } catch (e: PairingBurnedException) {
            throw e
        } catch (_: Exception) {
            null
        } finally {
            conn.disconnect()
        }
    }

    /**
     * DATA-FLOW: one ciphertext blob, to the relay mailbox [mailbox], encrypted.
     * When [wake] is true the query string is `wake=1`, which asks the relay to
     * send an empty FCM ping to whoever registered a token for this mailbox.
     * The ping carries no location and no message body.
     */
    suspend fun boxPost(mailbox: String, bytes: ByteArray, wake: Boolean): Boolean = io {
        if (!hex32(mailbox) || bytes.isEmpty() || bytes.size > 96 * 1024) return@io false
        val path = if (wake) "/box/$mailbox?wake=1" else "/box/$mailbox"
        val conn = open(path, "POST") ?: return@io false
        try {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/octet-stream")
            conn.outputStream.use { it.write(bytes) }
            conn.responseCode in 200..299
        } catch (_: Exception) {
            false
        } finally {
            conn.disconnect()
        }
    }

    /**
     * DATA-FLOW: all ciphertext blobs waiting in [mailbox], from the relay, encrypted.
     * The relay deletes them as part of this read. Empty on failure.
     */
    suspend fun boxGet(mailbox: String): List<ByteArray> = io {
        if (!hex32(mailbox)) return@io emptyList()
        val conn = open("/box/$mailbox", "GET") ?: return@io emptyList()
        try {
            if (conn.responseCode !in 200..299) return@io emptyList()
            val json = JSONObject(readLimited(conn, 4 * 1024 * 1024))
            val arr = json.optJSONArray("blobs") ?: return@io emptyList()
            val out = ArrayList<ByteArray>(arr.length())
            for (i in 0 until arr.length()) {
                val text = arr.optString(i)
                if (text.isEmpty()) continue
                val bytes = try {
                    Base64.decode(text, Base64.DEFAULT)
                } catch (_: IllegalArgumentException) {
                    continue
                }
                if (bytes.isNotEmpty() && bytes.size <= 96 * 1024) out.add(bytes)
            }
            out
        } catch (_: Exception) {
            emptyList()
        } finally {
            conn.disconnect()
        }
    }

    /**
     * DATA-FLOW: this device's FCM token, to the relay, not encrypted (TLS only).
     * Stored under [mailbox] so the relay can send an empty wake ping later.
     * The token is not a message and not a location.
     */
    suspend fun tokenPut(mailbox: String, token: String): Boolean = io {
        val clean = token.trim()
        if (!hex32(mailbox) || clean.length !in 20..400) return@io false
        val conn = open("/token/$mailbox", "PUT") ?: return@io false
        try {
            writeText(conn, clean)
            conn.responseCode in 200..299
        } catch (_: Exception) {
            false
        } finally {
            conn.disconnect()
        }
    }

    /**
     * DATA-FLOW: public update metadata (version, sha256, notes), from the relay,
     * not encrypted, no user data. Null when nothing is published or the network failed.
     */
    suspend fun updateLatest(): JSONObject? {
        val query = queryLatest()
        return if (query.httpCode == 200) query.json else null
    }

    /** Same request as [updateLatest], keeping the HTTP status for the 24h check policy. */
    internal suspend fun queryLatest(): UpdateQuery = io {
        val conn = open("/update/latest", "GET") ?: return@io UpdateQuery(-1, null)
        try {
            val code = conn.responseCode
            if (code == 200) {
                UpdateQuery(200, JSONObject(readLimited(conn, 64 * 1024)))
            } else {
                UpdateQuery(code, null)
            }
        } catch (_: Exception) {
            UpdateQuery(-1, null)
        } finally {
            conn.disconnect()
        }
    }

    /**
     * DATA-FLOW: the published APK bytes, from the relay, not encrypted, no user data.
     * Written to [dest]. [onProgress] is called on the IO dispatcher with 0..1
     * (only when the length is known). A failed download leaves any previous
     * [dest] in place and deletes only the partial file.
     */
    suspend fun downloadApk(dest: File, onProgress: (Float) -> Unit): Boolean = io {
        val conn = open("/update/apk", "GET") ?: return@io false
        conn.setRequestProperty("Accept-Encoding", "identity")
        val part = File(dest.parentFile, dest.name + ".part")
        try {
            if (conn.responseCode !in 200..299) return@io false
            val total = conn.contentLengthLong
            onProgress(0f)
            conn.inputStream.use { input ->
                FileOutputStream(part).use { out ->
                    val buf = ByteArray(8192)
                    var read = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        read += n
                        if (total > 0L) onProgress((read.toFloat() / total.toFloat()).coerceIn(0f, 1f))
                    }
                }
            }
            if (!part.exists() || part.length() <= 0L) {
                part.delete()
                return@io false
            }
            if (!part.renameTo(dest)) {
                part.copyTo(dest, overwrite = true)
                part.delete()
            }
            try {
                onProgress(1f)
            } catch (_: Exception) {
                // The file is complete; a progress callback must not fail the download.
            }
            true
        } catch (_: Exception) {
            part.delete()
            false
        } finally {
            conn.disconnect()
        }
    }

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    private fun open(path: String, method: String): HttpsURLConnection? {
        val base = BuildConfig.RELAY_URL.trim().trimEnd('/')
        if (!base.startsWith("https://")) return null
        return try {
            val connection = URL(base + path).openConnection()
            if (connection !is HttpsURLConnection) return null
            connection.requestMethod = method
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.setRequestProperty("Accept", "application/json")
            connection
        } catch (_: Exception) {
            null
        }
    }

    private fun writeText(conn: HttpsURLConnection, text: String) {
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "text/plain; charset=utf-8")
        conn.outputStream.use { it.write(text.toByteArray(Charsets.UTF_8)) }
    }

    private fun readLimited(conn: HttpURLConnection, max: Int): String {
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream ?: return ""
        return stream.use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(4096)
            var total = 0
            while (total < max) {
                val n = input.read(buf, 0, minOf(buf.size, max - total))
                if (n < 0) break
                out.write(buf, 0, n)
                total += n
            }
            out.toString(Charsets.UTF_8.name())
        }
    }

    private fun hex32(value: String): Boolean =
        value.length == 32 && value.all { it in '0'..'9' || it in 'a'..'f' }

    private fun roleOk(role: String): Boolean = role == "a" || role == "b"
}
