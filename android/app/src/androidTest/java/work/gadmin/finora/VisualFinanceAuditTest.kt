package work.gadmin.finora

import android.graphics.Bitmap
import android.os.Build
import android.view.KeyEvent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import work.gadmin.finora.accounts.AccountManagementState
import work.gadmin.finora.data.*
import work.gadmin.finora.localization.*
import work.gadmin.finora.ui.*

/**
 * Pixel QA only on a brand-new disposable emulator. No API client, save, payment or push action.
 */
class VisualFinanceAuditTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Suppress("UNCHECKED_CAST")
    private fun <T> state(owner: Any): MutableStateFlow<T> =
        owner.javaClass.getDeclaredField("mutable").let {
            it.isAccessible = true
            it.get(owner) as MutableStateFlow<T>
        }

    @Test
    fun captureOfflineFinanceMatrix() {
        assumeTrue(Build.MODEL.startsWith("sdk_gphone"))
        val context = rule.activity
        assumeTrue(!File(context.noBackupFilesDir, "session.enc").exists())
        val vm = ViewModelProvider(context)[FinoraViewModel::class.java]
        rule.waitUntil(10000) { !vm.state.value.starting }
        val app = state<AppState>(vm)
        val finance = state<FinanceState>(vm.finance)
        val payments = state<PaymentState>(vm.payments)
        val budgets = state<BudgetState>(vm.budgets)
        val ledger = state<LedgerState>(vm.ledger)
        val managedAccounts = state<AccountManagementState>(vm.accountManagement)
        val analytics = state<NativeAnalyticsState>(vm.nativeAnalytics)
        val organization = Organization("visual-org", "Тестовая организация · длинное имя", "admin")
        val account = Account("visual-account", "Тестовый основной счёт", "MDL", 12345678)
        val category = Category("visual-food", "Продукты и бытовые покупки", "#18a999", 234567)
        val base =
            AppState(
                starting = false,
                server = "https://example.invalid",
                user =
                    User("visual-user", "visual", "Визуальная проверка", "", listOf(organization)),
                organization = organization,
                accounts = listOf(account),
                categories = listOf(category),
                month = "2026-10",
                dashboard =
                    Dashboard(
                        "2026-10",
                        12345678,
                        234567,
                        12111111,
                        categories = listOf(category),
                        accounts = listOf(account),
                    ),
            )
        val income =
            FinanceState(
                month = "2026-10",
                accounts = listOf(account),
                report = IncomeReport(12345678, 12345678, 0, 1000000),
            )
        val debt =
            Debt(
                "visual-debt",
                "Тестовый человек с длинным именем",
                "lent",
                "MDL",
                1234567,
                2000000,
                "2026-10-31",
                "Синтетический долг для проверки отображения",
            )
        val output = File(context.filesDir, "visual-audit").apply { mkdirs() }
        val prefix = InstrumentationRegistry.getArguments().getString("capturePrefix") ?: "normal"
        fun capture(name: String) {
            rule.waitForIdle()
            rule.mainClock.advanceTimeBy(800)
            rule.waitForIdle()
            android.os.SystemClock.sleep(350) // Let native dialog/window transitions settle.
            val image = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            File(output, "$prefix-$name.png").outputStream().use {
                image.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            image.recycle()
        }
        rule.runOnIdle { LanguagePreferences(context).save(LanguagePreference.RUSSIAN) }
        for (theme in listOf(ThemeMode.LIGHT, ThemeMode.DARK, ThemeMode.BLACK)) {
            val tag = theme.name.lowercase()
            rule.runOnIdle {
                ThemePreferences(context).save(theme)
                app.value = base.copy(page = Page.OVERVIEW)
                finance.value = income
                payments.value =
                    PaymentState(
                        month = "2026-10",
                        accounts = listOf(account),
                        categories = listOf(category),
                        occurrences =
                            listOf(
                                PaymentOccurrence(
                                    id = "visual-payment",
                                    bill_id = "visual-bill",
                                    name = "Тестовый регулярный платёж с длинным названием",
                                    amount_minor = 1234567,
                                    currency = "MDL",
                                    account_id = account.id,
                                    due_date = "2026-10-20",
                                    recurrence = "monthly",
                                    active = true,
                                    status = "pending",
                                )
                            ),
                    )
                budgets.value =
                    BudgetState(
                        month = "2026-10",
                        categories =
                            listOf(
                                BudgetCategory(
                                    category.id,
                                    category.name,
                                    234567,
                                    500000,
                                    "visual-budget",
                                )
                            ),
                    )
                ledger.value =
                    LedgerState(
                        organizationId = organization.id,
                        organizationName = organization.name,
                        month = "2026-10",
                        accounts = listOf(account),
                        categories = listOf(category),
                    )
            }
            capture("$tag-overview")
            rule.onNodeWithText(account.name, substring = true).performScrollTo()
            capture("$tag-overview-account")
            rule.runOnIdle {
                managedAccounts.value = AccountManagementState(loaded = true)
                app.value = base.copy(page = Page.ACCOUNTS)
            }
            capture("$tag-accounts-empty")
            for (mode in NativeAnalyticsMode.entries) {
                rule.runOnIdle {
                    analytics.value = NativeAnalyticsState(mode = mode)
                    app.value = base.copy(page = Page.ANALYTICS)
                }
                capture("$tag-analytics-${mode.name.lowercase()}-empty")
            }
            rule.runOnIdle { app.value = base.copy(page = Page.CHAT) }
            capture("$tag-chat-empty")
            for (tab in FinanceTab.entries) {
                rule.runOnIdle {
                    finance.value = income.copy(tab = tab, debts = listOf(debt))
                    app.value = base.copy(page = Page.FINANCES)
                }
                capture("$tag-${tab.name.lowercase()}")
            }
            rule.runOnIdle {
                finance.value = income.copy(loading = true)
                app.value = base.copy(page = Page.FINANCES)
            }
            capture("$tag-loader")
            rule.runOnIdle {
                finance.value =
                    income.copy(
                        error =
                            "Не удалось загрузить данные. Проверьте соединение и повторите попытку."
                    )
            }
            capture("$tag-error")
            rule.runOnIdle {
                finance.value =
                    income.copy(
                        editor =
                            FinanceEdit(
                                FinanceEditKind.INCOME,
                                name = "Синтетический доход",
                                accountId = account.id,
                                date = "2026-10-10",
                            )
                    )
            }
            capture("$tag-income-form")
            rule.onNodeWithText("Сумма · MDL").performClick().performTextInput("12345,67")
            capture("$tag-income-keyboard")
            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            rule.waitForIdle()
            capture("$tag-income-keyboard-back")
            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            rule.waitForIdle()
            capture("$tag-income-form-back")
            rule.runOnIdle { finance.value = income }
            rule.runOnIdle {
                app.value = base.copy(page = Page.FINANCES)
                finance.value = income.copy(tab = FinanceTab.TRANSACTIONS)
                ledger.value =
                    ledger.value.copy(
                        organizationName = "Тестовая организация с длинным названием ".repeat(12),
                        accounts =
                            listOf(
                                account.copy(
                                    name = "Тестовый основной счёт с длинным названием ".repeat(8)
                                )
                            ),
                        editor =
                            LedgerEdit(
                                accountId = account.id,
                                amount = "12345,67",
                                date = "2026-10-10",
                                merchant = "Длинное тестовое название магазина ".repeat(6),
                            ),
                        confirmation = FinanceCommand("/api/transactions", "POST"),
                    )
            }
            capture("$tag-confirmation-long")
            rule.onNodeWithTag("confirmation-details").performSemanticsAction(
                SemanticsActions.ScrollBy
            ) {
                it(0f, 100000f)
            }
            capture("$tag-confirmation-long-scrolled")
            // Dismiss only: never invoke a financial action from this pixel fixture.
            rule.onNodeWithText("Назад", useUnmergedTree = true).performClick()
            rule.runOnIdle { ledger.value = LedgerState() }
        }
        rule.runOnIdle { app.value = AppState(starting = false) }
        InstrumentationRegistry.getInstrumentation().runOnMainSync { context.finish() }
    }
}
