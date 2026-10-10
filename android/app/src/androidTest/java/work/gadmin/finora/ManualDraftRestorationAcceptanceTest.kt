package work.gadmin.finora

import android.app.Application
import android.os.Build
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
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
import work.gadmin.finora.ui.*

/** Offline saved-instance restoration, matching Compose restoration during recreation/rotation. */
@RunWith(AndroidJUnit4::class)
class ManualDraftRestorationAcceptanceTest {
    @get:Rule(order = 0) val languageRule = RussianUiLanguageRule()
    @get:Rule(order = 1) val rule = createComposeRule()

    @Test
    fun savedInstanceRestoresLatestIncompleteEditorWithoutChangingPostingKey() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assumeTrue(Build.MODEL.startsWith("sdk_gphone"))
        assumeTrue(!File(context.noBackupFilesDir, "session.enc").exists())
        val scope =
            scopeKey("https://synthetic.invalid", "qa-restoration", UUID.randomUUID().toString())
        val disk = ManualReceiptDraftStore(context, scope)
        val original =
            ManualReceiptDraft(UUID.randomUUID().toString(), ReceiptForm.manual(emptyList()))
        val vm = FinoraViewModel(context.applicationContext as Application)
        val holder = ViewModelStore().apply { put("restore", vm) }
        try {
            runBlocking { withTimeout(10_000) { vm.state.first { !it.starting } } }
            @Suppress("UNCHECKED_CAST")
            val mutable =
                FinoraViewModel::class.java.getDeclaredField("mutable").let {
                    it.isAccessible = true
                    it.get(vm) as MutableStateFlow<AppState>
                }
            FinoraViewModel::class.java.getDeclaredField("manualDrafts").let {
                it.isAccessible = true
                it.set(vm, disk)
            }
            val initial =
                AppState(
                    starting = false,
                    editingReceipt = true,
                    manualReceiptKey = original.requestKey,
                    manualReceiptDraft = original,
                )
            instrumentation.runOnMainSync { mutable.value = initial }
            val restoration = StateRestorationTester(rule)
            restoration.setContent { FinoraTheme { ReceiptEditor(initial, vm) } }
            rule
                .onNodeWithText(tr(Message.STORE))
                .performTextInput("Synthetic restored incomplete store")
            rule.waitForIdle()
            restoration.emulateSavedInstanceStateRestore()
            rule.onNodeWithText("Synthetic restored incomplete store").assertExists()
            runBlocking {
                withTimeout(10_000) {
                    vm.state.first {
                        it.manualDraftSaved &&
                            it.manualReceiptDraft?.form?.merchant ==
                                "Synthetic restored incomplete store"
                    }
                }
            }
            val restored = requireNotNull(disk.read())
            assertEquals(original.requestKey, restored.requestKey)
            assertEquals("Synthetic restored incomplete store", restored.form.merchant)
            assertEquals(original.form.items, restored.form.items)
            assertEquals("", restored.form.total)
        } finally {
            instrumentation.runOnMainSync { holder.clear() }
            File(context.noBackupFilesDir, "drafts/$scope").deleteRecursively()
        }
    }
}
