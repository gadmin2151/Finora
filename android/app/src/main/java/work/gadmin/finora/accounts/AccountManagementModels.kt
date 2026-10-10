package work.gadmin.finora.accounts

import java.math.BigDecimal
import java.util.UUID
import kotlinx.serialization.json.*
import work.gadmin.finora.data.FinanceCommand

/** A scope snapshot must change when organization, membership or accounting settings change. */
data class AccountManagementScope(
    val organizationId: String,
    val admin: Boolean,
    val combined: Boolean,
    val defaultAccountId: String?,
    val ownerId: String,
    val serverOrigin: String,
)

data class ManagedAccount(
    val id: String,
    val name: String,
    val currency: String,
    val kind: String,
    val color: String,
    val openingMinor: Long,
    val balanceMinor: Long,
    val archived: Boolean,
)

data class ManagedAccountForm(
    val id: String? = null,
    val name: String = "",
    val currency: String = "MDL",
    val kind: String = "card",
    val color: String = "#16a69b",
    val openingBalance: String = "0.00",
) {
    fun command(): FinanceCommand {
        require(name.trim().length in 1..100) { "Название / Name: 1–100" }
        require(currency in listOf("MDL", "EUR", "USD", "RON")) { "Валюта / Currency" }
        require(kind in listOf("cash", "card", "savings")) { "Тип / Kind" }
        require(Regex("^#[0-9a-fA-F]{6}$").matches(color)) { "Цвет / Color: #RRGGBB" }
        require(Regex("^[+-]?[0-9]{1,10}([.,][0-9]{1,2})?$").matches(openingBalance.trim())) {
            "Начальный остаток / Opening balance: 0.00"
        }
        val amount = BigDecimal(openingBalance.trim().replace(',', '.'))
        require(amount.abs() <= BigDecimal("1000000000")) { "Максимум / Maximum: 1000000000" }
        id?.let { UUID.fromString(it) }
        return FinanceCommand(
            "/api/accounts" + (id?.let { "/$it" } ?: ""),
            if (id == null) "POST" else "PUT",
            buildJsonObject {
                put("name", name.trim())
                put("currency", currency)
                put("kind", kind)
                put("color", color)
                put("opening_balance", amount.toPlainString())
            },
        )
    }

    companion object {
        fun from(row: ManagedAccount) =
            ManagedAccountForm(
                row.id,
                row.name,
                row.currency,
                row.kind,
                row.color,
                BigDecimal.valueOf(row.openingMinor, 2).toPlainString(),
            )
    }
}

data class AccountManagementConfirmation(
    val command: FinanceCommand,
    val description: String,
    val scope: AccountManagementScope,
)

data class AccountManagementState(
    val scope: AccountManagementScope? = null,
    val accounts: List<ManagedAccount> = emptyList(),
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val error: String? = null,
    val editor: ManagedAccountForm? = null,
    val editorError: String? = null,
    val writing: Boolean = false,
    val uncertain: Boolean = false,
    val checkedAfterUncertain: Boolean = false,
    val pendingDescription: String? = null,
    val guardUnavailable: Boolean = false,
    val confirmation: AccountManagementConfirmation? = null,
)
