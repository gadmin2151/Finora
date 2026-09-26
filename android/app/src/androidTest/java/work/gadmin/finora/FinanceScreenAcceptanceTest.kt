package work.gadmin.finora

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import work.gadmin.finora.data.*

@RunWith(AndroidJUnit4::class)
class FinanceScreenAcceptanceTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun nativeMenuIncomeAndDebtFormsOverHttps() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val credentials = File(context.filesDir, "finance-acceptance.json")
        assumeTrue(
            "Only a disposable emulator with explicit QA credentials",
            credentials.isFile &&
                SessionStore(context).read() == null &&
                android.os.Build.MODEL.startsWith("sdk_gphone"),
        )
        val data = Json.parseToJsonElement(credentials.readText()).jsonObject
        fun value(key: String) = data.getValue(key).jsonPrimitive.content
        fun awaitText(text: String) {
            rule.waitUntil(40_000) {
                rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
            }
        }
        fun snapshot(name: String) {
            instrumentation.uiAutomation.takeScreenshot()?.let { image ->
                File(context.filesDir, name).outputStream().use {
                    image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
                image.recycle()
            }
        }
        try {
            awaitText("Логин")
            rule.onNodeWithText("Сервер HTTPS").performTextReplacement(value("server"))
            rule.onNodeWithText("Логин").performTextReplacement(value("username"))
            rule.onNodeWithText("Пароль").performTextReplacement(value("password"))
            rule.onNodeWithText("Войти").performScrollTo().performClick()
            awaitText(value("organization_name"))
            rule.onNodeWithText(value("organization_name")).performClick()
            rule.waitUntil(30_000) {
                rule.onAllNodesWithContentDescription("Финансы").fetchSemanticsNodes().isNotEmpty()
            }
            val tabs =
                listOf("Обзор", "Финансы", "Добавить чек", "Чеки", "Помощник").map {
                    rule.onNodeWithContentDescription(it).fetchSemanticsNode().boundsInRoot
                }
            assertTrue(tabs.zipWithNext().all { (a, b) -> a.center.x < b.center.x })
            rule.onNodeWithContentDescription("Финансы").performClick()
            awaitText("Получено за месяц")
            rule.onNodeWithText("Разовый доход").performScrollTo().performClick()
            rule.onNodeWithText("Откуда поступили деньги").performTextInput("QA UI income")
            rule.onNodeWithText("Сумма · MDL").performTextInput("17,25")
            rule.onNodeWithText("Сохранить доход").assertIsDisplayed().assertIsEnabled()
            snapshot("finance-income-editor.png")
            rule.onNodeWithText("Сохранить доход").performClick()
            rule.waitUntil(40_000) {
                rule.onAllNodesWithText("Сохранить доход").fetchSemanticsNodes().isEmpty()
            }
            awaitText("Доход сохранён")
            rule.waitUntil(10_000) {
                rule.onAllNodesWithText("Доход сохранён").fetchSemanticsNodes().isEmpty()
            }
            rule.waitUntil(10_000) {
                rule
                    .onAllNodes(hasContentDescription("Финансы") and isEnabled())
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            rule.waitUntil(10_000) {
                androidx.core.view.ViewCompat.getRootWindowInsets(rule.activity.window.decorView)
                    ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) != true
            }
            rule.onNodeWithTag("finance-list").performScrollToNode(hasText("История"))
            rule.onNodeWithText("История").performClick()
            rule.onNodeWithText("История").assertIsSelected()
            rule.onNodeWithTag("finance-list").performScrollToNode(hasText("QA UI income"))
            awaitText("QA UI income")
            rule.onNodeWithText("QA UI income").performScrollTo().assertIsDisplayed()
            snapshot("finance-income-history.png")
            rule.onNodeWithTag("finance-list").performScrollToIndex(0)
            rule.onNodeWithText("Долги").performClick()
            rule.onNodeWithText("Записать долг").performScrollTo().performClick()
            rule.onNodeWithText("Имя человека").performTextInput("QA UI debt")
            rule.onNodeWithText("Сумма · MDL").performTextInput("20")
            rule.onNodeWithText("Деньги передаются сейчас").performScrollTo().performClick()
            rule.onNodeWithText("Ранее существовавший долг").performClick()
            rule.onNodeWithText("Сохранить долг").performClick()
            rule.waitUntil(40_000) {
                rule.onAllNodesWithText("Сохранить долг").fetchSemanticsNodes().isEmpty()
            }
            rule.waitUntil(10_000) {
                rule
                    .onAllNodes(hasContentDescription("Финансы") and isEnabled())
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            rule.onNodeWithTag("finance-list").performScrollToNode(hasText("QA UI debt"))
            rule.waitUntil(10_000) {
                rule.onAllNodesWithText("Долг записан").fetchSemanticsNodes().isEmpty()
            }
            awaitText("QA UI debt")
            rule
                .onNodeWithText("Погасить частично")
                .performScrollTo()
                .performClick()
            rule.onNodeWithText("Сумма · MDL").performTextReplacement("10")
            rule.onNodeWithText("Записать погашение").assertIsDisplayed().performClick()
            rule.waitUntil(40_000) {
                rule.onAllNodesWithText("Записать погашение").fetchSemanticsNodes().isEmpty()
            }
            rule.onNodeWithText("QA UI debt").performScrollTo().assertIsDisplayed()
            snapshot("finance-debts.png")
            val saved = requireNotNull(SessionStore(context).read())
            runBlocking {
                val api = ApiClient(saved.server, saved.cookie).also { it.csrf = saved.user.csrf }
                assertEquals(
                    1000L,
                    api.debts(saved.user.organizations.single().id)
                        .first { it.person == "QA UI debt" }
                        .remaining_minor,
                )
            }
            rule.onNodeWithContentDescription("Открыть профиль").performClick()
            awaitText("@${value("username")}")
        } finally {
            SessionStore(context).read()?.let { saved ->
                runBlocking {
                    ApiClient(saved.server, saved.cookie)
                        .also { it.csrf = saved.user.csrf }
                        .logout()
                }
            }
            SessionStore(context).clear()
        }
    }
}
