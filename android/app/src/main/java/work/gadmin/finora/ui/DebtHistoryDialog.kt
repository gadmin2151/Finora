package work.gadmin.finora.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import work.gadmin.finora.data.*
import work.gadmin.finora.localization.Message
import work.gadmin.finora.localization.tr

@Composable
internal fun DebtHistoryDialog(
    state: DebtHistoryState,
    more: () -> Unit,
    retry: () -> Unit,
    close: () -> Unit,
) {
    val debt = state.debt ?: return
    AlertDialog(
        onDismissRequest = close,
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(tr(Message.DEBT_HISTORY))
                Text(debt.person, style = MaterialTheme.typography.titleMedium)
            }
        },
        text = {
            LazyColumn(
                Modifier.fillMaxWidth().heightIn(max = 440.dp).testTag("debt-history-list"),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                state.page?.let { history ->
                    item {
                        Text(
                            if (debt.direction == "lent") tr(Message.OWED_TO_ME)
                            else tr(Message.I_OWE),
                            color = Muted,
                        )
                        Text(
                            "${tr(Message.DEBT_HISTORY_TOTAL)}: ${money(history.initial_minor + history.increased_minor, history.currency)}"
                        )
                        Text(
                            "${tr(Message.DEBT_HISTORY_REPAID)}: ${money(history.repaid_minor, history.currency)}"
                        )
                        Text(
                            "${tr(Message.DEBT_OUTSTANDING_BALANCE)}: ${money(history.remaining_minor, history.currency)}",
                            fontWeight = FontWeight.Bold,
                        )
                        if (history.remaining_minor == 0L) Text(tr(Message.CLOSED), color = Mint)
                        if (history.initial_minor > 0)
                            Text(
                                "${tr(Message.DEBT_HISTORY_OPENING)}: ${money(history.initial_minor, history.currency)}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                    }
                    if (history.items.isNotEmpty())
                        item {
                            Text(
                                tr(Message.DEBT_HISTORY_ORDER),
                                color = Muted,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    items(history.items, key = DebtHistoryEntry::id) { entry ->
                        Column(
                            Modifier.fillMaxWidth().testTag("debt-history-${entry.id}"),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            HorizontalDivider()
                            Text(
                                tr(
                                    when (entry.operation) {
                                        "initial" -> Message.DEBT_HISTORY_CREATED
                                        "increase" -> Message.DEBT_HISTORY_INCREASED
                                        "repayment" -> Message.DEBT_HISTORY_REPAYMENT
                                        else -> Message.DEBT_HISTORY_MOVEMENT
                                    }
                                ),
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(financeDateLabel(entry.occurred_on), color = Muted)
                            Text(money(entry.amount_minor, history.currency))
                            entry.account_name?.takeIf(String::isNotBlank)?.let { Text(it) }
                            if (entry.note.isNotBlank()) Text(entry.note)
                            if (entry.voided)
                                Text(
                                    tr(Message.DEBT_HISTORY_VOIDED),
                                    color = MaterialTheme.colorScheme.error,
                                )
                            Text(
                                tr(
                                    Message.DEBT_HISTORY_BALANCE,
                                    money(entry.balance_after_minor, history.currency),
                                ),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    if (history.items.isEmpty() && !state.loading)
                        item { Text(tr(Message.DEBT_HISTORY_EMPTY), color = Muted) }
                    if (state.nextOffset < history.total && state.error == null)
                        item {
                            TextButton(
                                more,
                                Modifier.fillMaxWidth().testTag("debt-history-more"),
                                enabled = !state.loading,
                            ) {
                                Text(tr(Message.DEBT_HISTORY_MORE))
                            }
                        }
                }
                if (state.loading)
                    item {
                        BrandLoading(tr(Message.LOADING), compact = true)
                    }
                state.error?.let { error ->
                    item {
                        Text(error, color = MaterialTheme.colorScheme.error)
                        TextButton(retry, enabled = !state.loading) { Text(tr(Message.RETRY)) }
                    }
                }
            }
        },
        confirmButton = { TextButton(close) { Text(tr(Message.CLOSE)) } },
    )
}
