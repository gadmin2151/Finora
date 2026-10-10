package work.gadmin.finora

import java.io.IOException
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import work.gadmin.finora.data.*

class FinanceRetryTest {
    private fun form() =
        FinanceEdit(
            FinanceEditKind.DEBT,
            name = "Synthetic person",
            amount = "12.34",
            mode = "existing",
        )

    private fun withController(errors: List<Exception>, test: (FinanceController) -> Unit) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val failures = ArrayDeque(errors)
        val controller =
            FinanceController(
                scope,
                client = { throw failures.removeFirst() },
                organization = { Organization("synthetic-org", "Synthetic", "admin") },
                month = { "2026-10" },
                busy = { false },
                write = { block -> runBlocking { block() } },
                onError = { throw it },
                onSaved = { fail("A failed request must not show success") },
            )
        try {
            test(controller)
            assertTrue("Every simulated attempt must be consumed", failures.isEmpty())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun lostResponseKeepsExactFieldsAndKeyForRetry() =
        withController(
            listOf(IOException("Synthetic lost response"), ApiException(409, "Conflict"))
        ) { controller ->
            val original = form()
            controller.open(original)
            controller.submit()
            assertTrue(controller.state.value.editorRetryLocked)
            controller.edit(original.copy(amount = "99.00", note = "Changed after response loss"))
            assertEquals(original, controller.state.value.editor)
            controller.submit()
            assertTrue(controller.state.value.editorRetryLocked)
            assertEquals(
                original.command(emptyList()),
                controller.state.value.editor!!.command(emptyList()),
            )
            assertEquals("Conflict", controller.state.value.editorError)
        }

    @Test
    fun gatewayAndRequestTimeoutsKeepTheSameRequest() {
        for (code in listOf(408, 500, 502, 504)) withController(
            listOf(ApiException(code, "Synthetic timeout"))
        ) { controller ->
            val original = form()
            controller.open(original)
            controller.submit()
            assertTrue(controller.state.value.editorRetryLocked)
            controller.edit(original.copy(direction = "borrowed"))
            assertEquals(original, controller.state.value.editor)
        }
    }

    @Test
    fun explicitRejectionAllowsCorrectingInputs() =
        withController(listOf(ApiException(400, "Check amount"))) { controller ->
            val original = form()
            controller.open(original)
            controller.submit()
            assertFalse(controller.state.value.editorRetryLocked)
            controller.edit(original.copy(amount = "15.00"))
            assertEquals("15.00", controller.state.value.editor!!.amount)
            assertEquals(original.requestKey, controller.state.value.editor!!.requestKey)
        }

    @Test
    fun closingClearsOnlyTheLocalRetryState() =
        withController(listOf(IOException("Synthetic lost response"))) { controller ->
            val original = form()
            controller.open(original)
            controller.submit()
            controller.closeEditor()
            assertNull(controller.state.value.editor)
            assertFalse(controller.state.value.editorRetryLocked)
            val next = form()
            controller.open(next)
            assertNotEquals(original.requestKey, controller.state.value.editor!!.requestKey)
        }
}
