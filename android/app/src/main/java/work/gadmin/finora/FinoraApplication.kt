package work.gadmin.finora

import android.app.Application
import java.time.YearMonth
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import work.gadmin.finora.data.*
import work.gadmin.finora.notifications.*

/** Installs data-only callbacks even when FCM starts a process with no Activity. */
class FinoraApplication : Application() {
    @Volatile var pushHost: PushHost? = null
    private val background = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        val store = PushInstallationStore(this)
        val identity = PushSessionStore(this)
        val sessions = SessionStore(this)
        val notifications = PushNotifications(this, MainActivity::class.java, R.mipmap.ic_launcher)
        fun active(scope: PushScope): Boolean {
            val saved = sessions.read() ?: return false
            return identity.active(scope) &&
                saved.user.id == scope.userId &&
                serverOrigin(saved.server) == scope.server &&
                saved.user.organizations.any { it.id == scope.organizationId }
        }
        PushRuntime.receiver = { data ->
            val scope = store.scope()?.takeIf(::active)
            notifications.show(data, scope, scope != null)
        }
        PushRuntime.tokenRotated = { token ->
            val host = pushHost
            if (host != null) host.synchronize(token)
            else {
                val expected = store.scope()?.takeIf(::active)
                if (expected != null) {
                    store.disable()
                    background.launch {
                        runCatching {
                            withTimeout(30000) {
                                val saved = sessions.read() ?: return@withTimeout
                                val client =
                                    ApiClient(saved.server, saved.cookie).also {
                                        it.csrf = saved.user.csrf
                                    }
                                fun current(): PushScope? = expected.takeIf {
                                    active(it) &&
                                        (pushHost == null || pushHost?.currentScope() == it)
                                }
                                val transport =
                                    object : PushTransport {
                                        override suspend fun get(
                                            scope: PushScope,
                                            path: String,
                                        ): JsonObject {
                                            if (current() != scope) throw PushRevokedException()
                                            return client.pushGet(path, scope.organizationId)
                                        }

                                        override suspend fun getContext(
                                            scope: PushScope,
                                            path: String,
                                        ): JsonObject {
                                            if (current() != scope) throw PushRevokedException()
                                            return client.pushGet(path, null)
                                        }

                                        override suspend fun write(
                                            scope: PushScope,
                                            path: String,
                                            method: String,
                                            body: JsonObject?,
                                        ): JsonObject {
                                            if (current() != scope) throw PushRevokedException()
                                            return client.pushWrite(
                                                path,
                                                scope.organizationId,
                                                method,
                                                body,
                                            )
                                        }
                                    }
                                val api =
                                    PushApiContract(
                                        MappedPushTransport(transport, ::current) {
                                            store.disable()
                                            identity.invalidate()
                                            notifications.clear()
                                        },
                                        installedPushVersion(this@FinoraApplication),
                                    ) {
                                        NavigationScope(
                                            expected.organizationId,
                                            YearMonth.now().toString(),
                                            "${expected.userId}@${expected.server}",
                                        )
                                    }
                                api.setReceiptAllOrganizations(store.receiptAllOrganizations())
                                val controller =
                                    PushController(
                                        FirebasePushProvider(),
                                        api,
                                        ::current,
                                        store.installationId(),
                                        notifications::clear,
                                        { installedPushVersion(this@FinoraApplication)?.code ?: 0 },
                                        store::receiptAllOrganizations,
                                    )
                                try {
                                    synchronizeColdPush(
                                        controller,
                                        notifications.permission(),
                                        token,
                                        expected,
                                        ::current,
                                        store::enable,
                                    )
                                } finally {
                                    client.cancel()
                                }
                            }
                        }
                    }
                }
            }
        }
        PushRuntime.messagesDeleted = { pushHost?.synchronize() }
    }
}
