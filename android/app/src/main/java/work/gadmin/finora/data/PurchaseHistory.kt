package work.gadmin.finora.data

import java.time.YearMonth
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.Serializable
import work.gadmin.finora.localization.*

@Serializable
data class PurchaseItem(
    val id: String,
    val receipt_id: String,
    val name: String,
    val quantity: String,
    val unit: String,
    val total_minor: Long,
    val merchant: String,
    val currency: String,
    val purchased_on: String? = null,
    val category_id: String? = null,
)

@Serializable data class CurrencyTotal(val currency: String, val total_minor: Long)

@Serializable
data class PurchasePage(
    val items: List<PurchaseItem> = emptyList(),
    val total: Int = 0,
    val receipt_count: Int = 0,
    val totals: List<CurrencyTotal> = emptyList(),
)

data class PurchaseHistoryState(
    val category: Category? = null,
    val month: String = "",
    val items: List<PurchaseItem> = emptyList(),
    val total: Int = 0,
    val offset: Int = 0,
    val totals: List<CurrencyTotal> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
)

fun purchaseMonthRange(month: String): Pair<String, String> =
    YearMonth.parse(month).let {
        it.atDay(1).toString() to it.atEndOfMonth().toString()
    }

class PurchaseHistoryController(
    private val scope: CoroutineScope,
    private val client: () -> ApiClient,
    private val organization: () -> Organization?,
    private val onAuthError: (Exception) -> Unit,
) {
    private val mutable = MutableStateFlow(PurchaseHistoryState())
    val state = mutable.asStateFlow()
    private var job: Job? = null

    fun reset() {
        job?.cancel()
        mutable.value = PurchaseHistoryState()
    }

    fun open(category: Category, month: String) {
        reset()
        mutable.value = PurchaseHistoryState(category = category, month = month)
        load()
    }

    fun load(more: Boolean = false) {
        val current = mutable.value
        val category = current.category ?: return
        val org = organization()?.id ?: return
        if (current.loading || more && current.offset >= current.total) return
        job?.cancel()
        job = scope.launch {
            mutable.update { it.copy(loading = true, error = null) }
            try {
                val offset = if (more) current.offset else 0
                val result = client().purchases(org, category.id, current.month, offset)
                ensureActive()
                if (organization()?.id == org)
                    mutable.update {
                        it.copy(
                            items =
                                if (more) (it.items + result.items).distinctBy(PurchaseItem::id)
                                else result.items,
                            total = result.total,
                            totals = result.totals,
                            offset = offset + result.items.size,
                        )
                    }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (error is ApiException && error.code in setOf(401, 403)) onAuthError(error)
                else
                    mutable.update {
                        it.copy(
                            error =
                                error.message.takeIf { error is ApiException }
                                    ?: tr(
                                        Message
                                            .CANNOT_REACH_THE_SERVER_CHECK_YOUR_CONNECTION_AND_TRY_AGAI
                                    )
                        )
                    }
            } finally {
                if (isActive) mutable.update { it.copy(loading = false) }
            }
        }
    }
}
