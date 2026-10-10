package work.gadmin.finora.data

import java.time.LocalDate

enum class ReceiptReviewProblem {
    STORE,
    DATE,
    ACCOUNT,
    TOTAL,
    ITEMS,
    ITEM_DETAILS,
}

data class ReceiptReviewSummary(
    val totalMinor: Long?,
    val itemsMinor: Long?,
    val differenceMinor: Long?,
    val problems: List<ReceiptReviewProblem>,
    val invalidLineNumbers: List<Int>,
)

/** Compare explicit discounted line totals; never add taxes or recalculate a recognized receipt. */
fun receiptReviewSummary(
    form: ReceiptForm,
    accounts: List<Account>,
    today: LocalDate = LocalDate.now(),
): ReceiptReviewSummary {
    fun minor(value: String): Long? = runCatching {
        receiptNumber(value, true).movePointRight(2).longValueExact()
    }
        .getOrNull()
    val total = minor(form.total)
    val values = form.items.map { minor(it.total) }
    val sum =
        if (values.isNotEmpty() && values.all { it != null })
            values.fold(0L) { sum, value -> Math.addExact(sum, requireNotNull(value)) }
        else null
    val invalid =
        form.items.mapIndexedNotNull { index, item ->
            val quantity = runCatching { receiptNumber(item.quantity) }.getOrNull()
            if (
                item.name.isBlank() ||
                    item.name.length > 300 ||
                    quantity == null ||
                    quantity.signum() <= 0 ||
                    quantity > "100000".toBigDecimal() ||
                    minor(item.unitPrice) == null ||
                    values[index] == null
            )
                index + 1
            else null
        }
    val date = runCatching { LocalDate.parse(form.date) }.getOrNull()
    val problems = buildList {
        if (form.merchant.isBlank()) add(ReceiptReviewProblem.STORE)
        if (date == null || date.year < 1990 || date > today) add(ReceiptReviewProblem.DATE)
        if (
            !accounts.any {
                it.id == form.accountId && !it.archived && it.currency == form.currency
            }
        )
            add(ReceiptReviewProblem.ACCOUNT)
        if (total == null || total <= 0) add(ReceiptReviewProblem.TOTAL)
        if (form.items.isEmpty()) add(ReceiptReviewProblem.ITEMS)
        if (invalid.isNotEmpty()) add(ReceiptReviewProblem.ITEM_DETAILS)
    }
    return ReceiptReviewSummary(
        total,
        sum,
        if (total != null && total > 0 && sum != null) total - sum else null,
        problems,
        invalid,
    )
}
