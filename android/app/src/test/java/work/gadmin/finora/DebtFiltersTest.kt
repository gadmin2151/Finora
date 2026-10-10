package work.gadmin.finora

import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test
import work.gadmin.finora.data.*

class DebtFiltersTest {
    private val today = LocalDate.of(2026, 10, 10)
    private val partial =
        Debt(
            "partial",
            "Synthetic person",
            "lent",
            "EUR",
            320,
            initial_minor = 1000,
            due_date = "2026-10-09",
        )

    @Test
    fun overdueRequiresOutstandingBalanceAndAnEarlierValidDate() {
        assertTrue(debtIsOverdue(partial, today))
        for (date in listOf(null, "2026-10-10", "2026-10-11", "invalid", "2026-02-30")) assertFalse(
            debtIsOverdue(partial.copy(due_date = date), today)
        )
        assertFalse(debtIsOverdue(partial.copy(remaining_minor = 0), today))
    }

    @Test
    fun filtersKeepPartialBalancesAndCurrenciesAndCombineDirectionWithSearch() {
        val rows =
            listOf(
                partial,
                partial.copy(
                    id = "mdl",
                    direction = "borrowed",
                    currency = "MDL",
                    remaining_minor = 1000,
                ),
                partial.copy(id = "closed", remaining_minor = 0),
                partial.copy(id = "future", due_date = "2026-10-11"),
            )
        assertEquals(
            listOf("partial", "mdl"),
            filterDebts(rows, "all", true, true, "", today).map { it.id },
        )
        assertEquals(listOf(partial), filterDebts(rows, "lent", true, true, "PERSON", today))
        assertEquals(
            listOf("EUR", "MDL"),
            filterDebts(rows, "all", false, true, "", today).map { it.currency },
        )
        assertEquals(320L, partial.remaining_minor)
        assertEquals(4, filterDebts(rows, "all", true, false, "", today).size)
        assertTrue(filterDebts(rows, "all", true, true, "not found", today).isEmpty())
    }
}
