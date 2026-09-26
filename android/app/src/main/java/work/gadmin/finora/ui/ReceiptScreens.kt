package work.gadmin.finora.ui

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import work.gadmin.finora.*
import work.gadmin.finora.data.*
import work.gadmin.finora.localization.Message
import work.gadmin.finora.localization.tr

@Composable
fun ReceiptsScreen(state: AppState, vm: FinoraViewModel) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(22.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(tr(Message.YOUR_RECEIPTS), style = MaterialTheme.typography.headlineLarge)
                    Text(tr(Message.YOUR_ORGANIZATION_S_FULL_HISTORY), color = Muted)
                }
                IconButton(vm::refreshCurrent, enabled = !state.refreshing && !state.busy) {
                    LineIcon(Glyph.REFRESH, tr(Message.REFRESH_RECEIPTS))
                }
            }
        }
        item {
            OutlinedTextField(
                state.search,
                vm::search,
                Modifier.fillMaxWidth(),
                label = { Text(tr(Message.FIND_A_STORE)) },
                singleLine = true,
                leadingIcon = { LineIcon(Glyph.SEARCH) },
                trailingIcon = {
                    if (state.search.isNotEmpty())
                        IconButton({ vm.search("") }) {
                            LineIcon(Glyph.CLOSE, tr(Message.CLEAR_SEARCH), size = 18.dp)
                        }
                },
                shape = RoundedCornerShape(18.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            )
        }
        if (state.receiptsLoading && !state.refreshing)
            item { BrandLoading(tr(Message.LOADING_YOUR_RECEIPTS), compact = true) }
        if (state.receipts.isEmpty() && !state.receiptsLoading)
            item {
                EmptyState(
                    if (state.search.isBlank()) tr(Message.YOUR_FIRST_RECEIPT_IS_A_FRESH_START)
                    else tr(Message.NOTHING_FOUND),
                    if (state.search.isBlank())
                        tr(Message.SCAN_A_QR_CODE_OR_PHOTOGRAPH_A_PURCHASE_IT_WILL_APPEAR_HER)
                    else tr(Message.TRY_ANOTHER_STORE_NAME),
                )
                PrimaryButton(
                    tr(Message.ADD_RECEIPT),
                    { vm.navigate(Page.CAPTURE) },
                    Modifier.fillMaxWidth(),
                    icon = Glyph.PLUS,
                )
            }
        items(state.receipts, key = Receipt::id) { receipt ->
            Surface(
                onClick = { vm.openReceipt(receipt.id) },
                shape = RoundedCornerShape(22.dp),
                color = SurfaceColor,
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Surface(color = SoftGreen, shape = RoundedCornerShape(14.dp)) {
                            Box(Modifier.padding(11.dp)) {
                                LineIcon(if (receipt.source == "mev") Glyph.SCAN else Glyph.RECEIPT)
                            }
                        }
                        Column(Modifier.weight(1f)) {
                            Text(
                                receipt.title,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                receipt.purchased_on ?: receipt.created_at.take(10),
                                style = MaterialTheme.typography.bodySmall,
                                color = Muted,
                            )
                        }
                        LineIcon(Glyph.CHEVRON, size = 17.dp, tint = Muted)
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        StatusBadge(receipt.status)
                        Text(
                            receipt.total_minor?.let { money(it, receipt.currency) } ?: "—",
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }
        if (state.receipts.size < state.receiptCount)
            item {
                OutlinedButton(
                    { vm.loadReceipts(more = true) },
                    Modifier.fillMaxWidth(),
                    enabled = !state.receiptsLoading,
                ) {
                    Text(tr(Message.SHOW_MORE_1_S_OF_2_S, state.receipts.size, state.receiptCount))
                }
            }
    }
}

@Composable
fun StatusBadge(status: String) {
    val warning = status !in listOf("posted", "queued", "processing")
    Surface(
        color =
            if (status in setOf("failed", "error")) CoralSurface
            else if (warning) AmberSurface else SoftGreen,
        shape = RoundedCornerShape(8.dp),
    ) {
        Text(
            statusLabel(status),
            Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
            color =
                if (status in setOf("failed", "error")) Coral else if (warning) Amber else Green,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@Composable
fun ReceiptDetailScreen(state: AppState, vm: FinoraViewModel) {
    var comment by rememberSaveable(state.detailId) { mutableStateOf("") }
    var search by rememberSaveable(state.detailId) { mutableStateOf("") }
    var category by rememberSaveable(state.detailId) { mutableStateOf<String?>(null) }
    var categoriesOpen by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val receipt = state.detail
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(22.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        if (state.detailLoading && !state.refreshing)
            item { BrandLoading(tr(Message.OPENING_RECEIPT), compact = true) }
        if (receipt == null && !state.detailLoading)
            item {
                EmptyState(
                    tr(Message.COULD_NOT_LOAD_THE_RECEIPT),
                    tr(Message.PULL_DOWN_TO_TRY_LOADING_AGAIN),
                )
                OutlinedButton(
                    { state.detailId?.let(vm::openReceipt) },
                    Modifier.fillMaxWidth(),
                    enabled = !state.detailLoading,
                ) {
                    Text(tr(Message.RETRY_LOADING))
                }
            }
        if (receipt != null) {
            item {
                StatusBadge(receipt.status)
                Spacer(Modifier.height(12.dp))
                Text(receipt.title, style = MaterialTheme.typography.headlineMedium)
                if (receipt.merchant_address.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        receipt.merchant_address,
                        color = Muted,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Text(receipt.purchased_on ?: receipt.created_at.take(10), color = Muted)
                Spacer(Modifier.height(18.dp))
                Text(
                    receipt.total_minor?.let { money(it, receipt.currency) }
                        ?: tr(Message.CALCULATING_TOTAL),
                    style = MaterialTheme.typography.headlineLarge,
                )
            }
            if (receipt.isProcessing)
                item {
                    BrandLoading(tr(Message.READING_ITEMS_AND_TOTAL), compact = true)
                    Spacer(Modifier.height(10.dp))
                    Text(
                        tr(Message.YOU_CAN_CLOSE_THE_APP_PROCESSING_WILL_CONTINUE_ON_THE_SERV),
                        color = Muted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            receipt.error?.let { error -> item { InfoCard(error, Glyph.RECEIPT) } }
            receipt.warnings.forEach { warning -> item { InfoCard(warning, Glyph.RECEIPT) } }
            if (receipt.status == "review")
                item {
                    InfoCard(
                        tr(Message.CHECK_THE_ITEMS_AND_TOTAL_THE_CONFIRMATION_BUTTON_IS_BELOW),
                        Glyph.RECEIPT,
                    )
                }
            if (receipt.files.isNotEmpty())
                item { ReceiptPhotos(receipt, requireNotNull(state.organization).id, vm) }
            if (receipt.items.isNotEmpty()) {
                item {
                    SectionTitle(tr(Message.ITEMS_1_S, receipt.items.size))
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        search,
                        { search = it },
                        Modifier.fillMaxWidth(),
                        placeholder = { Text(tr(Message.SEARCH_BY_ITEM_NAME)) },
                        singleLine = true,
                        leadingIcon = { LineIcon(Glyph.SEARCH, size = 20.dp) },
                    )
                    Box {
                        TextButton({ categoriesOpen = true }) {
                            Text(
                                state.categories.firstOrNull { it.id == category }?.name
                                    ?: tr(Message.ALL_CATEGORIES)
                            )
                            Spacer(Modifier.width(6.dp))
                            LineIcon(Glyph.DOWN, size = 16.dp)
                        }
                        DropdownMenu(categoriesOpen, { categoriesOpen = false }) {
                            DropdownMenuItem(
                                text = { Text(tr(Message.ALL_CATEGORIES)) },
                                onClick = {
                                    category = null
                                    categoriesOpen = false
                                },
                            )
                            state.categories
                                .filter { candidate ->
                                    receipt.items.any { it.category_id == candidate.id }
                                }
                                .forEach { candidate ->
                                    DropdownMenuItem(
                                        text = { Text(candidate.name) },
                                        onClick = {
                                            category = candidate.id
                                            categoriesOpen = false
                                        },
                                    )
                                }
                        }
                    }
                }
                val filtered =
                    receipt.items.filter {
                        it.name.contains(search, ignoreCase = true) &&
                            (category == null || it.category_id == category)
                    }
                if (filtered.isEmpty())
                    item { Text(tr(Message.NO_ITEMS_MATCH_THIS_FILTER), color = Muted) }
                items(filtered, key = ReceiptItem::id) { item ->
                    Surface(color = SurfaceColor, shape = RoundedCornerShape(17.dp)) {
                        Column(
                            Modifier.fillMaxWidth().padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(5.dp),
                        ) {
                            Text(item.name, fontWeight = FontWeight.Medium)
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    "${runCatching { item.quantity.toBigDecimal().stripTrailingZeros().toPlainString() }.getOrDefault(item.quantity)} ${unitLabel(item.unit)} × ${money(item.unit_price_minor, receipt.currency)}",
                                    color = Muted,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    money(item.total_minor, receipt.currency),
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                            Text(
                                state.categories.firstOrNull { it.id == item.category_id }?.name
                                    ?: tr(Message.UNCATEGORIZED),
                                color = Green,
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                }
                if (search.isNotBlank() || category != null)
                    item {
                        Text(
                            tr(
                                Message.FILTERED_TOTAL_1_S,
                                money(filtered.sumOf { it.total_minor }, receipt.currency),
                            ),
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
            }
            if (receipt.status == "review") item { ReceiptReview(state, receipt, vm) }
            item {
                OutlinedButton(
                    {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, (state.server + "/#receipts").toUri())
                        )
                    },
                    Modifier.fillMaxWidth(),
                ) {
                    Text(tr(Message.OPEN_RECEIPTS_ON_THE_WEBSITE))
                    Spacer(Modifier.width(8.dp))
                    LineIcon(Glyph.ARROW, size = 18.dp)
                }
                if (
                    state.organization?.isAdmin == true &&
                        !receipt.isProcessing &&
                        receipt.status != "posted"
                )
                    TextButton(vm::retryReceipt, Modifier.fillMaxWidth(), enabled = !state.busy) {
                        Text(tr(Message.RETRY_RECOGNITION))
                    }
            }
            item {
                SectionTitle(tr(Message.COMMENTS))
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    comment,
                    { comment = it.take(2000) },
                    Modifier.fillMaxWidth(),
                    label = { Text(tr(Message.ADD_A_COMMENT)) },
                    minLines = 2,
                    enabled = !state.busy,
                )
                TextButton(
                    { vm.addComment(comment) { comment = "" } },
                    enabled = !state.busy && comment.isNotBlank(),
                ) {
                    Text(if (state.busy) tr(Message.SAVING) else tr(Message.SEND_COMMENT))
                }
            }
            items(state.comments, key = ReceiptComment::id) { entry ->
                Surface(color = SurfaceColor, shape = RoundedCornerShape(16.dp)) {
                    Column(
                        Modifier.fillMaxWidth().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            entry.author,
                            fontWeight = FontWeight.SemiBold,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(entry.text)
                        Text(
                            entry.created_at.take(16).replace('T', ' '),
                            style = MaterialTheme.typography.bodySmall,
                            color = Muted,
                        )
                    }
                }
            }
            if (state.comments.isEmpty()) item { Text(tr(Message.NO_COMMENTS_YET), color = Muted) }
        }
    }
}
