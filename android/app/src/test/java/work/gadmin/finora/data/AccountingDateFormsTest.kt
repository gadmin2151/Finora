package work.gadmin.finora.data

import java.util.TimeZone
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class AccountingDateFormsTest {
    private fun inDeviceZones(check: () -> Unit) {
        val original = TimeZone.getDefault()
        try {
            for (zone in listOf("GMT-12:00", "GMT+14:00")) {
                TimeZone.setDefault(TimeZone.getTimeZone(zone))
                check()
            }
        } finally {
            TimeZone.setDefault(original)
        }
    }

    @Test
    fun newReceiptAndFinanceFormsUseAccountingDateInsteadOfDeviceDate() = inDeviceZones {
        val today = accountingToday().toString()
        assertEquals(today, ReceiptForm.manual(emptyList()).date)
        assertEquals(today, FinanceEdit(FinanceEditKind.INCOME).date)
    }

    @Test
    fun incomeAcceptsAccountingTodayAndRejectsTomorrowInEveryDeviceZone() = inDeviceZones {
        val today = accountingToday()
        val form =
            FinanceEdit(
                FinanceEditKind.INCOME,
                name = "Synthetic",
                amount = "1.00",
                accountId = "cash",
                date = today.toString(),
            )
        val accounts = listOf(Account("cash", "Synthetic cash", "MDL"))
        assertEquals(
            today.toString(),
            form.command(accounts).body!!["occurred_on"]!!.jsonPrimitive.content,
        )
        assertThrows(IllegalArgumentException::class.java) {
            form.copy(date = today.plusDays(1).toString()).command(accounts)
        }
    }
}
