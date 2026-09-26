package work.gadmin.finora.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import work.gadmin.finora.AppState
import work.gadmin.finora.FinoraViewModel
import work.gadmin.finora.data.*

@Composable
fun ReceiptEditor(state: AppState, vm: FinoraViewModel) {
    val receipt = requireNotNull(state.detail)
    var form by
        rememberSaveable(
            receipt.id,
            stateSaver =
                Saver<ReceiptForm, String>(
                    save = { Json.encodeToString(it) },
                    restore = { Json.decodeFromString(it) },
                ),
        ) {
            mutableStateOf(ReceiptForm.from(receipt, state.accounts))
        }
    var error by remember { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf(false) }
    var discard by remember { mutableStateOf(false) }
    BackHandler { if (!state.busy) discard = true }
    val sum = form.lineSum()
    val enabled = !state.busy
    fun changeLine(index: Int, value: ReceiptLineForm) {
        form = form.copy(items = form.items.toMutableList().apply { set(index, value) })
    }
    Scaffold(
        containerColor = Paper,
        topBar = {
            Row(
                Modifier.statusBarsPadding().fillMaxWidth().padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton({ discard = true }, enabled = enabled) {
                    LineIcon(Glyph.BACK, "Назад к проверке")
                }
                Column {
                    Text("Исправить чек", style = MaterialTheme.typography.titleLarge)
                    Text(
                        state.organization?.name.orEmpty(),
                        color = Muted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        bottomBar = {
            Surface(color = SurfaceColor, shadowElevation = 5.dp) {
                Column(
                    Modifier.navigationBarsPadding().imePadding().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        "${form.items.size} позиций · сумма строк: ${sum?.toPlainString() ?: "—"} ${form.currency}"
                    )
                    PrimaryButton(
                        if (state.busy) "Сохраняем…" else "Подтвердить и сохранить",
                        {
                            try {
                                form.payload()
                                error = null
                                confirm = true
                            } catch (failure: IllegalArgumentException) {
                                error = failure.message
                            }
                        },
                        Modifier.fillMaxWidth(),
                        enabled = enabled,
                    )
                    (error ?: state.error)?.let {
                        Text(
                            it,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        },
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                InfoCard(
                    "Исправьте данные по чеку. Скидки учитывайте в суммах строк. Расход появится после подтверждения.",
                    Glyph.RECEIPT,
                )
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    EditField(
                        "Магазин",
                        form.merchant,
                        { form = form.copy(merchant = it.take(200)) },
                        enabled = enabled,
                    )
                    EditField(
                        "Дата · ГГГГ-ММ-ДД",
                        form.date,
                        { form = form.copy(date = it.take(10)) },
                        enabled = enabled,
                        trailingIcon = {
                            TextButton(
                                {
                                    form = form.copy(date = LocalDate.now().toString())
                                    error = null
                                },
                                Modifier.padding(end = 4.dp),
                                enabled = enabled,
                                contentPadding = PaddingValues(horizontal = 8.dp),
                            ) {
                                Text("Сегодня", style = MaterialTheme.typography.labelLarge)
                            }
                        },
                    )
                    ChoiceField(
                        "Валюта",
                        form.currency,
                        listOf("MDL", "EUR", "USD", "RON").map { it to it },
                        enabled,
                    ) { currency ->
                        form =
                            form.copy(
                                currency = requireNotNull(currency),
                                accountId =
                                    state.accounts
                                        .firstOrNull { it.currency == currency && !it.archived }
                                        ?.id,
                            )
                    }
                    ChoiceField(
                        "Счёт",
                        state.accounts.firstOrNull { it.id == form.accountId }?.name
                            ?: "Выберите счёт",
                        state.accounts
                            .filter { !it.archived && it.currency == form.currency }
                            .map { it.id to it.name },
                        enabled,
                    ) {
                        form = form.copy(accountId = it)
                    }
                    if (form.currency != "MDL")
                        EditField(
                            "Курс · MDL за 1 ${form.currency}",
                            form.fxRate,
                            { form = form.copy(fxRate = it.take(18)) },
                            number = true,
                            enabled = enabled,
                        )
                    EditField(
                        "Итог чека · ${form.currency}",
                        form.total,
                        { form = form.copy(total = it.take(16)) },
                        number = true,
                        enabled = enabled,
                    )
                }
            }
            itemsIndexed(form.items, key = { _, item -> item.key }) { index, item ->
                Surface(color = SurfaceColor, shape = RoundedCornerShape(20.dp)) {
                    Column(
                        Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "Позиция ${index + 1}",
                                Modifier.weight(1f),
                                style = MaterialTheme.typography.titleMedium,
                            )
                            IconButton(
                                {
                                    form =
                                        form.copy(
                                            items = form.items.filterNot { it.key == item.key }
                                        )
                                },
                                enabled = enabled,
                            ) {
                                LineIcon(Glyph.TRASH, "Удалить позицию ${index + 1}", size = 20.dp)
                            }
                        }
                        EditField(
                            "Название товара",
                            item.name,
                            { changeLine(index, item.copy(name = it.take(300))) },
                            enabled = enabled,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            EditField(
                                "Количество",
                                item.quantity,
                                { changeLine(index, item.copy(quantity = it.take(18))) },
                                Modifier.weight(1f),
                                number = true,
                                enabled = enabled,
                            )
                            EditField(
                                "Ед. изм.",
                                item.unit,
                                { changeLine(index, item.copy(unit = it.take(12))) },
                                Modifier.weight(1f),
                                enabled = enabled,
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            EditField(
                                "Цена",
                                item.unitPrice,
                                { changeLine(index, item.copy(unitPrice = it.take(16))) },
                                Modifier.weight(1f),
                                number = true,
                                enabled = enabled,
                            )
                            EditField(
                                "Сумма строки",
                                item.total,
                                { changeLine(index, item.copy(total = it.take(16))) },
                                Modifier.weight(1f),
                                number = true,
                                enabled = enabled,
                            )
                        }
                        ChoiceField(
                            "Категория",
                            state.categories.firstOrNull { it.id == item.categoryId }?.name
                                ?: "Без категории",
                            listOf(null to "Без категории") +
                                state.categories.map { it.id to it.name },
                            enabled,
                        ) {
                            changeLine(index, item.copy(categoryId = it))
                        }
                    }
                }
            }
            item {
                OutlinedButton(
                    { form = form.copy(items = form.items + ReceiptLineForm()) },
                    Modifier.fillMaxWidth(),
                    enabled = enabled && form.items.size < 200,
                ) {
                    LineIcon(Glyph.PLUS, size = 18.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Добавить позицию")
                }
                TextButton(
                    { sum?.let { form = form.copy(total = it.toPlainString()) } },
                    Modifier.fillMaxWidth(),
                    enabled = enabled && sum != null,
                ) {
                    Text("Поставить сумму строк в итог")
                }
            }
        }
    }
    if (confirm)
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Сохранить исправленный чек?") },
            text = {
                Text(
                    "${form.merchant}\n${form.date}\n${form.items.size} позиций · ${form.total} ${form.currency}\nСчёт: ${state.accounts.firstOrNull { it.id == form.accountId }?.name}"
                )
            },
            confirmButton = {
                TextButton({
                    confirm = false
                    vm.confirmReview(form)
                }) {
                    Text("Сохранить расход")
                }
            },
            dismissButton = { TextButton({ confirm = false }) { Text("Ещё проверить") } },
        )
    if (discard)
        AlertDialog(
            onDismissRequest = { discard = false },
            title = { Text("Выйти из редактирования?") },
            text = {
                Text(
                    "Несохранённые исправления будут отменены. Распознанный чек останется в истории."
                )
            },
            confirmButton = {
                TextButton({
                    discard = false
                    vm.editReceipt(false)
                }) {
                    Text("Выйти")
                }
            },
            dismissButton = { TextButton({ discard = false }) { Text("Продолжить") } },
        )
}

@Composable
private fun EditField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    number: Boolean = false,
    enabled: Boolean,
    trailingIcon: @Composable (() -> Unit)? = null,
) {
    OutlinedTextField(
        value,
        onChange,
        modifier.fillMaxWidth(),
        label = { Text(label) },
        trailingIcon = trailingIcon,
        singleLine = true,
        enabled = enabled,
        keyboardOptions =
            KeyboardOptions(keyboardType = if (number) KeyboardType.Decimal else KeyboardType.Text),
        shape = RoundedCornerShape(12.dp),
    )
}

@Composable
private fun ChoiceField(
    label: String,
    value: String,
    choices: List<Pair<String?, String>>,
    enabled: Boolean,
    onChange: (String?) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton({ open = true }, Modifier.fillMaxWidth(), enabled = enabled) {
            Text("$label: $value", Modifier.weight(1f))
            LineIcon(Glyph.DOWN, size = 18.dp)
        }
        DropdownMenu(open, { open = false }) {
            choices.forEach { (id, title) ->
                DropdownMenuItem(
                    text = { Text(title) },
                    onClick = {
                        onChange(id)
                        open = false
                    },
                )
            }
            if (choices.isEmpty())
                DropdownMenuItem(
                    text = { Text("Добавьте счёт в веб-версии") },
                    onClick = { open = false },
                    enabled = false,
                )
        }
    }
}
