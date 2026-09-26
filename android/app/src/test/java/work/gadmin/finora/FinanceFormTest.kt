package work.gadmin.finora

import java.time.LocalDate
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import work.gadmin.finora.data.*

class FinanceFormTest {
    private val accounts =
        listOf(
            Account("cash", "Наличные", "MDL"),
            Account("euro", "EUR", "EUR"),
            Account("old", "Закрыт", "MDL", archived = true),
        )

    private fun form(kind: FinanceEditKind) =
        FinanceEdit(kind, amount = "12,50", accountId = "cash", name = "Example")

    private fun rejected(block: () -> Unit) {
        assertThrows(IllegalArgumentException::class.java, block)
    }

    @Test
    fun incomeKeepsExactCentsAndRequestKeyOnRetry() {
        val form = form(FinanceEditKind.INCOME)
        val command = form.command(accounts)
        assertEquals("/api/transactions", command.path)
        assertEquals("POST", command.method)
        assertEquals("12.50", command.body!!["amount"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, command.body["fx_rate"])
        assertEquals(command, form.command(accounts))
        assertEquals(form.requestKey, form.copy(note = "changed").requestKey)
    }

    @Test
    fun oldDebtDoesNotRequireOrPostAnAccount() {
        val command =
            form(FinanceEditKind.DEBT)
                .copy(mode = "existing", currency = "EUR", accountId = "", direction = "borrowed")
                .command(accounts)
        assertEquals(JsonNull, command.body!!["account_id"])
        assertEquals(JsonNull, command.body["fx_rate"])
        assertEquals("existing", command.body["mode"]!!.jsonPrimitive.content)
    }

    @Test
    fun foreignNewDebtRequiresMatchingActiveAccountAndRate() {
        val form = form(FinanceEditKind.DEBT).copy(currency = "EUR")
        rejected { form.command(accounts) }
        rejected { form.copy(accountId = "euro").command(accounts) }
        val command = form.copy(accountId = "euro", fxRate = "19,12345678").command(accounts)
        assertEquals("19.12345678", command.body!!["fx_rate"]!!.jsonPrimitive.content)
        rejected { form(FinanceEditKind.INCOME).copy(accountId = "old").command(accounts) }
    }

    @Test
    fun repaymentRejectsOverpaymentAndAcceptsPartialOrExactBalance() {
        val form = form(FinanceEditKind.REPAY).copy(id = "debt", remainingMinor = 1250)
        assertEquals("/api/debts/debt/repay", form.command(accounts).path)
        form.copy(amount = "5.00").command(accounts)
        rejected { form.copy(amount = "12.51").command(accounts) }
        rejected { form.copy(amount = "0").command(accounts) }
    }

    @Test
    fun fullRepaymentSendsTheReviewedBalanceAndRejectsPartialAmount() {
        val form =
            form(FinanceEditKind.REPAY)
                .copy(
                    id = "debt",
                    remainingMinor = 1250,
                    fullRepayment = true,
                    note = "Paid in full",
                )
        val command = form.command(accounts)
        assertTrue(command.body!!["full"]!!.jsonPrimitive.boolean)
        assertEquals("Paid in full", command.body["note"]!!.jsonPrimitive.content)
        rejected { form.copy(amount = "5").command(accounts) }
        assertEquals(command, form.command(accounts))
    }

    @Test
    fun debtIncreaseCanReopenClosedDebtAndKeepsItsAccountCurrency() {
        val form =
            form(FinanceEditKind.INCREASE_DEBT)
                .copy(id = "debt", remainingMinor = 0, note = "Extra loan")
        val command = form.command(accounts)
        assertEquals("/api/debts/debt/increase", command.path)
        assertEquals("Extra loan", command.body!!["note"]!!.jsonPrimitive.content)
        assertFalse(command.body.containsKey("full"))
        assertEquals(command, form.command(accounts))
        rejected { form.copy(accountId = "euro").command(accounts) }
        rejected { form.copy(accountId = "old").command(accounts) }
    }

    @Test
    fun plansAllowFutureDatesAndCarryVersionWhenEditing() {
        val form =
            form(FinanceEditKind.PLAN)
                .copy(id = "source", version = 4, date = LocalDate.now().plusMonths(1).toString())
        val command = form.command(accounts)
        assertEquals("PUT", command.method)
        assertEquals(4, command.body!!["version"]!!.jsonPrimitive.int)
        assertTrue(command.body.containsKey("start_date"))
        assertFalse(command.body.containsKey("occurred_on"))
        rejected { form.copy(name = " ").command(accounts) }
        rejected { form.copy(recurrence = "daily").command(accounts) }
        rejected { form(FinanceEditKind.INCOME).copy(date = form.date).command(accounts) }
    }

    @Test
    fun invalidAmountsAndDatesNeverLeaveTheForm() {
        for (amount in listOf("", "-1", "NaN", "1e3", "1.001", "1000000001", "1 200")) rejected {
            form(FinanceEditKind.INCOME).copy(amount = amount).command(accounts)
        }
        for (date in listOf("2026-02-30", "1980-01-01", "2101-01-01")) rejected {
            form(FinanceEditKind.PLAN).copy(date = date).command(accounts)
        }
        rejected { form(FinanceEditKind.DEBT).copy(dueDate = "wrong").command(accounts) }
    }

    @Test
    fun linkingAnIncomeRequiresExactAccountDateCurrencyAndAmount() {
        val form = form(FinanceEditKind.RECEIVE).copy(id = "occurrence")
        val tx =
            FinanceTransaction(
                "tx",
                "income",
                "Salary",
                1250,
                "MDL",
                "cash",
                form.date,
                "1",
                version = 1,
            )
        val rows =
            listOf(
                tx,
                tx.copy(id = "wrong-account", account_id = "other"),
                tx.copy(id = "wrong-amount", amount_minor = 1251),
                tx.copy(id = "linked", occurrence_id = "linked"),
                tx.copy(id = "currency", currency = "EUR"),
                tx.copy(id = "date", occurred_on = "1990-01-01"),
            )
        assertEquals(listOf(tx), matchingIncome(rows, form))
        assertEquals(
            "tx",
            form
                .copy(transactionId = "tx")
                .command(accounts)
                .body!!["transaction_id"]!!
                .jsonPrimitive
                .content,
        )
    }

    @Test
    fun nullableAndOptionalReportFieldsDecodeLikeTheServer() {
        val json = Json { ignoreUnknownKeys = true }
        val report =
            json.decodeFromString<IncomeReport>(
                """{"received_minor":1250,"regular_minor":0,"occasional_minor":1250,"expected_minor":2500,"occurrences":[{"id":"due","name":"Salary","amount_minor":2500,"currency":"MDL","account_id":null,"fx_rate":"1.00000000","due_date":"2026-09-30","status":"upcoming","bill_id":"plan","base_minor":2500}]}"""
            )
        assertNull(report.occurrences.single().received_on)
        assertNull(report.occurrences.single().account_id)
        assertEquals(1250, report.received_minor)
    }
}
