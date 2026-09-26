package work.gadmin.finora.data

import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import work.gadmin.finora.localization.*

enum class BalanceMode {
    SET,
    ADD,
    SUBTRACT,
}

data class WalletEdit(
    val account: Account,
    val mode: BalanceMode = BalanceMode.SET,
    val amount: String = editableMoney(account.balance_minor),
    val countAsIncomeExpense: Boolean = false,
    val date: String = LocalDate.now().toString(),
    val fxRate: String = "",
    val note: String = "",
    val requestKey: String = UUID.randomUUID().toString(),
) {
    fun targetMinor(): Long {
        val text = amount.trim()
        val negative = text.startsWith("-")
        require(!negative || mode == BalanceMode.SET) { tr(Message.AMOUNT_MUST_BE_POSITIVE) }
        val number =
            receiptNumber(if (negative) text.drop(1) else text, true).let {
                if (negative) -it else it
            }
        require(mode == BalanceMode.SET || number.signum() > 0) {
            tr(Message.AMOUNT_MUST_BE_POSITIVE)
        }
        val current = BigDecimal.valueOf(account.balance_minor, 2)
        val target =
            when (mode) {
                BalanceMode.SET -> number
                BalanceMode.ADD -> current + number
                BalanceMode.SUBTRACT -> current - number
            }
        require(target.abs() <= BigDecimal("1000000000")) { tr(Message.THE_AMOUNT_IS_TOO_LARGE) }
        return target.movePointRight(2).longValueExact()
    }

    fun payload(): JsonObject {
        require(!account.archived) { tr(Message.CHOOSE_AN_ACCOUNT) }
        val target = targetMinor()
        require(target != account.balance_minor) { tr(Message.BALANCE_UNCHANGED) }
        require(note.length <= 3000) { tr(Message.THE_NOTE_IS_TOO_LONG) }
        val rate = if (account.currency == "MDL") null else financeRate(fxRate)
        return buildJsonObject {
            put("target_balance", editableMoney(target))
            put("expected_balance_minor", account.balance_minor)
            put("effect", if (countAsIncomeExpense) "income_expense" else "adjustment")
            put("occurred_on", financeDate(date))
            put("fx_rate", rate?.let(::JsonPrimitive) ?: JsonNull)
            put("note", note.trim())
            put("idempotency_key", requestKey)
        }
    }
}

@Serializable
data class BalanceAdjustmentResult(
    val account: Account,
    val previous_balance_minor: Long,
    val adjustment_minor: Long,
)

data class WalletState(
    val editor: WalletEdit? = null,
    val error: String? = null,
    val stale: Boolean = false,
    val refreshing: Boolean = false,
)

class WalletController(
    private val scope: CoroutineScope,
    private val client: () -> ApiClient,
    private val organization: () -> Organization?,
    private val busy: () -> Boolean,
    private val write: (suspend () -> Unit) -> Unit,
    private val onSaved: (Account) -> Unit,
    private val onError: (Exception) -> Unit,
) {
    private val mutable = MutableStateFlow(WalletState())
    val state = mutable.asStateFlow()
    private var refresh: Job? = null

    fun reset() {
        refresh?.cancel()
        mutable.value = WalletState()
    }

    fun open(account: Account) {
        if (busy() || organization()?.isAdmin != true || account.archived) return
        mutable.value = WalletState(editor = WalletEdit(account))
    }

    fun close() {
        if (!busy()) reset()
    }

    fun change(form: WalletEdit) {
        if (busy() || form == mutable.value.editor) return
        mutable.update {
            it.copy(editor = form.copy(requestKey = UUID.randomUUID().toString()), error = null)
        }
    }

    fun reload() {
        val form = mutable.value.editor ?: return
        val org = organization()?.id ?: return
        if (busy() || mutable.value.refreshing) return
        refresh = scope.launch {
            mutable.update { it.copy(refreshing = true) }
            try {
                val account =
                    client().accounts(org).firstOrNull { it.id == form.account.id && !it.archived }
                        ?: throw IllegalArgumentException(tr(Message.CHOOSE_AN_ACCOUNT))
                ensureActive()
                if (organization()?.id == org)
                    mutable.update {
                        it.copy(
                            editor =
                                it.editor?.copy(
                                    account = account,
                                    requestKey = UUID.randomUUID().toString(),
                                ),
                            stale = false,
                            error = null,
                        )
                    }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (error is ApiException && error.code == 401) onError(error)
                else
                    mutable.update {
                        it.copy(
                            error =
                                error.message
                                    ?: tr(
                                        Message
                                            .CANNOT_REACH_THE_SERVER_CHECK_YOUR_CONNECTION_AND_TRY_AGAI
                                    )
                        )
                    }
            } finally {
                if (isActive) mutable.update { it.copy(refreshing = false) }
            }
        }
    }

    fun save() {
        val form = mutable.value.editor ?: return
        if (
            busy() ||
                organization()?.isAdmin != true ||
                mutable.value.refreshing ||
                mutable.value.stale
        )
            return
        val body =
            try {
                form.payload()
            } catch (error: IllegalArgumentException) {
                mutable.update { it.copy(error = error.message) }
                return
            }
        write {
            try {
                val result =
                    client().adjustBalance(requireNotNull(organization()).id, form.account.id, body)
                mutable.value = WalletState()
                onSaved(result.account)
            } catch (error: Exception) {
                if (error is CancellationException || error is ApiException && error.code == 401)
                    throw error
                mutable.update {
                    it.copy(
                        stale = error is ApiException && error.code == 409,
                        error =
                            if (error is ApiException && error.code == 409)
                                tr(Message.BALANCE_CHANGED_REFRESH)
                            else if (error is ApiException) error.message
                            else
                                tr(
                                    Message
                                        .NO_CONFIRMATION_FROM_THE_SERVER_CHECK_YOUR_CONNECTION_AND
                                ),
                    )
                }
            }
        }
    }
}

fun walletTotals(accounts: List<Account>): Map<String, Long> =
    accounts.groupBy(Account::currency).toSortedMap().mapValues { (_, rows) ->
        rows.sumOf(Account::balance_minor)
    }
