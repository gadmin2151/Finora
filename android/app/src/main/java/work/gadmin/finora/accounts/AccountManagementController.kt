package work.gadmin.finora.accounts

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import work.gadmin.finora.data.ApiException
import work.gadmin.finora.data.FinanceCommand
import work.gadmin.finora.data.accountText

/** Call on the main dispatcher; host write must invoke its block exactly once when accepted. */
class AccountManagementController(
    private val scope: CoroutineScope,
    private val api: AccountManagementApi,
    private val currentScope: () -> AccountManagementScope?,
    private val busy: () -> Boolean,
    private val write: (suspend () -> Unit) -> Unit,
    private val onAuthError: (Exception) -> Unit,
    private val onSaved: () -> Unit,
    private val guardStore: AccountMutationGuardStore,
) {
    private val mutable = MutableStateFlow(AccountManagementState())
    val state = mutable.asStateFlow()
    private var read: Job? = null
    private var generation = 0L
    private var readSerial = 0L

    /** Scope changes never remove durable unresolved requests. Storage failures fail closed. */
    fun syncScope() {
        val now = currentScope()
        if (mutable.value.scope != now) {
            generation++
            read?.cancel()
            mutable.value = AccountManagementState(scope = now)
        }
        if (now == null) return
        try {
            val guard = guardStore.read(now)
            mutable.update {
                it.copy(
                    uncertain = guard != null,
                    pendingDescription = guard?.description,
                    guardUnavailable = false,
                    checkedAfterUncertain = if (guard == null) false else it.checkedAfterUncertain,
                )
            }
        } catch (e: Exception) {
            mutable.update {
                it.copy(
                    uncertain = true,
                    guardUnavailable = true,
                    checkedAfterUncertain = false,
                    error =
                        accountText(
                            "Не удалось прочитать защиту операции. Изменения заблокированы.",
                            "Could not read the operation guard. Changes are blocked.",
                        ),
                )
            }
        }
    }

    fun reset() {
        generation++
        read?.cancel()
        mutable.value = AccountManagementState()
    }

    private fun active(s: AccountManagementScope, token: Long) =
        token == generation && currentScope() == s && mutable.value.scope == s

    fun load() {
        syncScope()
        val s = mutable.value.scope ?: return
        val token = generation
        val serial = ++readSerial
        read?.cancel()
        mutable.update {
            it.copy(
                loading = true,
                loaded = false,
                error = null,
                checkedAfterUncertain = false,
                confirmation = null,
                editor = null,
                editorError = null,
            )
        }
        read = scope.launch {
            try {
                val rows = api.list(s.organizationId)
                ensureActive()
                if (active(s, token) && serial == readSerial)
                    mutable.update {
                        it.copy(
                            accounts = rows,
                            loaded = true,
                            checkedAfterUncertain = it.uncertain,
                        )
                    }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (active(s, token) && serial == readSerial) {
                    mutable.update { it.copy(error = e.message) }
                    if (e is ApiException && e.code == 401) onAuthError(e)
                }
            } finally {
                if (active(s, token) && serial == readSerial)
                    mutable.update { it.copy(loading = false) }
            }
        }
    }

    suspend fun refresh() {
        load()
        read?.join()
    }

    private fun allowed(): Boolean {
        syncScope()
        val data = mutable.value
        return data.scope?.admin == true &&
            data.loaded &&
            !data.loading &&
            !data.writing &&
            !data.uncertain &&
            !busy()
    }

    fun open(row: ManagedAccount? = null) {
        if (!allowed()) return
        val data = mutable.value
        if (row == null && data.scope?.combined == true) return
        val fresh = row?.let { selected ->
            data.accounts.firstOrNull { it.id == selected.id } ?: return
        }
        if (fresh?.archived == true) return
        mutable.update {
            it.copy(
                editor = fresh?.let(ManagedAccountForm::from) ?: ManagedAccountForm(),
                editorError = null,
                confirmation = null,
            )
        }
    }

    fun edit(form: ManagedAccountForm) {
        if (!allowed() || mutable.value.confirmation != null) return
        val before = mutable.value.editor ?: return
        if (before.id != form.id || (before.id != null && before.currency != form.currency)) return
        mutable.update { it.copy(editor = form, editorError = null) }
    }

    fun closeEditor() {
        if (!mutable.value.writing && !busy())
            mutable.update { it.copy(editor = null, editorError = null, confirmation = null) }
    }

    fun prepare() {
        if (!allowed()) return
        val data = mutable.value
        val s = data.scope ?: return
        val form = data.editor ?: return
        if (form.id == null && s.combined) return
        if (form.id != null) {
            val row = data.accounts.firstOrNull { it.id == form.id } ?: return
            if (row.archived || row.currency != form.currency) return
            if (s.combined && row.id != s.defaultAccountId && row.currency == "MDL") {
                mutable.update {
                    it.copy(
                        editorError =
                            accountText(
                                "Этот счёт недоступен в режиме «Всё вместе»",
                                "This account cannot be edited in combined mode",
                            )
                    )
                }
                return
            }
        }
        try {
            val command = form.command()
            mutable.update {
                it.copy(
                    confirmation =
                        AccountManagementConfirmation(
                            command,
                            "${form.name.trim()}\n${accountText("Начальный остаток", "Opening balance")}: ${form.openingBalance} ${form.currency}",
                            s,
                        ),
                    editorError = null,
                )
            }
        } catch (e: IllegalArgumentException) {
            mutable.update { it.copy(editorError = e.message) }
        }
    }

    fun archive(row: ManagedAccount) {
        if (!allowed()) return
        val s = mutable.value.scope ?: return
        val fresh = mutable.value.accounts.firstOrNull { it.id == row.id } ?: return
        if (!fresh.archived && s.combined && fresh.id == s.defaultAccountId) {
            mutable.update {
                it.copy(
                    error =
                        accountText(
                            "Сначала выберите другой основной счёт в настройках",
                            "Choose another default account in settings first",
                        )
                )
            }
            return
        }
        mutable.update {
            it.copy(
                confirmation =
                    AccountManagementConfirmation(
                        FinanceCommand("/api/accounts/${fresh.id}/archive", "POST"),
                        (if (fresh.archived) accountText("Восстановить счёт: ", "Restore account: ")
                        else accountText("Архивировать счёт: ", "Archive account: ")) + fresh.name,
                        s,
                    )
            )
        }
    }

    fun cancelConfirmation() {
        if (!mutable.value.writing && !busy()) mutable.update { it.copy(confirmation = null) }
    }

    fun confirm() {
        if (!allowed()) return
        val confirmation = mutable.value.confirmation ?: return
        val s = mutable.value.scope ?: return
        if (s != confirmation.scope) return
        val token = generation
        mutable.update { it.copy(writing = true) }
        write {
            var started = false
            var dispatched = false
            var persisted: AccountMutationGuard? = null
            try {
                if (!active(s, token) || !s.admin) return@write
                val guard =
                    AccountMutationGuard.create(s, confirmation.command, confirmation.description)
                guardStore.put(s, guard) // fsync + verification must complete before dispatch
                persisted = guard
                if (!active(s, token)) {
                    guardStore.clear(s, guard.requestId)
                    return@write
                }
                started = true
                dispatched = true
                api.write(s.organizationId, confirmation.command)
                started = false
                guardStore.clear(s, guard.requestId)
                persisted = null
                if (active(s, token)) {
                    mutable.update {
                        it.copy(
                            editor = null,
                            confirmation = null,
                            editorError = null,
                            error = null,
                        )
                    }
                    onSaved()
                    load()
                }
            } catch (e: Exception) {
                val ambiguous = started && (e !is ApiException || e.code == 408 || e.code >= 500)
                if (!ambiguous && persisted != null) {
                    try {
                        guardStore.clear(s, persisted.requestId)
                        persisted = null
                    } catch (_: Exception) {
                        /* keep durable guard until explicit reread review */
                    }
                }
                if (active(s, token)) {
                    syncScope()
                    mutable.update {
                        it.copy(
                            confirmation = null,
                            checkedAfterUncertain = false,
                            editorError = e.message,
                            error =
                                if (it.uncertain)
                                    accountText(
                                        "Результат требует проверки. Обновите список. Повтор заблокирован.",
                                        "Result requires review. Refresh the list. Retry is locked.",
                                    )
                                else if (!dispatched && e !is ApiException)
                                    accountText(
                                        "Не удалось сохранить защиту операции. Запрос не отправлен.",
                                        "Could not save the operation guard. Request was not sent.",
                                    )
                                else e.message,
                        )
                    }
                }
                if (e is CancellationException) throw e
                if (e is ApiException && e.code == 401) onAuthError(e)
            } finally {
                if (active(s, token)) mutable.update { it.copy(writing = false) }
            }
        }
    }

    fun acknowledgeCheckedResult() {
        syncScope()
        val data = mutable.value
        if (busy() || data.writing || data.loading || !data.checkedAfterUncertain) return
        if (data.guardUnavailable) return
        val s = data.scope ?: return
        try {
            val guard = guardStore.read(s) ?: return
            guardStore.clear(s, guard.requestId)
        } catch (e: Exception) {
            mutable.update {
                it.copy(
                    error =
                        accountText(
                            "Не удалось снять защиту операции. Повтор заблокирован.",
                            "Could not clear the operation guard. Retry is locked.",
                        )
                )
            }
            return
        }
        mutable.update {
            it.copy(
                uncertain = false,
                pendingDescription = null,
                checkedAfterUncertain = false,
                editor = null,
                editorError = null,
                confirmation = null,
                error = null,
            )
        }
    }
}
