package work.gadmin.finora.capture

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import java.io.Closeable
import java.io.File
import java.util.UUID
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

const val LONG_RECEIPT_MAX_HEIGHT = 14_000
const val LONG_RECEIPT_MAX_WIDTH = 960
private const val MAX_SEGMENTS = 100

data class ScanProgress(
    val count: Int = 0,
    val height: Int = 0,
    val message: String = "Поместите начало чека в рамку",
    val warning: Boolean = false,
    val limitReached: Boolean = false,
    val thumbnail: Bitmap? = null,
)

/** Owned by one camera executor. Frame files stay in app-private cache until review completes. */
class LongReceiptSession(cache: File) : Closeable {
    private data class Segment(
        val file: File,
        val x: Float,
        val y: Float,
        val scale: Float,
        val seam: Float,
    )

    private data class Pending(
        val bitmap: Bitmap,
        val texture: ReceiptTexture,
        val x: Float,
        val y: Float,
        val scale: Float,
    )

    private val directory =
        File(cache, "long-receipt/${UUID.randomUUID()}").apply { check(mkdirs()) }
    private val segments = ArrayList<Segment>()
    private var previous: Bitmap? = null
    private var previousTexture: ReceiptTexture? = null
    private var pending: Pending? = null
    private var frameWidth = 0
    private var frameHeight = 0
    private var commonLeft = 0f
    private var commonRight = 0f
    private var totalHeight = 0f
    private var preview: Bitmap? = null

    private fun status(message: String, warning: Boolean = false, limit: Boolean = false) =
        ScanProgress(segments.size, ceil(totalHeight).toInt(), message, warning, limit, preview)

    /** Takes ownership of bitmap, including on rejection. Never appends an uncertain alignment. */
    fun offer(bitmap: Bitmap): ScanProgress {
        var retained = false
        try {
            require(bitmap.width <= LONG_RECEIPT_MAX_WIDTH && bitmap.height <= 2400)
            val texture = texture(bitmap)
            if (texture.energy < 18f)
                return status("Наведите на печатные строки и задержите телефон", true)
            if (previous == null) {
                frameWidth = bitmap.width
                frameHeight = bitmap.height
                commonRight = frameWidth.toFloat()
                append(Pending(bitmap, texture, 0f, 0f, 1f))
                retained = true
                return status("Начало сохранено. Плавно ведите телефон вниз")
            }
            if (bitmap.width != frameWidth || bitmap.height != frameHeight)
                return status("Верните телефон в прежнее положение и держите его вертикально", true)
            if (texture.energy < requireNotNull(previousTexture).energy * .55f)
                return status("Кадр смазан. Задержите телефон и дождитесь фокусировки", true)
            val match =
                ReceiptAlignment.match(requireNotNull(previousTexture), texture)
                    ?: return status(
                        "Не вижу совпадения. Чуть вернитесь вверх и держите телефон ровно",
                        true,
                    )
            val last = segments.last()
            val scale = last.scale * match.scale
            if (scale !in .82f..1.22f)
                return status("Сохраняйте расстояние от телефона до чека", true)
            val ratioX = frameWidth.toFloat() / texture.width
            val ratioY = frameHeight.toFloat() / texture.height
            val x =
                last.x + last.scale * ((frameWidth - 1) * (1 - match.scale) / 2 + match.dx * ratioX)
            val y =
                last.y +
                    last.scale * ((frameHeight - 1) * (1 - match.scale) / 2 + match.dy * ratioY)
            val bottom = y + frameHeight * scale
            val advance = bottom - totalHeight
            if (advance < -frameHeight * .04f) return status("Снимайте сверху вниз", true)
            if (advance < 4)
                return status("Медленно двигайтесь вниз · неподвижный кадр уже сохранён")
            if (min(commonRight, x + frameWidth * scale) - max(commonLeft, x) < frameWidth * .78f)
                return status("Чек ушёл в сторону. Верните его в центр рамки", true)
            if (bottom > LONG_RECEIPT_MAX_HEIGHT || segments.size >= MAX_SEGMENTS)
                return status("Достигнута максимальная длина. Нажмите «Готово»", limit = true)
            val candidate = Pending(bitmap, texture, x, y, scale)
            // Keep the final small advance too, so Done never cuts off the receipt's tail.
            if (advance < frameHeight * .14f) {
                val old = pending
                if (old == null || bottom > old.y + frameHeight * old.scale) {
                    old?.bitmap?.recycle()
                    pending = candidate
                    retained = true
                }
                return status("Ведите вниз, сохраняя расстояние и направление")
            }
            pending?.bitmap?.recycle()
            pending = null
            append(candidate)
            retained = true
            return status("Фрагмент добавлен · ведите дальше или нажмите «Готово»")
        } finally {
            if (!retained) bitmap.recycle()
        }
    }

    private fun append(next: Pending) {
        val seam = if (segments.isEmpty()) 0f else chooseSeam(next)
        val file = File(directory, "${segments.size}.jpg")
        file.outputStream().use { check(next.bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it)) }
        segments.add(Segment(file, next.x, next.y, next.scale, seam))
        commonLeft = max(commonLeft, next.x)
        commonRight = min(commonRight, next.x + frameWidth * next.scale)
        totalHeight = next.y + frameHeight * next.scale
        previous?.recycle()
        previous = next.bitmap
        previousTexture = next.texture
        preview = render(72)
    }

    /** Put the join in the lightest shared paper band, away from printed strokes. */
    private fun chooseSeam(next: Pending): Float {
        val old = segments.last()
        val center = (totalHeight + next.y) / 2
        var best = center
        var lightest = -1.0
        for (offset in -20..20 step 2) {
            val row = center + offset
            val oldY = ((row - old.y) / old.scale).roundToInt()
            val newY = ((row - next.y) / next.scale).roundToInt()
            if (oldY !in 1 until frameHeight - 1 || newY !in 1 until frameHeight - 1) continue
            var light = 0.0
            var samples = 0
            for (x in frameWidth / 10 until frameWidth * 9 / 10 step 7) {
                val worldX = next.x + x * next.scale
                val oldX = ((worldX - old.x) / old.scale).roundToInt()
                if (oldX !in 0 until frameWidth) continue
                for (dy in -1..1) {
                    light +=
                        min(
                            luma(requireNotNull(previous).getPixel(oldX, oldY + dy)),
                            luma(next.bitmap.getPixel(x, newY + dy)),
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
        check(segments.isNotEmpty()) { "Сначала захватите начало чека" }
        pending?.let {
            pending = null
            append(it)
        }
        val bitmap = render((commonRight - commonLeft).toInt())
        val output = File(directory, "receipt.jpg")
        try {
            output.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 94, it)) }
            check(output.length() <= 15L * 1024 * 1024) {
                "Чек слишком большой. Снимите его двумя частями"
            }
        } finally {
            bitmap.recycle()
        }
        return output
    }

    private fun render(width: Int): Bitmap {
        val factor = width / (commonRight - commonLeft)
        val height = ceil(totalHeight * factor).toInt().coerceAtLeast(1)
        val result = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
        val canvas = Canvas(result)
        canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        for ((index, segment) in segments.withIndex()) {
            val options =
                BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.RGB_565
                    while (frameWidth / (inSampleSize.coerceAtLeast(1) * 2) > width) {
                        inSampleSize = inSampleSize.coerceAtLeast(1) * 2
                    }
                }
            val source = requireNotNull(BitmapFactory.decodeFile(segment.file.path, options))
            try {
                val save = canvas.save()
                canvas.clipRect(
                    0f,
                    segment.seam * factor,
                    width.toFloat(),
                    (segments.getOrNull(index + 1)?.seam ?: totalHeight) * factor,
                )
                canvas.drawBitmap(
                    source,
                    null,
                    RectF(
                        (segment.x - commonLeft) * factor,
                        segment.y * factor,
                        (segment.x - commonLeft + frameWidth * segment.scale) * factor,
                        (segment.y + frameHeight * segment.scale) * factor,
                    ),
                    paint,
                )
                canvas.restoreToCount(save)
            } finally {
                source.recycle()
            }
        }
        return result
    }

    override fun close() {
        previous?.recycle()
        previous = null
        previousTexture = null
        pending?.bitmap?.recycle()
        pending = null
        directory.deleteRecursively()
    }

    companion object {
        fun texture(bitmap: Bitmap): ReceiptTexture {
            val width = 256.coerceAtMost(bitmap.width)
            val height = (bitmap.height * width.toFloat() / bitmap.width).roundToInt()
            val small = Bitmap.createScaledBitmap(bitmap, width, height, true)
            val pixels = IntArray(width * height)
            small.getPixels(pixels, 0, width, 0, 0, width, height)
            if (small !== bitmap) small.recycle()
            return ReceiptTexture.fromGray(
                width,
                height,
                IntArray(pixels.size) { luma(pixels[it]) },
            )
        }

        private fun luma(color: Int) =
            (Color.red(color) * 77 + Color.green(color) * 150 + Color.blue(color) * 29) shr 8
    }
}
