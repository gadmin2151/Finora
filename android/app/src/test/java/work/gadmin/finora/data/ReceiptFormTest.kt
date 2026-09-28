package work.gadmin.finora.data

import java.time.LocalDate
import java.util.UUID
import kotlinx.serialization.encodeToString
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
    fun correctedNamesReclassifyCarriedCategoriesButKeepExplicitChoices() {
        val carried = ReceiptLineForm(name = "Bread", categoryId = "bakery")
        assertEquals("bakery", carried.rename("Bread").categoryId)
        assertNull(carried.rename("Coffee").categoryId)
        val chosen = carried.chooseCategory("gifts").rename("Coffee")
        assertEquals("gifts", chosen.categoryId)
        assertTrue(chosen.categoryChosen)
        val restored = Json.decodeFromString<ReceiptLineForm>(Json.encodeToString(chosen))
        assertEquals("gifts", restored.rename("Tea").categoryId)
        assertNull(restored.chooseCategory(null).rename("Tea").categoryId)
        assertFalse(Json.decodeFromString<ReceiptLineForm>("{}").categoryChosen)
        val payload =
            form().copy(items = listOf(form().items.single().chooseCategory("gifts"))).payload()
        val item = payload.getValue("items").jsonArray.single().jsonObject
        assertEquals("gifts", item.getValue("category_id").jsonPrimitive.content)
        assertFalse(item.containsKey("categoryChosen"))
    }

    @Test
    fun receiptAndPurchaseAuthorsRemainCompatibleWithOlderResponses() {
        val receipt =
            """{"id":"r","source":"photo","status":"posted","version":1,"created_at":"2026-09-28"}"""
        assertNull(Json.decodeFromString<Receipt>(receipt).creator)
        val author = ReceiptAuthor("u", "Test", "tester", true)
        assertEquals(
            author,
            Json.decodeFromString<Receipt>(
                    Json.encodeToString(
                        Json.decodeFromString<Receipt>(receipt).copy(creator = author)
                    )
                )
                .creator,
        )
        val purchase =
            """{"id":"p","receipt_id":"r","name":"Tea","quantity":"1","unit":"шт","total_minor":100,"merchant":"Shop","currency":"MDL"}"""
        assertNull(Json.decodeFromString<PurchaseItem>(purchase).creator)
        val authors =
            Json.decodeFromString<ReceiptAuthorPage>(
                """{"items":[{"id":null,"name":"Unknown author"}]}"""
            )
        assertNull(authors.items.single().id)
        assertFalse(authors.has_more)
    }

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
    fun manualReceiptUsesActiveAccountAndTodayWithoutRequiringAnOriginal() {
        val accounts =
            listOf(
                Account("old", "Archived", "MDL", archived = true),
                Account("eur", "Euro", "EUR"),
                Account("cash", "Cash", "MDL"),
            )
        val manual = ReceiptForm.manual(accounts, "old")
        assertEquals("cash", manual.accountId)
        assertEquals("MDL", manual.currency)
        assertEquals("1", manual.fxRate)
        assertEquals(LocalDate.now().toString(), manual.date)
        assertEquals(1, manual.items.size)
        assertEquals("", manual.merchant)
        val foreign = ReceiptForm.manual(accounts, "eur")
        assertEquals("EUR", foreign.currency)
        assertEquals("", foreign.fxRate)
        assertNull(ReceiptForm.manual(emptyList()).accountId)
    }

    @Test
    fun manualPayloadRetainsRetryKeyAndDiscountWithoutVersionOrBankTransaction() {
        val key = UUID.randomUUID().toString()
        val payload = form().manualPayload(key)
        assertEquals(key, payload["request_key"]?.jsonPrimitive?.content)
        assertEquals(payload, form().manualPayload(key))
        assertFalse(payload.containsKey("version"))
        assertFalse(payload.containsKey("transaction_id"))
        assertEquals(
            "11.50",
            payload["items"]!!.jsonArray[0].jsonObject["total"]?.jsonPrimitive?.content,
        )
        assertThrows(IllegalArgumentException::class.java) { form().manualPayload("invalid") }
    }

    @Test
    fun calculatedLineTotalsUseExactCentsAndRoundWeightedGoodsHalfUp() {
        assertEquals(
            "0.30",
            ReceiptLineForm(quantity = "3", unitPrice = "0.10").calculateTotal().total,
        )
        assertEquals(
            "44.59",
            ReceiptLineForm(quantity = "1.312", unitPrice = "33.99").calculateTotal().total,
        )
        assertEquals(
            "0.01",
            ReceiptLineForm(quantity = "0,5", unitPrice = "0.01").calculateTotal().total,
        )
        assertEquals("", ReceiptLineForm(quantity = "0", unitPrice = "10").calculateTotal().total)
        assertEquals("", ReceiptLineForm(quantity = "1", unitPrice = "NaN").calculateTotal().total)
        assertEquals(
            "",
            ReceiptLineForm(quantity = "100000", unitPrice = "100000").calculateTotal().total,
        )
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
