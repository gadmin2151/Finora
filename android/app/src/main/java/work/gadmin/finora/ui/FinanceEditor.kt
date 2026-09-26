package work.gadmin.finora.ui

import android.app.DatePickerDialog
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.time.LocalDate
import java.time.ZoneId
import work.gadmin.finora.data.*

@Composable
fun FinanceEditor(
    form: FinanceEdit,
    data: FinanceState,
    organization: String,
    busy: Boolean,
    change: (FinanceEdit) -> Unit,
    save: () -> Unit,
    close: () -> Unit,
    findMatches: () -> Unit,
) {
    var discard by remember { mutableStateOf(false) }
    val enabled = !busy
    val existing = form.kind == FinanceEditKind.DEBT && form.mode == "existing"
    val fixedCurrency = form.kind in setOf(FinanceEditKind.RECEIVE, FinanceEditKind.REPAY)
    val accounts = data.accounts.filter { it.currency == form.currency && !it.archived }
    Dialog(
        onDismissRequest = { if (enabled) discard = true },
        properties =
            DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Scaffold(
            containerColor = Paper,
            modifier = Modifier.fillMaxSize().safeDrawingPadding().imePadding(),
            topBar = {
                Row(
                    Modifier.fillMaxWidth().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton({ discard = true }, enabled = enabled) {
                        LineIcon(Glyph.BACK, "Закрыть форму")
                    }
                    Column {
                        Text(form.title, style = MaterialTheme.typography.titleLarge)
                        Text(
                            organization,
                            color = Muted,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            },
            bottomBar = {
                Surface(color = SurfaceColor) {
                    Column(
                        Modifier.fillMaxWidth().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        data.editorError?.let {
                            Text(
                                it,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        PrimaryButton(
                            if (busy) "Сохраняем…"
                            else
                                when (form.kind) {
                                    FinanceEditKind.PLAN -> "Сохранить источник"
                                    FinanceEditKind.DEBT -> "Сохранить долг"
                                    FinanceEditKind.REPAY -> "Подтвердить возврат"
                                    FinanceEditKind.RECEIVE -> "Подтвердить поступление"
                                    FinanceEditKind.INCOME -> "Сохранить доход"
                                },
                            save,
                            Modifier.fillMaxWidth(),
                            enabled = enabled && !data.matchesLoading,
                        )
                    }
                }
            },
        ) { padding ->
            Column(
                Modifier.fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                when (form.kind) {
                    FinanceEditKind.PLAN ->
                        InfoCard(
                            "План не меняет баланс. Отметьте поступление как полученное, когда деньги придут.",
                            Glyph.WALLET,
                        )
                    FinanceEditKind.REPAY ->
                        InfoCard(
                            "${form.name}\nОсталось ${money(form.remainingMinor ?: 0, form.currency)}. Можно вернуть часть суммы.",
                            Glyph.USER,
                        )
                    FinanceEditKind.RECEIVE ->
                        InfoCard(
                            "${form.name}\nУкажите фактические сумму и дату поступления.",
                            Glyph.WALLET,
                        )
                    else -> Unit
                }
                if (form.kind == FinanceEditKind.DEBT) {
                    FinanceChoice(
                        "Направление",
                        form.direction,
                        listOf("lent" to "Я дал в долг", "borrowed" to "Я взял в долг"),
                        enabled,
                    ) {
                        change(form.copy(direction = it))
                    }
                }
                if (
                    form.kind in
                        setOf(FinanceEditKind.INCOME, FinanceEditKind.PLAN, FinanceEditKind.DEBT)
                ) {
                    FinanceText(
                        when (form.kind) {
                            FinanceEditKind.DEBT -> "Имя человека"
                            FinanceEditKind.PLAN -> "Название источника"
                            else -> "Откуда поступили деньги"
                        },
                        form.name,
                        enabled,
                        max = if (form.kind == FinanceEditKind.INCOME) 200 else 100,
                    ) {
                        change(form.copy(name = it))
                    }
                }
                FinanceText(
                    "Сумма · ${form.currency}",
                    form.amount,
                    enabled,
                    number = true,
                    max = 24,
                ) {
                    change(form.copy(amount = it))
                }
                if (!fixedCurrency)
                    FinanceChoice(
                        "Валюта",
                        form.currency,
                        listOf("MDL", "EUR", "USD", "RON").map { it to it },
                        enabled,
                    ) {
                        change(
                            form.copy(
                                currency = it,
                                accountId = validFinanceAccount(data.accounts, it),
                                fxRate = "",
                            )
                        )
                    }
                if (form.kind == FinanceEditKind.DEBT) {
                    FinanceChoice(
                        "Как учитывать",
                        form.mode,
                        listOf(
                            "new" to "Деньги передаются сейчас",
                            "existing" to "Ранее существовавший долг",
                        ),
                        enabled,
                    ) {
                        change(form.copy(mode = it))
                    }
                    Text(
                        if (existing) "Баланс счёта не изменится: деньги уже были переданы раньше."
                        else if (form.direction == "lent") "Сумма будет списана с выбранного счёта."
                        else "Сумма поступит на выбранный счёт.",
                        color = Muted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (!existing) {
                    FinanceChoice(
                        if (form.kind == FinanceEditKind.REPAY && form.direction == "borrowed")
                            "Списать со счёта"
                        else "Счёт",
                        form.accountId,
                        accounts.map { it.id to "${it.name} · ${it.currency}" },
                        enabled,
                    ) {
                        change(form.copy(accountId = it))
                    }
                    if (accounts.isEmpty())
                        InfoCard(
                            "Нет действующего счёта в ${form.currency}. Создайте его в веб-версии или выберите другую валюту.",
                            Glyph.WALLET,
                        )
                    if (form.currency != "MDL")
                        FinanceText(
                            "Курс: 1 ${form.currency} в MDL",
                            form.fxRate,
                            enabled,
                            number = true,
                            max = 18,
                        ) {
                            change(form.copy(fxRate = it))
                        }
                }
                FinanceDate(
                    if (form.kind == FinanceEditKind.PLAN) "Первое поступление"
                    else "Дата операции",
                    form.date,
                    enabled,
                    future = form.kind == FinanceEditKind.PLAN,
                ) {
                    change(form.copy(date = it))
                }
                if (form.kind == FinanceEditKind.PLAN) {
                    FinanceChoice(
                        "Повторять",
                        form.recurrence,
                        incomeRecurrences.toList(),
                        enabled,
                    ) {
                        change(form.copy(recurrence = it))
                    }
                    Text(
                        "Для 29–31 числа в коротком месяце используется последний день. Полученные суммы сохраняются при изменении плана.",
                        color = Muted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (form.kind == FinanceEditKind.DEBT)
                    FinanceDate(
                        "Вернуть до · необязательно",
                        form.dueDate,
                        enabled,
                        future = true,
                        optional = true,
                    ) {
                        change(form.copy(dueDate = it))
                    }
                if (form.kind in setOf(FinanceEditKind.INCOME, FinanceEditKind.DEBT)) {
                    OutlinedTextField(
                        form.note,
                        { change(form.copy(note = it.take(3000))) },
                        Modifier.fillMaxWidth(),
                        enabled = enabled,
                        label = { Text("Примечание") },
                        minLines = 2,
                        maxLines = 5,
                        shape = RoundedCornerShape(16.dp),
                    )
                }
                if (form.kind == FinanceEditKind.RECEIVE) {
                    HorizontalDivider(color = Border)
                    Text("Доход уже внесён?", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Можно связать поступление с записью на тот же счёт, с той же суммой и датой. Баланс повторно не увеличится.",
                        color = Muted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedButton(
                        findMatches,
                        Modifier.fillMaxWidth(),
                        enabled = enabled && !data.matchesLoading,
                    ) {
                        Text(
                            if (data.matchesLoading) "Ищем зачисления…"
                            else "Найти уже внесённый доход"
                        )
                    }
                    if (data.matchesLoaded) {
                        if (data.matches.isEmpty())
                            Text(
                                "Совпадений за выбранный месяц не найдено среди последних 200 поступлений.",
                                color = Muted,
                            )
                        else
                            FinanceChoice(
                                "Зачисление",
                                form.transactionId.orEmpty(),
                                listOf("" to "Создать новое поступление") +
                                    data.matches.map {
                                        it.id to
                                            "${it.merchant.ifBlank { "Доход" }} · ${money(it.amount_minor, it.currency)}"
                                    },
                                enabled,
                            ) {
                                change(form.copy(transactionId = it.takeIf(String::isNotBlank)))
                            }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
        }
        if (discard)
            AlertDialog(
                onDismissRequest = { discard = false },
                title = { Text("Закрыть без сохранения?") },
                text = { Text("Несохранённые изменения в этой форме будут потеряны.") },
                confirmButton = {
                    TextButton(
                        {
                            discard = false
                            close()
                        },
                        enabled = enabled,
                    ) {
                        Text("Закрыть")
                    }
                },
                dismissButton = { TextButton({ discard = false }) { Text("Продолжить") } },
            )
    }
}

@Composable
private fun FinanceText(
    label: String,
    value: String,
    enabled: Boolean,
    number: Boolean = false,
    max: Int,
    change: (String) -> Unit,
) {
    OutlinedTextField(
        value,
        { change(it.take(max)) },
        Modifier.fillMaxWidth(),
        enabled = enabled,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions =
            KeyboardOptions(keyboardType = if (number) KeyboardType.Decimal else KeyboardType.Text),
        shape = RoundedCornerShape(16.dp),
    )
}

@Composable
private fun FinanceChoice(
    label: String,
    value: String,
    choices: List<Pair<String, String>>,
    enabled: Boolean,
    change: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, color = Muted, style = MaterialTheme.typography.labelLarge)
        Box {
            OutlinedButton(
                { expanded = true },
                Modifier.fillMaxWidth().heightIn(min = 52.dp),
                enabled = enabled && choices.isNotEmpty(),
                shape = RoundedCornerShape(16.dp),
            ) {
                Text(
                    choices.firstOrNull { it.first == value }?.second ?: "Выберите",
                    Modifier.weight(1f),
                )
                LineIcon(Glyph.DOWN, size = 18.dp)
            }
            DropdownMenu(
                expanded && enabled,
                { expanded = false },
                Modifier.heightIn(max = 360.dp),
            ) {
                choices.forEach { (key, name) ->
                    DropdownMenuItem(
                        text = { Text(name) },
                        onClick = {
                            expanded = false
                            change(key)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun FinanceDate(
    label: String,
    value: String,
    enabled: Boolean,
    future: Boolean,
    optional: Boolean = false,
    change: (String) -> Unit,
) {
    val context = LocalContext.current
    Column {
        Text(label, color = Muted, style = MaterialTheme.typography.labelLarge)
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(
                {
                    val initial = runCatching {
                        LocalDate.parse(value)
                    }
                        .getOrDefault(LocalDate.now())
                    DatePickerDialog(
                            context,
                            { _, year, month, day ->
                                change(LocalDate.of(year, month + 1, day).toString())
                            },
                            initial.year,
                            initial.monthValue - 1,
                            initial.dayOfMonth,
                        )
                        .apply {
                            datePicker.minDate =
                                LocalDate.of(1990, 1, 1)
                                    .atStartOfDay(ZoneId.systemDefault())
                                    .toInstant()
                                    .toEpochMilli()
                            datePicker.maxDate =
                                (if (future) LocalDate.of(2100, 12, 31) else LocalDate.now())
                                    .atStartOfDay(ZoneId.systemDefault())
                                    .toInstant()
                                    .toEpochMilli()
                        }
                        .show()
                },
                Modifier.weight(1f).heightIn(min = 52.dp),
                enabled = enabled,
                shape = RoundedCornerShape(16.dp),
            ) {
                Text(if (value.isBlank()) "Не указан" else financeDateLabel(value))
            }
            if (optional && value.isNotBlank())
                IconButton({ change("") }, enabled = enabled) {
                    LineIcon(Glyph.CLOSE, "Убрать срок возврата")
                }
        }
    }
}
