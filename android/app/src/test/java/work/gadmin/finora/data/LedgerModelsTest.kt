package work.gadmin.finora.data

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class LedgerModelsTest {
    private val accounts =
        listOf(
            Account("mdl", "Cash", "MDL", 50000),
            Account("mdl2", "Bank", "MDL"),
            Account("eur", "Euro", "EUR"),
            Account("old", "Old", "MDL", archived = true),
        )

    @Test
    fun manualExpenseUsesExactDecimalAndStableKeyWithoutBalanceMutation() {
        val form =
            LedgerEdit(
                accountId = "mdl",
                amount = "123,45",
                categoryId = "food",
                merchant = "Shop",
                date = "2026-01-01",
            )
        val command = form.command(accounts)
        assertEquals("POST", command.method)
        assertEquals("/api/transactions", command.path)
        val body = command.body!!
        assertEquals("expense", body["kind"]!!.jsonPrimitive.content)
        assertEquals("123.45", body["amount"]!!.jsonPrimitive.content)
        assertEquals("food", body["category_id"]!!.jsonPrimitive.content)
        assertEquals(command, form.command(accounts))
        assertFalse(body.containsKey("opening_balance"))
        assertFalse(body.containsKey("target_balance"))
    }

    @Test
    fun sameCurrencyTransferIsSingleTransferWithSourceAndTarget() {
        val command =
            LedgerEdit(
                    kind = "transfer",
                    accountId = "mdl",
                    targetAccountId = "mdl2",
                    amount = "9.99",
                    date = "2026-01-01",
                )
                .command(accounts)
        assertEquals("transfer", command.body!!["kind"]!!.jsonPrimitive.content)
        assertEquals("mdl2", command.body!!["target_account_id"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, command.body!!["target_amount"])
        assertEquals(JsonNull, command.body!!["category_id"])
    }

    @Test
    fun crossCurrencyTransferRequiresExactDestinationAmountAndFx() {
        val form =
            LedgerEdit(
                kind = "transfer",
                accountId = "eur",
                targetAccountId = "mdl",
                amount = "1.23",
                targetAmount = "24.79",
                fxRate = "20.15447154",
                date = "2026-01-01",
            )
        val body = form.command(accounts).body!!
        assertEquals("1.23", body["amount"]!!.jsonPrimitive.content)
        assertEquals("24.79", body["target_amount"]!!.jsonPrimitive.content)
        assertEquals("20.15447154", body["fx_rate"]!!.jsonPrimitive.content)
        assertThrows(IllegalArgumentException::class.java) {
            form.copy(targetAmount = "").command(accounts)
        }
        assertThrows(IllegalArgumentException::class.java) {
            form.copy(fxRate = "").command(accounts)
        }
        assertThrows(IllegalArgumentException::class.java) {
            form.copy(targetAmount = "1.001").command(accounts)
        }
    }

    @Test
    fun rejectsInvalidMoneyArchivedAccountSelfTransferAndFutureDate() {
        val form = LedgerEdit(accountId = "mdl", amount = "1", date = "2026-01-01")
        listOf("0", "-1", "1.001", "1000000000.01", "NaN").forEach { amount ->
            assertThrows(IllegalArgumentException::class.java) {
                form.copy(amount = amount).command(accounts)
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            form.copy(accountId = "old").command(accounts)
        }
        assertThrows(IllegalArgumentException::class.java) {
            form.copy(kind = "transfer", targetAccountId = "mdl").command(accounts)
        }
        assertThrows(IllegalArgumentException::class.java) {
            form.copy(date = "2100-01-01").command(accounts)
        }
    }

    @Test
    fun editIncludesOptimisticVersion() {
        val command =
            LedgerEdit(id = "tx", version = 7, accountId = "mdl", amount = "1", date = "2026-01-01")
                .command(accounts)
        assertEquals("PUT", command.method)
        assertEquals("/api/transactions/tx", command.path)
        assertEquals(7, command.body!!["version"]!!.jsonPrimitive.int)
    }

    @Test
    fun serverTransferTargetMinorIsDecodedWithoutPrecisionLoss() {
        val result = Json {
            ignoreUnknownKeys = true
        }
            .decodeFromString<LedgerHistory>(
                """{"items":[{"id":"tx","kind":"transfer","amount_minor":99999999999,"currency":"EUR","account_id":"eur","target_account_id":"mdl","target_minor":12345678901,"occurred_on":"2026-01-01","version":2,"fx_rate":"20.12345678","base_minor":2012345678900}],"total":1}"""
            )
        assertEquals(12345678901L, result.items.single().target_amount_minor)
        assertEquals(99999999999L, result.items.single().amount_minor)
        assertEquals("20.12345678", result.items.single().fx_rate)
    }

    @Test
    fun mdlToForeignTransferRequiresReceivedMoneyWithoutSourceFx() {
        val form =
            LedgerEdit(
                kind = "transfer",
                accountId = "mdl",
                targetAccountId = "eur",
                amount = "100.00",
                targetAmount = "4.97",
                date = "2026-01-01",
            )
        val body = form.command(accounts).body!!
        assertEquals(JsonNull, body["fx_rate"])
        assertEquals("4.97", body["target_amount"]!!.jsonPrimitive.content)
        assertThrows(IllegalArgumentException::class.java) {
            form.copy(targetAccountId = "old").command(accounts)
        }
    }
}
