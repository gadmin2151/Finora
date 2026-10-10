package work.gadmin.finora.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import work.gadmin.finora.data.*

/** All budget actions stay inside the authenticated native application. */
@Composable
fun BudgetScreen(
    data: BudgetState,
    admin: Boolean,
    busy: Boolean,
    actions: BudgetController,
    changeMonth: (Long) -> Unit,
) {
    BudgetContent(
        data,
        admin,
        busy,
        actions::open,
        actions::load,
        actions::requestCopy,
        changeMonth,
    )
    data.editor?.let {
        BudgetEditor(
            data,
            busy,
            actions::edit,
            actions::save,
            actions::delete,
            actions::closeEditor,
        )
    }
    if (data.copyOpen)
        BudgetCopyDialog(data, busy, actions::applyCopy, actions::requestCopy, actions::closeCopy)
}

@Composable
fun BudgetContent(
    data: BudgetState,
    admin: Boolean,
    busy: Boolean,
    edit: (BudgetCategory) -> Unit,
    retry: () -> Unit,
    copy: () -> Unit,
    changeMonth: (Long) -> Unit,
) {
    LazyColumn(
        Modifier.fillMaxSize().testTag("budget-list"),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Text(budgetText("Бюджеты", "Budgets"), style = MaterialTheme.typography.headlineMedium)
            Text(
                budgetText(
                    "Лимиты категорий в MDL. Они не создают платежи.",
                    "Category limits in MDL. These do not create payments.",
                ),
                color = Muted,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(
                    { changeMonth(-1) },
                    enabled = !busy && data.editor == null && !data.copyOpen,
                ) {
                    Text("‹")
                }
                Text(data.month, Modifier.padding(top = 12.dp))
                TextButton(
                    { changeMonth(1) },
                    enabled = !busy && data.editor == null && !data.copyOpen,
                ) {
                    Text("›")
                }
            }
            if (admin)
                OutlinedButton(
                    copy,
                    enabled = !busy && !data.loading,
                    modifier = Modifier.fillMaxWidth().testTag("budget-copy"),
                ) {
                    Text(budgetText("Перенести прошлый месяц", "Copy previous month"))
                }
            else
                Text(
                    budgetText(
                        "Просмотр: менять лимиты может администратор",
                        "View only: an administrator can change limits",
                    ),
                    color = Muted,
                )
        }
        if (data.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        data.error?.let { error ->
            item {
                Text(error, color = MaterialTheme.colorScheme.error)
                TextButton(retry, enabled = !busy, modifier = Modifier.testTag("budget-retry")) {
                    Text(budgetText("Повторить", "Retry"))
                }
            }
        }
        item {
            Text(
                budgetText("Запланировано", "Planned") +
                    ": " +
                    budgetMoney(data.categories.sumOf { it.budget_minor ?: 0 })
            )
        }
        if (!data.loading && data.categories.isEmpty())
            item {
                Text(
                    budgetText(
                        "Категорий пока нет. Обновите данные после добавления категории.",
                        "No categories yet. Refresh after adding a category.",
                    )
                )
                TextButton(retry, enabled = !busy, modifier = Modifier.testTag("budget-retry")) {
                    Text(budgetText("Обновить", "Refresh"))
                }
            }
        items(data.categories, key = { it.id }) { category ->
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = SurfaceColor,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(category.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        budgetText("Потрачено", "Spent") + ": " + budgetMoney(category.spent_minor)
                    )
                    Text(
                        budgetText("Лимит", "Limit") +
                            ": " +
                            (category.budget_minor?.let(::budgetMoney)
                                ?: budgetText("Не задан", "Not set"))
                    )
                    category.budget_minor?.let { amount ->
                        Text(
                            budgetText("Осталось", "Remaining") +
                                ": " +
                                budgetMoney(amount - category.spent_minor),
                            color =
                                if (amount < category.spent_minor) MaterialTheme.colorScheme.error
                                else Ink,
                        )
                    }
                    if (admin && category.id != "uncategorized")
                        TextButton(
                            { edit(category) },
                            enabled = !busy && !data.loading,
                            modifier = Modifier.testTag("budget-edit-${category.id}"),
                        ) {
                            Text(budgetText("Изменить лимит", "Edit limit"))
                        }
                }
            }
        }
    }
}

private fun budgetMoney(value: Long): String =
    java.math.BigDecimal.valueOf(value, 2).toPlainString() + " MDL"

@Composable
fun BudgetEditor(
    data: BudgetState,
    busy: Boolean,
    edit: (String) -> Unit,
    save: () -> Unit,
    delete: () -> Unit,
    close: () -> Unit,
) {
    val form = data.editor ?: return
    var discard by remember(form.category.id) { mutableStateOf(false) }
    var remove by remember(form.category.id) { mutableStateOf(false) }
    val dismiss = { if (!busy) discard = true }
    BackHandler { dismiss() }
    AlertDialog(
        onDismissRequest = dismiss,
        modifier = Modifier.testTag("budget-editor"),
        title = { Text(form.category.name) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(budgetText("Лимит на", "Limit for") + " ${form.month} (MDL)")
                OutlinedTextField(
                    form.amount,
                    edit,
                    label = { Text(budgetText("Сумма MDL", "Amount MDL")) },
                    enabled = !busy && !data.retryLocked,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth().testTag("budget-amount"),
                )
                if (data.retryLocked)
                    Text(
                        budgetText(
                            "Ответ не подтверждён. Повторяется только тот же лимит; изменение суммы заблокировано.",
                            "Response unconfirmed. Retry sets the same limit; amount changes are locked.",
                        )
                    )
                data.editorError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (form.category.budget_id != null)
                    TextButton(
                        { remove = true },
                        enabled = !busy && !data.retryLocked,
                        modifier = Modifier.testTag("budget-delete"),
                    ) {
                        Text(
                            budgetText("Удалить лимит", "Remove limit"),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
            }
        },
        confirmButton = {
            TextButton(save, enabled = !busy, modifier = Modifier.testTag("budget-save")) {
                Text(
                    if (data.retryLocked) budgetText("Повторить тот же лимит", "Retry same limit")
                    else budgetText("Сохранить", "Save")
                )
            }
        },
        dismissButton = {
            TextButton(dismiss, enabled = !busy, modifier = Modifier.testTag("budget-close")) {
                Text(budgetText("Отмена", "Cancel"))
            }
        },
    )
    if (discard)
        AlertDialog(
            onDismissRequest = { discard = false },
            title = { Text(budgetText("Закрыть без сохранения?", "Close without saving?")) },
            text = {
                Text(
                    if (data.retryLocked)
                        budgetText(
                            "Сервер мог уже сохранить лимит. После закрытия обновите данные перед следующим изменением.",
                            "The server may have saved this limit. Refresh after closing before making another change.",
                        )
                    else
                        budgetText(
                            "Несохранённые изменения формы будут отменены.",
                            "Unsaved form changes will be discarded.",
                        )
                )
            },
            confirmButton = {
                TextButton(
                    {
                        discard = false
                        close()
                    },
                    enabled = !busy,
                ) {
                    Text(budgetText("Закрыть", "Close"))
                }
            },
            dismissButton = {
                TextButton({ discard = false }) {
                    Text(budgetText("Продолжить", "Continue editing"))
                }
            },
        )
    if (remove)
        AlertDialog(
            onDismissRequest = { remove = false },
            title = { Text(budgetText("Удалить лимит?", "Remove limit?")) },
            text = {
                Text(
                    "${form.category.name} · ${form.month}\n" +
                        budgetText(
                            "Покупки и платежи останутся.",
                            "Purchases and payments will remain.",
                        )
                )
            },
            confirmButton = {
                TextButton(
                    {
                        remove = false
                        delete()
                    },
                    enabled = !busy,
                ) {
                    Text(budgetText("Удалить", "Remove"))
                }
            },
            dismissButton = {
                TextButton({ remove = false }, enabled = !busy) {
                    Text(budgetText("Отмена", "Cancel"))
                }
            },
        )
}

@Composable
fun BudgetCopyDialog(
    data: BudgetState,
    busy: Boolean,
    apply: () -> Unit,
    review: () -> Unit,
    close: () -> Unit,
) {
    BackHandler { if (!busy) close() }
    AlertDialog(
        onDismissRequest = { if (!busy) close() },
        modifier = Modifier.testTag("budget-copy-dialog"),
        title = { Text(budgetText("Проверить перенос лимитов", "Review copied limits")) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (data.copyLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
                data.copyError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                data.preview?.let { preview ->
                    Text("${preview.source_month} → ${preview.month}")
                    Text(
                        budgetText(
                            "Существующие лимиты сохраняются",
                            "Existing limits are preserved",
                        ) + ": ${preview.preserved_count}"
                    )
                    preview.items.forEach { Text("${it.name}: ${budgetMoney(it.amount_minor)}") }
                    Text(budgetText("Всего", "Total") + ": ${budgetMoney(preview.total_minor)}")
                    if (preview.items.isEmpty())
                        Text(budgetText("Нет новых лимитов для переноса", "No new limits to copy"))
                }
            }
        },
        confirmButton = {
            if (data.preview != null)
                TextButton(
                    apply,
                    enabled = !busy && !data.copyLoading && data.preview.items.isNotEmpty(),
                    modifier = Modifier.testTag("budget-copy-apply"),
                ) {
                    Text(budgetText("Подтвердить перенос", "Confirm copy"))
                }
            else
                TextButton(review, enabled = !busy && !data.copyLoading) {
                    Text(budgetText("Проверить заново", "Review again"))
                }
        },
        dismissButton = {
            TextButton(close, enabled = !busy, modifier = Modifier.testTag("budget-copy-close")) {
                Text(budgetText("Отмена", "Cancel"))
            }
        },
    )
}
