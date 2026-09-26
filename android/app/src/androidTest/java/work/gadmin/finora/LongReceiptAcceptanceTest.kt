package work.gadmin.finora

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import work.gadmin.finora.capture.LongReceiptController
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

    @Test
    fun highResolutionScanPreservesWidthAndSmoothPaperTones() {
        val source = paper(width = 1440, height = 3600)
        val canvas = Canvas(source)
        val paint = Paint()
        for (x in 900 until 1200) {
            val gray = 190 + (x - 900) / 5
            paint.color = Color.rgb(gray, gray, gray)
            canvas.drawRect(x.toFloat(), 100f, x + 1f, 300f, paint)
        }
        val store = DraftStore(context, "quality-test-${UUID.randomUUID()}")
        LongReceiptSession(context.cacheDir).use { session ->
            try {
                for (top in listOf(0, 400, 800, 1200, 1600)) {
                    val progress = session.offer(Bitmap.createBitmap(source, 0, top, 1440, 2000))
                    assertFalse(progress.message, progress.warning)
                }
                val result = session.finish()
                val photo = store.photo(store.importPhoto(result))
                val output = requireNotNull(BitmapFactory.decodeFile(photo.path))
                try {
                    assertTrue("High resolution was lost: ${output.width}", output.width >= 1420)
                    val tones = (910 until 1190).map { Color.red(output.getPixel(it, 200)) }.toSet()
                    assertTrue("Paper shades were quantized: ${tones.size}", tones.size >= 40)
                } finally {
                    output.recycle()
                }
            } finally {
                source.recycle()
                store.clear()
            }
        }
    }

    @Test
    fun continuousCaptureBuildsPreviewBeforeDone() {
        val source = paper(height = 2500)
        val live = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val failure = AtomicReference<String?>(null)
        val file = AtomicReference<File?>(null)
        val controller =
            LongReceiptController(
                context.cacheDir,
                Executor { it.run() },
                { if (it.count >= 4 && it.height > 1500 && it.thumbnail != null) live.countDown() },
                {
                    file.set(it)
                    finished.countDown()
                },
                {
                    failure.set(it)
                    live.countDown()
                    finished.countDown()
                },
            )
        try {
            for (top in listOf(0, 180, 360, 540, 720, 900)) {
                val frame = Bitmap.createBitmap(source, 0, top, 720, 1000)
                controller.executor.execute { controller.submitCapturedFrame(frame) }
                Thread.sleep(300)
            }
            assertTrue("Live panorama did not grow before Done", live.await(15, TimeUnit.SECONDS))
            assertNull(failure.get())
            assertNull("Final photo must wait for explicit Done", file.get())
            controller.finish()
            assertTrue(finished.await(15, TimeUnit.SECONDS))
            assertNull(failure.get())
            val image = requireNotNull(BitmapFactory.decodeFile(requireNotNull(file.get()).path))
            assertTrue(image.height > 1800)
            image.recycle()
        } finally {
            controller.close()
            source.recycle()
        }
    }

    @Test
    fun deskIsExcludedButReceiptInkIsPreserved() {
        val receipt = paper(height = 2200)
        val source = Bitmap.createBitmap(720, 2200, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(source)
        canvas.drawColor(Color.rgb(205, 209, 211))
        val deskInk =
            Paint().apply {
                color = Color.BLACK
                textSize = 22f
            }
        for (y in 80..2000 step 120) {
            canvas.drawText("DESK", 10f, y.toFloat(), deskInk)
            canvas.drawText("DESK", 630f, y.toFloat(), deskInk)
        }
        canvas.drawBitmap(
            receipt,
            null,
            android.graphics.RectF(120f, 0f, 600f, 2200f),
            Paint(Paint.FILTER_BITMAP_FLAG),
        )
        LongReceiptSession(context.cacheDir).use { session ->
            try {
                for (top in listOf(0, 250, 500, 750, 1000)) {
                    val progress = session.offer(Bitmap.createBitmap(source, 0, top, 720, 1000))
                    assertFalse(progress.message, progress.warning)
                }
                val result = BitmapFactory.decodeFile(session.finish().path)
                try {
                    assertTrue(
                        "Only the paper should remain: ${result.width}",
                        result.width in 450..500,
                    )
                    assertTrue(result.height > 1950)
                    var ink = 0
                    for (y in 0 until result.height step 5) for (x in 0 until result.width step 5) {
                        val pixel = result.getPixel(x, y)
                        assertTrue(
                            "Colored desk leaked into scan",
                            abs(Color.red(pixel) - Color.blue(pixel)) < 12,
                        )
                        if (Color.red(pixel) < 100) ink++
                    }
                    assertTrue("Printed details must survive the mask", ink > 250)
                    File(context.filesDir, "paper-only-panorama.jpg").outputStream().use {
                        result.compress(Bitmap.CompressFormat.JPEG, 94, it)
                    }
                } finally {
                    result.recycle()
                }
            } finally {
                source.recycle()
                receipt.recycle()
            }
        }
    }

    @Test
    fun handheldPanoramaContinuesAfterTiltAndDistanceChange() {
        val source = paper(height = 3000)
        LongReceiptSession(context.cacheDir).use { session ->
            try {
                assertEquals(1, session.offer(Bitmap.createBitmap(source, 0, 0, 720, 1000)).count)
                for ((index, top) in listOf(220, 440, 660, 880, 1100, 1320, 1540).withIndex()) {
                    val frame = Bitmap.createBitmap(720, 1000, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(frame)
                    canvas.drawColor(Color.WHITE)
                    val transform =
                        Matrix().apply {
                            setTranslate(0f, -top.toFloat())
                            postRotate(if (index % 2 == 0) 2f else -1.5f, 360f, 500f)
                            postScale(1.035f, 1.035f, 360f, 500f)
                        }
                    canvas.drawBitmap(source, transform, Paint(Paint.FILTER_BITMAP_FLAG))
                    val progress = session.offer(frame)
                    assertFalse("Handheld frame $index: ${progress.message}", progress.warning)
                    assertEquals(index + 2, progress.count)
                }
                val stitched = requireNotNull(BitmapFactory.decodeFile(session.finish().path))
                try {
                    assertTrue("Panorama must grow beyond the first frame", stitched.height > 2400)
                } finally {
                    stitched.recycle()
                }
            } finally {
                source.recycle()
            }
        }
    }

    @Test
    fun clearSparseReceiptSectionIsNotMistakenForBlur() {
        val source = paper(height = 1800)
        val canvas = Canvas(source)
        val ink = Paint().apply { color = Color.BLACK }
        // A dense header leaves the frame, while the shared printed rows remain perfectly sharp.
        for (y in 0 until 440 step 10) {
            canvas.drawRect(20f, y.toFloat(), 700f, y + 4f, ink)
        }
        val first = Bitmap.createBitmap(source, 0, 0, 720, 1000)
        val next = Bitmap.createBitmap(source, 0, 480, 720, 1000)
        LongReceiptSession(context.cacheDir).use { session ->
            try {
                assertEquals(1, session.offer(first).count)
                val progress = session.offer(next)
                assertFalse(progress.message, progress.warning)
                assertEquals(2, progress.count)
                val output = requireNotNull(BitmapFactory.decodeFile(session.finish().path))
                try {
                    assertTrue("The new rows must be included", abs(output.height - 1480) < 8)
                } finally {
                    output.recycle()
                }
            } finally {
                source.recycle()
            }
        }
    }

    @Test
    fun heavilyBlurredOverlapIsNotAppended() {
        val source = paper(height = 1800)
        val next = Bitmap.createBitmap(source, 0, 280, 720, 1000)
        val tiny = Bitmap.createScaledBitmap(next, 45, 63, true)
        val blurred = Bitmap.createScaledBitmap(tiny, 720, 1000, true)
        next.recycle()
        tiny.recycle()
        LongReceiptSession(context.cacheDir).use { session ->
            try {
                session.offer(Bitmap.createBitmap(source, 0, 0, 720, 1000))
                val progress = session.offer(blurred)
                assertTrue("An unreadable frame must not be appended", progress.warning)
                assertEquals(1, progress.count)
                val recovered = session.offer(Bitmap.createBitmap(source, 0, 280, 720, 1000))
                assertFalse(recovered.message, recovered.warning)
                assertEquals(2, recovered.count)
            } finally {
                source.recycle()
            }
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
                result.copyTo(
                    File(context.filesDir, "private-long-receipt-result.jpg"),
                    overwrite = true,
                )
                assertTrue(
                    "Output ${output.width}x${output.height}; source ${source.width}x${source.height}",
                    abs(output.height - source.height) < 15,
                )
                try {
                    // The supplied photo's QR is not reliably decodable even before masking.
                    // Compare its actual dark strokes instead, including the shaded footer.
                    var bestMissing = Int.MAX_VALUE
                    var samples = 0
                    for (shift in 0..source.width - output.width) {
                        var missing = 0
                        samples = 0
                        for (y in source.height * 82 / 100 until source.height - 20 step 2) {
                            for (x in 100 until 650 step 2) {
                                if (Color.red(source.getPixel(x, y)) >= 110) continue
                                samples++
                                var darkest = 255
                                for (dy in -1..1) for (dx in -1..1) {
                                    darkest =
                                        minOf(
                                            darkest,
                                            Color.red(output.getPixel(x - shift + dx, y + dy)),
                                        )
                                }
                                if (darkest > 145) missing++
                            }
                        }
                        bestMissing = minOf(bestMissing, missing)
                    }
                    assertTrue(
                        "The fixture must contain enough printed footer detail",
                        samples > 500,
                    )
                    assertTrue(
                        "Paper masking erased footer/QR strokes: $bestMissing/$samples",
                        bestMissing < samples * .02,
                    )
                } finally {
                    output.recycle()
                }
            }
        } finally {
            source.recycle()
        }
    }
}
