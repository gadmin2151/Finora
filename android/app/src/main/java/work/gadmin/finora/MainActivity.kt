package work.gadmin.finora

import android.content.res.Configuration
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import work.gadmin.finora.ui.FinoraApp
import work.gadmin.finora.ui.FinoraTheme
import work.gadmin.finora.ui.ThemePreferences

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val systemDark =
            resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                Configuration.UI_MODE_NIGHT_YES
        val dark = ThemePreferences(this).read().isDark(systemDark)
        window.setBackgroundDrawable(
            ColorDrawable(if (dark) 0xFF101715.toInt() else 0xFFF8FAF6.toInt())
        )
        WebView.enableSlowWholeDocumentDraw()
        enableEdgeToEdge()
        setContent { FinoraTheme { FinoraApp(viewModel()) } }
    }
}
