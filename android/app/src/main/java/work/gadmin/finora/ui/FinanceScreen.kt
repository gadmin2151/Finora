package work.gadmin.finora.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale
import work.gadmin.finora.AppState
import work.gadmin.finora.FinoraViewModel
import work.gadmin.finora.data.*

@Composable
fun FinanceScreen(app: AppState, vm: FinoraViewModel) {
    val data by vm.finance.state.collectAsStateWithLifecycle()
    FinanceContent(data, app.organization?.isAdmin == true, app.busy, vm.finance, vm::month)
    data.editor?.let {
        FinanceEditor(
            it,
            data,
            app.organization?.name.orEmpty(),
            app.busy,
            vm.finance::edit,
            vm.finance::submit,
            vm.finance::closeEditor,
            vm.finance::findMatches,
        )
    }
}

@Composable
fun FinanceContent(
    data: FinanceState,
    admin: Boolean,
    busy: Boolean,
    actions: FinanceController,
    changeMonth: (Long) -> Unit,
) {
    var incomeSection by rememberSaveable { mutableStateOf("schedule") }
    var direction by rememberSaveable { mutableStateOf("all") }
    var closed by rememberSaveable { mutableStateOf(false) }
    var search by rememberSaveable { mutableStateOf("") }
    var cancelling by remember { mutableStateOf<FinanceTransaction?>(null) }
    fun fresh(kind: FinanceEditKind): FinanceEdit {
        val account =
            data.accounts.firstOrNull { it.currency == "MDL" } ?: data.accounts.firstOrNull()
        return FinanceEdit(
            kind,
            accountId = account?.id.orEmpty(),
            currency = account?.currency ?: "MDL",
        )
    }
    LazyColumn(
        Modifier.fillMaxSize().testTag("finance-list"),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            SectionTitle("Ваши финансы", "Доходы и долги в одном месте")
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf(FinanceTab.INCOME to "Доходы", FinanceTab.DEBTS to "Долги").forEach {
                    (tab, title) ->
                    FilterChip(
                        data.tab == tab,
                        { actions.tab(tab) },
                        { Text(title) },
                        Modifier.weight(1f),
                        enabled = !busy,
                        leadingIcon = {
                            LineIcon(
                                if (tab == FinanceTab.INCOME) Glyph.WALLET else Glyph.USER,
                                size = 18.dp,
                            )
                        },
                    )
                }
            }
        }
        if (!admin)
            item {
                InfoCard(
                    "Вы можете смотреть общие финансы. Изменения доступны администратору организации."
                )
            }
        data.error?.let { error ->
            item {
                InfoCard(error, Glyph.REFRESH)
                TextButton(actions::load, enabled = !busy) { Text("Обновить финансы") }
            }
        }
        if (data.loading) item { BrandLoading("Обновляем доходы и долги", compact = true) }
        if (data.tab == FinanceTab.INCOME) {
            item {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    IconButton({ changeMonth(-1) }, enabled = !busy) {
                        LineIcon(Glyph.BACK, "Предыдущий месяц")
                    }
                    Text(
                        if (data.month.isBlank()) "Загрузка…"
                        else
                            YearMonth.parse(data.month)
                                .format(
                                    DateTimeFormatter.ofPattern(
                                        "LLLL yyyy",
                                        Locale.forLanguageTag("ru"),
                                    )
                                )
                                .replaceFirstChar(Char::titlecase),
                        fontWeight = FontWeight.SemiBold,
                    )
                    IconButton({ changeMonth(1) }, enabled = !busy) {
                        LineIcon(Glyph.CHEVRON, "Следующий месяц")
                    }
                }
            }
            data.report?.let { report ->
                item {
                    Surface(color = HeroStart, shape = RoundedCornerShape(24.dp)) {
                        Column(
                            Modifier.fillMaxWidth().padding(22.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text("Получено за месяц", color = Mint)
                            Text(
                                money(report.received_minor),
                                style = MaterialTheme.typography.headlineMedium,
                                color = Mint,
                            )
                            HorizontalDivider(color = Border)
                            FinanceMetric("Регулярные", money(report.regular_minor))
                            FinanceMetric("Разовые", money(report.occasional_minor))
                            FinanceMetric("Ожидается по плану", money(report.expected_minor))
                            Text(
                                "Сводка в MDL по курсам операций",
                                color = Muted,
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                }
            }
            if (admin)
                item {
                    PrimaryButton(
                        "Разовый доход",
                        { actions.open(fresh(FinanceEditKind.INCOME)) },
                        Modifier.fillMaxWidth(),
                        enabled = !busy && !data.loading,
                        icon = Glyph.PLUS,
                    )
                    OutlinedButton(
                        { actions.open(fresh(FinanceEditKind.PLAN)) },
                        Modifier.fillMaxWidth().padding(top = 8.dp),
                        enabled = !busy && !data.loading,
                    ) {
                        Text("Добавить источник дохода")
                    }
                }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("schedule" to "План", "sources" to "Источники", "history" to "История")
                        .forEach { (key, title) ->
                            FilterChip(
                                incomeSection == key,
                                { incomeSection = key },
                                { Text(title) },
                                Modifier.weight(1f),
                            )
                        }
                }
            }
            when (incomeSection) {
                "schedule" -> {
                    val rows = data.report?.occurrences.orEmpty()
                    if (!data.loading && data.report != null && rows.isEmpty())
                        item {
                            EmptyState(
                                "В этом месяце нет плана",
                                "Добавьте зарплату, аренду или другой источник дохода.",
                                Glyph.WALLET,
                            )
                        }
                    items(rows, key = { it.id }) { row ->
                        FinanceCard {
                            Text(row.name, style = MaterialTheme.typography.titleMedium)
                            FinanceMetric(
                                financeDateLabel(row.due_date),
                                money(row.received_minor ?: row.amount_minor, row.currency),
                            )
                            Text(
                                when (row.status) {
                                    "received" ->
                                        "Получено ${row.received_on?.let(::financeDateLabel).orEmpty()}"
                                    "skipped" -> "Пропущено"
                                    "paused" -> "Источник приостановлен"
                                    "overdue" -> "Пока не получено · срок прошёл"
                                    else -> "Ожидается"
                                },
                                color = if (row.status == "overdue") Amber else Muted,
                                style = MaterialTheme.typography.bodySmall,
                            )
                            if (admin && row.status in setOf("upcoming", "overdue", "skipped")) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    if (row.status != "skipped")
                                        TextButton(
                                            {
                                                actions.open(
                                                    FinanceEdit(
                                                        FinanceEditKind.RECEIVE,
                                                        id = row.id,
                                                        name = row.name,
                                                        amount = editableMoney(row.amount_minor),
                                                        currency = row.currency,
                                                        accountId =
                                                            validFinanceAccount(
                                                                data.accounts,
                                                                row.currency,
                                                                row.account_id,
                                                            ),
                                                        fxRate = row.fx_rate,
                                                    )
                                                )
                                            },
                                            enabled = !busy,
                                        ) {
                                            Text("Получить")
                                        }
                                    TextButton({ actions.skip(row) }, enabled = !busy) {
                                        Text(
                                            if (row.status == "skipped") "Вернуть в план"
                                            else "Пропустить"
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                "sources" -> {
                    if (!data.loading && data.plans.isEmpty())
                        item {
                            EmptyState(
                                "Нет источников дохода",
                                "Создайте расписание. Деньги зачисляются только после подтверждения.",
                                Glyph.WALLET,
                            )
                        }
                    items(data.plans, key = { it.id }) { plan ->
                        FinanceCard {
                            Text(plan.name, style = MaterialTheme.typography.titleMedium)
                            Text(
                                money(plan.amount_minor, plan.currency),
                                color = Mint,
                                style = MaterialTheme.typography.titleLarge,
                            )
                            Text(
                                "${incomeRecurrences[plan.recurrence] ?: plan.recurrence} · с ${financeDateLabel(plan.start_date)}",
                                color = Muted,
                            )
                            Text(
                                if (plan.active) "Активен" else "Приостановлен",
                                color = if (plan.active) Mint else Muted,
                            )
                            if (admin)
                                Row {
                                    TextButton(
                                        {
                                            actions.open(
                                                FinanceEdit(
                                                    FinanceEditKind.PLAN,
                                                    plan.id,
                                                    plan.version,
                                                    plan.name,
                                                    editableMoney(plan.amount_minor),
                                                    plan.currency,
                                                    validFinanceAccount(
                                                        data.accounts,
                                                        plan.currency,
                                                        plan.account_id,
                                                    ),
                                                    date = plan.start_date,
                                                    fxRate = plan.fx_rate,
                                                    recurrence = plan.recurrence,
                                                )
                                            )
                                        },
                                        enabled = !busy,
                                    ) {
                                        Text("Изменить")
                                    }
                                    TextButton({ actions.toggle(plan) }, enabled = !busy) {
                                        Text(if (plan.active) "Пауза" else "Возобновить")
                                    }
                                }
                        }
                    }
                }
                "history" -> {
                    if (!data.loading && data.history.isEmpty())
                        item {
                            EmptyState(
                                "Поступлений пока нет",
                                "Добавьте разовый доход или подтвердите поступление по плану.",
                                Glyph.WALLET,
                            )
                        }
                    items(data.history, key = { it.id }) { tx ->
                        FinanceCard {
                            Text(
                                tx.merchant.ifBlank { "Доход" },
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                "+${money(tx.amount_minor, tx.currency)}",
                                color = Mint,
                                style = MaterialTheme.typography.titleLarge,
                            )
                            Text(
                                "${financeDateLabel(tx.occurred_on)} · ${if (tx.occurrence_id == null) "Разовое поступление" else "По плану"}",
                                color = Muted,
                            )
                            if (tx.note.isNotBlank())
                                Text(tx.note, style = MaterialTheme.typography.bodySmall)
                            if (admin)
                                Row {
                                    if (tx.occurrence_id == null)
                                        TextButton(
                                            {
                                                actions.open(
                                                    FinanceEdit(
                                                        FinanceEditKind.INCOME,
                                                        id = tx.id,
                                                        version = tx.version,
                                                        name = tx.merchant,
                                                        amount = editableMoney(tx.amount_minor),
                                                        currency = tx.currency,
                                                        accountId = tx.account_id,
                                                        date = tx.occurred_on,
                                                        fxRate = tx.fx_rate,
                                                        note = tx.note,
                                                    )
                                                )
                                            },
                                            enabled = !busy,
                                        ) {
                                            Text("Изменить")
                                        }
                                    TextButton({ cancelling = tx }, enabled = !busy) {
                                        Text("Отменить зачисление", color = Amber)
                                    }
                                }
                        }
                    }
                    if (data.history.size < data.historyCount)
                        item {
                            OutlinedButton(
                                actions::moreHistory,
                                Modifier.fillMaxWidth(),
                                enabled = !data.loadingMore && !busy,
                            ) {
                                Text(
                                    if (data.loadingMore) "Загружаем…"
                                    else
                                        "Ещё поступления · ${data.history.size} из ${data.historyCount}"
                                )
                            }
                        }
                }
            }
        } else {
            item {
                FinanceCard {
                    FinanceMetric("Мне должны", debtTotal(data.debts, "lent"))
                    HorizontalDivider(color = Border)
                    FinanceMetric("Я должен", debtTotal(data.debts, "borrowed"))
                    Text(
                        "Долги и возвраты не считаются доходами или расходами",
                        color = Muted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            if (admin)
                item {
                    PrimaryButton(
                        "Записать долг",
                        { actions.open(fresh(FinanceEditKind.DEBT)) },
                        Modifier.fillMaxWidth(),
                        !busy && !data.loading,
                        Glyph.PLUS,
                    )
                }
            item {
                OutlinedTextField(
                    search,
                    { search = it.take(100) },
                    Modifier.fillMaxWidth(),
                    label = { Text("Поиск по имени или примечанию") },
                    singleLine = true,
                    leadingIcon = { LineIcon(Glyph.SEARCH) },
                    shape = RoundedCornerShape(16.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("all" to "Все", "lent" to "Мне должны", "borrowed" to "Я должен")
                        .forEach { (key, title) ->
                            FilterChip(
                                direction == key,
                                { direction = key },
                                { Text(title, style = MaterialTheme.typography.labelMedium) },
                            )
                        }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(closed, { closed = it })
                    Text("Показывать закрытые", style = MaterialTheme.typography.bodyMedium)
                }
            }
            val shown =
                data.debts.filter {
                    (direction == "all" || it.direction == direction) &&
                        (closed || it.remaining_minor > 0) &&
                        (it.person.contains(search, true) || it.note.contains(search, true))
                }
            if (!data.loading && shown.isEmpty())
                item {
                    EmptyState(
                        "Здесь всё спокойно",
                        "Нет долгов по выбранным условиям.",
                        Glyph.USER,
                    )
                }
            items(shown, key = { it.id }) { debt ->
                FinanceCard {
                    Text(debt.person, style = MaterialTheme.typography.titleMedium)
                    Text(if (debt.direction == "lent") "Мне должны" else "Я должен", color = Muted)
                    Text(
                        money(debt.remaining_minor, debt.currency),
                        color = Mint,
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    val overdue =
                        debt.remaining_minor > 0 &&
                            debt.due_date?.let { it < LocalDate.now().toString() } == true
                    Text(
                        if (debt.remaining_minor == 0L) "Закрыт"
                        else
                            debt.due_date?.let {
                                "${if (overdue) "Срок прошёл" else "Вернуть до"} · ${financeDateLabel(it)}"
                            } ?: "Без срока",
                        color = if (overdue) Amber else Muted,
                    )
                    if (debt.note.isNotBlank())
                        Text(debt.note, style = MaterialTheme.typography.bodySmall)
                    if (admin) {
                        fun openMovement(increase: Boolean = false, full: Boolean = false) {
                            actions.open(
                                FinanceEdit(
                                    if (increase) FinanceEditKind.INCREASE_DEBT
                                    else FinanceEditKind.REPAY,
                                    id = debt.id,
                                    name = debt.person,
                                    amount = if (full) editableMoney(debt.remaining_minor) else "",
                                    currency = debt.currency,
                                    accountId = validFinanceAccount(data.accounts, debt.currency),
                                    direction = debt.direction,
                                    remainingMinor = debt.remaining_minor,
                                    fullRepayment = full,
                                )
                            )
                        }
                        if (debt.remaining_minor > 0) {
                            OutlinedButton(
                                { openMovement() },
                                Modifier.fillMaxWidth(),
                                enabled = !busy,
                            ) {
                                Text("Погасить частично")
                            }
                            OutlinedButton(
                                { openMovement(full = true) },
                                Modifier.fillMaxWidth(),
                                enabled = !busy,
                            ) {
                                Text("Погасить полностью")
                            }
                        }
                        TextButton(
                            { openMovement(increase = true) },
                            Modifier.fillMaxWidth(),
                            enabled = !busy,
                        ) {
                            LineIcon(Glyph.PLUS)
                            Spacer(Modifier.width(8.dp))
                            Text("Увеличить долг")
                        }
                    }
                }
            }
        }
    }
    cancelling?.let { tx ->
        AlertDialog(
            onDismissRequest = { if (!busy) cancelling = null },
            title = { Text("Отменить зачисление?") },
            text = {
                Text(
                    "${tx.merchant.ifBlank { "Доход" }} · ${money(tx.amount_minor, tx.currency)}\nБаланс счёта уменьшится. Если поступление связано с планом, оно снова станет ожидаемым."
                )
            },
            confirmButton = {
                TextButton(
                    {
                        cancelling = null
                        actions.void(tx)
                    },
                    enabled = !busy,
                ) {
                    Text("Отменить зачисление")
                }
            },
            dismissButton = {
                TextButton({ cancelling = null }, enabled = !busy) { Text("Оставить") }
            },
        )
    }
}

@Composable
private fun FinanceCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(color = SurfaceColor, shape = RoundedCornerShape(22.dp), tonalElevation = 1.dp) {
        Column(
            Modifier.fillMaxWidth().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = content,
        )
    }
}

@Composable
private fun FinanceMetric(label: String, amount: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, Modifier.weight(1f), color = Muted)
        Text(amount, Modifier.weight(1f), fontWeight = FontWeight.SemiBold, color = Mint)
    }
}

fun validFinanceAccount(accounts: List<Account>, currency: String, preferred: String? = null) =
    accounts.firstOrNull { it.id == preferred && it.currency == currency && !it.archived }?.id
        ?: accounts.firstOrNull { it.currency == currency && !it.archived }?.id.orEmpty()

private fun debtTotal(debts: List<Debt>, direction: String) =
    debts
        .filter { it.direction == direction && it.remaining_minor > 0 }
        .groupBy(Debt::currency)
        .entries
        .joinToString("\n") { (currency, rows) ->
            money(rows.sumOf(Debt::remaining_minor), currency)
        }
        .ifBlank { money(0) }

fun financeDateLabel(date: String): String = runCatching {
    LocalDate.parse(date).format(DateTimeFormatter.ofPattern("dd.MM.yyyy"))
}
    .getOrDefault(date)
