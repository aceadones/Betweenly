package com.nextlayer.betweenly

import android.content.Context
import com.nextlayer.betweenly.ui.DrawingWidget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Incoming `draw` messages. The inbox calls [handle] on the IO dispatcher
 * while it still holds the session lock, so the download runs on [AppScope]
 * and does not block the next message.
 *
 * A message with `op` = `newpage` carries no file. A message with `layer` =
 * `shared` is that person's transparent layer for `page`. A message with no
 * `layer` field is a full drawing and is stored as their layer on the current page.
 *
 * DATA-FLOW: see [DrawSend]. This object only decrypts (via [FileTransfer])
 * and writes app-private storage. A new-page message has no download.
 */
object DrawInbound {
    fun handle(context: Context, json: JSONObject) {
        try {
            if (json.optString("t") != "draw") return
            if (json.optString("op") == "newpage") {
                val page = json.optInt("page", 0)
                if (page !in 1..1_000_000) return
                val app = context.applicationContext
                val generation = Store.generation()
                AppScope.scope.launch {
                    try {
                        if (!Store.isPaired(app) || Store.generation() != generation) return@launch
                        if (DrawStore.applyRemoteNewPage(app, page, generation)) {
                            DrawingWidget.updateAll(app)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        // The page stays as it was. The next message can move it.
                    }
                }
                return
            }
            val fid = json.optString("fid").lowercase()
            val key = json.optString("key")
            if (!hex32(fid) || !keyOk(key)) return
            val ts = json.optLong("ts", 0L)
            val layer = json.optString("layer")
            val page = if (json.has("page")) json.optInt("page", 0) else 0
            // Missing layer: a full drawing, composited as their layer on this page.
            val onShared = layer.isEmpty() || layer == "shared"
            val app = context.applicationContext
            val generation = Store.generation()
            AppScope.scope.launch {
                try {
                    if (!Store.isPaired(app) || Store.generation() != generation) return@launch
                    // DATA-FLOW: ciphertext of a drawing layer, GET /file/{fid}, from the relay.
                    // FileTransfer decrypts it with the key that arrived inside this sealed message.
                    // The relay stored only ciphertext, for 7 days. The key is not sent on this request.
                    val png = FileTransfer.download(app, fid, key) ?: return@launch
                    if (DrawStore.saveIncoming(app, png, ts, page, onShared, generation)) {
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
 *
 * [send] is a full drawing and has no `layer` field, so the partner treats it
 * as a layer on their current page. [sendLayer] uploads only this phone's
 * transparent layer. [sendNewPage] uploads nothing.
 */
object DrawSend {
    /**
     * DATA-FLOW: [png] is encrypted on this phone inside [FileTransfer.upload]
     * (Core.fileEncrypt). The relay receives ciphertext at PUT /file/{fid} and
     * cannot read it. The file id and the key then ride inside the sealed
     * `draw` message (Outbox, wake = false, so no FCM ping). The file is kept 7 days.
     */
    suspend fun send(context: Context, png: ByteArray): Boolean {
        if (!DrawStore.isPng(png)) return false
        val uploaded = upload(context, png) ?: return false
        val json = base(uploaded.first, uploaded.second, png.size)
        return deliver(context, json)
    }

    /**
     * Sends only this phone's transparent layer for shared page [page].
     * The composite stays on the phone.
     */
    suspend fun sendLayer(context: Context, png: ByteArray, page: Int): Boolean {
        if (!DrawStore.isPng(png) || page !in 1..1_000_000) return false
        val uploaded = upload(context, png) ?: return false
        val json = base(uploaded.first, uploaded.second, png.size)
            .put("layer", "shared")
            .put("page", page)
        return deliver(context, json)
    }

    /** Asks both phones to archive the current shared page and open [page]. No file. */
    suspend fun sendNewPage(context: Context, page: Int): Boolean {
        if (page !in 1..1_000_000) return false
        val json = JSONObject()
            .put("t", "draw")
            .put("op", "newpage")
            .put("page", page)
            .put("ts", System.currentTimeMillis())
        return deliver(context, json)
    }

    private suspend fun upload(context: Context, png: ByteArray): Pair<String, String>? {
        val uploaded = try {
            // DATA-FLOW: PNG is encrypted on this phone (Core.fileEncrypt inside FileTransfer).
            // PUT /file/{fid} sends ciphertext only. The relay stores it for 7 days and cannot read the drawing.
            FileTransfer.upload(context, png)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } ?: return null
        val fid = uploaded.first.lowercase()
        val key = uploaded.second
        if (fid.length != 32 || fid.any { it !in '0'..'9' && it !in 'a'..'f' }) return null
        if (key.isEmpty() || key.length > 200) return null
        return fid to key
    }

    private fun base(fid: String, key: String, size: Int): JSONObject {
        return JSONObject()
            .put("t", "draw")
            .put("ts", System.currentTimeMillis())
            .put("fid", fid)
            .put("key", key)
            .put("size", size)
    }

    private suspend fun deliver(context: Context, json: JSONObject): Boolean {
        return try {
            // DATA-FLOW: sealed draw message to my relay outbox.
            // A layer message carries the file id, key, size, time, layer, and page.
            // A new-page message carries op, page, and time, and no file.
            // wake is false, so there is no FCM ping. The relay stores ciphertext only.
            Outbox.send(context, json, wake = false)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }
}
