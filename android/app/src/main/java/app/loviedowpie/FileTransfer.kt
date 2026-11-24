package app.loviedowpie

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.URL
import java.security.SecureRandom
import javax.net.ssl.HttpsURLConnection
import kotlin.coroutines.cancellation.CancellationException

/**
 * Uploads and downloads one attachment. The relay only ever sees ciphertext.
 * The 32-byte key goes home inside the end-to-end encrypted chat message, not
 * in this request. Files live on the relay for 7 days. A 409 means that id is
 * already taken.
 *
 * [download] tries a few times. A file can be missing for up to about a minute
 * after a successful upload.
 *
 * The drawing task calls [upload] and [download] with these signatures.
 *
 * [progress] maps a file id to 0..1 while a download is reading bytes.
 * DATA-FLOW: see [upload] and [download]. No message text is logged.
 */
object FileTransfer {
    private const val MAX_FILE = 24 * 1024 * 1024
    private const val CONNECT_MS = 30_000
    private const val READ_MS = 120_000
    private const val USER_AGENT = "Loviedowpie"

    private val progressMap = MutableStateFlow<Map<String, Float>>(emptyMap())

    /** File id to fraction while that id is downloading. Absent when idle. */
    val progress: StateFlow<Map<String, Float>> = progressMap

    /**
     * Encrypts [plain] with [Core.fileEncrypt], PUTs the ciphertext, and returns
     * the new file id plus the base64 key. Null when the bytes cannot be sent.
     */
    suspend fun upload(context: Context, plain: ByteArray): Pair<String, String>? = withContext(Dispatchers.IO) {
        if (!usable(context) || plain.isEmpty()) return@withContext null
        val sealed = try {
            Core.fileEncrypt(plain)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            ByteArray(0)
        } catch (_: OutOfMemoryError) {
            ByteArray(0)
        }
        if (sealed.size <= 32) return@withContext null
        val key = sealed.copyOfRange(0, 32)
        val data = sealed.copyOfRange(32, sealed.size)
        if (data.isEmpty() || data.size > MAX_FILE) return@withContext null
        val keyB64 = Base64.encodeToString(key, Base64.NO_WRAP)
        var conflicts = 0
        var network = 0
        while (conflicts < 3 && network < 3) {
            val fid = randomHex(16)
            // DATA-FLOW: attachment ciphertext only (the key is not in this body), PUT to relay /file/{fid}. The relay stores it for 7 days and cannot read it.
            val code = putFile(fid, data)
            if (code in 200..299) return@withContext fid to keyB64
            if (code == 409) {
                conflicts++
                continue
            }
            if (code < 0) {
                network++
                continue
            }
            return@withContext null
        }
        null
    }

    /**
     * GETs /file/[fid] and decrypts it with [keyB64]. Null if the id, the key,
     * or the bytes are no good after the retries.
     */
    suspend fun download(context: Context, fid: String, keyB64: String): ByteArray? = withContext(Dispatchers.IO) {
        if (!usable(context)) return@withContext null
        val id = fid.trim().lowercase()
        if (!hex32(id)) return@withContext null
        val key = try {
            Base64.decode(keyB64.trim(), Base64.DEFAULT)
        } catch (_: IllegalArgumentException) {
            return@withContext null
        }
        if (key.size != 32) return@withContext null
        val waits = longArrayOf(0L, 3_000L, 8_000L, 15_000L, 30_000L)
        try {
            for (wait in waits) {
                if (wait > 0L) delay(wait)
                // DATA-FLOW: attachment ciphertext only, GET from relay /file/{fid}. Decrypted on this phone. The key arrived inside the encrypted message.
                val fetched = getFile(id)
                if (fetched.bytes != null) {
                    val plain = try {
                        Core.fileDecrypt(key, fetched.bytes)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        ByteArray(0)
                    }
                    return@withContext plain.takeIf { it.isNotEmpty() }
                }
                if (!fetched.retry) return@withContext null
            }
            null
        } catch (e: CancellationException) {
            throw e
        } finally {
            setProgress(id, null)
        }
    }

    private fun putFile(fid: String, data: ByteArray): Int {
        val conn = open(fid, "PUT") ?: return -1
        return try {
            conn.doOutput = true
            conn.setFixedLengthStreamingMode(data.size)
            conn.setRequestProperty("Content-Type", "application/octet-stream")
            conn.outputStream.use { it.write(data) }
            conn.responseCode
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            -1
        } finally {
            conn.disconnect()
        }
    }

    private class Fetch(val bytes: ByteArray?, val retry: Boolean)

    private fun getFile(fid: String): Fetch {
        val conn = open(fid, "GET") ?: return Fetch(null, true)
        return try {
            conn.setRequestProperty("Accept", "application/octet-stream")
            val code = conn.responseCode
            if (code == 404 || code in 500..599) return Fetch(null, true)
            if (code !in 200..299) return Fetch(null, false)
            val declared = conn.contentLengthLong
            if (declared > MAX_FILE) return Fetch(null, false)
            setProgress(fid, 0f)
            val out = ByteArrayOutputStream()
            var tooBig = false
            conn.inputStream.use { input ->
                val buf = ByteArray(8192)
                var read = 0
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    read += n
                    if (read > MAX_FILE) {
                        tooBig = true
                        break
                    }
                    out.write(buf, 0, n)
                    if (declared > 0L) setProgress(fid, read.toFloat() / declared.toFloat())
                }
            }
            if (tooBig) return Fetch(null, false)
            val body = out.toByteArray()
            if (body.isEmpty()) Fetch(null, true) else Fetch(body, false)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            Fetch(null, true)
        } finally {
            conn.disconnect()
        }
    }

    private fun open(fid: String, method: String): HttpsURLConnection? {
        val base = BuildConfig.RELAY_URL.trim().trimEnd('/')
        if (!base.startsWith("https://") || !hex32(fid)) return null
        return try {
            val connection = URL("$base/file/$fid").openConnection()
            if (connection !is HttpsURLConnection) return null
            connection.requestMethod = method
            connection.connectTimeout = CONNECT_MS
            connection.readTimeout = READ_MS
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection
        } catch (_: Exception) {
            null
        }
    }

    private fun setProgress(fid: String, value: Float?) {
        progressMap.update { current ->
            if (value == null) {
                if (fid !in current) current else current - fid
            } else {
                current + (fid to value.coerceIn(0f, 1f))
            }
        }
    }

    private fun usable(context: Context): Boolean = try {
        context.applicationContext.packageName.isNotEmpty()
    } catch (_: Exception) {
        false
    }

    private fun hex32(value: String): Boolean =
        value.length == 32 && value.all { it in '0'..'9' || it in 'a'..'f' }

    private fun randomHex(nbytes: Int): String {
        val bytes = ByteArray(nbytes)
        SecureRandom().nextBytes(bytes)
        val hex = "0123456789abcdef".toCharArray()
        val out = CharArray(bytes.size * 2)
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xff
            out[i * 2] = hex[v ushr 4]
            out[i * 2 + 1] = hex[v and 0x0f]
        }
        return String(out)
    }
}
