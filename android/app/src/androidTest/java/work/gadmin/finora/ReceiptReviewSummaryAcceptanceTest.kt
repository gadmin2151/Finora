package work.gadmin.finora

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import work.gadmin.finora.data.*
import work.gadmin.finora.localization.Message
import work.gadmin.finora.localization.tr
import work.gadmin.finora.ui.FinoraTheme
import work.gadmin.finora.ui.ReceiptReviewSummaryCard

/** Offline synthetic totals: no OCR provider, session or financial operation. */
@RunWith(AndroidJUnit4::class)
class ReceiptReviewSummaryAcceptanceTest {
    @get:Rule(order = 0) val languageRule = RussianUiLanguageRule()
    @get:Rule(order = 1) val rule = createComposeRule()
    private val account = Account("cash", "Synthetic cash", "EUR")
    private val original =
        ReceiptForm(
            "Synthetic market",
            "2026-10-09",
            "EUR",
            "12.50",
            "cash",
            "19.5",
            1,
            listOf(
                ReceiptLineForm(
                    name = "Discounted",
                    quantity = "2",
                    unitPrice = "6.00",
                    total = "11.50",
                )
            ),
        )

    @Test
    fun differenceIsVisibleAndOnlyExplicitTapChangesTotal() {
        val form = mutableStateOf(original)
        rule.setContent {
            val summary = receiptReviewSummary(form.value, listOf(account))
            FinoraTheme {
                ReceiptReviewSummaryCard(summary, "EUR", true) {
                    form.value =
                        form.value.copy(total = editableMoney(requireNotNull(summary.itemsMinor)))
                }
            }
        }
        rule.onNode(hasText("Разница со строками:", substring = true)).assertIsDisplayed()
        rule.runOnIdle { assertEquals("12.50", form.value.total) }
        rule.onNodeWithTag("receipt-use-line-total").performClick()
        rule.runOnIdle {
            assertEquals("11.50", form.value.total)
            assertEquals(original.items, form.value.items)
        }
        rule.onNode(hasText("Разница со строками:", substring = true)).assertDoesNotExist()
    }

    @Test
    fun incompleteReceiptShowsMissingFieldsAndBusyStateBlocksCorrection() {
        val incomplete =
            receiptReviewSummary(
                original.copy(merchant = "", date = "", total = ""),
                listOf(account),
            )
        rule.setContent {
            FinoraTheme {
                ReceiptReviewSummaryCard(incomplete, "EUR", false) { fail("Busy total changed") }
            }
        }
        rule.onNodeWithText(tr(Message.ENTER_THE_STORE)).assertIsDisplayed()
        rule.onNodeWithText(tr(Message.ENTER_THE_RECEIPT_TOTAL)).assertIsDisplayed()
        rule.onNodeWithTag("receipt-use-line-total").assertIsNotEnabled()
    }
}
