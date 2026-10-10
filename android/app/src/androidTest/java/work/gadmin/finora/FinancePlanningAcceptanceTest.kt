package work.gadmin.finora

import androidx.compose.material3.Surface
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import work.gadmin.finora.data.ChatDestination
import work.gadmin.finora.ui.FinancePlanningLinks
import work.gadmin.finora.ui.FinoraTheme

/** Offline explicit navigation only; this fixture never creates a session or posts money. */
@RunWith(AndroidJUnit4::class)
class FinancePlanningAcceptanceTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun adminChoosesExactPlanningRouteWithAnExplicitTap() {
        var opened: ChatDestination? = null
        rule.setContent {
            FinoraTheme { Surface { FinancePlanningLinks(true, false) { opened = it } } }
        }
        rule.onNodeWithTag("planning-budgets").assertIsDisplayed()
        rule.onNodeWithTag("planning-bills").assertIsDisplayed()
        rule.runOnIdle { assertNull(opened) }
        rule.onNodeWithTag("planning-budgets").performClick()
        rule.runOnIdle { assertEquals(ChatDestination.BUDGETS, opened) }
        rule.onNodeWithTag("planning-bills").performClick()
        rule.runOnIdle { assertEquals(ChatDestination.BILLS, opened) }
    }

    @Test
    fun memberHasNoAdminPlanningActions() {
        rule.setContent {
            FinoraTheme {
                Surface { FinancePlanningLinks(false, false) { fail("Unexpected link") } }
            }
        }
        rule.onNodeWithTag("planning-budgets").assertDoesNotExist()
        rule.onNodeWithTag("planning-bills").assertDoesNotExist()
    }

    @Test
    fun writeInProgressDisablesPlanningNavigation() {
        rule.setContent {
            FinoraTheme { Surface { FinancePlanningLinks(true, true) { fail("Unexpected link") } } }
        }
        rule.onNodeWithTag("planning-budgets").assertIsNotEnabled()
        rule.onNodeWithTag("planning-bills").assertIsNotEnabled()
    }
}
