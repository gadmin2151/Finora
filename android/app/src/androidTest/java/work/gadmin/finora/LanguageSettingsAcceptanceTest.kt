package work.gadmin.finora

import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import work.gadmin.finora.localization.*
import work.gadmin.finora.ui.*

@RunWith(AndroidJUnit4::class)
class LanguageSettingsAcceptanceTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun settingsFollowDeviceFallbackAndRememberManualChoice() {
        assumeTrue(Build.MODEL.startsWith("sdk_gphone"))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = LanguagePreferences(context)
        val previous = preferences.read()
        preferences.save(LanguagePreference.SYSTEM)
        var deviceLanguages by mutableStateOf("ru-MD,en-US")
        var generation by mutableIntStateOf(0)
        try {
            rule.setContent {
                val device =
                    Configuration(LocalConfiguration.current).apply {
                        setLocales(LocaleList.forLanguageTags(deviceLanguages))
                    }
                CompositionLocalProvider(LocalConfiguration provides device) {
                    key(generation) {
                        LanguageEnvironment {
                            FinoraTheme {
                                Column {
                                    LanguageSettings()
                                    Text(tr(Message.YOUR_PROFILE))
                                    Text("Мария · Family account")
                                }
                            }
                        }
                    }
                }
            }
            rule.onNodeWithText("Ваш профиль").assertExists()
            rule.onNodeWithText("English").performClick()
            rule.onNodeWithText("Your profile").assertExists()
            rule.runOnIdle {
                assertEquals(LanguagePreference.ENGLISH, LanguagePreferences(context).read())
                generation++
                deviceLanguages = "ru-RU"
            }
            rule.onNodeWithText("Your profile").assertExists()
            rule.onNodeWithText("Русский").performClick()
            rule.onNodeWithText("Ваш профиль").assertExists()
            rule.onNodeWithText("Язык телефона").performClick()
            rule.runOnIdle { deviceLanguages = "ro-MD,ru-RU" }
            rule.onNodeWithText("Your profile").assertExists()
            rule.onNodeWithText("Мария · Family account").assertExists()
            rule.runOnIdle {
                assertEquals(LanguagePreference.SYSTEM, LanguagePreferences(context).read())
            }
        } finally {
            preferences.save(previous)
            LanguageRuntime.initialize(context)
        }
    }
}
