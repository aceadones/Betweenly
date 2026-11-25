package com.nextlayer.betweenly

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.os.RemoteException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Local link to the Duet app (`com.nextlayer.duet`).
 *
 * Duet binds to [MusicBridgeService]. Betweenly seals `music` and `plist`
 * JSON that Duet hands over, and hands decrypted copies of those two types
 * back. Session bytes, file keys, and the relay URL are never put in a
 * bridge bundle.
 *
 * DATA-FLOW: the bridge itself is binder IPC on this phone. The only network
 * hop is [Outbox.send] for a `music` or `plist` object. Opening the releases
 * page is a browser intent. Betweenly does not fetch that URL.
 */
object DuetBridge {
    const val ACTION = "com.nextlayer.betweenly.action.MUSIC_BRIDGE"
    const val PACKAGE = "com.nextlayer.duet"
    const val RELEASES = "https://github.com/aceadones/Duet/releases"

    const val REGISTER = 1
    const val UNREGISTER = 2
    const val SEND = 3
    const val NOW_PLAYING = 4
    const val SESSION = 5
    const val PING = 6
    const val INBOUND = 7
    const val STATUS = 8
    const val ACK = 9

    const val JSON = "json"
    const val TITLE = "title"
    const val ARTIST = "artist"
    const val PLAYING = "playing"
    const val ACTIVE = "active"
    const val PAIRED = "paired"
    const val PARTNER_NAME = "partnerName"
    const val PARTNER_ONLINE = "partnerOnline"
    const val OK = "ok"
    const val REQ = "req"
    const val REPLY_TO = "replyTo"

    /** Always this value. Betweenly does not report whether the partner is reachable. */
    const val ONLINE_UNKNOWN = "unknown"

    const val MAX_JSON = 60_000

    private val gate = Any()
    private var client: Messenger? = null
    private var death: IBinder.DeathRecipient? = null
    private var watch: Job? = null
    private var lastStatus: String? = null

    /**
     * Release builds require Duet's package and the same signing certificate.
     * Debug builds accept the package name alone so an emulator can use a
     * different debug keystore.
     */
    fun callerAllowed(context: Context, uid: Int): Boolean {
        if (uid == Process.INVALID_UID || uid < 0) return false
        val pkgs = try {
            context.packageManager.getPackagesForUid(uid)
        } catch (_: Exception) {
            null
        } ?: return false
        if (PACKAGE !in pkgs) return false
        if (BuildConfig.DEBUG) return true
        return try {
            context.packageManager.checkSignatures(uid, Process.myUid()) == PackageManager.SIGNATURE_MATCH
        } catch (_: Exception) {
            false
        }
    }

    fun register(context: Context, reply: Messenger) {
        val binder = try {
            reply.binder
        } catch (_: Exception) {
            return
        }
        val recipient = IBinder.DeathRecipient { drop(reply, unlink = false) }
        try {
            binder.linkToDeath(recipient, 0)
        } catch (_: RemoteException) {
            return
        }
        val previous = synchronized(gate) {
            val old = client
            val oldDeath = death
            client = reply
            death = recipient
            lastStatus = null
            old to oldDeath
        }
        val previousMessenger = previous.first
        val previousDeath = previous.second
        if (previousMessenger != null && previousMessenger.binder !== binder && previousDeath != null) {
            try {
                previousMessenger.binder.unlinkToDeath(previousDeath, 0)
            } catch (_: Exception) {
                // The previous client is already gone.
            }
        }
        ChatNotifications.cancelMusic(context)
        pushStatus(context, force = true)
        watch(context)
        ack(REGISTER, true)
    }

    fun unregister() {
        ack(UNREGISTER, true)
        drop(null, unlink = true)
    }

    fun onServiceGone() {
        drop(null, unlink = true)
    }

    /** True when the package is installed. A missing launcher still counts as installed. */
    @Suppress("DEPRECATION")
    fun installed(context: Context): Boolean {
        return try {
            context.packageManager.getPackageInfo(PACKAGE, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Duet's launcher, or the releases page when that launcher is missing.
     * DATA-FLOW: no request from this process. A browser, if one is installed,
     * loads [RELEASES]. The intent carries no pairing data.
     */
    fun viewIntent(context: Context): Intent {
        val launch = try {
            context.packageManager.getLaunchIntentForPackage(PACKAGE)
        } catch (_: Exception) {
            null
        }
        if (launch != null) {
            return launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val view = Intent(Intent.ACTION_VIEW, Uri.parse(RELEASES))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val match = try {
            context.packageManager.resolveActivity(view, PackageManager.MATCH_DEFAULT_ONLY)
        } catch (_: Exception) {
            null
        }
        val activity = match?.activityInfo
        if (activity != null && activity.packageName.isNotBlank() && activity.name.isNotBlank()) {
            view.setClassName(activity.packageName, activity.name)
        }
        return view
    }

    fun open(context: Context) {
        try {
            context.startActivity(viewIntent(context))
        } catch (_: ActivityNotFoundException) {
            // No Duet launcher and no browser.
        } catch (_: Exception) {
            // The card stays as it is.
        }
    }

    /**
     * A decrypted `music` or `plist` object from the partner.
     * Forwards it to Duet. An `invite` with nobody registered posts a local
     * notice. Every other op is dropped when Duet is not connected.
     *
     * DATA-FLOW: none here. [Inbox] already opened the ciphertext.
     */
    fun deliver(context: Context, json: JSONObject) {
        try {
            if (!Store.isPaired(context)) return
            val type = json.optString("t")
            if (type != "music" && type != "plist") return
            val payload = json.toString()
            if (payload.isEmpty() || payload.toByteArray(Charsets.UTF_8).size > MAX_JSON) return
            val sent = send(INBOUND) { it.putString(JSON, payload) }
            if (!sent && type == "music" && json.optString("op") == "invite") {
                ChatNotifications.onMusicInvite(context)
            }
        } catch (_: Exception) {
            // A bad music payload must not stall the mailbox.
        }
    }

    /** Null when [raw] is not a `music` or `plist` object within [MAX_JSON]. */
    internal fun parseOutbound(raw: String?): Outbound? {
        if (raw.isNullOrEmpty()) return null
        val size = try {
            raw.toByteArray(Charsets.UTF_8).size
        } catch (_: Exception) {
            return null
        }
        if (size <= 0 || size > MAX_JSON) return null
        val json = try {
            JSONObject(raw)
        } catch (_: Exception) {
            return null
        }
        val type = json.optString("t")
        if (type != "music" && type != "plist") return null
        val printed = try {
            json.toString()
        } catch (_: Exception) {
            return null
        }
        val printedSize = try {
            printed.toByteArray(Charsets.UTF_8).size
        } catch (_: Exception) {
            return null
        }
        if (printedSize <= 0 || printedSize > MAX_JSON) return null
        val wake = type == "music" && json.optString("op") == "invite"
        return Outbound(printed, wake)
    }

    /** Listen-together asks the open app to pull every 2 seconds. Unpaired stays on the slow interval. */
    fun setSession(context: Context, active: Boolean) {
        val paired = try {
            Store.isPaired(context)
        } catch (_: Exception) {
            false
        }
        AppState.fastPoll = active && paired
    }

    fun ack(request: Int, ok: Boolean) {
        send(ACK) {
            it.putBoolean(OK, ok)
            it.putInt(REQ, request)
        }
    }

    private fun watch(context: Context) {
        val app = context.applicationContext
        synchronized(gate) {
            if (watch?.isActive == true) return
            watch = AppScope.scope.launch {
                Store.changes.collect {
                    if (synchronized(gate) { client } == null) return@collect
                    pushStatus(app, force = false)
                }
            }
        }
    }

    private fun pushStatus(context: Context, force: Boolean) {
        val paired = try {
            Store.isPaired(context)
        } catch (_: Exception) {
            return
        }
        val name = if (!paired) {
            ""
        } else {
            try {
                Store.partnerName(context).take(80)
            } catch (_: Exception) {
                ""
            }
        }
        val key = if (paired) "1\u0000$name" else "0"
        synchronized(gate) {
            if (client == null) return
            if (!force && key == lastStatus) return
            lastStatus = key
        }
        if (!paired) AppState.fastPoll = false
        val sent = send(STATUS) {
            it.putBoolean(PAIRED, paired)
            it.putString(PARTNER_NAME, name)
            it.putString(PARTNER_ONLINE, ONLINE_UNKNOWN)
        }
        if (!sent) {
            synchronized(gate) {
                if (lastStatus == key) lastStatus = null
            }
        }
    }

    private fun send(what: Int, fill: (Bundle) -> Unit): Boolean {
        val messenger = synchronized(gate) { client } ?: return false
        val msg = Message.obtain(null, what)
        val bundle = Bundle()
        try {
            fill(bundle)
        } catch (_: Exception) {
            msg.recycle()
            return false
        }
        msg.data = bundle
        return try {
            messenger.send(msg)
            true
        } catch (_: RemoteException) {
            drop(messenger, unlink = false)
            false
        } catch (_: Exception) {
            false
        }
    }

    private fun drop(expected: Messenger?, unlink: Boolean) {
        val previous = synchronized(gate) {
            if (expected != null && client !== expected) return
            val old = client
            val oldDeath = death
            client = null
            death = null
            lastStatus = null
            watch?.cancel()
            watch = null
            old to oldDeath
        }
        AppState.fastPoll = false
        if (!unlink) return
        val messenger = previous.first ?: return
        val recipient = previous.second ?: return
        try {
            messenger.binder.unlinkToDeath(recipient, 0)
        } catch (_: Exception) {
            // Already dead.
        }
    }

    internal data class Outbound(val body: String, val wake: Boolean)
}
