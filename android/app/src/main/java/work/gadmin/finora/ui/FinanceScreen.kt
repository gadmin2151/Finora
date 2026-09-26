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
import work.gadmin.finora.AppState
import work.gadmin.finora.FinoraViewModel
import work.gadmin.finora.data.*
import work.gadmin.finora.localization.LanguageRuntime
import work.gadmin.finora.localization.Message
import work.gadmin.finora.localization.tr

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
            SectionTitle(tr(Message.YOUR_FINANCES), tr(Message.INCOME_AND_DEBTS_IN_ONE_PLACE))
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf(
                        FinanceTab.INCOME to tr(Message.INCOME),
                        FinanceTab.DEBTS to tr(Message.DEBTS),
                    )
                    .forEach { (tab, title) ->
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
                InfoCard(tr(Message.YOU_CAN_VIEW_SHARED_FINANCES_ONLY_ORGANIZATION_ADMINISTRAT))
            }
        data.error?.let { error ->
            item {
                InfoCard(error, Glyph.REFRESH)
                TextButton(actions::load, enabled = !busy) { Text(tr(Message.REFRESH_FINANCES)) }
            }
        }
        if (data.loading)
            item { BrandLoading(tr(Message.REFRESHING_INCOME_AND_DEBTS), compact = true) }
        if (data.tab == FinanceTab.INCOME) {
            item {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    IconButton({ changeMonth(-1) }, enabled = !busy) {
                        LineIcon(Glyph.BACK, tr(Message.PREVIOUS_MONTH))
                    }
                    Text(
                        if (data.month.isBlank()) tr(Message.LOADING_B6819)
                        else
                            YearMonth.parse(data.month)
                                .format(
                                    DateTimeFormatter.ofPattern(
                                        "LLLL yyyy",
                                        LanguageRuntime.language.locale,
                                    )
                                )
                                .replaceFirstChar(Char::titlecase),
                        fontWeight = FontWeight.SemiBold,
                    )
                    IconButton({ changeMonth(1) }, enabled = !busy) {
                        LineIcon(Glyph.CHEVRON, tr(Message.NEXT_MONTH))
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
                            Text(tr(Message.RECEIVED_THIS_MONTH), color = Mint)
                            Text(
                                money(report.received_minor),
                                style = MaterialTheme.typography.headlineMedium,
                                color = Mint,
                            )
                            HorizontalDivider(color = Border)
                            FinanceMetric(tr(Message.RECURRING), money(report.regular_minor))
                            FinanceMetric(tr(Message.ONE_TIME), money(report.occasional_minor))
                            FinanceMetric(
                                tr(Message.EXPECTED_FROM_PLAN),
                                money(report.expected_minor),
                            )
                            Text(
                                tr(Message.SUMMARY_IN_MDL_USING_TRANSACTION_EXCHANGE_RATES),
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
                        tr(Message.ONE_TIME_INCOME),
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
                        Text(tr(Message.ADD_AN_INCOME_SOURCE))
                    }
                }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(
                            "schedule" to tr(Message.PLAN),
                            "sources" to tr(Message.SOURCES),
                            "history" to tr(Message.HISTORY),
                        )
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
                                tr(Message.NO_PLAN_FOR_THIS_MONTH),
                                tr(Message.ADD_A_SALARY_RENT_OR_ANOTHER_INCOME_SOURCE),
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
                                        tr(
                                            Message.RECEIVED_1_S,
                                            row.received_on?.let(::financeDateLabel).orEmpty(),
                                        )
                                    "skipped" -> tr(Message.SKIPPED)
                                    "paused" -> tr(Message.SOURCE_PAUSED)
                                    "overdue" -> tr(Message.NOT_RECEIVED_OVERDUE)
                                    else -> tr(Message.EXPECTED)
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
                                            Text(tr(Message.RECEIVE))
                                        }
                                    TextButton({ actions.skip(row) }, enabled = !busy) {
                                        Text(
                                            if (row.status == "skipped") tr(Message.RETURN_TO_PLAN)
                                            else tr(Message.SKIP)
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
                                tr(Message.NO_INCOME_SOURCES),
                                tr(
                                    Message
                                        .CREATE_A_SCHEDULE_MONEY_IS_CREDITED_ONLY_AFTER_CONFIRMATIO
                                ),
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
                                tr(
                                    Message.TEXT_1_S_FROM_2_S,
                                    incomeRecurrences[plan.recurrence] ?: plan.recurrence,
                                    financeDateLabel(plan.start_date),
                                ),
                                color = Muted,
                            )
                            Text(
                                if (plan.active) tr(Message.ACTIVE) else tr(Message.PAUSED),
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
                                        Text(tr(Message.EDIT))
                                    }
                                    TextButton({ actions.toggle(plan) }, enabled = !busy) {
                                        Text(
                                            if (plan.active) tr(Message.PAUSE)
                                            else tr(Message.RESUME)
                                        )
                                    }
                                }
                        }
                    }
                }
                "history" -> {
                    if (!data.loading && data.history.isEmpty())
                        item {
                            EmptyState(
                                tr(Message.NO_INCOME_RECEIVED_YET),
                                tr(Message.ADD_ONE_TIME_INCOME_OR_CONFIRM_A_PLANNED_PAYMENT),
                                Glyph.WALLET,
                            )
                        }
                    items(data.history, key = { it.id }) { tx ->
                        FinanceCard {
                            Text(
                                tx.merchant.ifBlank { tr(Message.INCOME_40B65) },
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                "+${money(tx.amount_minor, tx.currency)}",
                                color = Mint,
                                style = MaterialTheme.typography.titleLarge,
                            )
                            Text(
                                "${financeDateLabel(tx.occurred_on)} · ${if (tx.occurrence_id == null) tr(Message.ONE_TIME_PAYMENT) else tr(Message.AS_PLANNED)}",
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
                                            Text(tr(Message.EDIT))
                                        }
                                    TextButton({ cancelling = tx }, enabled = !busy) {
                                        Text(tr(Message.REVERSE_INCOME_ENTRY), color = Amber)
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
                                    if (data.loadingMore) tr(Message.LOADING_B00E2)
                                    else
                                        tr(
                                            Message.MORE_INCOME_1_S_OF_2_S,
                                            data.history.size,
                                            data.historyCount,
                                        )
                                )
                            }
                        }
                }
            }
        } else {
            item {
                FinanceCard {
                    FinanceMetric(tr(Message.OWED_TO_ME), debtTotal(data.debts, "lent"))
                    HorizontalDivider(color = Border)
                    FinanceMetric(tr(Message.I_OWE), debtTotal(data.debts, "borrowed"))
                    Text(
                        tr(Message.DEBTS_AND_REPAYMENTS_ARE_NOT_COUNTED_AS_INCOME_OR_EXPENSES),
                        color = Muted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            if (admin)
                item {
                    PrimaryButton(
                        tr(Message.RECORD_A_DEBT),
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
                    label = { Text(tr(Message.SEARCH_BY_NAME_OR_NOTE)) },
                    singleLine = true,
                    leadingIcon = { LineIcon(Glyph.SEARCH) },
                    shape = RoundedCornerShape(16.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(
                            "all" to tr(Message.ALL),
                            "lent" to tr(Message.OWED_TO_ME),
                            "borrowed" to tr(Message.I_OWE),
                        )
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
                    Text(tr(Message.SHOW_CLOSED_DEBTS), style = MaterialTheme.typography.bodyMedium)
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
                        tr(Message.ALL_CLEAR_HERE),
                        tr(Message.NO_DEBTS_MATCH_YOUR_FILTERS),
                        Glyph.USER,
                    )
                }
            items(shown, key = { it.id }) { debt ->
                FinanceCard {
                    Text(debt.person, style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (debt.direction == "lent") tr(Message.OWED_TO_ME) else tr(Message.I_OWE),
                        color = Muted,
                    )
                    Text(
                        money(debt.remaining_minor, debt.currency),
                        color = Mint,
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    val overdue =
                        debt.remaining_minor > 0 &&
                            debt.due_date?.let { it < LocalDate.now().toString() } == true
                    Text(
                        if (debt.remaining_minor == 0L) tr(Message.CLOSED)
                        else
                            debt.due_date?.let {
                                "${if (overdue) tr(Message.OVERDUE) else tr(Message.DUE_BY)} · ${financeDateLabel(it)}"
                            } ?: tr(Message.NO_DUE_DATE),
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
                                Text(tr(Message.REPAY_PART))
                            }
                            OutlinedButton(
                                { openMovement(full = true) },
                                Modifier.fillMaxWidth(),
                                enabled = !busy,
                            ) {
                                Text(tr(Message.REPAY_IN_FULL))
                            }
                        }
                        TextButton(
                            { openMovement(increase = true) },
                            Modifier.fillMaxWidth(),
                            enabled = !busy,
                        ) {
                            LineIcon(Glyph.PLUS)
                            Spacer(Modifier.width(8.dp))
                            Text(tr(Message.INCREASE_DEBT))
                        }
                    }
                }
            }
        }
    }
    cancelling?.let { tx ->
        AlertDialog(
            onDismissRequest = { if (!busy) cancelling = null },
            title = { Text(tr(Message.REVERSE_THIS_INCOME_ENTRY)) },
            text = {
                Text(
                    tr(
                        Message.TEXT_1_S_2_S_THE_ACCOUNT_BALANCE_WILL_DECREASE_IF_THIS_INCOME_I,
                        tx.merchant.ifBlank { tr(Message.INCOME_40B65) },
                        money(tx.amount_minor, tx.currency),
                    )
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
                    Text(tr(Message.REVERSE_INCOME_ENTRY))
                }
            },
            dismissButton = {
                TextButton({ cancelling = null }, enabled = !busy) { Text(tr(Message.KEEP)) }
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
    LocalDate.parse(date)
        .format(
            DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.MEDIUM)
                .withLocale(LanguageRuntime.language.locale)
        )
}
    .getOrDefault(date)
