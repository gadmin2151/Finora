package work.gadmin.finora

import android.os.Build
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import work.gadmin.finora.data.*
import work.gadmin.finora.localization.Message
import work.gadmin.finora.localization.tr
import work.gadmin.finora.ui.FinanceEditor
import work.gadmin.finora.ui.FinoraTheme

/** Synthetic UI only: no login, network, database, or money operation. */
@RunWith(AndroidJUnit4::class)
class FinanceRetryAcceptanceTest {
    @get:Rule(order = 0) val languageRule = RussianUiLanguageRule()
    @get:Rule(order = 1) val rule = createComposeRule()

    private val form =
        FinanceEdit(
            FinanceEditKind.DEBT,
            name = "Synthetic retry debt",
            amount = "12.34",
            mode = "existing",
        )

    @Test
    fun uncertainResponseLocksDetailsButAllowsExplicitSameRequestRetry() {
        assumeTrue(Build.MODEL.startsWith("sdk_gphone"))
        var retries = 0
        var closed = false
        rule.setContent {
            FinoraTheme {
                FinanceEditor(
                    form,
                    FinanceState(editor = form, editorRetryLocked = true),
                    "Synthetic QA only",
                    false,
                    change = { fail("Locked financial details changed") },
                    save = { retries++ },
                    close = { closed = true },
                    findMatches = { fail("Unexpected matching request") },
                )
            }
        }
        rule.onNodeWithText("Имя человека").assertIsNotEnabled()
        rule.onNodeWithText("Сумма · MDL").assertIsNotEnabled()
        rule.onNodeWithText(tr(Message.RETRY_SAME_DETAILS)).assertIsEnabled().performClick()
        rule.runOnIdle { assertEquals(1, retries) }
        rule.onNodeWithContentDescription(tr(Message.CLOSE_FORM)).performClick()
        rule.onNodeWithText(tr(Message.FINANCE_UNCERTAIN_CLOSE)).assertIsDisplayed()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.takeScreenshot()?.let { image ->
            File(instrumentation.targetContext.filesDir, "finance-retry-warning.png")
                .outputStream()
                .use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            image.recycle()
        }
        rule.onNodeWithText(tr(Message.CLOSE), useUnmergedTree = true).performClick()
        rule.runOnIdle { assertTrue(closed) }
    }

    @Test
    fun retryInProgressDisablesAnotherSubmitAndClose() {
        rule.setContent {
            FinoraTheme {
                FinanceEditor(
                    form,
                    FinanceState(editor = form, editorRetryLocked = true),
                    "Synthetic QA only",
                    true,
                    change = { fail("Busy form changed") },
                    save = { fail("Duplicate submit") },
                    close = { fail("Busy form closed") },
                    findMatches = { fail("Unexpected matching request") },
                )
            }
        }
        rule.onNodeWithText(tr(Message.SAVING)).assertIsNotEnabled()
        rule.onNodeWithContentDescription(tr(Message.CLOSE_FORM)).assertIsNotEnabled()
    }
}
