package work.gadmin.finora.notifications

import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

class FirebasePushProvider : PushProvider {
    override val configured: Boolean
        get() = runCatching { FirebaseApp.getInstance() }.isSuccess

    override suspend fun token(): PushToken = suspendCancellableCoroutine { continuation ->
        FirebaseMessaging.getInstance()
            .apply { isAutoInitEnabled = true }
            .token
            .addOnCompleteListener { task ->
                if (continuation.isActive) {
                    if (task.isSuccessful) continuation.resume(PushToken(task.result))
                    else
                        continuation.resumeWithException(
                            IllegalStateException("Push registration unavailable")
                        )
                }
            }
    }

    override suspend fun deleteToken(): Unit = suspendCancellableCoroutine { continuation ->
        FirebaseMessaging.getInstance()
            .apply { isAutoInitEnabled = false }
            .deleteToken()
            .addOnCompleteListener { task ->
                if (continuation.isActive) {
                    if (task.isSuccessful) continuation.resume(Unit)
                    else
                        continuation.resumeWithException(
                            IllegalStateException("Push revocation unavailable")
                        )
                }
            }
    }
}
