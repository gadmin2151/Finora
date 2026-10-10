package work.gadmin.finora.data

import org.junit.Assert.*
import org.junit.Test

class NativeNavigationTest {
    private val scope = NavigationScope("organization-a", "2026-10")
    private val access = NavigationAccess(true, true)
    private val id = "e522c84a-ced9-4820-9d90-c21da2b63547"

    private fun resolve(
        request: NativeNavigationRequest,
        tap: Boolean = true,
        current: NavigationScope = scope,
        permissions: NavigationAccess = access,
        available: Set<NativeRoute> = NativeNavigationResolver.existingRoutes,
    ) = NativeNavigationResolver.resolve(request, current, permissions, tap, 100L, available)

    private fun rejected(reason: NavigationRejection, result: NativeNavigationResolution) =
        assertEquals(NativeNavigationResolution.Rejected(reason), result)

    @Test
    fun catalogueIsExactAndUnknownRoutesAreRejected() {
        for (raw in
            listOf(
                null,
                "",
                "BILLS",
                "../bills",
                "https://example.com",
                "bills?org=a",
                "/#bills",
            )) rejected(
            NavigationRejection.UNKNOWN_ROUTE,
            resolve(NativeNavigationRequest(raw, scope)),
        )
        assertEquals(15, NativeRoute.entries.size)
    }

    @Test
    fun allFinancialDestinationsAreNativeOrExplicitlyUnavailable() {
        for (route in NativeRoute.entries.filterNot { it.webSettings }) {
            val result = resolve(NativeNavigationRequest(route.page, scope))
            assertTrue(
                result is NativeNavigationResolution.Open ||
                    result is NativeNavigationResolution.Unavailable
            )
            assertTrue(
                resolve(
                    NativeNavigationRequest(route.page, scope),
                    available = NativeRoute.entries.toSet(),
                )
                    is NativeNavigationResolution.Open
            )
        }
        for (route in listOf("settings", "users")) assertTrue(
            resolve(NativeNavigationRequest(route, scope)) is NativeNavigationResolution.WebSettings
        )
    }

    @Test
    fun historyRenderingNeverNavigatesAndExpiredButtonsReject() {
        val request = NativeNavigationRequest("income", scope)
        rejected(NavigationRejection.NO_EXPLICIT_TAP, resolve(request, tap = false))
        rejected(NavigationRejection.EXPIRED, resolve(request.copy(expiresAtMillis = 100)))
        assertTrue(resolve(request.copy(expiresAtMillis = 101)) is NativeNavigationResolution.Open)
        rejected(
            NavigationRejection.INVALID_MESSAGE,
            resolve(request.copy(assistantMessage = false)),
        )
        rejected(NavigationRejection.INVALID_MESSAGE, resolve(request.copy(error = true)))
    }

    @Test
    fun organizationAndMonthArePreservedAndCannotCrossScope() {
        val request =
            NativeNavigationRequest("receipts", scope, mapOf(NavigationEntity.RECEIPT to id))
        val intent = (resolve(request) as NativeNavigationResolution.Open).intent
        assertEquals(scope, intent.scope)
        assertEquals(id, intent.entities[NavigationEntity.RECEIPT])
        rejected(
            NavigationRejection.STALE_SCOPE,
            resolve(request, current = scope.copy(organizationId = "other")),
        )
        rejected(
            NavigationRejection.STALE_SCOPE,
            resolve(request, current = scope.copy(month = "2026-09")),
        )
        rejected(
            NavigationRejection.STALE_SCOPE,
            resolve(request, current = scope.copy(sessionKey = "other-user@other-server")),
        )
        rejected(
            NavigationRejection.INVALID_SCOPE,
            resolve(request.copy(scope = scope.copy(month = "2026-13"))),
        )
    }

    @Test
    fun permissionsAreCheckedAtTapTime() {
        for (route in listOf("accounts", "debts", "budgets", "bills", "users")) rejected(
            NavigationRejection.FORBIDDEN,
            resolve(
                NativeNavigationRequest(route, scope),
                permissions = NavigationAccess(false, false),
            ),
        )
        assertTrue(
            resolve(
                NativeNavigationRequest("users", scope),
                permissions = NavigationAccess(false, true),
            )
                is NativeNavigationResolution.WebSettings
        )
        assertTrue(
            resolve(
                NativeNavigationRequest("income", scope),
                permissions = NavigationAccess(false, false),
            )
                is NativeNavigationResolution.Open
        )
    }

    @Test
    fun entityIdsAreTypedAndCannotInjectAPath() {
        rejected(
            NavigationRejection.INVALID_ENTITY,
            resolve(
                NativeNavigationRequest(
                    "receipts",
                    scope,
                    mapOf(NavigationEntity.RECEIPT to "$id/../users"),
                )
            ),
        )
        rejected(
            NavigationRejection.WRONG_ENTITY,
            resolve(NativeNavigationRequest("receipts", scope, mapOf(NavigationEntity.DEBT to id))),
        )
    }

    @Test
    fun backRestoresExactOriginAndExcludesRevokedOrStaleScope() {
        val origin = NativeNavigationIntent(NativeRoute.ASSISTANT, scope)
        val target =
            NativeNavigationIntent(
                NativeRoute.RECEIPTS,
                scope,
                mapOf(NavigationEntity.RECEIPT to id),
            )
        val history = NativeNavigationHistory().push(origin, target)
        assertEquals(origin, history.back(scope, access).first)
        assertTrue(history.back(scope, access).second.entries.isEmpty())
        assertNull(history.back(scope.copy(organizationId = "other"), access).first)
        assertNull(history.back(scope.copy(month = "2026-09"), access).first)
        assertNull(
            NativeNavigationHistory(listOf(NativeNavigationIntent(NativeRoute.DEBTS, scope)))
                .back(scope, NavigationAccess(false, false))
                .first
        )
    }

    @Test
    fun repeatNavigationDoesNotGrowBackStackAndHistoryIsBounded() {
        val origin = NativeNavigationIntent(NativeRoute.ASSISTANT, scope)
        assertTrue(NativeNavigationHistory().push(origin, origin).entries.isEmpty())
        var history = NativeNavigationHistory()
        repeat(100) {
            history = history.push(origin, NativeNavigationIntent(NativeRoute.RECEIPTS, scope))
        }
        assertEquals(32, history.entries.size)
    }

    @Test
    fun integrationContractUsesActualNativeReadScreens() {
        assertEquals(NativeScreenTarget.BILLS, NativeRouteContract.targets[NativeRoute.BILLS])
        assertEquals(NativeScreenTarget.BUDGETS, NativeRouteContract.targets[NativeRoute.BUDGETS])
        assertEquals(
            NativeScreenTarget.TRANSACTIONS,
            NativeRouteContract.targets[NativeRoute.TRANSACTIONS],
        )
        assertEquals(NativeScreenTarget.WALLET, NativeRouteContract.targets[NativeRoute.ACCOUNTS])
        assertEquals(NativeScreenTarget.INSIGHTS, NativeRouteContract.targets[NativeRoute.INSIGHTS])
        for (route in listOf("reports", "purchases")) assertTrue(
            resolve(
                NativeNavigationRequest(route, scope),
                available = NativeRouteContract.available,
            )
                is NativeNavigationResolution.Open
        )
    }
}
