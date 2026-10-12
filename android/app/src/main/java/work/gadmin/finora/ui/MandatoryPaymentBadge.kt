package work.gadmin.finora.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import work.gadmin.finora.data.MandatoryPayment
import work.gadmin.finora.localization.Message
import work.gadmin.finora.localization.tr

@Composable
internal fun MandatoryPaymentBadge(payment: MandatoryPayment?) {
    if (payment == null) return
    Surface(color = SoftGreen, shape = RoundedCornerShape(8.dp)) {
        Text(
            tr(Message.MANDATORY_EXPENSE),
            Modifier.padding(horizontal = 9.dp, vertical = 5.dp).testTag("mandatory-expense-badge"),
            color = Green,
            style = MaterialTheme.typography.labelMedium,
        )
    }
}
