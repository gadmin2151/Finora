package work.gadmin.finora.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import java.time.YearMonth
import work.gadmin.finora.data.*

@Composable
fun LedgerScreen(
    state: LedgerState,
    admin: Boolean,
    busy: Boolean,
    controller: LedgerController,
    month: (String) -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Text(state.organizationName, style = MaterialTheme.typography.labelLarge)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(
                {
                    runCatching { YearMonth.parse(state.month).minusMonths(1) }
                        .getOrNull()
                        ?.let { month(it.toString()) }
                },
                enabled = !busy,
            ) {
                Text("‹")
            }
            Text(state.month, Modifier.padding(16.dp))
            TextButton(
                {
                    runCatching { YearMonth.parse(state.month).plusMonths(1) }
                        .getOrNull()
                        ?.let { month(it.toString()) }
                },
                enabled = !busy,
            ) {
                Text("›")
            }
        }
        if (admin)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    {
                        controller.open(
                            LedgerEdit(
                                accountId =
                                    state.accounts.firstOrNull { !it.archived }?.id.orEmpty()
                            )
                        )
                    },
                    enabled = !busy && !state.loading,
                    modifier = Modifier.testTag("ledger-add-expense"),
                ) {
                    Text(ledgerText("Расход", "Expense"))
                }
                OutlinedButton(
                    {
                        controller.open(
                            LedgerEdit(
                                kind = "transfer",
                                accountId =
                                    state.accounts.firstOrNull { !it.archived }?.id.orEmpty(),
                            )
                        )
                    },
                    enabled = !busy && !state.loading,
                    modifier = Modifier.testTag("ledger-add-transfer"),
                ) {
                    Text(ledgerText("Перевод", "Transfer"))
                }
            }
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf(
                    null to ledgerText("Все", "All"),
                    "expense" to ledgerText("Расходы", "Expenses"),
                    "income" to ledgerText("Доходы", "Income"),
                    "transfer" to ledgerText("Переводы", "Transfers"),
                )
                .forEach { (kind, label) ->
                    FilterChip(
                        state.kind == kind,
                        { controller.filter(kind, state.accountId) },
                        { Text(label) },
                        enabled = !busy,
                    )
                }
        }
        LedgerChoice(
            ledgerText("Счёт", "Account"),
            state.accountId.orEmpty(),
            listOf("" to ledgerText("Все счета", "All accounts")) +
                state.accounts.map { it.id to "${it.name} · ${it.currency}" },
            !busy,
        ) {
            controller.filter(state.kind, it.ifBlank { null })
        }
        if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        state.error?.let { error ->
            Text(error, color = MaterialTheme.colorScheme.error)
            TextButton(controller::load, enabled = !busy) {
                Text(ledgerText("Повторить загрузку", "Retry loading"))
            }
        }
        LazyColumn(
            Modifier.weight(1f).testTag("ledger-history"),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (!state.loading && state.error == null && state.history.isEmpty())
                item {
                    Text(
                        ledgerText("За этот месяц операций нет", "No transactions this month"),
                        Modifier.padding(16.dp),
                    )
                }
            items(state.history, key = LedgerTransaction::id) { tx ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            "${tx.occurred_on} · ${ledgerKind(tx.kind)}",
                            style = MaterialTheme.typography.labelLarge,
                        )
                        Text(
                            "${editableMoney(tx.amount_minor)} ${tx.currency}",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            state.accounts.firstOrNull { it.id == tx.account_id }?.name
                                ?: tx.account_id
                        )
                        if (tx.kind == "transfer") {
                            val target =
                                state.accounts.firstOrNull { it.id == tx.target_account_id }
                            Text(
                                "→ ${target?.name ?: tx.target_account_id.orEmpty()}" +
                                    (tx.target_amount_minor?.let {
                                        " · ${editableMoney(it)} ${target?.currency.orEmpty()}"
                                    } ?: "")
                            )
                        }
                        if (tx.merchant.isNotBlank()) Text(tx.merchant)
                        if (tx.note.isNotBlank()) Text(tx.note)
                        if (admin)
                            TextButton(
                                { controller.void(tx) },
                                enabled = !busy,
                                modifier = Modifier.testTag("ledger-void-${tx.id}"),
                            ) {
                                Text(ledgerText("Отменить операцию", "Reverse transaction"))
                            }
                    }
                }
            }
            if (state.historyOffset < state.historyCount)
                item {
                    TextButton(
                        controller::moreHistory,
                        enabled = !busy && !state.loadingMore && !state.loading,
                    ) {
                        Text(
                            if (state.loadingMore) ledgerText("Загрузка…", "Loading…")
                            else ledgerText("Загрузить ещё", "Load more")
                        )
                    }
                }
        }
    }
    state.editor?.let { form ->
        BackHandler(enabled = !busy) { controller.closeEditor() }
        LedgerEditor(form, state, busy, controller)
    }
    if (state.confirmation != null || state.voiding != null) {
        val form = state.editor
        val source = state.accounts.firstOrNull { it.id == form?.accountId }
        val target = state.accounts.firstOrNull { it.id == form?.targetAccountId }
        AlertDialog(
            onDismissRequest = controller::cancelConfirmation,
            title = { Text(ledgerText("Подтвердите операцию", "Confirm transaction")) },
            text = {
                Text(
                    state.organizationName +
                        "\n" +
                        if (state.voiding != null)
                            ledgerText(
                                "Отменить эту операцию и её влияние на баланс?",
                                "Reverse this transaction and its effect on the balance?",
                            ) +
                                "\n${state.voiding.occurred_on} · ${editableMoney(state.voiding.amount_minor)} ${state.voiding.currency}\n${state.accounts.firstOrNull { it.id == state.voiding.account_id }?.name.orEmpty()}"
                        else if (form?.kind == "transfer")
                            "${source?.name}: −${form.amount} ${source?.currency}\n→ ${target?.name}: +${if (source?.currency == target?.currency) form.amount else form.targetAmount} ${target?.currency}\n${form.date}"
                        else
                            "${source?.name}: −${form?.amount} ${source?.currency}\n${form?.date}\n${form?.merchant.orEmpty()}"
                )
            },
            confirmButton = {
                Button(
                    controller::confirm,
                    enabled = !busy,
                    modifier = Modifier.testTag("ledger-confirm"),
                ) {
                    Text(ledgerText("Подтвердить", "Confirm"))
                }
            },
            dismissButton = {
                TextButton(controller::cancelConfirmation, enabled = !busy) {
                    Text(ledgerText("Назад", "Back"))
                }
            },
        )
    }
}

private fun ledgerKind(kind: String) =
    when (kind) {
        "expense" -> ledgerText("Расход", "Expense")
        "income" -> ledgerText("Доход", "Income")
        "transfer" -> ledgerText("Перевод", "Transfer")
        "refund" -> ledgerText("Возврат", "Refund")
        "adjustment",
        "adjustment_out" -> ledgerText("Корректировка", "Adjustment")
        else -> kind
    }

@Composable
private fun LedgerChoice(
    label: String,
    value: String,
    options: List<Pair<String, String>>,
    enabled: Boolean,
    select: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton({ expanded = true }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
            Text(
                "$label: ${options.firstOrNull { it.first == value }?.second ?: ledgerText("Выберите", "Choose")}"
            )
        }
        DropdownMenu(expanded, { expanded = false }) {
            options.forEach { (id, name) ->
                DropdownMenuItem(
                    text = { Text(name) },
                    onClick = {
                        expanded = false
                        select(id)
                    },
                )
            }
        }
    }
}

@Composable
private fun LedgerEditor(
    form: LedgerEdit,
    state: LedgerState,
    busy: Boolean,
    controller: LedgerController,
) {
    val enabled = !busy && !state.editorRetryLocked
    val source = state.accounts.firstOrNull { it.id == form.accountId }
    val target = state.accounts.firstOrNull { it.id == form.targetAccountId }
    AlertDialog(
        onDismissRequest = controller::closeEditor,
        title = { Text(ledgerKind(form.kind)) },
        text = {
            Column(Modifier.heightIn(max = 480.dp)) {
                // The vertical scroll keeps every required field reachable on small screens.
                Text(
                    "${state.organizationName} · ${form.date}",
                    style = MaterialTheme.typography.labelMedium,
                )
                LedgerFields(form, state, enabled, source, target, controller)
            }
        },
        confirmButton = {
            Button(
                controller::submit,
                enabled = enabled,
                modifier = Modifier.testTag("ledger-review"),
            ) {
                Text(ledgerText("Проверить", "Review"))
            }
        },
        dismissButton = {
            TextButton(controller::closeEditor, enabled = !busy) {
                Text(ledgerText("Закрыть", "Close"))
            }
        },
    )
}

@Composable
private fun LedgerFields(
    form: LedgerEdit,
    state: LedgerState,
    enabled: Boolean,
    source: Account?,
    target: Account?,
    controller: LedgerController,
) {
    Column(
        Modifier.fillMaxWidth().verticalScroll(androidx.compose.foundation.rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        LedgerChoice(
            ledgerText("Со счёта", "Source account"),
            form.accountId,
            state.accounts.filterNot(Account::archived).map {
                it.id to "${it.name} · ${it.currency}"
            },
            enabled,
        ) {
            controller.edit(form.copy(accountId = it, fxRate = ""))
        }
        OutlinedTextField(
            form.amount,
            { controller.edit(form.copy(amount = it)) },
            label = { Text(ledgerText("Сумма", "Amount") + " ${source?.currency.orEmpty()}") },
            enabled = enabled,
            singleLine = true,
            modifier = Modifier.testTag("ledger-amount"),
        )
        if (form.kind == "transfer") {
            LedgerChoice(
                ledgerText("На счёт", "Destination account"),
                form.targetAccountId,
                state.accounts
                    .filter { !it.archived && it.id != form.accountId }
                    .map { it.id to "${it.name} · ${it.currency}" },
                enabled,
            ) {
                controller.edit(form.copy(targetAccountId = it, targetAmount = ""))
            }
            if (source != null && target != null && source.currency != target.currency)
                OutlinedTextField(
                    form.targetAmount,
                    { controller.edit(form.copy(targetAmount = it)) },
                    label = { Text(ledgerText("Получено", "Received") + " ${target.currency}") },
                    enabled = enabled,
                    singleLine = true,
                    modifier = Modifier.testTag("ledger-target-amount"),
                )
        } else {
            OutlinedTextField(
                form.merchant,
                { controller.edit(form.copy(merchant = it)) },
                label = { Text(ledgerText("Получатель / описание", "Merchant / description")) },
                enabled = enabled,
                singleLine = true,
            )
            LedgerChoice(
                ledgerText("Категория", "Category"),
                form.categoryId.orEmpty(),
                listOf("" to ledgerText("Без категории", "Uncategorized")) +
                    state.categories.map { it.id to it.name },
                enabled,
            ) {
                controller.edit(form.copy(categoryId = it.ifBlank { null }))
            }
        }
        OutlinedTextField(
            form.date,
            { controller.edit(form.copy(date = it)) },
            label = { Text(ledgerText("Дата YYYY-MM-DD", "Date YYYY-MM-DD")) },
            enabled = enabled,
            singleLine = true,
        )
        if (source != null && source.currency != "MDL")
            OutlinedTextField(
                form.fxRate,
                { controller.edit(form.copy(fxRate = it)) },
                label = { Text("MDL / ${source.currency}") },
                enabled = enabled,
                singleLine = true,
            )
        OutlinedTextField(
            form.note,
            { controller.edit(form.copy(note = it)) },
            label = { Text(ledgerText("Примечание", "Note")) },
            enabled = enabled,
        )
        state.editorError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (state.editorRetryLocked) {
            Text(
                ledgerText(
                    "Сервер мог сохранить операцию. Сохранён прежний запрос: безопасный повтор использует тот же ключ.",
                    "The server may have saved this transaction. Safe retry sends the saved request with the same key.",
                )
            )
            TextButton(controller::load) { Text(ledgerText("Обновить историю", "Refresh history")) }
            TextButton(controller::retryPending) {
                Text(
                    ledgerText("Повторить тот же запрос безопасно", "Safely retry the same request")
                )
            }
        }
    }
}
