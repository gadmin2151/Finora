package work.gadmin.finora.data

import java.time.YearMonth
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import work.gadmin.finora.localization.AppLanguage
import work.gadmin.finora.localization.LanguageRuntime

fun budgetText(ru: String, en: String): String =
    if (LanguageRuntime.language == AppLanguage.RUSSIAN) ru else en

@Serializable
data class BudgetCategory(
    val id: String,
    val name: String,
    val spent_minor: Long = 0,
    val budget_minor: Long? = null,
    val budget_id: String? = null,
)

@Serializable
data class BudgetDashboard(val month: String, val categories: List<BudgetCategory> = emptyList())

@Serializable
data class BudgetCopyItem(val category_id: String, val name: String, val amount_minor: Long)

@Serializable
data class BudgetCopyPreview(
    val month: String,
    val source_month: String,
    val fingerprint: String,
    val items: List<BudgetCopyItem> = emptyList(),
    val total_minor: Long = 0,
    val preserved_count: Int = 0,
) {
    fun command(currentMonth: String): FinanceCommand {
        require(month == currentMonth && Regex("[a-f0-9]{64}").matches(fingerprint)) {
            budgetText("Проверьте перенос заново", "Review the copy again")
        }
        require(items.isNotEmpty()) { budgetText("Нет лимитов для переноса", "No limits to copy") }
        return FinanceCommand(
            "/api/budgets/copy",
            "POST",
            buildJsonObject {
                put("month", month)
                put("fingerprint", fingerprint)
            },
        )
    }
}

@Serializable
data class BudgetEdit(val month: String, val category: BudgetCategory, val amount: String = "") {
    fun command(): FinanceCommand {
        require(runCatching { YearMonth.parse(month).year in 1990..2100 }.getOrDefault(false))
        require(category.id.isNotBlank() && category.id != "uncategorized")
        val value = receiptNumber(amount, true)
        require(value.signum() > 0 && value <= "1000000000".toBigDecimal()) {
            budgetText(
                "Лимит должен быть больше нуля и не выше 1 000 000 000 MDL",
                "Limit must be positive and no more than 1,000,000,000 MDL",
            )
        }
        return FinanceCommand(
            "/api/budgets",
            "PUT",
            buildJsonObject {
                put("month", month)
                put("category_id", category.id)
                put("amount", value.toPlainString())
            },
        )
    }
}
