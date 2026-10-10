package work.gadmin.finora.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import work.gadmin.finora.data.*
import work.gadmin.finora.localization.Message
import work.gadmin.finora.localization.tr

@Composable
internal fun ReceiptReviewSummaryCard(
    summary: ReceiptReviewSummary,
    currency: String,
    enabled: Boolean,
    useItemTotal: () -> Unit,
) {
    Surface(color = SurfaceColor, shape = MaterialTheme.shapes.medium) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(tr(Message.REVIEW_YOUR_RECEIPT), style = MaterialTheme.typography.titleMedium)
            Text(
                tr(
                    Message.REVIEW_RECEIPT_TOTAL,
                    summary.totalMinor?.let { money(it, currency) } ?: "—",
                )
            )
            Text(
                tr(
                    Message.REVIEW_ITEM_TOTAL,
                    summary.itemsMinor?.let { money(it, currency) } ?: "—",
                )
            )
            summary.differenceMinor
                ?.takeIf { it != 0L }
                ?.let {
                    Text(
                        tr(Message.REVIEW_TOTAL_DIFFERENCE, money(it, currency)),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            summary.problems.forEach { problem ->
                Text(
                    when (problem) {
                        ReceiptReviewProblem.STORE -> tr(Message.ENTER_THE_STORE)
                        ReceiptReviewProblem.DATE ->
                            tr(Message.CHECK_THE_DATE_YYYY_MM_DD_NO_LATER_THAN_TODAY)
                        ReceiptReviewProblem.ACCOUNT -> tr(Message.CHOOSE_AN_ACCOUNT)
                        ReceiptReviewProblem.TOTAL -> tr(Message.ENTER_THE_RECEIPT_TOTAL)
                        ReceiptReviewProblem.ITEMS -> tr(Message.ADD_RECEIPT_ITEMS)
                        ReceiptReviewProblem.ITEM_DETAILS ->
                            tr(
                                Message.REVIEW_INVALID_LINES,
                                summary.invalidLineNumbers.joinToString(", "),
                            )
                    },
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Text(
                tr(Message.REVIEW_INCLUDED_TAXES),
                color = Muted,
                style = MaterialTheme.typography.bodySmall,
            )
            if (
                summary.itemsMinor != null &&
                    summary.itemsMinor > 0 &&
                    summary.differenceMinor != 0L
            )
                OutlinedButton(
                    useItemTotal,
                    enabled = enabled,
                    modifier = Modifier.testTag("receipt-use-line-total"),
                ) {
                    Text(tr(Message.USE_ITEM_SUM_AS_TOTAL))
                }
        }
    }
}
