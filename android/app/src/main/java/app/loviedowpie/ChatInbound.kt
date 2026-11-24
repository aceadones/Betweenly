package app.loviedowpie

import android.content.Context
import kotlinx.coroutines.launch
import org.json.JSONObject
import kotlin.coroutines.cancellation.CancellationException

/**
 * Applies chat, attachment, link, and reaction messages after [Inbox] has
 * decrypted them. Downloads run on [AppScope] so the session lock is not held
 * while a file comes down.
 *
 * DATA-FLOW: no network of its own. The attachment bytes are fetched later by
 * [FileTransfer], still ciphertext until they reach this phone.
 */
object ChatInbound {
    fun handle(context: Context, json: JSONObject) {
        try {
            when (json.optString("t")) {
                "react" -> {
                    val emoji = json.optString("emoji")
                    if (emoji.isNotEmpty()) ChatStore.setReaction(context, json.optString("id"), emoji)
                }
                "chat", "att", "link" -> {
                    if (!ChatStore.insertIncoming(context, json)) return
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
                    ChatStore.noteIncoming(context)
                    ChatNotifications.onIncoming(context)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // One bad message must not drop the rest of the mailbox.
        }
    }
}
