package app.loviedowpie

import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Receives the empty FCM wake ping and pulls the inbox. Also stores a new
 * token and, once paired, gives it to the relay so the partner's wake can
 * find this phone.
 *
 * DATA-FLOW: the ping's only field is "w". It has no location and no message.
 * [onNewToken] sends the token to the relay (see [Relay.tokenPut]) when paired.
 */
class FcmService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        val app = applicationContext
        Store.setFcmToken(app, token)
        if (!Store.isPaired(app)) return
        val inbox = Store.inboxId(app)
        if (inbox.length != 32) return
        AppScope.scope.launch {
            // DATA-FLOW: FCM device token, to the relay under my inbox id, not encrypted (TLS only). No message content.
            Relay.tokenPut(inbox, token)
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        // DATA-FLOW: empty wake ping ("w") from Google FCM. No location, no message body. Triggers an encrypted pull from the relay.
        if (message.data["w"] == null) return
        // The process is allowed about 20 seconds. Blocking here is deliberate.
        runBlocking {
            withTimeoutOrNull(18_000) {
                Inbox.process(this@FcmService)
            }
        }
    }
}

/**
 * Starts Firebase Messaging from [BuildConfig] only. When the FCM ids are blank
 * (no local.properties), this does nothing and the rest of the app still works;
 * the partner's messages wait in the mailbox until the app is opened.
 *
 * Analytics collection is not turned on. The manifest also disables auto-init
 * so the SDK does not register with Google until [init] runs.
 */
object Fcm {
    @Volatile
    var enabled: Boolean = false
        private set

    fun init(context: android.content.Context) {
        val app = context.applicationContext
        if (
            BuildConfig.FCM_PROJECT_ID.isBlank() ||
            BuildConfig.FCM_APP_ID.isBlank() ||
            BuildConfig.FCM_API_KEY.isBlank() ||
            BuildConfig.FCM_SENDER_ID.isBlank()
        ) {
            return
        }
        try {
            if (FirebaseApp.getApps(app).isEmpty()) {
                val options = FirebaseOptions.Builder()
                    .setProjectId(BuildConfig.FCM_PROJECT_ID)
                    .setApplicationId(BuildConfig.FCM_APP_ID)
                    .setApiKey(BuildConfig.FCM_API_KEY)
                    .setGcmSenderId(BuildConfig.FCM_SENDER_ID)
                    .build()
                FirebaseApp.initializeApp(app, options)
            }
            enabled = true
            // DATA-FLOW: asks Google FCM for this device's token. No message content. The token is saved locally and, if already paired, sent to the relay.
            FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                if (!task.isSuccessful) return@addOnCompleteListener
                val token = task.result ?: return@addOnCompleteListener
                Store.setFcmToken(app, token)
                if (!Store.isPaired(app)) return@addOnCompleteListener
                val inbox = Store.inboxId(app)
                if (inbox.length != 32) return@addOnCompleteListener
                AppScope.scope.launch {
                    // DATA-FLOW: FCM device token, to the relay under my inbox id, not encrypted (TLS only).
                    Relay.tokenPut(inbox, token)
                }
            }
        } catch (_: Exception) {
            enabled = false
        }
    }

    /** DATA-FLOW: current FCM token, from Google. Null when FCM is off or the request fails. */
    suspend fun fetchToken(): String? {
        if (!enabled) return null
        return suspendCancellableCoroutine { cont ->
            try {
                FirebaseMessaging.getInstance().token
                    .addOnSuccessListener { token ->
                        if (cont.isActive) cont.resume(token)
                    }
                    .addOnFailureListener {
                        if (cont.isActive) cont.resume(null)
                    }
            } catch (_: Exception) {
                if (cont.isActive) cont.resume(null)
            }
        }
    }
}
