package work.gadmin.finora.data

import kotlinx.serialization.Serializable

@Serializable
data class ReportQuery(
    val kind: String,
    val date_from: String,
    val date_to: String,
    val search: String = "",
    val merchant: String = "",
    val category_id: String = "",
    val currency: String? = null,
)

@Serializable data class ReportMetric(val label: String, val value: String)

@Serializable
data class ReportRow(
    val label: String,
    val value: String,
    val detail: String = "",
    val receipt_id: String? = null,
)

@Serializable
data class AnalyticsReport(
    val query: ReportQuery,
    val title: String,
    val metrics: List<ReportMetric> = emptyList(),
    val rows: List<ReportRow> = emptyList(),
    val total_rows: Int = 0,
    val notices: List<String> = emptyList(),
)

@Serializable
data class ChatDetails(
    val provider: String? = null,
    val error: Boolean = false,
    val month: String? = null,
    val job_id: String? = null,
    val reports: List<AnalyticsReport> = emptyList(),
)

@Serializable
data class ChatMessage(
    val id: String,
    val role: String,
    val text: String,
    val created_at: String,
    val receipt_id: String? = null,
    val details: ChatDetails = ChatDetails(),
)

@Serializable
data class ChatJob(val id: String, val kind: String, val status: String, val progress: String) {
    val isPending: Boolean
        get() = kind in setOf("chat", "analysis") && status in setOf("queued", "running")
}

@Serializable data class ChatResult(val job_id: String)

fun mergeChatMessages(previous: List<ChatMessage>, latest: List<ChatMessage>): List<ChatMessage> =
    (previous + latest)
        .associateBy(ChatMessage::id)
        .values
        .sortedWith(compareBy(ChatMessage::created_at, ChatMessage::id))
        .takeLast(600)
