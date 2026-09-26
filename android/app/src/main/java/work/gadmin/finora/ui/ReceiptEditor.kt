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
import work.gadmin.finora.localization.Message
import work.gadmin.finora.localization.tr

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
                    LineIcon(Glyph.BACK, tr(Message.BACK_TO_REVIEW))
                }
                Column {
                    Text(tr(Message.EDIT_RECEIPT), style = MaterialTheme.typography.titleLarge)
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
                        tr(
                            Message.TEXT_1_S_ITEMS_ITEM_TOTAL_2_S_3_S,
                            form.items.size,
                            sum?.toPlainString() ?: "—",
                            form.currency,
                        )
                    )
                    PrimaryButton(
                        if (state.busy) tr(Message.SAVING) else tr(Message.CONFIRM_AND_SAVE),
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
                    tr(Message.CHECK_THE_DETAILS_AGAINST_YOUR_RECEIPT_INCLUDE_DISCOUNTS_I),
                    Glyph.RECEIPT,
                )
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    EditField(
                        tr(Message.STORE),
                        form.merchant,
                        { form = form.copy(merchant = it.take(200)) },
                        enabled = enabled,
                    )
                    EditField(
                        tr(Message.STORE_ADDRESS),
                        form.merchantAddress,
                        { form = form.copy(merchantAddress = it.take(500)) },
                        enabled = enabled,
                    )
                    EditField(
                        tr(Message.DATE_YYYY_MM_DD),
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
                                Text(tr(Message.TODAY), style = MaterialTheme.typography.labelLarge)
                            }
                        },
                    )
                    ChoiceField(
                        tr(Message.CURRENCY),
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
                        tr(Message.ACCOUNT),
                        state.accounts.firstOrNull { it.id == form.accountId }?.name
                            ?: tr(Message.CHOOSE_AN_ACCOUNT),
                        state.accounts
                            .filter { !it.archived && it.currency == form.currency }
                            .map { it.id to it.name },
                        enabled,
                    ) {
                        form = form.copy(accountId = it)
                    }
                    if (form.currency != "MDL")
                        EditField(
                            tr(Message.RATE_MDL_PER_1_1_S, form.currency),
                            form.fxRate,
                            { form = form.copy(fxRate = it.take(18)) },
                            number = true,
                            enabled = enabled,
                        )
                    EditField(
                        tr(Message.RECEIPT_TOTAL_1_S, form.currency),
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
                                tr(Message.ITEM_1_S, index + 1),
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
                                LineIcon(
                                    Glyph.TRASH,
                                    tr(Message.DELETE_ITEM_1_S, index + 1),
                                    size = 20.dp,
                                )
                            }
                        }
                        EditField(
                            tr(Message.ITEM_NAME),
                            item.name,
                            { changeLine(index, item.copy(name = it.take(300))) },
                            enabled = enabled,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            EditField(
                                tr(Message.QUANTITY),
                                item.quantity,
                                { changeLine(index, item.copy(quantity = it.take(18))) },
                                Modifier.weight(1f),
                                number = true,
                                enabled = enabled,
                            )
                            EditField(
                                tr(Message.UNIT),
                                item.unit,
                                { changeLine(index, item.copy(unit = it.take(12))) },
                                Modifier.weight(1f),
                                enabled = enabled,
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            EditField(
                                tr(Message.PRICE),
                                item.unitPrice,
                                { changeLine(index, item.copy(unitPrice = it.take(16))) },
                                Modifier.weight(1f),
                                number = true,
                                enabled = enabled,
                            )
                            EditField(
                                tr(Message.LINE_TOTAL),
                                item.total,
                                { changeLine(index, item.copy(total = it.take(16))) },
                                Modifier.weight(1f),
                                number = true,
                                enabled = enabled,
                            )
                        }
                        ChoiceField(
                            tr(Message.CATEGORY),
                            state.categories.firstOrNull { it.id == item.categoryId }?.name
                                ?: tr(Message.UNCATEGORIZED),
                            listOf(null to tr(Message.UNCATEGORIZED)) +
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
                    Text(tr(Message.ADD_ITEM))
                }
                TextButton(
                    { sum?.let { form = form.copy(total = it.toPlainString()) } },
                    Modifier.fillMaxWidth(),
                    enabled = enabled && sum != null,
                ) {
                    Text(tr(Message.USE_ITEM_SUM_AS_TOTAL))
                }
            }
        }
    }
    if (confirm)
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(tr(Message.SAVE_THE_CORRECTED_RECEIPT)) },
            text = {
                Text(
                    tr(
                        Message.TEXT_1_S_2_S_3_S_ITEMS_4_S_5_S_ACCOUNT_6_S,
                        form.merchant,
                        form.date,
                        form.items.size,
                        form.total,
                        form.currency,
                        state.accounts.firstOrNull { it.id == form.accountId }?.name,
                    )
                )
            },
            confirmButton = {
                TextButton({
                    confirm = false
                    vm.confirmReview(form)
                }) {
                    Text(tr(Message.SAVE_EXPENSE))
                }
            },
            dismissButton = { TextButton({ confirm = false }) { Text(tr(Message.REVIEW_AGAIN)) } },
        )
    if (discard)
        AlertDialog(
            onDismissRequest = { discard = false },
            title = { Text(tr(Message.LEAVE_THE_EDITOR)) },
            text = {
                Text(tr(Message.UNSAVED_CORRECTIONS_WILL_BE_LOST_THE_RECOGNIZED_RECEIPT_WI))
            },
            confirmButton = {
                TextButton({
                    discard = false
                    vm.editReceipt(false)
                }) {
                    Text(tr(Message.SIGN_OUT_026AB))
                }
            },
            dismissButton = { TextButton({ discard = false }) { Text(tr(Message.CONTINUE)) } },
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
                    text = { Text(tr(Message.ADD_AN_ACCOUNT_ON_THE_WEBSITE)) },
                    onClick = { open = false },
                    enabled = false,
                )
        }
    }
}
