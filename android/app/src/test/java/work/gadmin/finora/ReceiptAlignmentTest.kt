package work.gadmin.finora

import kotlin.math.roundToInt
import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Test
import work.gadmin.finora.capture.ReceiptAlignment
import work.gadmin.finora.capture.ReceiptTexture

class ReceiptAlignmentTest {
    private val width = 192
    private val height = 220

    private fun paper(seed: Int = 42): IntArray {
        val random = Random(seed)
        return IntArray(width * 1000) { index ->
            val x = index % width
            val y = index / width
            if (x in 12..180 && y % 13 in 3..8 && random.nextInt(4) == 0) 35 else 235
        }
    }

    private fun frame(
        source: IntArray,
        top: Int,
        dx: Int = 0,
        brightness: Int = 0,
    ): ReceiptTexture =
        ReceiptTexture.fromGray(
            width,
            height,
            IntArray(width * height) { i ->
                source[(top + i / width) * width + (i % width + dx).coerceIn(0, width - 1)]
                    .plus(brightness)
                    .coerceIn(0, 255)
            },
        )

    @Test
    fun findsVerticalAdvanceAndSidewaysDrift() {
        val source = paper()
        val result = requireNotNull(ReceiptAlignment.match(frame(source, 0), frame(source, 63, 4)))
        assertEquals(63f, result.dy, 1f)
        assertEquals(4f, result.dx, 1f)
        assertTrue(result.confidence > .9f)
    }

    @Test
    fun stationaryFrameDoesNotInventMovement() {
        val source = paper()
        val result =
            requireNotNull(
                ReceiptAlignment.match(frame(source, 70), frame(source, 70, brightness = -20))
            )
        assertEquals(0f, result.dy, .6f)
        assertEquals(0f, result.dx, .6f)
    }

    @Test
    fun reverseMotionIsReportedWithNegativeAdvance() {
        val source = paper()
        val result = requireNotNull(ReceiptAlignment.match(frame(source, 70), frame(source, 52)))
        assertEquals(-18f, result.dy, 1f)
    }

    @Test
    fun unrelatedAndBlankFramesAreRejected() {
        assertNull(ReceiptAlignment.match(frame(paper(), 0), frame(paper(123), 270)))
        val blank = ReceiptTexture.fromGray(width, height, IntArray(width * height) { 240 })
        assertNull(ReceiptAlignment.match(blank, blank))
    }

    @Test
    fun distantNonoverlappingFrameIsRejected() {
        val source = paper()
        assertNull(ReceiptAlignment.match(frame(source, 0), frame(source, 400)))
    }

    @Test
    fun smallDistanceChangeIsAligned() {
        val source = paper()
        val scale = 1.015f
        val next =
            ReceiptTexture.fromGray(
                width,
                height,
                IntArray(width * height) { i ->
                    val x =
                        ((i % width - (width - 1) / 2f) * scale + (width - 1) / 2f)
                            .roundToInt()
                            .coerceIn(0, width - 1)
                    val y =
                        ((i / width - (height - 1) / 2f) * scale + (height - 1) / 2f + 65)
                            .roundToInt()
                    source[y * width + x]
                },
            )
        val result = requireNotNull(ReceiptAlignment.match(frame(source, 0), next))
        assertEquals(65f, result.dy, 1.5f)
        assertEquals(scale, result.scale, .016f)
    }

    @Test
    fun identicalRepeatedLinesAreAmbiguousRatherThanInventingAnOverlap() {
        val repeated =
            ReceiptTexture.fromGray(
                width,
                height,
                IntArray(width * height) { i ->
                    if (i / width % 20 in 3..7 && i % width % 13 in 2..8) 30 else 235
                },
            )
        assertNull(ReceiptAlignment.match(repeated, repeated))
    }

    @Test
    fun tinyFramesAreRejectedWithoutCrashing() {
        val small = ReceiptTexture.fromGray(32, 32, IntArray(1024) { if (it % 5 == 0) 0 else 240 })
        assertNull(ReceiptAlignment.match(small, small))
    }
}
