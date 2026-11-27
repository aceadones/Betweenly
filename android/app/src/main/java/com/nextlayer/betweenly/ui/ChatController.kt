package com.nextlayer.betweenly.ui

import android.content.Context
import android.net.Uri
import com.nextlayer.betweenly.AppScope
import com.nextlayer.betweenly.ChatGallery
import com.nextlayer.betweenly.ChatMessage
import com.nextlayer.betweenly.ChatStore
import com.nextlayer.betweenly.FileTransfer
import com.nextlayer.betweenly.LinkPreview
import com.nextlayer.betweenly.Msg
import com.nextlayer.betweenly.Outbox
import com.nextlayer.betweenly.R
import com.nextlayer.betweenly.Store
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import android.util.Base64
import kotlin.coroutines.cancellation.CancellationException

/**
 * Sends chat messages. The in-app camera calls [stageCapture]; the chat screen
 * shows a preview and then [sendDrafts]. [sendMedia] still sends immediately
 * for a caller that already confirmed. The context is the one [ChatScreen]
 * installed before the camera opened.
 *
 * DATA-FLOW: text, link cards, reactions, unsends, and attachment pointers
 * (file id + key) leave through [Outbox.send]. File bytes leave through
 * [FileTransfer]. Link pages are fetched only on this phone; see [LinkPreview].
 * Shared wallpaper is sent by [ChatWallpaperSync], not here.
 */
/** One item waiting in the send preview. Bytes win over [uri] when both are set. */
class OutgoingDraft(
    val uri: Uri?,
    val bytes: ByteArray?,
    val mime: String,
    val name: String,
    val video: Boolean,
    val asFile: Boolean,
    val kind: String? = null,
)

/** A camera photo or video held until the chat preview sends or drops it. */
class StagedCapture(val uri: Uri, val video: Boolean)

object ChatController {
    private val notesMut = MutableSharedFlow<Int>(extraBufferCapacity = 8)

    /** String resource ids for short failures (too large, could not read, could not send). */
    val notes: SharedFlow<Int> = notesMut

    @Volatile
    private var app: Context? = null

    /** Ids this phone has unsent. An in-flight send of the original must not go out. */
    private val suppressed = LinkedHashSet<String>()

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

    private val stageLock = Any()
    private val staged = ArrayDeque<StagedCapture>()

    /** Camera confirmed a clip. The chat screen turns this into a preview, not a send. */
    fun stageCapture(uri: Uri, video: Boolean) {
        synchronized(stageLock) {
            staged.add(StagedCapture(uri, video))
            while (staged.size > 6) staged.removeFirst()
        }
    }

    fun consumeCaptures(): List<StagedCapture> = synchronized(stageLock) {
        val copy = staged.toList()
        staged.clear()
        copy
    }

    private var heldDrafts: List<ChatDraft> = emptyList()
    private var heldCaption: String = ""
    private var heldIndex: Int = 0

    /**
     * The camera route replaces the chat screen, so an open preview is kept
     * here until the chat comes back. Not written to disk.
     */
    fun holdPreview(drafts: List<ChatDraft>, caption: String, index: Int) {
        heldDrafts = drafts
        heldCaption = caption
        heldIndex = index
    }

    fun takePreview(): Triple<List<ChatDraft>, String, Int> {
        val out = Triple(heldDrafts, heldCaption, heldIndex)
        heldDrafts = emptyList()
        heldCaption = ""
        heldIndex = 0
        return out
    }

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

    fun sendMedia(context: Context, uri: Uri, isVideo: Boolean, replyId: String? = null, caption: String = "") {
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
            deliver(context, result, reply, caption)
        }
    }

    /** Gallery image or video. [replyId] is quoted on the attachment when it is a real message id. */
    fun sendPicked(context: Context, uri: Uri, replyId: String? = null, caption: String = "") {
        install(context)
        val mime = try {
            context.contentResolver.getType(uri).orEmpty()
        } catch (_: Exception) {
            ""
        }
        sendMedia(context, uri, isVideo = mime.startsWith("video/"), replyId = replyId, caption = caption)
    }

    /** Any file, sent as-is up to 20 MB. */
    fun sendDocument(context: Context, uri: Uri, replyId: String? = null, caption: String = "") {
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
            deliver(context, result, reply, caption)
        }
    }

    /** Keyboard sticker, GIF, or image. [bytes] are the original file bytes. */
    fun sendRich(context: Context, bytes: ByteArray, mime: String, replyId: String? = null, caption: String = "") {
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
            deliver(context, result, reply, caption)
        }
    }

    /**
     * Preview sheet. Items go out one after another. The caption rides on the
     * last item. The reply quote rides on the first.
     */
    fun sendDrafts(context: Context, drafts: List<OutgoingDraft>, caption: String, replyId: String?) {
        if (drafts.isEmpty()) return
        install(context)
        val reply = resolveReply(replyId)
        AppScope.scope.launch {
            drafts.forEachIndexed { index, draft ->
                val cap = if (index == drafts.lastIndex) caption else ""
                val quoted = if (index == 0) reply else null
                val result = try {
                    when {
                        draft.bytes != null && (draft.kind == "gif" || draft.kind == "sticker") -> ChatMedia.asAttachment(
                            draft.bytes,
                            draft.mime.ifBlank { if (draft.kind == "gif") "image/gif" else "image/png" },
                            draft.kind,
                            draft.name.ifBlank { if (draft.kind == "gif") "animation.gif" else "sticker.png" },
                        )
                        draft.bytes != null && draft.asFile -> ChatMedia.asAttachment(
                            draft.bytes,
                            draft.mime.ifBlank { "application/octet-stream" },
                            "file",
                            draft.name.ifBlank { "file" },
                        )
                        draft.bytes != null -> ChatMedia.fromBytes(draft.bytes, draft.mime)
                        draft.uri != null && draft.asFile -> ChatMedia.fromUri(context, draft.uri, asVideo = false, asFile = true)
                        draft.uri != null -> ChatMedia.fromUri(context, draft.uri, asVideo = draft.video, asFile = false)
                        else -> MediaResult.Failed
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    MediaResult.Failed
                }
                deliver(context, result, quoted, cap)
            }
        }
    }

    /** GIF, sticker, or other bytes that must keep [kind]. */
    fun sendKind(
        context: Context,
        bytes: ByteArray,
        mime: String,
        kind: String,
        name: String,
        replyId: String? = null,
        durationMs: Int = 0,
        wave: String = "",
    ) {
        install(context)
        val reply = resolveReply(replyId)
        AppScope.scope.launch {
            val result = try {
                ChatMedia.asAttachment(bytes, mime, kind, name, durationMs, wave)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                MediaResult.Failed
            }
            deliver(context, result, reply, "")
        }
    }

    /** A saved drawing. Resized like any other photo. */
    fun sendLocalImage(context: Context, bytes: ByteArray, mime: String, replyId: String? = null) {
        sendRich(context, bytes, mime, replyId, caption = "")
    }

    fun sendVoice(context: Context, bytes: ByteArray, durationMs: Int, wave: String, replyId: String? = null) {
        sendKind(context, bytes, "audio/mp4", "voice", "voice.m4a", replyId, durationMs, wave)
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
                if (message.json.optString("t") == "gone" || skipSend(context, id)) return@launch
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
                if (skipSend(context, id)) return@launch
                if (!fit(json)) {
                    if (skipSend(context, id)) return@launch
                    ChatStore.setStatus(context, id, ChatStore.FAILED)
                    note(R.string.chat_send_failed)
                    return@launch
                }
                if (!ChatStore.updateJson(context, id, json)) {
                    if (skipSend(context, id)) return@launch
                    ChatStore.setStatus(context, id, ChatStore.FAILED)
                    note(R.string.chat_send_failed)
                    return@launch
                }
                if (skipSend(context, id)) return@launch
                val ok = transmit(context, json)
                if (skipSend(context, id)) return@launch
                ChatStore.setStatus(context, id, if (ok) ChatStore.SENT else ChatStore.FAILED)
                if (!ok) note(R.string.chat_send_failed)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (skipSend(context, id)) return@launch
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
        val safe = ChatStore.normalizeId(id) ?: return
        if (clean.isEmpty()) return
        install(context)
        AppScope.scope.launch {
            try {
                val message = ChatStore.get(context, safe) ?: return@launch
                val type = message.json.optString("t")
                if (type == "gone" || type == "note") return@launch
                val mine = message.json.optString("mx")
                val next = if (mine == clean) "" else clean
                if (!ChatStore.setMyReaction(context, safe, next)) return@launch
                val json = JSONObject().put("t", "react").put("id", safe).put("emoji", clean)
                if (!transmit(context, json)) note(R.string.chat_send_failed)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                note(R.string.chat_send_failed)
            }
        }
    }

    /**
     * Delete-for-everyone of this phone's own message. The row becomes a
     * tombstone immediately. A failed send is remembered and tried again
     * from [flushUnsends].
     */
    fun unsend(context: Context, id: String) {
        val safe = ChatStore.normalizeId(id) ?: return
        blockSend(safe)
        install(context)
        AppScope.scope.launch {
            try {
                val message = ChatStore.get(context, safe)
                if (message == null || !message.outgoing) {
                    unblock(safe)
                    return@launch
                }
                if (message.json.optString("t") == "gone") {
                    rememberUnsend(context, safe)
                } else if (!ChatStore.tombstone(context, safe, onlyIncoming = false)) {
                    unblock(safe)
                    return@launch
                } else {
                    rememberUnsend(context, safe)
                }
                val json = JSONObject().put("t", "unsend").put("id", safe)
                if (transmit(context, json, wake = false)) {
                    forgetUnsend(context, safe)
                } else {
                    note(R.string.chat_unsend_failed)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                note(R.string.chat_unsend_failed)
            }
        }
    }

    /** Sends unsends that did not leave the phone last time. Quiet on failure. */
    fun flushUnsends(context: Context) {
        install(context)
        AppScope.scope.launch {
            val ids = synchronized(unsendLock) { readUnsends(context) }
            for (id in ids) {
                try {
                    val json = JSONObject().put("t", "unsend").put("id", id)
                    if (transmit(context, json, wake = false)) forgetUnsend(context, id)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // The next time Chat opens, this id is tried again.
                }
            }
        }
    }

    fun note(resId: Int) {
        notesMut.tryEmit(resId)
    }

    private suspend fun deliver(context: Context, result: MediaResult, reply: String?, caption: String = "") {
        when (result) {
            is MediaResult.TooLarge -> note(if (result.video) R.string.chat_too_large else R.string.chat_file_too_large)
            is MediaResult.Failed -> note(R.string.chat_attach_failed)
            is MediaResult.Ok -> sendPrepared(context, result.prepared, reply, caption)
        }
    }

    private suspend fun sendPrepared(context: Context, prepared: PreparedMedia, reply: String?, caption: String = "") {
        if (!Store.isPaired(context)) return
        val id = ChatStore.newId()
        val ts = System.currentTimeMillis()
        val json = attJson(id, ts, prepared, fid = "", key = "")
        putCaption(json, prepared.kind, caption)
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
            if (skipSend(context, id)) return
            ChatStore.setStatus(context, id, ChatStore.FAILED)
            note(R.string.chat_send_failed)
            return
        }
        json.put("fid", uploaded.first)
        json.put("key", uploaded.second)
        json.put("size", prepared.bytes.size)
        if (skipSend(context, id)) return
        if (!fit(json)) {
            if (skipSend(context, id)) return
            ChatStore.setStatus(context, id, ChatStore.FAILED)
            note(R.string.chat_send_failed)
            return
        }
        if (!ChatStore.updateJson(context, id, json)) {
            if (skipSend(context, id)) return
            ChatStore.setStatus(context, id, ChatStore.FAILED)
            note(R.string.chat_send_failed)
            return
        }
        if (skipSend(context, id)) return
        val ok = transmit(context, json)
        if (skipSend(context, id)) return
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
        if (skipSend(context, id)) return
        val ok = transmit(context, json)
        if (skipSend(context, id)) return
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
        if (skipSend(context, id)) return
        if (!fit(json)) {
            if (skipSend(context, id)) return
            ChatStore.setStatus(context, id, ChatStore.FAILED)
            note(R.string.chat_send_failed)
            return
        }
        if (!ChatStore.updateJson(context, id, json)) {
            if (skipSend(context, id)) return
            ChatStore.setStatus(context, id, ChatStore.FAILED)
            note(R.string.chat_send_failed)
            return
        }
        if (skipSend(context, id)) return
        val ok = transmit(context, json)
        if (skipSend(context, id)) return
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
            .also { json ->
                if (prepared.durationMs > 0) json.put("dur", prepared.durationMs)
                if (prepared.wave.isNotEmpty()) json.put("wave", prepared.wave)
            }
    }

    private fun putCaption(json: JSONObject, kind: String, caption: String) {
        if (kind == "sticker" || kind == "voice") return
        val clean = caption.trim().filter { it >= ' ' || it == '\n' }.take(2_000)
        if (clean.isNotEmpty()) json.put("text", clean)
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
        copy.remove("mx")
        copy.remove("gal")
        copy.remove("sv")
        copy.remove("dv")
        return copy
    }

    private fun blockSend(id: String) {
        synchronized(suppressed) {
            suppressed.add(id)
            while (suppressed.size > 80) {
                val first = suppressed.iterator().next()
                suppressed.remove(first)
            }
        }
    }

    private fun unblock(id: String) {
        synchronized(suppressed) { suppressed.remove(id) }
    }

    private fun blocked(id: String): Boolean = synchronized(suppressed) { id in suppressed }

    /** True when the original must not be sent or marked failed. Unsend already owns the row. */
    private fun skipSend(context: Context, id: String): Boolean =
        blocked(id) || ChatStore.isGone(context, id)

    private suspend fun transmit(context: Context, json: JSONObject, wake: Boolean = true): Boolean {
        val sending = wireCopy(json)
        if (sending.optString("t") != "unsend") {
            val id = ChatStore.normalizeId(sending.optString("id"))
            if (id != null && blocked(id)) return true
        }
        if (Msg.pad(sending) == null) return false
        return try {
            if (sending.optString("t") == "unsend") {
                // DATA-FLOW: unsend (the id of one of my messages, no text), padded and sealed, posted to my relay outbox. wake is off, so no FCM ping. The relay stores ciphertext only.
                Outbox.send(context, sending, wake = false)
            } else {
                // DATA-FLOW: chat text, a link card, a reaction, or an attachment pointer (fid + key, not the file bytes), padded and sealed, posted to my relay outbox. wake=1 asks for an empty FCM ping. The relay stores ciphertext only.
                Outbox.send(context, sending, wake = wake)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }

    private fun rememberUnsend(context: Context, id: String) = synchronized(unsendLock) {
        val next = readUnsends(context).toMutableList()
        if (id !in next) next.add(id)
        writeUnsends(context, next)
    }

    private fun forgetUnsend(context: Context, id: String) = synchronized(unsendLock) {
        writeUnsends(context, readUnsends(context).filter { it != id })
    }

    private fun readUnsends(context: Context): List<String> {
        val raw = try {
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(UNSEND, "")
                .orEmpty()
        } catch (_: Exception) {
            ""
        }
        return raw.split(',').mapNotNull { ChatStore.normalizeId(it) }
    }

    private fun writeUnsends(context: Context, ids: List<String>) {
        try {
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(UNSEND, ids.distinct().take(40).joinToString(","))
                .commit()
        } catch (_: Exception) {
            // The tombstone is already on this phone. The next flush tries again if the write stuck.
        }
    }

    private const val PREFS = "app"
    private const val UNSEND = "chat_unsend_pending"
    private val unsendLock = Any()

    private val receiptLock = Any()
    private val dlvQueue = LinkedHashSet<String>()
    private val seenQueue = LinkedHashSet<String>()
    private val seenSent = HashSet<String>()
    private var dlvJob: Job? = null
    private var seenJob: Job? = null

    /** Incoming message is stored. A short wait batches ids into one encrypted receipt. */
    fun noteDelivered(context: Context, id: String) {
        val safe = ChatStore.normalizeId(id) ?: return
        armReceipt(context.applicationContext, safe, read = false)
    }

    /**
     * Messages on screen. Nothing is sent when read receipts are off.
     * Ids already acknowledged this session are skipped.
     */
    fun noteSeen(context: Context, ids: List<String>) {
        if (!Store.chatReadReceipts(context)) return
        val app = context.applicationContext
        for (id in ids) {
            val safe = ChatStore.normalizeId(id) ?: continue
            if (safe in seenSent) continue
            armReceipt(app, safe, read = true)
        }
    }

    /** Rows that already have a local seen flag, so a reopen does not send them again. */
    fun rememberSeen(ids: Collection<String>) {
        synchronized(receiptLock) {
            ids.mapNotNull { ChatStore.normalizeId(it) }.forEach { seenSent.add(it) }
        }
    }

    /** Delivery receipts that never left, for messages stored before this open. */
    fun syncDelivery(context: Context, messages: List<ChatMessage>) {
        val app = context.applicationContext
        for (message in messages) {
            if (message.outgoing) continue
            val type = message.json.optString("t")
            if (type != "chat" && type != "att" && type != "link") continue
            if (message.json.optInt("dv") == 1) continue
            noteDelivered(app, message.id)
        }
        rememberSeen(messages.filter { it.json.optInt("sv") == 1 }.map { it.id })
    }

    private fun armReceipt(context: Context, id: String, read: Boolean) {
        synchronized(receiptLock) {
            val queue = if (read) seenQueue else dlvQueue
            queue.add(id)
            val running = if (read) seenJob?.isActive == true else dlvJob?.isActive == true
            if (running) return
            val job = AppScope.scope.launch {
                delay(280)
                flushReceipts(context, read)
            }
            if (read) seenJob = job else dlvJob = job
        }
    }

    private suspend fun flushReceipts(context: Context, read: Boolean) {
        var misses = 0
        while (true) {
            if (read && !Store.chatReadReceipts(context)) {
                synchronized(receiptLock) {
                    seenQueue.clear()
                    seenJob = null
                }
                return
            }
            val batch = synchronized(receiptLock) {
                val queue = if (read) seenQueue else dlvQueue
                if (queue.isEmpty()) {
                    if (read) seenJob = null else dlvJob = null
                    emptyList()
                } else {
                    val taken = queue.take(40)
                    taken.forEach { queue.remove(it) }
                    taken
                }
            }
            if (batch.isEmpty()) return
            val json = JSONObject().put("t", if (read) "seen" else "dlv")
            val ids = JSONArray()
            batch.forEach { ids.put(it) }
            json.put("ids", ids)
            val ok = transmitReceipt(context, json)
            if (!ok) {
                synchronized(receiptLock) {
                    val queue = if (read) seenQueue else dlvQueue
                    batch.forEach { queue.add(it) }
                }
                misses++
                if (misses >= 3) {
                    synchronized(receiptLock) {
                        if (read) seenJob = null else dlvJob = null
                    }
                    return
                }
                delay(2_000)
                continue
            }
            misses = 0
            if (read) {
                synchronized(receiptLock) { seenSent.addAll(batch) }
                ChatStore.markLocal(context, batch, "sv")
            } else {
                ChatStore.markLocal(context, batch, "dv")
            }
        }
    }

    private suspend fun transmitReceipt(context: Context, json: JSONObject): Boolean {
        if (Msg.pad(json) == null) return false
        return try {
            // DATA-FLOW: delivery or read receipt (message ids only, no text and no names), padded and sealed, posted to my relay outbox. wake is off, so no FCM ping. The relay stores ciphertext only.
            Outbox.send(context, json, wake = false)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }
}
