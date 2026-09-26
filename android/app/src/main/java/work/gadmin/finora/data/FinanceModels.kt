package work.gadmin.finora.data

import java.time.LocalDate
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable
data class IncomePlan(
    val id: String,
    val name: String,
    val amount_minor: Long,
    val currency: String,
    val account_id: String? = null,
    val recurrence: String,
    val start_date: String,
    val fx_rate: String,
    val active: Boolean,
    val version: Int,
)

@Serializable
data class IncomeOccurrence(
    val id: String,
    val name: String,
    val amount_minor: Long,
    val currency: String,
    val account_id: String? = null,
    val fx_rate: String,
    val due_date: String,
    val status: String,
    val received_minor: Long? = null,
    val received_on: String? = null,
)

@Serializable
data class IncomeReport(
    val received_minor: Long,
    val regular_minor: Long,
    val occasional_minor: Long,
    val expected_minor: Long,
    val occurrences: List<IncomeOccurrence> = emptyList(),
)

@Serializable
data class FinanceTransaction(
    val id: String,
    val kind: String,
    val merchant: String,
    val amount_minor: Long,
    val currency: String,
    val account_id: String,
    val occurred_on: String,
    val fx_rate: String,
    val note: String = "",
    val occurrence_id: String? = null,
    val version: Int,
)

@Serializable data class IncomeHistory(val items: List<FinanceTransaction>, val total: Int)

@Serializable
data class Debt(
    val id: String,
    val person: String,
    val direction: String,
    val currency: String,
    val remaining_minor: Long,
    val initial_minor: Long = 0,
    val due_date: String? = null,
    val note: String = "",
)

enum class FinanceTab {
    INCOME,
    DEBTS,
}

enum class FinanceEditKind {
    INCOME,
    PLAN,
    RECEIVE,
    DEBT,
    REPAY,
}

val incomeRecurrences =
    linkedMapOf(
        "monthly" to "Каждый месяц",
        "weekly" to "Каждую неделю",
        "quarterly" to "Каждый квартал",
        "yearly" to "Каждый год",
        "once" to "Один раз",
    )

data class FinanceCommand(val path: String, val method: String, val body: JsonObject? = null)

/** Amounts remain decimal strings in forms and integer minor units in responses. */
@Serializable
data class FinanceEdit(
    val kind: FinanceEditKind,
    val id: String? = null,
    val version: Int? = null,
    val name: String = "",
    val amount: String = "",
    val currency: String = "MDL",
    val accountId: String = "",
    val date: String = LocalDate.now().toString(),
    val dueDate: String = "",
    val fxRate: String = "",
    val note: String = "",
    val recurrence: String = "monthly",
    val direction: String = "lent",
    val mode: String = "new",
    val transactionId: String? = null,
    val remainingMinor: Long? = null,
    // A failed/uncertain request keeps this key: retrying cannot post the money twice.
    val requestKey: String = UUID.randomUUID().toString(),
) {
    val title: String
        get() =
            when (kind) {
                FinanceEditKind.INCOME -> if (id == null) "Разовый доход" else "Изменить доход"
                FinanceEditKind.PLAN -> if (id == null) "Источник дохода" else "Изменить источник"
                FinanceEditKind.RECEIVE -> "Получить доход"
                FinanceEditKind.DEBT -> "Записать долг"
                FinanceEditKind.REPAY -> "Возврат долга"
            }

    fun command(accounts: List<Account>): FinanceCommand {
        val amountValue = receiptNumber(amount, true)
        require(amountValue.signum() > 0) { "Сумма должна быть больше нуля" }
        if (kind == FinanceEditKind.REPAY) {
            require(
                amountValue.movePointRight(2).longValueExact() <= requireNotNull(remainingMinor)
            ) {
                "Сумма возврата превышает остаток долга"
            }
        }
        require(currency in setOf("MDL", "EUR", "USD", "RON")) { "Выберите валюту" }
        val existingDebt = kind == FinanceEditKind.DEBT && mode == "existing"
        if (!existingDebt)
            require(
                accounts.any { it.id == accountId && it.currency == currency && !it.archived }
            ) {
                "Выберите действующий счёт в валюте операции"
            }
        val posted = financeDate(date, allowFuture = kind == FinanceEditKind.PLAN)
        val due = dueDate.takeIf(String::isNotBlank)?.let { financeDate(it, true) }
        require(note.length <= 3000) { "Примечание слишком длинное" }
        val rate = if (currency == "MDL" || existingDebt) null else financeRate(fxRate)
        val body = buildJsonObject {
            put("amount", amountValue.toPlainString())
            put("account_id", if (existingDebt) JsonNull else JsonPrimitive(accountId))
            put("fx_rate", rate?.let(::JsonPrimitive) ?: JsonNull)
            put("idempotency_key", requestKey)
            when (kind) {
                FinanceEditKind.INCOME -> {
                    require(name.trim().length <= 200) { "Название слишком длинное" }
                    put("kind", "income")
                    put("merchant", name.trim())
                    put("note", note.trim())
                    put("occurred_on", posted)
                    if (id != null) put("version", requireNotNull(version))
                }
                FinanceEditKind.PLAN -> {
                    require(name.trim().length in 1..100) { "Укажите название источника" }
                    require(recurrence in incomeRecurrences) { "Выберите периодичность" }
                    put("name", name.trim())
                    put("currency", currency)
                    put("start_date", posted)
                    put("recurrence", recurrence)
                    if (id != null) put("version", requireNotNull(version))
                }
                FinanceEditKind.DEBT -> {
                    require(name.trim().length in 1..100) { "Укажите имя человека" }
                    require(direction in setOf("lent", "borrowed"))
                    require(mode in setOf("new", "existing"))
                    put("person", name.trim())
                    put("direction", direction)
                    put("mode", mode)
                    put("currency", currency)
                    put("occurred_on", posted)
                    put("due_date", due?.let(::JsonPrimitive) ?: JsonNull)
                    put("note", note.trim())
                }
                FinanceEditKind.RECEIVE,
                FinanceEditKind.REPAY -> {
                    put("occurred_on", posted)
                    if (kind == FinanceEditKind.RECEIVE)
                        put("transaction_id", transactionId?.let(::JsonPrimitive) ?: JsonNull)
                }
            }
        }
        val path =
            when (kind) {
                FinanceEditKind.INCOME -> "/api/transactions" + (id?.let { "/$it" } ?: "")
                FinanceEditKind.PLAN -> "/api/income/templates" + (id?.let { "/$it" } ?: "")
                FinanceEditKind.RECEIVE -> "/api/income/occurrences/${requireNotNull(id)}/receive"
                FinanceEditKind.DEBT -> "/api/debts"
                FinanceEditKind.REPAY -> "/api/debts/${requireNotNull(id)}/repay"
            }
        return FinanceCommand(
            path,
            if (id != null && kind in setOf(FinanceEditKind.INCOME, FinanceEditKind.PLAN)) "PUT"
            else "POST",
            body,
        )
    }
}

fun financeDate(value: String, allowFuture: Boolean = false): String {
    val date = runCatching { LocalDate.parse(value.trim()) }.getOrNull()
    require(date != null && date.year in 1990..2100 && (allowFuture || date <= LocalDate.now())) {
        if (allowFuture) "Выберите дату между 1990 и 2100 годом"
        else "Дата не может быть позже сегодняшней"
    }
    return date.toString()
}

private fun financeRate(value: String): String {
    val text = value.trim().replace(',', '.')
    require(Regex("[0-9]{1,6}(?:\\.[0-9]{1,8})?").matches(text)) {
        "Укажите курс: сколько MDL за единицу валюты"
    }
    val number = text.toBigDecimal()
    require(number.signum() > 0 && number <= "100000".toBigDecimal()) { "Проверьте курс валюты" }
    return number.toPlainString()
}

fun matchingIncome(items: List<FinanceTransaction>, form: FinanceEdit): List<FinanceTransaction> {
    val amount =
        runCatching { receiptNumber(form.amount, true).movePointRight(2).longValueExact() }
            .getOrNull() ?: return emptyList()
    return items.filter {
        it.kind == "income" &&
            it.occurrence_id == null &&
            it.account_id == form.accountId &&
            it.currency == form.currency &&
            it.occurred_on == form.date &&
            it.amount_minor == amount
    }
}
