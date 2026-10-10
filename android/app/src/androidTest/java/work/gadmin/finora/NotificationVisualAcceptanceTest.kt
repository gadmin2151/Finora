package work.gadmin.finora

import android.app.Application
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import work.gadmin.finora.data.*
import work.gadmin.finora.notifications.*
import work.gadmin.finora.ui.*

/** Actual notification card with synthetic state: no server, token, financial write or push. */
@RunWith(AndroidJUnit4::class)
class NotificationVisualAcceptanceTest {
    @get:Rule(order = 0) val languageRule = RussianUiLanguageRule()
    @get:Rule(order = 1) val rule = createComposeRule()

    @Test
    fun captureNotificationThemesAndLargeText() {
        assumeTrue(Build.MODEL.startsWith("sdk_gphone"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assumeTrue(!File(context.noBackupFilesDir, "session.enc").exists())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val host =
            PushHost(
                context.applicationContext as Application,
                scope,
                MutableStateFlow(AppState(starting = false)),
                { null },
                { NavigationScope("synthetic-org", "2026-10", "synthetic-session") },
                {},
                {},
                {},
                {},
            )
        @Suppress("UNCHECKED_CAST")
        val state =
            PushHost::class.java.getDeclaredField("mutable").let {
                it.isAccessible = true
                it.get(host) as MutableStateFlow<PushHostState>
            }
        val prefs = ThemePreferences(context)
        val original = prefs.read()
        var night by mutableStateOf(false)
        var scale by mutableFloatStateOf(1f)
        val scroll = androidx.compose.foundation.ScrollState(0)
        rule.setContent {
            val config = Configuration(LocalConfiguration.current)
            config.uiMode =
                (config.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                    if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
            CompositionLocalProvider(
                LocalConfiguration provides config,
                LocalDensity provides Density(LocalDensity.current.density, scale),
            ) {
                FinoraTheme {
                    Surface(color = Paper) {
                        Column(
                            Modifier.fillMaxSize()
                                .safeDrawingPadding()
                                .verticalScroll(scroll)
                                .padding(16.dp)
                        ) {
                            Text(
                                "Уведомления · визуальный QA",
                                style = MaterialTheme.typography.titleLarge,
                            )
                            PushSettingsCard(host)
                        }
                    }
                }
            }
        }
        try {
            rule.waitForIdle()
            for ((mode, dark) in
                listOf(
                    ThemeMode.LIGHT to false,
                    ThemeMode.DARK to true,
                    ThemeMode.BLACK to true,
                    ThemeMode.SYSTEM to false,
                    ThemeMode.SYSTEM to true,
                )) {
                for (large in listOf(false, true)) {
                    rule.runOnIdle {
                        prefs.save(mode)
                        night = dark
                        scale = if (large) 1.8f else 1f
                        state.value =
                            PushHostState(
                                PushState.READY,
                                PushPreferences(
                                    enabled = true,
                                    payments = false,
                                    receipts = true,
                                    important = false,
                                    updates = true,
                                ),
                                true,
                                true,
                                false,
                            )
                    }
                    rule.waitForIdle()
                    runBlocking { scroll.scrollTo(0) }
                    rule.waitForIdle()
                    val suffix =
                        "${mode.name}-${if (dark) "night" else "day"}-${if (large) "large" else "normal"}"
                    save("notifications-$suffix-top.png")
                    rule
                        .onNodeWithText("Время напоминания о платеже", substring = true)
                        .performScrollTo()
                    save("notifications-$suffix-bottom.png")
                    rule.runOnIdle {
                        state.value = state.value.copy(status = PushState.UNCONFIGURED)
                    }
                    runBlocking { scroll.scrollTo(0) }
                    save("notifications-$suffix-disabled-top.png")
                    rule
                        .onNodeWithText("Время напоминания о платеже", substring = true)
                        .performScrollTo()
                    save("notifications-$suffix-disabled-bottom.png")
                }
            }
            rule.runOnIdle { state.value = state.value.copy(status = PushState.UNCONFIGURED) }
            runBlocking { scroll.scrollTo(0) }
            save("notifications-disabled.png")
        } finally {
            rule.runOnIdle { prefs.save(original) }
            scope.cancel()
        }
    }

    @Test
    fun labelledRowsExposeOneToggleAndKeepLargeTextTouchTargets() {
        assumeTrue(Build.MODEL.startsWith("sdk_gphone"))
        var checked by mutableStateOf(false)
        rule.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(LocalDensity.current.density, 1.8f)
            ) {
                FinoraTheme {
                    Surface {
                        Column(Modifier.width(320.dp).padding(16.dp)) {
                            PreferenceSwitchRow("Напоминания о платежах", checked, { checked = it })
                            PreferenceSwitchRow(
                                "Новые чеки других участников",
                                true,
                                {},
                                enabled = false,
                            )
                            PreferenceSwitchRow(
                                "Чеки из всех подключённых организаций",
                                false,
                                {},
                                enabled = false,
                            )
                        }
                    }
                }
            }
        }
        rule.onAllNodes(isToggleable()).assertCountEquals(3)
        val enabled = rule.onNodeWithText("Напоминания о платежах")
        enabled.assertIsOff().assertIsEnabled().performClick().assertIsOn()
        org.junit.Assert.assertTrue(
            enabled.fetchSemanticsNode().boundsInRoot.height >= 56f * rule.density.density
        )
        rule.onNodeWithText("Новые чеки других участников").assertIsOn().assertIsNotEnabled()
        rule
            .onNodeWithText("Чеки из всех подключённых организаций")
            .assertIsOff()
            .assertIsNotEnabled()
        save("notification-touch-targets-disabled.png")
    }

    @Test
    fun longConfirmationKeepsActionsVisibleAndDetailsScrollable() {
        assumeTrue(Build.MODEL.startsWith("sdk_gphone"))
        rule.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(LocalDensity.current.density, 1.8f)
            ) {
                FinoraTheme {
                    AlertDialog(
                        onDismissRequest = {},
                        title = { Text("Подтвердите операцию") },
                        text = {
                            ScrollableDialogText(
                                "Тестовая организация\n" +
                                    "Длинное название счёта и получателя ".repeat(20) +
                                    "\nСумма: 123 456,78 MDL\nБаланс счёта уменьшится."
                            )
                        },
                        confirmButton = { TextButton({}) { Text("Подтвердить") } },
                        dismissButton = { TextButton({}) { Text("Отмена") } },
                    )
                }
            }
        }
        rule.onNodeWithText("Подтвердить").assertIsDisplayed()
        save("confirmation-long-top.png")
        val details = rule.onNodeWithTag("confirmation-details")
        repeat(6) { details.performTouchInput { swipeUp() } }
        org.junit.Assert.assertTrue(
            details
                .fetchSemanticsNode()
                .config[androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange]
                .value() > 0f
        )
        rule.onNodeWithText("Подтвердить").assertIsDisplayed()
        save("confirmation-long-bottom.png")
    }

    private fun save(name: String) {
        rule.waitForIdle()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bitmap =
            requireNotNull(
                InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            )
        File(context.filesDir, name).outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
