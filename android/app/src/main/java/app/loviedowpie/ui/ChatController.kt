package app.loviedowpie.ui

import android.content.Context
import android.net.Uri
import app.loviedowpie.AppScope
import app.loviedowpie.ChatGallery
import app.loviedowpie.ChatMessage
import app.loviedowpie.ChatStore
import app.loviedowpie.FileTransfer
import app.loviedowpie.LinkPreview
import app.loviedowpie.Msg
import app.loviedowpie.Outbox
import app.loviedowpie.R
import app.loviedowpie.Store
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import android.util.Base64
import kotlin.coroutines.cancellation.CancellationException

/**
 * Sends chat messages. [sendMedia] is what [android.app.Activity] calls with a
 * [CameraScreen] result: `ChatController.sendMedia(uri, isVideo)`. The context
 * is the one [ChatScreen] installed before the camera opened. The overload that
 * takes a [Context] works even if the chat screen is not up.
 *
 * DATA-FLOW: text, link cards, reactions, and attachment pointers (file id + key)
 * leave through [Outbox.send]. File bytes leave through [FileTransfer]. Link
 * pages are fetched only on this phone; see [LinkPreview].
 */
object ChatController {
    private val notesMut = MutableSharedFlow<Int>(extraBufferCapacity = 8)

    /** String resource ids for short failures (too large, could not read, could not send). */
    val notes: SharedFlow<Int> = notesMut

    @Volatile
    private var app: Context? = null

    fun install(context: Context) {
        app = context.applicationContext
    }

    private val replyLock = Any()

    @Volatile
    private var armedReply: String? = null

    /** Quote this message on the next send. Used when the chat screen is covered by the camera. */
    fun armReply(id: String?) {
        synchronized(replyLock) {
            armedReply = id?.let { ChatStore.normalizeId(it) }
        }
    }

    fun peekReply(): String? = synchronized(replyLock) { armedReply }

    /**
     * The id to put in `"re"`. An explicit id wins. Otherwise the armed reply
     * is used. Either way the armed reply is cleared so the next send is plain.
     */
    private fun resolveReply(explicit: String?): String? = synchronized(replyLock) {
        val chosen = ChatStore.normalizeId(explicit ?: "") ?: armedReply?.let { ChatStore.normalizeId(it) }
        armedReply = null
        chosen
    }

    /** Camera result. Requires [install] (ChatScreen does this as soon as it is shown). */
    fun sendMedia(uri: Uri, isVideo: Boolean) {
        val context = app ?: return
        sendMedia(context, uri, isVideo, replyId = null)
    }

    fun sendMedia(context: Context, uri: Uri, isVideo: Boolean, replyId: String? = null) {
        install(context)
        val reply = resolveReply(replyId)
        AppScope.scope.launch {
            val result = try {
                ChatMedia.fromUri(context, uri, asVideo = isVideo, asFile = false)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                MediaResult.Failed
            }
            deliver(context, result, reply)
        }
    }

    /** Gallery image or video. [replyId] is quoted on the attachment when it is a real message id. */
    fun sendPicked(context: Context, uri: Uri, replyId: String? = null) {
        install(context)
        val mime = try {
            context.contentResolver.getType(uri).orEmpty()
        } catch (_: Exception) {
            ""
        }
        sendMedia(context, uri, isVideo = mime.startsWith("video/"), replyId = replyId)
    }

    /** Any file, sent as-is up to 20 MB. */
    fun sendDocument(context: Context, uri: Uri, replyId: String? = null) {
        install(context)
        val reply = resolveReply(replyId)
        AppScope.scope.launch {
            val result = try {
                ChatMedia.fromUri(context, uri, asVideo = false, asFile = true)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                MediaResult.Failed
            }
            deliver(context, result, reply)
        }
    }

    /** Keyboard sticker, GIF, or image. [bytes] are the original file bytes. */
    fun sendRich(context: Context, bytes: ByteArray, mime: String, replyId: String? = null) {
        install(context)
        val reply = resolveReply(replyId)
        AppScope.scope.launch {
            val result = try {
                ChatMedia.fromBytes(bytes, mime)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                MediaResult.Failed
            }
            deliver(context, result, reply)
        }
    }

    /** True once the message is queued. The field should be cleared only after that. */
    fun sendText(context: Context, raw: String, replyId: String? = null): Boolean {
        install(context)
        val text = raw.trim().take(10_000)
        if (text.isEmpty() || !Store.isPaired(context)) return false
        val reply = resolveReply(replyId)
        AppScope.scope.launch {
            try {
                if (!Store.isPaired(context)) return@launch
                val url = LinkPreview.firstHttpsUrl(text)
                if (url == null) {
                    sendChat(context, text, reply)
                } else {
                    sendLink(context, text, url, reply)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                note(R.string.chat_send_failed)
            }
        }
        return true
    }

    fun retry(context: Context, id: String) {
        install(context)
        AppScope.scope.launch {
            try {
                val message = ChatStore.get(context, id) ?: return@launch
                if (!message.outgoing) return@launch
                ChatStore.setStatus(context, id, ChatStore.SENDING)
                val json = message.json
                if (json.optString("t") == "att" && json.optString("fid").length != 32) {
                    val plain = ChatStore.readAttachment(context, id)
                    if (plain == null) {
                        ChatStore.setStatus(context, id, ChatStore.FAILED)
                        note(R.string.chat_send_failed)
                        return@launch
                    }
                    val uploaded = FileTransfer.upload(context, plain)
                    if (uploaded == null) {
                        ChatStore.setStatus(context, id, ChatStore.FAILED)
                        note(R.string.chat_send_failed)
                        return@launch
                    }
                    json.put("fid", uploaded.first)
                    json.put("key", uploaded.second)
                    json.put("size", plain.size)
                }
                if (!fit(json)) {
                    ChatStore.setStatus(context, id, ChatStore.FAILED)
                    note(R.string.chat_send_failed)
                    return@launch
                }
                ChatStore.updateJson(context, id, json)
                val ok = transmit(context, json)
                ChatStore.setStatus(context, id, if (ok) ChatStore.SENT else ChatStore.FAILED)
                if (!ok) note(R.string.chat_send_failed)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                ChatStore.setStatus(context, id, ChatStore.FAILED)
                note(R.string.chat_send_failed)
            }
        }
    }

    fun delete(context: Context, id: String) {
        AppScope.scope.launch {
            try {
                ChatStore.delete(context, id)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // The row may already be gone.
            }
        }
    }

    fun react(context: Context, id: String, emoji: String) {
        val clean = emoji.trim().filter { it >= ' ' }.take(16)
        if (clean.isEmpty() || ChatStore.normalizeId(id) == null) return
        AppScope.scope.launch {
            try {
                ChatStore.setReaction(context, id, clean)
                val json = JSONObject().put("t", "react").put("id", id).put("emoji", clean)
                if (!transmit(context, json)) note(R.string.chat_send_failed)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                note(R.string.chat_send_failed)
            }
        }
    }

    fun note(resId: Int) {
        notesMut.tryEmit(resId)
    }

    private suspend fun deliver(context: Context, result: MediaResult, reply: String?) {
        when (result) {
            is MediaResult.TooLarge -> note(if (result.video) R.string.chat_too_large else R.string.chat_file_too_large)
            is MediaResult.Failed -> note(R.string.chat_attach_failed)
            is MediaResult.Ok -> sendPrepared(context, result.prepared, reply)
        }
    }

    private suspend fun sendPrepared(context: Context, prepared: PreparedMedia, reply: String?) {
        if (!Store.isPaired(context)) return
        val id = ChatStore.newId()
        val ts = System.currentTimeMillis()
        val json = attJson(id, ts, prepared, fid = "", key = "")
        putReply(json, reply)
        if (!fit(json)) {
            note(R.string.chat_attach_failed)
            return
        }
        if (!ChatStore.writeAttachment(context, id, prepared.bytes)) {
            note(R.string.chat_attach_failed)
            return
        }
        val message = ChatMessage(id, ts, outgoing = true, status = ChatStore.SENDING, json = json)
        if (!ChatStore.insert(context, message)) {
            note(R.string.chat_attach_failed)
            return
        }
        ChatGallery.maybe(context, id, json, prepared.bytes)
        val uploaded = try {
            FileTransfer.upload(context, prepared.bytes)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        if (uploaded == null) {
            ChatStore.setStatus(context, id, ChatStore.FAILED)
            note(R.string.chat_send_failed)
            return
        }
        json.put("fid", uploaded.first)
        json.put("key", uploaded.second)
        json.put("size", prepared.bytes.size)
        if (!fit(json)) {
            ChatStore.setStatus(context, id, ChatStore.FAILED)
            note(R.string.chat_send_failed)
            return
        }
        ChatStore.updateJson(context, id, json)
        val ok = transmit(context, json)
        ChatStore.setStatus(context, id, if (ok) ChatStore.SENT else ChatStore.FAILED)
        if (!ok) note(R.string.chat_send_failed)
    }

    private suspend fun sendChat(context: Context, text: String, reply: String?) {
        val id = ChatStore.newId()
        val ts = System.currentTimeMillis()
        val json = JSONObject().put("t", "chat").put("id", id).put("ts", ts).put("text", text)
        putReply(json, reply)
        if (!fit(json)) {
            note(R.string.chat_send_failed)
            return
        }
        val message = ChatMessage(id, ts, outgoing = true, status = ChatStore.SENDING, json = json)
        if (!ChatStore.insert(context, message)) return
        val ok = transmit(context, json)
        ChatStore.setStatus(context, id, if (ok) ChatStore.SENT else ChatStore.FAILED)
        if (!ok) note(R.string.chat_send_failed)
    }

    private suspend fun sendLink(context: Context, text: String, url: String, reply: String?) {
        val id = ChatStore.newId()
        val ts = System.currentTimeMillis()
        val json = JSONObject()
            .put("t", "link")
            .put("id", id)
            .put("ts", ts)
            .put("text", text)
            .put("url", url)
            .put("title", "")
            .put("desc", "")
            .put("img", "")
        putReply(json, reply)
        val message = ChatMessage(id, ts, outgoing = true, status = ChatStore.SENDING, json = json)
        if (!ChatStore.insert(context, message)) return
        val card = try {
            withTimeoutOrNull(12_000) { LinkPreview.fetch(url) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        if (card != null) {
            json.put("title", card.title)
            json.put("desc", card.description)
            if (card.jpeg != null && card.jpeg.isNotEmpty()) {
                json.put("img", Base64.encodeToString(card.jpeg, Base64.NO_WRAP))
            }
        }
        if (!fit(json)) {
            ChatStore.setStatus(context, id, ChatStore.FAILED)
            note(R.string.chat_send_failed)
            return
        }
        ChatStore.updateJson(context, id, json)
        val ok = transmit(context, json)
        ChatStore.setStatus(context, id, if (ok) ChatStore.SENT else ChatStore.FAILED)
        if (!ok) note(R.string.chat_send_failed)
    }

    private fun attJson(id: String, ts: Long, prepared: PreparedMedia, fid: String, key: String): JSONObject {
        return JSONObject()
            .put("t", "att")
            .put("id", id)
            .put("ts", ts)
            .put("kind", prepared.kind)
            .put("mime", prepared.mime)
            .put("name", prepared.name)
            .put("size", prepared.bytes.size)
            .put("fid", fid)
            .put("key", key)
            .put("w", prepared.width)
            .put("h", prepared.height)
            .put("thumb", prepared.thumb)
            .put("text", "")
    }

    private fun putReply(json: JSONObject, replyId: String?) {
        val id = replyId?.let { ChatStore.normalizeId(it) } ?: return
        json.put("re", id)
    }

    /**
     * Shrinks thumb, link image, or text until [Msg.pad] accepts the JSON.
     * Returns false when nothing more can be removed.
     */
    private fun fit(json: JSONObject): Boolean {
        repeat(8) {
            if (Msg.pad(wireCopy(json)) != null) return true
            if (!shrink(json)) return false
        }
        return Msg.pad(wireCopy(json)) != null
    }

    private fun shrink(json: JSONObject): Boolean {
        val imageKey = when {
            json.optString("thumb").isNotEmpty() -> "thumb"
            json.optString("img").isNotEmpty() -> "img"
            else -> null
        }
        if (imageKey != null) {
            val current = json.optString(imageKey)
            val smaller = ChatMedia.shrinkJpegBase64(current)
            if (smaller.isNotEmpty() && smaller.length < current.length) {
                json.put(imageKey, smaller)
                return true
            }
            json.put(imageKey, "")
            return true
        }
        val text = json.optString("text")
        if (text.length > 40) {
            json.put("text", text.take(text.length * 2 / 3))
            return true
        }
        val desc = json.optString("desc")
        if (desc.length > 20) {
            json.put("desc", desc.take(desc.length / 2))
            return true
        }
        val title = json.optString("title")
        if (title.length > 20) {
            json.put("title", title.take(title.length / 2))
            return true
        }
        return false
    }

    private fun wireCopy(json: JSONObject): JSONObject {
        val copy = JSONObject(json.toString())
        copy.remove("rx")
        copy.remove("gal")
        return copy
    }

    private suspend fun transmit(context: Context, json: JSONObject): Boolean {
        val sending = wireCopy(json)
        if (Msg.pad(sending) == null) return false
        return try {
            // DATA-FLOW: chat text, a link card, a reaction, or an attachment pointer (fid + key, not the file bytes), padded and sealed, posted to my relay outbox. wake=1 asks for an empty FCM ping. The relay stores ciphertext only.
            Outbox.send(context, sending, wake = true)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }
}
