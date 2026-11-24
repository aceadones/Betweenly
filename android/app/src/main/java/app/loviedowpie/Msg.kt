package app.loviedowpie

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * What [Inbox.process] did with the mailbox.
 * [OpenFailed] is only returned while pairing is confirming the first message.
 */
enum class InboxOutcome {
    Empty,
    Applied,
    OpenFailed,
}

/**
 * Plaintext message codec. Bytes on the wire are always ciphertext; this object
 * only builds and parses the JSON that [Core.seal] / [Core.open] wrap.
 *
 * Layout, before encryption: a 2-byte big-endian length, the UTF-8 JSON, then
 * zero padding out to a multiple of 512 bytes. Padding keeps a short location
 * update the same size as any other short update.
 *
 * DATA-FLOW: no network. See [Outbox] and [Inbox].
 */
object Msg {
    private const val PAD = 512
    private const val MAX_JSON = 65_000

    fun loc(
        lat: Double,
        lon: Double,
        acc: Float,
        bat: Int,
        chg: Boolean,
        ts: Long,
        live: Boolean,
    ): JSONObject = JSONObject()
        .put("t", "loc")
        .put("lat", lat)
        .put("lon", lon)
        .put("acc", acc.toDouble())
        .put("bat", bat)
        .put("chg", chg)
        .put("ts", ts)
        .put("live", live)

    fun bat(bat: Int, chg: Boolean, ts: Long): JSONObject = JSONObject()
        .put("t", "bat")
        .put("bat", bat)
        .put("chg", chg)
        .put("ts", ts)

    fun share(on: Boolean, ts: Long = System.currentTimeMillis()): JSONObject = JSONObject()
        .put("t", "share")
        .put("on", on)
        .put("ts", ts)

    fun ring(): JSONObject = JSONObject().put("t", "ring")

    fun ringStop(): JSONObject = JSONObject().put("t", "ringstop")

    /**
     * Profile JSON. A JPEG over 48KB, or one that pushes the JSON past the
     * 2-byte length limit, is sent as an empty avatar rather than truncated.
     */
    fun profile(name: String, avatarJpeg: ByteArray?): JSONObject {
        val clean = name.replace("\n", " ").replace("\r", " ").take(80)
        val avatar = avatarJpeg?.takeIf { it.isNotEmpty() && it.size <= Store.MAX_AVATAR_BYTES }
        val withAvatar = profileJson(clean, avatar)
        val size = withAvatar.toString().toByteArray(Charsets.UTF_8).size
        return if (avatar != null && size > MAX_JSON) profileJson(clean, null) else withAvatar
    }

    private fun profileJson(name: String, avatar: ByteArray?): JSONObject {
        val encoded = if (avatar == null) "" else Base64.encodeToString(avatar, Base64.NO_WRAP)
        return JSONObject()
            .put("t", "profile")
            .put("name", name)
            .put("avatar", encoded)
    }

    /** Null when the JSON is too large for the 2-byte length. */
    fun pad(json: JSONObject): ByteArray? {
        val payload = json.toString().toByteArray(Charsets.UTF_8)
        if (payload.isEmpty() || payload.size > 65535) return null
        val size = ((2 + payload.size + PAD - 1) / PAD) * PAD
        val out = ByteArray(size)
        out[0] = (payload.size ushr 8).toByte()
        out[1] = (payload.size and 0xff).toByte()
        payload.copyInto(out, destinationOffset = 2)
        return out
    }

    fun unpad(padded: ByteArray): JSONObject? {
        if (padded.size < PAD || padded.size % PAD != 0) return null
        val len = ((padded[0].toInt() and 0xff) shl 8) or (padded[1].toInt() and 0xff)
        if (len <= 0 || 2 + len > padded.size) return null
        return try {
            JSONObject(String(padded, 2, len, Charsets.UTF_8))
        } catch (_: Exception) {
            null
        }
    }
}

/**
 * Serializes every seal and open. The session counter lives inside the blob
 * [Core.seal] returns; two overlapping seals would reuse a counter and the
 * partner would drop one message as a replay.
 */
internal object SessionGate {
    val mutex = Mutex()
}

/**
 * Encrypts one message and posts it to my outbox. The new session (first 80
 * bytes of the seal result) is saved before the network call, because the
 * send counter has already moved. A lost post skips a counter; the partner
 * accepts gaps. A post that lands and then fails to save would make the next
 * message a replay, so the save comes first.
 */
object Outbox {
    /**
     * DATA-FLOW: [json] is padded, sealed with the current session, and the
     * ciphertext is posted to my relay outbox. The relay cannot read it.
     * [wake] adds an empty FCM ping for my partner (no message body in the ping).
     */
    suspend fun send(context: Context, json: JSONObject, wake: Boolean = false): Boolean =
        SessionGate.mutex.withLock {
            withContext(Dispatchers.IO) { sendBody(context, json, wake) }
        }

    /** Saves a brand-new session and sends [json] in the same lock, so nothing can seal in between. */
    internal suspend fun installAndSend(
        context: Context,
        session: ByteArray,
        json: JSONObject,
        wake: Boolean,
    ): Boolean = SessionGate.mutex.withLock {
        val gen = Store.generation()
        if (!Store.setSessionIf(context, session, gen)) return@withLock false
        withContext(Dispatchers.IO) { sendBody(context, json, wake) }
    }

    /** DATA-FLOW: my name and avatar, encrypted, to my partner via the relay. */
    suspend fun sendProfile(context: Context, wake: Boolean = true): Boolean =
        send(context, Msg.profile(Store.myName(context), Store.myAvatar(context)), wake)

    /** DATA-FLOW: a ring command, encrypted, to my partner, plus an empty wake ping. */
    suspend fun sendRing(context: Context): Boolean = send(context, Msg.ring(), wake = true)

    /** DATA-FLOW: a ring-stop command, encrypted, to my partner, plus an empty wake ping. */
    suspend fun sendRingStop(context: Context): Boolean = send(context, Msg.ringStop(), wake = true)

    private suspend fun sendBody(context: Context, json: JSONObject, wake: Boolean): Boolean {
        val gen = Store.generation()
        val session = Store.session(context) ?: return false
        val padded = Msg.pad(json) ?: return false
        val sealed = try {
            Core.seal(session, padded)
        } catch (_: Exception) {
            return false
        }
        if (sealed.size <= Core.SESSION_LEN) return false
        val newSession = sealed.copyOfRange(0, Core.SESSION_LEN)
        val ciphertext = sealed.copyOfRange(Core.SESSION_LEN, sealed.size)
        if (!Store.setSessionIf(context, newSession, gen)) return false
        if (Store.generation() != gen) return false
        val outbox = try {
            Core.sessionInfo(newSession, 2)
        } catch (_: Exception) {
            return false
        }
        if (outbox.length != 32) return false
        // DATA-FLOW: ciphertext of this message, to my relay outbox, encrypted.
        return Relay.boxPost(outbox, ciphertext, wake)
    }
}

/**
 * Pulls my inbox, opens each blob, and applies it to [Store].
 *
 * Call [process] whenever the screen is open. Live location updates are posted
 * without a wake ping (a ping every few seconds would burn battery), so they
 * sit in the mailbox until the next pull. Share, ring, profile and the final
 * "live off" update do wake.
 *
 * A blob that fails to open is skipped, except during the pairing confirm
 * window, where a total failure means the codes did not match.
 */
object Inbox {
    /**
     * While true, [process] does not pull. Pairing owns the inbox for those
     * seconds so a wake ping cannot consume the partner's profile first.
     */
    @Volatile
    internal var holdForPairing: Boolean = false

    suspend fun process(context: Context): InboxOutcome {
        if (holdForPairing) return InboxOutcome.Empty
        return drain(context, strict = false)
    }

    internal suspend fun confirmPartner(context: Context): InboxOutcome = drain(context, strict = true)

    private suspend fun drain(context: Context, strict: Boolean): InboxOutcome =
        SessionGate.mutex.withLock {
            withContext(Dispatchers.IO) { drainBody(context, strict) }
        }

    private suspend fun drainBody(context: Context, strict: Boolean): InboxOutcome {
        val gen = Store.generation()
        var session = Store.session(context) ?: return InboxOutcome.Empty
        val inbox = try {
            Core.sessionInfo(session, 1)
        } catch (_: Exception) {
            return InboxOutcome.Empty
        }
        if (inbox.length != 32) return InboxOutcome.Empty
        // DATA-FLOW: ciphertext blobs, from my relay inbox, encrypted. The relay deletes them on read.
        val blobs = Relay.boxGet(inbox)
        if (blobs.isEmpty()) return InboxOutcome.Empty
        var accepted = 0
        var failed = 0
        for (blob in blobs) {
            if (Store.generation() != gen) break
            val opened = try {
                Core.open(session, blob)
            } catch (_: Exception) {
                ByteArray(0)
            }
            if (opened.size <= Core.SESSION_LEN) {
                failed++
                continue
            }
            val newSession = opened.copyOfRange(0, Core.SESSION_LEN)
            if (!Store.setSessionIf(context, newSession, gen)) break
            session = newSession
            accepted++
            val json = Msg.unpad(opened.copyOfRange(Core.SESSION_LEN, opened.size)) ?: continue
            apply(context, json)
        }
        return when {
            accepted > 0 -> InboxOutcome.Applied
            failed > 0 && strict -> InboxOutcome.OpenFailed
            else -> InboxOutcome.Empty
        }
    }

    private fun apply(context: Context, json: JSONObject) {
        try {
            when (json.optString("t")) {
                "loc" -> applyLoc(context, json)
                "bat" -> applyBat(context, json)
                "share" -> Store.setPartnerSharing(context, json.optBoolean("on", false))
                "ring" -> RingService.start(context)
                "ringstop" -> RingService.stop(context)
                "profile" -> applyProfile(context, json)
                "chat", "att", "link", "react" -> ChatInbound.handle(context, json)
                "draw" -> DrawInbound.handle(context, json)
            }
        } catch (_: Exception) {
            // One bad field should not drop the rest of the mailbox.
        }
    }

    private fun applyLoc(context: Context, json: JSONObject) {
        val lat = json.optDouble("lat", Double.NaN)
        val lon = json.optDouble("lon", Double.NaN)
        if (lat.isNaN() || lon.isNaN() || lat !in -90.0..90.0 || lon !in -180.0..180.0) return
        val prev = Store.partnerLoc(context)
        val now = System.currentTimeMillis()
        Store.setPartnerLoc(
            context,
            PartnerLoc(
                lat = lat,
                lon = lon,
                acc = json.optDouble("acc", 0.0).toFloat(),
                bat = if (json.has("bat")) json.optInt("bat", -1) else (prev?.bat ?: -1),
                chg = if (json.has("chg")) json.optBoolean("chg", false) else (prev?.chg ?: false),
                ts = json.optLong("ts", now),
                live = json.optBoolean("live", false),
                lastSeen = now,
            ),
        )
        Store.setPartnerSharing(context, true)
    }

    private fun applyBat(context: Context, json: JSONObject) {
        val prev = Store.partnerLoc(context)
        val now = System.currentTimeMillis()
        Store.setPartnerLoc(
            context,
            PartnerLoc(
                lat = prev?.lat,
                lon = prev?.lon,
                acc = prev?.acc,
                bat = json.optInt("bat", prev?.bat ?: -1),
                chg = if (json.has("chg")) json.optBoolean("chg", false) else (prev?.chg ?: false),
                ts = json.optLong("ts", now),
                live = false,
                lastSeen = now,
            ),
        )
    }

    private fun applyProfile(context: Context, json: JSONObject) {
        Store.setPartnerName(context, json.optString("name"))
        val encoded = json.optString("avatar")
        if (encoded.isEmpty()) {
            Store.setPartnerAvatar(context, null)
            return
        }
        if (encoded.length > 70_000) return
        val bytes = try {
            Base64.decode(encoded, Base64.DEFAULT)
        } catch (_: IllegalArgumentException) {
            null
        }
        if (bytes != null && bytes.size <= Store.MAX_AVATAR_BYTES) {
            Store.setPartnerAvatar(context, bytes)
        }
    }
}
