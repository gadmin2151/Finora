package work.gadmin.finora

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.time.LocalDate
import java.time.YearMonth
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import work.gadmin.finora.data.*

/** Opt-in integration against disposable organizations only; never uses the owner's session. */
@RunWith(AndroidJUnit4::class)
class FinanceAcceptanceTest {
    @Test
    fun httpsIncomePlansDebtRepaymentAndRoleBoundaries() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.filesDir, "finance-acceptance.json")
        assumeTrue("Provide isolated QA credentials to run the HTTPS checks", file.isFile)
        val data = Json.parseToJsonElement(file.readText()).jsonObject
        fun credential(key: String) = data.getValue(key).jsonPrimitive.content
        val client = ApiClient(credential("server"))
        val user = client.login(credential("username"), credential("password"))
        assertTrue(
            user.organizations.size == 1 &&
                user.organizations.all { it.name.startsWith("Android QA Finance ") && it.isAdmin }
        )
        val org = user.organizations.single().id
        val month = YearMonth.now().toString()
        val accounts = client.accounts(org)
        val cash = accounts.first { it.currency == "MDL" }
        val opening = cash.balance_minor
        suspend fun write(form: FinanceEdit) = client.financeWrite(org, form.command(accounts))
        suspend fun balance() = client.accounts(org).first { it.id == cash.id }.balance_minor
        suspend fun forbidden(block: suspend () -> Unit) {
            try {
                block()
                fail("Restricted request was allowed")
            } catch (error: ApiException) {
                assertEquals(403, error.code)
            }
        }
        try {
            val occasional =
                FinanceEdit(
                    FinanceEditKind.INCOME,
                    name = "QA income",
                    amount = "12.50",
                    accountId = cash.id,
                )
            write(occasional)
            write(occasional)
            assertEquals(opening + 1250, balance())
            val tx = client.incomeHistory(org, month).items.single()
            write(
                occasional.copy(
                    id = tx.id,
                    version = tx.version,
                    amount = "15.00",
                    requestKey = java.util.UUID.randomUUID().toString(),
                )
            )
            assertEquals(opening + 1500, balance())

            val source =
                FinanceEdit(
                    FinanceEditKind.PLAN,
                    name = "QA monthly",
                    amount = "15.00",
                    accountId = cash.id,
                )
            write(source)
            write(source)
            var plan = client.incomePlans(org).single()
            write(source.copy(id = plan.id, version = plan.version, name = "QA updated"))
            plan = client.incomePlans(org).single()
            assertEquals("QA updated", plan.name)
            for (active in listOf(false, true)) {
                client.financeWrite(
                    org,
                    FinanceCommand(
                        "/api/income/templates/${plan.id}/active",
                        "PUT",
                        buildJsonObject {
                            put("active", active)
                            put("version", plan.version)
                        },
                    ),
                )
                plan = client.incomePlans(org).single()
                assertEquals(active, plan.active)
            }
            val due = client.income(org, month).occurrences.single()
            client.financeWrite(
                org,
                FinanceCommand("/api/income/occurrences/${due.id}/skip", "POST"),
            )
            assertEquals("skipped", client.income(org, month).occurrences.single().status)
            client.financeWrite(
                org,
                FinanceCommand("/api/income/occurrences/${due.id}/skip", "POST"),
            )
            val receive =
                FinanceEdit(
                    FinanceEditKind.RECEIVE,
                    id = due.id,
                    amount = "15.00",
                    accountId = cash.id,
                    transactionId = tx.id,
                    date = LocalDate.now().toString(),
                )
            write(receive)
            write(receive)
            assertEquals(opening + 1500, balance())
            assertEquals(1500L, client.income(org, month).regular_minor)
            val received = client.incomeHistory(org, month).items.single()
            client.financeWrite(
                org,
                FinanceCommand(
                    "/api/transactions/${received.id}?version=${received.version}",
                    "DELETE",
                ),
            )
            assertEquals(opening, balance())
            val newReceive =
                receive.copy(
                    transactionId = null,
                    requestKey = java.util.UUID.randomUUID().toString(),
                )
            write(newReceive)
            write(newReceive)
            assertEquals(opening + 1500, balance())

            val lending =
                FinanceEdit(
                    FinanceEditKind.DEBT,
                    name = "QA lender",
                    amount = "100",
                    accountId = cash.id,
                    direction = "lent",
                )
            write(lending)
            write(lending)
            val debt = client.debts(org).single()
            assertEquals(opening - 8500, balance())
            val repayment =
                FinanceEdit(
                    FinanceEditKind.REPAY,
                    id = debt.id,
                    amount = "40",
                    accountId = cash.id,
                    remainingMinor = debt.remaining_minor,
                )
            write(repayment)
            write(repayment)
            assertEquals(6000L, client.debts(org).single().remaining_minor)
            assertEquals(opening - 4500, balance())
            write(
                repayment.copy(
                    amount = "60",
                    remainingMinor = 6000,
                    requestKey = java.util.UUID.randomUUID().toString(),
                )
            )
            assertEquals(0L, client.debts(org).single().remaining_minor)
            assertEquals(opening + 1500, balance())

            val old =
                lending.copy(
                    name = "QA old borrowed",
                    direction = "borrowed",
                    mode = "existing",
                    amount = "200",
                    accountId = "",
                    requestKey = java.util.UUID.randomUUID().toString(),
                )
            write(old)
            write(old)
            assertEquals(opening + 1500, balance())
            val borrowed = client.debts(org).first { it.person == old.name }
            write(
                FinanceEdit(
                    FinanceEditKind.REPAY,
                    id = borrowed.id,
                    amount = "50",
                    accountId = cash.id,
                    remainingMinor = borrowed.remaining_minor,
                )
            )
            assertEquals(15000L, client.debts(org).first { it.id == borrowed.id }.remaining_minor)
            assertEquals(opening - 3500, balance())
            assertEquals(1500L, client.income(org, month).received_minor)
            forbidden {
                client.financeWrite(credential("foreign_org"), occasional.command(accounts))
            }

            val member = ApiClient(credential("server"))
            val reader = member.login(credential("member_username"), credential("member_password"))
            try {
                assertFalse(reader.organizations.single().isAdmin)
                assertEquals(2, member.debts(org).size)
                assertEquals(1500L, member.income(org, month).received_minor)
                forbidden { member.financeWrite(org, occasional.command(accounts)) }
                forbidden { member.financeWrite(org, repayment.command(accounts)) }
                assertEquals(opening - 3500, balance())
            } finally {
                member.logout()
            }
        } finally {
            client.logout()
        }
    }
}
