package work.gadmin.finora.data

import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import work.gadmin.finora.localization.AppLanguage
import work.gadmin.finora.localization.LanguageRuntime

fun paymentText(ru: String, en: String) =
    if (LanguageRuntime.language == AppLanguage.RUSSIAN) ru else en

@Serializable
data class PaymentTemplate(
    val id: String,
    val name: String,
    val amount_minor: Long,
    val currency: String,
    val fx_rate: String = "1",
    val category_id: String? = null,
    val account_id: String? = null,
    val start_date: String,
    val recurrence: String,
    val active: Boolean,
)

@Serializable
data class PaymentOccurrence(
    val id: String,
    val bill_id: String,
    val name: String,
    val amount_minor: Long,
    val currency: String,
    val fx_rate: String = "1",
    val category_id: String? = null,
    val account_id: String? = null,
    val due_date: String,
    val recurrence: String,
    val active: Boolean,
    val transaction_id: String? = null,
    val status: String,
)

data class PaymentEdit(
    val occurrenceId: String? = null,
    val name: String = "",
    val amount: String = "",
    val currency: String = "MDL",
    val accountId: String = "",
    val categoryId: String = "",
    val date: String = accountingToday().toString(),
    val recurrence: String = "monthly",
    val fxRate: String = "",
    val transactionId: String? = null,
    val key: String = UUID.randomUUID().toString(),
) {
    fun command(accounts: List<Account>): FinanceCommand {
        val value = receiptNumber(amount, true)
        require(value.signum() > 0 && value <= "1000000000".toBigDecimal()) {
            paymentText(
                "Введите положительную сумму до 1 млрд",
                "Enter a positive amount up to 1 billion",
            )
        }
        require(currency in setOf("MDL", "EUR", "USD", "RON")) {
            paymentText("Выберите валюту", "Choose currency")
        }
        require(accounts.any { it.id == accountId && it.currency == currency && !it.archived }) {
            paymentText(
                "Выберите активный счёт в валюте платежа",
                "Choose an active account in the payment currency",
            )
        }
        val posted = financeDate(date, occurrenceId == null)
        val rate =
            if (currency == "MDL") null
            else {
                val number = fxRate.trim().replace(',', '.').toBigDecimalOrNull()
                require(
                    number != null &&
                        number.signum() > 0 &&
                        number <= "100000".toBigDecimal() &&
                        number.scale() <= 8
                ) {
                    paymentText(
                        "Введите курс MDL за единицу валюты, до 8 знаков",
                        "Enter MDL per currency unit, up to 8 decimals",
                    )
                }
                number.toPlainString()
            }
        if (occurrenceId == null) {
            require(name.trim().length in 1..100) {
                paymentText("Название: от 1 до 100 символов", "Name: 1 to 100 characters")
            }
            require(recurrence in setOf("monthly", "quarterly", "yearly", "weekly", "once")) {
                paymentText("Выберите периодичность", "Choose recurrence")
            }
        }
        return FinanceCommand(
            if (occurrenceId == null) "/api/bills" else "/api/occurrences/$occurrenceId/pay",
            "POST",
            buildJsonObject {
                put("amount", value.toPlainString())
                put("account_id", accountId)
                rate?.let { put("fx_rate", it) }
                if (occurrenceId == null) {
                    put("name", name.trim())
                    put("currency", currency)
                    put("start_date", posted)
                    put("recurrence", recurrence)
                    if (categoryId.isNotBlank()) put("category_id", categoryId)
                } else {
                    put("occurred_on", posted)
                    put("idempotency_key", key)
                    transactionId?.let { put("transaction_id", it) }
                }
            },
        )
    }
}

data class PaymentState(
    val month: String = "",
    val occurrences: List<PaymentOccurrence> = emptyList(),
    val templates: List<PaymentTemplate> = emptyList(),
    val accounts: List<Account> = emptyList(),
    val categories: List<Category> = emptyList(),
    val loading: Boolean = false,
    val actionUncertain: Boolean = false,
    val checkedAfterUncertain: Boolean = false,
    val error: String? = null,
    val editor: PaymentEdit? = null,
    val editorError: String? = null,
    val retryLocked: Boolean = false,
    val matches: List<FinanceTransaction> = emptyList(),
    val matchesLoaded: Boolean = false,
    val matching: Boolean = false,
)
