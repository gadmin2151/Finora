package work.gadmin.finora.data

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ReceiptFormTest {
    private fun form() =
        ReceiptForm(
            "Market",
            "2025-09-25",
            "MDL",
            "11,50",
            "account",
            "1",
            3,
            listOf(
                ReceiptLineForm(name = "Bread", quantity = "2", unitPrice = "6.00", total = "11.50")
            ),
        )

    @Test
    fun preservesDiscountsAndCorrectionsWithExactDecimals() {
        val payload = form().payload()
        assertEquals("11.50", payload["total"]?.jsonPrimitive?.content)
        val item = payload["items"]!!.jsonArray.single().jsonObject
        assertEquals("6.00", item["unit_price"]?.jsonPrimitive?.content)
        assertEquals("11.50", item["total"]?.jsonPrimitive?.content)
        assertFalse(item.containsKey("key"))
        assertFalse(payload.containsKey("transaction_id"))
    }

    @Test
    fun refusesUnbalancedInvalidAndIncompleteExpenses() {
        listOf(
                form().copy(total = "12.00"),
                form().copy(date = "2025-02-30"),
                form().copy(date = "2999-01-01"),
                form().copy(accountId = null),
                form().copy(items = emptyList()),
                form().copy(currency = "BTC"),
                form().copy(merchant = " "),
                form().copy(total = "NaN"),
                form().copy(total = "11.501"),
            )
            .forEach { assertThrows(IllegalArgumentException::class.java) { it.payload() } }
        assertThrows(IllegalArgumentException::class.java) {
            form().copy(items = listOf(form().items[0].copy(quantity = "0"))).payload()
        }
    }

    @Test
    fun legacyDraftAndNewCapturedDraftSurviveSerialization() {
        assertFalse(
            Json.decodeFromString<Draft>("{\"qr\":\"https://shop.example/receipt\"}").pageCaptured
        )
        val draft =
            Draft(
                qr = "https://shop.example/receipt",
                pageCaptured = true,
                pageText = "TOTAL 11.50",
            )
        assertEquals(
            draft,
            Json.decodeFromString<Draft>(Json.encodeToString(Draft.serializer(), draft)),
        )
    }
}
