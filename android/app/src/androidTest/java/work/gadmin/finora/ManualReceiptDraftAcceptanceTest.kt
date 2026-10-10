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

/** New isolated directories only; no network or existing workspace records. */
@RunWith(AndroidJUnit4::class)
class ManualReceiptDraftAcceptanceTest {
    @Test
    fun delayedEditorCallbackCannotRestoreDraftWhileDeletionOrPostingIsBusy() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assumeTrue(Build.MODEL.startsWith("sdk_gphone"))
        assumeTrue(!File(context.noBackupFilesDir, "session.enc").exists())
        val scope =
            scopeKey("https://synthetic.invalid", "draft-race", UUID.randomUUID().toString())
        val disk = ManualReceiptDraftStore(context, scope)
        val original =
            ManualReceiptDraft(UUID.randomUUID().toString(), ReceiptForm.manual(emptyList()))
        val changed = original.form.copy(merchant = "Late edit from disposed editor")
        val vm = FinoraViewModel(context.applicationContext as Application)
        val holder = ViewModelStore().apply { put("draft-race", vm) }
        try {
            runBlocking { withTimeout(10_000) { vm.state.first { !it.starting } } }
            @Suppress("UNCHECKED_CAST")
            val mutable =
                FinoraViewModel::class.java.getDeclaredField("mutable").let {
                    it.isAccessible = true
                    it.get(vm) as MutableStateFlow<AppState>
                }
            FinoraViewModel::class.java.getDeclaredField("manualDrafts").let {
                it.isAccessible = true
                it.set(vm, disk)
            }
            disk.save(original)
            instrumentation.runOnMainSync {
                mutable.value =
                    AppState(
                        starting = false,
                        busy = true,
                        editingReceipt = true,
                        manualReceiptKey = original.requestKey,
                        manualReceiptDraft = original,
                        manualDraftSaved = true,
                    )
                vm.saveManualDraft(original.requestKey, changed)
            }
            assertEquals(original, vm.state.value.manualReceiptDraft)
            assertTrue(vm.state.value.manualDraftSaved)
            assertEquals(original, disk.read())
            disk.clear()
            instrumentation.runOnMainSync { vm.saveManualDraft(original.requestKey, changed) }
            assertNull(disk.read())
            assertEquals(original, vm.state.value.manualReceiptDraft)
        } finally {
            instrumentation.runOnMainSync { holder.clear() }
            File(context.noBackupFilesDir, "drafts/$scope").deleteRecursively()
        }
    }

    @Test
    fun closingFlushesLatestFormAndExplicitDeletionDoesNotRecreateIt() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assumeTrue(Build.MODEL.startsWith("sdk_gphone"))
        assumeTrue(!File(context.noBackupFilesDir, "session.enc").exists())
        val scope =
            scopeKey("https://synthetic.invalid", "close-race", UUID.randomUUID().toString())
        val disk = ManualReceiptDraftStore(context, scope)
        val original =
            ManualReceiptDraft(UUID.randomUUID().toString(), ReceiptForm.manual(emptyList()))
        val latest =
            original.form.copy(
                merchant = "Latest unfinished store",
                items = listOf(ReceiptLineForm(name = "Incomplete final item", quantity = "1,")),
            )
        val vm = FinoraViewModel(context.applicationContext as Application)
        val holder = ViewModelStore().apply { put("close-race", vm) }
        try {
            runBlocking { withTimeout(10_000) { vm.state.first { !it.starting } } }
            @Suppress("UNCHECKED_CAST")
            val mutable =
                FinoraViewModel::class.java.getDeclaredField("mutable").let {
                    it.isAccessible = true
                    it.get(vm) as MutableStateFlow<AppState>
                }
            FinoraViewModel::class.java.getDeclaredField("manualDrafts").let {
                it.isAccessible = true
                it.set(vm, disk)
            }
            disk.save(original)
            instrumentation.runOnMainSync {
                mutable.value =
                    AppState(
                        starting = false,
                        editingReceipt = true,
                        manualReceiptKey = original.requestKey,
                        manualReceiptDraft = original,
                    )
                vm.saveManualDraft(
                    original.requestKey,
                    original.form.copy(merchant = "Earlier edit"),
                )
                vm.closeManualDraft(latest)
            }
            runBlocking {
                withTimeout(10_000) { vm.state.first { !it.busy && !it.editingReceipt } }
            }
            assertEquals(ManualReceiptDraft(original.requestKey, latest), disk.read())
            assertEquals(latest, vm.state.value.manualReceiptDraft?.form)
            assertTrue(vm.state.value.manualDraftSaved)
            instrumentation.runOnMainSync {
                mutable.value =
                    mutable.value.copy(
                        editingReceipt = true,
                        manualReceiptKey = original.requestKey,
                    )
                vm.saveManualDraft(
                    original.requestKey,
                    latest.copy(merchant = "Obsolete queued edit"),
                )
                vm.discardManualDraft()
            }
            runBlocking {
                withTimeout(10_000) { vm.state.first { !it.busy && it.manualReceiptDraft == null } }
            }
            assertNull(disk.read())
            assertNull(vm.state.value.manualReceiptKey)
        } finally {
            instrumentation.runOnMainSync { holder.clear() }
            File(context.noBackupFilesDir, "drafts/$scope").deleteRecursively()
        }
    }

    @Test
    fun atomicDraftSurvivesStoreRecreationWithoutErasingPhotosOrChangingRequestId() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val scope = scopeKey("https://synthetic.invalid", "test-user", UUID.randomUUID().toString())
        val other =
            scopeKey("https://synthetic.invalid", "other-user", UUID.randomUUID().toString())
        try {
            val photos = DraftStore(context, scope)
            val originalPhotoDraft = Draft(qr = "https://receipt.invalid/synthetic")
            photos.save(originalPhotoDraft)
            val draft =
                ManualReceiptDraft(
                    UUID.randomUUID().toString(),
                    ReceiptForm(
                        "Incomplete",
                        "2026-10-09",
                        "MDL",
                        "",
                        "synthetic-account",
                        "1",
                        1,
                        listOf(ReceiptLineForm(name = "Milk", quantity = "1,", unitPrice = "")),
                    ),
                )
            ManualReceiptDraftStore(context, scope).save(draft)
            assertEquals(draft, ManualReceiptDraftStore(context, scope).read())
            assertNull(ManualReceiptDraftStore(context, other).read())
            ManualReceiptDraftStore(context, scope).clear()
            assertNull(ManualReceiptDraftStore(context, scope).read())
            assertEquals(originalPhotoDraft, photos.read())
        } finally {
            File(context.noBackupFilesDir, "drafts/$scope").deleteRecursively()
            File(context.noBackupFilesDir, "drafts/$other").deleteRecursively()
        }
    }

    @Test
    fun organizationSwitchRejectsOldEditorAndViewModelRecreationRestoresExactOwnerDraft() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assumeTrue(Build.MODEL.startsWith("sdk_gphone"))
        assumeTrue(!File(context.noBackupFilesDir, "session.enc").exists())
        val server = "https://synthetic.invalid"
        val userId = UUID.randomUUID().toString()
        val firstOrg = Organization(UUID.randomUUID().toString(), "Synthetic A", "admin")
        val secondOrg = Organization(UUID.randomUUID().toString(), "Synthetic B", "admin")
        val user =
            User(userId, "synthetic-scope", "Synthetic", "synthetic", listOf(firstOrg, secondOrg))
        val scopes =
            listOf(
                scopeKey(server, userId, firstOrg.id),
                scopeKey(server, userId, secondOrg.id),
                scopeKey(server, "other-user", firstOrg.id),
                scopeKey("https://other-synthetic.invalid", userId, firstOrg.id),
            )
        val original =
            ManualReceiptDraft(
                UUID.randomUUID().toString(),
                ReceiptForm.manual(emptyList()).copy(merchant = "Synthetic unfinished A"),
            )
        val disk = ManualReceiptDraftStore(context, scopes[0])
        val holders = mutableListOf<ViewModelStore>()
        fun create(): FinoraViewModel {
            val vm = FinoraViewModel(context.applicationContext as Application)
            holders.add(ViewModelStore().apply { put("scope", vm) })
            runBlocking { withTimeout(10_000) { vm.state.first { !it.starting } } }
            @Suppress("UNCHECKED_CAST")
            val mutable =
                FinoraViewModel::class.java.getDeclaredField("mutable").let {
                    it.isAccessible = true
                    it.get(vm) as MutableStateFlow<AppState>
                }
            instrumentation.runOnMainSync {
                mutable.value = AppState(starting = false, server = server, user = user)
            }
            return vm
        }
        try {
            disk.save(original)
            val first = create()
            instrumentation.runOnMainSync { first.selectOrganization(firstOrg) }
            runBlocking {
                withTimeout(10_000) {
                    first.state.first { !it.workspaceLoading && it.organization?.id == firstOrg.id }
                }
            }
            assertEquals(original, first.state.value.manualReceiptDraft)
            instrumentation.runOnMainSync { first.selectOrganization(secondOrg) }
            runBlocking {
                withTimeout(10_000) {
                    first.state.first {
                        !it.workspaceLoading && it.organization?.id == secondOrg.id
                    }
                }
            }
            assertNull(first.state.value.manualReceiptDraft)
            instrumentation.runOnMainSync {
                first.saveManualDraft(
                    original.requestKey,
                    original.form.copy(merchant = "Stale editor A callback"),
                )
            }
            assertNull(ManualReceiptDraftStore(context, scopes[1]).read())
            assertEquals(original, disk.read())
            instrumentation.runOnMainSync { holders[0].clear() }
            val recreated = create()
            instrumentation.runOnMainSync { recreated.selectOrganization(firstOrg) }
            runBlocking {
                withTimeout(10_000) {
                    recreated.state.first {
                        !it.workspaceLoading && it.organization?.id == firstOrg.id
                    }
                }
            }
            assertEquals(original, recreated.state.value.manualReceiptDraft)
            assertNull(ManualReceiptDraftStore(context, scopes[2]).read())
            assertNull(ManualReceiptDraftStore(context, scopes[3]).read())
        } finally {
            instrumentation.runOnMainSync { holders.forEach { it.clear() } }
            scopes.forEach { File(context.noBackupFilesDir, "drafts/$it").deleteRecursively() }
        }
    }
}
