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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import work.gadmin.finora.*
import work.gadmin.finora.data.*

@Composable
fun ReceiptsScreen(state: AppState, vm: FinoraViewModel) {
    LazyColumn(
        contentPadding = PaddingValues(22.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Ваши чеки", style = MaterialTheme.typography.headlineLarge)
                    Text("Вся история организации", color = Muted)
                }
                IconButton({ vm.loadReceipts() }, enabled = !state.receiptsLoading) {
                    LineIcon(Glyph.REFRESH, "Обновить чеки")
                }
            }
        }
        item {
            OutlinedTextField(
                state.search,
                vm::search,
                Modifier.fillMaxWidth(),
                label = { Text("Найти магазин") },
                singleLine = true,
                leadingIcon = { LineIcon(Glyph.SEARCH) },
                trailingIcon = {
                    if (state.search.isNotEmpty())
                        IconButton({ vm.search("") }) {
                            LineIcon(Glyph.CLOSE, "Очистить поиск", size = 18.dp)
                        }
                },
                shape = RoundedCornerShape(18.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            )
        }
        if (state.receiptsLoading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        if (state.receipts.isEmpty() && !state.receiptsLoading)
            item {
                EmptyState(
                    if (state.search.isBlank()) "Первый чек — начало ясности"
                    else "Ничего не найдено",
                    if (state.search.isBlank())
                        "Отсканируйте QR или сфотографируйте покупку.\nОна появится здесь."
                    else "Попробуйте другое название магазина.",
                )
                PrimaryButton(
                    "Добавить чек",
                    { vm.navigate(Page.CAPTURE) },
                    Modifier.fillMaxWidth(),
                    icon = Glyph.PLUS,
                )
            }
        items(state.receipts, key = Receipt::id) { receipt ->
            Surface(
                onClick = { vm.openReceipt(receipt.id) },
                shape = RoundedCornerShape(22.dp),
                color = Color.White,
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
                    Text("Показать ещё · ${state.receipts.size} из ${state.receiptCount}")
                }
            }
    }
}

@Composable
fun StatusBadge(status: String) {
    val warning = status !in listOf("posted", "queued", "processing")
    Surface(
        color = if (warning) Color(0xFFFFF1DC) else SoftGreen,
        shape = RoundedCornerShape(8.dp),
    ) {
        Text(
            statusLabel(status),
            Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
            color = if (warning) Color(0xFF8A5A12) else Green,
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
        contentPadding = PaddingValues(22.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        if (state.detailLoading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        if (receipt == null)
            item {
                EmptyState("Открываем чек", "Здесь будут товары, сумма и комментарии.")
                OutlinedButton(
                    { state.detailId?.let(vm::openReceipt) },
                    Modifier.fillMaxWidth(),
                    enabled = !state.detailLoading,
                ) {
                    Text("Повторить загрузку")
                }
            }
        if (receipt != null) {
            item {
                StatusBadge(receipt.status)
                Spacer(Modifier.height(12.dp))
                Text(receipt.title, style = MaterialTheme.typography.headlineMedium)
                Text(receipt.purchased_on ?: receipt.created_at.take(10), color = Muted)
                Spacer(Modifier.height(18.dp))
                Text(
                    receipt.total_minor?.let { money(it, receipt.currency) } ?: "Сумма уточняется",
                    style = MaterialTheme.typography.headlineLarge,
                )
            }
            if (receipt.isProcessing)
                item {
                    InfoCard(
                        "Чек на сервере. Можно закрыть приложение — товары появятся после распознавания.",
                        Glyph.SPARK,
                    )
                }
            receipt.error?.let { error -> item { InfoCard(error, Glyph.RECEIPT) } }
            receipt.warnings.forEach { warning -> item { InfoCard(warning, Glyph.RECEIPT) } }
            if (receipt.status == "review")
                item {
                    InfoCard(
                        "Нужна проверка перед добавлением в расходы. Администратор может исправить и подтвердить чек в веб-версии.",
                        Glyph.RECEIPT,
                    )
                }
            if (receipt.files.isNotEmpty())
                item { ReceiptPhotos(receipt, requireNotNull(state.organization).id, vm) }
            if (receipt.items.isNotEmpty()) {
                item {
                    SectionTitle("Товары · ${receipt.items.size}")
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        search,
                        { search = it },
                        Modifier.fillMaxWidth(),
                        placeholder = { Text("Поиск по названию товара") },
                        singleLine = true,
                        leadingIcon = { LineIcon(Glyph.SEARCH, size = 20.dp) },
                    )
                    Box {
                        TextButton({ categoriesOpen = true }) {
                            Text(
                                state.categories.firstOrNull { it.id == category }?.name
                                    ?: "Все категории"
                            )
                            Spacer(Modifier.width(6.dp))
                            LineIcon(Glyph.DOWN, size = 16.dp)
                        }
                        DropdownMenu(categoriesOpen, { categoriesOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Все категории") },
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
                if (filtered.isEmpty()) item { Text("Нет товаров по этому фильтру", color = Muted) }
                items(filtered, key = ReceiptItem::id) { item ->
                    Surface(color = Color.White, shape = RoundedCornerShape(17.dp)) {
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
                                    "${runCatching { item.quantity.toBigDecimal().stripTrailingZeros().toPlainString() }.getOrDefault(item.quantity)} ${item.unit} × ${money(item.unit_price_minor, receipt.currency)}",
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
                                    ?: "Без категории",
                                color = Green,
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                }
                if (search.isNotBlank() || category != null)
                    item {
                        Text(
                            "По фильтру: ${money(filtered.sumOf { it.total_minor }, receipt.currency)}",
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
            }
            item {
                OutlinedButton(
                    {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, (state.server + "/#receipts").toUri())
                        )
                    },
                    Modifier.fillMaxWidth(),
                ) {
                    Text("Открыть чеки в веб-версии")
                    Spacer(Modifier.width(8.dp))
                    LineIcon(Glyph.ARROW, size = 18.dp)
                }
                if (
                    state.organization?.isAdmin == true &&
                        !receipt.isProcessing &&
                        receipt.status != "posted"
                )
                    TextButton(vm::retryReceipt, Modifier.fillMaxWidth(), enabled = !state.busy) {
                        Text("Повторить распознавание")
                    }
            }
            item {
                SectionTitle("Комментарии")
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    comment,
                    { comment = it.take(2000) },
                    Modifier.fillMaxWidth(),
                    label = { Text("Добавить комментарий") },
                    minLines = 2,
                    enabled = !state.busy,
                )
                TextButton(
                    { vm.addComment(comment) { comment = "" } },
                    enabled = !state.busy && comment.isNotBlank(),
                ) {
                    Text(if (state.busy) "Сохраняем…" else "Отправить комментарий")
                }
            }
            items(state.comments, key = ReceiptComment::id) { entry ->
                Surface(color = Color.White, shape = RoundedCornerShape(16.dp)) {
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
            if (state.comments.isEmpty()) item { Text("Пока нет комментариев", color = Muted) }
        }
    }
}
