package work.gadmin.finora

import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.Rect
import androidx.camera.core.ImageInfo
import androidx.camera.core.ImageProxy
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import work.gadmin.finora.capture.LongReceiptController
import work.gadmin.finora.capture.SCAN_WINDOW_HEIGHT
import work.gadmin.finora.capture.SCAN_WINDOW_WIDTH

@RunWith(AndroidJUnit4::class)
class ReceiptFrameTest {
    @Test
    fun lumaWindowPreservesDetailAcrossPaddingAndCameraRotation() {
        val bounds = Rect(10, 6, 112, 76)
        for (stride in 1..2) for (degrees in listOf(0, 90, 180, 270)) {
            val rowStride = 120 * stride + 13
            val bytes = ByteArray(5 + 80 * rowStride) { -1 }
            for (y in 0 until 80) for (x in 0 until 120) bytes[5 + y * rowStride + x * stride] =
                (x + y * 2).toByte()
            val plane =
                object : ImageProxy.PlaneProxy {
                    override fun getRowStride() = rowStride

                    override fun getPixelStride() = stride

                    override fun getBuffer(): ByteBuffer =
                        ByteBuffer.wrap(bytes).apply { position(5) }
                }
            val info =
                Proxy.newProxyInstance(
                    ImageInfo::class.java.classLoader,
                    arrayOf(ImageInfo::class.java),
                ) { _, method, _ ->
                    when (method.name) {
                        "getRotationDegrees" -> degrees
                        else -> error("Unexpected image metadata: ${method.name}")
                    }
                } as ImageInfo
            val frame =
                Proxy.newProxyInstance(
                    ImageProxy::class.java.classLoader,
                    arrayOf(ImageProxy::class.java),
                ) { _, method, _ ->
                    when (method.name) {
                        "getFormat" -> ImageFormat.YUV_420_888
                        "getCropRect" -> bounds
                        "getImageInfo" -> info
                        "getPlanes" -> arrayOf(plane)
                        else -> error("Unexpected frame access: ${method.name}")
                    }
                } as ImageProxy
            val sideways = degrees == 90 || degrees == 270
            val width =
                (bounds.width() * if (sideways) SCAN_WINDOW_HEIGHT else SCAN_WINDOW_WIDTH)
                    .roundToInt()
            val height =
                (bounds.height() * if (sideways) SCAN_WINDOW_WIDTH else SCAN_WINDOW_HEIGHT)
                    .roundToInt()
            val left = bounds.left + (bounds.width() - width) / 2
            val top = bounds.top + (bounds.height() - height) / 2
            val image = LongReceiptController.cropFrame(frame)
            try {
                assertEquals(if (sideways) height else width, image.width)
                assertEquals(if (sideways) width else height, image.height)
                for (y in 0 until image.height step 7) for (x in 0 until image.width step 7) {
                    val point =
                        when (degrees) {
                            90 -> y to height - 1 - x
                            180 -> width - 1 - x to height - 1 - y
                            270 -> width - 1 - y to x
                            else -> x to y
                        }
                    val expected = (left + point.first + (top + point.second) * 2) and 255
                    val pixel = image.getPixel(x, y)
                    assertEquals(expected, Color.red(pixel))
                    assertEquals(expected, Color.green(pixel))
                    assertEquals(expected, Color.blue(pixel))
                }
            } finally {
                image.recycle()
            }
        }
    }
}
