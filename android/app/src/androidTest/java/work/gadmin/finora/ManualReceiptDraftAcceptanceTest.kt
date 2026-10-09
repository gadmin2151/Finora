package work.gadmin.finora

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import work.gadmin.finora.data.*

/** New isolated directories only; no network or existing workspace records. */
@RunWith(AndroidJUnit4::class)
class ManualReceiptDraftAcceptanceTest {
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
}
