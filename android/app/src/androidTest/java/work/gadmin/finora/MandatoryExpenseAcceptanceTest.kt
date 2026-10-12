package work.gadmin.finora

import android.app.Application
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import work.gadmin.finora.data.*
import work.gadmin.finora.ui.*

/** Offline synthetic mandatory receipt display; never creates a real expense. */
@RunWith(AndroidJUnit4::class)
class MandatoryExpenseAcceptanceTest {
    @get:Rule(order = 0) val languageRule = RussianUiLanguageRule()
    @get:Rule(order = 1) val rule = createComposeRule()
    private val marker = MandatoryPayment("bill", "occurrence", "2026-10-01")

    @Test
    fun mandatoryReceiptIsLabeledInListAndReadOnlyPostedDetail() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue(!File(context.noBackupFilesDir, "session.enc").exists())
        val store = ViewModelStore()
        val vm = FinoraViewModel(context.applicationContext as Application)
        store.put("mandatory-receipt", vm)
        val receipt =
            Receipt(
                "receipt",
                "manual",
                merchant = "Аренда",
                purchased_on = "2026-10-12",
                total_minor = 12345,
                status = "posted",
                version = 1,
                transaction_id = "same-expense",
                created_at = "2026-10-12T10:00:00Z",
                mandatory_payment = marker,
                items = listOf(ReceiptItem("item", "Аренда", "1", "pcs", 12345, 12345, "category")),
            )
        val detail = mutableStateOf(false)
        val state =
            AppState(
                starting = false,
                organization = Organization("org", "Synthetic", "admin"),
                receipts = listOf(receipt),
                receiptCount = 1,
                detail = receipt,
                detailId = receipt.id,
                categories = listOf(Category("category", "Жильё")),
            )
        try {
            rule.setContent {
                FinoraTheme {
                    if (detail.value) ReceiptDetailScreen(state, vm) else ReceiptsScreen(state, vm)
                }
            }
            rule
                .onNode(hasScrollToIndexAction())
                .performScrollToNode(hasText("Обязательный расход"))
            rule.onNodeWithText("Обязательный расход").assertIsDisplayed()
            rule.runOnIdle { detail.value = true }
            rule.onNodeWithText("Обязательный расход").assertIsDisplayed()
            rule.onAllNodesWithText("123,45 MDL")[0].assertIsDisplayed()
            rule.onNodeWithText("Подтвердить чек").assertDoesNotExist()
            snapshot("mandatory-receipt")
        } finally {
            store.clear()
        }
    }

    @Test
    fun mandatoryPurchaseKeepsReceiptNavigationAndAmount() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val navigation = NavigationScope("org", "2026-10", "test")
        val reader =
            object : NativeAnalyticsReader {
                override suspend fun report(
                    scope: NavigationScope,
                    kind: NativeReportKind,
                ): AnalyticsReport = error("No network in fixture")

                override suspend fun purchases(scope: NavigationScope, offset: Int): PurchasePage =
                    error("No network in fixture")
            }
        val controller =
            NativeAnalyticsController(scope, reader, { navigation }, { throw AssertionError(it) })
        var opened: String? = null
        val item =
            PurchaseItem(
                "item",
                "receipt",
                "Аренда",
                "1",
                "pcs",
                12345,
                "Аренда",
                "MDL",
                mandatory_payment = marker,
            )
        try {
            rule.setContent {
                FinoraTheme {
                    NativeAnalyticsScreen(
                        NativeAnalyticsState(
                            scope = navigation,
                            mode = NativeAnalyticsMode.PURCHASES,
                            purchases = PurchasePage(listOf(item), 1),
                        ),
                        controller,
                        { opened = it },
                        {},
                        false,
                    )
                }
            }
            rule
                .onNodeWithTag("native-analytics")
                .performScrollToNode(hasText("Обязательный расход"))
            rule.onNodeWithText("Обязательный расход").assertIsDisplayed()
            rule.onNodeWithText("Открыть чек").performScrollTo().performClick()
            rule.runOnIdle { assertEquals("receipt", opened) }
        } finally {
            scope.cancel()
        }
    }

    private fun snapshot(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(context.filesDir, "$name-${context.resources.configuration.fontScale}.png")
                .outputStream()
                .use {
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
            bitmap.recycle()
        }
    }
}
