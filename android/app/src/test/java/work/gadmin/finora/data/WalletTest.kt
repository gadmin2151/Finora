package work.gadmin.finora.data

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class WalletTest {
    private val account = Account("a", "Main", "MDL", 12000)

    @Test
    fun correctionHasExplicitTargetExpectedBalanceAndStableRetryKey() {
        val form = WalletEdit(account, BalanceMode.ADD, "12.50")
        assertEquals(13250L, form.targetMinor())
        val body = form.payload()
        assertEquals("132.50", body.getValue("target_balance").jsonPrimitive.content)
        assertEquals(12000L, body.getValue("expected_balance_minor").jsonPrimitive.long)
        assertEquals("adjustment", body.getValue("effect").jsonPrimitive.content)
        assertEquals(form.payload(), body)
        assertEquals(
            "income_expense",
            form
                .copy(countAsIncomeExpense = true)
                .payload()
                .getValue("effect")
                .jsonPrimitive
                .content,
        )
    }

    @Test
    fun subtractionAndSignedActualBalanceUseExactMinorUnits() {
        assertEquals(-501L, WalletEdit(account, BalanceMode.SUBTRACT, "125.01").targetMinor())
        assertEquals(-225L, WalletEdit(account, amount = "-2,25").targetMinor())
        assertEquals(0L, WalletEdit(account, amount = "0").targetMinor())
        assertThrows(IllegalArgumentException::class.java) {
            WalletEdit(account, BalanceMode.ADD, "-1").targetMinor()
        }
        assertThrows(IllegalArgumentException::class.java) {
            WalletEdit(account, amount = "0.001").targetMinor()
        }
        assertThrows(IllegalArgumentException::class.java) { WalletEdit(account).payload() }
    }

    @Test
    fun currenciesStaySeparateAndArchivedMoneyRemainsIncluded() {
        assertEquals(
            mapOf("EUR" to 400L, "MDL" to 13499L),
            walletTotals(
                listOf(
                    account,
                    account.copy(id = "b", balance_minor = 500),
                    account.copy(id = "c", currency = "EUR", balance_minor = 400),
                    account.copy(id = "d", archived = true, balance_minor = 999),
                )
            ),
        )
        val euro = WalletEdit(account.copy(currency = "EUR"), amount = "130")
        assertThrows(IllegalArgumentException::class.java) { euro.payload() }
        assertEquals(
            "20.15",
            euro.copy(fxRate = "20.15").payload().getValue("fx_rate").jsonPrimitive.content,
        )
    }

    @Test
    fun categoryMonthIncludesWholeLeapMonth() {
        assertEquals("2024-02-01" to "2024-02-29", purchaseMonthRange("2024-02"))
        assertEquals("2026-12-01" to "2026-12-31", purchaseMonthRange("2026-12"))
    }
}
