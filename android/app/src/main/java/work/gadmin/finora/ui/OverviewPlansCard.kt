package work.gadmin.finora.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import work.gadmin.finora.data.*
import work.gadmin.finora.localization.*

@Composable
fun OverviewPlansCard(
    dashboard: Dashboard,
    admin: Boolean,
    busy: Boolean,
    open: (ChatDestination) -> Unit,
) {
    val pending = dashboard.bills.filter { it.status == "overdue" || it.status == "upcoming" }
    Surface(color = SurfaceColor, shape = RoundedCornerShape(26.dp)) {
        Column(
            Modifier.fillMaxWidth().padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(tr(Message.OVERVIEW_PLAN_TITLE), style = MaterialTheme.typography.titleMedium)
            Text(
                money(dashboard.planned_remaining_minor),
                style = MaterialTheme.typography.headlineMedium,
            )
            Text(
                tr(Message.OVERVIEW_PLAN_NOTE),
                color = Muted,
                style = MaterialTheme.typography.bodySmall,
            )
            val overdue = pending.count { it.status == "overdue" }
            if (overdue > 0) Text(tr(Message.OVERVIEW_OVERDUE, overdue), color = Coral)
            // Rows retain original currencies; the aggregate above is explicitly an MDL equivalent.
            pending
                .sortedBy { it.due_date }
                .take(4)
                .forEach { bill ->
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(bill.name)
                            Text(
                                bill.due_date,
                                color = Muted,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Text(money(bill.amount_minor, bill.currency))
                    }
                }
            if (pending.isEmpty() && dashboard.planned_remaining_minor == 0L)
                Text(tr(Message.OVERVIEW_NO_PENDING), color = Muted)
            FinancePlanningLinks(admin, busy, open)
        }
    }
}
