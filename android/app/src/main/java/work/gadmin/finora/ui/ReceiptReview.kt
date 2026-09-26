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
import work.gadmin.finora.localization.Message
import work.gadmin.finora.localization.tr

@Composable
fun ReceiptReview(state: AppState, receipt: Receipt, vm: FinoraViewModel) {
    val accounts = state.paymentAccounts.filter { it.currency == receipt.currency && !it.archived }
    var accountId by
        rememberSaveable(receipt.id) {
            mutableStateOf(
                receipt.account_id?.takeIf { id -> accounts.any { it.id == id } }
                    ?: accounts.firstOrNull()?.id
            )
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
            Text(tr(Message.REVIEW_YOUR_RECEIPT), style = MaterialTheme.typography.titleLarge)
            Text(
                tr(Message.CHECK_THE_STORE_DATE_AND_EACH_ITEM_NO_EXPENSE_IS_ADDED_UNT),
                color = Muted,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    tr(Message.TOTAL_1_S_ITEMS, receipt.items.size),
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    receipt.total_minor?.let { money(it, receipt.currency) }
                        ?: tr(Message.NOT_RECOGNIZED),
                    fontWeight = FontWeight.Bold,
                )
            }
            if (canAccept) {
                OutlinedButton(
                    { vm.editReceipt(true) },
                    Modifier.fillMaxWidth(),
                    enabled = !state.busy,
                ) {
                    Text(tr(Message.EDIT_DETAILS_AND_ITEMS))
                }
                Box {
                    OutlinedButton(
                        { menu = true },
                        Modifier.fillMaxWidth(),
                        enabled = !state.busy,
                    ) {
                        Text(
                            accounts.firstOrNull { it.id == accountId }?.name
                                ?: tr(Message.CHOOSE_AN_ACCOUNT)
                        )
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
                    if (state.busy) tr(Message.SAVING) else tr(Message.LOOKS_RIGHT_CONFIRM),
                    { confirm = true },
                    Modifier.fillMaxWidth(),
                    enabled = !state.busy && complete && accounts.any { it.id == accountId },
                )
                if (!complete) {
                    val problems = buildList {
                        if (receipt.purchased_on == null) add(tr(Message.ENTER_THE_PURCHASE_DATE))
                        if (receipt.merchant.isBlank()) add(tr(Message.ENTER_THE_STORE))
                        if (receipt.items.isEmpty()) add(tr(Message.ADD_RECEIPT_ITEMS))
                        if (receipt.total_minor == null || receipt.total_minor <= 0)
                            add(tr(Message.ENTER_THE_RECEIPT_TOTAL))
                        else if (
                            receipt.items.isNotEmpty() &&
                                receipt.items.sumOf { it.total_minor } != receipt.total_minor
                        )
                            add(tr(Message.THE_ITEM_SUM_DIFFERS_FROM_THE_RECEIPT_TOTAL))
                    }
                    Text(
                        problems.joinToString(" ") + tr(Message.TAP_EDIT_DETAILS_AND_ITEMS),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (accounts.isEmpty())
                    Text(
                        tr(Message.ADD_A_1_S_ACCOUNT_ON_THE_WEBSITE, receipt.currency),
                        color = Muted,
                    )
            } else
                Text(
                    tr(Message.THE_RECEIPT_AUTHOR_OR_AN_ADMINISTRATOR_WILL_CONFIRM_THE_EX),
                    color = Muted,
                )
        }
    }
    if (confirm)
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(tr(Message.ADD_THIS_EXPENSE)) },
            text = {
                Text(
                    tr(
                        Message.TEXT_1_S_2_S_3_S_ITEMS_4_S_ACCOUNT_5_S,
                        receipt.title,
                        receipt.purchased_on,
                        receipt.items.size,
                        money(requireNotNull(receipt.total_minor), receipt.currency),
                        accounts.firstOrNull { it.id == accountId }?.name,
                    )
                )
            },
            confirmButton = {
                TextButton({
                    confirm = false
                    accountId?.let(vm::acceptReceipt)
                }) {
                    Text(tr(Message.CONFIRM))
                }
            },
            dismissButton = { TextButton({ confirm = false }) { Text(tr(Message.REVIEW_AGAIN)) } },
        )
}
