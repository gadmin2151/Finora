package work.gadmin.finora

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import work.gadmin.finora.data.*

class PaymentHistoryPagingTest {
    private fun tx(id: Int) =
        FinanceTransaction(
            "tx-$id",
            "expense",
            "Synthetic",
            1250,
            "MDL",
            "cash",
            "2026-10-01",
            "1",
            version = 1,
        )

    @Test
    fun findsAnExpenseBeyondTheFirstTwoHundredRows() = runBlocking {
        val all = (0..449).map(::tx)
        val offsets = mutableListOf<Int>()
        val result = completePaymentHistory { offset ->
            offsets += offset
            IncomeHistory(all.drop(offset).take(200), all.size)
        }
        assertEquals(listOf(0, 200, 400), offsets)
        assertEquals(450, result.size)
        assertEquals("tx-449", result.last().id)
    }

    @Test
    fun incompleteResponseCannotBeMistakenForNoMatches() = runBlocking {
        var calls = 0
        try {
            completePaymentHistory {
                calls++
                if (calls == 1) IncomeHistory(listOf(tx(0)), 2) else IncomeHistory(emptyList(), 2)
            }
            fail("Incomplete response must fail")
        } catch (expected: IllegalArgumentException) {
            assertEquals(2, calls)
        }
    }

    @Test
    fun concurrentHistoryChangeRequiresReload() = runBlocking {
        var calls = 0
        try {
            completePaymentHistory {
                calls++
                if (calls == 1) IncomeHistory(listOf(tx(0)), 2) else IncomeHistory(listOf(tx(1)), 3)
            }
            fail("Changed total must fail")
        } catch (expected: IllegalArgumentException) {
            assertEquals(2, calls)
        }
    }

    @Test
    fun repeatedPageCannotSilentlyHideEarlierTransactions() = runBlocking {
        var calls = 0
        try {
            completePaymentHistory {
                calls++
                IncomeHistory(listOf(tx(0)), 2)
            }
            fail("Repeated page must fail")
        } catch (expected: IllegalArgumentException) {
            assertEquals(2, calls)
        }
    }
}
