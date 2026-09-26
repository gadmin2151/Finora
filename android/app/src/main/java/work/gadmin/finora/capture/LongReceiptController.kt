package work.gadmin.finora.capture

import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import androidx.camera.core.ImageProxy
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt

const val SCAN_WINDOW_WIDTH = .86f
const val SCAN_WINDOW_HEIGHT = .46f

const val RECEIPT_FRAME_INTERVAL_MS = 300L
private const val MAX_PENDING_FRAMES = 3

/** Camera acquisition and live stitching use independent, bounded workers. */
class LongReceiptController(
    private val cache: File,
    private val main: Executor,
    private val onProgress: (ScanProgress) -> Unit,
    private val onResult: (File) -> Unit,
    private val onError: (String) -> Unit,
    private val onCaptured: (Int) -> Unit = {},
) : AutoCloseable {
    val executor = Executors.newSingleThreadExecutor()
    private val stitching = Executors.newSingleThreadExecutor()
    private val pendingFrames = AtomicInteger(0)
    private val capturedFrames = AtomicInteger(0)
    private val recording = AtomicBoolean(false)
    private val disposed = AtomicBoolean(false)
    private var current: LongReceiptSession? = null

    private fun session() = current ?: LongReceiptSession(cache).also { current = it }

    private var lastFrameAt = 0L

    fun start() {
        recording.set(true)
    }

    fun pause() {
        recording.set(false)
    }

    fun analyze(frame: ImageProxy) {
        var bitmap: Bitmap? = null
        try {
            val now = SystemClock.elapsedRealtime()
            if (
                disposed.get() ||
                    !recording.get() ||
                    now - lastFrameAt < RECEIPT_FRAME_INTERVAL_MS ||
                    pendingFrames.get() >= MAX_PENDING_FRAMES
            )
                return
            lastFrameAt = now
            bitmap = cropFrame(frame)
        } catch (_: Exception) {
            pause()
            dispatch { onError("Не удалось прочитать кадр. Попробуйте начать съёмку заново.") }
        } finally {
            frame.close()
        }
        bitmap?.let(::submitCapturedFrame)
    }

    /** Transfers ownership to the stitcher; bounded buffering keeps CameraX responsive. */
    internal fun submitCapturedFrame(image: Bitmap) {
        if (disposed.get()) {
            image.recycle()
            return
        }
        if (pendingFrames.incrementAndGet() > MAX_PENDING_FRAMES) {
            pendingFrames.decrementAndGet()
            image.recycle()
            return
        }
        val count = capturedFrames.incrementAndGet()
        dispatch { onCaptured(count) }
        stitching.execute {
            try {
                if (disposed.get()) {
                    image.recycle()
                    return@execute
                }
                val result = session().offer(image)
                if (result.limitReached) pause()
                dispatch { onProgress(result) }
            } catch (error: Exception) {
                android.util.Log.w("FinoraPanorama", "Frame processing failed", error)
                if (!image.isRecycled) image.recycle()
                pause()
                dispatch {
                    onError(
                        "Не удалось сохранить фрагмент. Проверьте свободное место и переснимите чек."
                    )
                }
            } finally {
                pendingFrames.decrementAndGet()
            }
        }
    }

    fun finish() {
        pause()
        executor.execute {
            stitching.execute {
                try {
                    val file = session().finish()
                    dispatch { onResult(file) }
                } catch (error: Exception) {
                    dispatch {
                        onError(error.message ?: "Не удалось собрать чек. Попробуйте ещё раз.")
                    }
                }
            }
        }
    }

    fun reset() {
        pause()
        executor.execute {
            lastFrameAt = 0
            stitching.execute {
                current?.close()
                current = null
                capturedFrames.set(0)
                dispatch { onCaptured(0) }
                dispatch { onProgress(ScanProgress()) }
            }
        }
    }

    fun copyForDraft(file: File, onPhoto: (File) -> Unit) {
        stitching.execute {
            var output: File? = null
            try {
                output = File.createTempFile("finora-long-", ".jpg", cache)
                file.copyTo(output, overwrite = true)
                val copy = output
                main.execute {
                    if (disposed.get()) copy.delete() else onPhoto(copy)
                }
            } catch (_: Exception) {
                output?.delete()
                dispatch { onError("Не удалось сохранить снимок в черновик. Попробуйте ещё раз.") }
            }
        }
    }

    private fun dispatch(action: () -> Unit) {
        main.execute { if (!disposed.get()) action() }
    }

    override fun close() {
        if (!disposed.compareAndSet(false, true)) return
        pause()
        executor.execute {
            stitching.execute {
                current?.close()
                current = null
            }
            stitching.shutdown()
        }
        executor.shutdown()
    }

    companion object {
        /** ImageProxy.cropRect is shared with the preview through CameraX ViewPort. */
        fun cropFrame(frame: ImageProxy): Bitmap {
            require(frame.format == android.graphics.ImageFormat.YUV_420_888)
            val rect = frame.cropRect
            val degrees = frame.imageInfo.rotationDegrees
            val sideways = degrees == 90 || degrees == 270
            val width =
                (rect.width() * if (sideways) SCAN_WINDOW_HEIGHT else SCAN_WINDOW_WIDTH)
                    .roundToInt()
            val height =
                (rect.height() * if (sideways) SCAN_WINDOW_WIDTH else SCAN_WINDOW_HEIGHT)
                    .roundToInt()
            val left = rect.left + (rect.width() - width) / 2
            val top = rect.top + (rect.height() - height) / 2
            // Paper needs luminance only. Copy the selected window directly from YUV;
            // converting a complete 4K frame to RGBA for every preview frame wastes RAM/CPU.
            val plane = frame.planes[0]
            val buffer = plane.buffer.duplicate()
            val base = buffer.position()
            val row = ByteArray((width - 1) * plane.pixelStride + 1)
            val pixels = IntArray(width * height)
            for (y in 0 until height) {
                buffer.position(base + (top + y) * plane.rowStride + left * plane.pixelStride)
                buffer.get(row)
                for (x in 0 until width) {
                    val gray = row[x * plane.pixelStride].toInt() and 255
                    pixels[y * width + x] = (255 shl 24) or (gray shl 16) or (gray shl 8) or gray
                }
            }
            val raw = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
            var oriented: Bitmap? = null
            var result: Bitmap? = null
            try {
                val rotation = Matrix().apply { postRotate(degrees.toFloat()) }
                oriented = Bitmap.createBitmap(raw, 0, 0, width, height, rotation, false)
                val factor =
                    minOf(
                        1f,
                        LONG_RECEIPT_MAX_WIDTH.toFloat() / oriented.width,
                        2400f / oriented.height,
                    )
                result =
                    Bitmap.createScaledBitmap(
                        oriented,
                        (oriented.width * factor).roundToInt(),
                        (oriented.height * factor).roundToInt(),
                        true,
                    )
                return result
            } finally {
                if (oriented !== result) oriented?.recycle()
                if (raw !== oriented && raw !== result) raw.recycle()
            }
        }
    }
}
