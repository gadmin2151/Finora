package work.gadmin.finora.data

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

// Only bounded, read-only reports already supported by /api/reports.
enum class NativeAnalyticsMode {
    REPORTS,
    PURCHASES,
}

enum class NativeReportKind(val wire: String) {
    SUMMARY("summary"),
    CATEGORIES("categories"),
    MERCHANTS("merchants"),
    TREND("trend"),
    PRICES("prices"),
}

interface NativeAnalyticsReader {
    suspend fun report(scope: NavigationScope, kind: NativeReportKind): AnalyticsReport

    suspend fun purchases(scope: NavigationScope, offset: Int): PurchasePage
}

data class NativeAnalyticsState(
    val scope: NavigationScope? = null,
    val mode: NativeAnalyticsMode = NativeAnalyticsMode.REPORTS,
    val kind: NativeReportKind = NativeReportKind.SUMMARY,
    val report: AnalyticsReport? = null,
    val purchases: PurchasePage = PurchasePage(),
    val loading: Boolean = false,
    val error: String? = null,
)

/** GET only. A changed user/server/organization/month invalidates every in-flight result. */
class NativeAnalyticsController(
    private val coroutineScope: CoroutineScope,
    private val reader: NativeAnalyticsReader,
    private val currentScope: () -> NavigationScope,
    private val onError: (Exception) -> Unit,
) {
    private val mutable = MutableStateFlow(NativeAnalyticsState())
    val state = mutable.asStateFlow()
    private var job: Job? = null
    private var generation = 0L

    fun reset() {
        generation++
        job?.cancel()
        mutable.value = NativeAnalyticsState()
    }

    fun open(mode: NativeAnalyticsMode) {
        reset()
        mutable.value = NativeAnalyticsState(scope = currentScope(), mode = mode)
        load()
    }

    fun resetForMonth() {
        val previous = mutable.value
        reset()
        mutable.value =
            NativeAnalyticsState(scope = currentScope(), mode = previous.mode, kind = previous.kind)
        load()
    }

    fun kind(kind: NativeReportKind) {
        if (mutable.value.kind == kind) return
        job?.cancel()
        generation++
        mutable.update { it.copy(kind = kind, report = null, loading = false, error = null) }
        load()
    }

    fun load(more: Boolean = false) {
        val current = mutable.value
        if (current.loading || currentScope().organizationId.isBlank()) return
        val bound = currentScope()
        val sameScope = current.scope == bound
        val base =
            if (sameScope) current
            else current.copy(scope = bound, report = null, purchases = PurchasePage())
        val offset = if (more && sameScope) base.purchases.items.size else 0
        if (more && offset >= base.purchases.total) return
        job?.cancel()
        val token = ++generation
        mutable.value = base.copy(scope = bound, loading = true, error = null)
        job = coroutineScope.launch {
            try {
                if (base.mode == NativeAnalyticsMode.REPORTS) {
                    val report = reader.report(bound, base.kind)
                    ensureActive()
                    if (generation == token && currentScope() == bound)
                        mutable.update { it.copy(report = report) }
                } else {
                    val page = reader.purchases(bound, offset)
                    ensureActive()
                    if (generation == token && currentScope() == bound)
                        mutable.update {
                            it.copy(
                                purchases =
                                    page.copy(
                                        items =
                                            if (offset == 0) page.items
                                            else
                                                (base.purchases.items + page.items).distinctBy(
                                                    PurchaseItem::id
                                                )
                                    )
                            )
                        }
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (generation == token && currentScope() == bound) {
                    mutable.update { it.copy(error = error.message ?: "Unable to load data") }
                    onError(error)
                }
            } finally {
                if (generation == token && currentScope() == bound)
                    mutable.update { it.copy(loading = false) }
            }
        }
    }

    suspend fun refresh() {
        job?.cancel()
        generation++
        mutable.update { it.copy(loading = false) }
        load()
        job?.join()
    }
}
