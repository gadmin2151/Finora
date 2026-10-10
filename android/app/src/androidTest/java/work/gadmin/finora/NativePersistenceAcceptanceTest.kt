package work.gadmin.finora

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import work.gadmin.finora.accounts.*
import work.gadmin.finora.data.*

/** Real Android storage, fictional scope; no authenticated transport or money writes. */
@RunWith(AndroidJUnit4::class)
class NativePersistenceAcceptanceTest {
    @Test
    fun accountGuardSurvivesStoreRecreationBeforeDispatch() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.noBackupFilesDir, "synthetic-storage-acceptance")
        val scope =
            AccountManagementScope(
                "synthetic-org",
                true,
                false,
                null,
                "synthetic-user",
                "https://synthetic.invalid",
            )
        val command =
            FinanceCommand("/api/accounts", "POST", buildJsonObject { put("name", "Synthetic") })
        val guard = AccountMutationGuard.create(scope, command, "Synthetic storage test")
        val store = FileAccountMutationGuardStore(directory)
        try {
            store.put(scope, guard)
            assertEquals(guard, FileAccountMutationGuardStore(directory).read(scope))
            assertNull(store.read(scope.copy(ownerId = "another-synthetic-user")))
            store.clear(scope, guard.requestId)
            assertNull(store.read(scope))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun ledgerPayloadAndKeySurviveAndroidStoreRecreation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val form = LedgerEdit(accountId = "synthetic-account", amount = "1.23", date = "2026-10-10")
        val command = form.command(listOf(Account("synthetic-account", "Synthetic", "MDL")))
        val pending =
            LedgerPendingMutation(
                "synthetic.invalid|storage-test",
                "synthetic-org",
                form,
                command.path,
                command.method,
                command.body,
            )
        val store = PreferencesLedgerPendingStore(context)
        try {
            store.save(pending)
            assertEquals(
                pending,
                PreferencesLedgerPendingStore(context).read(pending.owner, pending.organization),
            )
            assertNull(store.read("another-synthetic-owner", pending.organization))
        } finally {
            store.clear(pending)
        }
    }
}
