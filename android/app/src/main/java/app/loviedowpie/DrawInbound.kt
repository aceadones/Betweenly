package app.loviedowpie

import android.content.Context
import app.loviedowpie.ui.DrawingWidget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Incoming `draw` messages. The inbox calls [handle] on the IO dispatcher
 * while it still holds the session lock, so the download runs on [AppScope]
 * and does not block the next message.
 *
 * DATA-FLOW: see [DrawSend]. This object only decrypts (via [FileTransfer])
 * and writes app-private storage.
 */
object DrawInbound {
    fun handle(context: Context, json: JSONObject) {
        try {
            if (json.optString("t") != "draw") return
            val fid = json.optString("fid").lowercase()
            val key = json.optString("key")
            if (!hex32(fid) || !keyOk(key)) return
            val ts = json.optLong("ts", 0L)
            val app = context.applicationContext
            val generation = Store.generation()
            AppScope.scope.launch {
                try {
                    if (!Store.isPaired(app) || Store.generation() != generation) return@launch
                    // DATA-FLOW: ciphertext of a drawing, GET /file/{fid}, from the relay.
                    // FileTransfer decrypts it with the key that arrived inside this sealed message.
                    // The relay stored only ciphertext. The key is not sent on this request.
                    val png = FileTransfer.download(app, fid, key) ?: return@launch
                    if (DrawStore.savePartnerLatest(app, png, ts, generation)) {
                        DrawingWidget.updateAll(app)
                        // Local notice only. The text is "New drawing". No picture, no name.
                        // DATA-FLOW: nothing leaves the phone.
                        ChatNotifications.onDrawing(app)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // No network, or the file was not readable. The canvas keeps what it had.
                }
            }
        } catch (_: Exception) {
            // A bad field must not crash the inbox drain.
        }
    }

    private fun hex32(value: String): Boolean =
        value.length == 32 && value.all { it in '0'..'9' || it in 'a'..'f' }

    private fun keyOk(value: String): Boolean =
        value.length in 16..200 && value.all { it != '\n' && it != '\r' && it != ' ' }
}

/**
 * Uploads a PNG drawing and sends the sealed `draw` message.
 * Shared by the Activity tab and "Send again" on Profile.
 */
object DrawSend {
    /**
     * DATA-FLOW: [png] is encrypted on this phone inside [FileTransfer.upload]
     * (Core.fileEncrypt). The relay receives ciphertext at PUT /file/{fid} and
     * cannot read it. The file id and the key then ride inside the sealed
     * `draw` message (Outbox, wake = false, so no FCM ping).
     */
    suspend fun send(context: Context, png: ByteArray): Boolean {
        if (!DrawStore.isPng(png)) return false
        val uploaded = try {
            // DATA-FLOW: PNG is encrypted on this phone (Core.fileEncrypt inside FileTransfer).
            // PUT /file/{fid} sends ciphertext only. The relay cannot read the drawing.
            FileTransfer.upload(context, png)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } ?: return false
        val fid = uploaded.first.lowercase()
        val key = uploaded.second
        if (fid.length != 32 || fid.any { it !in '0'..'9' && it !in 'a'..'f' }) return false
        if (key.isEmpty() || key.length > 200) return false
        val json = JSONObject()
            .put("t", "draw")
            .put("ts", System.currentTimeMillis())
            .put("fid", fid)
            .put("key", key)
            .put("size", png.size)
        return try {
            // DATA-FLOW: sealed draw message (file id, key, size, time) to my relay outbox.
            // wake is false, so there is no FCM ping. The relay stores ciphertext only.
            Outbox.send(context, json, wake = false)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }
}
