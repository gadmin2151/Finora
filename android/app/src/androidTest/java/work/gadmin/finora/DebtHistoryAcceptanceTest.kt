package work.gadmin.finora

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.IOException
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import work.gadmin.finora.data.*
import work.gadmin.finora.ui.*

/** Synthetic, offline read-only history. No financial API or account is accessed. */
@RunWith(AndroidJUnit4::class)
class DebtHistoryAcceptanceTest {
    @get:Rule(order = 0) val languageRule = RussianUiLanguageRule()
    @get:Rule(order = 1) val rule = createComposeRule()

    @Test
    fun closedDebtHistoryIsAvailableWithoutFinancialWritePermission() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val controller =
            FinanceController(
                scope,
                { throw IOException("Offline test") },
                { Organization("org", "Synthetic", "member") },
                { "2026-10" },
                { false },
                { fail("History must not write") },
                { fail("Offline failure must remain in history") },
                { fail("History must not write") },
            )
        try {
            rule.setContent {
                FinoraTheme {
                    FinanceContent(
                        FinanceState(tab = FinanceTab.DEBTS, debts = listOf(debt())),
                        false,
                        false,
                        controller,
                        {},
                    )
                }
            }
            val list = rule.onNodeWithTag("finance-list")
            list.performScrollToNode(hasTestTag("debts-closed-filter"))
            rule.onNodeWithTag("debts-closed-filter").performClick()
            list.performScrollToNode(hasTestTag("debt-history-open-debt"))
            rule.onNodeWithTag("debt-history-open-debt").performClick()
            rule
                .onNodeWithText(
                    "Не удалось загрузить историю долга. Проверьте соединение и повторите."
                )
                .assertIsDisplayed()
            rule.onNodeWithText("Повторить").assertIsDisplayed()
            rule.onNodeWithText("Увеличить долг").assertDoesNotExist()
            rule.onNodeWithText("Закрыть").performClick()
            rule.onNodeWithTag("debt-history-list").assertDoesNotExist()
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun historyShowsMovementNotesReversalAndClosedBalanceWithPagination() {
        var more = 0
        val entries =
            listOf(
                entry("repayment", "repayment", 400, 0, "Final part"),
                entry("reversed", "repayment", 100, 400, "Canceled part", true),
                entry("increase", "increase", 200, 400, "Added loan"),
            )
        rule.setContent {
            FinoraTheme {
                DebtHistoryDialog(
                    DebtHistoryState(
                        debt = debt(),
                        page = DebtHistory("debt", "MDL", 500, 0, 200, 700, entries, 4),
                        nextOffset = 3,
                    ),
                    { more++ },
                    {},
                    {},
                )
            }
        }
        rule.onNodeWithText("Закрыт").assertIsDisplayed()
        rule.onNodeWithText("Всего учтено долга: 7,00 MDL").assertIsDisplayed()
        snapshot("debt-history-summary")
        val list = rule.onNodeWithTag("debt-history-list")
        list.performScrollToNode(hasText("Final part"))
        rule.onNodeWithText("Final part").assertIsDisplayed()
        list.performScrollToNode(hasText("Отменено · не влияет на остаток"))
        rule.onNodeWithText("Отменено · не влияет на остаток").assertIsDisplayed()
        snapshot("debt-history-reversal")
        list.performScrollToNode(hasText("Увеличение долга"))
        rule.onNodeWithText("Увеличение долга").assertIsDisplayed()
        list.performScrollToNode(hasTestTag("debt-history-more"))
        rule.onNodeWithTag("debt-history-more").performClick()
        rule.runOnIdle { assertEquals(1, more) }
    }

    private fun debt() = Debt("debt", "Synthetic person", "lent", "MDL", 0, 500)

    private fun snapshot(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val scale = context.resources.configuration.fontScale
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(context.filesDir, "$name-$scale.png").outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
    }

    private fun entry(
        id: String,
        operation: String,
        amount: Long,
        balance: Long,
        note: String,
        voided: Boolean = false,
    ) =
        DebtHistoryEntry(
            id,
            "debt_repayment_in",
            operation,
            "2026-10-12",
            "2026-10-12T10:00:00Z",
            amount,
            balance,
            "account",
            "Cash",
            note,
            voided,
        )
}
