package work.gadmin.finora.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import work.gadmin.finora.*
import work.gadmin.finora.data.*
import work.gadmin.finora.localization.*

@Composable
fun WalletCard(state: AppState, vm: FinoraViewModel) {
    val data by vm.wallet.state.collectAsStateWithLifecycle()
    val accounts = state.dashboard?.accounts.orEmpty()
    Surface(color = HeroStart, shape = RoundedCornerShape(28.dp)) {
        Column(
            Modifier.fillMaxWidth()
                .background(Brush.linearGradient(listOf(HeroStart, HeroEnd)))
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        tr(Message.CURRENT_WALLET),
                        style = MaterialTheme.typography.titleMedium,
                        color = Mint,
                    )
                    Text(
                        tr(Message.WALLET_CURRENT_NOT_MONTH),
                        color = Mint,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                BrandMark(Modifier.size(48.dp))
            }
            if (accounts.isEmpty()) Text(tr(Message.NO_WALLET_ACCOUNTS), color = HeroInk)
            walletTotals(accounts).forEach { (currency, amount) ->
                Text(
                    money(amount, currency),
                    style = MaterialTheme.typography.headlineMedium,
                    color = HeroInk,
                )
            }
            HorizontalDivider(color = Mint.copy(alpha = .2f))
            accounts.forEach { account ->
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(account.name, color = HeroInk, fontWeight = FontWeight.Medium)
                        if (account.archived)
                            Text(
                                tr(Message.ARCHIVED_ACCOUNT),
                                color = Mint,
                                style = MaterialTheme.typography.labelSmall,
                            )
                        Text(money(account.balance_minor, account.currency), color = Mint)
                    }
                    if (state.organization?.isAdmin == true && !account.archived)
                        OutlinedButton({ vm.wallet.open(account) }, enabled = !state.busy) {
                            Text(tr(Message.ADJUST_BALANCE), color = HeroInk)
                        }
                }
            }
        }
    }
    data.editor?.let { WalletEditor(it, data, state.busy, vm.wallet) }
}

@Composable
private fun WalletEditor(
    form: WalletEdit,
    state: WalletState,
    busy: Boolean,
    controller: WalletController,
) {
    val enabled = !busy && !state.refreshing
    val target = runCatching { form.targetMinor() }.getOrNull()
    Dialog(
        controller::close,
        properties =
            DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Scaffold(
            Modifier.fillMaxSize().safeDrawingPadding().imePadding(),
            containerColor = Paper,
            topBar = {
                Row(
                    Modifier.fillMaxWidth().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(controller::close, enabled = enabled) {
                        LineIcon(Glyph.BACK, tr(Message.CLOSE_FORM))
                    }
                    Column {
                        Text(
                            tr(Message.ADJUST_BALANCE),
                            style = MaterialTheme.typography.titleLarge,
                        )
                        Text(form.account.name, color = Muted)
                    }
                }
            },
            bottomBar = {
                Surface(color = SurfaceColor) {
                    Column(
                        Modifier.fillMaxWidth().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        state.error?.let {
                            Text(it, color = Coral, style = MaterialTheme.typography.bodySmall)
                        }
                        if (state.stale)
                            OutlinedButton(
                                controller::reload,
                                enabled = enabled,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(tr(Message.REFRESH_ACCOUNT_BALANCE))
                            }
                        Button(
                            controller::save,
                            enabled = enabled && !state.stale,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                if (busy) tr(Message.SAVING) else tr(Message.CONFIRM_BALANCE_CHANGE)
                            )
                        }
                    }
                }
            },
        ) { padding ->
            Column(
                Modifier.fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                InfoCard(
                    tr(
                        Message.CURRENT_ACCOUNT_BALANCE,
                        money(form.account.balance_minor, form.account.currency),
                    ),
                    Glyph.WALLET,
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    BalanceMode.entries.forEach { mode ->
                        FilterChip(
                            selected = form.mode == mode,
                            enabled = enabled,
                            onClick = {
                                controller.change(
                                    form.copy(
                                        mode = mode,
                                        amount =
                                            if (mode == BalanceMode.SET)
                                                editableMoney(form.account.balance_minor)
                                            else "",
                                    )
                                )
                            },
                            label = {
                                Text(
                                    tr(
                                        when (mode) {
                                            BalanceMode.SET -> Message.SET_ACTUAL_BALANCE
                                            BalanceMode.ADD -> Message.ADD_MONEY
                                            BalanceMode.SUBTRACT -> Message.SUBTRACT_MONEY
                                        }
                                    )
                                )
                            },
                        )
                    }
                }
                OutlinedTextField(
                    form.amount,
                    { controller.change(form.copy(amount = it)) },
                    enabled = enabled,
                    singleLine = true,
                    label = {
                        Text(
                            if (form.mode == BalanceMode.SET) tr(Message.ACTUAL_BALANCE)
                            else tr(Message.AMOUNT_1_S, form.account.currency)
                        )
                    },
                    suffix = { Text(form.account.currency) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (target != null)
                    Text(
                        tr(Message.BALANCE_AFTER_CHANGE, money(target, form.account.currency)),
                        color = Green,
                        fontWeight = FontWeight.SemiBold,
                    )
                Surface(color = SoftGreen, shape = RoundedCornerShape(18.dp)) {
                    Column(
                        Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                tr(Message.COUNT_DIFFERENCE_AS_INCOME_EXPENSE),
                                Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Switch(
                                form.countAsIncomeExpense,
                                { controller.change(form.copy(countAsIncomeExpense = it)) },
                                enabled = enabled,
                            )
                        }
                        Text(
                            tr(
                                if (form.countAsIncomeExpense) Message.BALANCE_INCOME_EXPENSE_HINT
                                else Message.BALANCE_ONLY_HINT
                            ),
                            color = Muted,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                OutlinedTextField(
                    form.date,
                    { controller.change(form.copy(date = it)) },
                    enabled = enabled,
                    label = { Text(tr(Message.TRANSACTION_DATE)) },
                    placeholder = { Text("YYYY-MM-DD") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (form.account.currency != "MDL")
                    OutlinedTextField(
                        form.fxRate,
                        { controller.change(form.copy(fxRate = it)) },
                        enabled = enabled,
                        label = {
                            Text(tr(Message.EXCHANGE_RATE_1_1_S_IN_MDL, form.account.currency))
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth(),
                    )
                OutlinedTextField(
                    form.note,
                    { controller.change(form.copy(note = it)) },
                    enabled = enabled,
                    label = { Text(tr(Message.NOTE_OPTIONAL)) },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
