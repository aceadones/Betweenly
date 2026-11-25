package com.nextlayer.betweenly

import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.os.Binder
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Exported binder Duet uses to listen together. Every bind and every message
 * checks that the caller is `com.nextlayer.duet` signed with this app's
 * certificate. A debug build allows the package name without that match.
 *
 * DATA-FLOW: [DuetBridge.SEND] is the only path that reaches the relay, and
 * only through [Outbox.send]. Nothing in this service reads or returns the
 * session, a file key, or the relay URL.
 */
class MusicBridgeService : Service() {
    private val thread = HandlerThread("duet-bridge").apply { start() }
    private val handler = Handler(thread.looper, object : Handler.Callback {
        override fun handleMessage(msg: Message): Boolean {
            onMessage(msg)
            return true
        }
    })
    private val messenger = Messenger(handler)

    override fun onBind(intent: Intent?): IBinder? {
        if (intent?.action != DuetBridge.ACTION) return null
        if (!DuetBridge.callerAllowed(this, Binder.getCallingUid())) return null
        return messenger.binder
    }

    override fun onUnbind(intent: Intent?): Boolean {
        DuetBridge.onServiceGone()
        return false
    }

    override fun onDestroy() {
        DuetBridge.onServiceGone()
        thread.quitSafely()
        super.onDestroy()
    }

    private fun onMessage(msg: Message) {
        try {
            if (!DuetBridge.callerAllowed(this, msg.sendingUid)) return
            when (msg.what) {
                DuetBridge.REGISTER -> {
                    val reply = replyTo(msg) ?: return
                    DuetBridge.register(this, reply)
                }
                DuetBridge.UNREGISTER -> DuetBridge.unregister()
                DuetBridge.SEND -> sendOutbound(msg)
                DuetBridge.NOW_PLAYING -> {
                    val data = msg.data
                    DuetState.update(
                        this,
                        data?.getString(DuetBridge.TITLE) ?: "",
                        data?.getString(DuetBridge.ARTIST) ?: "",
                        data?.getBoolean(DuetBridge.PLAYING, false) ?: false,
                    )
                    DuetBridge.ack(DuetBridge.NOW_PLAYING, true)
                }
                DuetBridge.SESSION -> {
                    val active = msg.data?.getBoolean(DuetBridge.ACTIVE, false) ?: false
                    DuetBridge.setSession(this, active)
                    DuetBridge.ack(DuetBridge.SESSION, true)
                }
                DuetBridge.PING -> DuetBridge.ack(DuetBridge.PING, true)
                else -> DuetBridge.ack(msg.what, false)
            }
        } catch (_: Exception) {
            // One bad bundle must not take down the process.
        }
    }

    private fun sendOutbound(msg: Message) {
        val outbound = DuetBridge.parseOutbound(msg.data?.getString(DuetBridge.JSON))
        if (outbound == null) {
            DuetBridge.ack(DuetBridge.SEND, false)
            return
        }
        val app = applicationContext
        AppScope.scope.launch {
            val ok = try {
                val json = JSONObject(outbound.body)
                // DATA-FLOW: Duet's music or plist JSON, sealed with the session, posted to my relay outbox. Ciphertext only. Wake is on only for a music invite. No key, session, or relay URL is returned to Duet.
                Outbox.send(app, json, outbound.wake)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                false
            }
            DuetBridge.ack(DuetBridge.SEND, ok)
        }
    }

    @Suppress("DEPRECATION")
    private fun replyTo(msg: Message): Messenger? {
        msg.replyTo?.let { return it }
        val data = msg.data ?: return null
        return try {
            data.classLoader = Messenger::class.java.classLoader
            if (Build.VERSION.SDK_INT >= 33) {
                data.getParcelable(DuetBridge.REPLY_TO, Messenger::class.java)
            } else {
                data.getParcelable<Messenger>(DuetBridge.REPLY_TO)
            }
        } catch (_: Exception) {
            null
        }
    }
}
