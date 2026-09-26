package work.gadmin.finora.data

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.*

data class FinanceState(
    val month: String = "",
    val tab: FinanceTab = FinanceTab.INCOME,
    val report: IncomeReport? = null,
    val plans: List<IncomePlan> = emptyList(),
    val debts: List<Debt> = emptyList(),
    val accounts: List<Account> = emptyList(),
    val history: List<FinanceTransaction> = emptyList(),
    val historyCount: Int = 0,
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val error: String? = null,
    val editor: FinanceEdit? = null,
    val editorError: String? = null,
    val matches: List<FinanceTransaction> = emptyList(),
    val matchesLoaded: Boolean = false,
    val matchesLoading: Boolean = false,
)

/** Uses the existing authenticated client and global write lock; never owns a second session. */
class FinanceController(
    private val scope: CoroutineScope,
    private val client: () -> ApiClient,
    private val organization: () -> Organization?,
    private val month: () -> String,
    private val busy: () -> Boolean,
    private val write: (suspend () -> Unit) -> Unit,
    private val onError: (Exception) -> Unit,
    private val onSaved: (String) -> Unit,
) {
    private val mutable = MutableStateFlow(FinanceState())
    val state = mutable.asStateFlow()
    private var loading: Job? = null
    private var matching: Job? = null

    fun reset() {
        loading?.cancel()
        matching?.cancel()
        mutable.value = FinanceState()
    }

    fun tab(tab: FinanceTab) {
        mutable.update { it.copy(tab = tab) }
    }

    fun load() {
        organization() ?: return
        loading?.cancel()
        if (mutable.value.month != month())
            mutable.update { FinanceState(month = month(), tab = it.tab) }
        loading = scope.launch {
            mutable.update { it.copy(loading = true, loadingMore = false, error = null) }
            try {
                fetch()
            } catch (error: Exception) {
                loadError(error)
            } finally {
                if (isActive) mutable.update { it.copy(loading = false) }
            }
        }
    }

    suspend fun refresh() {
        loading?.cancel()
        try {
            fetch()
        } finally {
            mutable.update { it.copy(loading = false, loadingMore = false) }
        }
    }

    private suspend fun fetch() = coroutineScope {
        val org = requireNotNull(organization()).id
        val selectedMonth = month()
        val api = client()
        val report = async { api.income(org, selectedMonth) }
        val plans = async { api.incomePlans(org) }
        val debts = async { api.debts(org) }
        val accounts = async { api.accounts(org) }
        val history = async { api.incomeHistory(org, selectedMonth) }
        val data =
            FinanceState(
                month = selectedMonth,
                report = report.await(),
                plans = plans.await(),
                debts = debts.await(),
                accounts = accounts.await().filterNot(Account::archived),
                history = history.await().items,
                historyCount = history.await().total,
            )
        ensureActive()
        if (organization()?.id == org && month() == selectedMonth)
            mutable.update {
                data.copy(
                    tab = it.tab,
                    editor = it.editor,
                    editorError = it.editorError,
                    matches = it.matches,
                    matchesLoaded = it.matchesLoaded,
                    matchesLoading = it.matchesLoading,
                )
            }
    }

    fun moreHistory() {
        if (busy() || mutable.value.loading || mutable.value.loadingMore) return
        val org = organization()?.id ?: return
        val before = mutable.value
        if (before.history.size >= before.historyCount) return
        loading = scope.launch {
            mutable.update { it.copy(loadingMore = true) }
            try {
                val page = client().incomeHistory(org, before.month, before.history.size)
                ensureActive()
                if (org == organization()?.id && before.month == month())
                    mutable.update {
                        it.copy(
                            history = (it.history + page.items).distinctBy(FinanceTransaction::id),
                            historyCount = page.total,
                        )
                    }
            } catch (error: Exception) {
                loadError(error)
            } finally {
                if (isActive) mutable.update { it.copy(loadingMore = false) }
            }
        }
    }

    private fun loadError(error: Exception) {
        if (error is CancellationException) throw error
        if (error is ApiException && error.code == 401) onError(error)
        else mutable.update { it.copy(error = message(error)) }
    }

    fun open(form: FinanceEdit) {
        if (busy() || organization()?.isAdmin != true) return
        matching?.cancel()
        mutable.update {
            it.copy(
                editor = form,
                editorError = null,
                matches = emptyList(),
                matchesLoaded = false,
                matchesLoading = false,
            )
        }
    }

    fun closeEditor() {
        if (busy()) return
        matching?.cancel()
        mutable.update {
            it.copy(
                editor = null,
                editorError = null,
                matches = emptyList(),
                matchesLoading = false,
            )
        }
    }

    fun edit(form: FinanceEdit) {
        if (busy()) return
        val old = mutable.value.editor ?: return
        val changesMatch =
            old.accountId != form.accountId || old.date != form.date || old.amount != form.amount
        if (changesMatch) matching?.cancel()
        mutable.update {
            it.copy(
                editor = if (changesMatch) form.copy(transactionId = null) else form,
                editorError = null,
                matches = if (changesMatch) emptyList() else it.matches,
                matchesLoaded = !changesMatch && it.matchesLoaded,
                matchesLoading = !changesMatch && it.matchesLoading,
            )
        }
    }

    fun findMatches() {
        val form = mutable.value.editor ?: return
        val org = organization()?.id ?: return
        if (busy() || form.kind != FinanceEditKind.RECEIVE) return
        matching?.cancel()
        matching = scope.launch {
            mutable.update { it.copy(matchesLoading = true, editorError = null) }
            try {
                form.command(mutable.value.accounts)
                val result =
                    client()
                        .incomeHistory(
                            org,
                            form.date.take(7),
                            account = form.accountId,
                            limit = 200,
                        )
                ensureActive()
                if (mutable.value.editor == form)
                    mutable.update {
                        it.copy(matches = matchingIncome(result.items, form), matchesLoaded = true)
                    }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (error is ApiException && error.code == 401) onError(error)
                else mutable.update { it.copy(editorError = message(error)) }
            } finally {
                if (isActive) mutable.update { it.copy(matchesLoading = false) }
            }
        }
    }

    fun submit() {
        val form = mutable.value.editor ?: return
        val command =
            try {
                form.command(mutable.value.accounts)
            } catch (error: IllegalArgumentException) {
                mutable.update { it.copy(editorError = error.message) }
                return
            }
        mutate(
            command,
            when (form.kind) {
                FinanceEditKind.PLAN -> "Источник дохода сохранён"
                FinanceEditKind.DEBT -> "Долг записан"
                FinanceEditKind.REPAY -> "Возврат учтён"
                else -> "Доход сохранён"
            },
            editor = true,
        )
    }

    fun toggle(plan: IncomePlan) =
        mutate(
            FinanceCommand(
                "/api/income/templates/${plan.id}/active",
                "PUT",
                buildJsonObject {
                    put("active", !plan.active)
                    put("version", plan.version)
                },
            ),
            if (plan.active) "Источник приостановлен" else "Источник возобновлён",
        )

    fun skip(row: IncomeOccurrence) =
        mutate(
            FinanceCommand("/api/income/occurrences/${row.id}/skip", "POST"),
            if (row.status == "skipped") "Поступление возвращено в план"
            else "Поступление пропущено",
        )

    fun void(tx: FinanceTransaction) =
        mutate(
            FinanceCommand("/api/transactions/${tx.id}?version=${tx.version}", "DELETE"),
            "Зачисление отменено",
        )

    private fun mutate(command: FinanceCommand, notice: String, editor: Boolean = false) {
        if (busy() || organization()?.isAdmin != true) return
        write {
            val org = requireNotNull(organization()).id
            loading?.cancel()
            mutable.update {
                it.copy(editorError = null, error = null, loading = false, loadingMore = false)
            }
            try {
                client().financeWrite(org, command)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (error is ApiException && error.code == 401) throw error
                mutable.update {
                    if (editor) it.copy(editorError = message(error))
                    else it.copy(error = message(error))
                }
                // Refresh toggle/version state after uncertain responses, without blindly retrying.
                if (!editor)
                    try {
                        fetch()
                    } catch (refreshError: Exception) {
                        if (refreshError is CancellationException) throw refreshError
                        if (refreshError is ApiException && refreshError.code == 401)
                            throw refreshError
                    }
                if (!editor) mutable.update { it.copy(error = message(error)) }
                return@write
            }
            matching?.cancel()
            mutable.update {
                it.copy(
                    editor = null,
                    editorError = null,
                    matches = emptyList(),
                    matchesLoading = false,
                )
            }
            onSaved(notice)
            try {
                fetch()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (error is ApiException && error.code == 401) throw error
                mutable.update {
                    it.copy(
                        error = "Изменение сохранено. Не удалось обновить список — потяните вниз."
                    )
                }
            }
        }
    }

    companion object {
        private fun message(error: Exception) =
            when (error) {
                is ApiException,
                is IllegalArgumentException -> error.message ?: "Проверьте введённые данные"
                else ->
                    "Нет подтверждения от сервера. Проверьте интернет и повторите с теми же данными."
            }
    }
}
