package work.gadmin.finora.data

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class NativeAnalyticsControllerTest {
    private val scope = NavigationScope("org", "2026-10", "user@server")

    private fun report() =
        AnalyticsReport(ReportQuery("summary", "2026-10-01", "2026-10-31"), "Summary")

    private fun item(id: String) =
        PurchaseItem(id, "receipt", "Item", "1", "pcs", 100, "Shop", "MDL")

    @Test
    fun reportAndPurchasePaginationUseCurrentScopeWithoutWrites() = runBlocking {
        val calls = mutableListOf<Pair<NavigationScope, Int>>()
        val reader =
            object : NativeAnalyticsReader {
                override suspend fun report(scope: NavigationScope, kind: NativeReportKind) =
                    report()

                override suspend fun purchases(scope: NavigationScope, offset: Int): PurchasePage {
                    calls += scope to offset
                    return PurchasePage(
                        listOf(item(if (offset == 0) "first" else "second")),
                        total = 2,
                    )
                }
            }
        val controller =
            NativeAnalyticsController(
                CoroutineScope(Dispatchers.Unconfined),
                reader,
                { scope },
                { fail(it.message) },
            )
        controller.open(NativeAnalyticsMode.REPORTS)
        assertEquals("Summary", controller.state.value.report?.title)
        controller.open(NativeAnalyticsMode.PURCHASES)
        controller.load(more = true)
        assertEquals(listOf(scope to 0, scope to 1), calls)
        assertEquals(
            listOf("first", "second"),
            controller.state.value.purchases.items.map { it.id },
        )
        assertFalse(controller.state.value.loading)
        controller.reset()
    }

    @Test
    fun delayedResultCannotCrossOrganizationMonthOrSession() = runBlocking {
        for (newScope in
            listOf(
                scope.copy(organizationId = "other"),
                scope.copy(month = "2026-09"),
                scope.copy(sessionKey = "other@server"),
            )) {
            var selected = scope
            val pending = CompletableDeferred<AnalyticsReport>()
            val reader =
                object : NativeAnalyticsReader {
                    override suspend fun report(scope: NavigationScope, kind: NativeReportKind) =
                        pending.await()

                    override suspend fun purchases(scope: NavigationScope, offset: Int) =
                        PurchasePage()
                }
            val controller =
                NativeAnalyticsController(
                    CoroutineScope(Dispatchers.Unconfined),
                    reader,
                    { selected },
                    { fail(it.message) },
                )
            controller.open(NativeAnalyticsMode.REPORTS)
            selected = newScope
            pending.complete(report())
            assertNull(controller.state.value.report)
            controller.reset()
        }
    }
}
