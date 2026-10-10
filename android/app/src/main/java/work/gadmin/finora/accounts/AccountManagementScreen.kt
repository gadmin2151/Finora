package work.gadmin.finora.accounts

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import work.gadmin.finora.data.*

@Composable
fun AccountManagementScreen(
    data: AccountManagementState,
    busy: Boolean,
    actions: AccountManagementController,
) {
    val admin = data.scope?.admin == true
    val combined = data.scope?.combined == true
    val locked = busy || data.writing || data.loading || !data.loaded
    LazyColumn(
        Modifier.fillMaxSize().testTag("native-accounts"),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(accountText("Счета", "Accounts"), style = MaterialTheme.typography.headlineMedium)
            Text(
                accountText(
                    "Баланс учитывает операции. Начальный остаток хранится отдельно.",
                    "Balances include transactions. Opening balances are stored separately.",
                )
            )
            if (combined)
                Text(
                    accountText(
                        "Режим «Всё вместе»: создание счёта доступно после включения раздельного учёта в настройках.",
                        "Combined accounting: enable separate accounts in settings before creating an account.",
                    )
                )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    { actions.open() },
                    enabled = admin && !combined && !locked && !data.uncertain,
                ) {
                    Text(accountText("Новый счёт", "New account"))
                }
                OutlinedButton(actions::load, enabled = !busy && !data.writing && !data.loading) {
                    Text(accountText("Обновить", "Refresh"))
                }
            }
            if (!admin)
                Text(
                    accountText(
                        "Только просмотр: изменение счетов доступно администратору",
                        "Read only: account changes require an administrator",
                    )
                )
            if (data.loaded && data.accounts.isEmpty())
                Text(accountText("Счетов пока нет", "No accounts yet"))
            if (data.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            data.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (data.uncertain)
                Text(
                    accountText(
                        "Ответ не подтверждён. Обновите список и проверьте результат.",
                        "Result unconfirmed. Refresh the list and verify the result.",
                    )
                )
            data.pendingDescription?.let { Text(it) }
            if (data.uncertain)
                OutlinedButton(
                    actions::acknowledgeCheckedResult,
                    enabled =
                        data.checkedAfterUncertain &&
                            !data.guardUnavailable &&
                            !locked &&
                            !data.loading,
                ) {
                    Text(
                        accountText(
                            "Проверил результат в списке",
                            "I checked the result in the list",
                        )
                    )
                }
        }
        items(data.accounts, key = { it.id }) { row ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(row.name, style = MaterialTheme.typography.titleMedium)
                    Text(money(row.balanceMinor, row.currency))
                    Text(
                        accountText("Начальный остаток: ", "Opening balance: ") +
                            money(row.openingMinor, row.currency)
                    )
                    Text(
                        row.currency +
                            " · " +
                            row.kind +
                            if (row.archived) accountText(" · архив", " · archived") else ""
                    )
                    if (admin)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(
                                { actions.open(row) },
                                enabled = !locked && !data.uncertain && !row.archived,
                            ) {
                                Text(accountText("Изменить", "Edit"))
                            }
                            TextButton(
                                { actions.archive(row) },
                                enabled = !locked && !data.uncertain,
                            ) {
                                Text(
                                    if (row.archived) accountText("Восстановить", "Restore")
                                    else accountText("В архив", "Archive")
                                )
                            }
                        }
                }
            }
        }
    }
    data.editor
        ?.takeIf { data.confirmation == null }
        ?.let { form ->
            BackHandler {
                if (!locked) actions.closeEditor()
            }
            AlertDialog(
                onDismissRequest = { if (!locked) actions.closeEditor() },
                title = {
                    Text(
                        if (form.id == null) accountText("Новый счёт", "New account")
                        else accountText("Изменить счёт", "Edit account")
                    )
                },
                text = {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        item {
                            OutlinedTextField(
                                form.name,
                                { actions.edit(form.copy(name = it)) },
                                label = { Text(accountText("Название", "Name")) },
                                enabled = !locked && !data.uncertain,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        item {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                listOf("cash", "card", "savings").forEach { kind ->
                                    FilterChip(
                                        form.kind == kind,
                                        { actions.edit(form.copy(kind = kind)) },
                                        label = {
                                            Text(
                                                when (kind) {
                                                    "cash" -> accountText("Наличные", "Cash")
                                                    "card" -> accountText("Карта", "Card")
                                                    else -> accountText("Накопления", "Savings")
                                                }
                                            )
                                        },
                                        enabled = !locked && !data.uncertain,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }
                            }
                        }
                        item {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                listOf("MDL", "EUR", "USD", "RON").forEach { currency ->
                                    FilterChip(
                                        form.currency == currency,
                                        { actions.edit(form.copy(currency = currency)) },
                                        label = { Text(currency) },
                                        enabled = !locked && !data.uncertain && form.id == null,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }
                            }
                        }
                        item {
                            OutlinedTextField(
                                form.openingBalance,
                                { actions.edit(form.copy(openingBalance = it)) },
                                label = {
                                    Text(accountText("Начальный остаток", "Opening balance"))
                                },
                                enabled = !locked && !data.uncertain,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        item {
                            Text(
                                accountText(
                                    "Изменение начального остатка влияет на баланс. Оно не является доходом или расходом.",
                                    "Changing the opening balance affects the balance. It is not income or an expense.",
                                )
                            )
                        }
                        item {
                            OutlinedTextField(
                                form.color,
                                { actions.edit(form.copy(color = it)) },
                                label = { Text(accountText("Цвет #RRGGBB", "Color #RRGGBB")) },
                                enabled = !locked && !data.uncertain,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        data.editorError?.let {
                            item { Text(it, color = MaterialTheme.colorScheme.error) }
                        }
                    }
                },
                confirmButton = {
                    TextButton({ actions.prepare() }, enabled = !locked && !data.uncertain) {
                        Text(accountText("Проверить", "Review"))
                    }
                },
                dismissButton = {
                    TextButton(actions::closeEditor, enabled = !locked) {
                        Text(accountText("Отмена", "Cancel"))
                    }
                },
            )
        }
    data.confirmation?.let { confirmation ->
        BackHandler { if (!locked) actions.cancelConfirmation() }
        AlertDialog(
            onDismissRequest = { if (!locked) actions.cancelConfirmation() },
            title = { Text(accountText("Подтвердить изменение", "Confirm change")) },
            text = { Text(confirmation.description) },
            confirmButton = {
                TextButton(actions::confirm, enabled = !locked && !data.uncertain) {
                    Text(accountText("Подтвердить", "Confirm"))
                }
            },
            dismissButton = {
                TextButton(actions::cancelConfirmation, enabled = !locked) {
                    Text(accountText("Отмена", "Cancel"))
                }
            },
        )
    }
}
