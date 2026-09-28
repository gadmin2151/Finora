package work.gadmin.finora.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import work.gadmin.finora.data.ReceiptAuthor
import work.gadmin.finora.localization.Message
import work.gadmin.finora.localization.tr

private fun authorName(author: ReceiptAuthor?): String =
    author?.name?.takeIf(String::isNotBlank)
        ?: author?.username?.takeIf(String::isNotBlank)
        ?: tr(Message.UNKNOWN_RECEIPT_AUTHOR)

@Composable
fun ReceiptAuthorFilter(
    authors: List<ReceiptAuthor>,
    selected: String?,
    enabled: Boolean = true,
    onChange: (String?) -> Unit,
) {
    val choices =
        listOf(null to tr(Message.ALL_RECEIPT_AUTHORS)) +
            authors.map { (it.id ?: "unknown") to authorName(it) }
    ChoiceField(
        tr(Message.RECEIPT_AUTHOR),
        choices.firstOrNull { it.first == selected }?.second ?: tr(Message.UNKNOWN_RECEIPT_AUTHOR),
        choices,
        enabled,
        onChange,
    )
}

@Composable
fun ReceiptAuthorLabel(author: ReceiptAuthor?) {
    Text(
        "${tr(Message.RECEIPT_AUTHOR)}: ${authorName(author)}",
        color = Muted,
        style = MaterialTheme.typography.bodySmall,
    )
}
