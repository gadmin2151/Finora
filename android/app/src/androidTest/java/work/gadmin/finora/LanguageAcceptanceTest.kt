package work.gadmin.finora

import android.os.Build
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import work.gadmin.finora.localization.*

/** Runs only on an empty emulator; never reads or changes a physical user's session. */
@RunWith(AndroidJUnit4::class)
class LanguageAcceptanceTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun changesLoginLanguageWithoutLosingEnteredUsernameAndPersists() {
        assumeTrue(Build.MODEL.startsWith("sdk_gphone"))
        val context = rule.activity
        assumeTrue(!File(context.noBackupFilesDir, "session.enc").exists())
        val preferences = LanguagePreferences(context)
        val previous = preferences.read()
        val vm = ViewModelProvider(context)[FinoraViewModel::class.java]
        rule.waitUntil(10000) { !vm.state.value.starting }
        try {
            rule.runOnIdle { preferences.save(LanguagePreference.ENGLISH) }
            rule.onNodeWithText("Username").performScrollTo().performTextInput("demo-person")
            rule.onNodeWithText("Password").performScrollTo().performTextInput("fictional-password")
            rule.onNodeWithContentDescription("Change language").performScrollTo().performClick()
            rule.onNodeWithText("Русский").performClick()
            rule.onNodeWithText("Логин").performScrollTo().assertIsDisplayed()
            rule.onNodeWithText("demo-person").assertExists()
            rule.onNodeWithText("Пароль").performScrollTo().assertExists()
            rule.onNodeWithContentDescription("Показать пароль").performClick()
            rule.onNodeWithText("fictional-password").assertExists()
            rule.runOnIdle {
                assertEquals(LanguagePreference.RUSSIAN, LanguagePreferences(context).read())
            }
            rule.onNodeWithContentDescription("Выбрать язык").performScrollTo().performClick()
            rule.onNodeWithText("English").performClick()
            rule.onNodeWithText("Username").performScrollTo().assertIsDisplayed()
            rule.onNodeWithText("demo-person").assertExists()
            rule.runOnIdle {
                assertEquals(LanguagePreference.ENGLISH, LanguagePreferences(context).read())
            }
        } finally {
            rule.runOnIdle { preferences.save(previous) }
        }
    }
}
