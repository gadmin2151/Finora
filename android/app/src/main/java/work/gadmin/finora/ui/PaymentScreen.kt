package work.gadmin.finora.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import work.gadmin.finora.data.*

@Composable
fun PaymentScreen(
    data: PaymentState,
    admin: Boolean,
    busy: Boolean,
    actions: PaymentController,
    changeMonth: (Long) -> Unit,
    selection: NativeEntitySelection? = null,
) {
    var confirmation by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    val form = data.editor
    BackHandler(form != null) { actions.closeEditor() }
    BackHandler(confirmation != null) { if (!busy) confirmation = null }
    LazyColumn(
        Modifier.fillMaxSize().testTag("payments-list"),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Text(
                paymentText("Регулярные платежи", "Recurring payments"),
                style = MaterialTheme.typography.headlineSmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(onClick = { changeMonth(-1) }, enabled = !busy) { Text("←") }
                Text(data.month)
                TextButton(onClick = { changeMonth(1) }, enabled = !busy) { Text("→") }
            }
            Text(
                paymentText(
                    "План не списывает деньги. Оплата записывается только после подтверждения.",
                    "A plan does not debit your accounts. Payments are recorded only after confirmation.",
                )
            )
            if (data.actionUncertain) {
                Text(
                    paymentText(
                        "Предыдущее действие требует проверки. Обновите список и проверьте результат перед новым действием.",
                        "A previous action requires review. Reload and check its result before another action.",
                    )
                )
                if (data.checkedAfterUncertain)
                    TextButton(
                        onClick = actions::acknowledgeCheckedResult,
                        enabled = !busy && !data.loading,
                        modifier = Modifier.testTag("payment-acknowledge"),
                    ) {
                        Text(paymentText("Проверил результат", "I checked the result"))
                    }
            }
            if (!admin)
                Text(
                    paymentText(
                        "Просмотр. Изменения доступны администратору семьи.",
                        "Read only. Changes require a family administrator.",
                    )
                )
            if (admin)
                Button(
                    onClick = { actions.open(PaymentEdit()) },
                    enabled = !busy && !data.loading,
                    modifier = Modifier.testTag("payment-create"),
                ) {
                    Text(paymentText("Создать платёж", "Create payment"))
                }
        }
        item {
            if (data.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            data.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            TextButton(
                onClick = actions::load,
                enabled = !busy && !data.loading,
                modifier = Modifier.testTag("payment-reload"),
            ) {
                Text(paymentText("Обновить", "Reload"))
            }
        }
        if (selection != null && selection != NativeEntitySelection.NoSelection)
            item { PaymentSelectionNotice(selection, data.templates) }
        if (data.occurrences.isEmpty() && !data.loading)
            item { Text(paymentText("В этом месяце платежей нет", "No payments this month")) }
        items(data.occurrences, key = { "occ-${it.id}" }) { row ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if ((selection as? NativeEntitySelection.Selected)?.id == row.bill_id)
                        Text(
                            paymentText("Платёж из уведомления", "Payment from notification"),
                            color = MaterialTheme.colorScheme.primary,
                        )
                    Text(row.name, style = MaterialTheme.typography.titleMedium)
                    Text("${editableMoney(row.amount_minor)} ${row.currency} · ${row.due_date}")
                    Text(
                        when (row.status) {
                            "paid" -> paymentText("Оплачен", "Paid")
                            "skipped" -> paymentText("Пропущен", "Skipped")
                            "overdue" -> paymentText("Просрочен", "Overdue")
                            else -> paymentText("Ожидается", "Upcoming")
                        }
                    )
                    if (admin && row.status != "paid") {
                        Button(
                            onClick = {
                                actions.open(
                                    PaymentEdit(
                                        occurrenceId = row.id,
                                        name = row.name,
                                        amount = editableMoney(row.amount_minor),
                                        currency = row.currency,
                                        accountId = row.account_id.orEmpty(),
                                        fxRate = if (row.currency == "MDL") "" else row.fx_rate,
                                    )
                                )
                            },
                            enabled = !busy,
                        ) {
                            Text(paymentText("Оплатить", "Pay"))
                        }
                        TextButton(
                            onClick = {
                                confirmation =
                                    paymentText(
                                        "Изменить статус этого платежа? Баланс не изменится.",
                                        "Change this payment's planned status? Account balances stay unchanged.",
                                    ) to { actions.skip(row) }
                            },
                            enabled = !busy && !data.actionUncertain,
                        ) {
                            Text(
                                if (row.status == "skipped")
                                    paymentText("Вернуть в план", "Restore to plan")
                                else paymentText("Пропустить", "Skip")
                            )
                        }
                    }
                }
            }
        }
        item {
            Text(paymentText("Шаблоны", "Templates"), style = MaterialTheme.typography.titleLarge)
            Text(
                paymentText(
                    "Изменение и удаление шаблонов пока не поддерживаются сервером. Пауза сохраняет историю.",
                    "The server does not support editing or deleting templates yet. Pausing preserves history.",
                )
            )
        }
        items(data.templates, key = { "tpl-${it.id}" }) { row ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Text(row.name)
                    Text(
                        "${editableMoney(row.amount_minor)} ${row.currency} · ${paymentRecurrence(row.recurrence)}"
                    )
                    Text(
                        if (row.active) paymentText("Активен", "Active")
                        else paymentText("На паузе", "Paused")
                    )
                    if (admin)
                        TextButton(
                            onClick = {
                                confirmation =
                                    (if (row.active)
                                        paymentText(
                                            "Приостановить шаблон? Неоплаченные платежи будут пропущены. История оплат сохранится.",
                                            "Pause this template? Unpaid occurrences will be skipped. Paid history is preserved.",
                                        )
                                    else paymentText("Возобновить шаблон?", "Resume template?")) to
                                        {
                                            actions.toggle(row)
                                        }
                            },
                            enabled = !busy && !data.actionUncertain,
                        ) {
                            Text(
                                if (row.active) paymentText("Пауза", "Pause")
                                else paymentText("Возобновить", "Resume")
                            )
                        }
                }
            }
        }
    }
    confirmation?.let { (text, action) ->
        AlertDialog(
            onDismissRequest = { if (!busy) confirmation = null },
            title = { Text(paymentText("Подтверждение", "Confirmation")) },
            text = { Text(text) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmation = null
                        action()
                    },
                    enabled = !busy,
                ) {
                    Text(paymentText("Подтвердить", "Confirm"))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmation = null }, enabled = !busy) {
                    Text(paymentText("Отмена", "Cancel"))
                }
            },
        )
    }
    if (form != null) PaymentEditor(form, data, busy, actions)
}

fun paymentRecurrence(value: String) =
    when (value) {
        "weekly" -> paymentText("Каждую неделю", "Weekly")
        "quarterly" -> paymentText("Каждый квартал", "Quarterly")
        "yearly" -> paymentText("Каждый год", "Yearly")
        "once" -> paymentText("Однократно", "Once")
        else -> paymentText("Каждый месяц", "Monthly")
    }

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PaymentEditor(
    form: PaymentEdit,
    data: PaymentState,
    busy: Boolean,
    actions: PaymentController,
) {
    var confirm by remember(form.key) { mutableStateOf(false) }
    val locked = busy || data.retryLocked
    val paying = form.occurrenceId != null
    AlertDialog(
        onDismissRequest = actions::closeEditor,
        modifier = Modifier.testTag("payment-editor"),
        title = {
            Text(
                if (paying) paymentText("Оплата: ${form.name}", "Pay: ${form.name}")
                else paymentText("Новый платёж", "New payment")
            )
        },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (!paying)
                    item {
                        OutlinedTextField(
                            form.name,
                            { actions.edit(form.copy(name = it)) },
                            label = { Text(paymentText("Название", "Name")) },
                            enabled = !locked,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                item {
                    OutlinedTextField(
                        form.amount,
                        { actions.edit(form.copy(amount = it)) },
                        label = { Text(paymentText("Сумма", "Amount")) },
                        enabled = !locked,
                        modifier = Modifier.fillMaxWidth().testTag("payment-amount"),
                    )
                    OutlinedTextField(
                        form.date,
                        { actions.edit(form.copy(date = it)) },
                        label = { Text(paymentText("Дата YYYY-MM-DD", "Date YYYY-MM-DD")) },
                        enabled = !locked,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (!paying)
                    item {
                        Text(paymentText("Валюта", "Currency"))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("MDL", "EUR", "USD", "RON").forEach { currency ->
                                FilterChip(
                                    form.currency == currency,
                                    {
                                        actions.edit(
                                            form.copy(
                                                currency = currency,
                                                accountId =
                                                    data.accounts
                                                        .firstOrNull { it.currency == currency }
                                                        ?.id
                                                        .orEmpty(),
                                                fxRate = "",
                                            )
                                        )
                                    },
                                    label = { Text(currency) },
                                    enabled = !locked,
                                )
                            }
                        }
                    }
                item {
                    Text(paymentText("Счёт оплаты", "Payment account"))
                    data.accounts
                        .filter { it.currency == form.currency }
                        .forEach { account ->
                            FilterChip(
                                form.accountId == account.id,
                                { actions.edit(form.copy(accountId = account.id)) },
                                label = { Text(account.name) },
                                enabled = !locked,
                            )
                        }
                    if (data.accounts.none { it.currency == form.currency })
                        Text(
                            paymentText(
                                "Нет активного счёта в этой валюте",
                                "No active account in this currency",
                            ),
                            color = MaterialTheme.colorScheme.error,
                        )
                }
                if (form.currency != "MDL")
                    item {
                        OutlinedTextField(
                            form.fxRate,
                            { actions.edit(form.copy(fxRate = it)) },
                            label = {
                                Text(
                                    paymentText(
                                        "Курс MDL за единицу валюты",
                                        "MDL per currency unit",
                                    )
                                )
                            },
                            enabled = !locked,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                if (!paying) {
                    item {
                        Text(paymentText("Периодичность", "Recurrence"))
                        listOf("monthly", "weekly", "quarterly", "yearly", "once").forEach { value
                            ->
                            FilterChip(
                                form.recurrence == value,
                                { actions.edit(form.copy(recurrence = value)) },
                                label = { Text(paymentRecurrence(value)) },
                                enabled = !locked,
                            )
                        }
                    }
                    item {
                        Text(paymentText("Категория (необязательно)", "Category (optional)"))
                        FilterChip(
                            form.categoryId.isBlank(),
                            { actions.edit(form.copy(categoryId = "")) },
                            label = { Text(paymentText("Без категории", "No category")) },
                            enabled = !locked,
                        )
                        data.categories.forEach { category ->
                            FilterChip(
                                form.categoryId == category.id,
                                { actions.edit(form.copy(categoryId = category.id)) },
                                label = { Text(category.name) },
                                enabled = !locked,
                            )
                        }
                    }
                }
                if (paying)
                    item {
                        Text(
                            paymentText(
                                "Уже записали расход? Найдите его, чтобы не списывать деньги повторно.",
                                "Already recorded this expense? Link it to avoid debiting money twice.",
                            )
                        )
                        TextButton(
                            onClick = actions::findMatches,
                            enabled = !locked && !data.matching,
                        ) {
                            Text(
                                paymentText(
                                    "Найти расход с тем же счётом, датой и суммой",
                                    "Find expense with same account, date and amount",
                                )
                            )
                        }
                        FilterChip(
                            form.transactionId == null,
                            { actions.edit(form.copy(transactionId = null)) },
                            label = {
                                Text(paymentText("Записать новую оплату", "Record a new payment"))
                            },
                            enabled = !locked,
                        )
                        data.matches.forEach { tx ->
                            FilterChip(
                                form.transactionId == tx.id,
                                { actions.edit(form.copy(transactionId = tx.id)) },
                                label = {
                                    Text(
                                        "${tx.merchant} · ${tx.occurred_on} · ${editableMoney(tx.amount_minor)} ${tx.currency}"
                                    )
                                },
                                enabled = !locked,
                            )
                        }
                        if (data.matchesLoaded && data.matches.isEmpty())
                            Text(
                                paymentText(
                                    "Совпадающих расходов не найдено",
                                    "No matching expenses found",
                                )
                            )
                    }
                item {
                    data.editorError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    if (data.retryLocked)
                        Text(
                            paymentText(
                                "Поля заблокированы для безопасного повтора. Не создавайте дубликат до проверки списка.",
                                "Fields are locked for a safe retry. Check the list before creating a duplicate.",
                            )
                        )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { confirm = true },
                enabled = !busy && !(data.retryLocked && !paying),
                modifier = Modifier.testTag("payment-review"),
            ) {
                Text(paymentText("Проверить", "Review"))
            }
        },
        dismissButton = {
            TextButton(
                onClick = actions::closeEditor,
                enabled = !busy,
                modifier = Modifier.testTag("payment-cancel"),
            ) {
                Text(paymentText("Отмена", "Cancel"))
            }
        },
    )
    if (confirm)
        AlertDialog(
            onDismissRequest = { if (!busy) confirm = false },
            title = { Text(paymentText("Подтвердить действие", "Confirm action")) },
            text = {
                Text(
                    "${form.name}\n${form.amount} ${form.currency}\n${form.date}\n${data.accounts.firstOrNull{it.id==form.accountId}?.name.orEmpty()}\n" +
                        if (paying && form.transactionId == null)
                            paymentText(
                                "Будет записан новый расход и изменён баланс счёта.",
                                "A new expense will be recorded and account balance will change.",
                            )
                        else if (paying)
                            paymentText(
                                "Будет привязан существующий расход без повторного списания.",
                                "The existing expense will be linked without debiting again.",
                            )
                        else
                            paymentText(
                                "Будет создан план без списания денег.",
                                "A plan will be created without debiting money.",
                            )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirm = false
                        actions.submit()
                    },
                    enabled = !busy,
                    modifier = Modifier.testTag("payment-confirm"),
                ) {
                    Text(paymentText("Подтвердить", "Confirm"))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirm = false }, enabled = !busy) {
                    Text(paymentText("Назад", "Back"))
                }
            },
        )
}

@Composable
fun PaymentSelectionNotice(selection: NativeEntitySelection, templates: List<PaymentTemplate>) {
    when (selection) {
        is NativeEntitySelection.Selected -> {
            val template = templates.firstOrNull { it.id == selection.id }
            if (template != null)
                Text(
                    paymentText("Выбранный платёж: ", "Selected payment: ") + template.name,
                    Modifier.testTag("payment-native-selection"),
                )
        }
        NativeEntitySelection.Loading ->
            Text(paymentText("Загружаем платёж из уведомления…", "Loading the selected payment…"))
        NativeEntitySelection.NotFound ->
            Text(
                paymentText(
                    "Платёж больше недоступен в этой организации.",
                    "This payment is no longer available in this organization.",
                )
            )
        NativeEntitySelection.StaleScope ->
            Text(
                paymentText(
                    "Выбор платежа относится к другому периоду или сессии.",
                    "Payment selection belongs to another period or session.",
                )
            )
        else -> Unit
    }
}
