package work.gadmin.finora.notifications

import java.net.URI
import java.util.UUID
import work.gadmin.finora.data.NativeNavigationRequest

/** sessionKey is an opaque local generation, never a cookie or credential. */
data class PushScope(
    val server: String,
    val userId: String,
    val sessionKey: String,
    val organizationId: String,
) {
    init {
        val origin = URI(server)
        require(origin.scheme == "https" && origin.host != null && origin.userInfo == null)
        require(
            origin.rawQuery == null &&
                origin.rawFragment == null &&
                origin.path.orEmpty() in setOf("", "/")
        )
        require(userId.isNotBlank() && sessionKey.isNotBlank() && organizationId.isNotBlank())
    }
}

/** Deliberately not a data class: generated toString must not expose the provider token. */
class PushToken(val value: String) {
    init {
        require(value.length in 16..4096 && value.none { it.isWhitespace() || it.isISOControl() })
    }

    override fun toString() = "PushToken([redacted])"
}

enum class PushPermission {
    GRANTED,
    DENIED,
    CHANNEL_DISABLED,
}

enum class PushState {
    UNCONFIGURED,
    SIGNED_OUT,
    DISABLED,
    PERMISSION_DENIED,
    CHANNEL_DISABLED,
    REGISTERING,
    READY,
    REVOKED,
    ERROR,
}

data class PushPreferences(
    val enabled: Boolean = false,
    val payments: Boolean = false,
    val receipts: Boolean = false,
    val important: Boolean = false,
    val timezone: String = "UTC",
    val quietStart: Int? = null,
    val quietEnd: Int? = null,
    val paymentHour: Int = 9,
    val leadDays: Int = 1,
    val updates: Boolean = false,
)

data class PushRegistration(val id: String, val deliveryConfigured: Boolean = true)

class PushRetryException(retryAfterMillis: Long) : Exception("Push registration retry") {
    val retryAfterMillis: Long = retryAfterMillis.also { require(it in 1..10_000) }
}

class PushRevokedException : Exception("Push registration revoked")

data class PushEvent(
    val id: String,
    val organizationId: String,
    val navigation: NativeNavigationRequest,
    val targetMonth: String? = null,
    val updaterVersion: InstalledPushVersion? = null,
)

/** API instance must be bound to this exact authenticated scope; no independent cookie jar. */
data class PushEventContext(val eventId: String, val category: String, val organizationId: String)

interface PushApi {
    suspend fun receiptSubscriptions(scope: PushScope): Set<String> = emptySet()

    suspend fun eventContext(scope: PushScope, eventId: String): PushEventContext =
        throw UnsupportedOperationException("Push context unavailable")

    suspend fun deliveryConfigured(scope: PushScope): Boolean

    suspend fun register(
        scope: PushScope,
        installationId: String,
        token: PushToken,
    ): PushRegistration

    suspend fun revoke(scope: PushScope, registration: PushRegistration)

    suspend fun event(scope: PushScope, eventId: String): PushEvent

    suspend fun preferences(scope: PushScope): PushPreferences

    suspend fun savePreferences(scope: PushScope, preferences: PushPreferences): PushPreferences
}

interface PushProvider {
    val configured: Boolean

    suspend fun token(): PushToken

    suspend fun deleteToken()
}

/** Data-only wire envelope. All title/body/URL/action fields are ignored. */
object PushEnvelope {
    private val uuid =
        Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

    fun eventId(data: Map<String, String>): String? = data["event_id"]?.takeIf { uuid.matches(it) }

    fun installationId(): String = UUID.randomUUID().toString()
}

/**
 * Month is a read-only target selected only after authenticated fetch and explicit tap. Host must
 * recheck captured PushScope before selecting it, then open a freshly scoped request.
 */
data class PushOpenResult(
    val resolution: work.gadmin.finora.data.NativeNavigationResolution,
    val targetMonth: String? = null,
    val updaterVersion: InstalledPushVersion? = null,
)
