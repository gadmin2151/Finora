package work.gadmin.finora

import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test
import work.gadmin.finora.data.*

class ReceiptReviewSummaryTest {
    private val account = Account("cash", "Synthetic cash", "EUR")
    private val form =
        ReceiptForm(
            "Synthetic market",
            "2026-10-09",
            "EUR",
            "11.50",
            "cash",
            "19.50",
            1,
            listOf(
                ReceiptLineForm(
                    name = "Discounted product",
                    quantity = "2",
                    unitPrice = "6.00",
                    total = "11.50",
                )
            ),
        )

    @Test
    fun discountedTotalsAndIncludedTaxesAreNotAddedAgain() {
        val check = receiptReviewSummary(form, listOf(account))
        assertEquals(1150L, check.itemsMinor)
        assertEquals(0L, check.differenceMinor)
        assertTrue(check.problems.isEmpty())
        assertEquals("11.50", form.items.single().total)
        assertEquals(
            100L,
            receiptReviewSummary(form.copy(total = "12.50"), listOf(account)).differenceMinor,
        )
        assertEquals(
            -100L,
            receiptReviewSummary(form.copy(total = "10.50"), listOf(account)).differenceMinor,
        )
    }

    @Test
    fun missingFieldsAndTotalsNeverLookLikeAValidMatch() {
        val check =
            receiptReviewSummary(
                form.copy(merchant = "", date = "", total = "", items = emptyList()),
                emptyList(),
            )
        assertNull(check.itemsMinor)
        assertNull(check.differenceMinor)
        assertEquals(
            setOf(
                ReceiptReviewProblem.STORE,
                ReceiptReviewProblem.DATE,
                ReceiptReviewProblem.ACCOUNT,
                ReceiptReviewProblem.TOTAL,
                ReceiptReviewProblem.ITEMS,
            ),
            check.problems.toSet(),
        )
    }

    @Test
    fun malformedLinesAndWrongCurrencyAccountAreExplicit() {
        val incomplete =
            form.copy(items = listOf(form.items.single().copy(quantity = "1.0000001", total = "")))
        val check = receiptReviewSummary(incomplete, listOf(account.copy(currency = "MDL")))
        assertEquals(listOf(1), check.invalidLineNumbers)
        assertTrue(ReceiptReviewProblem.ITEM_DETAILS in check.problems)
        assertTrue(ReceiptReviewProblem.ACCOUNT in check.problems)
        assertNull(check.differenceMinor)
        assertTrue(
            ReceiptReviewProblem.DATE in
                receiptReviewSummary(
                        form.copy(date = "2026-10-11"),
                        listOf(account),
                        LocalDate.of(2026, 10, 10),
                    )
                    .problems
        )
    }

    @Test
    fun freeLinesAndExactDecimalSumsRemainValid() {
        val check =
            receiptReviewSummary(
                form.copy(
                    total = "0.30",
                    items =
                        listOf(
                            form.items.single().copy(total = "0.10"),
                            form.items.single().copy(total = "0.20"),
                            form.items
                                .single()
                                .copy(quantity = "1", unitPrice = "0.00", total = "0.00"),
                        ),
                ),
                listOf(account),
            )
        assertEquals(30L, check.itemsMinor)
        assertEquals(0L, check.differenceMinor)
        assertTrue(check.invalidLineNumbers.isEmpty())
    }

    @Test
    fun midnightBoundaryUsesAccountingDayForReviewAndPayload() {
        val clock =
            java.time.Clock.fixed(
                java.time.Instant.parse("2026-10-09T22:30:00Z"),
                java.time.ZoneOffset.UTC,
            )
        val today = accountingToday(clock)
        assertEquals(LocalDate.of(2026, 10, 10), today)
        val receipt = form.copy(date = "2026-10-10")
        assertFalse(
            ReceiptReviewProblem.DATE in
                receiptReviewSummary(receipt, listOf(account), today).problems
        )
        receipt.payload(today)
        assertTrue(
            ReceiptReviewProblem.DATE in
                receiptReviewSummary(receipt.copy(date = "2026-10-11"), listOf(account), today)
                    .problems
        )
        assertTrue(runCatching { receipt.copy(date = "2026-10-11").payload(today) }.isFailure)
    }

    @Test
    fun explicitDiscountedTotalMayBeBelowOversizedGrossProduct() {
        val receipt =
            form.copy(
                total = "900000000.00",
                items =
                    listOf(
                        form.items
                            .single()
                            .copy(
                                quantity = "100",
                                unitPrice = "10000000.00",
                                total = "900000000.00",
                            )
                    ),
            )
        val check = receiptReviewSummary(receipt, listOf(account))
        assertTrue(check.invalidLineNumbers.isEmpty())
        assertEquals(0L, check.differenceMinor)
        receipt.payload()
    }
}
