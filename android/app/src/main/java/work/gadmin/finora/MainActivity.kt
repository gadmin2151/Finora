package work.gadmin.finora

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import work.gadmin.finora.ui.FinoraApp
import work.gadmin.finora.ui.FinoraTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { FinoraTheme { FinoraApp(viewModel()) } }
    }
}
