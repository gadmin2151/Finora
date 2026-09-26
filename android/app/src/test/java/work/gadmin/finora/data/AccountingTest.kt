package work.gadmin.finora.data

import org.junit.Assert.*
import org.junit.Test

class AccountingTest {
    private val accounts =
        listOf(
            Account("cash", "Cash", "MDL"),
            Account("card", "Card", "MDL"),
            Account("usd", "USD", "USD"),
            Account("old", "Archived", "MDL", archived = true),
        )

    @Test
    fun separateKeepsCashAndCards() {
        assertEquals(
            listOf("cash", "card", "usd"),
            AccountingConfig().paymentAccounts(accounts).map { it.id },
        )
    }

    @Test
    fun combinedRoutesCashButNeverConvertsForeignCurrency() {
        val config = AccountingConfig("combined", "card", 2)
        assertEquals(listOf("card", "usd"), config.paymentAccounts(accounts).map { it.id })
        assertEquals("card", config.receiptAccount("cash", accounts))
        assertEquals("card", config.receiptAccount(null, accounts))
        assertEquals("usd", config.receiptAccount("usd", accounts))
    }
}
