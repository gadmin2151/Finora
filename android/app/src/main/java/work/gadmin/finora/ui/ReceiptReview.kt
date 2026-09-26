package work.gadmin.finora.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import work.gadmin.finora.AppState
import work.gadmin.finora.FinoraViewModel
import work.gadmin.finora.data.Receipt
import work.gadmin.finora.data.money

@Composable
fun ReceiptReview(state: AppState, receipt: Receipt, vm: FinoraViewModel) {
    val accounts = state.accounts.filter { it.currency == receipt.currency && !it.archived }
    var accountId by
        rememberSaveable(receipt.id) {
            mutableStateOf(receipt.account_id ?: accounts.firstOrNull()?.id)
        }
    var menu by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    val canAccept = state.organization?.isAdmin == true || receipt.created_by == state.user?.id
    val complete =
        receipt.items.isNotEmpty() &&
            receipt.total_minor != null &&
            receipt.total_minor > 0 &&
            receipt.purchased_on != null &&
            receipt.merchant.isNotBlank() &&
            receipt.items.sumOf { it.total_minor } == receipt.total_minor
    Surface(shape = RoundedCornerShape(24.dp), color = SurfaceColor) {
        Column(
            Modifier.fillMaxWidth().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Проверьте чек", style = MaterialTheme.typography.titleLarge)
            Text(
                "Сверьте магазин, дату и каждую позицию. До подтверждения расход не добавляется.",
                color = Muted,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Итого · ${receipt.items.size} позиций", fontWeight = FontWeight.SemiBold)
                Text(
                    receipt.total_minor?.let { money(it, receipt.currency) } ?: "Не распознано",
                    fontWeight = FontWeight.Bold,
                )
            }
            if (canAccept) {
                OutlinedButton(
                    { vm.editReceipt(true) },
                    Modifier.fillMaxWidth(),
                    enabled = !state.busy,
                ) {
                    Text("Исправить данные и позиции")
                }
                Box {
                    OutlinedButton(
                        { menu = true },
                        Modifier.fillMaxWidth(),
                        enabled = !state.busy,
                    ) {
                        Text(accounts.firstOrNull { it.id == accountId }?.name ?: "Выберите счёт")
                        Spacer(Modifier.weight(1f))
                        LineIcon(Glyph.DOWN)
                    }
                    DropdownMenu(menu, { menu = false }) {
                        accounts.forEach { account ->
                            DropdownMenuItem(
                                text = { Text(account.name) },
                                onClick = {
                                    accountId = account.id
                                    menu = false
                                },
                            )
                        }
                    }
                }
                PrimaryButton(
                    if (state.busy) "Сохраняем…" else "Всё верно · подтвердить",
                    { confirm = true },
                    Modifier.fillMaxWidth(),
                    enabled = !state.busy && complete && accounts.any { it.id == accountId },
                )
                if (!complete) {
                    val problems = buildList {
                        if (receipt.purchased_on == null) add("Укажите дату покупки.")
                        if (receipt.merchant.isBlank()) add("Укажите магазин.")
                        if (receipt.items.isEmpty()) add("Добавьте позиции чека.")
                        if (receipt.total_minor == null || receipt.total_minor <= 0)
                            add("Укажите итог чека.")
                        else if (
                            receipt.items.isNotEmpty() &&
                                receipt.items.sumOf { it.total_minor } != receipt.total_minor
                        )
                            add("Сумма позиций отличается от итога чека.")
                    }
                    Text(
                        problems.joinToString(" ") + " Нажмите «Исправить данные и позиции».",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (accounts.isEmpty())
                    Text(
                        "Добавьте счёт в валюте ${receipt.currency} через веб-версию.",
                        color = Muted,
                    )
            } else
                Text(
                    "Автор чека или администратор подтвердит расход. Замечания можно оставить в комментарии.",
                    color = Muted,
                )
        }
    }
    if (confirm)
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Добавить этот расход?") },
            text = {
                Text(
                    "${receipt.title}\n${receipt.purchased_on}\n${receipt.items.size} позиций · ${money(requireNotNull(receipt.total_minor), receipt.currency)}\nСчёт: ${accounts.firstOrNull { it.id == accountId }?.name}"
                )
            },
            confirmButton = {
                TextButton({
                    confirm = false
                    accountId?.let(vm::acceptReceipt)
                }) {
                    Text("Подтвердить")
                }
            },
            dismissButton = { TextButton({ confirm = false }) { Text("Ещё проверить") } },
        )
}
