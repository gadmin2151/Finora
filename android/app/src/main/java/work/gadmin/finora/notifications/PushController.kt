package work.gadmin.finora.notifications

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import work.gadmin.finora.data.*

/** No timer or periodic work: only login, explicit preference changes and FCM callbacks. */
class PushController(
    private val provider: PushProvider,
    private val api: PushApi,
    private val currentScope: () -> PushScope?,
    private val installationId: String,
    private val clearNotifications: () -> Unit,
    private val installedVersionCode: () -> Long = { 0 },
    private val receiptAllOrganizations: () -> Boolean = { false },
) {
    private val mutex = Mutex()

    private class Binding(
        val scope: PushScope,
        val registration: PushRegistration,
        val tokenHash: String,
    )

    private var binding: Binding? = null
    private var preferences = PushPreferences()
    @Volatile
    var state = PushState.UNCONFIGURED
        private set

    private suspend fun revokeBinding() {
        val old = binding ?: return
        // Retain failed revocation to prevent re-binding a token to another account.
        api.revoke(old.scope, old.registration)
        binding = null
    }

    suspend fun synchronize(permission: PushPermission, rotatedToken: PushToken? = null) =
        mutex.withLock {
            val scope = currentScope()
            state = PushState.REGISTERING
            try {
                if (!provider.configured || scope == null) {
                    clearNotifications()
                    revokeBinding()
                    state = if (scope == null) PushState.SIGNED_OUT else PushState.UNCONFIGURED
                    return@withLock
                }
                if (!api.deliveryConfigured(scope)) {
                    clearNotifications()
                    revokeBinding()
                    state = PushState.UNCONFIGURED
                    return@withLock
                }
                preferences = api.preferences(scope)
                if (currentScope() != scope) {
                    state = PushState.REVOKED
                    return@withLock
                }
                val receiptGate =
                    receiptAllOrganizations() && api.receiptSubscriptions(scope).isNotEmpty()
                if (currentScope() != scope) {
                    state = PushState.REVOKED
                    return@withLock
                }
                if (
                    (!preferences.enabled && !receiptGate) || permission != PushPermission.GRANTED
                ) {
                    clearNotifications()
                    revokeBinding()
                    state =
                        when {
                            !preferences.enabled && !receiptGate -> PushState.DISABLED
                            permission == PushPermission.DENIED -> PushState.PERMISSION_DENIED
                            else -> PushState.CHANNEL_DISABLED
                        }
                    return@withLock
                }
                val old = binding
                val changedAccount =
                    old != null &&
                        (old.scope.server != scope.server || old.scope.userId != scope.userId)
                if (changedAccount) {
                    clearNotifications()
                    revokeBinding()
                    provider.deleteToken()
                }
                val token =
                    if (changedAccount) provider.token() else rotatedToken ?: provider.token()
                val hash =
                    java.security.MessageDigest.getInstance("SHA-256")
                        .digest(
                            (token.value +
                                    "\u0000" +
                                    receiptAllOrganizations() +
                                    "\u0000" +
                                    installedVersionCode())
                                .toByteArray()
                        )
                        .joinToString("") { "%02x".format(it) }
                if (currentScope() != scope) {
                    state = PushState.REVOKED
                    return@withLock
                }
                if (binding?.let { it.scope == scope && it.tokenHash == hash } == true) {
                    state = PushState.READY
                    return@withLock
                }
                clearNotifications()
                // Same-user token/org/session rotation is an atomic server upsert. Revoking first
                // would disable a healthy binding if the server responds with Retry-After.
                val registration =
                    try {
                        api.register(scope, installationId, token)
                    } catch (retry: PushRetryException) {
                        kotlinx.coroutines.delay(retry.retryAfterMillis)
                        if (currentScope() != scope) {
                            state = PushState.REVOKED
                            return@withLock
                        }
                        api.register(scope, installationId, token) // one bounded retry, no polling
                    }
                binding = Binding(scope, registration, hash)
                if (!registration.deliveryConfigured) {
                    revokeBinding()
                    state = PushState.UNCONFIGURED
                } else if (currentScope() != scope) {
                    revokeBinding()
                    state = PushState.REVOKED
                } else state = PushState.READY
            } catch (cancelled: CancellationException) {
                state = PushState.ERROR
                throw cancelled
            } catch (_: PushRevokedException) {
                state = PushState.REVOKED
            } catch (_: Exception) {
                state = PushState.ERROR
            }
        }

    /** Must run before destroying the old authenticated session; backend also revokes on logout. */
    suspend fun logout() = mutex.withLock {
        state = PushState.REVOKED
        clearNotifications()
        preferences = PushPreferences()
        var failed = false
        try {
            revokeBinding()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            failed = true
        }
        try {
            if (provider.configured) provider.deleteToken()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            failed = true
        }
        state = if (failed) PushState.ERROR else PushState.SIGNED_OUT
    }

    suspend fun setEnabled(enabled: Boolean, permission: PushPermission) {
        mutex.withLock {
            val scope = currentScope() ?: return@withLock
            try {
                preferences =
                    api.savePreferences(scope, api.preferences(scope).copy(enabled = enabled))
                if (currentScope() != scope) {
                    state = PushState.REVOKED
                    return@withLock
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                state = PushState.ERROR
                return
            }
        }
        synchronize(permission)
    }

    /** A push never opens a screen. This runs only for the immutable notification tap. */
    suspend fun openEvent(
        eventId: String,
        expectedScope: PushScope,
        navigationScope: NavigationScope,
        access: NavigationAccess,
        available: Set<NativeRoute>,
        nowMillis: Long,
    ): PushOpenResult = mutex.withLock {
        fun rejected() =
            PushOpenResult(NativeNavigationResolution.Rejected(NavigationRejection.STALE_SCOPE))
        if (
            currentScope() != expectedScope ||
                PushEnvelope.eventId(mapOf("event_id" to eventId)) == null
        )
            return@withLock rejected()
        if (navigationScope.organizationId != expectedScope.organizationId)
            return@withLock rejected()
        try {
            val event = api.event(expectedScope, eventId)
            if (
                currentScope() != expectedScope ||
                    event.id != eventId ||
                    event.organizationId != expectedScope.organizationId
            )
                return@withLock rejected()
            event.updaterVersion?.let { version ->
                val request = event.navigation
                if (
                    request.route != PushUpdatePolicy.ROUTE ||
                        request.scope != navigationScope ||
                        request.entities.isNotEmpty() ||
                        event.targetMonth != null ||
                        request.error ||
                        !request.assistantMessage ||
                        request.expiresAtMillis == null ||
                        nowMillis >= request.expiresAtMillis ||
                        !PushUpdatePolicy.newer(version, installedVersionCode())
                )
                    return@withLock rejected()
                // Dedicated read-only host destination, never NativeRoute.SETTINGS or a URL.
                return@withLock PushOpenResult(
                    NativeNavigationResolution.Rejected(NavigationRejection.UNKNOWN_ROUTE),
                    updaterVersion = version,
                )
            }
            // Admin/server settings and arbitrary URLs are never push destinations.
            val allowed =
                setOf(
                    NativeRoute.OVERVIEW,
                    NativeRoute.RECEIPTS,
                    NativeRoute.INCOME,
                    NativeRoute.TRANSACTIONS,
                    NativeRoute.DEBTS,
                    NativeRoute.BILLS,
                    NativeRoute.BUDGETS,
                )
            if (NativeRoute.parse(event.navigation.route) !in allowed)
                return@withLock PushOpenResult(
                    NativeNavigationResolution.Rejected(NavigationRejection.UNKNOWN_ROUTE)
                )
            if (event.targetMonth != null) {
                require(event.navigation.route == "bills")
                require(Regex("^[0-9]{4}-(0[1-9]|1[0-2])$").matches(event.targetMonth))
            }
            val resolution =
                NativeNavigationResolver.resolve(
                    event.navigation,
                    navigationScope,
                    access,
                    true,
                    nowMillis,
                    available,
                )
            PushOpenResult(
                resolution,
                event.targetMonth.takeIf { resolution is NativeNavigationResolution.Open },
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: PushRevokedException) {
            state = PushState.REVOKED
            rejected()
        } catch (_: Exception) {
            state = PushState.ERROR
            PushOpenResult(NativeNavigationResolution.Rejected(NavigationRejection.INVALID_MESSAGE))
        }
    }
}
