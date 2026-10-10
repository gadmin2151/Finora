package work.gadmin.finora.ui

import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import work.gadmin.finora.AppState
import work.gadmin.finora.data.*
import work.gadmin.finora.localization.Message
import work.gadmin.finora.localization.tr

/** Read-only buttons. Only a tap emits an intent; recomposition/history loading emits nothing. */
@Composable
fun NativeChatNavigation(
    message: ChatMessage,
    state: AppState,
    open: (NativeNavigationRequest) -> Unit,
) {
    val org = state.organization ?: return
    val scope = NavigationScope(org.id, state.month, "${state.user?.id}@${state.server}")
    message.linkedReceipt()?.let { id ->
        val request =
            NativeNavigationRequest(
                "receipts",
                scope,
                mapOf(NavigationEntity.RECEIPT to id),
                message.role == "assistant",
                message.details.error,
            )
        OutlinedButton(
            { open(request) },
            modifier = Modifier.testTag("native-nav-receipt"),
            enabled = !state.busy,
        ) {
            Text(tr(Message.OPEN_RECEIPT_AND_ITEMS))
        }
    }
    message.destination(org.isAdmin, state.user?.is_server_admin == true)?.let { destination ->
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
        val request =
            NativeNavigationRequest(
                destination.route,
                scope,
                assistantMessage = message.role == "assistant",
                error = message.details.error,
            )
        OutlinedButton(
            { open(request) },
            modifier = Modifier.testTag("native-nav-${destination.route}"),
            enabled = !state.busy,
        ) {
            Text(
                tr(
                    if (NativeRoute.parse(destination.route)?.webSettings == true)
                        Message.AI_NAV_OPEN_WEB
                    else Message.AI_NAV_OPEN,
                    label,
                )
            )
        }
    }
}
