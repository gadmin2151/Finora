package work.gadmin.finora.data

import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class DebtRecordedTimeTest {
    private fun entry(timestamp: String) =
        DebtHistoryEntry("id", "debt_repayment_in", "repayment", "2026-01-01", timestamp, 100, 0)

    @Test
    fun utcAndLegacyTimestampsUseAccountingTimezoneIncludingDaylightSavings() {
        assertEquals(13, entry("2026-10-12T10:00:00Z").recordedAt()!!.hour)
        assertEquals(13, entry("2026-10-12T10:00:00").recordedAt()!!.hour)
        assertEquals(12, entry("2026-01-12T10:00:00+00:00").recordedAt()!!.hour)
        assertEquals(
            ZoneId.of("Europe/Chisinau"),
            entry("2026-10-12T10:00:00Z").recordedAt()!!.zone,
        )
    }

    @Test
    fun recordingDateRemainsSeparateFromBackdatedTransactionDate() {
        val row = entry("2026-10-12T22:30:00Z")
        assertEquals("2026-10-13", row.recordedAt()!!.toLocalDate().toString())
        assertEquals("2026-01-01", row.occurred_on)
        assertEquals(1, row.recordedAt()!!.hour)
    }

    @Test
    fun invalidOrMissingTimeIsNeverInvented() {
        for (raw in listOf("", "2026-10-12", "invalid")) assertNull(entry(raw).recordedAt())
    }
}
