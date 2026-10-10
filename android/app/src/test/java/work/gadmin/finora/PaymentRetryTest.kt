package work.gadmin.finora

import java.io.IOException
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import work.gadmin.finora.data.*

class PaymentRetryTest {
    private val row =
        PaymentTemplate(
            "synthetic",
            "Synthetic rent",
            1250,
            "MDL",
            start_date = "2026-10-01",
            recurrence = "monthly",
            active = true,
        )

    @Test
    fun uncertainToggleWithFailedRefreshCannotBeRepeatedBlindly() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var attempts = 0
        val controller =
            PaymentController(
                scope,
                {
                    attempts++
                    throw IOException("Synthetic offline")
                },
                { Organization("synthetic-org", "Synthetic", "admin") },
                { "2026-10" },
                { false },
                { runBlocking { it() } },
                { throw it },
                { fail("Offline cannot report success") },
            )
        try {
            controller.toggle(row)
            assertTrue(controller.state.value.actionUncertain)
            assertNotNull(controller.state.value.error)
            assertEquals(2, attempts)
            controller.toggle(row)
            assertEquals("No blind toggle retry after unknown outcome", 2, attempts)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun memberCannotOpenOrMutatePayments() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val controller =
            PaymentController(
                scope,
                { throw AssertionError("Member must not call API") },
                { Organization("synthetic-org", "Synthetic", "member") },
                { "2026-10" },
                { false },
                { throw AssertionError("Member must not write") },
                { throw it },
                { fail() },
            )
        try {
            controller.open(PaymentEdit(name = "Synthetic"))
            assertNull(controller.state.value.editor)
            controller.toggle(row)
            controller.submit()
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun busyCannotOpenOrMutatePayments() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val controller =
            PaymentController(
                scope,
                { throw AssertionError("Busy must not call API") },
                { Organization("synthetic-org", "Synthetic", "admin") },
                { "2026-10" },
                { true },
                { throw AssertionError("Busy must not write") },
                { throw it },
                { fail() },
            )
        try {
            controller.open(PaymentEdit())
            assertNull(controller.state.value.editor)
            controller.toggle(row)
            controller.submit()
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun authenticationFailureReachesSessionHandlerThroughWrite() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var error: Exception? = null
        val controller =
            PaymentController(
                scope,
                { throw ApiException(401, "Synthetic session expired") },
                { Organization("synthetic-org", "Synthetic", "admin") },
                { "2026-10" },
                { false },
                { block ->
                    try {
                        runBlocking { block() }
                    } catch (e: Exception) {
                        error = e
                    }
                },
                { error = it },
                { fail() },
            )
        try {
            controller.toggle(row)
            assertTrue(error is ApiException)
            assertTrue(controller.state.value.actionUncertain)
        } finally {
            scope.cancel()
        }
    }
}
