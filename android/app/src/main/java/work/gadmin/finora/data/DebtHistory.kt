package work.gadmin.finora.data

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlinx.serialization.Serializable

@Serializable
data class DebtHistoryEntry(
    val id: String,
    val kind: String,
    val operation: String,
    val occurred_on: String,
    val created_at: String,
    val amount_minor: Long,
    val balance_after_minor: Long,
    val account_id: String? = null,
    val account_name: String? = null,
    val note: String = "",
    val voided: Boolean = false,
) {
    /** Server timestamps are UTC; legacy SQLite responses omit the offset. */
    fun recordedAt(zone: ZoneId = accountingZone): ZonedDateTime? = runCatching {
        Instant.parse(created_at)
    }
        .recoverCatching { LocalDateTime.parse(created_at).toInstant(ZoneOffset.UTC) }
        .getOrNull()
        ?.atZone(zone)
}

@Serializable
data class DebtHistory(
    val debt_id: String,
    val currency: String,
    val initial_minor: Long,
    val remaining_minor: Long,
    val increased_minor: Long,
    val repaid_minor: Long,
    val items: List<DebtHistoryEntry>,
    val total: Int,
) {
    fun sameSummary(other: DebtHistory): Boolean =
        debt_id == other.debt_id &&
            currency == other.currency &&
            initial_minor == other.initial_minor &&
            remaining_minor == other.remaining_minor &&
            increased_minor == other.increased_minor &&
            repaid_minor == other.repaid_minor &&
            total == other.total
}

fun interface DebtHistoryApi {
    suspend fun history(organizationId: String, debtId: String, offset: Int): DebtHistory
}
