package work.gadmin.finora.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import work.gadmin.finora.AppState
import work.gadmin.finora.FinoraViewModel
import work.gadmin.finora.localization.*

@Composable
fun AccountingSettings(state: AppState, vm: FinoraViewModel) {
    val config = state.accounting
    Surface(color = SurfaceColor, shape = RoundedCornerShape(26.dp)) {
        Column(
            Modifier.fillMaxWidth().padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(tr(Message.ACCOUNTING_TITLE), style = MaterialTheme.typography.titleLarge)
            Text(tr(Message.ACCOUNTING_SCOPE, state.organization?.name.orEmpty()), color = Muted)
            if (config == null) {
                if (state.error == null) BrandLoading(tr(Message.LOADING), compact = true)
                else Text(state.error, color = Coral)
            } else
                key(state.organization?.id, config.version) {
                    var mode by remember { mutableStateOf(config.mode) }
                    var account by remember { mutableStateOf(config.default_account_id) }
                    var move by remember { mutableStateOf(false) }
                    var open by remember { mutableStateOf(false) }
                    val enabled = state.organization?.isAdmin == true && !state.busy
                    listOf("separate", "combined").forEach { value ->
                        Row(
                            Modifier.fillMaxWidth()
                                .selectable(
                                    mode == value,
                                    enabled = enabled,
                                    role = Role.RadioButton,
                                    onClick = { mode = value },
                                )
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = mode == value, onClick = null, enabled = enabled)
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text(
                                    tr(
                                        if (value == "combined") Message.ACCOUNTING_COMBINED
                                        else Message.ACCOUNTING_SEPARATE
                                    )
                                )
                                Text(
                                    tr(
                                        if (value == "combined") Message.ACCOUNTING_COMBINED_HINT
                                        else Message.ACCOUNTING_SEPARATE_HINT
                                    ),
                                    color = Muted,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                    Text(
                        tr(if (mode == "combined") Message.ACCOUNTING_PRIMARY else Message.ACCOUNT),
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Box {
                        OutlinedButton(
                            { open = true },
                            enabled = enabled,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                state.accounts
                                    .firstOrNull { it.id == account }
                                    ?.let { "${it.name} · ${it.currency}" }
                                    ?: tr(Message.CHOOSE_AN_ACCOUNT)
                            )
                            Spacer(Modifier.width(8.dp))
                            LineIcon(Glyph.CHEVRON, size = 16.dp)
                        }
                        DropdownMenu(open, { open = false }) {
                            state.accounts
                                .filter {
                                    !it.archived && (mode != "combined" || it.currency == "MDL")
                                }
                                .forEach { a ->
                                    DropdownMenuItem(
                                        text = { Text("${a.name} · ${a.currency}") },
                                        onClick = {
                                            account = a.id
                                            open = false
                                        },
                                    )
                                }
                        }
                    }
                    if (mode == "combined" && state.organization?.isAdmin == true) {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(move, { move = it }, enabled = enabled)
                            Text(
                                tr(Message.ACCOUNTING_MOVE_RECEIPTS),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                    Text(
                        tr(Message.ACCOUNTING_HISTORY_HINT),
                        color = Muted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (state.organization?.isAdmin == true) {
                        Button(
                            {
                                vm.saveAccounting(
                                    config.copy(mode = mode, default_account_id = account),
                                    mode == "combined" && move,
                                )
                            },
                            enabled =
                                enabled &&
                                    (mode != "combined" ||
                                        state.accounts.any {
                                            it.id == account && it.currency == "MDL" && !it.archived
                                        }),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(tr(if (state.busy) Message.SAVING else Message.CONFIRM_AND_SAVE))
                        }
                    }
                }
        }
    }
}
