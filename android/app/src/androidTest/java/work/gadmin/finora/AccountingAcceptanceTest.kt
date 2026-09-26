package work.gadmin.finora

import android.app.Application
import android.graphics.Bitmap
import android.os.Build
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
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
import work.gadmin.finora.localization.*
import work.gadmin.finora.ui.*

@RunWith(AndroidJUnit4::class)
class AccountingAcceptanceTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun adminCanChooseSingleAccountModeOnPhone() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue(Build.MODEL.startsWith("sdk_gphone"))
        assumeTrue(!File(context.noBackupFilesDir, "session.enc").exists())
        val vm = FinoraViewModel(context.applicationContext as Application)
        val store = ViewModelStore().apply { put("accounting-test", vm) }
        val previous = LanguageRuntime.language
        LanguageRuntime.language = AppLanguage.ENGLISH
        try {
            rule.setContent {
                FinoraTheme {
                    Surface(Modifier.verticalScroll(rememberScrollState())) {
                        AccountingSettings(
                            AppState(
                                organization = Organization("qa", "Demo", "admin"),
                                accounting = AccountingConfig("separate", "card", 1),
                                accounts =
                                    listOf(
                                        Account("cash", "Cash", "MDL"),
                                        Account("card", "Primary", "MDL"),
                                    ),
                            ),
                            vm,
                        )
                    }
                }
            }
            rule.onNodeWithText("All together").performClick()
            rule
                .onNodeWithText(
                    "One MDL account: balances, receipts and all transactions are merged."
                )
                .assertExists()
            rule.onNode(isToggleable()).assertDoesNotExist()
            rule.onNodeWithText("Confirm and save").performScrollTo().assertIsEnabled()
            rule.onRoot().captureToImage().asAndroidBitmap().let { bitmap ->
                File(context.filesDir, "accounting-settings.png").outputStream().use {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
            }
            rule.onNodeWithText("Cash and cards separately").performScrollTo().performClick()
            rule
                .onNodeWithText("Move existing MDL receipts to the primary account")
                .assertDoesNotExist()
        } finally {
            rule.runOnIdle {
                store.clear()
                LanguageRuntime.language = previous
            }
        }
    }
}
