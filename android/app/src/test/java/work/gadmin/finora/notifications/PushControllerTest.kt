package work.gadmin.finora.notifications

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import work.gadmin.finora.data.*

class PushControllerTest {
    private val eventId = "11111111-1111-4111-8111-111111111111"
    private val scope = PushScope("https://finora.example", "user", "generation-1", "org")
    private val nav = NavigationScope("org", "2026-10", "user@server")

    private class Provider : PushProvider {
        override var configured = true
        var deleted = 0

        override suspend fun token() = PushToken("mock-provider-token-0001")

        override suspend fun deleteToken() {
            deleted++
        }
    }

    private inner class Api : PushApi {
        var configured = true
        var subscriptions = emptySet<String>()

        override suspend fun receiptSubscriptions(scope: PushScope) = subscriptions

        var prefs =
            PushPreferences(
                enabled = true,
                payments = true,
                timezone = "Europe/Berlin",
                quietStart = 1320,
                quietEnd = 420,
            )
        val calls = mutableListOf<String>()
        var afterRegister: (() -> Unit)? = null
        var afterFetch: (() -> Unit)? = null
        var failRevoke = false
        var revokeEvent = false
        var retryOnce = false
        var afterRetry: (() -> Unit)? = null
        var event = PushEvent(eventId, "org", NativeNavigationRequest("receipts", nav))

        override suspend fun deliveryConfigured(scope: PushScope) = configured

        override suspend fun preferences(scope: PushScope) = prefs

        override suspend fun savePreferences(
            scope: PushScope,
            preferences: PushPreferences,
        ): PushPreferences {
            prefs = preferences
            return prefs
        }

        override suspend fun register(
            scope: PushScope,
            installationId: String,
            token: PushToken,
        ): PushRegistration {
            calls.add("register:${scope.sessionKey}")
            if (retryOnce) {
                retryOnce = false
                afterRetry?.invoke()
                throw PushRetryException(1)
            }
            afterRegister?.invoke()
            return PushRegistration(eventId)
        }

        override suspend fun revoke(scope: PushScope, registration: PushRegistration) {
            calls.add("revoke:${scope.sessionKey}")
            if (failRevoke) error("mock failure")
        }

        override suspend fun event(scope: PushScope, eventId: String): PushEvent {
            calls.add("event")
            afterFetch?.invoke()
            if (revokeEvent) throw PushRevokedException()
            return event
        }
    }

    private inner class Fixture {
        var current: PushScope? = scope
        val provider = Provider()
        val api = Api()
        var cleared = 0
        var allOrganizations = false
        val controller =
            PushController(
                provider,
                api,
                { current },
                eventId,
                { cleared++ },
                { 31 },
                { allOrganizations },
            )

        suspend fun open() =
            controller.openEvent(
                eventId,
                scope,
                nav,
                NavigationAccess(true, false),
                setOf(NativeRoute.RECEIPTS),
                0,
            )
    }

    @Test
    fun permissionDeniedDoesNotRegister() = runBlocking {
        val f = Fixture()
        f.controller.synchronize(PushPermission.DENIED)
        assertEquals(PushState.PERMISSION_DENIED, f.controller.state)
        assertTrue(f.api.calls.isEmpty())
    }

    @Test
    fun disabledChannelDoesNotRegister() = runBlocking {
        val f = Fixture()
        f.controller.synchronize(PushPermission.CHANNEL_DISABLED)
        assertEquals(PushState.CHANNEL_DISABLED, f.controller.state)
        assertTrue(f.api.calls.isEmpty())
    }

    @Test
    fun missingConfigurationNeverClaimsReady() = runBlocking {
        val f = Fixture()
        f.api.configured = false
        f.controller.synchronize(PushPermission.GRANTED)
        assertEquals(PushState.UNCONFIGURED, f.controller.state)
        assertTrue(f.api.calls.isEmpty())
    }

    @Test
    fun noSessionNeverRegisters() = runBlocking {
        val f = Fixture()
        f.current = null
        f.controller.synchronize(PushPermission.GRANTED)
        assertEquals(PushState.SIGNED_OUT, f.controller.state)
    }

    @Test
    fun rotationUpsertsAtomically() = runBlocking {
        val f = Fixture()
        f.controller.synchronize(PushPermission.GRANTED)
        f.controller.synchronize(PushPermission.GRANTED, PushToken("mock-rotated-token-0002"))
        assertEquals(
            listOf("register:generation-1", "register:generation-1"),
            f.api.calls,
        )
    }

    @Test
    fun revokeFailurePreventsNewBinding() = runBlocking {
        val f = Fixture()
        f.controller.synchronize(PushPermission.GRANTED)
        f.api.failRevoke = true
        f.current = scope.copy(userId = "other")
        f.controller.synchronize(PushPermission.GRANTED)
        assertEquals(PushState.ERROR, f.controller.state)
        assertEquals(1, f.api.calls.count { it.startsWith("register") })
    }

    @Test
    fun sessionSwitchDuringRegistrationRevokesOldBinding() = runBlocking {
        val f = Fixture()
        f.api.afterRegister = { f.current = scope.copy(sessionKey = "generation-2") }
        f.controller.synchronize(PushPermission.GRANTED)
        assertEquals(PushState.REVOKED, f.controller.state)
        assertEquals("revoke:generation-1", f.api.calls.last())
    }

    @Test
    fun logoutClearsAndDeletesProviderTokenEvenIfRevokeFails() = runBlocking {
        val f = Fixture()
        f.controller.synchronize(PushPermission.GRANTED)
        f.api.failRevoke = true
        f.controller.logout()
        assertEquals(1, f.provider.deleted)
        assertEquals(PushState.ERROR, f.controller.state)
        assertTrue(f.cleared >= 1)
    }

    @Test
    fun preferenceTogglePreservesScheduleAndCategories() = runBlocking {
        val f = Fixture()
        f.controller.setEnabled(false, PushPermission.GRANTED)
        assertFalse(f.api.prefs.enabled)
        assertTrue(f.api.prefs.payments)
        assertEquals("Europe/Berlin", f.api.prefs.timezone)
        assertEquals(1320, f.api.prefs.quietStart)
    }

    @Test
    fun scopedAuthenticatedEventOpensNativeScreen() = runBlocking {
        val f = Fixture()
        assertTrue(f.open().resolution is NativeNavigationResolution.Open)
        assertEquals(listOf("event"), f.api.calls)
    }

    @Test
    fun staleTapDoesNotFetch() = runBlocking {
        val f = Fixture()
        f.current = scope.copy(sessionKey = "generation-2")
        assertTrue(f.open().resolution is NativeNavigationResolution.Rejected)
        assertTrue(f.api.calls.isEmpty())
    }

    @Test
    fun sessionChangeDuringFetchRejectsResult() = runBlocking {
        val f = Fixture()
        f.api.afterFetch = { f.current = scope.copy(organizationId = "other") }
        assertTrue(f.open().resolution is NativeNavigationResolution.Rejected)
    }

    @Test
    fun unknownAndWebRoutesCannotBeOpened() = runBlocking {
        val f = Fixture()
        for (route in listOf("settings", "users", "https://example.com", "pay", "delete")) {
            f.api.event = f.api.event.copy(navigation = NativeNavigationRequest(route, nav))
            assertTrue(f.open().resolution is NativeNavigationResolution.Rejected)
        }
    }

    @Test
    fun revokedEventIsVisibleAsRevoked() = runBlocking {
        val f = Fixture()
        f.api.revokeEvent = true
        assertTrue(f.open().resolution is NativeNavigationResolution.Rejected)
        assertEquals(PushState.REVOKED, f.controller.state)
    }

    @Test
    fun payloadRejectsPathsAndIgnoresPrivateFields() {
        assertNull(PushEnvelope.eventId(mapOf("event_id" to "../auth")))
        assertEquals(
            eventId,
            PushEnvelope.eventId(
                mapOf(
                    "event_id" to eventId,
                    "amount" to "100",
                    "name" to "private",
                    "url" to "https://evil.example",
                )
            ),
        )
        assertEquals("PushToken([redacted])", PushToken("mock-provider-token-0001").toString())
    }

    @Test
    fun unchangedSyncKeepsHealthyBinding() = runBlocking {
        val f = Fixture()
        f.controller.synchronize(PushPermission.GRANTED)
        f.controller.synchronize(PushPermission.GRANTED)
        assertEquals(listOf("register:generation-1"), f.api.calls)
        assertEquals(PushState.READY, f.controller.state)
    }

    @Test
    fun retryAfterIsBoundedWithoutRevokingHealthyBinding() = runBlocking {
        val f = Fixture()
        f.controller.synchronize(PushPermission.GRANTED)
        f.api.retryOnce = true
        f.controller.synchronize(PushPermission.GRANTED, PushToken("mock-rotated-token-0002"))
        assertEquals(
            listOf("register:generation-1", "register:generation-1", "register:generation-1"),
            f.api.calls,
        )
        assertEquals(PushState.READY, f.controller.state)
    }

    @Test
    fun scopeChangeStopsRetryAfter() = runBlocking {
        val f = Fixture()
        f.api.retryOnce = true
        f.api.afterRetry = { f.current = scope.copy(organizationId = "other") }
        f.controller.synchronize(PushPermission.GRANTED)
        assertEquals(listOf("register:generation-1"), f.api.calls)
        assertEquals(PushState.REVOKED, f.controller.state)
    }

    @Test
    fun futureMonthIsReturnedSeparatelyAfterFreshnessValidation() = runBlocking {
        val f = Fixture()
        f.api.event = PushEvent(eventId, "org", NativeNavigationRequest("bills", nav), "2026-11")
        val result =
            f.controller.openEvent(
                eventId,
                scope,
                nav,
                NavigationAccess(true, false),
                setOf(NativeRoute.BILLS),
                0,
            )
        assertTrue(result.resolution is NativeNavigationResolution.Open)
        assertEquals("2026-11", result.targetMonth)
        assertEquals(nav, (result.resolution as NativeNavigationResolution.Open).intent.scope)
    }

    @Test
    fun newerUpdateOpensOnlyDedicatedUpdaterTarget() = runBlocking {
        val f = Fixture()
        f.api.event =
            PushEvent(
                eventId,
                "org",
                NativeNavigationRequest("updater", nav, expiresAtMillis = 1000),
                updaterVersion = InstalledPushVersion(32, "1.18.0"),
            )
        val result = f.open()
        assertEquals(32L, result.updaterVersion?.code)
        assertNull(result.targetMonth)
        assertFalse(result.resolution is NativeNavigationResolution.Open)
    }

    @Test
    fun updateAlreadyInstalledOrExpiredCannotOpenUpdater() = runBlocking {
        val f = Fixture()
        f.api.event =
            PushEvent(
                eventId,
                "org",
                NativeNavigationRequest("updater", nav, expiresAtMillis = 1000),
                updaterVersion = InstalledPushVersion(31, "1.17.0"),
            )
        assertNull(f.open().updaterVersion)
        f.api.event =
            f.api.event.copy(
                updaterVersion = InstalledPushVersion(32, "1.18.0"),
                navigation = NativeNavigationRequest("updater", nav, expiresAtMillis = 0),
            )
        assertNull(f.open().updaterVersion)
    }

    @Test
    fun forgedUpdaterRouteAndEntitiesCannotNavigate() = runBlocking {
        val f = Fixture()
        for (route in listOf("settings", "https://evil.example", "updater")) {
            f.api.event =
                PushEvent(
                    eventId,
                    "org",
                    NativeNavigationRequest(
                        route,
                        nav,
                        mapOf(NavigationEntity.RECEIPT to eventId),
                        expiresAtMillis = 1000,
                    ),
                    updaterVersion = InstalledPushVersion(32, "1.18.0"),
                )
            assertNull(f.open().updaterVersion)
        }
    }

    @Test
    fun allOrganizationsSubscriptionCanRegisterWithoutChangingCurrentPreferences() = runBlocking {
        val f = Fixture()
        f.api.prefs = f.api.prefs.copy(enabled = false)
        f.allOrganizations = true
        f.api.subscriptions = setOf("other-org")
        f.controller.synchronize(PushPermission.GRANTED)
        assertEquals(PushState.READY, f.controller.state)
        assertFalse(f.api.prefs.enabled)
    }

    @Test
    fun allOrganizationsWithoutSubscriptionOrPermissionCannotRegister() = runBlocking {
        val f = Fixture()
        f.api.prefs = f.api.prefs.copy(enabled = false)
        f.allOrganizations = true
        f.controller.synchronize(PushPermission.GRANTED)
        assertEquals(PushState.DISABLED, f.controller.state)
        f.api.subscriptions = setOf("other-org")
        f.controller.synchronize(PushPermission.DENIED)
        assertEquals(PushState.PERMISSION_DENIED, f.controller.state)
    }
}
