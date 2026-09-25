package work.gadmin.finora

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import work.gadmin.finora.data.*
import work.gadmin.finora.ui.ChatScreen
import work.gadmin.finora.ui.FinoraTheme

/** Offline UI acceptance on a disposable emulator. Never alters an existing login. */
@RunWith(AndroidJUnit4::class)
class ChatScreenAcceptanceTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun reportAndComposerRemainUsableWhileHistoryRefreshes() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue(!File(context.noBackupFilesDir, "session.enc").exists())
        val store = ViewModelStore()
        val vm = FinoraViewModel(context.applicationContext as Application)
        store.put("chat-test", vm)
        var refreshing by mutableStateOf(false)
        val report =
            AnalyticsReport(
                query = ReportQuery("summary", "2026-09-01", "2026-09-26"),
                title = "Финансовая сводка",
                metrics =
                    listOf(
                        ReportMetric("Доходы", "32500.00 MDL"),
                        ReportMetric("Расходы", "10088.00 MDL"),
                    ),
                notices = listOf("Демонстрационные данные. Расчёт по подтверждённым операциям."),
            )
        try {
            rule.setContent {
                val live by vm.state.collectAsState()
                FinoraTheme {
                    Surface(color = MaterialTheme.colorScheme.background) {
                        ChatScreen(
                            AppState(
                                starting = false,
                                page = Page.CHAT,
                                chatDraft = live.chatDraft,
                                refreshing = refreshing,
                                chatHasOlder = false,
                                month = "2026-09",
                                chat =
                                    listOf(
                                        ChatMessage(
                                            "demo",
                                            "assistant",
                                            "Вот сводка за сентябрь.",
                                            "2026-09-26T00:00:00Z",
                                            details =
                                                ChatDetails(
                                                    provider = "reports",
                                                    reports = listOf(report),
                                                ),
                                        )
                                    ),
                            ),
                            vm,
                        )
                    }
                }
            }
            rule.onNodeWithText("Финансовая сводка").assertExists()
            rule
                .onNode(hasSetTextAction())
                .assertIsDisplayed()
                .performClick()
                .performTextInput("А за август?")
            rule.runOnIdle { refreshing = true }
            rule.onNode(hasSetTextAction()).assertTextContains("А за август?").assertIsDisplayed()
            rule
                .onNodeWithContentDescription("Отправить сообщение")
                .assertIsDisplayed()
                .assertIsEnabled()
            rule.runOnIdle { refreshing = false }
            rule.onNode(hasSetTextAction()).assertTextContains("А за август?")
            val bitmap =
                requireNotNull(
                    InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
                )
            File(context.filesDir, "acceptance-chat.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        } finally {
            rule.runOnIdle { store.clear() }
        }
    }
}
