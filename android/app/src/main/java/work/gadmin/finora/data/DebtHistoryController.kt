package work.gadmin.finora.data

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import work.gadmin.finora.localization.Message
import work.gadmin.finora.localization.tr

data class DebtHistoryState(
    val debt: Debt? = null,
    val page: DebtHistory? = null,
    val nextOffset: Int = 0,
    val loading: Boolean = false,
    val error: String? = null,
)

/** Read-only history tied to the current user, server, organization and request generation. */
class DebtHistoryController(
    private val scope: CoroutineScope,
    private val api: DebtHistoryApi,
    private val currentScope: () -> NavigationScope?,
    private val onAuthError: (Exception) -> Unit,
) {
    private val mutable = MutableStateFlow(DebtHistoryState())
    val state = mutable.asStateFlow()
    private var loading: Job? = null
    private var generation = 0L
    private var openedScope: NavigationScope? = null
    private var failedOffset = 0

    fun open(debt: Debt) {
        close()
        openedScope = currentScope() ?: return
        mutable.value = DebtHistoryState(debt = debt)
        load(0)
    }

    fun close() {
        generation++
        loading?.cancel()
        openedScope = null
        mutable.value = DebtHistoryState()
    }

    fun more() {
        val before = mutable.value
        if (before.loading || before.nextOffset >= (before.page?.total ?: 0)) return
        load(before.nextOffset)
    }

    fun retry() {
        if (!mutable.value.loading) load(failedOffset)
    }

    private fun load(offset: Int) {
        val expected = openedScope ?: return
        val debt = mutable.value.debt ?: return
        if (currentScope() != expected) {
            close()
            return
        }
        loading?.cancel()
        val token = ++generation
        failedOffset = offset
        fun current() = generation == token && openedScope == expected && currentScope() == expected
        mutable.update { it.copy(loading = true, error = null) }
        loading = scope.launch {
            try {
                var start = offset
                var result = api.history(expected.organizationId, debt.id, start)
                ensureActive()
                if (!current()) return@launch
                // A concurrent repayment/increase/undo can shift offset pages. Reload the first
                // page instead of combining balances from two different snapshots.
                if (start > 0 && mutable.value.page?.sameSummary(result) != true) {
                    start = 0
                    failedOffset = 0
                    result = api.history(expected.organizationId, debt.id, 0)
                    ensureActive()
                    if (!current()) return@launch
                }
                require(result.debt_id == debt.id && result.currency == debt.currency)
                mutable.update {
                    it.copy(
                        page =
                            result.copy(
                                items =
                                    (if (start == 0) result.items
                                        else it.page.orEmptyItems() + result.items)
                                        .distinctBy(DebtHistoryEntry::id)
                            ),
                        nextOffset =
                            if (result.items.isEmpty()) result.total else start + result.items.size,
                    )
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (!current()) return@launch
                when {
                    error is ApiException && error.code == 401 -> {
                        close()
                        onAuthError(error)
                    }
                    error is ApiException && (error.code == 403 || error.code == 404) -> {
                        failedOffset = 0
                        mutable.update {
                            it.copy(
                                page = null,
                                nextOffset = 0,
                                error = error.message,
                            )
                        }
                    }
                    else -> mutable.update { it.copy(error = tr(Message.DEBT_HISTORY_FAILED)) }
                }
            } finally {
                if (current()) mutable.update { it.copy(loading = false) }
            }
        }
    }

    private fun DebtHistory?.orEmptyItems() = this?.items.orEmpty()
}
