package com.nextlayer.betweenly

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** One shared record: a to-do, a mood, a game, a letter, or the countdown. [mine] is true when this phone created it. */
class SharedItem(val id: String, val ts: Long, val mine: Boolean, val d: JSONObject)

/**
 * Small shared records both of you can read and change: to-do lists, moods, X and O games, letters
 * and the countdown. Each record is a sealed row in the chat store (hidden from the chat) and a tiny
 * encrypted message to the partner. When both phones change the same record, the newer change wins.
 *
 * DATA-FLOW: `{"t":"sd","id","f":feature,"ts","d":{...}}`, encrypted like any message, to the partner
 * via the relay. Nothing else leaves the phone.
 */
object Shared {
    /** Fixed id for the one countdown record, so both phones edit the same row. */
    const val COUNTDOWN_ID = "c0de000000000001"

    fun items(context: Context, feature: String): List<SharedItem> =
        ChatStore.list(context).mapNotNull { m ->
            val j = m.json
            if (j.optString("t") != "sd" || j.optString("f") != feature || j.optInt("del") == 1) return@mapNotNull null
            SharedItem(m.id, j.optLong("ts", m.ts), m.outgoing, j.optJSONObject("d") ?: JSONObject())
        }

    fun item(context: Context, id: String): SharedItem? {
        val m = ChatStore.get(context, id) ?: return null
        val j = m.json
        if (j.optString("t") != "sd" || j.optInt("del") == 1) return null
        return SharedItem(m.id, j.optLong("ts", m.ts), m.outgoing, j.optJSONObject("d") ?: JSONObject())
    }

    /** Creates or changes a record and tells the partner. Returns its id. [wake] pings the partner's phone. */
    suspend fun put(context: Context, feature: String, id: String?, d: JSONObject, wake: Boolean = false): String =
        withContext(Dispatchers.IO) {
            val rowId = id ?: ChatStore.newId()
            write(context, feature, rowId, d, 0, wake)
            rowId
        }

    suspend fun remove(context: Context, feature: String, id: String) {
        withContext(Dispatchers.IO) {
            write(context, feature, id, JSONObject(), 1, false)
        }
    }

    private suspend fun write(context: Context, feature: String, id: String, d: JSONObject, del: Int, wake: Boolean) {
        val ts = System.currentTimeMillis()
        val json = JSONObject().put("t", "sd").put("id", id).put("f", feature).put("ts", ts).put("d", d).put("del", del)
        val existing = ChatStore.get(context, id)
        if (existing == null) {
            ChatStore.insert(context, ChatMessage(id, ts, outgoing = true, status = ChatStore.SENT, json = json))
        } else {
            ChatStore.updateJson(context, id, json)
        }
        try {
            // DATA-FLOW: this record, padded and sealed, posted to my relay outbox. The relay stores ciphertext only.
            Outbox.send(context, json, wake)
        } catch (_: Exception) {
            // The record is saved here. The next change sends again.
        }
    }

    /** A record from the partner. A newer timestamp replaces the stored one. */
    fun onIncoming(context: Context, json: JSONObject) {
        val id = ChatStore.normalizeId(json.optString("id")) ?: return
        if (json.optString("f").isEmpty()) return
        val ts = json.optLong("ts", System.currentTimeMillis())
        val existing = ChatStore.get(context, id)
        if (existing == null) {
            ChatStore.insert(context, ChatMessage(id, ts, outgoing = false, status = ChatStore.RECEIVED, json = json))
        } else if (ts > existing.json.optLong("ts", 0L)) {
            ChatStore.updateJson(context, id, json)
        }
    }
}
