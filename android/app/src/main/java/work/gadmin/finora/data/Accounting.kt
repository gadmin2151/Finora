package work.gadmin.finora.data

import kotlinx.serialization.Serializable

@Serializable
data class AccountingConfig(
    val mode: String = "separate",
    val default_account_id: String? = null,
    val version: Int = 1,
) {
    val combined: Boolean
        get() = mode == "combined"

    fun paymentAccounts(accounts: List<Account>): List<Account> = accounts.filter {
        !it.archived && (!combined || it.currency != "MDL" || it.id == default_account_id)
    }

    fun receiptAccount(id: String?, accounts: List<Account>): String? {
        val selected = accounts.firstOrNull { it.id == id && !it.archived }
        return if (combined && (selected == null || selected.currency == "MDL")) default_account_id
        else selected?.id
    }
}
