package work.gadmin.finora.notifications

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import work.gadmin.finora.data.*

class PushApiContractTest {
    private val id = "11111111-1111-4111-8111-111111111111"
    private val scope = PushScope("https://finora.example", "user", "local-generation", "org")
    private val nav = NavigationScope("org", "2026-10", "user@server")

    @Test
    fun fencedGenerationRefreshesCapabilitiesAndPreservesUpdatesAcrossCategoryWrites() =
        runBlocking {
            var persisted = buildJsonObject {
                put("enabled", true)
                put("updates", true)
            }
            val fetched = mutableListOf<PushScope>()
            val transport =
                object : PushTransport {
                    override suspend fun get(scope: PushScope, path: String): JsonObject =
                        if (path.endsWith("capabilities")) {
                            fetched += scope
                            buildJsonObject {
                                put("schema_version", 1)
                                put("provider", "fcm")
                                put("delivery_configured", true)
                                put("update_notifications", true)
                            }
                        } else persisted

                    override suspend fun write(
                        scope: PushScope,
                        path: String,
                        method: String,
                        body: JsonObject?,
                    ): JsonObject {
                        persisted = requireNotNull(body)
                        return persisted
                    }
                }
            val api = PushApiContract(transport) { nav }
            api.deliveryConfigured(scope)
            val fenced = scope.copy(sessionKey = "fresh-generation")
            assertFalse(api.supportsUpdateNotifications(fenced))
            val loaded = api.preferences(fenced)
            assertTrue(loaded.updates)
            assertTrue(api.supportsUpdateNotifications(fenced))
            api.savePreferences(fenced, loaded.copy(payments = true))
            assertTrue(persisted["updates"]!!.jsonPrimitive.boolean)
            assertEquals(listOf(scope, fenced), fetched)
            assertTrue(PushApiContract(transport) { nav }.preferences(fenced).updates)
        }

    private class Transport : PushTransport {
        var response = buildJsonObject {}
        var path = ""
        var method = ""
        var body: JsonObject? = null
        var capturedScope: PushScope? = null

        override suspend fun getContext(scope: PushScope, path: String): JsonObject {
            capturedScope = scope
            this.path = path
            return response
        }

        override suspend fun get(scope: PushScope, path: String): JsonObject {
            capturedScope = scope
            this.path = path
            return response
        }

        override suspend fun write(
            scope: PushScope,
            path: String,
            method: String,
            body: JsonObject?,
        ): JsonObject {
            capturedScope = scope
            this.path = path
            this.method = method
            this.body = body
            return response
        }
    }

    @Test
    fun capabilitiesMustExplicitlyEnableFcmSchema() = runBlocking {
        val t = Transport()
        val api = PushApiContract(t) { nav }
        assertFalse(api.deliveryConfigured(scope))
        t.response = buildJsonObject {
            put("schema_version", 1)
            put("delivery_configured", true)
            put("provider", "fcm")
        }
        assertTrue(api.deliveryConfigured(scope))
        assertEquals("/api/push/capabilities", t.path)
    }

    @Test
    fun registrationUsesExactWireContractAndBoundScope() = runBlocking {
        val t = Transport()
        t.response = buildJsonObject {
            put("device_id", id)
            put("organization_id", "org")
            put("enabled", true)
            put("delivery_configured", false)
        }
        val api = PushApiContract(t) { nav }
        val result = api.register(scope, id, PushToken("mock-provider-token-0001"))
        assertEquals(id, result.id)
        assertFalse(result.deliveryConfigured)
        assertEquals("/api/push/devices", t.path)
        assertEquals("POST", t.method)
        assertEquals(scope, t.capturedScope)
        assertEquals(setOf("installation_id", "provider", "token", "enabled"), t.body!!.keys)
        assertEquals("fcm", t.body!!["provider"]!!.jsonPrimitive.content)
    }

    @Test
    fun preferencesSendAllFieldsAndPreserveQuietSchedule() = runBlocking {
        val t = Transport()
        val api = PushApiContract(t) { nav }
        api.savePreferences(
            scope,
            PushPreferences(true, true, true, false, "Europe/Berlin", 1320, 420, 9, 2),
        )
        assertEquals("PUT", t.method)
        assertEquals("/api/push/preferences", t.path)
        assertEquals(
            setOf(
                "enabled",
                "payments",
                "receipts",
                "important",
                "timezone",
                "quiet_start",
                "quiet_end",
                "payment_hour",
                "lead_days",
            ),
            t.body!!.keys,
        )
        assertEquals(1320, t.body!!["quiet_start"]!!.jsonPrimitive.int)
    }

    @Test
    fun eventOnlyProducesReadOnlyAllowlistedDestination() = runBlocking {
        val t = Transport()
        t.response = buildJsonObject {
            put("event_id", id)
            put("organization_id", "org")
            put("route", "receipts")
            put("entity_id", id)
            put("category", "receipts")
            put("due_date", JsonNull)
        }
        val event = PushApiContract(t) { nav }.event(scope, id)
        assertEquals("/api/push/events/$id", t.path)
        assertEquals(nav, event.navigation.scope)
        assertEquals(mapOf(NavigationEntity.RECEIPT to id), event.navigation.entities)
    }

    @Test
    fun injectedPathRejectedBeforeTransport() = runBlocking {
        val t = Transport()
        try {
            PushApiContract(t) { nav }.event(scope, "../auth/logout")
            fail("path accepted")
        } catch (_: IllegalArgumentException) {}
        assertEquals("", t.path)
    }

    @Test
    fun foreignOrganizationResponseRejected() = runBlocking {
        val t = Transport()
        t.response = buildJsonObject {
            put("event_id", id)
            put("organization_id", "foreign")
            put("route", "overview")
        }
        try {
            PushApiContract(t) { nav }.event(scope, id)
            fail("foreign org accepted")
        } catch (_: IllegalArgumentException) {}
    }

    @Test
    fun deleteUsesDeviceIdAndNoTokenBody() = runBlocking {
        val t = Transport()
        PushApiContract(t) { nav }.revoke(scope, PushRegistration(id))
        assertEquals("/api/push/devices/$id", t.path)
        assertEquals("DELETE", t.method)
        assertNull(t.body)
    }

    @Test
    fun billsDueMonthNeverReplacesCapturedScope() = runBlocking {
        val t = Transport()
        t.response = buildJsonObject {
            put("event_id", id)
            put("organization_id", "org")
            put("route", "bills")
            put("entity_id", id)
            put("due_date", "2026-11-01")
        }
        val event = PushApiContract(t) { nav }.event(scope, id)
        assertEquals(nav, event.navigation.scope)
        assertEquals("2026-11", event.targetMonth)
    }

    @Test
    fun newRegistrationAndPreferencesAreCapabilityGated() = runBlocking {
        val t = Transport()
        val api = PushApiContract(t, InstalledPushVersion(31, "1.17.0")) { nav }
        t.response = buildJsonObject {
            put("schema_version", 1)
            put("delivery_configured", true)
            put("provider", "fcm")
            put("update_notifications", true)
            put("receipt_notifications_all_organizations", true)
        }
        api.deliveryConfigured(scope)
        api.setReceiptAllOrganizations(true)
        t.response = buildJsonObject {
            put("device_id", id)
            put("organization_id", "org")
            put("enabled", true)
            put("delivery_configured", true)
        }
        api.register(scope, id, PushToken("mock-provider-token-0001"))
        assertEquals(31, t.body!!["installed_version_code"]!!.jsonPrimitive.int)
        assertTrue(t.body!!["all_organizations"]!!.jsonPrimitive.boolean)
        api.savePreferences(scope, PushPreferences(updates = false))
        assertFalse(t.body!!["updates"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun updateEventValidatesInstalledVersionAndHasNoNativeSettingsRoute() = runBlocking {
        val t = Transport()
        val api = PushApiContract(t, InstalledPushVersion(31, "1.17.0")) { nav }
        t.response = buildJsonObject {
            put("event_id", id)
            put("organization_id", "org")
            put("category", "update_available")
            put("route", "updater")
            put("entity_id", JsonNull)
            put("version_code", 32)
            put("version_name", "1.18.0")
            put("expires_at", "2027-01-01T00:00:00Z")
            put("apk_url", "http://evil.example")
        }
        val event = api.event(scope, id)
        assertEquals(32L, event.updaterVersion?.code)
        assertEquals("updater", event.navigation.route)
        assertTrue(event.navigation.entities.isEmpty())
    }

    @Test
    fun contextAndSubscriptionsUseDedicatedUnscopedTransport() = runBlocking {
        val t = Transport()
        val api = PushApiContract(t) { nav }
        api.setReceiptAllOrganizations(true)
        t.response = buildJsonObject {
            put("schema_version", 1)
            put("delivery_configured", true)
            put("provider", "fcm")
            put("receipt_notifications_all_organizations", true)
        }
        api.deliveryConfigured(scope)
        t.response = buildJsonObject { put("receipt_organizations", buildJsonArray { add(id) }) }
        assertEquals(setOf(id), api.receiptSubscriptions(scope))
        assertEquals("/api/push/subscriptions", t.path)
        t.response = buildJsonObject {
            put("event_id", id)
            put("category", "receipts")
            put("organization_id", id)
        }
        assertEquals(id, api.eventContext(scope, id).organizationId)
        assertEquals("/api/push/events/$id/context", t.path)
    }
}
