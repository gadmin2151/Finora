package work.gadmin.finora.capture

import kotlin.math.abs
import kotlin.math.hypot

data class ReceiptPoint(val x: Float, val y: Float)

/** Maps camera pixels into the first frame's plane, including handheld perspective changes. */
class ReceiptTransform(values: FloatArray = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)) {
    val values = values.copyOf()

    init {
        require(values.size == 9 && values.all { it.isFinite() })
    }

    fun map(x: Float, y: Float): ReceiptPoint {
        val v = values
        val z = v[6] * x + v[7] * y + v[8]
        return ReceiptPoint((v[0] * x + v[1] * y + v[2]) / z, (v[3] * x + v[4] * y + v[5]) / z)
    }

    operator fun times(other: ReceiptTransform): ReceiptTransform {
        val result = FloatArray(9)
        for (row in 0..2) for (col in 0..2) {
            result[row * 3 + col] =
                (0..2)
                    .sumOf {
                        (values[row * 3 + it] * other.values[it * 3 + col]).toDouble()
                    }
                    .toFloat()
        }
        val scale = result[8]
        require(abs(scale) > 1e-6f)
        return ReceiptTransform(FloatArray(9) { result[it] / scale })
    }

    fun inverse(): ReceiptTransform? {
        val a = values
        val v =
            floatArrayOf(
                a[4] * a[8] - a[5] * a[7],
                a[2] * a[7] - a[1] * a[8],
                a[1] * a[5] - a[2] * a[4],
                a[5] * a[6] - a[3] * a[8],
                a[0] * a[8] - a[2] * a[6],
                a[2] * a[3] - a[0] * a[5],
                a[3] * a[7] - a[4] * a[6],
                a[1] * a[6] - a[0] * a[7],
                a[0] * a[4] - a[1] * a[3],
            )
        val determinant = a[0] * v[0] + a[1] * v[3] + a[2] * v[6]
        if (abs(determinant) < 1e-6f) return null
        return ReceiptTransform(FloatArray(9) { v[it] / determinant })
    }

    fun corners(width: Int, height: Int): List<ReceiptPoint> =
        listOf(
            map(0f, 0f),
            map(width.toFloat(), 0f),
            map(width.toFloat(), height.toFloat()),
            map(0f, height.toFloat()),
        )

    fun plausible(width: Int, height: Int): Boolean {
        val p = corners(width, height)
        if (p.any { !it.x.isFinite() || !it.y.isFinite() }) return false
        val c = map(width / 2f, height / 2f)
        if (
            abs(c.x - width / 2f) > width * .22f ||
                c.y - height / 2f !in -height * .25f..height * .72f
        )
            return false
        for (i in 0..3) {
            val a = p[i]
            val b = p[(i + 1) % 4]
            val length = hypot(b.x - a.x, b.y - a.y)
            val expected = if (i % 2 == 0) width else height
            if (length / expected !in .72f..1.40f) return false
        }
        return p[1].x > p[0].x &&
            p[2].x > p[3].x &&
            p[3].y > p[0].y &&
            p[2].y > p[1].y &&
            abs(p[0].y - p[1].y) < height * .16f &&
            abs(p[2].y - p[3].y) < height * .16f
    }
}
