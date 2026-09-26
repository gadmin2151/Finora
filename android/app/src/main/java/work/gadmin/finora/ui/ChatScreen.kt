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
import work.gadmin.finora.AppState
import work.gadmin.finora.FinoraViewModel
import work.gadmin.finora.Page
import work.gadmin.finora.data.AnalyticsReport
import work.gadmin.finora.data.ChatMessage
import work.gadmin.finora.localization.LanguageRuntime
import work.gadmin.finora.localization.Message
import work.gadmin.finora.localization.tr

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
                    Text(
                        tr(Message.MONEY_IN_PLAIN_WORDS),
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    Text(
                        tr(Message.SHARED_ORGANIZATION_CHAT_FIGURES_FROM_YOUR_RECORDS),
                        color = Muted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        IconButton({ vm.month(-1) }, enabled = !state.busy) {
                            LineIcon(Glyph.BACK, tr(Message.PREVIOUS_MONTH), size = 18.dp)
                        }
                        Text(
                            YearMonth.parse(state.month)
                                .format(
                                    DateTimeFormatter.ofPattern(
                                        "LLLL yyyy",
                                        LanguageRuntime.language.locale,
                                    )
                                ),
                            style = MaterialTheme.typography.labelLarge,
                        )
                        IconButton({ vm.month(1) }, enabled = !state.busy) {
                            LineIcon(Glyph.CHEVRON, tr(Message.NEXT_MONTH), size = 18.dp)
                        }
                    }
                }
            }
            if (state.chatSyncError != null)
                item(key = "offline") { InfoCard(state.chatSyncError, Glyph.REFRESH) }
            if (state.chat.isEmpty()) {
                item(key = "welcome") {
                    if (state.chatLoading) BrandLoading(tr(Message.OPENING_HISTORY), compact = true)
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
                                    tr(Message.FROM_QUESTIONS_TO_CLARITY),
                                    style = MaterialTheme.typography.titleLarge,
                                )
                                Text(
                                    tr(
                                        Message
                                            .COMPARE_PERIODS_FIND_PURCHASES_AND_SEE_WHERE_YOUR_MONEY_GO
                                    ),
                                    color = Mint,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                listOf(
                                        tr(Message.WHERE_CAN_I_SAVE_MONEY_THIS_MONTH),
                                        tr(Message.FIND_LAPTE_PURCHASES_FROM_THE_LAST_3_MONTHS),
                                        tr(
                                            Message.COMPARE_GROCERY_SPENDING_IN_AUGUST_AND_SEPTEMBER
                                        ),
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
                            Text(
                                if (state.chatLoading) tr(Message.LOADING)
                                else tr(Message.EARLIER_MESSAGES)
                            )
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
                            "summary" to tr(Message.SUMMARY),
                            "categories" to tr(Message.CATEGORIES),
                            "prices" to tr(Message.MY_PRICES),
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
                        LineIcon(Glyph.CAMERA, tr(Message.ADD_A_RECEIPT_PHOTO))
                    }
                    OutlinedTextField(
                        value = state.chatDraft,
                        onValueChange = vm::chatDraft,
                        modifier = Modifier.weight(1f),
                        placeholder = { Text(tr(Message.YOUR_QUESTION)) },
                        maxLines = 4,
                        shape = RoundedCornerShape(18.dp),
                        enabled = !state.busy,
                    )
                    FilledIconButton(
                        onClick = { vm.sendChat() },
                        enabled = state.chatDraft.isNotBlank() && !state.busy && pending == null,
                        modifier = Modifier.size(48.dp),
                    ) {
                        LineIcon(Glyph.CHEVRON, tr(Message.SEND_MESSAGE), tint = OnPrimary)
                    }
                }
                Text(
                    tr(Message.QUICK_REPORTS_WORK_WITHOUT_AI_ALWAYS_CHECK_AI_ANSWERS),
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
                    if (user) tr(Message.ORGANIZATION_MEMBER)
                    else
                        when (message.details.provider) {
                            "reports" -> tr(Message.FINORA_VERIFIED_CALCULATION)
                            "ollama" -> tr(Message.FINORA_LOCAL_AI)
                            else -> tr(Message.FINORA_ASSISTANT)
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
                Text(tr(Message.OPEN_RECEIPT_AND_ITEMS))
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
                            Text(tr(Message.SHOW_ORIGINAL_RECEIPT))
                            LineIcon(Glyph.CHEVRON, size = 16.dp)
                        }
                    }
                }
            }
            if (report.rows.size > 5)
                TextButton(onClick = { expanded = !expanded }) {
                    Text(
                        if (expanded) tr(Message.SHOW_LESS)
                        else tr(Message.SHOW_1_S_MORE, report.rows.size - 5)
                    )
                }
            if (report.total_rows > report.rows.size)
                Text(
                    tr(
                        Message.SHOWING_1_S_OF_2_S_TOTALS_INCLUDE_ALL_MATCHING_RECORDS,
                        report.rows.size,
                        report.total_rows,
                    ),
                    color = Muted,
                    style = MaterialTheme.typography.bodySmall,
                )
            if (report.query.kind != "summary" && report.rows.isEmpty())
                Text(
                    tr(Message.NO_MATCHING_RECORDS_TRY_ANOTHER_PERIOD_OR_AN_ITEM_NAME_FRO),
                    color = Muted,
                    style = MaterialTheme.typography.bodySmall,
                )
            TextButton(onClick = { methods = !methods }) {
                Text(tr(Message.HOW_THIS_WAS_CALCULATED))
            }
            if (methods)
                report.notices.forEach {
                    Text(it, color = Muted, style = MaterialTheme.typography.bodySmall)
                }
        }
    }
}
