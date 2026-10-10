package work.gadmin.finora.notifications

import kotlinx.serialization.json.*
import work.gadmin.finora.data.*

/**
 * Adapter of the existing ApiClient only. Implementations must capture an authenticated client for
 * the exact PushScope, including revocation of the previous binding during scope changes.
 */
interface PushTransport {
    suspend fun getContext(scope: PushScope, path: String): JsonObject =
        throw UnsupportedOperationException("Unscoped push context unavailable")

    suspend fun get(scope: PushScope, path: String): JsonObject

    suspend fun write(scope: PushScope, path: String, method: String, body: JsonObject?): JsonObject
}

class PushApiContract(
    private val transport: PushTransport,
    private val installedVersion: InstalledPushVersion? = null,
    private val navigationScope: () -> NavigationScope,
) : PushApi {
    @Volatile private var allOrganizations = false
    private val receiptSupport = java.util.concurrent.ConcurrentHashMap<PushScope, Boolean>()

    fun setReceiptAllOrganizations(enabled: Boolean) {
        allOrganizations = enabled
    }

    fun supportsReceiptAllOrganizations(scope: PushScope): Boolean = receiptSupport[scope] == true

    private val updateSupport = java.util.concurrent.ConcurrentHashMap<PushScope, Boolean>()

    fun supportsUpdateNotifications(scope: PushScope): Boolean = updateSupport[scope] == true

    private fun JsonObject.text(key: String) = get(key)?.jsonPrimitive?.contentOrNull

    private fun JsonObject.flag(key: String) = get(key)?.jsonPrimitive?.booleanOrNull ?: false

    private fun JsonObject.number(key: String) = get(key)?.jsonPrimitive?.intOrNull

    private fun validId(id: String): String {
        require(PushEnvelope.eventId(mapOf("event_id" to id)) != null)
        return id
    }

    override suspend fun deliveryConfigured(scope: PushScope): Boolean {
        val response = transport.get(scope, "/api/push/capabilities")
        updateSupport[scope] = response.flag("update_notifications")
        receiptSupport[scope] = response.flag("receipt_notifications_all_organizations")
        return response.number("schema_version") == 1 &&
            response.flag("delivery_configured") &&
            response.text("provider") == "fcm"
    }

    override suspend fun register(
        scope: PushScope,
        installationId: String,
        token: PushToken,
    ): PushRegistration {
        val response =
            transport.write(
                scope,
                "/api/push/devices",
                "POST",
                buildJsonObject {
                    put("installation_id", validId(installationId))
                    put("provider", "fcm")
                    put("token", token.value)
                    put("enabled", true)
                    if (supportsReceiptAllOrganizations(scope))
                        put("all_organizations", allOrganizations)
                    if (updateSupport[scope] == true)
                        installedVersion?.let { version ->
                            put("installed_version_code", version.code)
                            put("installed_version_name", version.name)
                        }
                },
            )
        require(
            response.text("organization_id") == scope.organizationId && response.flag("enabled")
        )
        return PushRegistration(
            validId(requireNotNull(response.text("device_id"))),
            response.flag("delivery_configured"),
        )
    }

    override suspend fun revoke(scope: PushScope, registration: PushRegistration) {
        transport.write(scope, "/api/push/devices/${validId(registration.id)}", "DELETE", null)
    }

    override suspend fun preferences(scope: PushScope): PushPreferences {
        if (!updateSupport.containsKey(scope)) deliveryConfigured(scope)
        return decodePreferences(scope, transport.get(scope, "/api/push/preferences"))
    }

    override suspend fun savePreferences(
        scope: PushScope,
        preferences: PushPreferences,
    ): PushPreferences {
        // Preference fencing rotates the local generation. Never serialize optional fields using
        // capabilities from another generation, or silently drop a saved updates preference.
        if (!updateSupport.containsKey(scope)) deliveryConfigured(scope)
        return decodePreferences(
            scope,
            transport.write(
                scope,
                "/api/push/preferences",
                "PUT",
                buildJsonObject {
                    put("enabled", preferences.enabled)
                    put("payments", preferences.payments)
                    put("receipts", preferences.receipts)
                    put("important", preferences.important)
                    put("timezone", preferences.timezone)
                    put("quiet_start", preferences.quietStart?.let(::JsonPrimitive) ?: JsonNull)
                    put("quiet_end", preferences.quietEnd?.let(::JsonPrimitive) ?: JsonNull)
                    put("payment_hour", preferences.paymentHour)
                    put("lead_days", preferences.leadDays)
                    if (updateSupport[scope] == true) put("updates", preferences.updates)
                },
            ),
        )
    }

    private fun decodePreferences(scope: PushScope, response: JsonObject) =
        PushPreferences(
            response.flag("enabled"),
            response.flag("payments"),
            response.flag("receipts"),
            response.flag("important"),
            response.text("timezone") ?: "UTC",
            response.number("quiet_start"),
            response.number("quiet_end"),
            response.number("payment_hour") ?: 9,
            response.number("lead_days") ?: 1,
            if (supportsUpdateNotifications(scope))
                response["updates"]?.jsonPrimitive?.booleanOrNull ?: false
            else false,
        )

    override suspend fun receiptSubscriptions(scope: PushScope): Set<String> {
        if (!allOrganizations || !supportsReceiptAllOrganizations(scope)) return emptySet()
        val response = transport.getContext(scope, "/api/push/subscriptions")
        val organizations = response["receipt_organizations"]?.jsonArray ?: return emptySet()
        require(organizations.size <= 1000)
        return organizations.map { validId(it.jsonPrimitive.content) }.toSet()
    }

    override suspend fun eventContext(scope: PushScope, eventId: String): PushEventContext {
        require(allOrganizations && supportsReceiptAllOrganizations(scope))
        val response = transport.getContext(scope, "/api/push/events/${validId(eventId)}/context")
        require(response.text("event_id") == eventId)
        return PushEventContext(
            eventId,
            requireNotNull(response.text("category")),
            validId(requireNotNull(response.text("organization_id"))),
        )
    }

    override suspend fun event(scope: PushScope, eventId: String): PushEvent {
        val local = navigationScope()
        require(local.organizationId == scope.organizationId)
        val response = transport.get(scope, "/api/push/events/${validId(eventId)}")
        require(
            response.text("event_id") == eventId &&
                response.text("organization_id") == scope.organizationId
        )
        val route = requireNotNull(response.text("route"))
        val entity = response.text("entity_id")
        if (route == PushUpdatePolicy.ROUTE) {
            require(response.text("category") == PushUpdatePolicy.CATEGORY && entity == null)
            require(response.text("due_date") == null)
            val version =
                InstalledPushVersion(
                    requireNotNull(response["version_code"]?.jsonPrimitive?.longOrNull),
                    requireNotNull(response.text("version_name")),
                )
            require(
                installedVersion != null && PushUpdatePolicy.newer(version, installedVersion.code)
            )
            val expires =
                java.time.Instant.parse(requireNotNull(response.text("expires_at"))).toEpochMilli()
            return PushEvent(
                eventId,
                scope.organizationId,
                NativeNavigationRequest(route, local, expiresAtMillis = expires),
                updaterVersion = version,
            )
        }
        val entities =
            when (route) {
                "receipts" ->
                    entity?.let { mapOf(NavigationEntity.RECEIPT to validId(it)) } ?: emptyMap()
                "bills" -> entity?.let { mapOf(NavigationEntity.BILL to validId(it)) } ?: emptyMap()
                "overview" -> {
                    require(entity == null)
                    emptyMap()
                }
                else -> throw IllegalArgumentException("Push destination unavailable")
            }
        // Server rechecks event expiry and financial entity state. Due date is not a command.
        val dueMonth =
            response.text("due_date")?.let { date ->
                // java.time validates actual calendar dates, not merely a YYYY-MM prefix.
                java.time.LocalDate.parse(date).toString().take(7)
            }
        return PushEvent(
            eventId,
            scope.organizationId,
            NativeNavigationRequest(route, local, entities),
            if (route == "bills") dueMonth else null,
        )
    }
}
