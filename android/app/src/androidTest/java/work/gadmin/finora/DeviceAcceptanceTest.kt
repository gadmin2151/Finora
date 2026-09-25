package work.gadmin.finora

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import work.gadmin.finora.data.*

@RunWith(AndroidJUnit4::class)
class DeviceAcceptanceTest {
    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun fixture(index: Int): File {
        val image = Bitmap.createBitmap(1000, 1800, Bitmap.Config.ARGB_8888)
        Canvas(image).apply {
            drawColor(Color.WHITE)
            val ink =
                Paint().apply {
                    color = Color.BLACK
                    textSize = 42f
                    isAntiAlias = true
                }
            drawText("FINORA TEST - NOT A FISCAL RECEIPT", 30f, 150f, ink)
            drawText("PAGE $index", 30f, 260f, ink)
            drawText("RUN ${UUID.randomUUID()}", 30f, 320f, ink)
            drawText("TEST PRODUCT 1 x 5.00 = 5.00", 30f, 380f, ink)
            drawText("TOTAL MDL 5.00", 30f, 540f, ink)
        }
        return File(context.cacheDir, "fixture-${UUID.randomUUID()}.jpg").also { file ->
            file.outputStream().use { image.compress(Bitmap.CompressFormat.JPEG, 95, it) }
            image.recycle()
        }
    }

    @Test
    fun privateDraftSurvivesRestartAndIsIsolated() {
        val key = "acceptance-${UUID.randomUUID()}"
        val store = DraftStore(context, key)
        val files = (1..4).map(::fixture)
        try {
            val photos = files.map(store::importPhoto)
            store.save(Draft(photos = photos))
            assertEquals(photos, DraftStore(context, key).read().photos)
            assertFalse(DraftStore(context, key + "-other-organization").read().hasContent)
            store.save(store.read().copy(photos = photos.reversed()))
            assertEquals(photos.reversed(), DraftStore(context, key).read().photos)
            store.clear()
            assertFalse(store.read().hasContent)
            assertTrue(photos.none { store.photo(it).exists() })
        } finally {
            files.forEach(File::delete)
            store.clear()
        }
    }

    @Test
    fun encryptedSessionRestoresWithoutStoringPassword() {
        val store = SessionStore(context)
        val previous = store.read()
        val test =
            SavedSession(
                DEFAULT_SERVER,
                "finance_session=fixture-secret; Path=/; Secure; HttpOnly",
                User("test", "test", "Test", "csrf"),
            )
        try {
            store.save(test)
            assertEquals(test, SessionStore(context).read())
            val raw = File(context.noBackupFilesDir, "session.enc").readBytes().decodeToString()
            assertFalse(raw.contains("fixture-secret"))
            assertFalse(raw.contains("csrf"))
            store.clear()
            assertNull(store.read())
        } finally {
            if (previous != null) store.save(previous) else store.clear()
        }
    }

    @Test
    fun bundledQrScannerRecognizesMevWithoutModelDownload() {
        val input =
            InstrumentationRegistry.getInstrumentation().context.assets.open("mev-test.png").use {
                BitmapFactory.decodeStream(it)
            }
        val scanner =
            BarcodeScanning.getClient(
                BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
            )
        try {
            val codes =
                Tasks.await(scanner.process(InputImage.fromBitmap(input, 0)), 20, TimeUnit.SECONDS)
            assertEquals(
                "https://mev.sfs.md/receipt-verifier/0123456789abcdef",
                mevLink(requireNotNull(codes.single().rawValue)),
            )
        } finally {
            scanner.close()
            input.recycle()
        }
    }

    /** Opt-in live test account MUST have only disposable organizations named Android QA. */
    @Test
    fun httpsLoginUploadTwoPhotosDuplicateCommentsAndLogout() = runBlocking {
        val file = File(context.filesDir, "acceptance-credentials.json")
        assumeTrue(
            "Provide private test-only credentials to opt into network acceptance",
            file.isFile,
        )
        val credentials = Json.parseToJsonElement(file.readText()).jsonObject
        val client = ApiClient(credentials.getValue("server").jsonPrimitive.content)
        val user =
            client.login(
                credentials.getValue("username").jsonPrimitive.content,
                credentials.getValue("password").jsonPrimitive.content,
            )
        assertTrue(user.organizations.isNotEmpty())
        assertTrue(
            "Never upload test purchases to real finances",
            user.organizations.all { it.name.startsWith("Android QA ") },
        )
        assertEquals(user.id, client.me().id)
        assertEquals(user.id, ApiClient(client.server, client.savedCookie()).me().id)
        assertTrue(client.savedCookie().contains("secure", ignoreCase = true))
        val org = user.organizations.first().id
        assertTrue(client.accounts(org).isNotEmpty())
        assertTrue(client.categories(org).isNotEmpty())
        assertEquals(0L, client.dashboard(org, "2026-09").expense_minor)
        client.insights(org, "2026-09")
        val sources = listOf(fixture(1), fixture(2))
        val drafts = DraftStore(context, "acceptance-upload")
        try {
            val photos = sources.map(drafts::importPhoto)
            val draft = Draft(photos = photos)
            val result = client.upload(org, draft, photos.map(drafts::photo)) {}
            assertEquals(2, result.receipt.files.size)
            assertFalse(result.duplicate)
            val photo = client.receiptPhoto(org, result.receipt.id, 0)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(photo, 0, photo.size, bounds)
            assertEquals(1000, bounds.outWidth)
            try {
                client.retry(org, result.receipt.id)
                fail("An ordinary member cannot retry recognition")
            } catch (error: ApiException) {
                assertEquals(403, error.code)
            }
            val duplicate = client.upload(org, draft, photos.map(drafts::photo)) {}
            assertTrue(duplicate.duplicate)
            assertEquals(result.receipt.id, duplicate.receipt.id)
            client.comment(org, result.receipt.id, "Android acceptance: two photos, one receipt")
            assertEquals(1, client.comments(org, result.receipt.id).size)
            assertTrue(client.receipts(org, "", 0).items.any { it.id == result.receipt.id })
            val other = user.organizations.last().id
            if (other != org) {
                assertTrue(client.receipts(other, "", 0).items.isEmpty())
                try {
                    client.receipt(other, result.receipt.id)
                    fail("Cross-organization access must fail")
                } catch (error: ApiException) {
                    assertEquals(404, error.code)
                }
            }
        } finally {
            sources.forEach(File::delete)
            drafts.clear()
            client.logout()
        }
        try {
            client.me()
            fail("Logout must revoke session")
        } catch (error: ApiException) {
            assertEquals(401, error.code)
        }
    }
}
