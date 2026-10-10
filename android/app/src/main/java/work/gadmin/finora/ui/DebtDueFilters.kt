package work.gadmin.finora.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import work.gadmin.finora.localization.Message
import work.gadmin.finora.localization.tr

@Composable
internal fun DebtDueFilters(
    overdueOnly: Boolean,
    includeClosed: Boolean,
    changeOverdue: (Boolean) -> Unit,
    changeClosed: (Boolean) -> Unit,
) {
    Column {
        FilterChip(
            selected = overdueOnly,
            onClick = { changeOverdue(!overdueOnly) },
            label = { Text(tr(Message.ONLY_OVERDUE_DEBTS)) },
            modifier = Modifier.testTag("debts-overdue-filter"),
        )
        Row(
            Modifier.fillMaxWidth()
                .heightIn(min = 48.dp)
                .testTag("debts-closed-filter")
                .toggleable(
                    includeClosed,
                    enabled = !overdueOnly,
                    role = Role.Checkbox,
                    onValueChange = changeClosed,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                includeClosed,
                null,
                enabled = !overdueOnly,
                modifier = Modifier.clearAndSetSemantics {},
            )
            Text(tr(Message.SHOW_CLOSED_DEBTS), style = MaterialTheme.typography.bodyMedium)
        }
    }
}
