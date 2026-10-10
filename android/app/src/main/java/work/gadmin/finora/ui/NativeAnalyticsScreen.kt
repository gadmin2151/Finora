package work.gadmin.finora.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import work.gadmin.finora.data.*
import work.gadmin.finora.localization.*

@Composable
fun NativeAnalyticsScreen(
    state: NativeAnalyticsState,
    controller: NativeAnalyticsController,
    openReceipt: (String) -> Unit,
    month: (Long) -> Unit,
    busy: Boolean,
) {
    val ru = LanguageRuntime.language == AppLanguage.RUSSIAN
    fun text(ruText: String, enText: String) = if (ru) ruText else enText
    LazyColumn(
        Modifier.fillMaxSize().testTag("native-analytics"),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Text(
                if (state.mode == NativeAnalyticsMode.REPORTS) text("Отчёты", "Reports")
                else text("Покупки", "Purchases"),
                style = MaterialTheme.typography.headlineSmall,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton({ month(-1) }, enabled = !busy && !state.loading) { Text("‹") }
                Text(state.scope?.month.orEmpty(), Modifier.padding(12.dp))
                TextButton({ month(1) }, enabled = !busy && !state.loading) { Text("›") }
            }
        }
        if (state.mode == NativeAnalyticsMode.REPORTS) {
            item {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    NativeReportKind.entries.forEach { kind ->
                        FilterChip(
                            state.kind == kind,
                            { controller.kind(kind) },
                            {
                                Text(
                                    when (kind) {
                                        NativeReportKind.SUMMARY -> text("Сводка", "Summary")
                                        NativeReportKind.CATEGORIES ->
                                            text("Категории", "Categories")
                                        NativeReportKind.MERCHANTS -> text("Магазины", "Merchants")
                                        NativeReportKind.TREND -> text("Динамика", "Trend")
                                        NativeReportKind.PRICES -> text("Цены", "Prices")
                                    }
                                )
                            },
                            enabled = !busy && !state.loading,
                        )
                    }
                }
            }
            state.report?.let { report -> item { ReportBlock(report, openReceipt) } }
        } else {
            item {
                Text(text("Покупок: ", "Purchases: ") + state.purchases.total)
                state.purchases.totals.forEach { Text(money(it.total_minor, it.currency)) }
            }
            items(state.purchases.items, key = PurchaseItem::id) { item ->
                Surface(
                    onClick = { if (!busy) openReceipt(item.receipt_id) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(item.name, style = MaterialTheme.typography.titleMedium)
                        Text(
                            "${item.quantity} ${unitLabel(item.unit)} · ${money(item.total_minor, item.currency)}"
                        )
                        Text(
                            listOfNotNull(
                                    item.merchant.takeIf(String::isNotBlank),
                                    item.purchased_on,
                                )
                                .joinToString(" · ")
                        )
                        ReceiptAuthorLabel(item.creator)
                        Text(tr(Message.OPEN_RECEIPT), style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
            if (!state.loading && state.error == null && state.purchases.items.isEmpty())
                item { Text(text("За этот месяц покупок нет", "No purchases this month")) }
            if (state.purchases.items.size < state.purchases.total)
                item {
                    OutlinedButton(
                        { controller.load(more = true) },
                        enabled = !state.loading && !busy,
                    ) {
                        Text(tr(Message.LOAD_MORE_PURCHASES))
                    }
                }
        }
        if (state.loading) item { BrandLoading(tr(Message.LOADING), compact = true) }
        state.error?.let { error ->
            item {
                Text(error, color = MaterialTheme.colorScheme.error)
                TextButton({ controller.load() }, enabled = !busy) { Text(tr(Message.TRY_AGAIN)) }
            }
        }
    }
}
