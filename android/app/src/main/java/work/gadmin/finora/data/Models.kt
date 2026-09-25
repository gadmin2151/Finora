package work.gadmin.finora.data

import kotlinx.serialization.Serializable

@Serializable
data class Organization(val id: String, val name: String, val role: String) {
    val isAdmin: Boolean
        get() = role == "admin"
}

@Serializable
data class User(
    val id: String,
    val username: String,
    val name: String,
    val csrf: String,
    val organizations: List<Organization> = emptyList(),
)

@Serializable
data class Account(
    val id: String,
    val name: String,
    val currency: String,
    val balance_minor: Long = 0,
    val archived: Boolean = false,
)

@Serializable
data class Category(
    val id: String,
    val name: String,
    val color: String = "#24745F",
    val spent_minor: Long = 0,
    val budget_minor: Long? = null,
)

@Serializable
data class ReceiptItem(
    val id: String,
    val name: String,
    val quantity: String,
    val unit: String,
    val unit_price_minor: Long,
    val total_minor: Long,
    val category_id: String? = null,
)

@Serializable
data class Receipt(
    val id: String,
    val source: String,
    val source_url: String? = null,
    val review_required: Boolean = false,
    val created_by: String? = null,
    val merchant: String = "",
    val purchased_on: String? = null,
    val currency: String = "MDL",
    val total_minor: Long? = null,
    val status: String,
    val error: String? = null,
    val warnings: List<String> = emptyList(),
    val account_id: String? = null,
    val fx_rate: String = "1",
    val version: Int,
    val transaction_id: String? = null,
    val files: List<String> = emptyList(),
    val created_at: String,
    val items: List<ReceiptItem> = emptyList(),
) {
    val isProcessing: Boolean
        get() = status == "queued" || status == "processing"

    val title: String
        get() = merchant.ifBlank { if (isProcessing) "Распознаём чек…" else "Чек без названия" }
}

@Serializable data class ReceiptPage(val items: List<Receipt>, val total: Int)

@Serializable data class ReceiptResult(val receipt: Receipt, val duplicate: Boolean = false)

@Serializable
data class ReceiptComment(
    val id: String,
    val text: String,
    val author: String,
    val created_at: String,
)

@Serializable
data class Dashboard(
    val month: String,
    val income_minor: Long,
    val expense_minor: Long,
    val net_minor: Long,
    val planned_remaining_minor: Long = 0,
    val available_mdl_minor: Long = 0,
    val categories: List<Category> = emptyList(),
    val accounts: List<Account> = emptyList(),
    val balances: Map<String, Long> = emptyMap(),
)

@Serializable
data class Insight(val id: String, val title: String, val text: String, val saving_minor: Long = 0)

@Serializable data class SavedSession(val server: String, val cookie: String, val user: User)

@Serializable
data class Draft(
    val photos: List<String> = emptyList(),
    val qr: String = "",
    val accountId: String? = null,
    val pageCaptured: Boolean = false,
    val pageText: String = "",
) {
    val hasContent: Boolean
        get() = photos.isNotEmpty() || qr.isNotBlank()
}

class ApiException(val code: Int, override val message: String) : Exception(message)
