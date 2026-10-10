package work.gadmin.finora.data

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class DashboardCompatibilityTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun olderDashboardWithoutBillRowsStillLoads() {
        val dashboard =
            json.decodeFromString<Dashboard>(
                """{"month":"2026-10","income_minor":0,"expense_minor":0,"net_minor":0}"""
            )
        assertTrue(dashboard.bills.isEmpty())
        assertEquals(0L, dashboard.planned_remaining_minor)
    }

    @Test
    fun productionBillRowsPreserveCurrenciesAndIgnoreAdditionalFields() {
        val dashboard =
            json.decodeFromString<Dashboard>(
                """{"month":"2026-10","income_minor":0,"expense_minor":0,"net_minor":0,"planned_remaining_minor":3900,"bills":[{"id":"synthetic","name":"Synthetic bill","amount_minor":200,"currency":"EUR","due_date":"2026-10-09","status":"overdue","fx_rate":"19.50","transaction_id":null}]}"""
            )
        assertEquals("EUR", dashboard.bills.single().currency)
        assertEquals(200L, dashboard.bills.single().amount_minor)
        assertEquals(3900L, dashboard.planned_remaining_minor)
    }
}
