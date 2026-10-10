package work.gadmin.finora.notifications

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import work.gadmin.finora.data.*

class ColdPushTransportTest {
    private val scope = PushScope("https://finora.example", "user", "generation", "org")
    private val id = "11111111-1111-4111-8111-111111111111"

    private class Provider : PushProvider {
        override val configured = true

        override suspend fun token() = PushToken("fictional-token-value")

        override suspend fun deleteToken() {}
    }

    private fun navigation() = NavigationScope("org", "2026-10", "user@server")

    @Test
    fun cold429RetriesOnceThenEnables() = runBlocking {
        var current: PushScope? = scope
        var calls = 0
        var enabled: PushScope? = null
        val raw =
            object : PushTransport {
                override suspend fun get(scope: PushScope, path: String) = buildJsonObject {
                    if (path.endsWith("capabilities")) {
                        put("schema_version", 1)
                        put("delivery_configured", true)
                        put("provider", "fcm")
                    } else put("enabled", true)
                }

                override suspend fun write(
                    scope: PushScope,
                    path: String,
                    method: String,
                    body: JsonObject?,
                ): JsonObject {
                    calls++
                    if (calls == 1) throw ApiException(429, "throttled", 1)
                    return buildJsonObject {
                        put("device_id", id)
                        put("organization_id", "org")
                        put("enabled", true)
                        put("delivery_configured", true)
                    }
                }
            }
        val controller =
            PushController(
                Provider(),
                PushApiContract(
                    MappedPushTransport(raw, { current }) { current = null },
                    navigationScope = ::navigation,
                ),
                { current },
                id,
                {},
            )
        synchronizeColdPush(
            controller,
            PushPermission.GRANTED,
            PushToken("fictional-token-value"),
            scope,
            { current },
        ) {
            enabled = it
        }
        assertEquals(2, calls)
        assertEquals(scope, enabled)
        assertEquals(PushState.READY, controller.state)
    }

    @Test
    fun logoutDuringRetryCannotRegisterOrEnable() = runBlocking {
        var current: PushScope? = scope
        var calls = 0
        var enabled = false
        val raw =
            object : PushTransport {
                override suspend fun get(scope: PushScope, path: String) = buildJsonObject {
                    if (path.endsWith("capabilities")) {
                        put("schema_version", 1)
                        put("delivery_configured", true)
                        put("provider", "fcm")
                    } else put("enabled", true)
                }

                override suspend fun write(
                    scope: PushScope,
                    path: String,
                    method: String,
                    body: JsonObject?,
                ): JsonObject {
                    calls++
                    current = null
                    throw ApiException(429, "throttled", 1)
                }
            }
        val controller =
            PushController(
                Provider(),
                PushApiContract(
                    MappedPushTransport(raw, { current }) {},
                    navigationScope = ::navigation,
                ),
                { current },
                id,
                {},
            )
        synchronizeColdPush(
            controller,
            PushPermission.GRANTED,
            PushToken("fictional-token-value"),
            scope,
            { current },
        ) {
            enabled = true
        }
        assertEquals(1, calls)
        assertFalse(enabled)
        assertEquals(PushState.REVOKED, controller.state)
    }

    @Test
    fun coldUnauthorizedDisablesSession() = runBlocking {
        var current: PushScope? = scope
        var revoked = false
        val raw =
            object : PushTransport {
                override suspend fun get(scope: PushScope, path: String): JsonObject =
                    throw ApiException(401, "expired")

                override suspend fun write(
                    scope: PushScope,
                    path: String,
                    method: String,
                    body: JsonObject?,
                ): JsonObject = error("no write")
            }
        val mapped =
            MappedPushTransport(raw, { current }) {
                revoked = true
                current = null
            }
        try {
            mapped.get(scope, "/api/push/capabilities")
            fail()
        } catch (_: PushRevokedException) {}
        assertTrue(revoked)
        assertNull(current)
    }
}
