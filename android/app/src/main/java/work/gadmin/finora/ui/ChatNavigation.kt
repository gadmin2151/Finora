package work.gadmin.finora.ui

import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import work.gadmin.finora.AppState
import work.gadmin.finora.data.*
import work.gadmin.finora.localization.Message
import work.gadmin.finora.localization.tr

/** Explicit taps only: reading history never navigates or launches a browser. */
@Composable
fun ChatNavigation(
    message: ChatMessage,
    state: AppState,
    open: (ChatDestination) -> Unit,
    openReceipt: (String) -> Unit,
) {
    message.linkedReceipt()?.let { receipt ->
        OutlinedButton({ openReceipt(receipt) }, enabled = !state.busy) {
            Text(tr(Message.OPEN_RECEIPT_AND_ITEMS))
        }
    }
    message
        .destination(state.organization?.isAdmin == true, state.user?.is_server_admin == true)
        ?.let { destination ->
            val label =
                tr(
                    when (destination) {
                        ChatDestination.OVERVIEW -> Message.OVERVIEW
                        ChatDestination.INCOME -> Message.INCOME
                        ChatDestination.DEBTS -> Message.DEBTS
                        ChatDestination.RECEIPTS -> Message.RECEIPTS
                        ChatDestination.SETTINGS -> Message.AI_NAV_SETTINGS
                        ChatDestination.ASSISTANT -> Message.ASSISTANT
                        ChatDestination.ORGANIZATIONS -> Message.AI_NAV_ORGANIZATIONS
                        ChatDestination.ACCOUNTS -> Message.ACCOUNTS
                        ChatDestination.TRANSACTIONS -> Message.AI_NAV_TRANSACTIONS
                        ChatDestination.PURCHASES -> Message.AI_NAV_PURCHASES
                        ChatDestination.REPORTS -> Message.AI_NAV_REPORTS
                        ChatDestination.INSIGHTS -> Message.AI_NAV_INSIGHTS
                        ChatDestination.BUDGETS -> Message.AI_NAV_BUDGETS
                        ChatDestination.BILLS -> Message.AI_NAV_BILLS
                        ChatDestination.USERS -> Message.AI_NAV_USERS
                    }
                )
            OutlinedButton(
                { open(destination) },
                enabled = !state.busy,
            ) {
                Text(
                    tr(
                        if (destination.native) Message.AI_NAV_OPEN else Message.AI_NAV_OPEN_WEB,
                        label,
                    )
                )
            }
        }
}
