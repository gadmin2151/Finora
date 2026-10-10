package work.gadmin.finora

import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import work.gadmin.finora.accounts.*
import work.gadmin.finora.data.*

class PaymentPersistenceTest {
    private val owner =
        AccountManagementScope(
            "synthetic-org",
            true,
            false,
            null,
            "synthetic-owner",
            "https://synthetic.invalid",
        )
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
    fun failureToPersistPreventsEveryHttpAttempt() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var apiAttempts = 0
        val store =
            object : AccountMutationGuardStore {
                override fun read(scope: AccountManagementScope): AccountMutationGuard? = null

                override fun put(scope: AccountManagementScope, guard: AccountMutationGuard) {
                    throw IOException("Synthetic disk full")
                }

                override fun clear(scope: AccountManagementScope, requestId: String) {
                    fail("Nothing was persisted")
                }
            }
        val actions =
            PaymentController(
                scope,
                {
                    apiAttempts++
                    throw IOException("Must not reach HTTP")
                },
                { Organization(owner.organizationId, "Synthetic", "admin") },
                { "2026-10" },
                { false },
                { runBlocking { it() } },
                { throw it },
                { fail() },
                store,
                { owner },
            )
        try {
            actions.toggle(row)
            assertEquals(0, apiAttempts)
            assertTrue(actions.state.value.actionUncertain)
            assertFalse(actions.state.value.checkedAfterUncertain)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun persistedGuardSurvivesControllerRecreationAndOfflineLoad() {
        val directory = Files.createTempDirectory("synthetic-payment-guard-").toFile()
        val store = FileAccountMutationGuardStore(directory)
        val command = FinanceCommand("/api/bills/synthetic/toggle", "POST")
        val guard = AccountMutationGuard.create(owner, command, "Synthetic action")
        store.put(owner, guard)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var attempts = 0
        val actions =
            PaymentController(
                scope,
                {
                    attempts++
                    throw IOException("Synthetic offline")
                },
                { Organization(owner.organizationId, "Synthetic", "admin") },
                { "2026-10" },
                { false },
                { runBlocking { it() } },
                { throw it },
                { fail() },
                store,
                { owner },
            )
        try {
            actions.load()
            assertTrue(actions.state.value.actionUncertain)
            assertFalse(actions.state.value.checkedAfterUncertain)
            actions.toggle(row)
            actions.open(PaymentEdit())
            actions.acknowledgeCheckedResult()
            assertEquals(1, attempts)
            assertNull(actions.state.value.editor)
            assertEquals(guard, store.read(owner))
        } finally {
            scope.cancel()
            directory.deleteRecursively()
        }
    }

    @Test
    fun lostResponseThenLateForbiddenCannotClearPriorGuard() {
        assertTrue(paymentGuardNeedsReview(false, true, IOException("Synthetic lost response")))
        for (code in listOf(400, 403, 409, 422)) assertTrue(
            "Prior unknown outcome must survive late $code",
            paymentGuardNeedsReview(true, true, ApiException(code, "Synthetic late rejection")),
        )
        assertFalse(
            paymentGuardNeedsReview(false, true, ApiException(422, "First definitive rejection"))
        )
    }

    @Test
    fun corruptPersistedMetadataBlocksLoadBeforeHttp() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var attempts = 0
        val store =
            object : AccountMutationGuardStore {
                override fun read(scope: AccountManagementScope): AccountMutationGuard? {
                    throw IllegalArgumentException("Synthetic corrupt guard")
                }

                override fun put(scope: AccountManagementScope, guard: AccountMutationGuard) {
                    fail()
                }

                override fun clear(scope: AccountManagementScope, requestId: String) {
                    fail()
                }
            }
        val actions =
            PaymentController(
                scope,
                {
                    attempts++
                    throw IOException("Must not reach HTTP")
                },
                { Organization(owner.organizationId, "Synthetic", "admin") },
                { "2026-10" },
                { false },
                { runBlocking { it() } },
                { throw it },
                { fail() },
                store,
                { owner },
            )
        try {
            actions.load()
            actions.toggle(row)
            assertEquals(0, attempts)
            assertTrue(actions.state.value.actionUncertain)
        } finally {
            scope.cancel()
        }
    }
}
