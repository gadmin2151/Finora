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
import java.util.Locale
import work.gadmin.finora.AppState
import work.gadmin.finora.FinoraViewModel
import work.gadmin.finora.data.money

@Composable
fun OverviewScreen(state: AppState, vm: FinoraViewModel) {
    val dashboard = state.dashboard
    LazyColumn(
        contentPadding = PaddingValues(22.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Ясность в цифрах",
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.weight(1f),
                )
                IconButton(vm::loadDashboard, enabled = !state.dashboardLoading) {
                    LineIcon(Glyph.REFRESH, "Обновить статистику")
                }
            }
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                IconButton({ vm.month(-1) }) {
                    LineIcon(Glyph.BACK, "Предыдущий месяц", size = 20.dp)
                }
                Text(
                    YearMonth.parse(state.month)
                        .format(
                            DateTimeFormatter.ofPattern("LLLL yyyy", Locale.forLanguageTag("ru"))
                        )
                        .replaceFirstChar(Char::titlecase),
                    fontWeight = FontWeight.Medium,
                )
                IconButton({ vm.month(1) }) {
                    LineIcon(Glyph.CHEVRON, "Следующий месяц", size = 20.dp)
                }
            }
        }
        if (state.dashboardLoading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        if (dashboard != null) {
            item {
                Surface(color = Forest, shape = RoundedCornerShape(28.dp)) {
                    Column(
                        Modifier.fillMaxWidth()
                            .background(Brush.linearGradient(listOf(Forest, Color(0xFF29364F))))
                            .padding(25.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text("Расходы за месяц", color = Mint)
                        Text(
                            money(dashboard.expense_minor),
                            style = MaterialTheme.typography.headlineLarge,
                            color = Color.White,
                        )
                        HorizontalDivider(
                            color = Mint.copy(alpha = .2f),
                            modifier = Modifier.padding(vertical = 6.dp),
                        )
                        Row(Modifier.fillMaxWidth()) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "Доходы",
                                    color = Mint,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Text(
                                    money(dashboard.income_minor),
                                    color = Color.White,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "Разница",
                                    color = Mint,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Text(
                                    money(dashboard.net_minor),
                                    color = Color.White,
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
                            SectionTitle("На что уходят деньги")
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
                                    Canvas(Modifier.fillMaxSize().padding(12.dp)) {
                                        var angle = -90f
                                        categories.forEach { category ->
                                            val sweep = category.spent_minor.toFloat() / sum * 360f
                                            val color = runCatching {
                                                Color(category.color.toColorInt())
                                            }
                                                .getOrDefault(Green)
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
                                            "категорий",
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
                        "В этом месяце ещё нет расходов. Отправленные и подтверждённые чеки появятся в статистике.",
                        Glyph.CHART,
                    )
                }
            if (dashboard.accounts.isNotEmpty())
                item {
                    SectionTitle("Счета")
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
                        "Предстоящие обязательные платежи: ${money(dashboard.planned_remaining_minor)}",
                        Glyph.WALLET,
                    )
                }
            if (state.insights.isNotEmpty())
                item {
                    SectionTitle(
                        "Где можно сэкономить",
                        "Рекомендации на основе истории организации",
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
                                    "Возможная экономия: ${money(insight.saving_minor)}",
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
                    "Статистика пока недоступна",
                    "Проверьте соединение с сервером.",
                    Glyph.CHART,
                )
                PrimaryButton("Обновить", vm::loadDashboard, Modifier.fillMaxWidth())
            }
    }
}
