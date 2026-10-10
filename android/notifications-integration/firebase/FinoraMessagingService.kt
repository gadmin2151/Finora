package work.gadmin.finora.notifications

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/** Copy through integration.patch only after Firebase configuration is available. */
class FinoraMessagingService : FirebaseMessagingService() {
    override fun onMessageReceived(message: RemoteMessage) {
        // Server MUST send data-only. Notification payloads bypass this handler in background.
        if (message.notification != null) return
        PushRuntime.receiver?.invoke(message.data)
    }

    override fun onNewToken(token: String) {
        PushRuntime.tokenRotated?.invoke(PushToken(token))
    }

    override fun onDeletedMessages() {
        PushRuntime.messagesDeleted?.invoke()
    }
}

/**
 * Application installs callbacks synchronously, including process starts caused by FCM. No service
 * networking: show a neutral notification immediately; fetch on explicit tap. Token callbacks
 * should enqueue one-time lifecycle-owned work, never periodic polling.
 */
object PushRuntime {
    @Volatile var receiver: ((Map<String, String>) -> Unit)? = null
    @Volatile var tokenRotated: ((PushToken) -> Unit)? = null
    @Volatile var messagesDeleted: (() -> Unit)? = null
}
