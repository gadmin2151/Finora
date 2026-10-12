package work.gadmin.finora.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.time.format.DateTimeFormatter
import work.gadmin.finora.data.*
import work.gadmin.finora.localization.LanguageRuntime
import work.gadmin.finora.localization.Message
import work.gadmin.finora.localization.tr

/** Full debt card with receipt-style movement entries, independent of the selected month. */
@Composable
internal fun DebtHistoryDialog(
    state: DebtHistoryState,
    more: () -> Unit,
    retry: () -> Unit,
    close: () -> Unit,
    busy: Boolean = false,
    openMovement: ((Debt, Boolean, Boolean) -> Unit)? = null,
) {
    val debt = state.debt ?: return
    Dialog(
        onDismissRequest = close,
        properties =
            DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Scaffold(
            modifier = Modifier.fillMaxSize().safeDrawingPadding(),
            containerColor = Paper,
            topBar = {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(close) { LineIcon(Glyph.BACK, tr(Message.CLOSE)) }
                    Text(tr(Message.DEBT_CARD), style = MaterialTheme.typography.titleLarge)
                }
            },
        ) { padding ->
            LazyColumn(
                Modifier.fillMaxSize().padding(padding).testTag("debt-history-list"),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                item {
                    Text(debt.person, style = MaterialTheme.typography.headlineMedium)
                    Text(
                        if (debt.direction == "lent") tr(Message.OWED_TO_ME) else tr(Message.I_OWE),
                        color = Muted,
                    )
                    debt.due_date?.let {
                        Text("${tr(Message.DUE_BY)}: ${financeDateLabel(it)}", color = Muted)
                    }
                    if (debt.note.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(debt.note)
                    }
                }
                state.page?.let { history ->
                    item {
                        Surface(color = SurfaceColor, shape = RoundedCornerShape(22.dp)) {
                            Column(
                                Modifier.fillMaxWidth().padding(18.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(tr(Message.DEBT_OUTSTANDING_BALANCE), color = Muted)
                                Text(
                                    money(history.remaining_minor, history.currency),
                                    style = MaterialTheme.typography.headlineLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = Mint,
                                )
                                if (history.remaining_minor == 0L)
                                    Text(tr(Message.CLOSED), color = Green)
                                HorizontalDivider(color = Border)
                                Text(
                                    "${tr(Message.DEBT_HISTORY_TOTAL)}: ${money(history.initial_minor + history.increased_minor, history.currency)}"
                                )
                                Text(
                                    "${tr(Message.DEBT_HISTORY_REPAID)}: ${money(history.repaid_minor, history.currency)}"
                                )
                                if (history.initial_minor > 0)
                                    Text(
                                        "${tr(Message.DEBT_HISTORY_OPENING)}: ${money(history.initial_minor, history.currency)}",
                                        color = Muted,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                            }
                        }
                    }
                    if (openMovement != null)
                        item {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (history.remaining_minor > 0) {
                                    PrimaryButton(
                                        tr(Message.REPAY_PART),
                                        { openMovement(debt, false, false) },
                                        Modifier.fillMaxWidth().testTag("debt-card-repay"),
                                        !busy && !state.loading,
                                    )
                                    OutlinedButton(
                                        { openMovement(debt, false, true) },
                                        Modifier.fillMaxWidth().testTag("debt-card-repay-full"),
                                        enabled = !busy && !state.loading,
                                    ) {
                                        Text(tr(Message.REPAY_IN_FULL))
                                    }
                                }
                                OutlinedButton(
                                    { openMovement(debt, true, false) },
                                    Modifier.fillMaxWidth().testTag("debt-card-increase"),
                                    enabled = !busy && !state.loading,
                                ) {
                                    LineIcon(Glyph.PLUS)
                                    Spacer(Modifier.width(8.dp))
                                    Text(tr(Message.INCREASE_DEBT))
                                }
                            }
                        }
                    item {
                        Text(
                            tr(Message.DEBT_MOVEMENTS),
                            style = MaterialTheme.typography.titleLarge,
                        )
                        Text(
                            tr(Message.DEBT_HISTORY_ORDER),
                            color = Muted,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    items(history.items, key = DebtHistoryEntry::id) { entry ->
                        DebtMovementCard(entry, history.currency)
                    }
                    if (history.items.isEmpty() && !state.loading)
                        item { Text(tr(Message.DEBT_HISTORY_EMPTY), color = Muted) }
                    if (state.nextOffset < history.total && state.error == null)
                        item {
                            OutlinedButton(
                                more,
                                Modifier.fillMaxWidth().testTag("debt-history-more"),
                                enabled = !state.loading,
                            ) {
                                Text(tr(Message.DEBT_HISTORY_MORE))
                            }
                        }
                }
                if (state.loading) item { BrandLoading(tr(Message.LOADING), compact = true) }
                state.error?.let { error ->
                    item {
                        Text(error, color = MaterialTheme.colorScheme.error)
                        TextButton(retry, enabled = !state.loading) { Text(tr(Message.RETRY)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun DebtMovementCard(entry: DebtHistoryEntry, currency: String) {
    val recorded =
        entry
            .recordedAt()
            ?.format(
                DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", LanguageRuntime.language.locale)
            )
    Surface(
        color = SurfaceColor,
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, Border),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp).testTag("debt-history-${entry.id}"),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                tr(
                    when (entry.operation) {
                        "initial" -> Message.DEBT_HISTORY_CREATED
                        "increase" -> Message.DEBT_HISTORY_INCREASED
                        "repayment" -> Message.DEBT_HISTORY_REPAYMENT
                        else -> Message.DEBT_HISTORY_MOVEMENT
                    }
                ),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                (if (entry.operation == "repayment") "−" else "+") +
                    money(entry.amount_minor, currency),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                color = if (entry.voided) Muted else Ink,
                textDecoration =
                    if (entry.voided) TextDecoration.LineThrough else TextDecoration.None,
            )
            Text(
                tr(Message.DEBT_OPERATION_DATE, financeDateLabel(entry.occurred_on)),
                color = Muted,
            )
            Text(
                recorded?.let { tr(Message.DEBT_RECORDED_AT, it) }
                    ?: tr(Message.DEBT_TIME_UNAVAILABLE),
                color = Muted,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("debt-recorded-at-${entry.id}"),
            )
            entry.account_name?.takeIf(String::isNotBlank)?.let {
                Text("${tr(Message.ACCOUNT)}: $it")
            }
            if (entry.note.isNotBlank()) {
                HorizontalDivider(color = Border)
                Text(
                    tr(Message.DEBT_MOVEMENT_COMMENT),
                    color = Muted,
                    style = MaterialTheme.typography.labelMedium,
                )
                Text(entry.note)
            }
            if (entry.voided)
                Text(tr(Message.DEBT_HISTORY_VOIDED), color = MaterialTheme.colorScheme.error)
            Text(
                tr(Message.DEBT_HISTORY_BALANCE, money(entry.balance_after_minor, currency)),
                fontWeight = FontWeight.Medium,
            )
        }
    }
}
