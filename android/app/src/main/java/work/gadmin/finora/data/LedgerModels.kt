package work.gadmin.finora.data

import java.util.UUID
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import work.gadmin.finora.localization.AppLanguage
import work.gadmin.finora.localization.LanguageRuntime

fun ledgerText(ru: String, en: String): String =
    if (LanguageRuntime.language == AppLanguage.RUSSIAN) ru else en

@Serializable data class LedgerSplit(val category_id: String? = null, val amount_minor: Long)

@Serializable
data class LedgerTransaction(
    val id: String,
    val kind: String,
    val amount_minor: Long,
    val currency: String,
    val account_id: String,
    val occurred_on: String,
    val version: Int,
    val merchant: String = "",
    val note: String = "",
    val fx_rate: String = "1",
    val target_account_id: String? = null,
    @SerialName("target_minor") val target_amount_minor: Long? = null,
    val category_id: String? = null,
    val refund_of: String? = null,
    val receipt_id: String? = null,
    val occurrence_id: String? = null,
    val splits: List<LedgerSplit> = emptyList(),
) {
    val editable: Boolean
        get() =
            kind in setOf("expense", "income", "transfer") &&
                receipt_id == null &&
                occurrence_id == null &&
                refund_of == null &&
                splits.isEmpty()
}

@Serializable data class LedgerHistory(val items: List<LedgerTransaction>, val total: Int)

@Serializable
data class LedgerEdit(
    val kind: String = "expense",
    val id: String? = null,
    val version: Int? = null,
    val accountId: String = "",
    val targetAccountId: String = "",
    val amount: String = "",
    val targetAmount: String = "",
    val date: String = accountingToday().toString(),
    val fxRate: String = "",
    val merchant: String = "",
    val note: String = "",
    val categoryId: String? = null,
    val requestKey: String = UUID.randomUUID().toString(),
) {
    fun command(accounts: List<Account>): FinanceCommand {
        require(kind in setOf("expense", "income", "transfer"))
        val account = accounts.firstOrNull { it.id == accountId && !it.archived }
        requireNotNull(account) {
            ledgerText("Выберите действующий счёт", "Choose an active account")
        }
        val value = receiptNumber(amount, true)
        require(value.signum() > 0 && value <= java.math.BigDecimal("1000000000"))
        val posted = financeDate(date)
        require(merchant.length <= 200 && note.length <= 3000)
        val rate = if (account.currency == "MDL") null else financeRate(fxRate)
        val target =
            if (kind == "transfer")
                accounts.firstOrNull { it.id == targetAccountId && !it.archived }
            else null
        if (kind == "transfer")
            require(target != null && target.id != account.id) {
                ledgerText(
                    "Выберите другой действующий счёт назначения",
                    "Choose a different active destination account",
                )
            }
        val targetValue =
            if (target != null && target.currency != account.currency) {
                receiptNumber(targetAmount, true).also {
                    require(it.signum() > 0 && it <= java.math.BigDecimal("1000000000"))
                }
            } else null
        val body = buildJsonObject {
            put("kind", kind)
            put("amount", value.toPlainString())
            put("account_id", account.id)
            put("occurred_on", posted)
            put("fx_rate", rate?.let(::JsonPrimitive) ?: JsonNull)
            put("target_account_id", target?.id?.let(::JsonPrimitive) ?: JsonNull)
            put("target_amount", targetValue?.toPlainString()?.let(::JsonPrimitive) ?: JsonNull)
            put(
                "category_id",
                if (kind == "transfer") JsonNull else categoryId?.let(::JsonPrimitive) ?: JsonNull,
            )
            put("merchant", merchant.trim())
            put("note", note.trim())
            put("refund_of", JsonNull)
            put("splits", JsonArray(emptyList()))
            put("idempotency_key", requestKey)
            if (id != null) put("version", requireNotNull(version))
        }
        return FinanceCommand(
            "/api/transactions" + (id?.let { "/$it" } ?: ""),
            if (id == null) "POST" else "PUT",
            body,
        )
    }
}
