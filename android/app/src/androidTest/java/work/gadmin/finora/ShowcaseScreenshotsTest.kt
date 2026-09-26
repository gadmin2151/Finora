package work.gadmin.finora

import android.graphics.Bitmap
import android.os.Build
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import work.gadmin.finora.data.*
import work.gadmin.finora.localization.*
import work.gadmin.finora.ui.*

/** Reproducible bilingual documentation captures. Fictional data on an empty emulator only. */
@RunWith(AndroidJUnit4::class)
class ShowcaseScreenshotsTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun captureDocumentationScreens() {
        assumeTrue(Build.MODEL.startsWith("sdk_gphone"))
        val context = rule.activity
        assumeTrue(!File(context.noBackupFilesDir, "session.enc").exists())
        val vm = ViewModelProvider(rule.activity)[FinoraViewModel::class.java]
        rule.waitUntil(10000) { !vm.state.value.starting }
        @Suppress("UNCHECKED_CAST")
        val state =
            FinoraViewModel::class.java.getDeclaredField("mutable").let {
                it.isAccessible = true
                it.get(vm) as MutableStateFlow<AppState>
            }
        @Suppress("UNCHECKED_CAST")
        val finance =
            FinanceController::class.java.getDeclaredField("mutable").let {
                it.isAccessible = true
                it.get(vm.finance) as MutableStateFlow<FinanceState>
            }
        val previousLanguage = LanguagePreferences(context).read()
        val previousTheme = ThemePreferences(context).read()
        try {
            for (language in AppLanguage.entries) {
                val english = language == AppLanguage.ENGLISH
                fun copy(en: String, ru: String) = if (english) en else ru
                rule.runOnIdle {
                    LanguagePreferences(context)
                        .save(
                            if (english) LanguagePreference.ENGLISH else LanguagePreference.RUSSIAN
                        )
                }
                val org = Organization("showcase", copy("Family · demo", "Семья · демо"), "admin")
                val account = Account("cash", copy("Main account", "Основной счёт"), "MDL", 2485600)
                val categories =
                    listOf(
                        Category("home", copy("Home", "Дом"), "#7376d7", 818000),
                        Category("food", copy("Groceries", "Продукты"), "#18a999", 249000),
                        Category("car", copy("Car", "Автомобиль"), "#4a93cf", 85000),
                        Category("cafe", copy("Eating out", "Рестораны и кафе"), "#ed9753", 42000),
                        Category(
                            "milk",
                            copy("Dairy and eggs", "Молочные продукты и яйца"),
                            "#769dc6",
                        ),
                        Category("bread", copy("Bread and baking", "Хлеб и выпечка"), "#bb8b48"),
                        Category(
                            "fruit",
                            copy("Fruit and vegetables", "Овощи и фрукты"),
                            "#669c45",
                        ),
                    )
                val receipt =
                    Receipt(
                        id = "demo-receipt",
                        source = "photo",
                        review_required = true,
                        merchant = copy("Fresh · demo", "Fresh · демо"),
                        purchased_on = "2026-09-26",
                        total_minor = 8800,
                        status = "review",
                        account_id = account.id,
                        version = 1,
                        created_at = "2026-09-26T09:00:00Z",
                        items =
                            listOf(
                                ReceiptItem(
                                    "milk",
                                    "LAPTE 2.5% 1L",
                                    "1",
                                    copy("pcs", "шт"),
                                    2500,
                                    2500,
                                    "milk",
                                ),
                                ReceiptItem(
                                    "bread",
                                    "PAINE INTEGRALA",
                                    "1",
                                    copy("pcs", "шт"),
                                    1800,
                                    1800,
                                    "bread",
                                ),
                                ReceiptItem(
                                    "fruit",
                                    "MERE GOLDEN",
                                    "1",
                                    copy("kg", "кг"),
                                    4500,
                                    4500,
                                    "fruit",
                                ),
                            ),
                    )
                val base =
                    AppState(
                        starting = false,
                        user =
                            User(
                                "demo",
                                "demo",
                                copy("Alex Demo", "Алексей Демо"),
                                "",
                                listOf(org),
                            ),
                        server = "https://finance.example.com",
                        organization = org,
                        accounts = listOf(account),
                        categories = categories,
                        month = "2026-09",
                        dashboard =
                            Dashboard(
                                "2026-09",
                                3250000,
                                1194000,
                                2056000,
                                categories = categories,
                                accounts = listOf(account),
                            ),
                        receipts = listOf(receipt),
                        receiptCount = 1,
                    )
                fun capture(name: String, data: AppState, theme: ThemeMode, text: String) {
                    rule.runOnIdle {
                        ThemePreferences(context).save(theme)
                        state.value = data
                    }
                    rule.waitForIdle()
                    rule.onNodeWithText(text).assertIsDisplayed()
                    // Wait for the real navigation transition to finish before the frame capture.
                    rule.mainClock.advanceTimeBy(700)
                    rule.waitForIdle()
                    val bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
                    File(context.filesDir, "showcase-${language.tag}-$name.png")
                        .outputStream()
                        .use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                }
                capture(
                    "login",
                    AppState(starting = false),
                    ThemeMode.LIGHT,
                    copy("Your money.\nMade clear.", "Ваши деньги.\nВсё понятно."),
                )
                capture(
                    "overview",
                    base.copy(page = Page.OVERVIEW),
                    ThemeMode.LIGHT,
                    copy("Clarity in numbers", "Ясность в цифрах"),
                )
                capture(
                    "capture",
                    base.copy(page = Page.CAPTURE),
                    ThemeMode.DARK,
                    copy("Scan QR code", "Сканировать QR"),
                )
                capture(
                    "receipt",
                    base.copy(page = Page.RECEIPTS, detailId = receipt.id, detail = receipt),
                    ThemeMode.LIGHT,
                    copy("Fresh · demo", "Fresh · демо"),
                )
                rule.runOnIdle {
                    finance.value =
                        FinanceState(
                            month = "2026-09",
                            tab = FinanceTab.DEBTS,
                            accounts = listOf(account),
                            debts =
                                listOf(
                                    Debt(
                                        "a",
                                        copy("Maria · demo", "Мария · демо"),
                                        "lent",
                                        "MDL",
                                        150000,
                                        200000,
                                        "2026-10-01",
                                        copy("Shared trip", "Совместная поездка"),
                                    ),
                                    Debt(
                                        "b",
                                        copy("Alex · demo", "Алексей · демо"),
                                        "borrowed",
                                        "MDL",
                                        60000,
                                        100000,
                                        "2026-10-10",
                                        copy("Repay by October", "Вернуть до октября"),
                                    ),
                                ),
                        )
                }
                capture(
                    "debts",
                    base.copy(page = Page.FINANCES),
                    ThemeMode.DARK,
                    copy("Maria · demo", "Мария · демо"),
                )
                capture(
                    "profile",
                    base.copy(page = Page.PROFILE),
                    ThemeMode.LIGHT,
                    copy("Language", "Язык"),
                )
            }
        } finally {
            rule.runOnIdle {
                LanguagePreferences(context).save(previousLanguage)
                ThemePreferences(context).save(previousTheme)
                state.value = AppState(starting = false)
            }
        }
    }
}
