package work.gadmin.finora.data

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import work.gadmin.finora.accounts.AccountManagementScope
import work.gadmin.finora.accounts.AccountMutationGuard
import work.gadmin.finora.accounts.AccountMutationGuardStore

class PaymentController(
    private val scope: CoroutineScope,
    private val client: () -> ApiClient,
    private val organization: () -> Organization?,
    private val month: () -> String,
    private val busy: () -> Boolean,
    private val write: (suspend () -> Unit) -> Unit,
    private val onError: (Exception) -> Unit,
    private val onSaved: (String) -> Unit,
    private val guardStore: AccountMutationGuardStore? = null,
    private val guardScope: () -> AccountManagementScope? = { null },
) {
    private val mutable = MutableStateFlow(PaymentState())
    val state = mutable.asStateFlow()
    private var loading: Job? = null
    private var matching: Job? = null
    private var mutationPending = false
    private var checkedScope: AccountManagementScope? = null
    private var checkedRequestId: String? = null

    fun reset() {
        loading?.cancel()
        matching?.cancel()
        mutable.value = PaymentState()
        checkedScope = null
        checkedRequestId = null
    }

    fun load() {
        if (organization() == null) return
        loading?.cancel()
        if (mutable.value.month != month()) {
            matching?.cancel()
            mutable.value = PaymentState(month = month())
        }
        loading = scope.launch {
            mutable.update { it.copy(loading = true, error = null) }
            try {
                fetch()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (e is ApiException && e.code == 401) onError(e)
                else mutable.update { it.copy(error = message(e)) }
            } finally {
                if (isActive) mutable.update { it.copy(loading = false) }
            }
        }
    }

    suspend fun refresh() {
        loading?.cancel()
        fetch()
    }

    private suspend fun fetch() = coroutineScope {
        val org = organization()?.id ?: return@coroutineScope
        val selected = month()
        val persisted =
            try {
                guardStore?.read(requireGuardScope(org))
            } catch (e: Exception) {
                mutable.update { it.copy(actionUncertain = true, checkedAfterUncertain = false) }
                throw e
            }
        if (persisted != null)
            mutable.update { it.copy(actionUncertain = true, checkedAfterUncertain = false) }
        val api = client()
        val rows = async { api.paymentReport(org, selected) }
        val plans = async { api.paymentTemplates(org) }
        val accounts = async { api.paymentAccounts(org) }
        val categories = async { api.categories(org) }
        val data =
            PaymentState(
                month = selected,
                occurrences = rows.await(),
                templates = plans.await(),
                accounts = accounts.await().filterNot(Account::archived),
                categories = categories.await(),
            )
        ensureActive()
        if (organization()?.id == org && month() == selected) {
            checkedScope = if (guardStore != null) guardScope() else null
            checkedRequestId = persisted?.requestId
            mutable.update {
                data.copy(
                    actionUncertain = persisted != null || it.actionUncertain,
                    checkedAfterUncertain = persisted != null || it.actionUncertain,
                    editor = it.editor,
                    editorError = it.editorError,
                    retryLocked = it.retryLocked,
                    matches = it.matches,
                    matchesLoaded = it.matchesLoaded,
                    matching = it.matching,
                )
            }
        }
    }

    fun open(form: PaymentEdit) {
        if (
            busy() ||
                mutationPending ||
                mutable.value.actionUncertain ||
                organization()?.isAdmin != true
        )
            return
        matching?.cancel()
        mutable.update {
            it.copy(
                editor =
                    form.copy(
                        accountId =
                            form.accountId.takeIf { id ->
                                it.accounts.any { a -> a.id == id && a.currency == form.currency }
                            }
                                ?: it.accounts
                                    .firstOrNull { a -> a.currency == form.currency }
                                    ?.id
                                    .orEmpty()
                    ),
                editorError = null,
                retryLocked = false,
                matches = emptyList(),
                matchesLoaded = false,
                matching = false,
            )
        }
    }

    fun closeEditor() {
        if (busy() || mutationPending) return
        matching?.cancel()
        mutable.update {
            it.copy(
                editor = null,
                editorError = null,
                retryLocked = false,
                matches = emptyList(),
                matching = false,
            )
        }
    }

    fun edit(form: PaymentEdit) {
        if (busy() || mutationPending || mutable.value.retryLocked) return
        val old = mutable.value.editor ?: return
        val changed =
            old.accountId != form.accountId || old.amount != form.amount || old.date != form.date
        if (changed) matching?.cancel()
        mutable.update {
            it.copy(
                editor = if (changed) form.copy(transactionId = null) else form,
                editorError = null,
                matches = if (changed) emptyList() else it.matches,
                matchesLoaded = !changed && it.matchesLoaded,
                matching = !changed && it.matching,
            )
        }
    }

    fun findMatches() {
        val form = mutable.value.editor ?: return
        val org = organization()?.id ?: return
        val selectedMonth = month()
        if (form.occurrenceId == null || busy() || mutable.value.retryLocked) return
        matching?.cancel()
        matching = scope.launch {
            mutable.update {
                it.copy(
                    matching = true,
                    editorError = null,
                    matchesLoaded = false,
                    matches = emptyList(),
                )
            }
            try {
                form.command(mutable.value.accounts)
                val api = client()
                val rows = completePaymentHistory { offset ->
                    ensureActive()
                    if (
                        organization()?.id != org ||
                            month() != selectedMonth ||
                            mutable.value.editor != form
                    )
                        throw CancellationException("Payment matching scope changed")
                    api.expenseHistory(org, form.date.take(7), form.accountId, offset)
                }
                ensureActive()
                if (
                    mutable.value.editor == form &&
                        organization()?.id == org &&
                        month() == selectedMonth
                )
                    mutable.update {
                        it.copy(
                            matches =
                                rows.filter { tx ->
                                    tx.kind == "expense" &&
                                        tx.occurrence_id == null &&
                                        tx.account_id == form.accountId &&
                                        tx.occurred_on == form.date &&
                                        tx.currency == form.currency &&
                                        tx.amount_minor ==
                                            receiptNumber(form.amount, true)
                                                .movePointRight(2)
                                                .longValueExact()
                                },
                            matchesLoaded = true,
                        )
                    }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (e is ApiException && e.code == 401) onError(e)
                else if (
                    mutable.value.editor == form &&
                        organization()?.id == org &&
                        month() == selectedMonth
                )
                    mutable.update { it.copy(editorError = message(e), matchesLoaded = false) }
            } finally {
                if (
                    isActive &&
                        mutable.value.editor == form &&
                        organization()?.id == org &&
                        month() == selectedMonth
                )
                    mutable.update { it.copy(matching = false) }
            }
        }
    }

    fun submit() {
        val form = mutable.value.editor ?: return
        if (mutable.value.retryLocked && form.occurrenceId == null) return
        val command =
            try {
                form.command(mutable.value.accounts)
            } catch (e: IllegalArgumentException) {
                mutable.update { it.copy(editorError = e.message) }
                return
            }
        mutate(command, true)
    }

    fun toggle(row: PaymentTemplate) = mutate(FinanceCommand("/api/bills/${row.id}/toggle", "POST"))

    fun skip(row: PaymentOccurrence) =
        mutate(FinanceCommand("/api/occurrences/${row.id}/skip", "POST"))

    private fun mutate(command: FinanceCommand, editor: Boolean = false) {
        if (
            busy() ||
                mutationPending ||
                organization()?.isAdmin != true ||
                (!editor && mutable.value.actionUncertain)
        )
            return
        mutationPending = true
        write {
            try {
                val org = organization()?.id ?: return@write
                loading?.cancel()
                mutable.update { it.copy(error = null, editorError = null, loading = false) }
                var dispatchStarted = false
                var hadPriorGuard = false
                var guard: AccountMutationGuard? = null
                var dispatchScope: AccountManagementScope? = null
                try {
                    if (guardStore != null) {
                        dispatchScope = requireGuardScope(org)
                        check(dispatchScope.admin) { "Payment write requires administrator" }
                        val existing = guardStore.read(dispatchScope)
                        hadPriorGuard = existing != null
                        val candidate =
                            AccountMutationGuard.create(
                                dispatchScope,
                                command,
                                paymentText(
                                    "Проверить результат платежного действия",
                                    "Review payment action result",
                                ),
                            )
                        if (existing != null) {
                            check(
                                editor &&
                                    mutable.value.retryLocked &&
                                    mutable.value.editor?.occurrenceId != null &&
                                    existing.commandDigest == candidate.commandDigest
                            ) {
                                paymentText(
                                    "Сначала обновите список и подтвердите проверку предыдущего действия",
                                    "Reload and acknowledge the previous action first",
                                )
                            }
                            guard = existing
                        } else {
                            guardStore.put(dispatchScope, candidate)
                            guard = candidate
                        }
                        check(
                            guardScope() == dispatchScope &&
                                organization()?.id == org &&
                                organization()?.isAdmin == true
                        ) {
                            "Payment scope changed"
                        }
                    }
                    dispatchStarted = true
                    client().financeWrite(org, command)
                    if (guardStore != null && guard != null && dispatchScope != null)
                        guardStore.clear(dispatchScope, guard.requestId)
                } catch (e: Exception) {
                    val uncertain = paymentGuardNeedsReview(hadPriorGuard, dispatchStarted, e)
                    if (
                        !uncertain && guardStore != null && guard != null && dispatchScope != null
                    ) {
                        try {
                            guardStore.clear(dispatchScope, guard.requestId)
                        } catch (clearError: Exception) {
                            mutable.update {
                                it.copy(
                                    actionUncertain = true,
                                    checkedAfterUncertain = false,
                                    error = message(clearError),
                                )
                            }
                            return@write
                        }
                    }
                    mutable.update {
                        if (editor)
                            it.copy(
                                editorError = message(e),
                                retryLocked = uncertain || it.retryLocked,
                                actionUncertain = uncertain || it.actionUncertain,
                                checkedAfterUncertain = false,
                            )
                        else
                            it.copy(
                                error = message(e),
                                actionUncertain = uncertain,
                                checkedAfterUncertain = false,
                            )
                    }
                    if (e is CancellationException) throw e
                    if (e is ApiException && e.code == 401) throw e
                    if (!editor && dispatchStarted)
                        try {
                            fetch()
                            mutable.update { it.copy(error = message(e)) }
                        } catch (refresh: Exception) {
                            if (refresh is CancellationException) throw refresh
                            if (refresh is ApiException && refresh.code == 401) throw refresh
                        }
                    return@write
                }
                mutable.update {
                    it.copy(
                        editor = null,
                        editorError = null,
                        retryLocked = false,
                        actionUncertain = false,
                        checkedAfterUncertain = false,
                        matches = emptyList(),
                        matching = false,
                    )
                }
                onSaved(paymentText("Платежи обновлены", "Payments updated"))
                try {
                    fetch()
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    if (e is ApiException && e.code == 401) throw e
                    mutable.update {
                        it.copy(
                            error =
                                paymentText(
                                    "Изменение сохранено. Не удалось обновить список — повторите загрузку.",
                                    "Saved. Could not refresh the list — reload.",
                                )
                        )
                    }
                }
            } finally {
                mutationPending = false
            }
        }
    }

    private fun requireGuardScope(org: String): AccountManagementScope =
        requireNotNull(guardScope()).also {
            check(it.organizationId == org && it.ownerId.isNotBlank()) {
                "Payment guard scope unavailable"
            }
        }

    fun acknowledgeCheckedResult() {
        if (
            busy() ||
                mutationPending ||
                mutable.value.loading ||
                !mutable.value.checkedAfterUncertain
        )
            return
        try {
            val org = organization()?.id ?: return
            if (guardStore != null) {
                val current = requireGuardScope(org)
                check(current == checkedScope) { "Payment review scope changed" }
                val unresolved = guardStore.read(current)
                check(unresolved?.requestId == checkedRequestId) {
                    "Payment guard identity changed after reread"
                }
                unresolved?.let { guardStore.clear(current, it.requestId) }
            }
            mutable.update {
                it.copy(
                    actionUncertain = false,
                    checkedAfterUncertain = false,
                    editor = null,
                    editorError = null,
                    retryLocked = false,
                    error = null,
                )
            }
        } catch (e: Exception) {
            mutable.update { it.copy(actionUncertain = true, error = message(e)) }
        }
    }

    companion object {
        private fun message(e: Exception) =
            if (e is ApiException || e is IllegalArgumentException)
                e.message ?: paymentText("Проверьте введённые данные", "Check entered details")
            else
                paymentText(
                    "Нет подтверждения сервера. Проверьте соединение. Оплата повторяется с прежним ключом; создание шаблона сначала проверьте в списке.",
                    "No server confirmation. Check connection. Payment retries use the same key; check the list before creating another template.",
                )
    }
}

/** Never present a truncated history as an exhaustive absence of matching expenses. */
suspend fun completePaymentHistory(page: suspend (Int) -> IncomeHistory): List<FinanceTransaction> {
    val result = mutableListOf<FinanceTransaction>()
    var expectedTotal: Int? = null
    do {
        currentCoroutineContext().ensureActive()
        val response = page(result.size)
        require(response.total >= 0 && (expectedTotal == null || expectedTotal == response.total)) {
            paymentText(
                "История изменилась во время поиска. Повторите поиск.",
                "History changed during matching. Retry the search.",
            )
        }
        expectedTotal = response.total
        require(response.items.isNotEmpty() || result.size == response.total) {
            paymentText(
                "Сервер вернул неполную историю. Повторите поиск.",
                "The server returned incomplete history. Retry the search.",
            )
        }
        result.addAll(response.items)
        require(
            result.size <= response.total && result.map { it.id }.distinct().size == result.size
        ) {
            paymentText(
                "История изменилась во время поиска. Повторите поиск.",
                "History changed during matching. Retry the search.",
            )
        }
    } while (result.size < requireNotNull(expectedTotal))
    return result
}

/** A later rejection cannot establish the outcome of an earlier unacknowledged attempt. */
fun paymentGuardNeedsReview(
    hadPriorGuard: Boolean,
    dispatchStarted: Boolean,
    error: Exception,
): Boolean =
    hadPriorGuard ||
        !dispatchStarted ||
        error !is ApiException ||
        error.code == 408 ||
        error.code >= 500 ||
        error.code == 401
