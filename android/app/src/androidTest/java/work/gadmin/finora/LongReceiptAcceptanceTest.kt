package work.gadmin.finora

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import kotlin.math.abs
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import work.gadmin.finora.capture.LongReceiptSession
import work.gadmin.finora.data.Draft
import work.gadmin.finora.data.DraftStore

@RunWith(AndroidJUnit4::class)
class LongReceiptAcceptanceTest {
    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun paper(width: Int = 720, height: Int = 3200): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val paint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                textSize = 28f
            }
        for (i in 0 until height / 64) {
            val y = 40f + i * 64
            canvas.drawText("${i + 1}. PRODUCT ${1137 * (i + 3)}", 28f, y, paint)
            canvas.drawText(
                "${i % 7 + 1} x ${i * 13 + 7}.50 = ${i * 21 + 16}.00",
                260f,
                y + 27,
                paint,
            )
            canvas.drawRect(25f + i % 5 * 17, y + 4, 34f + i % 5 * 17, y + 13, paint)
        }
        return bitmap
    }

    @Test
    fun automaticFramesBecomeOnePhotoWithoutRepeatedRows() {
        val source = paper()
        val store = DraftStore(context, "long-receipt-test-${UUID.randomUUID()}")
        LongReceiptSession(context.cacheDir).use { session ->
            try {
                val first = session.offer(Bitmap.createBitmap(source, 0, 0, 720, 1000))
                assertEquals(1, first.count)
                assertEquals(1, session.offer(Bitmap.createBitmap(source, 0, 0, 720, 1000)).count)
                var accepted = 1
                for (top in listOf(280, 560, 840, 1120, 1400, 1680, 1960)) {
                    val progress = session.offer(Bitmap.createBitmap(source, 0, top, 720, 1000))
                    assertFalse(
                        "At $top: ${progress.message}, height=${progress.height}",
                        progress.warning,
                    )
                    assertEquals(++accepted, progress.count)
                }
                // A final movement below the normal capture threshold is retained by Done.
                session.offer(Bitmap.createBitmap(source, 0, 2020, 720, 1000))
                val result = session.finish()
                result.copyTo(
                    File(context.filesDir, "long-receipt-synthetic.jpg"),
                    overwrite = true,
                )
                val stitched = requireNotNull(BitmapFactory.decodeFile(result.path))
                assertTrue(
                    "Expected full tail: ${stitched.height}",
                    abs(stitched.height - 3020) < 8,
                )
                assertTrue(stitched.width >= 710)
                // Compare distributed dark strokes, not just the output dimensions.
                var differences = 0
                var samples = 0
                for (y in 20 until 3000 step 3) for (x in 30 until 680 step 5) {
                    if (
                        abs(Color.red(source.getPixel(x, y)) - Color.red(stitched.getPixel(x, y))) >
                            80
                    )
                        differences++
                    samples++
                }
                assertTrue(
                    "Printed rows moved or duplicated: $differences/$samples, output=${stitched.width}x${stitched.height}",
                    differences.toFloat() / samples < .035f,
                )
                result.copyTo(
                    File(context.filesDir, "long-receipt-synthetic.jpg"),
                    overwrite = true,
                )
                val name = store.importPhoto(result)
                store.save(Draft(photos = listOf(name)))
                assertEquals(1, store.read().photos.size)
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(store.photo(name).path, bounds)
                assertEquals(stitched.height, bounds.outHeight)
                stitched.recycle()
            } finally {
                source.recycle()
                store.clear()
            }
        }
    }

    @Test
    fun unrelatedFrameIsNotInsertedAndLongDraftKeepsItsWidth() {
        val source = paper()
        LongReceiptSession(context.cacheDir).use { session ->
            session.offer(Bitmap.createBitmap(source, 0, 0, 720, 1000))
            val unrelated =
                Bitmap.createBitmap(720, 1000, Bitmap.Config.ARGB_8888).apply {
                    eraseColor(Color.WHITE)
                }
            val rejected = session.offer(unrelated)
            assertTrue(rejected.warning)
            assertEquals(1, rejected.count)
            assertEquals(1000, BitmapFactory.decodeFile(session.finish().path).height)
        }
        source.recycle()
        val long = paper(height = 12_000)
        val original = File(context.cacheDir, "long-draft-${UUID.randomUUID()}.jpg")
        val store = DraftStore(context, "long-draft-test-${UUID.randomUUID()}")
        try {
            original.outputStream().use { long.compress(Bitmap.CompressFormat.JPEG, 93, it) }
            val name = store.importPhoto(original)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(store.photo(name).path, bounds)
            assertEquals(720, bounds.outWidth)
            assertEquals(12_000, bounds.outHeight)
        } finally {
            original.delete()
            long.recycle()
            store.clear()
        }
    }

    /** Optional private user photo; never included in the repository or release. */
    @Test
    fun privateReceiptPhotoCanBeReassembled() {
        val file = File(context.filesDir, "private-long-receipt-fixture.jpg")
        assumeTrue("Private receipt fixture not supplied", file.isFile)
        val original = requireNotNull(BitmapFactory.decodeFile(file.path))
        val crop = Bitmap.createBitmap(original, 680, 140, 980, 3800)
        val source = Bitmap.createScaledBitmap(crop, 720, 2792, true)
        original.recycle()
        crop.recycle()
        try {
            LongReceiptSession(context.cacheDir).use { session ->
                for (top in listOf(0, 260, 520, 780, 1040, 1300, 1560, 1792)) {
                    val progress = session.offer(Bitmap.createBitmap(source, 0, top, 720, 1000))
                    assertFalse("At $top: ${progress.message}", progress.warning)
                }
                val result = session.finish()
                val output = BitmapFactory.decodeFile(result.path)
                assertTrue(abs(output.height - source.height) < 15)
                result.copyTo(
                    File(context.filesDir, "private-long-receipt-result.jpg"),
                    overwrite = true,
                )
                output.recycle()
            }
        } finally {
            source.recycle()
        }
    }
}
