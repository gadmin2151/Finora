package work.gadmin.finora.capture

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import java.io.Closeable
import java.io.File
import java.util.UUID
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

const val LONG_RECEIPT_MAX_HEIGHT = 14_000
const val LONG_RECEIPT_MAX_WIDTH = 1920
private const val MAX_SEGMENTS = 160

data class ScanProgress(
    val count: Int = 0,
    val height: Int = 0,
    val message: String = "Поместите белую бумагу чека в рамку",
    val warning: Boolean = false,
    val limitReached: Boolean = false,
    val thumbnail: Bitmap? = null,
)

/** A single stitching worker owns feature tracking, camera pixels and private section files. */
class LongReceiptSession(cache: File) : Closeable {
    private data class Segment(val file: File, val transform: ReceiptTransform, val seam: Float)

    private data class Pending(
        val bitmap: Bitmap,
        val transform: ReceiptTransform,
        val top: Float,
        val bottom: Float,
    )

    private val registration = ReceiptRegistration()
    private val directory =
        File(cache, "long-receipt/${UUID.randomUUID()}").apply { check(mkdirs()) }
    private val segments = ArrayList<Segment>()
    private var previous: Bitmap? = null
    private var tracking: ReceiptRegistration.Features? = null
    private var trackingTransform = ReceiptTransform()
    private var pending: Pending? = null
    private var frameWidth = 0
    private var frameHeight = 0
    private var commonLeft = 0f
    private var commonRight = 0f
    private var paperLeft = Float.POSITIVE_INFINITY
    private var paperRight = Float.NEGATIVE_INFINITY
    private var originTop = 0f
    private var totalHeight = 0f
    private var preview: Bitmap? = null

    private fun status(message: String, warning: Boolean = false, limit: Boolean = false) =
        ScanProgress(
            segments.size,
            ceil(totalHeight - originTop).toInt(),
            message,
            warning,
            limit,
            preview,
        )

    /** Takes ownership of every camera bitmap. Rejected frames never add invented paper. */
    fun offer(bitmap: Bitmap): ScanProgress {
        var retained = false
        var features: ReceiptRegistration.Features? = null
        try {
            require(bitmap.width <= LONG_RECEIPT_MAX_WIDTH && bitmap.height <= 2400)
            features = registration.features(bitmap)
            if (features.points.size < 16 || features.paper.isEmpty)
                return status("Наведите на белый чек с печатными строками", true)
            if (previous == null) {
                frameWidth = bitmap.width
                frameHeight = bitmap.height
                commonRight = frameWidth.toFloat()
                originTop = features.paper.top
                paperLeft = features.paper.left
                paperRight = features.paper.right
                tracking = features
                features = null
                append(
                    Pending(
                        bitmap,
                        ReceiptTransform(),
                        originTop,
                        requireNotNull(tracking).paper.bottom,
                    )
                )
                retained = true
                return status("Сканируем · плавно ведите телефон вниз")
            }
            if (bitmap.width != frameWidth || bitmap.height != frameHeight)
                return status("Держите телефон вертикально", true)
            val match =
                registration.match(requireNotNull(tracking), features, frameWidth, frameHeight)
                    ?: return status("Вернитесь чуть вверх, чтобы снова увидеть общие строки", true)
            val transform = trackingTransform * match
            val corners = transform.corners(frameWidth, frameHeight)
            val left = max(corners[0].x, corners[3].x)
            val right = min(corners[1].x, corners[2].x)
            if (min(commonRight, right) - max(commonLeft, left) < frameWidth * .65f)
                return status("Держите бумагу по центру рамки", true)
            val top = max(corners[0].y, corners[1].y)
            val bottom = min(corners[2].y, corners[3].y)
            if (!bottom.isFinite() || bottom - top !in frameHeight * .60f..frameHeight * 1.6f)
                return status("Сохраняйте расстояние до бумаги", true)
            val advance = bottom - totalHeight
            if (advance < -frameHeight * .12f) return status("Ведите телефон сверху вниз", true)
            if (bottom - originTop > LONG_RECEIPT_MAX_HEIGHT || segments.size >= MAX_SEGMENTS)
                return status("Достигнута максимальная длина. Нажмите «Готово»", limit = true)
            // Track each neighboring frame, even when movement is too small to add a strip.
            // This prevents accumulated handheld tilt from losing the original reference.
            tracking?.close()
            tracking = features
            features = null
            trackingTransform = transform
            val paper = requireNotNull(tracking).paper
            val paperCorners =
                listOf(
                    transform.map(paper.left, paper.top),
                    transform.map(paper.right, paper.top),
                    transform.map(paper.right, paper.bottom),
                    transform.map(paper.left, paper.bottom),
                )
            paperLeft = min(paperLeft, paperCorners.minOf { it.x })
            paperRight = max(paperRight, paperCorners.maxOf { it.x })
            if (advance < 4) return status("Сканируем · продолжайте движение вниз")
            val candidate = Pending(bitmap, transform, top, bottom)
            if (advance < frameHeight * .08f) {
                if (pending == null || bottom > requireNotNull(pending).bottom) {
                    pending?.bitmap?.recycle()
                    pending = candidate
                    retained = true
                }
                return status("Сканируем · полоса чека продолжается")
            }
            pending?.bitmap?.recycle()
            pending = null
            append(candidate)
            retained = true
            return status("Добавлен новый участок · ведите дальше вниз")
        } finally {
            features?.close()
            if (!retained) bitmap.recycle()
        }
    }

    private fun append(next: Pending) {
        val seam = if (segments.isEmpty()) originTop else chooseSeam(next)
        val file = File(directory, "${segments.size}.jpg")
        file.outputStream().use { check(next.bitmap.compress(Bitmap.CompressFormat.JPEG, 98, it)) }
        segments.add(Segment(file, next.transform, seam))
        val corners = next.transform.corners(frameWidth, frameHeight)
        commonLeft = max(commonLeft, max(corners[0].x, corners[3].x))
        commonRight = min(commonRight, min(corners[1].x, corners[2].x))
        totalHeight = next.bottom
        previous?.recycle()
        previous = next.bitmap
        preview = render(72)
    }

    private fun chooseSeam(next: Pending): Float {
        val oldInverse = requireNotNull(segments.last().transform.inverse())
        val newInverse = requireNotNull(next.transform.inverse())
        val center = (totalHeight + next.top) / 2
        var best = center
        var lightest = -1.0
        for (offset in -20..20 step 2) {
            val row = center + offset
            var light = 0.0
            var samples = 0
            for (x in commonLeft.roundToInt() + 12 until commonRight.roundToInt() - 12 step 7) {
                val a = oldInverse.map(x.toFloat(), row)
                val b = newInverse.map(x.toFloat(), row)
                val ax = a.x.roundToInt()
                val ay = a.y.roundToInt()
                val bx = b.x.roundToInt()
                val by = b.y.roundToInt()
                if (
                    ax !in 0 until frameWidth ||
                        bx !in 0 until frameWidth ||
                        ay !in 1 until frameHeight - 1 ||
                        by !in 1 until frameHeight - 1
                )
                    continue
                for (dy in -1..1) {
                    light +=
                        min(
                            Color.red(requireNotNull(previous).getPixel(ax, ay + dy)),
                            Color.red(next.bitmap.getPixel(bx, by + dy)),
                        )
                    samples++
                }
            }
            if (samples > 0 && light / samples > lightest) {
                lightest = light / samples
                best = row
            }
        }
        return best
    }

    fun finish(): File {
        check(segments.isNotEmpty()) { "Сначала наведите камеру на печатную часть чека" }
        pending?.let {
            pending = null
            append(it)
        }
        val paperWidth = min(commonRight, paperRight) - max(commonLeft, paperLeft)
        val paperHeight = totalHeight - originTop
        val maxPixels = minOf(16_000_000L, Runtime.getRuntime().maxMemory() / 24).toDouble()
        val scale = minOf(1.0, kotlin.math.sqrt(maxPixels / (paperWidth * paperHeight)))
        val width = (paperWidth * scale).toInt().coerceAtLeast(1)
        val bitmap = render(width)
        val output = File(directory, "receipt.jpg")
        try {
            output.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 97, it)) }
            check(output.length() <= 15L * 1024 * 1024) {
                "Снимок слишком большой. Снимите чек двумя частями"
            }
        } finally {
            bitmap.recycle()
        }
        return output
    }

    private fun render(width: Int): Bitmap {
        val left = max(commonLeft, paperLeft)
        val right = min(commonRight, paperRight)
        val factor = width / (right - left)
        val height = ceil((totalHeight - originTop) * factor).toInt().coerceAtLeast(1)
        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val output =
            ReceiptTransform(
                floatArrayOf(
                    factor,
                    0f,
                    -left * factor,
                    0f,
                    factor,
                    -originTop * factor,
                    0f,
                    0f,
                    1f,
                )
            )
        for ((index, segment) in segments.withIndex()) {
            val options =
                BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                    while (frameWidth / (inSampleSize.coerceAtLeast(1) * 2) > width) inSampleSize =
                        inSampleSize.coerceAtLeast(1) * 2
                }
            val source = requireNotNull(BitmapFactory.decodeFile(segment.file.path, options))
            try {
                val saved = canvas.save()
                canvas.clipRect(
                    0f,
                    (segment.seam - originTop) * factor,
                    width.toFloat(),
                    ((segments.getOrNull(index + 1)?.seam ?: totalHeight) - originTop) * factor,
                )
                val resolution =
                    ReceiptTransform(
                        floatArrayOf(
                            frameWidth.toFloat() / source.width,
                            0f,
                            0f,
                            0f,
                            frameHeight.toFloat() / source.height,
                            0f,
                            0f,
                            0f,
                            1f,
                        )
                    )
                val matrix =
                    Matrix().apply { setValues((output * segment.transform * resolution).values) }
                canvas.drawBitmap(source, matrix, paint)
                canvas.restoreToCount(saved)
            } finally {
                source.recycle()
            }
        }
        return result
    }

    override fun close() {
        previous?.recycle()
        previous = null
        tracking?.close()
        tracking = null
        pending?.bitmap?.recycle()
        pending = null
        registration.close()
        directory.deleteRecursively()
    }
}
