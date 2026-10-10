package work.gadmin.finora.data

import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class LedgerControllerTest {
    private class DurableFake(val disk: MutableMap<String, String> = mutableMapOf()) :
        LedgerPendingStore {
        var failSave = false

        override fun read(owner: String, organization: String): LedgerPendingMutation? =
            disk[owner + ":" + organization]?.let {
                Json.decodeFromString<LedgerPendingMutation>(it)
            }

        override fun save(pending: LedgerPendingMutation) {
            check(!failSave) { "disk failure" }
            require(read(pending.owner, pending.organization).let { it == null || it == pending })
            disk[pending.owner + ":" + pending.organization] = Json.encodeToString(pending)
        }

        override fun clear(pending: LedgerPendingMutation) {
            require(read(pending.owner, pending.organization) == pending)
            disk.remove(pending.owner + ":" + pending.organization)
        }
    }

    private class FakeApi : LedgerApi {
        var pages = listOf(LedgerHistory(listOf(tx("expense"), tx("transfer")), 2))
        var failure: Exception? = null
        var readFailure: Exception? = null
        val writes = mutableListOf<Pair<String, FinanceCommand>>()
        val reads = mutableListOf<List<Any?>>()
        var suspended: CompletableDeferred<Unit>? = null

        override suspend fun history(
            org: String,
            month: String,
            offset: Int,
            kind: String?,
            account: String?,
        ): LedgerHistory {
            reads += listOf(org, month, offset, kind, account)
            suspended?.await()
            readFailure?.let { throw it }
            return pages[if (offset > 0) 1 else 0]
        }

        override suspend fun accounts(org: String) =
            listOf(Account("a", "Cash", "MDL"), Account("b", "Bank", "MDL"))

        override suspend fun categories(org: String) = emptyList<Category>()

        override suspend fun write(org: String, command: FinanceCommand) {
            writes += org to command
            failure?.let { throw it }
        }
    }

    private class Fixture {
        val job = SupervisorJob()
        val scope = CoroutineScope(job + Dispatchers.Unconfined)
        var org = Organization("org", "Family", "admin")
        var month = "2026-01"
        val api = FakeApi()
        val store = DurableFake()
        val errors = mutableListOf<Exception>()
        val controller =
            LedgerController(
                scope,
                { error("Fake adapter only") },
                { org },
                { month },
                { false },
                { action ->
                    scope.launch { action() }
                    Unit
                },
                { errors += it },
                {},
                api,
                store,
                { "server|user" },
            )

        fun open() {
            controller.load()
            controller.open(LedgerEdit(accountId = "a", amount = "1.23", date = "2026-01-01"))
        }
    }

    @Test
    fun generalHistoryHasNoIncomeFilterAndConfirmationPrecedesWrite() {
        val f = Fixture()
        try {
            f.open()
            assertNull(f.api.reads.first()[3])
            assertEquals(2, f.controller.state.value.history.size)
            f.controller.submit()
            assertTrue(f.api.writes.isEmpty())
            assertNotNull(f.controller.state.value.confirmation)
            f.controller.confirm()
            assertEquals(1, f.api.writes.size)
            assertEquals("org", f.api.writes.single().first)
            assertNull(f.controller.state.value.editor)
        } finally {
            f.job.cancel()
        }
    }

    @Test
    fun uncertainWriteLocksRetryAndPreservesRequestKey() {
        val f = Fixture()
        try {
            f.open()
            val key = f.controller.state.value.editor!!.requestKey
            f.api.failure = IOException("lost response")
            f.controller.submit()
            f.controller.confirm()
            assertTrue(f.controller.state.value.editorRetryLocked)
            assertEquals(key, f.controller.state.value.editor!!.requestKey)
            f.controller.submit()
            f.controller.confirm()
            assertEquals(1, f.api.writes.size)
            f.controller.edit(f.controller.state.value.editor!!.copy(amount = "2"))
            assertEquals("1.23", f.controller.state.value.editor!!.amount)
        } finally {
            f.job.cancel()
        }
    }

    @Test
    fun validationErrorCanRetryWithSameKey() {
        val f = Fixture()
        try {
            f.open()
            val key = f.controller.state.value.editor!!.requestKey
            f.api.failure = ApiException(422, "invalid")
            f.controller.submit()
            f.controller.confirm()
            assertFalse(f.controller.state.value.editorRetryLocked)
            f.api.failure = null
            f.controller.submit()
            f.controller.confirm()
            assertEquals(2, f.api.writes.size)
            assertEquals(
                key,
                f.api.writes[1].second.body!!["idempotency_key"]!!.toString().trim('"'),
            )
        } finally {
            f.job.cancel()
        }
    }

    @Test
    fun organizationChangeRejectsPreparedMutationAndStaleReads() = runBlocking {
        val f = Fixture()
        try {
            f.open()
            f.controller.submit()
            f.org = f.org.copy(id = "other")
            f.controller.confirm()
            assertTrue(f.api.writes.isEmpty())
            f.api.suspended = CompletableDeferred()
            f.controller.load()
            f.org = f.org.copy(id = "third")
            f.api.suspended!!.complete(Unit)
            yield()
            assertTrue(f.controller.state.value.history.isEmpty())
        } finally {
            f.job.cancel()
        }
    }

    @Test
    fun voidRequiresConfirmationAndVersionAndRefreshesAfterUncertainResponse() {
        val f = Fixture()
        try {
            f.controller.load()
            f.controller.void(tx("expense"))
            assertTrue(f.api.writes.isEmpty())
            f.api.failure = IOException("timeout")
            f.controller.confirm()
            assertEquals("DELETE", f.api.writes.single().second.method)
            assertEquals("/api/transactions/expense?version=4", f.api.writes.single().second.path)
            assertNull(f.controller.state.value.voiding)
            assertEquals(2, f.api.reads.size)
        } finally {
            f.job.cancel()
        }
    }

    @Test
    fun paginationDeduplicatesAndPreservesFilters() {
        val f = Fixture()
        try {
            f.api.pages =
                listOf(
                    LedgerHistory(listOf(tx("expense")), 3),
                    LedgerHistory(listOf(tx("expense"), tx("transfer")), 3),
                )
            f.controller.load()
            f.controller.filter("transfer", "a")
            f.controller.moreHistory()
            assertEquals(2, f.controller.state.value.history.size)
            assertEquals(listOf("org", "2026-01", 1, "transfer", "a"), f.api.reads.last())
            val count = f.api.reads.size
            f.controller.moreHistory()
            assertEquals(count, f.api.reads.size)
        } finally {
            f.job.cancel()
        }
    }

    @Test
    fun readFailureShowsRetryAnd401GoesToSessionHandler() {
        val f = Fixture()
        try {
            f.api.readFailure = IOException()
            f.controller.load()
            assertNotNull(f.controller.state.value.error)
            assertFalse(f.controller.state.value.loading)
            f.api.readFailure = null
            f.controller.load()
            assertNull(f.controller.state.value.error)
            f.api.readFailure = ApiException(401, "unauthorized")
            f.controller.load()
            assertEquals(1, f.errors.size)
        } finally {
            f.job.cancel()
        }
    }

    @Test
    fun nonAdminCannotOpenOrVoid() {
        val f = Fixture()
        try {
            f.org = f.org.copy(role = "member")
            f.controller.load()
            f.controller.open()
            f.controller.void(tx("expense"))
            f.controller.confirm()
            assertNull(f.controller.state.value.editor)
            assertNull(f.controller.state.value.voiding)
            assertTrue(f.api.writes.isEmpty())
        } finally {
            f.job.cancel()
        }
    }

    @Test
    fun roleChangeWhileWaitingForWriteLockRejectsMutation() {
        val job = SupervisorJob()
        val scope = CoroutineScope(job + Dispatchers.Unconfined)
        val api = FakeApi()
        var org = Organization("org", "Family", "admin")
        var queued: (suspend () -> Unit)? = null
        val controller =
            LedgerController(
                scope,
                { error("fake") },
                { org },
                { "2026-01" },
                { false },
                { queued = it },
                {},
                {},
                api,
                DurableFake(),
                { "server|user" },
            )
        try {
            controller.load()
            controller.open(LedgerEdit(accountId = "a", amount = "1", date = "2026-01-01"))
            controller.submit()
            controller.confirm()
            org = org.copy(role = "member")
            runBlocking { queued!!() }
            assertTrue(api.writes.isEmpty())
        } finally {
            job.cancel()
        }
    }

    @Test
    fun timeoutAndServerFailureNeverBlindlyRetry() {
        listOf(408, 500, 503).forEach { status ->
            val f = Fixture()
            try {
                f.open()
                f.api.failure = ApiException(status, "uncertain")
                f.controller.submit()
                f.controller.confirm()
                assertTrue(f.controller.state.value.editorRetryLocked)
                f.controller.submit()
                f.controller.confirm()
                assertEquals(1, f.api.writes.size)
            } finally {
                f.job.cancel()
            }
        }
    }

    @Test
    fun timeoutCloseReopenUsesSameImmutablePayloadAndKey() {
        val f = Fixture()
        try {
            f.open()
            f.api.failure = IOException("lost")
            f.controller.submit()
            f.controller.confirm()
            val first = f.api.writes.single().second
            f.controller.closeEditor()
            f.controller.open(LedgerEdit(accountId = "a", amount = "999", date = "2026-01-01"))
            assertEquals("1.23", f.controller.state.value.editor!!.amount)
            f.controller.edit(f.controller.state.value.editor!!.copy(amount = "999"))
            assertEquals("1.23", f.controller.state.value.editor!!.amount)
            f.api.failure = null
            f.controller.retryPending()
            f.controller.confirm()
            assertEquals(first, f.api.writes.last().second)
            assertNull(f.store.read("server|user", "org"))
        } finally {
            f.job.cancel()
        }
    }

    @Test
    fun processRecreationRestoresSerializedPendingAndReusesKey() {
        val f = Fixture()
        try {
            f.open()
            f.api.failure = IOException("lost")
            f.controller.submit()
            f.controller.confirm()
            val first = f.api.writes.single().second
            val recreatedStore = DurableFake(f.store.disk)
            val recreated =
                LedgerController(
                    f.scope,
                    { error("fake") },
                    { f.org },
                    { f.month },
                    { false },
                    { action ->
                        f.scope.launch { action() }
                        Unit
                    },
                    {},
                    {},
                    f.api,
                    recreatedStore,
                    { "server|user" },
                )
            recreated.load()
            recreated.open()
            assertEquals(first.body, recreated.state.value.pending!!.body)
            assertThrows(IllegalArgumentException::class.java) {
                recreatedStore.save(
                    recreated.state.value.pending!!.copy(
                        body =
                            kotlinx.serialization.json.buildJsonObject {
                                put("amount", kotlinx.serialization.json.JsonPrimitive("999"))
                            }
                    )
                )
            }
            f.api.failure = null
            recreated.retryPending()
            recreated.confirm()
            assertEquals(first, f.api.writes.last().second)
        } finally {
            f.job.cancel()
        }
    }

    @Test
    fun persistenceFailureBlocksPostAndOwnerScopeHidesOtherPending() {
        val f = Fixture()
        try {
            f.open()
            f.store.failSave = true
            f.controller.submit()
            f.controller.confirm()
            assertTrue(f.api.writes.isEmpty())
            f.store.failSave = false
            f.controller.closeEditor()
            f.open()
            f.api.failure = IOException("lost")
            f.controller.submit()
            f.controller.confirm()
            assertNotNull(f.store.read("server|user", "org"))
            assertNull(f.store.read("other-user", "org"))
            assertNull(f.store.read("server|user", "other-org"))
        } finally {
            f.job.cancel()
        }
    }

    @Test
    fun lateForbiddenCannotForgetAnUncertainPayment() {
        val f = Fixture()
        try {
            f.open()
            f.api.failure = IOException("lost response after server commit")
            f.controller.submit()
            f.controller.confirm()
            val original = requireNotNull(f.store.read("server|user", "org"))
            f.api.failure = ApiException(403, "temporary membership restriction")
            f.controller.retryPending()
            f.controller.confirm()
            assertEquals(original, f.store.read("server|user", "org"))
            assertTrue(f.controller.state.value.editorRetryLocked)
            f.controller.closeEditor()
            f.controller.open(LedgerEdit(accountId = "a", amount = "99", date = "2026-01-01"))
            assertEquals(original.form, f.controller.state.value.editor)
            f.api.failure = null
            f.controller.retryPending()
            f.controller.confirm()
            assertEquals(original.command(), f.api.writes.last().second)
            assertNull(f.store.read("server|user", "org"))
        } finally {
            f.job.cancel()
        }
    }

    companion object {
        private fun tx(id: String) = LedgerTransaction(id, id, 123, "MDL", "a", "2026-01-01", 4)
    }
}
