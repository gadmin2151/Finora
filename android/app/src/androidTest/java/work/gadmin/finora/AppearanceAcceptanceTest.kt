package work.gadmin.finora

import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import work.gadmin.finora.ui.*

/** Offline theme checks on a disposable emulator. Never touches a signed-in physical device. */
@RunWith(AndroidJUnit4::class)
class AppearanceAcceptanceTest {
    @get:Rule(order = 0) val languageRule = RussianUiLanguageRule()

    @get:Rule(order = 1) val rule = createComposeRule()

    @Test
    fun followsSystemAndPreservesManualChoiceAcrossCompositionRecreation() {
        assumeTrue(Build.MODEL.contains("sdk_gphone"))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = ThemePreferences(context)
        val original = preferences.read()
        preferences.save(ThemeMode.SYSTEM)
        var systemDark by mutableStateOf(false)
        var generation by mutableIntStateOf(0)
        try {
            rule.setContent {
                val configuration = Configuration(LocalConfiguration.current)
                configuration.uiMode =
                    (configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                        if (systemDark) Configuration.UI_MODE_NIGHT_YES
                        else Configuration.UI_MODE_NIGHT_NO
                CompositionLocalProvider(LocalConfiguration provides configuration) {
                    key(generation) {
                        FinoraTheme {
                            Surface(color = Paper) {
                                Column(
                                    Modifier.fillMaxSize().safeDrawingPadding().padding(22.dp),
                                    verticalArrangement = Arrangement.spacedBy(20.dp),
                                ) {
                                    Brand()
                                    Text(
                                        "Ваш профиль",
                                        style = MaterialTheme.typography.headlineLarge,
                                    )
                                    AppearanceSettings()
                                    Text(
                                        if (LocalAppearance.current.dark) "Resolved: dark"
                                        else "Resolved: light"
                                    )
                                    PrimaryButton(
                                        "Добавить чек",
                                        {},
                                        Modifier.fillMaxWidth(),
                                        icon = Glyph.SCAN,
                                    )
                                    OutlinedTextField(
                                        "235.08",
                                        {},
                                        label = { Text("Итог чека · MDL") },
                                    )
                                    BrandLoading("Обновляем данные", compact = true)
                                }
                            }
                        }
                    }
                }
            }
            rule.onNodeWithText("Resolved: light").assertExists()
            rule.runOnIdle { systemDark = true }
            rule.onNodeWithText("Resolved: dark").assertExists()
            rule.onNodeWithText("Светлая").performClick()
            rule.onNodeWithText("Resolved: light").assertExists()
            rule.runOnIdle {
                assertEquals(ThemeMode.LIGHT, ThemePreferences(context).read())
                generation++
            }
            rule.onNodeWithText("Resolved: light").assertExists()
            screenshot("theme-light.png")
            rule.onNodeWithText("Тёмная").performClick()
            rule.runOnIdle {
                systemDark = false
                generation++
            }
            rule.onNodeWithText("Resolved: dark").assertExists()
            screenshot("theme-dark.png")
            rule.onNodeWithText("Как в системе").performClick()
            rule.onNodeWithText("Resolved: light").assertExists()
            rule.runOnIdle { systemDark = true }
            rule.onNodeWithText("Resolved: dark").assertExists()
        } finally {
            rule.runOnIdle { preferences.save(original) }
        }
    }

    private fun screenshot(name: String) {
        rule.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(instrumentation.targetContext.filesDir, name).outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
    }
}
