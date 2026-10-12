package work.gadmin.finora.data

import java.io.IOException
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class DebtHistoryControllerTest {
    private val debt = Debt("debt", "Synthetic person", "lent", "MDL", 0, 500)
    private val navigation = NavigationScope("organization", "2026-10", "user@server")

    private fun entry(id: String, voided: Boolean = false) =
        DebtHistoryEntry(
            id,
            "debt_repayment_in",
            "repayment",
            "2026-10-12",
            "2026-10-12T10:00:00Z",
            250,
            0,
            "account",
            "Cash",
            "Synthetic note",
            voided,
        )

    private fun page(vararg entries: DebtHistoryEntry, total: Int = 3, repaid: Long = 750) =
        DebtHistory("debt", "MDL", 500, 750 - repaid, 250, repaid, entries.toList(), total)

    private fun test(
        api: DebtHistoryApi,
        current: () -> NavigationScope? = { navigation },
        auth: (Exception) -> Unit = { throw AssertionError(it) },
        block: (DebtHistoryController) -> Unit,
    ) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            block(DebtHistoryController(scope, api, current, auth))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun closedDebtPaginatesIncludingReversedEntriesAndImportedOpeningBalance() {
        val offsets = mutableListOf<Int>()
        test({ org, id, offset ->
            assertEquals("organization", org)
            assertEquals("debt", id)
            offsets += offset
            if (offset == 0) page(entry("3"), entry("2", voided = true)) else page(entry("1"))
        }) { controller ->
            controller.open(debt)
            controller.more()
            controller.more()
            val state = controller.state.value
            assertEquals(listOf(0, 2), offsets)
            assertEquals(listOf("3", "2", "1"), state.page!!.items.map { it.id })
            assertTrue(state.page.items[1].voided)
            assertEquals(500L, state.page.initial_minor)
            assertEquals(0L, state.page.remaining_minor)
            assertFalse(state.loading)
            assertNull(state.error)
        }
    }

    @Test
    fun failedPageRetainsLoadedEntriesAndRetriesExactOffset() {
        val offsets = mutableListOf<Int>()
        test({ _, _, offset ->
            offsets += offset
            if (offsets.size == 2) throw IOException("Synthetic offline")
            if (offset == 0) page(entry("3"), entry("2")) else page(entry("1"))
        }) { controller ->
            controller.open(debt)
            controller.more()
            assertEquals(2, controller.state.value.page!!.items.size)
            assertNotNull(controller.state.value.error)
            assertFalse(controller.state.value.loading)
            controller.retry()
            assertEquals(listOf(0, 2, 2), offsets)
            assertEquals(3, controller.state.value.page!!.items.size)
            assertNull(controller.state.value.error)
        }
    }

    @Test
    fun concurrentChangeReloadsFirstPageInsteadOfMixingBalances() {
        val offsets = mutableListOf<Int>()
        test({ _, _, offset ->
            offsets += offset
            when (offsets.size) {
                1 -> page(entry("3"), entry("2"))
                2 -> page(entry("2"), entry("1"), total = 4, repaid = 500)
                else -> page(entry("4"), entry("3"), total = 4, repaid = 500)
            }
        }) { controller ->
            controller.open(debt)
            controller.more()
            assertEquals(listOf(0, 2, 0), offsets)
            assertEquals(listOf("4", "3"), controller.state.value.page!!.items.map { it.id })
            assertEquals(250L, controller.state.value.page!!.remaining_minor)
            assertEquals(2, controller.state.value.nextOffset)
        }
    }

    @Test
    fun lateResponseCannotCrossOrganizationOrLoginScope() {
        for (changed in
            listOf(
                navigation.copy(organizationId = "other"),
                navigation.copy(sessionKey = "other@server"),
            )) {
            var current = navigation
            val response = CompletableDeferred<DebtHistory>()
            test({ _, _, _ -> response.await() }, current = { current }) { controller ->
                controller.open(debt)
                assertTrue(controller.state.value.loading)
                current = changed
                response.complete(page(entry("1")))
                assertNull(controller.state.value.page)
                controller.retry()
                controller.close()
                assertNull(controller.state.value.debt)
            }
        }
    }

    @Test
    fun cancelledRequestCannotReopenDismissedHistory() {
        val response = CompletableDeferred<DebtHistory>()
        test({ _, _, _ -> withContext(NonCancellable) { response.await() } }) { controller ->
            controller.open(debt)
            controller.close()
            response.complete(page(entry("1")))
            assertNull(controller.state.value.debt)
            assertNull(controller.state.value.page)
            assertFalse(controller.state.value.loading)
        }
    }

    @Test
    fun revokedAccessClearsCachedEntriesAndRestartsRetryAtFirstPage() {
        val offsets = mutableListOf<Int>()
        test({ _, _, offset ->
            offsets += offset
            if (offsets.size == 2) throw ApiException(403, "Access revoked")
            page(entry("3"), entry("2"))
        }) { controller ->
            controller.open(debt)
            controller.more()
            assertNull(controller.state.value.page)
            assertEquals("Access revoked", controller.state.value.error)
            controller.retry()
            assertEquals(listOf(0, 2, 0), offsets)
        }
    }

    @Test
    fun expiredSessionClosesHistoryAndUsesExistingAuthenticationHandler() {
        var authErrors = 0
        test({ _, _, _ -> throw ApiException(401, "Expired") }, auth = { authErrors++ }) {
            controller ->
            controller.open(debt)
            assertEquals(1, authErrors)
            assertNull(controller.state.value.debt)
            assertNull(controller.state.value.page)
        }
    }
}
