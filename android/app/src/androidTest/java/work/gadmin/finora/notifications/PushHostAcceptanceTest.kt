package work.gadmin.finora.notifications

import android.app.Application
import android.os.Build
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import work.gadmin.finora.AppState
import work.gadmin.finora.data.*
import work.gadmin.finora.ui.PaymentSelectionNotice
import work.gadmin.finora.ui.PushSettingsCard

/** Synthetic local identity only. No token generation, network or real notifications. */
@RunWith(AndroidJUnit4::class)
class PushHostAcceptanceTest {
    @get:Rule val compose = createComposeRule()
    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    /** Actual UI -> Host -> authenticated loopback FastAPI -> independent DB readback. */
    @Test
    fun updateTapPersistsOverHttpReloadAndHostRecreation() {
        org.junit.Assume.assumeTrue(Build.MODEL.startsWith("sdk_gphone"))
        org.junit.Assume.assumeTrue(!File(context.noBackupFilesDir, "session.enc").exists())
        val transport = FixtureTransport()
        transport.request("/api/__fixture/reset", "POST")
        val user = transport.login()
        val org = user.organizations.first()
        val app =
            MutableStateFlow(
                AppState(
                    starting = true,
                    server = "https://fixture.invalid",
                    user = user,
                    organization = org,
                )
            )
        var permission = PushPermission.DENIED
        val scopes = mutableListOf<CoroutineScope>()
        fun host(): PushHost {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
            scopes += scope
            return PushHost(
                context.applicationContext as Application,
                scope,
                app,
                { null },
                { NavigationScope(app.value.organization!!.id, "2026-10", "fixture") },
                {},
                {},
                {},
                {},
                transportOverride = transport,
                providerOverride =
                    object : PushProvider {
                        override val configured = true

                        override suspend fun token() = PushToken("fixture-device-token-18083")

                        override suspend fun deleteToken() {}
                    },
                permissionOverride = { permission },
            )
        }
        val first = host()
        try {
            first.beginSession(app.value.server, user.id, true)
            first.synchronize()
            compose.waitUntil(40000) { first.state.value.status == PushState.PERMISSION_DENIED }
            assertTrue(first.state.value.updateNotificationsSupported)
            assertFalse(first.state.value.preferences.updates)
            val originalScope = first.currentScope()
            compose.setContent {
                MaterialTheme {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                        PushSettingsCard(first)
                    }
                }
            }
            val label =
                if (
                    work.gadmin.finora.localization.LanguageRuntime.language ==
                        work.gadmin.finora.localization.AppLanguage.RUSSIAN
                )
                    "Обновления приложения"
                else "App updates"
            compose.onNodeWithText(label).performScrollTo()
            // Opening the actual Card triggers its ON_RESUME synchronization. Wait for the
            // rendered row, not only the pre-composition Host status, before delivering a tap.
            compose.waitUntil(40000) {
                first.state.value.status == PushState.PERMISSION_DENIED &&
                    runCatching { compose.onNodeWithText(label).assertIsEnabled() }.isSuccess
            }
            compose.onNodeWithText(label).assertIsOff().performClick()
            assertNotEquals(
                "Actual tap must dispatch the generation fence",
                originalScope,
                first.currentScope(),
            )
            try {
                compose.waitUntil(40000) {
                    transport.puts.isNotEmpty() &&
                        first.state.value.preferences.updates &&
                        first.state.value.status != PushState.REGISTERING
                }
            } catch (failure: AssertionError) {
                throw AssertionError(
                    "Update tap not persisted: status=${first.state.value.status}, successfulPuts=${transport.puts.size}, preferenceGets=${transport.preferenceGets.get()}, lastHttpStatus=${transport.lastHttpStatus}",
                    failure,
                )
            }
            assertNotEquals(originalScope, first.currentScope())
            assertTrue(transport.puts.last()["updates"]!!.jsonPrimitive.boolean)
            assertTrue(transport.stored(org.id)["updates"]!!.jsonPrimitive.boolean)
            assertEquals(PushState.PERMISSION_DENIED, first.state.value.status)
            compose.onNodeWithText(label).assertIsOn()
            val beforeReload = transport.preferenceGets.get()
            first.synchronize()
            compose.waitUntil(40000) {
                transport.preferenceGets.get() >= beforeReload + 2 &&
                    first.state.value.preferences.updates &&
                    first.state.value.status == PushState.PERMISSION_DENIED
            }
            scopes.first().cancel()
            val recreated = host()
            recreated.beginSession(app.value.server, user.id, false)
            recreated.synchronize()
            compose.waitUntil(40000) { recreated.state.value.status == PushState.PERMISSION_DENIED }
            assertTrue(recreated.state.value.preferences.updates)
            assertTrue(transport.stored(org.id)["updates"]!!.jsonPrimitive.boolean)
            val independent = transport.request("/api/__fixture/state")
            assertEquals(0, independent["provider_sends"]!!.jsonPrimitive.int)
            assertEquals(0, independent["financial_transactions"]!!.jsonPrimitive.int)
        } finally {
            scopes.forEach { it.cancel() }
            PushInstallationStore(context).disable()
            PushSessionStore(context).apply {
                invalidate()
                tokenDeleted()
            }
        }
    }

    @Test
    fun preferencesRetainUpdatesAcrossMasterCategoriesFaultsPermissionAndOrganizations() {
        org.junit.Assume.assumeTrue(Build.MODEL.startsWith("sdk_gphone"))
        org.junit.Assume.assumeTrue(!File(context.noBackupFilesDir, "session.enc").exists())
        val http = FixtureTransport()
        http.request("/api/__fixture/reset", "POST")
        val user = http.login()
        val firstOrg = user.organizations.first()
        val secondOrg = user.organizations.first { it.id != firstOrg.id }
        val app =
            MutableStateFlow(
                AppState(
                    starting = true,
                    server = "https://fixture.invalid",
                    user = user,
                    organization = firstOrg,
                )
            )
        var permission = PushPermission.DENIED
        var reads = 0
        var failReadAt = -1
        var delayReadAt = -1
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val transport =
            object : PushTransport by http {
                override suspend fun get(scope: PushScope, path: String): JsonObject {
                    if (path == "/api/push/preferences") {
                        reads++
                        if (reads == failReadAt)
                            throw java.io.IOException("Synthetic final read failure")
                        if (reads == delayReadAt) {
                            val result = http.get(scope, path)
                            entered.complete(Unit)
                            release.await()
                            return result
                        }
                    }
                    return http.get(scope, path)
                }
            }
        val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val host =
            PushHost(
                context.applicationContext as Application,
                coroutineScope,
                app,
                { null },
                { NavigationScope(app.value.organization!!.id, "2026-10", "fixture") },
                {},
                {},
                {},
                {},
                transportOverride = transport,
                providerOverride =
                    object : PushProvider {
                        override val configured = true

                        override suspend fun token() = PushToken("fixture-device-token-18083")

                        override suspend fun deleteToken() {}
                    },
                permissionOverride = { permission },
            )
        fun await(status: PushState, updates: Boolean, enabled: Boolean = true) {
            compose.waitUntil(40000) {
                host.state.value.status == status &&
                    host.state.value.preferences.updates == updates &&
                    host.state.value.preferences.enabled == enabled &&
                    (status != PushState.READY ||
                        PushInstallationStore(context).scope() == host.currentScope())
            }
        }
        try {
            host.beginSession(app.value.server, user.id, true)
            host.synchronize()
            await(PushState.PERMISSION_DENIED, false)
            host.setUpdates(true)
            await(PushState.PERMISSION_DENIED, true)
            val categoryWrites = http.puts.size
            host.setCategories(true, true, false)
            compose.waitUntil(40000) {
                http.puts.size > categoryWrites &&
                    host.state.value.status == PushState.PERMISSION_DENIED
            }
            await(PushState.PERMISSION_DENIED, true)
            assertTrue(http.stored(firstOrg.id)["updates"]!!.jsonPrimitive.boolean)
            host.setEnabled(false)
            await(PushState.DISABLED, true, false)
            host.setEnabled(true)
            await(PushState.PERMISSION_DENIED, true)
            http.request("/api/__fixture/fail-next-preferences-put", "POST")
            host.setUpdates(false)
            await(PushState.ERROR, true)
            assertTrue(http.stored(firstOrg.id)["updates"]!!.jsonPrimitive.boolean)
            host.synchronize()
            await(PushState.PERMISSION_DENIED, true)
            host.setUpdates(false)
            await(PushState.PERMISSION_DENIED, false)
            assertFalse(http.stored(firstOrg.id)["updates"]!!.jsonPrimitive.boolean)
            permission = PushPermission.GRANTED
            host.synchronize()
            await(PushState.READY, false)
            // A successful PUT followed by a failed final GET must not publish false READY.
            failReadAt = reads + 3
            host.setUpdates(true)
            await(PushState.ERROR, true)
            assertTrue(http.stored(firstOrg.id)["updates"]!!.jsonPrimitive.boolean)
            host.synchronize()
            await(PushState.READY, true)
            // Hold publish's final authenticated preference response, then switch organization.
            delayReadAt = reads + 2
            host.synchronize()
            compose.waitUntil(40000) { entered.isCompleted }
            app.value = app.value.copy(starting = false, organization = secondOrg)
            compose.waitUntil(10000) { PushInstallationStore(context).scope() == null }
            release.complete(Unit)
            await(PushState.READY, false)
            assertEquals(secondOrg.id, PushInstallationStore(context).scope()!!.organizationId)
            assertTrue(http.stored(firstOrg.id)["updates"]!!.jsonPrimitive.boolean)
            assertFalse(http.stored(secondOrg.id)["updates"]!!.jsonPrimitive.boolean)
            host.setUpdates(true)
            await(PushState.READY, true)
            assertTrue(http.stored(secondOrg.id)["updates"]!!.jsonPrimitive.boolean)
            host.setReceiptAllOrganizations(true)
            await(PushState.READY, true)
            assertTrue(PushInstallationStore(context).receiptAllOrganizations())
            val metadata = http.request("/api/__fixture/state")["devices_metadata"]!!.jsonArray
            assertTrue(
                metadata.any { element ->
                    element.jsonObject.let {
                        it["enabled"]!!.jsonPrimitive.boolean &&
                            it["organization_id"]!!.jsonPrimitive.content == secondOrg.id &&
                            it["all_organizations"]!!.jsonPrimitive.boolean &&
                            it["installed_version_code"]!!.jsonPrimitive.long == 34L
                    }
                }
            )
            runBlocking { host.logout() }
            assertEquals(PushState.SIGNED_OUT, host.state.value.status)
            assertNull(PushInstallationStore(context).scope())
            val state = http.request("/api/__fixture/state")
            assertEquals(0, state["provider_sends"]!!.jsonPrimitive.int)
            assertEquals(0, state["financial_transactions"]!!.jsonPrimitive.int)
        } finally {
            release.complete(Unit)
            coroutineScope.cancel()
            PushInstallationStore(context).disable()
            PushSessionStore(context).apply {
                invalidate()
                tokenDeleted()
            }
        }
    }

    @Test
    fun delayedPublishedPreferencesCannotReenableGateAfterLogout() {
        org.junit.Assume.assumeTrue(Build.MODEL.startsWith("sdk_gphone"))
        org.junit.Assume.assumeTrue(!File(context.noBackupFilesDir, "session.enc").exists())
        val http = FixtureTransport()
        http.request("/api/__fixture/reset", "POST")
        val user = http.login()
        val org = user.organizations.first()
        val app =
            MutableStateFlow(
                AppState(
                    starting = true,
                    server = "https://fixture.invalid",
                    user = user,
                    organization = org,
                )
            )
        var reads = 0
        var delayedAt = -1
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val transport =
            object : PushTransport by http {
                override suspend fun get(scope: PushScope, path: String): JsonObject {
                    val result = http.get(scope, path)
                    if (path == "/api/push/preferences" && ++reads == delayedAt) {
                        entered.complete(Unit)
                        release.await()
                    }
                    return result
                }
            }
        val coroutines = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val host =
            PushHost(
                context.applicationContext as Application,
                coroutines,
                app,
                { null },
                { NavigationScope(org.id, "2026-10", "fixture") },
                {},
                {},
                {},
                {},
                transportOverride = transport,
                providerOverride =
                    object : PushProvider {
                        override val configured = true

                        override suspend fun token() = PushToken("fixture-device-token-18083")

                        override suspend fun deleteToken() {}
                    },
                permissionOverride = { PushPermission.GRANTED },
            )
        try {
            host.beginSession(app.value.server, user.id, true)
            host.synchronize()
            compose.waitUntil(40000) {
                host.state.value.status == PushState.READY &&
                    PushInstallationStore(context).scope() == host.currentScope()
            }
            delayedAt = reads + 2
            host.synchronize()
            compose.waitUntil(40000) { entered.isCompleted }
            host.invalidateSession()
            app.value = AppState(starting = false)
            assertNull(PushInstallationStore(context).scope())
            release.complete(Unit)
            host.synchronize()
            compose.waitUntil(40000) { host.state.value.status == PushState.SIGNED_OUT }
            assertNull(PushInstallationStore(context).scope())
            assertFalse(host.state.value.preferences.enabled)
            assertFalse(host.state.value.preferences.updates)
        } finally {
            release.complete(Unit)
            coroutines.cancel()
            PushInstallationStore(context).disable()
            PushSessionStore(context).apply {
                invalidate()
                tokenDeleted()
            }
        }
    }

    private class FixtureTransport : PushTransport {
        private var cookie = ""
        private var csrf = ""
        val puts = CopyOnWriteArrayList<JsonObject>()
        val preferenceGets = java.util.concurrent.atomic.AtomicInteger()
        @Volatile var lastHttpStatus = 0

        fun request(
            path: String,
            method: String = "GET",
            body: JsonObject? = null,
            organization: String? = null,
        ): JsonObject {
            // Hardcoded loopback fixture only. This transport cannot target a user's server.
            val connection =
                URI("http://127.0.0.1:18083$path").toURL().openConnection() as HttpURLConnection
            connection.requestMethod = method
            connection.connectTimeout = 5000
            connection.readTimeout = 10000
            connection.setRequestProperty("X-Fixture-Key", "fixture-only-18083")
            connection.setRequestProperty("X-Finora-Client", "web")
            if (cookie.isNotEmpty()) connection.setRequestProperty("Cookie", cookie)
            if (csrf.isNotEmpty()) connection.setRequestProperty("X-CSRF-Token", csrf)
            organization?.let { connection.setRequestProperty("X-Organization-ID", it) }
            body?.let {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { output -> output.write(it.toString().toByteArray()) }
            }
            try {
                val code = connection.responseCode
                lastHttpStatus = code
                if (code !in 200..299)
                    throw ApiException(
                        code,
                        "Synthetic fixture HTTP failure",
                        connection.getHeaderField("Retry-After")?.toLongOrNull()?.times(1000),
                    )
                connection.headerFields.entries
                    .firstOrNull { it.key?.equals("Set-Cookie", true) == true }
                    ?.value
                    ?.let { cookie = it.joinToString("; ") { value -> value.substringBefore(';') } }
                return Json.parseToJsonElement(
                        connection.inputStream.bufferedReader().use { it.readText() }
                    )
                    .jsonObject
            } finally {
                connection.disconnect()
            }
        }

        fun login(): User {
            val response =
                request(
                    "/api/auth/login",
                    "POST",
                    buildJsonObject {
                        put("username", "push-fixture")
                        put("password", "Fixture-Only-18083!")
                    },
                )
            csrf = response["csrf"]!!.jsonPrimitive.content
            return Json { ignoreUnknownKeys = true }.decodeFromString<User>(response.toString())
        }

        fun stored(org: String) =
            request("/api/__fixture/state?organization_id=$org")["preferences"]!!.jsonObject

        override suspend fun get(scope: PushScope, path: String) =
            withContext(Dispatchers.IO) {
                request(path, organization = scope.organizationId).also {
                    if (path == "/api/push/preferences") preferenceGets.incrementAndGet()
                }
            }

        override suspend fun getContext(scope: PushScope, path: String) =
            withContext(Dispatchers.IO) {
                request(path)
            }

        override suspend fun write(
            scope: PushScope,
            path: String,
            method: String,
            body: JsonObject?,
        ) =
            withContext(Dispatchers.IO) {
                request(path, method, body, scope.organizationId).also {
                    if (path == "/api/push/preferences" && method == "PUT")
                        puts += requireNotNull(body)
                }
            }
    }

    @Test
    fun persistedGateIsFencedAfterProcessRecreationAndLogout() {
        val sessions = PushSessionStore(context)
        val installation = PushInstallationStore(context)
        try {
            val generation = sessions.begin("https://synthetic.invalid", "synthetic-user", true)
            sessions.workspace("https://synthetic.invalid", "synthetic-user", "synthetic-org")
            val scope =
                PushScope(
                    "https://synthetic.invalid",
                    "synthetic-user",
                    generation,
                    "synthetic-org",
                )
            installation.enable(scope)
            assertEquals(scope, PushInstallationStore(context).scope())
            assertTrue(PushSessionStore(context).active(scope))
            assertFalse(sessions.active(scope.copy(organizationId = "other")))
            sessions.invalidate()
            assertFalse(PushSessionStore(context).active(scope))
            assertTrue(PushSessionStore(context).tokenDeletionRequired())
            val fresh = sessions.begin(scope.server, scope.userId, true)
            assertNotEquals(generation, fresh)
            assertFalse(sessions.active(scope))
        } finally {
            installation.disable()
            sessions.invalidate()
            sessions.tokenDeleted()
        }
    }

    @Test
    fun permissionIsRequestedOnlyAfterExplicitTap() {
        var requests = 0
        var settings = 0
        compose.setContent {
            MaterialTheme {
                PushSettingsUi(
                    PushState.PERMISSION_DENIED,
                    true,
                    {},
                    { requests++ },
                    { settings++ },
                    {},
                )
            }
        }
        compose.runOnIdle {
            assertEquals(0, requests)
            assertEquals(0, settings)
        }
        compose.onNodeWithText("Разрешить уведомления").performClick()
        compose.runOnIdle { assertEquals(1, requests) }
        compose.onNodeWithText("Настройки Android").performClick()
        compose.runOnIdle { assertEquals(1, settings) }
    }

    @Test
    fun unconfiguredServerCannotEnablePushOrRequestPermission() {
        compose.setContent {
            MaterialTheme { PushSettingsUi(PushState.UNCONFIGURED, false, {}, {}, {}, {}) }
        }
        compose.onNodeWithText("Разрешить уведомления").assertDoesNotExist()
        compose.onNode(isToggleable()).assertIsNotEnabled()
    }

    @Test
    fun notificationBillSelectionDisplaysReadOnlyTemplate() {
        val id = "11111111-1111-4111-8111-111111111111"
        val template =
            PaymentTemplate(
                id,
                "Synthetic bill",
                123,
                "MDL",
                start_date = "2026-10-10",
                recurrence = "monthly",
                active = true,
            )
        compose.setContent {
            MaterialTheme {
                PaymentSelectionNotice(
                    NativeEntitySelection.Selected(NavigationEntity.BILL, id),
                    listOf(template),
                )
            }
        }
        compose.onNodeWithTag("payment-native-selection").assertExists()
        compose.onNodeWithText("Synthetic bill", substring = true).assertExists()
        compose.onNodeWithText("Оплатить").assertDoesNotExist()
        compose.onNodeWithText("Подтвердить").assertDoesNotExist()
    }
}
