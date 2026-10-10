package work.gadmin.finora.data

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Budget limits never create ledger transactions. Copy uses a server snapshot fingerprint. */
data class BudgetState(
    val month: String = "",
    val categories: List<BudgetCategory> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val editor: BudgetEdit? = null,
    val editorError: String? = null,
    val retryLocked: Boolean = false,
    val copyOpen: Boolean = false,
    val copyLoading: Boolean = false,
    val preview: BudgetCopyPreview? = null,
    val copyError: String? = null,
)

class BudgetController(
    private val scope: CoroutineScope,
    private val client: () -> ApiClient,
    private val organization: () -> Organization?,
    private val month: () -> String,
    private val busy: () -> Boolean,
    private val write: (suspend () -> Unit) -> Unit,
    private val onError: (Exception) -> Unit,
    private val onSaved: (String) -> Unit,
) {
    private val mutable = MutableStateFlow(BudgetState())
    val state = mutable.asStateFlow()
    private var loading: Job? = null
    private var previewJob: Job? = null

    private fun allowed() = organization()?.isAdmin == true && !busy()

    fun reset() {
        loading?.cancel()
        previewJob?.cancel()
        mutable.value = BudgetState()
    }

    fun load() {
        if (organization() == null) return
        loading?.cancel()
        if (mutable.value.month != month()) mutable.value = BudgetState(month = month())
        loading = scope.launch {
            mutable.update { it.copy(loading = true, error = null) }
            try {
                fetch()
            } catch (e: Exception) {
                fail(e)
            } finally {
                if (isActive) mutable.update { it.copy(loading = false) }
            }
        }
    }

    suspend fun refresh() {
        loading?.cancel()
        fetch()
    }

    private suspend fun fetch() {
        val org = organization()?.id ?: return
        val selected = month()
        val result = client().budgetDashboard(org, selected)
        currentCoroutineContext().ensureActive()
        if (organization()?.id == org && month() == selected)
            mutable.update {
                it.copy(month = selected, categories = result.categories, loading = false)
            }
    }

    private fun fail(e: Exception) {
        if (e is CancellationException) throw e
        if (e is ApiException && e.code == 401) onError(e)
        else mutable.update { it.copy(error = message(e)) }
    }

    fun open(category: BudgetCategory) {
        if (!allowed() || category.id == "uncategorized") return
        mutable.update {
            it.copy(
                editor =
                    BudgetEdit(
                        month(),
                        category,
                        category.budget_minor
                            ?.let { n -> java.math.BigDecimal.valueOf(n, 2).toPlainString() }
                            .orEmpty(),
                    ),
                editorError = null,
                retryLocked = false,
            )
        }
    }

    fun edit(value: String) {
        if (!allowed() || mutable.value.retryLocked) return
        mutable.update { it.copy(editor = it.editor?.copy(amount = value), editorError = null) }
    }

    fun closeEditor() {
        if (busy()) return
        val uncertain = mutable.value.retryLocked
        mutable.update { it.copy(editor = null, editorError = null, retryLocked = false) }
        if (uncertain) load()
    }

    fun save() {
        val form = mutable.value.editor ?: return
        if (form.month != month()) {
            closeEditor()
            load()
            return
        }
        val command =
            try {
                form.command()
            } catch (e: IllegalArgumentException) {
                mutable.update { it.copy(editorError = e.message) }
                return
            }
        mutate(command, budgetText("Лимит сохранён", "Limit saved"), editor = true)
    }

    fun delete() {
        val form = mutable.value.editor ?: return
        val id = form.category.budget_id ?: return
        if (form.month != month() || mutable.value.retryLocked) return
        mutate(
            FinanceCommand("/api/budgets/$id", "DELETE"),
            budgetText("Лимит удалён", "Limit removed"),
            removal = true,
        )
    }

    fun requestCopy() {
        if (!allowed()) return
        previewJob?.cancel()
        val org = organization()!!.id
        val selected = month()
        mutable.update {
            it.copy(copyOpen = true, copyLoading = true, preview = null, copyError = null)
        }
        previewJob = scope.launch {
            try {
                val result = client().budgetCopyPreview(org, selected)
                ensureActive()
                if (organization()?.id == org && month() == selected && mutable.value.copyOpen)
                    mutable.update { it.copy(preview = result) }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (e is ApiException && e.code == 401) onError(e)
                else mutable.update { it.copy(copyError = message(e)) }
            } finally {
                if (isActive) mutable.update { it.copy(copyLoading = false) }
            }
        }
    }

    fun closeCopy() {
        if (!busy()) {
            previewJob?.cancel()
            mutable.update {
                it.copy(copyOpen = false, preview = null, copyError = null, copyLoading = false)
            }
        }
    }

    fun applyCopy() {
        val preview = mutable.value.preview ?: return
        val command =
            try {
                preview.command(month())
            } catch (e: IllegalArgumentException) {
                mutable.update { it.copy(preview = null, copyError = e.message) }
                return
            }
        mutate(command, budgetText("Лимиты перенесены", "Limits copied"), copy = true)
    }

    private fun mutate(
        command: FinanceCommand,
        notice: String,
        editor: Boolean = false,
        copy: Boolean = false,
        removal: Boolean = false,
    ) {
        if (!allowed()) return
        write {
            val org = organization()?.id ?: return@write
            mutable.update { it.copy(editorError = null, copyError = null, error = null) }
            try {
                client().financeWrite(org, command)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (e is ApiException && e.code == 401) throw e
                mutable.update {
                    it.copy(
                        editor = if (removal) null else it.editor,
                        error = if (removal) message(e) else it.error,
                        editorError = if (editor) message(e) else it.editorError,
                        retryLocked =
                            if (editor)
                                it.retryLocked ||
                                    e !is ApiException ||
                                    e.code == 408 ||
                                    e.code >= 500
                            else it.retryLocked,
                        copyError = if (copy) message(e) else it.copyError,
                        // A stale or uncertain copy cannot be applied again without a fresh review.
                        preview = if (copy) null else it.preview,
                    )
                }
                try {
                    fetch()
                } catch (refresh: Exception) {
                    if (
                        refresh is CancellationException ||
                            refresh is ApiException && refresh.code == 401
                    )
                        throw refresh
                }
                return@write
            }
            mutable.update {
                it.copy(
                    editor = null,
                    editorError = null,
                    retryLocked = false,
                    copyOpen = false,
                    preview = null,
                )
            }
            onSaved(notice)
            try {
                fetch()
            } catch (e: Exception) {
                fail(e)
            }
        }
    }

    private fun message(e: Exception): String =
        when {
            e is ApiException && e.code == 409 ->
                budgetText(
                    "Лимиты изменились. Проверьте перенос заново.",
                    "Limits changed. Review the copy again.",
                )
            e is ApiException && e.code in listOf(404, 405) ->
                budgetText(
                    "Сервер не поддерживает эту операцию. Обновите данные.",
                    "This server does not support this operation. Refresh the data.",
                )
            e is ApiException -> e.message
            else ->
                budgetText(
                    "Нет подтверждения сервера. Проверьте соединение и повторите те же данные.",
                    "No server confirmation. Check your connection and retry the same details.",
                )
        }
}
