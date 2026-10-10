package work.gadmin.finora

import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import work.gadmin.finora.accounts.*
import work.gadmin.finora.data.*
import work.gadmin.finora.localization.AppLanguage
import work.gadmin.finora.localization.LanguageRuntime
import work.gadmin.finora.ui.*

/** Offline fictional models and local callbacks only: no account, browser, network or ledger. */
@RunWith(AndroidJUnit4::class)
class NativePlanningAcceptanceTest {
    @get:Rule(order = 0) val languageRule = RussianUiLanguageRule()
    @get:Rule(order = 1) val rule = createComposeRule()
    private val category =
        BudgetCategory("fictional-food", "Synthetic food", 1234, 5000, "synthetic-limit")
    private val budget = BudgetState(month = "2026-10", categories = listOf(category))

    @Test
    fun budgetAdministratorEditsExactCategoryAndOpensNativeCopy() {
        var edited: BudgetCategory? = null
        var copies = 0
        rule.setContent {
            FinoraTheme {
                Surface {
                    BudgetContent(
                        budget,
                        true,
                        false,
                        { edited = it },
                        { fail("Unexpected refresh") },
                        { copies++ },
                        {},
                    )
                }
            }
        }
        rule.onNodeWithTag("budget-edit-fictional-food").performScrollTo().performClick()
        rule.runOnIdle { assertEquals(category, edited) }
        rule.onNodeWithTag("budget-copy").performScrollTo().performClick()
        rule.runOnIdle { assertEquals(1, copies) }
    }

    @Test
    fun memberCanReadAmountsButCannotMutateBudget() {
        rule.setContent {
            FinoraTheme {
                Surface {
                    BudgetContent(
                        budget,
                        false,
                        false,
                        { fail("Member edited") },
                        {},
                        { fail("Member copied") },
                        {},
                    )
                }
            }
        }
        rule.onNodeWithText("Synthetic food").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Лимит: 50.00 MDL").assertExists()
        rule.onNodeWithTag("budget-copy").assertDoesNotExist()
        rule.onNodeWithTag("budget-edit-fictional-food").assertDoesNotExist()
    }

    @Test
    fun busyBudgetDisablesDuplicateSaveAndClose() {
        rule.setContent {
            FinoraTheme {
                BudgetEditor(
                    budget.copy(editor = BudgetEdit("2026-10", category, "50")),
                    true,
                    { fail("Busy changed") },
                    { fail("Duplicate save") },
                    { fail("Busy delete") },
                    { fail("Busy close") },
                )
            }
        }
        rule.onNodeWithTag("budget-amount").assertIsNotEnabled()
        rule.onNodeWithTag("budget-save").assertIsNotEnabled()
        rule.onNodeWithText("Отмена").assertIsNotEnabled()
    }

    @Test
    fun cancelRequiresDiscardConfirmationAndDoesNotSave() {
        var closed = false
        rule.setContent {
            FinoraTheme {
                BudgetEditor(
                    budget.copy(editor = BudgetEdit("2026-10", category, "60")),
                    false,
                    {},
                    { fail("Cancel saved") },
                    { fail("Cancel deleted") },
                    { closed = true },
                )
            }
        }
        rule.onNodeWithText("Отмена").performClick()
        rule.onNodeWithText("Закрыть без сохранения?").assertIsDisplayed()
        rule.runOnIdle { assertFalse(closed) }
        rule.onNodeWithText("Продолжить").performClick()
        rule.onNodeWithTag("budget-editor").assertExists()
        rule.onNodeWithText("Отмена").performClick()
        rule.onNodeWithText("Закрыть", useUnmergedTree = true).performClick()
        rule.runOnIdle { assertTrue(closed) }
    }

    @Test
    fun uncertainBudgetLocksAmountAndOffersSameRequestRetry() {
        var retries = 0
        rule.setContent {
            FinoraTheme {
                BudgetEditor(
                    budget.copy(editor = BudgetEdit("2026-10", category, "50"), retryLocked = true),
                    false,
                    { fail("Uncertain details changed") },
                    { retries++ },
                    { fail("Uncertain deleted") },
                    {},
                )
            }
        }
        rule.onNodeWithTag("budget-amount").assertIsNotEnabled()
        rule.onNodeWithTag("budget-delete").assertIsNotEnabled()
        rule.onNodeWithText("Повторить тот же лимит").performClick()
        rule.runOnIdle { assertEquals(1, retries) }
    }

    @Test
    fun backOpensDiscardReviewInsteadOfSavingOrClosing() {
        var closed = false
        rule.setContent {
            FinoraTheme {
                BudgetEditor(
                    budget.copy(editor = BudgetEdit("2026-10", category, "60")),
                    false,
                    {},
                    { fail("Back saved") },
                    { fail("Back deleted") },
                    { closed = true },
                )
            }
        }
        rule.waitForIdle()
        InstrumentationRegistry.getInstrumentation()
            .sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        rule.onNodeWithText("Закрыть без сохранения?").assertIsDisplayed()
        rule.runOnIdle { assertFalse(closed) }
    }

    @Test
    fun copyPreviewIsEnglishAndCannotApplyWhileBusy() {
        val busy = mutableStateOf(true)
        var applied = 0
        val preview =
            BudgetCopyPreview(
                "2026-10",
                "2026-09",
                "a".repeat(64),
                listOf(BudgetCopyItem(category.id, category.name, 5000)),
                5000,
                2,
            )
        rule.runOnIdle { LanguageRuntime.language = AppLanguage.ENGLISH }
        rule.setContent {
            FinoraTheme {
                BudgetCopyDialog(
                    budget.copy(copyOpen = true, preview = preview),
                    busy.value,
                    {
                        applied++
                        busy.value = true
                    },
                    { fail("Preview refreshed") },
                    { fail("Busy cancelled") },
                )
            }
        }
        rule.onNodeWithText("Review copied limits").assertIsDisplayed()
        rule.onNodeWithText("Existing limits are preserved: 2").assertExists()
        rule.onNodeWithTag("budget-copy-apply").assertIsNotEnabled()
        rule.onNodeWithText("Cancel").assertIsNotEnabled()
        rule.runOnIdle { busy.value = false }
        rule.onNodeWithTag("budget-copy-apply").performClick().assertIsNotEnabled()
        rule.runOnIdle { assertEquals(1, applied) }
    }

    private val occurrence =
        PaymentOccurrence(
            "fictional-occ",
            "fictional-plan",
            "Synthetic internet",
            1234,
            "MDL",
            due_date = "2026-10-10",
            recurrence = "monthly",
            active = true,
            status = "upcoming",
        )
    private val account = Account("fictional-account", "Synthetic wallet", "MDL")
    private val payment =
        PaymentState(
            month = "2026-10",
            occurrences = listOf(occurrence),
            accounts = listOf(account),
        )

    private fun paymentActions(admin: Boolean = true, busy: Boolean = false) =
        PaymentController(
            CoroutineScope(Dispatchers.Main),
            { error("Network forbidden in native UI fixture") },
            {
                Organization(
                    "fictional-family",
                    "Synthetic family",
                    if (admin) "admin" else "member",
                )
            },
            { "2026-10" },
            { busy },
            { fail("Fixture must not write money") },
            { throw it },
            { fail("Fixture unexpectedly saved") },
        )

    @Test
    fun paymentsMemberSeesPlanButNoMutatingControls() {
        val actions = paymentActions(admin = false)
        rule.setContent { FinoraTheme { PaymentScreen(payment, false, false, actions, {}) } }
        rule.onNodeWithText("Synthetic internet").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("payment-create").assertDoesNotExist()
        rule.onNodeWithText("Оплатить").assertDoesNotExist()
        rule.onNodeWithText("Пропустить").assertDoesNotExist()
    }

    @Test
    fun busyPaymentBlocksAnotherReviewOrCancel() {
        val form =
            PaymentEdit(
                occurrenceId = occurrence.id,
                name = occurrence.name,
                amount = "12.34",
                accountId = account.id,
            )
        rule.setContent {
            FinoraTheme {
                PaymentScreen(
                    payment.copy(editor = form),
                    true,
                    true,
                    paymentActions(busy = true),
                    {},
                )
            }
        }
        rule.onNodeWithTag("payment-review").assertIsNotEnabled()
        rule.onNodeWithTag("payment-cancel").assertIsNotEnabled()
    }

    @Test
    fun uncertainTemplateCannotBeSubmittedAgain() {
        val form =
            PaymentEdit(name = "Synthetic template", amount = "12.34", accountId = account.id)
        rule.setContent {
            FinoraTheme {
                PaymentScreen(
                    payment.copy(editor = form, retryLocked = true),
                    true,
                    false,
                    paymentActions(),
                    {},
                )
            }
        }
        rule.onNodeWithTag("payment-review").assertIsNotEnabled()
        rule.onNodeWithTag("payment-cancel").assertIsEnabled()
        rule.onNodeWithTag("payment-confirm").assertDoesNotExist()
    }

    @Test
    fun englishPaymentReviewStatesFinancialEffectBeforeConfirmation() {
        val form =
            PaymentEdit(
                occurrenceId = occurrence.id,
                name = occurrence.name,
                amount = "12.34",
                accountId = account.id,
            )
        rule.runOnIdle { LanguageRuntime.language = AppLanguage.ENGLISH }
        rule.setContent {
            FinoraTheme {
                PaymentScreen(payment.copy(editor = form), true, false, paymentActions(), {})
            }
        }
        rule.onNodeWithTag("payment-review").performClick()
        rule.onNodeWithText("Confirm action").assertIsDisplayed()
        rule
            .onNodeWithText(
                "A new expense will be recorded and account balance will change.",
                substring = true,
            )
            .assertIsDisplayed()
        rule.onNodeWithText("Back").performClick()
        rule.onNodeWithTag("payment-confirm").assertDoesNotExist()
        rule.onNodeWithTag("payment-editor").assertExists()
    }

    private fun accountScope(admin: Boolean = true) =
        AccountManagementScope(
            "fictional-family",
            admin,
            false,
            account.id,
            "fictional-owner",
            "https://synthetic.invalid",
        )

    private class MemoryAccountMutationGuardStore : AccountMutationGuardStore {
        private val entries = mutableMapOf<AccountManagementScope, AccountMutationGuard>()

        override fun read(scope: AccountManagementScope) = entries[scope]

        override fun put(scope: AccountManagementScope, guard: AccountMutationGuard) {
            check(entries[scope] == null)
            entries[scope] = guard
        }

        override fun clear(scope: AccountManagementScope, requestId: String) {
            val guard = entries[scope] ?: return
            check(guard.requestId == requestId)
            entries.remove(scope)
        }
    }

    private fun accountActions(current: AccountManagementScope, busy: Boolean = false) =
        AccountManagementController(
            CoroutineScope(Dispatchers.Main),
            object : AccountManagementApi {
                override suspend fun list(organizationId: String) = listOf(lifecycle)

                override suspend fun write(organizationId: String, command: FinanceCommand) {
                    fail("Fixture must not write accounts")
                }
            },
            { current },
            { busy },
            { fail("Fixture must not dispatch writes") },
            { throw it },
            { fail("Fixture unexpectedly saved") },
            MemoryAccountMutationGuardStore(),
        )

    private val lifecycle =
        ManagedAccount(account.id, account.name, "MDL", "cash", "#16a69b", 1000, 2234, false)

    @Test
    fun accountMemberReadsBalanceAndCannotCreateEditOrArchive() {
        val scope = accountScope(admin = false)
        val actions = accountActions(scope)
        rule.setContent {
            FinoraTheme {
                AccountManagementScreen(
                    AccountManagementState(
                        scope = scope,
                        accounts = listOf(lifecycle),
                        loaded = true,
                    ),
                    false,
                    actions,
                )
            }
        }
        rule.onNodeWithText("Synthetic wallet").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Новый счёт").performScrollTo().assertIsNotEnabled()
        rule.onNodeWithText("Изменить").assertDoesNotExist()
        rule.onNodeWithText("В архив").assertDoesNotExist()
    }

    @Test
    fun accountBusyEditorBlocksReviewAndCancel() {
        val scope = accountScope()
        val actions = accountActions(scope)
        val form = ManagedAccountForm(name = "Synthetic account", openingBalance = "10.00")
        rule.setContent {
            FinoraTheme {
                AccountManagementScreen(
                    AccountManagementState(
                        scope = scope,
                        loaded = true,
                        editor = form,
                        writing = true,
                    ),
                    false,
                    actions,
                )
            }
        }
        rule.onNodeWithText("Проверить").assertIsNotEnabled()
        rule.onNodeWithText("Отмена").assertIsNotEnabled()
        rule.onNodeWithText("Название").performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun uncertainAccountRequiresCheckingListBeforeAnotherCreate() {
        val scope = accountScope()
        val actions = accountActions(scope)
        rule.setContent {
            FinoraTheme {
                AccountManagementScreen(
                    AccountManagementState(
                        scope = scope,
                        loaded = true,
                        accounts = listOf(lifecycle),
                        uncertain = true,
                    ),
                    false,
                    actions,
                )
            }
        }
        rule.onNodeWithText("Новый счёт").assertIsNotEnabled()
        rule.onNodeWithText("Проверил результат в списке").assertIsNotEnabled()
    }

    @Test
    fun accountBackClosesNativeDraftWithoutWriting() {
        val scope = accountScope()
        val actions = accountActions(scope)
        rule.setContent {
            FinoraTheme {
                AccountManagementScreen(actions.state.collectAsState().value, false, actions)
            }
        }
        rule.runOnIdle { actions.load() }
        rule.waitUntil(5000) { actions.state.value.loaded }
        rule.onNodeWithText("Новый счёт").performClick()
        rule.onNodeWithText("Проверить").assertExists()
        InstrumentationRegistry.getInstrumentation()
            .sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        rule.runOnIdle { assertNull(actions.state.value.editor) }
        rule.onNodeWithText("Проверить").assertDoesNotExist()
    }

    private class MemoryLedgerPendingStore : LedgerPendingStore {
        private val entries = mutableMapOf<Pair<String, String>, LedgerPendingMutation>()

        override fun read(owner: String, organization: String) = entries[owner to organization]

        override fun save(pending: LedgerPendingMutation) {
            val key = pending.owner to pending.organization
            check(entries[key] == null || entries[key] == pending)
            entries[key] = pending
        }

        override fun clear(pending: LedgerPendingMutation) {
            val key = pending.owner to pending.organization
            check(entries[key] == pending)
            entries.remove(key)
        }
    }

    private fun ledgerActions(busy: Boolean = false) =
        LedgerController(
            CoroutineScope(Dispatchers.Main),
            { error("Network forbidden in ledger fixture") },
            { Organization("fictional-family", "Synthetic family", "admin") },
            { "2026-10" },
            { busy },
            { fail("Fixture must not write ledger") },
            { throw it },
            { fail("Fixture unexpectedly saved") },
            pendingStore = MemoryLedgerPendingStore(),
            owner = { "fictional-owner@https://synthetic.invalid" },
        )

    private val ledger =
        LedgerState(
            organizationName = "Synthetic family",
            month = "2026-10",
            accounts = listOf(account),
        )

    @Test
    fun ledgerMemberHasNoExpenseTransferOrReversalActions() {
        val tx =
            LedgerTransaction(
                "fictional-expense",
                "expense",
                1234,
                "MDL",
                account.id,
                "2026-10-10",
                1,
                merchant = "Synthetic shop",
            )
        rule.setContent {
            FinoraTheme {
                LedgerScreen(ledger.copy(history = listOf(tx)), false, false, ledgerActions(), {})
            }
        }
        rule.onNodeWithText("Synthetic shop").assertExists()
        rule.onNodeWithTag("ledger-add-expense").assertDoesNotExist()
        rule.onNodeWithTag("ledger-add-transfer").assertDoesNotExist()
        rule.onNodeWithTag("ledger-void-fictional-expense").assertDoesNotExist()
    }

    @Test
    fun busyLedgerEditorLocksAmountReviewAndClose() {
        val form = LedgerEdit(accountId = account.id, amount = "12.34", date = "2026-10-10")
        rule.setContent {
            FinoraTheme {
                LedgerScreen(ledger.copy(editor = form), true, true, ledgerActions(busy = true), {})
            }
        }
        rule.onNodeWithTag("ledger-amount").assertIsNotEnabled()
        rule.onNodeWithTag("ledger-review").assertIsNotEnabled()
        rule.onNodeWithText("Закрыть").assertIsNotEnabled()
    }

    @Test
    fun transferConfirmationShowsBothAccountsAndBlocksBusyRepeat() {
        val target = Account("fictional-target", "Synthetic savings", "MDL")
        val form =
            LedgerEdit(
                kind = "transfer",
                accountId = account.id,
                targetAccountId = target.id,
                amount = "12.34",
                date = "2026-10-10",
            )
        val state =
            ledger.copy(
                accounts = listOf(account, target),
                editor = form,
                confirmation = form.command(listOf(account, target)),
            )
        rule.runOnIdle { LanguageRuntime.language = AppLanguage.ENGLISH }
        rule.setContent {
            FinoraTheme { LedgerScreen(state, true, true, ledgerActions(busy = true), {}) }
        }
        rule.onNodeWithText("Confirm transaction").assertIsDisplayed()
        rule.onNodeWithText("Synthetic wallet: −12.34 MDL", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Synthetic savings: +12.34 MDL", substring = true).assertIsDisplayed()
        rule.onNodeWithTag("ledger-confirm").assertIsNotEnabled()
        rule.onNodeWithText("Back").assertIsNotEnabled()
    }

    @Test
    fun ledgerBackAndCloseDismissNativeDraftWithoutWriting() {
        val actions = ledgerActions()
        rule.setContent {
            FinoraTheme {
                LedgerScreen(actions.state.collectAsState().value, true, false, actions, {})
            }
        }
        rule.runOnIdle { actions.open(LedgerEdit(amount = "12.34", date = "2026-10-10")) }
        rule.onNodeWithTag("ledger-review").assertExists()
        InstrumentationRegistry.getInstrumentation()
            .sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        rule.runOnIdle { assertNull(actions.state.value.editor) }
        rule.onNodeWithTag("ledger-review").assertDoesNotExist()
        rule.runOnIdle {
            actions.open(LedgerEdit(kind = "transfer", amount = "12.34", date = "2026-10-10"))
        }
        rule.onNodeWithText("Закрыть").performClick()
        rule.runOnIdle { assertNull(actions.state.value.editor) }
        rule.onNodeWithTag("ledger-review").assertDoesNotExist()
    }
}
