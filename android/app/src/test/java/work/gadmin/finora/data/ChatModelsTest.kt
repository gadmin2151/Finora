package work.gadmin.finora.data

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class ChatModelsTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun legacyMessagesAndNewReportsDecodeTogether() {
        val messages =
            json.decodeFromString<List<ChatMessage>>(
                """[
            {"id":"old","role":"user","text":"Hello","created_at":"2025-02-01T10:00:00Z","actor_id":null,"details":{}},
            {"id":"new","role":"assistant","text":"Ready","created_at":"2025-02-01T10:00:01Z","details":{"provider":"reports","reports":[{"query":{"kind":"summary","date_from":"2025-02-01","date_to":"2025-02-28"},"title":"Summary","metrics":[{"label":"Expenses","value":"25.00 MDL"}],"rows":[{"label":"Milk","value":"25.00 MDL","receipt_id":"receipt"}],"total_rows":1,"notices":["Verified"]}]}}
        ]"""
            )
        assertTrue(messages.first().details.reports.isEmpty())
        val report = messages.last().details.reports.single()
        assertEquals("25.00 MDL", report.metrics.single().value)
        assertEquals("receipt", report.rows.single().receipt_id)
    }

    @Test
    fun pollingAndPaginationDoNotDuplicateMessagesAndKeepUpdatedData() {
        val first = ChatMessage("a", "assistant", "First", "2025-02-01T10:00:00Z")
        val next = first.copy(id = "b", text = "Next")
        val merged = mergeChatMessages(listOf(next, first), listOf(next.copy(text = "Updated")))
        assertEquals(listOf("a", "b"), merged.map { it.id })
        assertEquals("Updated", merged.last().text)
    }

    @Test
    fun receiptJobsDoNotDisableAssistantComposer() {
        assertFalse(ChatJob("r", "receipt", "running", "Reading").isPending)
        assertTrue(ChatJob("c", "chat", "running", "Reading").isPending)
        assertFalse(ChatJob("c", "chat", "failed", "Retry").isPending)
    }
}
