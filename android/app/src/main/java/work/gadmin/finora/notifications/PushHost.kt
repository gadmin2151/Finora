package work.gadmin.finora.notifications

import android.app.Application
import android.content.Intent
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import work.gadmin.finora.*
import work.gadmin.finora.data.*

data class PushHostState(
    val status: PushState = PushState.UNCONFIGURED,
    val preferences: PushPreferences = PushPreferences(),
    val updateNotificationsSupported: Boolean = false,
    val receiptAllOrganizationsSupported: Boolean = false,
    val receiptAllOrganizations: Boolean = false,
)

/** Only the host owns credential-bearing clients; notification code sees scoped transport. */
class PushHost(
    private val application: Application,
    private val coroutines: CoroutineScope,
    private val appState: StateFlow<AppState>,
    private val activeClient: () -> ApiClient?,
    private val nativeScope: () -> NavigationScope,
    private val navigate: (NativeNavigationRequest) -> Unit,
    private val month: (Long) -> Unit,
    private val onAuthError: (Exception) -> Unit,
    private val notice: (String) -> Unit,
    private val onOpenUpdater: (() -> Unit)? = null,
    private val onOpenReceiptOrganization: ((String, String) -> Unit)? = null,
    private val transportOverride: PushTransport? = null,
    private val providerOverride: PushProvider? = null,
    private val permissionOverride: (() -> PushPermission)? = null,
) {
    private val store = PushInstallationStore(application)
    private val sessions = PushSessionStore(application)
    private val provider = providerOverride ?: FirebasePushProvider()
    private val notifications =
        PushNotifications(application, MainActivity::class.java, R.mipmap.ic_launcher)
    private val clients = ConcurrentHashMap<PushScope, ApiClient>()
    private val mutable = MutableStateFlow(PushHostState())
    val state = mutable.asStateFlow()
    private val operations = Mutex()
    private var pendingIntent: Intent? = null
    private val transport =
        object : PushTransport {
            private fun client(scope: PushScope, revoke: Boolean): ApiClient {
                if (!revoke && currentScope() != scope) throw PushRevokedException()
                return clients[scope]
                    ?: activeClient()
                        ?.takeIf {
                            currentScope() == scope && serverOrigin(it.server) == scope.server
                        }
                        ?.also { clients[scope] = it }
                    ?: throw PushRevokedException()
            }

            override suspend fun getContext(scope: PushScope, path: String): JsonObject =
                client(scope, false).pushGet(path, null)

            override suspend fun get(scope: PushScope, path: String): JsonObject =
                client(scope, false).pushGet(path, scope.organizationId)

            override suspend fun write(
                scope: PushScope,
                path: String,
                method: String,
                body: JsonObject?,
            ): JsonObject =
                client(scope, method == "DELETE")
                    .pushWrite(path, scope.organizationId, method, body)
        }
    private val mappedTransport =
        MappedPushTransport(transportOverride ?: transport, ::currentScope) {
            onAuthError(ApiException(401, "Session expired"))
        }
    private val installationId = runCatching { store.installationId() }.getOrNull()
    private val guardedProvider =
        object : PushProvider {
            override val configured
                get() = installationId != null && provider.configured

            override suspend fun token(): PushToken {
                check(installationId != null)
                return provider.token()
            }

            override suspend fun deleteToken() = provider.deleteToken()
        }
    private val installedVersion = installedPushVersion(application)
    private val api =
        PushApiContract(mappedTransport, installedVersion, nativeScope).apply {
            setReceiptAllOrganizations(store.receiptAllOrganizations())
        }
    private val controller =
        PushController(
            guardedProvider,
            api,
            ::currentScope,
            installationId ?: "storage-unavailable",
            notifications::clear,
            { installedVersion?.code ?: 0 },
            store::receiptAllOrganizations,
        )

    init {
        (application as? FinoraApplication)?.pushHost = this
        coroutines.launch {
            appState
                .map {
                    listOf(
                        it.starting,
                        it.user?.id,
                        it.organization?.id,
                        it.server,
                        it.choosingOrganization,
                    )
                }
                .distinctUntilChanged()
                .collect {
                    if (!appState.value.starting) {
                        val data = appState.value
                        data.user?.let { user ->
                            runCatching {
                                sessions.workspace(
                                    serverOrigin(data.server),
                                    user.id,
                                    currentScope()?.organizationId,
                                )
                            }
                        }
                        disableGate()
                        synchronize()
                        consumePending()
                    }
                }
        }
    }

    fun beginSession(server: String, user: String, fresh: Boolean) {
        if (fresh) {
            disableGate()
            store.setReceiptAllOrganizations(false)
            api.setReceiptAllOrganizations(false)
        }
        runCatching { sessions.begin(serverOrigin(server), user, fresh) }
    }

    fun currentScope(): PushScope? {
        val data = appState.value
        val user = data.user ?: return null
        val org = data.organization ?: return null
        if (data.choosingOrganization || user.organizations.none { it.id == org.id }) return null
        val origin = serverOrigin(data.server)
        val generation = sessions.session(origin, user.id) ?: return null
        return PushScope(origin, user.id, generation, org.id)
    }

    fun disableGate() {
        store.disable()
        notifications.clear()
    }

    fun invalidateSession() {
        disableGate()
        store.setReceiptAllOrganizations(false)
        api.setReceiptAllOrganizations(false)
        runCatching { sessions.invalidate() }
        pendingIntent = null
        coroutines.launch { operations.withLock { deletePendingToken() } }
    }

    private suspend fun deletePendingToken(): Boolean {
        if (!sessions.tokenDeletionRequired()) return true
        if (!provider.configured) return false
        return try {
            withTimeout(5000) { provider.deleteToken() }
            sessions.tokenDeleted()
            true
        } catch (_: TimeoutCancellationException) {
            false
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            false
        }
    }

    private fun notificationPermission() =
        permissionOverride?.invoke() ?: notifications.permission()

    fun synchronize(token: PushToken? = null) {
        coroutines.launch {
            operations.withLock {
                val before = currentScope()
                mutable.value = mutable.value.copy(status = PushState.REGISTERING)
                if (!deletePendingToken()) {
                    disableGate()
                    mutable.value = mutable.value.copy(status = PushState.ERROR)
                    return@withLock
                }
                try {
                    withTimeout(30000) { controller.synchronize(notificationPermission(), token) }
                    publish(before)
                } catch (_: TimeoutCancellationException) {
                    disableGate()
                    mutable.value = mutable.value.copy(status = PushState.ERROR)
                }
            }
        }
    }

    private suspend fun publish(expected: PushScope?) {
        if (expected != currentScope()) {
            disableGate()
            mutable.value = PushHostState(PushState.REVOKED)
            return
        }
        var status = controller.state
        val preferences =
            if (expected != null && status !in setOf(PushState.UNCONFIGURED, PushState.SIGNED_OUT))
                try {
                    api.preferences(expected)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    status = PushState.ERROR
                    mutable.value.preferences
                }
            else PushPreferences()
        // Authenticated reads suspend: logout or an organization switch may fence the scope
        // while fetching preferences. Never reopen an old gate or publish its checked values.
        if (expected != currentScope()) {
            disableGate()
            return
        }
        if (status == PushState.READY && expected != null) {
            try {
                store.enable(expected)
            } catch (_: Exception) {
                disableGate()
                status = PushState.ERROR
            }
        } else disableGate()
        if (expected != currentScope()) {
            disableGate()
            return
        }
        mutable.value =
            PushHostState(
                status,
                preferences,
                expected != null && api.supportsUpdateNotifications(expected),
                expected != null && api.supportsReceiptAllOrganizations(expected),
                store.receiptAllOrganizations(),
            )
    }

    private fun fencePreferences(): PushScope? {
        val scope = currentScope() ?: return null
        disableGate()
        sessions.begin(scope.server, scope.userId, true)
        sessions.workspace(scope.server, scope.userId, scope.organizationId)
        return currentScope()
    }

    fun setEnabled(enabled: Boolean) {
        val before = fencePreferences() ?: return
        coroutines.launch {
            operations.withLock {
                if (currentScope() != before) return@withLock
                mutable.value = mutable.value.copy(status = PushState.REGISTERING)
                controller.setEnabled(enabled, notificationPermission())
                publish(before)
            }
        }
    }

    fun setCategories(payments: Boolean, receipts: Boolean, important: Boolean) {
        val before = fencePreferences() ?: return
        coroutines.launch {
            operations.withLock {
                if (currentScope() != before) return@withLock
                mutable.value = mutable.value.copy(status = PushState.REGISTERING)
                try {
                    val saved =
                        api.savePreferences(
                            before,
                            api.preferences(before)
                                .copy(
                                    payments = payments,
                                    receipts = receipts,
                                    important = important,
                                ),
                        )
                    if (currentScope() != before) throw PushRevokedException()
                    mutable.value = mutable.value.copy(preferences = saved)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    if (currentScope() == before)
                        mutable.value = mutable.value.copy(status = PushState.ERROR)
                    return@withLock
                }
                controller.synchronize(notificationPermission())
                publish(before)
            }
        }
    }

    fun setReceiptAllOrganizations(enabled: Boolean) {
        fencePreferences()
        store.setReceiptAllOrganizations(enabled)
        api.setReceiptAllOrganizations(enabled)
        synchronize()
    }

    /** Called by VM only after explicit receipt tap's authorized target organization settles. */
    fun openReceiptEvent(eventId: String, original: PushScope, targetOrganization: String) {
        val current = currentScope() ?: return
        if (
            !PushReceiptRouting.settled(
                original,
                current,
                targetOrganization,
                store.receiptAllOrganizations(),
            )
        )
            return
        coroutines.launch { openAuthenticatedEvent(eventId, current, true) }
    }

    fun setUpdates(enabled: Boolean) {
        val before = fencePreferences() ?: return
        coroutines.launch {
            operations.withLock {
                if (currentScope() != before) return@withLock
                mutable.value = mutable.value.copy(status = PushState.REGISTERING)
                try {
                    api.deliveryConfigured(before)
                    if (currentScope() != before) throw PushRevokedException()
                    if (!api.supportsUpdateNotifications(before)) {
                        publish(before)
                        return@withLock
                    }
                    val saved =
                        api.savePreferences(before, api.preferences(before).copy(updates = enabled))
                    if (currentScope() != before) throw PushRevokedException()
                    mutable.value = mutable.value.copy(preferences = saved)
                    controller.synchronize(notificationPermission())
                    publish(before)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    if (currentScope() == before)
                        mutable.value = mutable.value.copy(status = PushState.ERROR)
                }
            }
        }
    }

    suspend fun logout() = operations.withLock {
        disableGate()
        store.setReceiptAllOrganizations(false)
        api.setReceiptAllOrganizations(false)
        runCatching { sessions.invalidate() }
        pendingIntent = null
        try {
            withTimeoutOrNull(4000) { controller.logout() }
        } finally {
            mutable.value = PushHostState(PushState.SIGNED_OUT)
            coroutines.launch { operations.withLock { deletePendingToken() } }
        }
    }

    fun receiveIntent(intent: Intent) {
        pendingIntent = Intent(intent)
        consumePending()
    }

    fun consumePending() {
        val data = appState.value
        if (data.starting || data.busy || data.choosingOrganization) return
        val expected = currentScope() ?: return
        val pending = pendingIntent ?: return
        pendingIntent = null
        val receiptMode = PushNotifications.isAllOrganizationReceiptTap(pending)
        val event =
            PushNotifications.consumeTap(pending, expected, store.receiptAllOrganizations())
                ?: return
        coroutines.launch {
            if (
                receiptMode &&
                    (!store.receiptAllOrganizations() ||
                        !api.supportsReceiptAllOrganizations(expected))
            )
                return@launch
            if (store.receiptAllOrganizations() && api.supportsReceiptAllOrganizations(expected)) {
                val context =
                    try {
                        api.eventContext(expected, event)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        notice("Уведомление недоступно.")
                        return@launch
                    }
                if (currentScope() != expected || (receiptMode && context.category != "receipts"))
                    return@launch
                if (context.organizationId != expected.organizationId) {
                    val target =
                        PushReceiptRouting.targetOrganization(
                            event,
                            context,
                            expected,
                            currentScope(),
                            appState.value.user?.organizations?.map { it.id }?.toSet().orEmpty(),
                            store.receiptAllOrganizations(),
                        ) ?: return@launch
                    val callback = onOpenReceiptOrganization ?: return@launch
                    callback.invoke(target, event)
                    return@launch
                }
            }
            openAuthenticatedEvent(event, expected, receiptMode)
        }
    }

    private suspend fun openAuthenticatedEvent(
        event: String,
        expected: PushScope,
        receiptOnly: Boolean,
    ) {
        val data = appState.value
        val captured = nativeScope()
        val access =
            NavigationAccess(
                data.organization?.isAdmin == true,
                data.user?.is_server_admin == true,
            )
        val result =
            controller.openEvent(
                event,
                expected,
                captured,
                access,
                NativeRouteContract.available,
                System.currentTimeMillis(),
            )
        if (currentScope() != expected || nativeScope() != captured || appState.value.busy) return
        if (
            receiptOnly &&
                (result.updaterVersion != null ||
                    (result.resolution as? NativeNavigationResolution.Open)?.intent?.route !=
                        NativeRoute.RECEIPTS)
        )
            return
        if (result.updaterVersion != null) {
            if (onOpenUpdater != null) onOpenUpdater.invoke()
            else notice("Обновление доступно. Откройте проверку обновлений в профиле.")
            return
        }
        when (val resolution = result.resolution) {
            is NativeNavigationResolution.Open -> {
                result.targetMonth?.let { target ->
                    month(
                        ChronoUnit.MONTHS.between(
                            YearMonth.parse(captured.month),
                            YearMonth.parse(target),
                        )
                    )
                }
                if (currentScope() != expected) return
                navigate(
                    NativeNavigationRequest(
                        resolution.intent.route.page,
                        nativeScope(),
                        resolution.intent.entities,
                    )
                )
            }
            else -> notice("Уведомление недоступно в текущей организации или сессии.")
        }
    }

    fun detach() {
        (application as? FinoraApplication)?.let { if (it.pushHost === this) it.pushHost = null }
    }
}
