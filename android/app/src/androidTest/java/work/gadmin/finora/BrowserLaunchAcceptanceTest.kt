package work.gadmin.finora

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import work.gadmin.finora.data.*
import work.gadmin.finora.localization.*

/** Intercept intents: no actual browser, network, credentials or existing records. */
@RunWith(AndroidJUnit4::class)
class BrowserLaunchAcceptanceTest {
    private class RecordingApplication(context: Context) : Application() {
        val intents = mutableListOf<Intent>()
        var unavailable = false

        init {
            attachBaseContext(context)
        }

        override fun startActivity(intent: Intent) {
            if (unavailable) throw ActivityNotFoundException("Synthetic browser unavailable")
            intents.add(intent)
        }
    }

    private fun withViewModel(
        test: (FinoraViewModel, MutableStateFlow<AppState>, RecordingApplication) -> Unit
    ) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assumeTrue(Build.MODEL.startsWith("sdk_gphone"))
        assumeTrue(!File(context.noBackupFilesDir, "session.enc").exists())
        val app = RecordingApplication(context)
        val vm = FinoraViewModel(app)
        val holder = ViewModelStore().apply { put("browser-qa", vm) }
        val previous = LanguageRuntime.language
        try {
            runBlocking { withTimeout(10_000) { vm.state.first { !it.starting } } }
            @Suppress("UNCHECKED_CAST")
            val mutable =
                FinoraViewModel::class.java.getDeclaredField("mutable").let {
                    it.isAccessible = true
                    it.get(vm) as MutableStateFlow<AppState>
                }
            instrumentation.runOnMainSync {
                LanguageRuntime.language = AppLanguage.ENGLISH
                test(vm, mutable, app)
            }
        } finally {
            instrumentation.runOnMainSync {
                holder.clear()
                LanguageRuntime.language = previous
            }
        }
    }

    @Test
    fun browserFailureKeepsCurrentScreenAndShowsRecoverableMessage() =
        withViewModel { vm, state, app ->
            app.unavailable = true
            state.value = AppState(starting = false, page = Page.PROFILE)
            vm.openWebsite()
            assertEquals(Page.PROFILE, state.value.page)
            assertEquals(tr(Message.AI_NAV_OPEN_FAILED), state.value.error)
            state.value = state.value.copy(error = null)
            vm.openPrivacyPolicy()
            assertEquals(tr(Message.AI_NAV_OPEN_FAILED), state.value.error)
            assertTrue(app.intents.isEmpty())
        }

    @Test
    fun existingServerAndFixedPrivacyLinksDoNotIncludeSessionOrProposalValues() =
        withViewModel { vm, state, app ->
            state.value = AppState(starting = false, server = "https://SYNTHETIC.invalid:443/")
            vm.openWebsite(ChatDestination.RECEIPTS)
            vm.openPrivacyPolicy()
            assertEquals("https://synthetic.invalid/#receipts", app.intents[0].dataString)
            assertEquals(PRIVACY_POLICY_URL, app.intents[1].dataString)
            assertTrue(app.intents.all { it.action == Intent.ACTION_VIEW && it.extras == null })
        }

    @Test
    fun invalidOriginAndUnavailableRoleNeverLaunchAnIntent() = withViewModel { vm, state, app ->
        state.value = AppState(starting = false)
        vm.openWebsite(ChatDestination.BUDGETS)
        assertTrue(app.intents.isEmpty())
        state.value = state.value.copy(server = "https://synthetic.invalid/?secret=never-send")
        vm.openWebsite()
        assertTrue(app.intents.isEmpty())
        assertEquals(tr(Message.AI_NAV_OPEN_FAILED), state.value.error)
    }
}
