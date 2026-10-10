package work.gadmin.finora

import java.io.IOException
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import work.gadmin.finora.data.*

class BudgetRetryTest {
    private fun controller(
        role: String = "admin",
        busy: () -> Boolean = { false },
        failure: Exception = IOException("synthetic offline"),
        test: (BudgetController, () -> Int) -> Unit,
    ) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var attempts = 0
        val controller =
            BudgetController(
                scope,
                client = {
                    attempts++
                    throw failure
                },
                organization = { Organization("synthetic", "Synthetic", role) },
                month = { "2026-10" },
                busy = busy,
                write = { action -> runBlocking { action() } },
                onError = { throw it },
                onSaved = { fail("Offline operation must not claim success") },
            )
        try {
            test(controller) { attempts }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun lostResponseLocksPayloadUntilSafeRetry() = controller { c, _ ->
        c.open(BudgetCategory("food", "Food"))
        c.edit("12.34")
        c.save()
        assertTrue(c.state.value.retryLocked)
        c.edit("99.99")
        assertEquals("12.34", c.state.value.editor!!.amount)
        val original = c.state.value.editor!!.command()
        c.save()
        assertEquals(original, c.state.value.editor!!.command())
    }

    @Test
    fun readOnlyCannotOpenOrWrite() =
        controller(role = "member") { c, attempts ->
            c.open(BudgetCategory("food", "Food"))
            c.edit("12.34")
            c.save()
            c.requestCopy()
            assertNull(c.state.value.editor)
            assertFalse(c.state.value.copyOpen)
            assertEquals(0, attempts())
        }

    @Test
    fun globalBusyBlocksWritesAndDraftDiscard() {
        var busy = false
        controller(busy = { busy }) { c, attempts ->
            c.open(BudgetCategory("food", "Food"))
            c.edit("12.34")
            busy = true
            c.save()
            c.closeEditor()
            c.edit("99")
            assertNotNull(c.state.value.editor)
            assertEquals("12.34", c.state.value.editor!!.amount)
            assertEquals(0, attempts())
        }
    }

    @Test
    fun explicitValidationFailureAllowsCorrection() =
        controller(failure = ApiException(422, "Invalid limit")) { c, _ ->
            c.open(BudgetCategory("food", "Food"))
            c.edit("12.34")
            c.save()
            assertFalse(c.state.value.retryLocked)
            c.edit("13.00")
            assertEquals("13.00", c.state.value.editor!!.amount)
        }
}
