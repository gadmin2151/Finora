package work.gadmin.finora

import android.app.Application
import android.os.Build
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import work.gadmin.finora.data.*

/** Actual offline logout/controller path with fake identities; never contacts a real server. */
@RunWith(AndroidJUnit4::class)
class ManualDraftLogoutAcceptanceTest {
    @Test
    fun offlineLogoutPreservesOwnerDraftAndRejectsStaleEditorForNextUser() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assumeTrue(Build.MODEL.startsWith("sdk_gphone"))
        assumeTrue(!File(context.noBackupFilesDir, "session.enc").exists())
        val server = "https://127.0.0.1:1"
        val org = Organization(UUID.randomUUID().toString(), "Synthetic logout", "admin")
        val first =
            User(UUID.randomUUID().toString(), "qa-owner", "Synthetic", "synthetic", listOf(org))
        val second = first.copy(id = UUID.randomUUID().toString(), username = "qa-other")
        val firstScope = scopeKey(server, first.id, org.id)
        val secondScope = scopeKey(server, second.id, org.id)
        val disk = ManualReceiptDraftStore(context, firstScope)
        val original =
            ManualReceiptDraft(
                UUID.randomUUID().toString(),
                ReceiptForm.manual(emptyList()).copy(merchant = "Synthetic retained owner draft"),
            )
        val vm = FinoraViewModel(context.applicationContext as Application)
        val holder = ViewModelStore().apply { put("logout", vm) }
        try {
            runBlocking { withTimeout(10_000) { vm.state.first { !it.starting } } }
            @Suppress("UNCHECKED_CAST")
            val mutable =
                FinoraViewModel::class.java.getDeclaredField("mutable").let {
                    it.isAccessible = true
                    it.get(vm) as MutableStateFlow<AppState>
                }
            fun select(user: User) {
                instrumentation.runOnMainSync {
                    mutable.value = AppState(starting = false, server = server, user = user)
                    vm.selectOrganization(org)
                }
                runBlocking {
                    withTimeout(10_000) {
                        vm.state.first { !it.workspaceLoading && it.organization?.id == org.id }
                    }
                }
            }
            disk.save(original)
            select(first)
            assertEquals(original, vm.state.value.manualReceiptDraft)
            instrumentation.runOnMainSync {
                FinoraViewModel::class.java.getDeclaredField("api").let {
                    it.isAccessible = true
                    it.set(vm, ApiClient(server))
                }
                vm.logout()
            }
            runBlocking { withTimeout(10_000) { vm.state.first { !it.busy && it.user == null } } }
            assertNull(vm.state.value.manualReceiptDraft)
            assertNull(vm.state.value.manualReceiptKey)
            assertFalse(File(context.noBackupFilesDir, "session.enc").exists())
            assertEquals(original, disk.read())
            select(second)
            assertNull(vm.state.value.manualReceiptDraft)
            instrumentation.runOnMainSync {
                vm.saveManualDraft(
                    original.requestKey,
                    original.form.copy(merchant = "Stale owner callback"),
                )
            }
            assertNull(ManualReceiptDraftStore(context, secondScope).read())
            assertEquals(original, disk.read())
            select(first)
            assertEquals(original, vm.state.value.manualReceiptDraft)
        } finally {
            instrumentation.runOnMainSync { holder.clear() }
            listOf(firstScope, secondScope).forEach {
                File(context.noBackupFilesDir, "drafts/$it").deleteRecursively()
            }
        }
    }
}
