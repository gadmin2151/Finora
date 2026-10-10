package work.gadmin.finora

import android.content.Intent
import android.content.res.Configuration
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import work.gadmin.finora.localization.LanguageEnvironment
import work.gadmin.finora.localization.LanguageRuntime
import work.gadmin.finora.ui.FinoraApp
import work.gadmin.finora.ui.FinoraTheme
import work.gadmin.finora.ui.ThemeMode
import work.gadmin.finora.ui.ThemePreferences

class MainActivity : ComponentActivity() {
    private var pushIntentConsumer: ((Intent) -> Unit)? = null

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pushIntentConsumer?.let { consume ->
            consume(intent)
            setIntent(Intent())
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LanguageRuntime.initialize(this)
        val systemDark =
            resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                Configuration.UI_MODE_NIGHT_YES
        val selectedTheme = ThemePreferences(this).read().resolve(systemDark)
        val dark = selectedTheme.isDark(systemDark)
        window.setBackgroundDrawable(
            ColorDrawable(
                when {
                    selectedTheme == ThemeMode.BLACK -> 0xFF18191B.toInt()
                    dark -> 0xFF101715.toInt()
                    else -> 0xFFF8FAF6.toInt()
                }
            )
        )
        WebView.enableSlowWholeDocumentDraw()
        enableEdgeToEdge()
        setContent {
            LanguageEnvironment {
                FinoraTheme {
                    val vm: FinoraViewModel = viewModel()
                    val state by vm.state.collectAsStateWithLifecycle()
                    androidx.compose.runtime.LaunchedEffect(vm) {
                        intent?.let(vm.push::receiveIntent)
                        setIntent(Intent())
                    }
                    androidx.compose.runtime.LaunchedEffect(
                        state.busy,
                        state.starting,
                        state.choosingOrganization,
                        state.organization?.id,
                    ) {
                        vm.push.consumePending()
                    }
                    androidx.compose.runtime.DisposableEffect(vm) {
                        pushIntentConsumer = vm.push::receiveIntent
                        onDispose { pushIntentConsumer = null }
                    }
                    // Registered first: receipt/category/editor Back handlers inside FinoraApp win.
                    BackHandler(
                        state.nativeBackAvailable &&
                            !state.choosingOrganization &&
                            state.camera == null &&
                            state.detailId == null &&
                            state.purchaseCategory == null
                    ) {
                        vm.nativeNavigationBack()
                    }
                    FinoraApp(vm)
                }
            }
        }
    }
}
