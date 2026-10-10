package work.gadmin.finora.accounts

import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test
import work.gadmin.finora.data.ApiException
import work.gadmin.finora.data.FinanceCommand

class AccountManagementTest {
    private val row =
        ManagedAccount(
            "01234567-89ab-cdef-0123-456789abcdef",
            "Savings",
            "EUR",
            "savings",
            "#abcdef",
            12345,
            99000,
            false,
        )

    private class Fake(var rows: List<ManagedAccount>) : AccountManagementApi {
        var writes = 0
        var failure: Exception? = null
        var listFailure: Exception? = null

        override suspend fun list(organizationId: String): List<ManagedAccount> {
            listFailure?.let { throw it }
            return rows
        }

        override suspend fun write(organizationId: String, command: FinanceCommand) {
            writes++
            failure?.let { throw it }
        }
    }

    private class MemoryGuard : AccountMutationGuardStore {
        val guards = mutableMapOf<String, AccountMutationGuard>()
        var failPut = false

        private fun key(s: AccountManagementScope) =
            "${s.serverOrigin}/${s.ownerId}/${s.organizationId}"

        override fun read(scope: AccountManagementScope) = guards[key(scope)]

        override fun put(scope: AccountManagementScope, guard: AccountMutationGuard) {
            if (failPut) throw IOException("disk full")
            check(read(scope) == null)
            guards[key(scope)] = guard
        }

        override fun clear(scope: AccountManagementScope, requestId: String) {
            check(read(scope)?.requestId == requestId)
            guards.remove(key(scope))
        }
    }

    private inner class Fixture(val store: AccountMutationGuardStore = MemoryGuard()) {
        val api = Fake(listOf(row))
        var selected =
            AccountManagementScope("org-a", true, false, row.id, "owner-a", "https://example.test")
        var pending: (suspend () -> Unit)? = null
        var saved = 0
        val controller =
            AccountManagementController(
                CoroutineScope(Dispatchers.Unconfined),
                api,
                { selected },
                { false },
                { pending = it },
                {},
                { saved++ },
                store,
            )

        init {
            controller.load()
        }

        fun prepareEdit() {
            controller.open(row)
            controller.prepare()
        }

        suspend fun runWrite() {
            pending!!.invoke()
            pending = null
        }
    }

    @Test
    fun preservesOpeningAndAllMetadata() {
        val command = ManagedAccountForm.from(row).copy(name = "Renamed").command()
        assertEquals("123.45", command.body!!["opening_balance"]!!.jsonPrimitive.content)
        assertEquals("savings", command.body["kind"]!!.jsonPrimitive.content)
        assertEquals("#abcdef", command.body["color"]!!.jsonPrimitive.content)
        assertEquals("EUR", command.body["currency"]!!.jsonPrimitive.content)
        assertEquals("PUT", command.method)
    }

    @Test
    fun validatesDecimalRangeAndNoExponent() {
        listOf("0.001", "1e3", "NaN", "1000000000.01", "").forEach { value ->
            assertThrows(IllegalArgumentException::class.java) {
                ManagedAccountForm(name = "Cash", openingBalance = value).command()
            }
        }
        assertEquals(
            "-12.50",
            ManagedAccountForm(name = "Cash", openingBalance = "-12,50")
                .command()
                .body!!["opening_balance"]!!
                .jsonPrimitive
                .content,
        )
    }

    @Test
    fun rejectsInvalidNameColorKindAndCurrency() {
        listOf(
                ManagedAccountForm(name = " "),
                ManagedAccountForm(name = "a", color = "red"),
                ManagedAccountForm(name = "a", kind = "loan"),
                ManagedAccountForm(name = "a", currency = "GBP"),
            )
            .forEach {
                assertThrows(IllegalArgumentException::class.java) { it.command() }
            }
    }

    @Test
    fun doubleConfirmQueuesOneWrite() = runBlocking {
        val f = Fixture()
        f.prepareEdit()
        f.controller.confirm()
        val first = f.pending
        f.controller.confirm()
        assertSame(first, f.pending)
        f.runWrite()
        assertEquals(1, f.api.writes)
        assertEquals(1, f.saved)
    }

    @Test
    fun archiveRequiresExplicitConfirmation() = runBlocking {
        val f = Fixture()
        f.controller.archive(row)
        assertEquals(0, f.api.writes)
        assertTrue(f.controller.state.value.confirmation!!.description.contains("Savings"))
        f.controller.cancelConfirmation()
        f.controller.confirm()
        assertNull(f.pending)
        f.controller.archive(row)
        f.controller.confirm()
        f.runWrite()
        assertEquals(1, f.api.writes)
    }

    @Test
    fun readOnlyCannotQueueWrites() {
        val f = Fixture()
        f.selected = f.selected.copy(admin = false)
        f.controller.load()
        f.controller.open()
        f.controller.archive(row)
        f.controller.confirm()
        assertNull(f.controller.state.value.editor)
        assertNull(f.pending)
    }

    @Test
    fun scopeSwitchInvalidatesConfirmationBeforeDispatch() = runBlocking {
        val f = Fixture()
        f.prepareEdit()
        f.controller.confirm()
        f.selected = f.selected.copy(organizationId = "org-b")
        f.controller.load()
        f.runWrite()
        assertEquals(0, f.api.writes)
        assertEquals("org-b", f.controller.state.value.scope!!.organizationId)
    }

    @Test
    fun roleRevocationInvalidatesQueuedWrite() = runBlocking {
        val f = Fixture()
        f.prepareEdit()
        f.controller.confirm()
        f.selected = f.selected.copy(admin = false)
        f.runWrite()
        assertEquals(0, f.api.writes)
    }

    @Test
    fun uncertaintyBlocksUntilSuccessfulReadAndAcknowledgement() = runBlocking {
        val f = Fixture()
        f.api.failure = IOException("lost response")
        f.prepareEdit()
        f.controller.confirm()
        f.runWrite()
        assertTrue(f.controller.state.value.uncertain)
        assertEquals(0, f.saved)
        f.controller.confirm()
        assertNull(f.pending)
        f.controller.acknowledgeCheckedResult()
        assertTrue(f.controller.state.value.uncertain)
        f.api.listFailure = IOException("offline")
        f.controller.load()
        f.controller.acknowledgeCheckedResult()
        assertTrue(f.controller.state.value.uncertain)
        f.api.listFailure = null
        f.controller.load()
        assertTrue(f.controller.state.value.checkedAfterUncertain)
        f.controller.acknowledgeCheckedResult()
        assertFalse(f.controller.state.value.uncertain)
        assertNull(f.controller.state.value.editor)
    }

    @Test
    fun uncertaintySurvivesScopeSwitch() = runBlocking {
        val f = Fixture()
        f.api.failure = IOException("lost")
        f.prepareEdit()
        f.controller.confirm()
        f.runWrite()
        f.selected = f.selected.copy(organizationId = "org-b")
        f.controller.load()
        assertFalse(f.controller.state.value.uncertain)
        f.selected = f.selected.copy(organizationId = "org-a")
        f.controller.load()
        assertTrue(f.controller.state.value.uncertain)
    }

    @Test
    fun explicitValidationFailureAllowsCorrection() = runBlocking {
        val f = Fixture()
        f.api.failure = ApiException(422, "validation")
        f.prepareEdit()
        f.controller.confirm()
        f.runWrite()
        assertFalse(f.controller.state.value.uncertain)
        assertNotNull(f.controller.state.value.editor)
        assertEquals(0, f.saved)
    }

    @Test
    fun currencyImmutableAndCombinedDefaultCannotArchive() {
        val f = Fixture()
        f.controller.open(row)
        f.controller.edit(ManagedAccountForm.from(row).copy(currency = "USD"))
        assertEquals("EUR", f.controller.state.value.editor!!.currency)
        f.selected = f.selected.copy(combined = true)
        f.controller.load()
        f.controller.open()
        assertNull(f.controller.state.value.editor)
        f.controller.archive(row)
        assertNull(f.controller.state.value.confirmation)
    }

    @Test
    fun failedRefreshAfterAcknowledgedWriteDoesNotBecomeUncertain() = runBlocking {
        val f = Fixture()
        f.prepareEdit()
        f.controller.confirm()
        f.api.listFailure = IOException("offline")
        f.runWrite()
        assertFalse(f.controller.state.value.uncertain)
        assertEquals(1, f.saved)
        assertNotNull(f.controller.state.value.error)
    }

    @Test
    fun failedRereadBlocksStaleArchiveAndEdit() {
        val f = Fixture()
        f.api.listFailure = IOException("offline")
        f.controller.load()
        f.controller.open(row)
        f.controller.archive(row)
        f.controller.confirm()
        assertFalse(f.controller.state.value.loaded)
        assertNull(f.pending)
        assertNull(f.controller.state.value.editor)
        assertNull(f.controller.state.value.confirmation)
    }

    @Test
    fun cancellationAfterDispatchLocksUncertainResult() = runBlocking {
        val f = Fixture()
        f.api.failure = CancellationException("cancelled")
        f.prepareEdit()
        f.controller.confirm()
        try {
            f.runWrite()
            fail("Expected cancellation")
        } catch (_: CancellationException) {}
        assertTrue(f.controller.state.value.uncertain)
        assertFalse(f.controller.state.value.writing)
        assertEquals(0, f.saved)
    }

    @Test
    fun diskGuardSurvivesProcessRecreationAndRequiresReview() = runBlocking {
        val dir = java.nio.file.Files.createTempDirectory("account-guard-test").toFile()
        try {
            val first = Fixture(FileAccountMutationGuardStore(dir))
            first.controller.open()
            first.controller.edit(ManagedAccountForm(name = "Opening", openingBalance = "500.00"))
            first.controller.prepare()
            first.api.failure = IOException("response lost")
            first.controller.confirm()
            first.runWrite()
            assertEquals(1, first.api.writes)
            val second = Fixture(FileAccountMutationGuardStore(dir))
            assertTrue(second.controller.state.value.uncertain)
            assertTrue(second.controller.state.value.pendingDescription!!.contains("500.00"))
            second.controller.open()
            second.controller.confirm()
            assertNull(second.pending)
            second.controller.acknowledgeCheckedResult()
            assertFalse(second.controller.state.value.uncertain)
            val third = Fixture(FileAccountMutationGuardStore(dir))
            assertFalse(third.controller.state.value.uncertain)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun pendingIsDurableBeforeHttpStarts() = runBlocking {
        val dir = java.nio.file.Files.createTempDirectory("account-guard-before-http").toFile()
        val selected =
            AccountManagementScope("org-a", true, false, row.id, "owner-a", "https://example.test")
        val store = FileAccountMutationGuardStore(dir)
        var block: (suspend () -> Unit)? = null
        var observed = false
        val api =
            object : AccountManagementApi {
                override suspend fun list(organizationId: String) = listOf(row)

                override suspend fun write(organizationId: String, command: FinanceCommand) {
                    val reloaded = FileAccountMutationGuardStore(dir).read(selected)!!
                    assertEquals(
                        AccountMutationGuard.create(selected, command, "").commandDigest,
                        reloaded.commandDigest,
                    )
                    observed = true
                }
            }
        try {
            val c =
                AccountManagementController(
                    CoroutineScope(Dispatchers.Unconfined),
                    api,
                    { selected },
                    { false },
                    { block = it },
                    {},
                    {},
                    store,
                )
            c.load()
            c.open(row)
            c.prepare()
            c.confirm()
            block!!.invoke()
            assertTrue(observed)
            assertNull(store.read(selected))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun persistenceFailureNeverDispatchesHttp() = runBlocking {
        val store = MemoryGuard().apply { failPut = true }
        val f = Fixture(store)
        f.prepareEdit()
        f.controller.confirm()
        f.runWrite()
        assertEquals(0, f.api.writes)
        assertEquals(0, f.saved)
        assertNotNull(f.controller.state.value.error)
    }

    @Test
    fun realDirectoryFailureNeverDispatchesHttp() = runBlocking {
        val badDirectory = java.io.File.createTempFile("account-guard", ".file")
        try {
            val f = Fixture(FileAccountMutationGuardStore(badDirectory))
            f.prepareEdit()
            f.controller.confirm()
            f.runWrite()
            assertEquals(0, f.api.writes)
            assertEquals(0, f.saved)
        } finally {
            badDirectory.delete()
        }
    }

    @Test
    fun guardBelongsToOwnerOrgAndServerAndDoesNotOverwrite() {
        val dir = java.nio.file.Files.createTempDirectory("account-guard-scope").toFile()
        try {
            val store = FileAccountMutationGuardStore(dir)
            val f = Fixture(store)
            val g =
                AccountMutationGuard.create(
                    f.selected,
                    ManagedAccountForm(name = "Cash").command(),
                    "Cash",
                )
            store.put(f.selected, g)
            assertNull(store.read(f.selected.copy(ownerId = "other-owner")))
            assertNull(store.read(f.selected.copy(organizationId = "other-org")))
            assertNull(store.read(f.selected.copy(serverOrigin = "https://other.test")))
            assertThrows(IllegalStateException::class.java) {
                store.put(f.selected, g.copy(requestId = java.util.UUID.randomUUID().toString()))
            }
            assertThrows(IllegalStateException::class.java) {
                store.clear(f.selected, "different-identity")
            }
            assertEquals(g, FileAccountMutationGuardStore(dir).read(f.selected))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun corruptGuardFailsClosedAfterRecreation() {
        val dir = java.nio.file.Files.createTempDirectory("account-guard-corrupt").toFile()
        try {
            val store = FileAccountMutationGuardStore(dir)
            val f = Fixture(store)
            store.put(
                f.selected,
                AccountMutationGuard.create(
                    f.selected,
                    ManagedAccountForm(name = "Cash").command(),
                    "Cash",
                ),
            )
            dir.listFiles()!!.single { it.extension == "json" }.writeText("corrupt")
            val second = Fixture(FileAccountMutationGuardStore(dir))
            assertTrue(second.controller.state.value.uncertain)
            assertTrue(second.controller.state.value.guardUnavailable)
            second.controller.open()
            second.controller.confirm()
            second.controller.acknowledgeCheckedResult()
            assertNull(second.pending)
            assertTrue(second.controller.state.value.uncertain)
        } finally {
            dir.deleteRecursively()
        }
    }
}
