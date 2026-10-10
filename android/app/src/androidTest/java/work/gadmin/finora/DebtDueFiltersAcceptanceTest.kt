package work.gadmin.finora

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import work.gadmin.finora.ui.DebtDueFilters
import work.gadmin.finora.ui.FinoraTheme

/** Offline filter state only; no account or financial service is accessed. */
@RunWith(AndroidJUnit4::class)
class DebtDueFiltersAcceptanceTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun overdueSelectionDisablesClosedFilterAndPreservesItsPreviousChoice() {
        val overdue = mutableStateOf(false)
        val closed = mutableStateOf(true)
        rule.setContent {
            FinoraTheme {
                DebtDueFilters(
                    overdue.value,
                    closed.value,
                    { overdue.value = it },
                    { closed.value = it },
                )
            }
        }
        rule.onNodeWithTag("debts-closed-filter").assertIsOn().assertIsEnabled()
        rule.onNodeWithTag("debts-overdue-filter").performClick().assertIsSelected()
        rule.onNodeWithTag("debts-closed-filter").assertIsNotEnabled()
        rule.onNodeWithTag("debts-overdue-filter").performClick().assertIsNotSelected()
        rule.onNodeWithTag("debts-closed-filter").assertIsOn().assertIsEnabled()
        rule.runOnIdle { assertTrue(closed.value) }
    }
}
