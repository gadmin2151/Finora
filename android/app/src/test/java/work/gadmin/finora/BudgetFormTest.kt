package work.gadmin.finora

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import work.gadmin.finora.data.*

class BudgetFormTest {
    private val category = BudgetCategory("food", "Fictional food", budget_id = "limit-id")

    @Test
    fun exactCentsAndSafeRepeat() {
        val form = BudgetEdit("2026-10", category, "12,50")
        assertEquals(form.command(), form.command())
        assertEquals("PUT", form.command().method)
        assertEquals("12.50", form.command().body!!["amount"]!!.jsonPrimitive.content)
        assertEquals("food", form.command().body!!["category_id"]!!.jsonPrimitive.content)
        assertFalse(form.command().body!!.containsKey("idempotency_key"))
    }

    @Test
    fun refusesInvalidOrOutOfRangeLimits() {
        listOf("0", "-1", "1.001", "1000000000.01", "NaN", "").forEach {
            assertThrows(IllegalArgumentException::class.java) {
                BudgetEdit("2026-10", category, it).command()
            }
        }
    }

    @Test
    fun copyRequiresReviewedMonthAndFingerprint() {
        val preview =
            BudgetCopyPreview(
                "2026-10",
                "2026-09",
                "a".repeat(64),
                listOf(BudgetCopyItem("food", "Food", 1250)),
            )
        assertEquals("/api/budgets/copy", preview.command("2026-10").path)
        assertEquals(
            "a".repeat(64),
            preview.command("2026-10").body!!["fingerprint"]!!.jsonPrimitive.content,
        )
        assertThrows(IllegalArgumentException::class.java) { preview.command("2026-11") }
        assertThrows(IllegalArgumentException::class.java) {
            preview.copy(fingerprint = "bad").command("2026-10")
        }
        assertThrows(IllegalArgumentException::class.java) {
            preview.copy(items = emptyList()).command("2026-10")
        }
    }

    @Test
    fun virtualUncategorizedAndOutOfRangeMonthCannotCreateLimits() {
        assertThrows(IllegalArgumentException::class.java) {
            BudgetEdit("2026-10", category.copy(id = "uncategorized"), "1").command()
        }
        assertThrows(IllegalArgumentException::class.java) {
            BudgetEdit("1989-12", category, "1").command()
        }
    }

    @Test
    fun dashboardKeepsBudgetIdSeparateFromCategory() {
        val json = Json { ignoreUnknownKeys = true }
        val data =
            json.decodeFromString<BudgetDashboard>(
                """{"month":"2026-10","income_minor":12,"categories":[{"id":"category","name":"Food","budget_id":"limit","budget_minor":1250}]}"""
            )
        assertEquals("category", data.categories.single().id)
        assertEquals("limit", data.categories.single().budget_id)
    }
}
