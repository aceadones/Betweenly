package com.nextlayer.betweenly

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Process-wide scope for work that must finish after a screen is gone:
 * sending, FCM token registration, and the location alarm.
 */
object AppScope {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
}

/**
 * Wires process start: FCM (if configured) and the sharing alarm. Also pulls
 * the inbox once so a cold start shows whatever the partner sent while the
 * app was dead.
 *
 * DATA-FLOW: the inbox pull is encrypted ciphertext from the relay. FCM init
 * may register with Google; see [Fcm].
 */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Fcm.init(this)
        if (Store.isPaired(this) && Store.shareOn(this)) {
            if (Store.liveOn(this)) {
                LocationSharing.setLive(this, true)
            } else {
                SharingScheduler.schedule(this)
            }
        }
        if (Store.isPaired(this)) {
            AppScope.scope.launch { Inbox.process(this@App) }
        }
        // DATA-FLOW: none. Deletes leftover files from the old in-app player.
        AppScope.scope.launch { dropLegacyMusic() }
    }

    /** Older builds kept a music database and song copies. Duet has its own. */
    private fun dropLegacyMusic() {
        try {
            deleteDatabase("betweenly-music.db")
            java.io.File(filesDir, "music").deleteRecursively()
            java.io.File(cacheDir, "music").deleteRecursively()
        } catch (_: Exception) {
            // A locked file is tried again the next time the process starts.
        }
    }
}
