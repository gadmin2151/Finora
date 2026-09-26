package work.gadmin.finora

import android.app.Application
import android.graphics.Bitmap
import android.os.Build
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import work.gadmin.finora.data.*
import work.gadmin.finora.localization.*
import work.gadmin.finora.ui.*

@RunWith(AndroidJUnit4::class)
class WalletChatAcceptanceTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun opensAtLatestAndPreservesReadingOlderMessages() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue(Build.MODEL.startsWith("sdk_gphone"))
        assumeTrue(!File(context.noBackupFilesDir, "session.enc").exists())
        val vm = FinoraViewModel(context.applicationContext as Application)
        val store = ViewModelStore().apply { put("test", vm) }
        val previous = LanguageRuntime.language
        LanguageRuntime.language = AppLanguage.ENGLISH
        var messages by mutableStateOf(emptyList<ChatMessage>())
        try {
            rule.setContent {
                FinoraTheme {
                    Surface {
                        ChatScreen(
                            AppState(
                                starting = false,
                                page = Page.CHAT,
                                chat = messages,
                                chatHasOlder = false,
                            ),
                            vm,
                        )
                    }
                }
            }
            rule.runOnIdle {
                messages =
                    (1..30).map {
                        ChatMessage(
                            "$it",
                            "assistant",
                            "Message $it\n\nSome useful details about your finances.",
                            "2026-09-26",
                        )
                    }
            }
            rule.waitUntil(10000) {
                rule
                    .onAllNodesWithText("Message 30", substring = true)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            rule.onNodeWithText("Message 30", substring = true).assertIsDisplayed()
            rule.onNodeWithTag("chat-history").performTouchInput { swipeDown() }
            rule.waitForIdle()
            rule.onNodeWithText("Latest messages").assertIsDisplayed()
            rule.runOnIdle {
                messages =
                    messages +
                        ChatMessage("31", "assistant", "Latest incoming message", "2026-09-26")
            }
            rule.onNodeWithText("Latest incoming message").assertDoesNotExist()
            rule.onNodeWithText("Latest messages").performClick()
            rule.onNodeWithText("Latest incoming message").assertIsDisplayed()
        } finally {
            rule.runOnIdle {
                store.clear()
                LanguageRuntime.language = previous
            }
        }
    }

    @Test
    fun walletMarkdownAndCategoryHaveUsableBilingualScreens() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue(Build.MODEL.startsWith("sdk_gphone"))
        assumeTrue(!File(context.noBackupFilesDir, "session.enc").exists())
        val vm = FinoraViewModel(context.applicationContext as Application)
        val store = ViewModelStore().apply { put("test", vm) }
        @Suppress("UNCHECKED_CAST")
        val mutable =
            FinoraViewModel::class.java.getDeclaredField("mutable").let {
                it.isAccessible = true
                it.get(vm) as MutableStateFlow<AppState>
            }
        @Suppress("UNCHECKED_CAST")
        val history =
            PurchaseHistoryController::class.java.getDeclaredField("mutable").let {
                it.isAccessible = true
                it.get(vm.purchases) as MutableStateFlow<PurchaseHistoryState>
            }
        val previous = LanguageRuntime.language
        val org = Organization("demo", "Family · demo", "admin")
        val account = Account("main", "Main account", "MDL", 2485600)
        var screen by mutableStateOf("wallet")
        val state =
            AppState(
                starting = false,
                organization = org,
                user = User("demo", "demo", "Demo", "", listOf(org)),
                page = Page.OVERVIEW,
                dashboard =
                    Dashboard("2026-09", 3250000, 1194000, 2056000, accounts = listOf(account)),
            )
        try {
            rule.setContent {
                FinoraTheme {
                    Surface {
                        when (screen) {
                            "wallet" -> OverviewScreen(state, vm)
                            "category" -> CategoryPurchasesScreen(vm)
                            else ->
                                ChatScreen(
                                    state.copy(
                                        page = Page.CHAT,
                                        chatHasOlder = false,
                                        chat =
                                            listOf(
                                                ChatMessage(
                                                    "markdown",
                                                    "assistant",
                                                    "## Monthly overview\n\n**Spending** is under control.\n\n- Groceries: **250 MDL**\n- Coffee: *40 MDL*\n\n| Category | Amount |\n| --- | --- |\n| Food | 250 MDL |\n\n[Open guide](https://example.com)\n\n`Total: 290 MDL`",
                                                    "2026-09-26",
                                                )
                                            ),
                                    ),
                                    vm,
                                )
                        }
                    }
                }
            }
            rule.waitUntil(10000) { !vm.state.value.starting }
            for (language in AppLanguage.entries) {
                rule.runOnIdle {
                    LanguageRuntime.language = language
                    mutable.value = state
                    screen = "wallet"
                }
                rule.onNodeWithText(tr(Message.CURRENT_WALLET)).assertIsDisplayed()
                capture(context.filesDir, "wallet-${language.tag}")
                rule.onNodeWithText(tr(Message.ADJUST_BALANCE)).performClick()
                rule.onNodeWithText(tr(Message.BALANCE_ONLY_HINT)).assertExists()
                capture(context.filesDir, "wallet-editor-${language.tag}")
                rule.runOnIdle {
                    vm.wallet.close()
                    screen = "category"
                    history.value =
                        PurchaseHistoryState(
                            category = Category("food", "Groceries"),
                            month = "2026-09",
                            total = 40,
                            offset = 1,
                            items =
                                listOf(
                                    PurchaseItem(
                                        "item",
                                        "receipt",
                                        "LAPTE 2.5%",
                                        "1",
                                        "шт",
                                        2500,
                                        "Fresh · demo",
                                        "MDL",
                                        "2026-09-26",
                                    )
                                ),
                        )
                }
                rule.onNodeWithText("LAPTE 2.5%").assertIsDisplayed()
                rule.onNodeWithText(tr(Message.LOAD_MORE_PURCHASES)).assertExists()
                capture(context.filesDir, "category-${language.tag}")
                rule.runOnIdle { screen = "markdown" }
                rule.onNodeWithText("Monthly overview").assertExists()
                rule.onNodeWithText("Total: 290 MDL").assertExists()
                capture(context.filesDir, "markdown-${language.tag}")
            }
        } finally {
            rule.runOnIdle {
                store.clear()
                LanguageRuntime.language = previous
            }
        }
    }

    private fun capture(directory: File, name: String) {
        rule.waitForIdle()
        val root =
            if (name.startsWith("wallet-editor")) {
                rule
                    .onAllNodes(isRoot())
                    .filterToOne(hasAnyDescendant(hasText(tr(Message.CONFIRM_BALANCE_CHANGE))))
            } else rule.onAllNodes(isRoot()).onLast()
        root.captureToImage().asAndroidBitmap().let { bitmap ->
            File(directory, "$name.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
    }
}
