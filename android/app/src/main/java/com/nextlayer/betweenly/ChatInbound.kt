package com.nextlayer.betweenly

import android.content.Context
import com.nextlayer.betweenly.ui.ChatController
import kotlinx.coroutines.launch
import org.json.JSONObject
import kotlin.coroutines.cancellation.CancellationException

/**
 * Applies chat, attachment, link, reaction, unsend, and wallpaper messages
 * after [Inbox] has decrypted them. Downloads run on [AppScope] so the session
 * lock is not held while a file comes down.
 *
 * DATA-FLOW: no network of its own. Attachment and wallpaper bytes are fetched
 * later by [FileTransfer], still ciphertext until they reach this phone.
 * Unsend is only the message id.
 */
object ChatInbound {
    fun handle(context: Context, json: JSONObject) {
        try {
            when (json.optString("t")) {
                "react" -> {
                    val emoji = json.optString("emoji")
                    if (emoji.isNotEmpty()) ChatStore.setReaction(context, json.optString("id"), emoji)
                }
                "dlv" -> ChatStore.applyReceipt(context, json, read = false)
                "seen" -> ChatStore.applyReceipt(context, json, read = true)
                "unsend" -> ChatStore.tombstone(context, json.optString("id"), onlyIncoming = true)
                "wall" -> ChatWallpaperSync.onIncoming(context, json)
                "fcom" -> {
                    // A photo-frame comment. Stored with the chat rows but never shown in the chat.
                    if (!ChatStore.insertIncoming(context, json)) return
                    ChatNotifications.onIncoming(context, json)
                }
                "chat", "att", "link" -> {
                    if (!ChatStore.insertIncoming(context, json)) return
                    val stored = ChatStore.normalizeId(json.optString("id"))
                    if (stored != null) ChatController.noteDelivered(context, stored)
                    if (json.optString("t") == "att") {
                        val id = ChatStore.normalizeId(json.optString("id"))
                        if (id != null) {
                            val app = context.applicationContext
                            AppScope.scope.launch {
                                try {
                                    ChatStore.fetch(app, id)
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (_: Exception) {
                                    // The bubble shows a retry.
                                }
                            }
                        }
                    }
                    // A photo-frame post is not a chat message: no chat unread badge.
                    if (json.optInt("fr") != 1) ChatStore.noteIncoming(context)
                    ChatNotifications.onIncoming(context, json)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // One bad message must not drop the rest of the mailbox.
        }
    }
}
