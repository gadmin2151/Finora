package work.gadmin.finora.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.graphics.toColorInt
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import work.gadmin.finora.AppState
import work.gadmin.finora.FinoraViewModel
import work.gadmin.finora.data.money
import work.gadmin.finora.localization.LanguageRuntime
import work.gadmin.finora.localization.Message
import work.gadmin.finora.localization.tr

@Composable
fun OverviewScreen(state: AppState, vm: FinoraViewModel) {
    val dashboard = state.dashboard
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(22.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    tr(Message.CLARITY_IN_NUMBERS),
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.weight(1f),
                )
                IconButton(vm::refreshCurrent, enabled = !state.refreshing && !state.busy) {
                    LineIcon(Glyph.REFRESH, tr(Message.REFRESH_STATISTICS))
                }
            }
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                IconButton({ vm.month(-1) }) {
                    LineIcon(Glyph.BACK, tr(Message.PREVIOUS_MONTH), size = 20.dp)
                }
                Text(
                    YearMonth.parse(state.month)
                        .format(
                            DateTimeFormatter.ofPattern(
                                "LLLL yyyy",
                                LanguageRuntime.language.locale,
                            )
                        )
                        .replaceFirstChar(Char::titlecase),
                    fontWeight = FontWeight.Medium,
                )
                IconButton({ vm.month(1) }) {
                    LineIcon(Glyph.CHEVRON, tr(Message.NEXT_MONTH), size = 20.dp)
                }
            }
        }
        if (state.dashboardLoading && !state.refreshing)
            item { BrandLoading(tr(Message.PUTTING_YOUR_MONTH_TOGETHER), compact = true) }
        if (dashboard != null) {
            item {
                Surface(color = HeroStart, shape = RoundedCornerShape(28.dp)) {
                    Column(
                        Modifier.fillMaxWidth()
                            .background(Brush.linearGradient(listOf(HeroStart, HeroEnd)))
                            .padding(25.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(tr(Message.SPENDING_THIS_MONTH), Modifier.weight(1f), color = Mint)
                            BrandMark(Modifier.size(52.dp))
                        }
                        Text(
                            money(dashboard.expense_minor),
                            style = MaterialTheme.typography.headlineLarge,
                            color = HeroInk,
                        )
                        HorizontalDivider(
                            color = Mint.copy(alpha = .2f),
                            modifier = Modifier.padding(vertical = 6.dp),
                        )
                        Row(Modifier.fillMaxWidth()) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    tr(Message.INCOME),
                                    color = Mint,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Text(
                                    money(dashboard.income_minor),
                                    color = HeroInk,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                            Column(Modifier.weight(1f)) {
                                Text(
                                    tr(Message.DIFFERENCE),
                                    color = Mint,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Text(
                                    money(dashboard.net_minor),
                                    color = HeroInk,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                        }
                    }
                }
            }
            if (dashboard.categories.any { it.spent_minor > 0 })
                item {
                    Surface(color = SurfaceColor, shape = RoundedCornerShape(26.dp)) {
                        Column(
                            Modifier.padding(22.dp),
                            verticalArrangement = Arrangement.spacedBy(18.dp),
                        ) {
                            SectionTitle(tr(Message.WHERE_YOUR_MONEY_GOES))
                            val categories =
                                dashboard.categories
                                    .filter { it.spent_minor > 0 }
                                    .sortedByDescending { it.spent_minor }
                            val sum = categories.sumOf { it.spent_minor }.coerceAtLeast(1)
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.Center,
                            ) {
                                Box(Modifier.size(160.dp), contentAlignment = Alignment.Center) {
                                    val fallbackColor = Green
                                    Canvas(Modifier.fillMaxSize().padding(12.dp)) {
                                        var angle = -90f
                                        categories.forEach { category ->
                                            val sweep = category.spent_minor.toFloat() / sum * 360f
                                            val color = runCatching {
                                                Color(category.color.toColorInt())
                                            }
                                                .getOrDefault(fallbackColor)
                                            drawArc(
                                                color,
                                                angle,
                                                (sweep - 2f).coerceAtLeast(0f),
                                                false,
                                                style = Stroke(18.dp.toPx(), cap = StrokeCap.Butt),
                                            )
                                            angle += sweep
                                        }
                                    }
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text(
                                            "${categories.size}",
                                            style = MaterialTheme.typography.headlineMedium,
                                        )
                                        Text(
                                            tr(Message.CATEGORIES_72264),
                                            color = Muted,
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                    }
                                }
                            }
                            categories.forEach { category ->
                                Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                                    Row(
                                        Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                    ) {
                                        Text(
                                            category.name,
                                            Modifier.weight(1f),
                                            style = MaterialTheme.typography.bodyMedium,
                                        )
                                        Text(
                                            money(category.spent_minor),
                                            fontWeight = FontWeight.SemiBold,
                                            style = MaterialTheme.typography.bodyMedium,
                                        )
                                    }
                                    LinearProgressIndicator(
                                        progress = { category.spent_minor.toFloat() / sum },
                                        Modifier.fillMaxWidth(),
                                        color =
                                            runCatching { Color(category.color.toColorInt()) }
                                                .getOrDefault(Green),
                                        trackColor = SoftGreen,
                                    )
                                }
                            }
                        }
                    }
                }
            else
                item {
                    InfoCard(
                        tr(Message.NO_SPENDING_THIS_MONTH_YET_SUBMITTED_AND_CONFIRMED_RECEIPT),
                        Glyph.CHART,
                    )
                }
            if (dashboard.accounts.isNotEmpty())
                item {
                    SectionTitle(tr(Message.ACCOUNTS))
                    Spacer(Modifier.height(12.dp))
                    Surface(color = SurfaceColor, shape = RoundedCornerShape(22.dp)) {
                        Column(
                            Modifier.padding(18.dp),
                            verticalArrangement = Arrangement.spacedBy(18.dp),
                        ) {
                            dashboard.accounts
                                .filterNot { it.archived }
                                .forEach { account ->
                                    Row(
                                        Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    ) {
                                        LineIcon(Glyph.WALLET, size = 21.dp)
                                        Text(account.name, Modifier.weight(1f))
                                        Text(
                                            money(account.balance_minor, account.currency),
                                            fontWeight = FontWeight.Medium,
                                        )
                                    }
                                }
                        }
                    }
                }
            if (dashboard.planned_remaining_minor > 0)
                item {
                    InfoCard(
                        tr(
                            Message.UPCOMING_ESSENTIAL_PAYMENTS_1_S,
                            money(dashboard.planned_remaining_minor),
                        ),
                        Glyph.WALLET,
                    )
                }
            if (state.insights.isNotEmpty())
                item {
                    SectionTitle(
                        tr(Message.WHERE_YOU_COULD_SAVE),
                        tr(Message.SUGGESTIONS_BASED_ON_YOUR_ORGANIZATION_S_HISTORY),
                    )
                }
            state.insights.forEach { insight ->
                item {
                    Surface(color = SoftGreen, shape = RoundedCornerShape(22.dp)) {
                        Column(
                            Modifier.padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            LineIcon(Glyph.SPARK)
                            Text(insight.title, fontWeight = FontWeight.SemiBold)
                            Text(insight.text, style = MaterialTheme.typography.bodyMedium)
                            if (insight.saving_minor > 0)
                                Text(
                                    tr(Message.POTENTIAL_SAVINGS_1_S, money(insight.saving_minor)),
                                    color = Green,
                                    fontWeight = FontWeight.Medium,
                                )
                        }
                    }
                }
            }
        } else if (!state.dashboardLoading)
            item {
                EmptyState(
                    tr(Message.STATISTICS_ARE_NOT_AVAILABLE_YET),
                    tr(Message.CHECK_YOUR_CONNECTION_TO_THE_SERVER),
                    Glyph.CHART,
                )
                PrimaryButton(tr(Message.REFRESH), vm::refreshCurrent, Modifier.fillMaxWidth())
            }
    }
}
