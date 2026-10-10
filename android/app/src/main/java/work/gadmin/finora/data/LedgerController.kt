package work.gadmin.finora.data

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class LedgerState(
    val organizationId: String = "",
    val organizationName: String = "",
    val month: String = "",
    val history: List<LedgerTransaction> = emptyList(),
    val historyCount: Int = 0,
    val historyOffset: Int = 0,
    val accounts: List<Account> = emptyList(),
    val categories: List<Category> = emptyList(),
    val kind: String? = null,
    val accountId: String? = null,
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val error: String? = null,
    val editor: LedgerEdit? = null,
    val editorError: String? = null,
    val editorRetryLocked: Boolean = false,
    val confirmation: FinanceCommand? = null,
    val voiding: LedgerTransaction? = null,
    val pending: LedgerPendingMutation? = null,
)

class LedgerController(
    private val scope: CoroutineScope,
    client: () -> ApiClient,
    private val organization: () -> Organization?,
    private val month: () -> String,
    private val busy: () -> Boolean,
    private val write: (suspend () -> Unit) -> Unit,
    private val onError: (Exception) -> Unit,
    private val onSaved: (String) -> Unit,
    private val api: LedgerApi = AuthenticatedLedgerApi(client),
    private val pendingStore: LedgerPendingStore = UnavailableLedgerPendingStore,
    private val owner: () -> String = { "" },
) {
    private val mutable = MutableStateFlow(LedgerState())
    val state = mutable.asStateFlow()
    private var loading: Job? = null
    private var generation = 0L
    private var mutating = false

    fun reset() {
        generation++
        loading?.cancel()
        mutable.value = LedgerState()
    }

    private fun current(org: String, selectedMonth: String, token: Long) =
        organization()?.id == org && month() == selectedMonth && generation == token

    fun load() {
        val org = organization()?.id ?: return
        if (mutable.value.organizationId != org || mutable.value.month != month()) reset()
        loading?.cancel()
        val token = ++generation
        val selectedMonth = month()
        mutable.update {
            it.copy(
                organizationId = org,
                organizationName = organization()?.name.orEmpty(),
                month = month(),
                loading = true,
                error = null,
            )
        }
        loading = scope.launch {
            try {
                fetch(org, selectedMonth, token)
            } catch (e: Exception) {
                failure(e, org, token)
            } finally {
                if (generation == token)
                    mutable.update { it.copy(loading = false, loadingMore = false) }
            }
        }
    }

    suspend fun refresh() {
        val org = organization()?.id ?: return
        loading?.cancel()
        val token = ++generation
        if (mutable.value.organizationId != org || mutable.value.month != month())
            mutable.value =
                LedgerState(
                    organizationId = org,
                    organizationName = organization()?.name.orEmpty(),
                    month = month(),
                )
        try {
            fetch(org, month(), token)
        } finally {
            if (generation == token)
                mutable.update { it.copy(loading = false, loadingMore = false) }
        }
    }

    private suspend fun fetch(org: String, selectedMonth: String, token: Long) = coroutineScope {
        val filters = mutable.value
        val page = async { api.history(org, selectedMonth, 0, filters.kind, filters.accountId) }
        val accounts = async { api.accounts(org) }
        val categories = async { api.categories(org) }
        val result = page.await()
        val accountRows = accounts.await()
        val categoryRows = categories.await()
        ensureActive()
        if (current(org, selectedMonth, token))
            mutable.update {
                it.copy(
                    organizationId = org,
                    month = selectedMonth,
                    history = result.items,
                    historyCount = result.total,
                    historyOffset = result.items.size,
                    accounts = accountRows,
                    categories = categoryRows,
                    error = null,
                )
            }
    }

    fun filter(kind: String?, accountId: String?) {
        if (busy() || mutating) return
        require(
            kind == null ||
                kind in
                    setOf("expense", "income", "transfer", "refund", "adjustment", "adjustment_out")
        )
        mutable.update {
            it.copy(
                kind = kind,
                accountId = accountId,
                history = emptyList(),
                historyCount = 0,
                historyOffset = 0,
            )
        }
        load()
    }

    fun moreHistory() {
        val before = mutable.value
        if (
            busy() ||
                mutating ||
                before.loading ||
                before.loadingMore ||
                before.historyOffset >= before.historyCount
        )
            return
        val org = organization()?.id ?: return
        val token = generation
        mutable.update { it.copy(loadingMore = true, error = null) }
        loading = scope.launch {
            try {
                val result =
                    api.history(
                        org,
                        before.month,
                        before.historyOffset,
                        before.kind,
                        before.accountId,
                    )
                ensureActive()
                if (current(org, before.month, token))
                    mutable.update {
                        it.copy(
                            history = (it.history + result.items).distinctBy(LedgerTransaction::id),
                            historyCount = result.total,
                            historyOffset = before.historyOffset + result.items.size,
                        )
                    }
            } catch (e: Exception) {
                failure(e, org, token)
            } finally {
                if (generation == token) mutable.update { it.copy(loadingMore = false) }
            }
        }
    }

    fun open(form: LedgerEdit = LedgerEdit()) {
        if (busy() || mutating || organization()?.isAdmin != true) return
        val pending =
            try {
                pendingStore.read(owner(), requireNotNull(organization()).id)
            } catch (e: Exception) {
                mutable.update { it.copy(error = e.message) }
                return
            }
        if (pending != null) {
            mutable.update {
                it.copy(
                    editor = pending.form,
                    pending = pending,
                    editorRetryLocked = true,
                    editorError =
                        ledgerText(
                            "Ожидается подтверждение прежней операции",
                            "Previous transaction awaits confirmation",
                        ),
                    confirmation = null,
                )
            }
            return
        }
        mutable.update {
            it.copy(
                editor = form,
                editorError = null,
                editorRetryLocked = false,
                confirmation = null,
                voiding = null,
            )
        }
    }

    fun edit(form: LedgerEdit) {
        if (!busy() && !mutating && !mutable.value.editorRetryLocked)
            mutable.update { it.copy(editor = form, editorError = null, confirmation = null) }
    }

    fun retryPending() {
        if (busy() || mutating) return
        val org = organization()?.takeIf { it.isAdmin }?.id ?: return
        try {
            val pending = pendingStore.read(owner(), org) ?: return
            mutable.update {
                it.copy(
                    editor = pending.form,
                    pending = pending,
                    editorRetryLocked = true,
                    confirmation = pending.command(),
                )
            }
        } catch (e: Exception) {
            mutable.update { it.copy(editorError = e.message) }
        }
    }

    fun closeEditor() {
        if (!busy() && !mutating)
            mutable.update {
                it.copy(
                    editor = null,
                    confirmation = null,
                    editorError = null,
                    editorRetryLocked = false,
                )
            }
    }

    /** Validates first, then UI shows an explicit money movement confirmation. */
    fun submit() {
        if (busy() || mutating || mutable.value.editorRetryLocked) return
        try {
            val form = mutable.value.editor ?: return
            mutable.update { it.copy(confirmation = form.command(it.accounts), editorError = null) }
        } catch (e: IllegalArgumentException) {
            mutable.update { it.copy(editorError = e.message ?: "Check the entered details") }
        }
    }

    fun void(tx: LedgerTransaction) {
        if (!busy() && !mutating && organization()?.isAdmin == true)
            mutable.update { it.copy(voiding = tx, confirmation = null) }
    }

    fun cancelConfirmation() {
        if (!busy() && !mutating) mutable.update { it.copy(confirmation = null, voiding = null) }
    }

    fun confirm() {
        val before = mutable.value
        val org = organization()?.takeIf { it.isAdmin }?.id ?: return
        if (
            busy() ||
                mutating ||
                org != before.organizationId ||
                month() != before.month ||
                before.editorRetryLocked && before.confirmation != before.pending?.command()
        )
            return
        val command =
            before.confirmation
                ?: before.voiding?.let {
                    FinanceCommand("/api/transactions/${it.id}?version=${it.version}", "DELETE")
                }
                ?: return
        val editor = before.confirmation != null
        val token = generation
        val mutationOwner = owner()
        mutating = true
        write {
            try {
                // Recheck scope after waiting for the global write lock.
                if (!current(org, before.month, token) || organization()?.isAdmin != true)
                    return@write
                loading?.cancel()
                val pending =
                    if (editor) {
                        require(mutationOwner.isNotBlank()) { "Ledger owner scope is unavailable" }
                        val candidate =
                            LedgerPendingMutation(
                                mutationOwner,
                                org,
                                requireNotNull(before.editor),
                                command.path,
                                command.method,
                                command.body,
                            )
                        pendingStore.save(candidate)
                        mutable.update { it.copy(pending = candidate) }
                        candidate
                    } else null
                require(owner() == mutationOwner) { "Ledger owner changed" }
                api.write(org, command)
                if (pending != null) pendingStore.clear(pending)
                if (!current(org, before.month, token)) return@write
                mutable.update {
                    it.copy(
                        editor = null,
                        confirmation = null,
                        voiding = null,
                        editorError = null,
                        editorRetryLocked = false,
                        pending = null,
                    )
                }
                onSaved(ledgerText("Операция сохранена", "Transaction saved"))
                try {
                    fetch(org, before.month, token)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    if (e is ApiException && e.code == 401) throw e
                    mutable.update {
                        it.copy(
                            error =
                                ledgerText("Сохранено. Обновите список", "Saved. Refresh the list")
                        )
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (e is ApiException && e.code == 401) {
                    onError(e)
                    return@write
                }
                if (current(org, before.month, token)) {
                    val uncertain = e !is ApiException || e.code == 408 || e.code >= 500
                    if (editor && !uncertain && before.pending == null && !before.editorRetryLocked)
                        before.editor?.let { form ->
                            try {
                                pendingStore.read(mutationOwner, org)?.let(pendingStore::clear)
                            } catch (_: Exception) {
                                /* Keep immutable pending if durable clearing fails. */
                            }
                        }
                    val pendingRead = runCatching {
                        if (editor) pendingStore.read(mutationOwner, org) else null
                    }
                    val retained = pendingRead.getOrNull()
                    mutable.update {
                        if (editor)
                            it.copy(
                                editorError = errorText(e),
                                editorRetryLocked =
                                    uncertain || retained != null || pendingRead.isFailure,
                                pending = retained,
                                confirmation = null,
                            )
                        else it.copy(error = errorText(e), voiding = null)
                    }
                    if (!editor)
                        try {
                            fetch(org, before.month, token)
                            mutable.update { it.copy(error = errorText(e)) }
                        } catch (refresh: Exception) {
                            if (refresh is CancellationException) throw refresh
                            if (refresh is ApiException && refresh.code == 401) onError(refresh)
                        }
                }
            } finally {
                mutating = false
            }
        }
    }

    private fun failure(e: Exception, org: String, token: Long) {
        if (e is CancellationException) throw e
        if (!current(org, mutable.value.month, token)) return
        if (e is ApiException && e.code == 401) onError(e)
        else mutable.update { it.copy(error = errorText(e)) }
    }

    private fun errorText(e: Exception) =
        if (e is ApiException) e.message
        else
            ledgerText(
                "Нет подтверждения сервера. Обновите историю перед новой попыткой",
                "No server confirmation. Refresh history before trying again",
            )
}
