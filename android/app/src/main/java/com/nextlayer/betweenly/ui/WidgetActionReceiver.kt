package com.nextlayer.betweenly.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.nextlayer.betweenly.AppScope
import com.nextlayer.betweenly.LocationSharing
import com.nextlayer.betweenly.Msg
import com.nextlayer.betweenly.Outbox
import com.nextlayer.betweenly.R
import com.nextlayer.betweenly.Store
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

/**
 * Handles the ring and live tiles on the home-screen widget.
 * The track and settings tiles open [com.nextlayer.betweenly.MainActivity] directly.
 *
 * DATA-FLOW: ring and live go out through [Outbox] and [LocationSharing], encrypted, to the partner.
 */
class WidgetActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val app = context.applicationContext
        WidgetRefresh.ensure(app)
        when (intent?.action) {
            ACTION_RING -> ring(app)
            ACTION_LIVE -> live(app)
        }
    }

    private fun ring(app: Context) {
        if (!Store.isPaired(app)) {
            Toast.makeText(app, app.getString(R.string.widget_need_pair), Toast.LENGTH_SHORT).show()
            return
        }
        val pending = goAsync()
        AppScope.scope.launch {
            try {
                val ok = try {
                    // DATA-FLOW: a ring command, encrypted, to my partner, plus an empty wake ping. No location in this message.
                    Outbox.send(app, Msg.ring(), wake = true)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    false
                }
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        app,
                        app.getString(if (ok) R.string.ring_sent else R.string.ring_failed),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            } finally {
                pending.finish()
            }
        }
    }

    private fun live(app: Context) {
        if (!Store.isPaired(app)) {
            Toast.makeText(app, app.getString(R.string.widget_need_pair), Toast.LENGTH_SHORT).show()
            return
        }
        val pending = goAsync()
        AppScope.scope.launch {
            try {
                // DATA-FLOW: live on or off. Encrypted share and location messages go to my partner. See LocationSharing.setLive.
                LocationSharing.setLive(app, !Store.liveOn(app))
                BetweenlyWidget.updateAll(app)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // The service was refused. The switch in the app still shows the saved value.
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_RING = "com.nextlayer.betweenly.action.WIDGET_RING"
        const val ACTION_LIVE = "com.nextlayer.betweenly.action.WIDGET_LIVE"
    }
}
