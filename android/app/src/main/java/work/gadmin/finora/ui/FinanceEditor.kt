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
import work.gadmin.finora.localization.Message
import work.gadmin.finora.localization.tr

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
    val fixedCurrency =
        form.kind in
            setOf(FinanceEditKind.RECEIVE, FinanceEditKind.REPAY, FinanceEditKind.INCREASE_DEBT)
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
                        LineIcon(Glyph.BACK, tr(Message.CLOSE_FORM))
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
                            if (busy) tr(Message.SAVING)
                            else
                                when (form.kind) {
                                    FinanceEditKind.PLAN -> tr(Message.SAVE_SOURCE)
                                    FinanceEditKind.DEBT -> tr(Message.SAVE_DEBT)
                                    FinanceEditKind.REPAY ->
                                        if (form.fullRepayment) tr(Message.REPAY_THE_FULL_BALANCE)
                                        else tr(Message.RECORD_REPAYMENT)
                                    FinanceEditKind.INCREASE_DEBT -> tr(Message.INCREASE_DEBT)
                                    FinanceEditKind.RECEIVE -> tr(Message.CONFIRM_RECEIPT_OF_INCOME)
                                    FinanceEditKind.INCOME -> tr(Message.SAVE_INCOME)
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
                            tr(Message.A_PLAN_DOES_NOT_CHANGE_YOUR_BALANCE_MARK_INCOME_AS_RECEIVE),
                            Glyph.WALLET,
                        )
                    FinanceEditKind.REPAY ->
                        InfoCard(
                            tr(
                                Message.TEXT_1_S_OUTSTANDING_2_S_3_S,
                                form.name,
                                money(form.remainingMinor ?: 0, form.currency),
                                if (form.fullRepayment)
                                    tr(Message.THE_DEBT_WILL_BE_CLOSED_AFTER_CONFIRMATION)
                                else tr(Message.ENTER_THE_AMOUNT_ACTUALLY_REPAID),
                            ),
                            Glyph.USER,
                        )
                    FinanceEditKind.INCREASE_DEBT ->
                        InfoCard(
                            tr(
                                Message
                                    .TEXT_1_S_OUTSTANDING_2_S_ENTER_THE_ADDITIONAL_AMOUNT_TO_INCREAS,
                                form.name,
                                money(form.remainingMinor ?: 0, form.currency),
                            ),
                            Glyph.USER,
                        )
                    FinanceEditKind.RECEIVE ->
                        InfoCard(
                            tr(
                                Message.TEXT_1_S_ENTER_THE_ACTUAL_AMOUNT_AND_DATE_RECEIVED,
                                form.name,
                            ),
                            Glyph.WALLET,
                        )
                    else -> Unit
                }
                if (form.kind == FinanceEditKind.DEBT) {
                    FinanceChoice(
                        tr(Message.DIRECTION),
                        form.direction,
                        listOf(
                            "lent" to tr(Message.I_LENT_MONEY),
                            "borrowed" to tr(Message.I_BORROWED_MONEY),
                        ),
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
                            FinanceEditKind.DEBT -> tr(Message.PERSON_S_NAME)
                            FinanceEditKind.PLAN -> tr(Message.SOURCE_NAME)
                            else -> tr(Message.WHERE_THE_MONEY_CAME_FROM)
                        },
                        form.name,
                        enabled,
                        max = if (form.kind == FinanceEditKind.INCOME) 200 else 100,
                    ) {
                        change(form.copy(name = it))
                    }
                }
                FinanceText(
                    tr(Message.AMOUNT_1_S, form.currency),
                    form.amount,
                    enabled && !form.fullRepayment,
                    number = true,
                    max = 24,
                ) {
                    change(form.copy(amount = it))
                }
                if (!fixedCurrency)
                    FinanceChoice(
                        tr(Message.CURRENCY),
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
                        tr(Message.RECORD_AS),
                        form.mode,
                        listOf(
                            "new" to tr(Message.MONEY_IS_BEING_TRANSFERRED_NOW),
                            "existing" to tr(Message.AN_EXISTING_DEBT),
                        ),
                        enabled,
                    ) {
                        change(form.copy(mode = it))
                    }
                    Text(
                        if (existing)
                            tr(Message.THE_ACCOUNT_BALANCE_WILL_NOT_CHANGE_THE_MONEY_WAS_TRANSFER)
                        else if (form.direction == "lent")
                            tr(Message.THE_AMOUNT_WILL_BE_DEDUCTED_FROM_THE_SELECTED_ACCOUNT)
                        else tr(Message.THE_AMOUNT_WILL_BE_ADDED_TO_THE_SELECTED_ACCOUNT),
                        color = Muted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (!existing) {
                    FinanceChoice(
                        when {
                            form.kind == FinanceEditKind.REPAY ->
                                if (form.direction == "borrowed") tr(Message.DEBIT_ACCOUNT)
                                else tr(Message.RECEIVED_IN_ACCOUNT)
                            form.kind == FinanceEditKind.INCREASE_DEBT ->
                                if (form.direction == "lent") tr(Message.DEBIT_ACCOUNT)
                                else tr(Message.RECEIVED_IN_ACCOUNT)
                            else -> tr(Message.ACCOUNT)
                        },
                        form.accountId,
                        accounts.map { it.id to "${it.name} · ${it.currency}" },
                        enabled,
                    ) {
                        change(form.copy(accountId = it))
                    }
                    if (accounts.isEmpty())
                        InfoCard(
                            tr(
                                Message.NO_ACTIVE_1_S_ACCOUNT_CREATE_ONE_ON_THE_WEBSITE_OR_CHOOSE,
                                form.currency,
                            ),
                            Glyph.WALLET,
                        )
                    if (form.currency != "MDL")
                        FinanceText(
                            tr(Message.EXCHANGE_RATE_1_1_S_IN_MDL, form.currency),
                            form.fxRate,
                            enabled,
                            number = true,
                            max = 18,
                        ) {
                            change(form.copy(fxRate = it))
                        }
                }
                FinanceDate(
                    if (form.kind == FinanceEditKind.PLAN) tr(Message.FIRST_PAYMENT)
                    else tr(Message.TRANSACTION_DATE),
                    form.date,
                    enabled,
                    future = form.kind == FinanceEditKind.PLAN,
                ) {
                    change(form.copy(date = it))
                }
                if (form.kind == FinanceEditKind.PLAN) {
                    FinanceChoice(
                        tr(Message.REPEAT),
                        form.recurrence,
                        incomeRecurrences.toList(),
                        enabled,
                    ) {
                        change(form.copy(recurrence = it))
                    }
                    Text(
                        tr(Message.FOR_THE_29TH_31ST_IN_SHORTER_MONTHS_THE_LAST_DAY_IS_USED_A),
                        color = Muted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (form.kind == FinanceEditKind.DEBT)
                    FinanceDate(
                        tr(Message.DUE_DATE_OPTIONAL),
                        form.dueDate,
                        enabled,
                        future = true,
                        optional = true,
                    ) {
                        change(form.copy(dueDate = it))
                    }
                if (
                    form.kind in
                        setOf(
                            FinanceEditKind.INCOME,
                            FinanceEditKind.DEBT,
                            FinanceEditKind.REPAY,
                            FinanceEditKind.INCREASE_DEBT,
                        )
                ) {
                    OutlinedTextField(
                        form.note,
                        { change(form.copy(note = it.take(3000))) },
                        Modifier.fillMaxWidth(),
                        enabled = enabled,
                        label = { Text(tr(Message.NOTE)) },
                        minLines = 2,
                        maxLines = 5,
                        shape = RoundedCornerShape(16.dp),
                    )
                }
                if (form.kind == FinanceEditKind.RECEIVE) {
                    HorizontalDivider(color = Border)
                    Text(
                        tr(Message.ALREADY_RECORDED_THIS_INCOME),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        tr(Message.LINK_IT_TO_AN_ENTRY_WITH_THE_SAME_ACCOUNT_AMOUNT_AND_DATE),
                        color = Muted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedButton(
                        findMatches,
                        Modifier.fillMaxWidth(),
                        enabled = enabled && !data.matchesLoading,
                    ) {
                        Text(
                            if (data.matchesLoading) tr(Message.LOOKING_FOR_INCOME_ENTRIES)
                            else tr(Message.FIND_EXISTING_INCOME)
                        )
                    }
                    if (data.matchesLoaded) {
                        if (data.matches.isEmpty())
                            Text(
                                tr(
                                    Message
                                        .NO_MATCHES_FOR_THIS_MONTH_AMONG_THE_LAST_200_INCOME_ENTRIE
                                ),
                                color = Muted,
                            )
                        else
                            FinanceChoice(
                                tr(Message.INCOME_ENTRY),
                                form.transactionId.orEmpty(),
                                listOf("" to tr(Message.CREATE_A_NEW_INCOME_ENTRY)) +
                                    data.matches.map {
                                        it.id to
                                            "${it.merchant.ifBlank { tr(Message.INCOME) }} · ${money(it.amount_minor, it.currency)}"
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
                title = { Text(tr(Message.CLOSE_WITHOUT_SAVING)) },
                text = { Text(tr(Message.UNSAVED_CHANGES_IN_THIS_FORM_WILL_BE_LOST)) },
                confirmButton = {
                    TextButton(
                        {
                            discard = false
                            close()
                        },
                        enabled = enabled,
                    ) {
                        Text(tr(Message.CLOSE))
                    }
                },
                dismissButton = { TextButton({ discard = false }) { Text(tr(Message.CONTINUE)) } },
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
                    choices.firstOrNull { it.first == value }?.second ?: tr(Message.CHOOSE),
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
                Text(if (value.isBlank()) tr(Message.NOT_SPECIFIED) else financeDateLabel(value))
            }
            if (optional && value.isNotBlank())
                IconButton({ change("") }, enabled = enabled) {
                    LineIcon(Glyph.CLOSE, tr(Message.REMOVE_DUE_DATE))
                }
        }
    }
}
