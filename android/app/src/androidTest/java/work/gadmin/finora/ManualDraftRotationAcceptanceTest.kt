package work.gadmin.finora

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Build
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import work.gadmin.finora.data.*
import work.gadmin.finora.localization.*

/** Real MainActivity orientation recreation; no authenticated session or network client. */
@RunWith(AndroidJUnit4::class)
class ManualDraftRotationAcceptanceTest {
    @get:Rule(order = 0) val languageRule = RussianUiLanguageRule()
    @get:Rule(order = 1) val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun realRotationRetainsIncompleteFieldsAndExactPostingKey() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assumeTrue(Build.MODEL.startsWith("sdk_gphone"))
        assumeTrue(!File(context.noBackupFilesDir, "session.enc").exists())
        val org = Organization(UUID.randomUUID().toString(), "Synthetic rotation", "admin")
        val user = User(UUID.randomUUID().toString(), "qa-rotation", "Synthetic", "synthetic")
        val scope = scopeKey("https://synthetic.invalid", user.id, org.id)
        val disk = ManualReceiptDraftStore(context, scope)
        val original =
            ManualReceiptDraft(UUID.randomUUID().toString(), ReceiptForm.manual(emptyList()))
        val vm = AtomicReference<FinoraViewModel>()
        val activity = AtomicReference<MainActivity>()
        try {
            rule.activityRule.scenario.onActivity {
                activity.set(it)
                vm.set(ViewModelProvider(it)[FinoraViewModel::class.java])
            }
            runBlocking { withTimeout(10_000) { vm.get().state.first { !it.starting } } }
            @Suppress("UNCHECKED_CAST")
            val mutable =
                FinoraViewModel::class.java.getDeclaredField("mutable").let {
                    it.isAccessible = true
                    it.get(vm.get()) as MutableStateFlow<AppState>
                }
            instrumentation.runOnMainSync {
                FinoraViewModel::class.java.getDeclaredField("manualDrafts").let {
                    it.isAccessible = true
                    it.set(vm.get(), disk)
                }
                mutable.value =
                    AppState(
                        starting = false,
                        user = user,
                        organization = org,
                        editingReceipt = true,
                        manualReceiptKey = original.requestKey,
                        manualReceiptDraft = original,
                    )
            }
            rule
                .onNodeWithText(tr(Message.STORE))
                .performTextInput("Synthetic rotated incomplete store")
            rule.waitForIdle()
            runBlocking {
                withTimeout(10_000) {
                    vm.get().state.first {
                        it.manualDraftSaved &&
                            it.manualReceiptDraft?.form?.merchant ==
                                "Synthetic rotated incomplete store"
                    }
                }
            }
            val before = activity.get()
            val expectedOrientation =
                if (
                    before.resources.configuration.orientation ==
                        Configuration.ORIENTATION_LANDSCAPE
                )
                    Configuration.ORIENTATION_PORTRAIT
                else Configuration.ORIENTATION_LANDSCAPE
            rule.activityRule.scenario.onActivity {
                it.requestedOrientation =
                    if (expectedOrientation == Configuration.ORIENTATION_LANDSCAPE)
                        ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                    else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            }
            rule.waitUntil(10_000) {
                rule.activityRule.scenario.onActivity { activity.set(it) }
                activity.get() !== before &&
                    activity.get().resources.configuration.orientation == expectedOrientation
            }
            rule.waitForIdle()
            rule.onNodeWithText("Synthetic rotated incomplete store").assertExists()
            rule.activityRule.scenario.onActivity {
                assertSame(vm.get(), ViewModelProvider(it)[FinoraViewModel::class.java])
            }
            val restored = requireNotNull(disk.read())
            assertEquals(original.requestKey, restored.requestKey)
            assertEquals("Synthetic rotated incomplete store", restored.form.merchant)
            assertEquals(original.form.items, restored.form.items)
            assertEquals("", restored.form.total)
        } finally {
            rule.activityRule.scenario.close()
            File(context.noBackupFilesDir, "drafts/$scope").deleteRecursively()
        }
    }
}
