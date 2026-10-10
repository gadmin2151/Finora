package work.gadmin.finora.ui

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/** Keep amounts and warnings reachable while dialog actions remain visible. */
@Composable
fun ScrollableDialogText(text: String) {
    Text(
        text,
        Modifier.heightIn(max = 320.dp)
            .verticalScroll(rememberScrollState())
            .testTag("confirmation-details"),
    )
}
