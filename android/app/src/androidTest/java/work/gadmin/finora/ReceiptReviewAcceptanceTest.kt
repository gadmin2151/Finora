package work.gadmin.finora

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.time.LocalDate
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import work.gadmin.finora.data.*
import work.gadmin.finora.ui.*

/** Offline partial-draft review. No login, upload or financial posting. */
@RunWith(AndroidJUnit4::class)
class ReceiptReviewAcceptanceTest {
    @get:Rule(order = 0) val languageRule = RussianUiLanguageRule()

    @get:Rule(order = 1) val rule = createComposeRule()

    @Test
    fun missingDatePreservesTotalAndTodayIsAnExplicitEditableChoice() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue(!File(context.noBackupFilesDir, "session.enc").exists())
        val store = ViewModelStore()
        val vm = FinoraViewModel(context.applicationContext as Application)
        store.put("receipt-review-test", vm)
        val receipt =
            Receipt(
                id = "test-draft",
                source = "photo",
                status = "review",
                version = 2,
                created_at = "2025-09-25T00:00:00Z",
                merchant = "TEST MARKET",
                total_minor = 23508,
                items = listOf(ReceiptItem("item", "TEST ITEM", "1", "шт", 23508, 23508)),
            )
        var editing by mutableStateOf(false)
        var busy by mutableStateOf(false)
        try {
            rule.setContent {
                val state =
                    AppState(
                        starting = false,
                        detail = receipt,
                        busy = busy,
                        organization = Organization("test", "Test organization", "admin"),
                        accounts = listOf(Account("cash", "Наличные", "MDL")),
                    )
                FinoraTheme {
                    if (editing) ReceiptEditor(state, vm) else ReceiptReview(state, receipt, vm)
                }
            }
            rule.onNodeWithText("Укажите дату покупки.", substring = true).assertExists()
            rule.onNodeWithText("Укажите итог чека.", substring = true).assertDoesNotExist()
            rule.onNodeWithText("Всё верно · подтвердить").assertIsNotEnabled()
            rule.runOnIdle { editing = true }
            val date = rule.onNodeWithText("Дата · ГГГГ-ММ-ДД")
            date.assert(
                SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(""))
            )
            rule.onNodeWithText("Сегодня").performScrollTo().performClick()
            date.assertTextContains(LocalDate.now().toString())
            date.performTextReplacement("2025-09-25")
            rule.runOnIdle { busy = true }
            rule.onNodeWithText("Сегодня").assertIsNotEnabled()
            date.assertTextContains("2025-09-25")
            rule.runOnIdle { busy = false }
            rule.onNodeWithText("Сегодня").performClick()
            date.assertTextContains(LocalDate.now().toString())
            rule
                .onNode(hasSetTextAction() and hasText("Итог чека · MDL"))
                .performScrollTo()
                .assertTextContains("235.08")
            InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let { image
                ->
                File(context.filesDir, "receipt-today.png").outputStream().use {
                    image.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                image.recycle()
            }
        } finally {
            rule.runOnIdle { store.clear() }
        }
    }
}
