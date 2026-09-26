package work.gadmin.finora.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import work.gadmin.finora.FinoraViewModel
import work.gadmin.finora.data.*
import work.gadmin.finora.localization.*

@Composable
fun CategoryPurchasesScreen(vm: FinoraViewModel) {
    val state by vm.purchases.state.collectAsStateWithLifecycle()
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(22.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Text(state.category?.name.orEmpty(), style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            Text(
                YearMonth.parse(state.month)
                    .format(
                        DateTimeFormatter.ofPattern("LLLL yyyy", LanguageRuntime.language.locale)
                    ),
                color = Muted,
            )
            Text(
                tr(Message.CATEGORY_PURCHASES_HINT),
                style = MaterialTheme.typography.bodySmall,
                color = Muted,
            )
            Spacer(Modifier.height(12.dp))
            Text(tr(Message.PURCHASE_COUNT, state.total), fontWeight = FontWeight.Medium)
            state.totals.forEach { Text(money(it.total_minor, it.currency), color = Green) }
        }
        items(state.items, key = PurchaseItem::id) { item ->
            Surface(
                onClick = { vm.openReceipt(item.receipt_id) },
                color = SurfaceColor,
                shape = RoundedCornerShape(18.dp),
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    Text(item.name, fontWeight = FontWeight.SemiBold)
                    Text(
                        "${item.quantity} ${unitLabel(item.unit)} · ${money(item.total_minor, item.currency)}",
                        color = Green,
                    )
                    Text(
                        listOfNotNull(item.merchant.takeIf(String::isNotBlank), item.purchased_on)
                            .joinToString(" · "),
                        color = Muted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row {
                        Text(tr(Message.OPEN_RECEIPT), style = MaterialTheme.typography.labelMedium)
                        Spacer(Modifier.weight(1f))
                        LineIcon(Glyph.CHEVRON, size = 17.dp)
                    }
                }
            }
        }
        if (state.error != null)
            item {
                InfoCard(requireNotNull(state.error), Glyph.REFRESH)
                TextButton({ vm.purchases.load(more = state.items.isNotEmpty()) }) {
                    Text(tr(Message.TRY_AGAIN))
                }
            }
        if (state.loading) item { BrandLoading(tr(Message.LOADING), compact = true) }
        else if (state.items.isEmpty() && state.error == null)
            item { InfoCard(tr(Message.NO_PURCHASES_IN_CATEGORY), Glyph.RECEIPT) }
        else if (state.offset < state.total)
            item {
                OutlinedButton({ vm.purchases.load(more = true) }, Modifier.fillMaxWidth()) {
                    Text(tr(Message.LOAD_MORE_PURCHASES))
                }
            }
    }
}
