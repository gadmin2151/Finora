package work.gadmin.finora.capture

import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import androidx.camera.core.ImageProxy
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

const val SCAN_WINDOW_WIDTH = .86f
const val SCAN_WINDOW_HEIGHT = .46f

/** Camera frames, registration, JPEG I/O and cleanup all use a single bounded worker. */
class LongReceiptController(
    private val cache: File,
    private val main: Executor,
    private val onProgress: (ScanProgress) -> Unit,
    private val onResult: (File) -> Unit,
    private val onError: (String) -> Unit,
) : AutoCloseable {
    val executor = Executors.newSingleThreadExecutor()
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
            if (disposed.get() || !recording.get() || now - lastFrameAt < 280) return
            lastFrameAt = now
            bitmap = cropFrame(frame)
        } catch (_: Exception) {
            pause()
            dispatch { onError("Не удалось прочитать кадр. Попробуйте начать съёмку заново.") }
        } finally {
            frame.close()
        }
        bitmap?.let { image ->
            try {
                val result = session().offer(image)
                if (result.limitReached) pause()
                dispatch { onProgress(result) }
            } catch (_: Exception) {
                pause()
                dispatch {
                    onError(
                        "Не удалось сохранить фрагмент. Проверьте свободное место и переснимите чек."
                    )
                }
            }
        }
    }

    fun finish() {
        pause()
        executor.execute {
            try {
                val file = session().finish()
                dispatch { onResult(file) }
            } catch (error: Exception) {
                dispatch { onError(error.message ?: "Не удалось собрать чек. Попробуйте ещё раз.") }
            }
        }
    }

    fun reset() {
        pause()
        executor.execute {
            current?.close()
            current = null
            lastFrameAt = 0
            dispatch { onProgress(ScanProgress()) }
        }
    }

    fun copyForDraft(file: File, onPhoto: (File) -> Unit) {
        executor.execute {
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
            current?.close()
            current = null
        }
        executor.shutdown()
    }

    companion object {
        /** ImageProxy.cropRect is shared with the preview through CameraX ViewPort. */
        fun cropFrame(frame: ImageProxy): Bitmap {
            val raw = frame.toBitmap()
            var oriented: Bitmap? = null
            var crop: Bitmap? = null
            var result: Bitmap? = null
            try {
                val rect = frame.cropRect
                val rotation =
                    Matrix().apply { postRotate(frame.imageInfo.rotationDegrees.toFloat()) }
                oriented =
                    Bitmap.createBitmap(
                        raw,
                        rect.left,
                        rect.top,
                        rect.width(),
                        rect.height(),
                        rotation,
                        true,
                    )
                val width = (oriented.width * SCAN_WINDOW_WIDTH).roundToInt()
                val height = (oriented.height * SCAN_WINDOW_HEIGHT).roundToInt()
                crop =
                    Bitmap.createBitmap(
                        oriented,
                        (oriented.width - width) / 2,
                        (oriented.height - height) / 2,
                        width,
                        height,
                    )
                val factor = minOf(1f, LONG_RECEIPT_MAX_WIDTH.toFloat() / width, 2400f / height)
                result =
                    Bitmap.createScaledBitmap(
                        crop,
                        (width * factor).roundToInt(),
                        (height * factor).roundToInt(),
                        true,
                    )
                return result
            } finally {
                if (crop !== result) crop?.recycle()
                if (oriented !== crop && oriented !== result) oriented?.recycle()
                if (raw !== oriented && raw !== crop && raw !== result) raw.recycle()
            }
        }
    }
}
