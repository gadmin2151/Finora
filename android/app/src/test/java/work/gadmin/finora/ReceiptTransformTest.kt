package work.gadmin.finora

import org.junit.Assert.*
import org.junit.Test
import work.gadmin.finora.capture.ReceiptTransform

class ReceiptTransformTest {
    @Test
    fun identityPreservesCameraCoordinates() {
        val p = ReceiptTransform().map(120f, 230f)
        assertEquals(120f, p.x, .001f)
        assertEquals(230f, p.y, .001f)
        assertTrue(ReceiptTransform().plausible(720, 1000))
    }

    @Test
    fun transformationsComposeInCaptureOrder() {
        val first = ReceiptTransform(floatArrayOf(1f, 0f, 4f, 0f, 1f, 100f, 0f, 0f, 1f))
        val next = ReceiptTransform(floatArrayOf(1.02f, 0f, 3f, 0f, 1.02f, 50f, 0f, 0f, 1f))
        val p = (first * next).map(100f, 200f)
        assertEquals(109f, p.x, .001f)
        assertEquals(354f, p.y, .001f)
    }

    @Test
    fun perspectiveMappingCanBeInverted() {
        val t =
            ReceiptTransform(
                floatArrayOf(1.02f, .01f, 4f, -.015f, .99f, 75f, .00004f, -.00002f, 1f)
            )
        val mapped = t.map(375f, 820f)
        val original = requireNotNull(t.inverse()).map(mapped.x, mapped.y)
        assertEquals(375f, original.x, .001f)
        assertEquals(820f, original.y, .001f)
        assertTrue(t.plausible(720, 1000))
    }

    @Test
    fun degenerateOrDistantTransformsAreRejected() {
        assertNull(ReceiptTransform(FloatArray(9)).inverse())
        assertFalse(ReceiptTransform(FloatArray(9)).plausible(720, 1000))
        assertFalse(
            ReceiptTransform(floatArrayOf(1f, 0f, 0f, 0f, 1f, 900f, 0f, 0f, 1f))
                .plausible(720, 1000)
        )
        assertFalse(
            ReceiptTransform(floatArrayOf(-1f, 0f, 720f, 0f, 1f, 0f, 0f, 0f, 1f))
                .plausible(720, 1000)
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidValuesAreRejected() {
        ReceiptTransform(floatArrayOf(Float.NaN, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f))
    }
}
