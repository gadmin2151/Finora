package work.gadmin.finora.data

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class MandatoryPaymentReceiptTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val base =
        """{"id":"receipt","source":"manual","status":"posted","version":1,"created_at":"2026-10-12T10:00:00Z","total_minor":12345,"transaction_id":"same-expense"}"""

    @Test
    fun legacyReceiptsRemainCompatibleWithoutMandatoryMetadata() {
        assertNull(json.decodeFromString<Receipt>(base).mandatory_payment)
        assertNull(
            json
                .decodeFromString<Receipt>(base.dropLast(1) + ",\"mandatory_payment\":null}")
                .mandatory_payment
        )
    }

    @Test
    fun paidBillMetadataPreservesPostedReceiptAndExistingExpenseLink() {
        val receipt =
            json.decodeFromString<Receipt>(
                base.dropLast(1) +
                    """, "mandatory_payment":{"bill_id":"bill","occurrence_id":"occurrence","due_date":"2026-10-01"}}"""
            )
        assertEquals("occurrence", receipt.mandatory_payment!!.occurrence_id)
        assertEquals("same-expense", receipt.transaction_id)
        assertEquals("posted", receipt.status)
        assertFalse(receipt.review_required)
        assertEquals(12345L, receipt.total_minor)
    }

    @Test
    fun purchaseItemsShareTheReceiptMetadataWithoutChangingAmounts() {
        val purchase =
            json.decodeFromString<PurchaseItem>(
                """{"id":"item","receipt_id":"receipt","name":"Rent","quantity":"1","unit":"pcs","total_minor":12345,"merchant":"Rent","currency":"MDL","mandatory_payment":{"bill_id":"bill","occurrence_id":"occurrence","due_date":"2026-10-01"}}"""
            )
        assertEquals("bill", purchase.mandatory_payment!!.bill_id)
        assertEquals(12345L, purchase.total_minor)
    }
}
