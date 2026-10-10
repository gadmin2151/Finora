package work.gadmin.finora

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import work.gadmin.finora.data.*

class PaymentFormTest {
    private val accounts =
        listOf(
            Account("cash", "Synthetic cash", "MDL"),
            Account("eur", "Synthetic EUR", "EUR"),
            Account("closed", "Closed", "MDL", archived = true),
        )

    private fun form() = PaymentEdit(name = "Synthetic rent", amount = "12,50", accountId = "cash")

    @Test
    fun templateCreatesPlanWithoutTransactionKey() {
        val command = form().command(accounts)
        assertEquals("/api/bills", command.path)
        assertEquals("POST", command.method)
        assertEquals("12.50", command.body!!["amount"]!!.jsonPrimitive.content)
        assertFalse(command.body.containsKey("idempotency_key"))
        assertFalse(command.body.containsKey("transaction_id"))
    }

    @Test
    fun payKeepsExactKeyAndPayloadForRetry() {
        val form = form().copy(occurrenceId = "synthetic-occurrence")
        val command = form.command(accounts)
        assertEquals("/api/occurrences/synthetic-occurrence/pay", command.path)
        assertEquals(form.key, command.body!!["idempotency_key"]!!.jsonPrimitive.content)
        assertEquals(command, form.command(accounts))
        assertEquals(form.key, form.copy(amount = "13").key)
    }

    @Test
    fun linkExpenseDoesNotBecomeANewPayment() {
        val command = form().copy(occurrenceId = "occ", transactionId = "expense").command(accounts)
        assertEquals("expense", command.body!!["transaction_id"]!!.jsonPrimitive.content)
        assertFalse(command.body.containsKey("currency"))
        assertFalse(command.body.containsKey("name"))
    }

    @Test
    fun validatesActiveMatchingCurrency() {
        assertThrows(IllegalArgumentException::class.java) {
            form().copy(accountId = "closed").command(accounts)
        }
        assertThrows(IllegalArgumentException::class.java) {
            form().copy(currency = "EUR").command(accounts)
        }
        assertThrows(IllegalArgumentException::class.java) {
            form().copy(currency = "EUR", accountId = "eur").command(accounts)
        }
        val command =
            form()
                .copy(currency = "EUR", accountId = "eur", fxRate = "19,12345678")
                .command(accounts)
        assertEquals("19.12345678", command.body!!["fx_rate"]!!.jsonPrimitive.content)
    }

    @Test
    fun paymentDisallowsFutureButTemplateAllowsFuture() {
        val future = accountingToday().plusDays(3).toString()
        form().copy(date = future).command(accounts)
        assertThrows(IllegalArgumentException::class.java) {
            form().copy(occurrenceId = "occ", date = future).command(accounts)
        }
    }

    @Test
    fun decodesActualServerShapeWithUnknownBaseAmount() {
        val json = Json { ignoreUnknownKeys = true }
        val rows =
            json.decodeFromString<List<PaymentOccurrence>>(
                """[{"id":"occ","bill_id":"bill","name":"Synthetic rent","amount_minor":1250,"base_minor":1250,"currency":"MDL","fx_rate":"1","category_id":null,"account_id":"cash","due_date":"2026-10-10","recurrence":"monthly","active":true,"transaction_id":null,"status":"upcoming"}]"""
            )
        assertEquals("occ", rows.single().id)
        assertNull(rows.single().transaction_id)
        assertEquals(1250L, rows.single().amount_minor)
    }

    @Test
    fun rejectsInvalidAmountRecurrenceAndDate() {
        for (value in listOf("0", "-1", "1000000001", "12.345")) assertThrows(
            IllegalArgumentException::class.java
        ) {
            form().copy(amount = value).command(accounts)
        }
        assertThrows(IllegalArgumentException::class.java) {
            form().copy(recurrence = "hourly").command(accounts)
        }
        assertThrows(IllegalArgumentException::class.java) {
            form().copy(date = "2026-02-30").command(accounts)
        }
    }
}
