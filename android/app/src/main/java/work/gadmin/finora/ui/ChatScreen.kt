package work.gadmin.finora.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale
import work.gadmin.finora.AppState
import work.gadmin.finora.FinoraViewModel
import work.gadmin.finora.Page
import work.gadmin.finora.data.AnalyticsReport
import work.gadmin.finora.data.ChatMessage

@Composable
fun ChatScreen(state: AppState, vm: FinoraViewModel) {
    val list = rememberLazyListState()
    val pending = state.chatJobs.firstOrNull { it.isPending }
    val lastId = state.chat.lastOrNull()?.id
    LaunchedEffect(lastId) {
        if (
            lastId != null &&
                (list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >=
                    list.layoutInfo.totalItemsCount - 4
        ) {
            list.animateScrollToItem((list.layoutInfo.totalItemsCount - 1).coerceAtLeast(0))
        }
    }
    Column(Modifier.fillMaxSize().imePadding()) {
        LazyColumn(
            state = list,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(key = "header") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Деньги. Понятным языком.", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "Общий чат организации · суммы из вашего учёта",
                        color = Muted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        IconButton({ vm.month(-1) }, enabled = !state.busy) {
                            LineIcon(Glyph.BACK, "Предыдущий месяц", size = 18.dp)
                        }
                        Text(
                            YearMonth.parse(state.month)
                                .format(
                                    DateTimeFormatter.ofPattern(
                                        "LLLL yyyy",
                                        Locale.forLanguageTag("ru"),
                                    )
                                ),
                            style = MaterialTheme.typography.labelLarge,
                        )
                        IconButton({ vm.month(1) }, enabled = !state.busy) {
                            LineIcon(Glyph.CHEVRON, "Следующий месяц", size = 18.dp)
                        }
                    }
                }
            }
            if (state.chatSyncError != null)
                item(key = "offline") { InfoCard(state.chatSyncError, Glyph.REFRESH) }
            if (state.chat.isEmpty()) {
                item(key = "welcome") {
                    if (state.chatLoading) BrandLoading("Открываю историю", compact = true)
                    else
                        Surface(color = HeroStart, shape = RoundedCornerShape(24.dp)) {
                            Column(
                                Modifier.fillMaxWidth()
                                    .background(Brush.linearGradient(listOf(HeroStart, HeroEnd)))
                                    .padding(24.dp),
                                verticalArrangement = Arrangement.spacedBy(18.dp),
                            ) {
                                BrandPulse(Modifier.size(60.dp))
                                Text(
                                    "От вопроса к ясности",
                                    style = MaterialTheme.typography.titleLarge,
                                )
                                Text(
                                    "Сравню периоды, найду покупки и покажу, из чего складываются расходы.",
                                    color = Mint,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                listOf(
                                        "На чём я могу сэкономить в этом месяце?",
                                        "Найди LAPTE за последние 3 месяца",
                                        "Сравни расходы на продукты за август и сентябрь",
                                    )
                                    .forEach { prompt ->
                                        OutlinedButton(
                                            onClick = { vm.chatDraft(prompt) },
                                            modifier = Modifier.fillMaxWidth(),
                                            border = BorderStroke(1.dp, Mint.copy(alpha = .25f)),
                                        ) {
                                            Text(prompt, color = Mint)
                                        }
                                    }
                            }
                        }
                }
            } else {
                if (state.chatHasOlder)
                    item(key = "older") {
                        TextButton(
                            onClick = { vm.loadChat(older = true) },
                            enabled = !state.chatLoading && !state.busy,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(if (state.chatLoading) "Загружаю…" else "Более ранние сообщения")
                        }
                    }
                items(state.chat, key = ChatMessage::id) { message ->
                    ChatBubble(message, vm::openReceipt)
                }
            }
            if (pending != null)
                item(key = "pending") { BrandLoading(pending.progress, compact = true) }
            item(key = "end") { Spacer(Modifier.height(4.dp)) }
        }
        Surface(color = SurfaceColor, tonalElevation = 2.dp) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    listOf(
                            "summary" to "Сводка",
                            "categories" to "Категории",
                            "prices" to "Мои цены",
                        )
                        .forEach { (kind, label) ->
                            SuggestionChip(
                                onClick = { vm.sendChat(kind) },
                                label = { Text(label) },
                                enabled = !state.busy && pending == null,
                            )
                        }
                }
                Row(
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    IconButton(onClick = { vm.navigate(Page.CAPTURE) }, enabled = !state.busy) {
                        LineIcon(Glyph.CAMERA, "Добавить фото чека")
                    }
                    OutlinedTextField(
                        value = state.chatDraft,
                        onValueChange = vm::chatDraft,
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Ваш вопрос…") },
                        maxLines = 4,
                        shape = RoundedCornerShape(18.dp),
                        enabled = !state.busy,
                    )
                    FilledIconButton(
                        onClick = { vm.sendChat() },
                        enabled = state.chatDraft.isNotBlank() && !state.busy && pending == null,
                        modifier = Modifier.size(48.dp),
                    ) {
                        LineIcon(Glyph.CHEVRON, "Отправить сообщение", tint = OnPrimary)
                    }
                }
                Text(
                    "Быстрые отчёты работают без AI. Ответы AI стоит проверять.",
                    style = MaterialTheme.typography.labelSmall,
                    color = Muted,
                )
            }
        }
    }
}

@Composable
private fun ChatBubble(message: ChatMessage, openReceipt: (String) -> Unit) {
    val user = message.role == "user"
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = if (user) Alignment.End else Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Surface(
            color =
                if (message.details.error) CoralSurface else if (user) SoftGreen else SurfaceColor,
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.widthIn(max = 620.dp),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    if (user) "Участник организации"
                    else
                        when (message.details.provider) {
                            "reports" -> "Finora · точный расчёт"
                            "ollama" -> "Finora · локальный AI"
                            else -> "Finora · помощник"
                        },
                    color = if (user) Mint else Green,
                    style = MaterialTheme.typography.labelSmall,
                )
                SelectionContainer {
                    Text(
                        message.text,
                        color = if (message.details.error) Coral else Ink,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
        message.receipt_id?.let { id ->
            OutlinedButton(onClick = { openReceipt(id) }) {
                LineIcon(Glyph.RECEIPT, size = 18.dp)
                Spacer(Modifier.width(8.dp))
                Text("Открыть чек и товары")
            }
        }
        message.details.reports.forEach { report -> ReportBlock(report, openReceipt) }
        Text(
            message.created_at.take(16).replace('T', ' '),
            color = Muted,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@Composable
private fun ReportBlock(report: AnalyticsReport, openReceipt: (String) -> Unit) {
    var expanded by rememberSaveable(report.query.toString()) { mutableStateOf(false) }
    var methods by rememberSaveable { mutableStateOf(false) }
    Surface(
        color = SurfaceRaised,
        shape = RoundedCornerShape(22.dp),
        border = BorderStroke(1.dp, Green.copy(alpha = .2f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(report.title, style = MaterialTheme.typography.titleMedium, color = Mint)
            Text(
                "${report.query.date_from} — ${report.query.date_to}",
                style = MaterialTheme.typography.labelSmall,
                color = Muted,
            )
            listOf(report.query.search, report.query.merchant, report.query.currency.orEmpty())
                .filter(String::isNotBlank)
                .takeIf { it.isNotEmpty() }
                ?.let {
                    Text(
                        it.joinToString(" · "),
                        color = Amber,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            report.metrics.chunked(2).forEach { metrics ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    metrics.forEach { metric ->
                        Surface(
                            modifier = Modifier.weight(1f),
                            color = Paper.copy(alpha = .5f),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Column(
                                Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Text(
                                    metric.label,
                                    color = Muted,
                                    style = MaterialTheme.typography.labelSmall,
                                )
                                Text(
                                    metric.value,
                                    color = Ink,
                                    fontWeight = FontWeight.SemiBold,
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                            }
                        }
                    }
                    if (metrics.size == 1) Spacer(Modifier.weight(1f))
                }
            }
            (if (expanded) report.rows else report.rows.take(5)).forEach { row ->
                HorizontalDivider(color = Border.copy(alpha = .4f))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        row.label,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(row.value, color = Mint, style = MaterialTheme.typography.bodyLarge)
                    if (row.detail.isNotBlank())
                        Text(row.detail, color = Muted, style = MaterialTheme.typography.bodySmall)
                    row.receipt_id?.let { id ->
                        TextButton(onClick = { openReceipt(id) }) {
                            Text("Показать исходный чек")
                            LineIcon(Glyph.CHEVRON, size = 16.dp)
                        }
                    }
                }
            }
            if (report.rows.size > 5)
                TextButton(onClick = { expanded = !expanded }) {
                    Text(if (expanded) "Свернуть" else "Показать ещё ${report.rows.size - 5}")
                }
            if (report.total_rows > report.rows.size)
                Text(
                    "Показано ${report.rows.size} из ${report.total_rows}. Итоги включают все найденные записи.",
                    color = Muted,
                    style = MaterialTheme.typography.bodySmall,
                )
            if (report.query.kind != "summary" && report.rows.isEmpty())
                Text(
                    "Подходящих записей нет. Попробуйте другой период или название из чека.",
                    color = Muted,
                    style = MaterialTheme.typography.bodySmall,
                )
            TextButton(onClick = { methods = !methods }) { Text("Как рассчитано") }
            if (methods)
                report.notices.forEach {
                    Text(it, color = Muted, style = MaterialTheme.typography.bodySmall)
                }
        }
    }
}
