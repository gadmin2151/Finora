package work.gadmin.finora.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.time.Instant
import kotlinx.coroutines.delay
import work.gadmin.finora.AppState
import work.gadmin.finora.data.*
import work.gadmin.finora.localization.Message
import work.gadmin.finora.localization.tr

@Composable
fun ChatActions(message: ChatMessage, state: AppState, decide: (ChatMessage, Boolean) -> Unit) {
    var reviewed by remember(message.id) { mutableStateOf<ChatMessage?>(null) }
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(message.id) {
        while (true) {
            now = Instant.now()
            delay(30_000)
        }
    }
    val names =
        (state.accounts.map { it.id to it.name } + state.categories.map { it.id to it.name })
            .toMap()
    Surface(color = SurfaceColor, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(tr(Message.AI_ACTIONS_REVIEW), fontWeight = FontWeight.SemiBold)
            ActionValues(message, names)
            message.details.action_results.orEmpty().forEach { result ->
                Text(
                    "${result.label}: ${result.detail}",
                    color = if (result.status >= 300) MaterialTheme.colorScheme.error else Ink,
                )
            }
            if (message.ownsPendingActions(state.user?.id)) {
                if (!message.canConfirmActions(state.user?.id, now))
                    Text(tr(Message.AI_ACTIONS_EXPIRED_OR_INVALID), color = Muted)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        { reviewed = message },
                        modifier = Modifier.testTag("ai-action-review"),
                        enabled = !state.busy && message.canConfirmActions(state.user?.id, now),
                    ) {
                        Text(tr(Message.AI_ACTIONS_EXECUTE))
                    }
                    OutlinedButton({ decide(message, false) }, enabled = !state.busy) {
                        Text(tr(Message.AI_ACTIONS_CANCEL))
                    }
                }
            } else {
                Text(
                    tr(
                        when (message.details.action_status) {
                            "completed" -> Message.AI_ACTIONS_COMPLETED
                            "cancelled" -> Message.AI_ACTIONS_CANCELLED
                            "executing" -> Message.AI_ACTIONS_EXECUTING
                            "failed" -> Message.AI_ACTIONS_FAILED
                            "uncertain" -> Message.AI_ACTIONS_UNCERTAIN
                            else -> Message.AI_ACTIONS_OWNER
                        }
                    ),
                    color = Muted,
                )
            }
        }
    }
    reviewed?.let { snapshot ->
        val current =
            message.sameProposal(snapshot) && message.canConfirmActions(state.user?.id, now)
        AlertDialog(
            onDismissRequest = { reviewed = null },
            title = { Text(tr(Message.AI_ACTIONS_CONFIRM)) },
            text = {
                Column(
                    Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (!current)
                        Text(
                            tr(Message.AI_ACTIONS_CHANGED),
                            color = MaterialTheme.colorScheme.error,
                        )
                    ActionValues(snapshot, names, showParameters = true)
                }
            },
            confirmButton = {
                TextButton(
                    {
                        reviewed = null
                        decide(snapshot, true)
                    },
                    enabled = current && !state.busy,
                    modifier = Modifier.testTag("ai-action-confirm"),
                ) {
                    Text(tr(Message.AI_ACTIONS_EXECUTE))
                }
            },
            dismissButton = { TextButton({ reviewed = null }) { Text(tr(Message.REVIEW_AGAIN)) } },
        )
    }
}

@Composable
private fun ActionValues(
    message: ChatMessage,
    names: Map<String, String>,
    showParameters: Boolean = false,
) {
    var expanded by remember(message.id) { mutableStateOf(showParameters) }
    message.details.actions.orEmpty().take(6).forEach { action ->
        Text(action.label, fontWeight = FontWeight.SemiBold)
        Text(action.description)
        val preview = action.preview()
        if (preview == null)
            Text(tr(Message.AI_ACTIONS_EXPIRED_OR_INVALID), color = MaterialTheme.colorScheme.error)
        else {
            preview.body.forEach { field -> ActionField(field, names) }
            TextButton({ expanded = !expanded }) { Text(tr(Message.AI_ACTIONS_PARAMETERS)) }
            if (expanded) {
                Text(action.operation, style = MaterialTheme.typography.bodySmall)
                preview.parameters.forEach { field -> ActionField(field, names) }
            }
        }
        HorizontalDivider()
    }
}

@Composable
private fun ActionField(field: ChatActionField, names: Map<String, String>) {
    val labels =
        mapOf(
            "amount" to Message.AI_ACTION_AMOUNT,
            "currency" to Message.CURRENCY,
            "occurred_on" to Message.AI_ACTION_DATE,
            "purchased_on" to Message.AI_ACTION_DATE,
            "account_id" to Message.ACCOUNT,
            "category_id" to Message.CATEGORY,
            "name" to Message.AI_ACTION_NAME,
            "merchant" to Message.STORE,
            "merchant_address" to Message.STORE_ADDRESS,
            "note" to Message.NOTE,
            "total" to Message.AI_ACTION_TOTAL,
            "quantity" to Message.QUANTITY,
            "unit_price" to Message.PRICE,
            "kind" to Message.AI_ACTION_KIND,
            "direction" to Message.AI_ACTION_DIRECTION,
            "balance" to Message.AI_ACTION_BALANCE,
            "version" to Message.AI_ACTION_VERSION,
            "active" to Message.AI_ACTION_ACTIVE,
            "archived" to Message.AI_ACTION_ARCHIVED,
            "due_on" to Message.AI_ACTION_DUE_DATE,
            "items" to Message.AI_ACTION_ITEMS,
            "text" to Message.AI_ACTION_TEXT,
            "to_account_id" to Message.AI_ACTION_TARGET_ACCOUNT,
            "fx_rate" to Message.AI_ACTION_EXCHANGE_RATE,
            "limit" to Message.AI_ACTION_LIMIT,
            "month" to Message.AI_ACTION_MONTH,
            "unit" to Message.AI_ACTION_UNIT,
        )
    val label = field.path.joinToString(" · ") { labels[it]?.let(::tr) ?: it }
    val raw = field.value?.content
    val value =
        when (raw) {
            null -> "—"
            "true" -> if (!field.value.isString) tr(Message.AI_ACTION_YES) else raw
            "false" -> if (!field.value.isString) tr(Message.AI_ACTION_NO) else raw
            else -> names[raw]?.let { "$it ($raw)" } ?: raw
        }
    Column {
        Text(label, color = Muted, style = MaterialTheme.typography.labelSmall)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
