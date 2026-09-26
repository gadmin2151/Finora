package work.gadmin.finora.capture

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** A small, illumination-normalized registration image. Contains no Android state. */
class ReceiptTexture(val width: Int, val height: Int, val values: FloatArray) {
    init {
        require(width >= 32 && height >= 32 && values.size == width * height)
    }

    val energy: Float = values.sumOf { (it * it).toDouble() }.div(values.size).toFloat()

    fun reduced(): ReceiptTexture {
        val w = width / 2
        val h = height / 2
        return ReceiptTexture(
            w,
            h,
            FloatArray(w * h) { i ->
                val x = (i % w) * 2
                val y = (i / w) * 2
                (values[y * width + x] +
                    values[y * width + x + 1] +
                    values[(y + 1) * width + x] +
                    values[(y + 1) * width + x + 1]) / 4f
            },
        )
    }

    companion object {
        /** Local contrast removes smooth paper/shadow gradients, keeping printed details. */
        fun fromGray(width: Int, height: Int, gray: IntArray): ReceiptTexture {
            require(gray.size == width * height)
            val integral = IntArray((width + 1) * (height + 1))
            for (y in 0 until height) {
                var row = 0
                for (x in 0 until width) {
                    row += gray[y * width + x]
                    integral[(y + 1) * (width + 1) + x + 1] =
                        integral[y * (width + 1) + x + 1] + row
                }
            }
            val values = FloatArray(gray.size)
            for (y in 0 until height) for (x in 0 until width) {
                val l = (x - 4).coerceAtLeast(0)
                val r = (x + 5).coerceAtMost(width)
                val t = (y - 4).coerceAtLeast(0)
                val b = (y + 5).coerceAtMost(height)
                val sum =
                    integral[b * (width + 1) + r] -
                        integral[t * (width + 1) + r] -
                        integral[b * (width + 1) + l] + integral[t * (width + 1) + l]
                values[y * width + x] = gray[y * width + x] - sum.toFloat() / ((r - l) * (b - t))
            }
            return ReceiptTexture(width, height, values)
        }
    }
}

data class ReceiptMatch(val dx: Float, val dy: Float, val scale: Float, val confidence: Float)

/** Constrained paper-pan registration. Uncertain/repeated/blank patterns fail closed. */
object ReceiptAlignment {
    private data class Candidate(val dx: Float, val dy: Float, val scale: Float, val score: Float)

    fun match(previous: ReceiptTexture, next: ReceiptTexture): ReceiptMatch? {
        if (previous.width != next.width || previous.height != next.height) return null
        if (previous.width < 64 || previous.height < 64) return null
        if (minOf(previous.energy, next.energy) < 18f) return null
        val a = previous.reduced()
        val b = next.reduced()
        val candidates = ArrayList<Candidate>()
        val horizontal = (a.width * .075f).roundToInt()
        for (dy in -(a.height / 6)..(a.height * .60f).roundToInt()) {
            var best = Candidate(0f, dy.toFloat(), 1f, -1f)
            for (dx in -horizontal..horizontal) {
                val score = correlation(a, b, dx.toFloat(), dy.toFloat(), 1f, 2)
                if (score > best.score) best = Candidate(dx.toFloat(), dy.toFloat(), 1f, score)
            }
            candidates.add(best)
        }
        val peaks = ArrayList<Candidate>()
        for (candidate in candidates.sortedByDescending { it.score }) {
            if (candidate.score < .30f) break
            if (peaks.none { abs(it.dy - candidate.dy) < a.height * .055f }) peaks.add(candidate)
            if (peaks.size == 4) break
        }
        if (peaks.isEmpty()) return null
        val refined =
            peaks
                .map { coarse ->
                    var best = Candidate(coarse.dx * 2, coarse.dy * 2, 1f, -1f)
                    for (dy in (coarse.dy * 2).roundToInt() - 3..(coarse.dy * 2).roundToInt() + 3) {
                        for (dx in
                            (coarse.dx * 2).roundToInt() - 3..(coarse.dx * 2).roundToInt() + 3) {
                            val score =
                                correlation(previous, next, dx.toFloat(), dy.toFloat(), 1f, 2)
                            if (score > best.score)
                                best = Candidate(dx.toFloat(), dy.toFloat(), 1f, score)
                        }
                    }
                    val center = best
                    for (scale in listOf(.97f, .985f, 1f, 1.015f, 1.03f)) {
                        for (dy in -2..2) for (dx in -2..2) {
                            val x = center.dx + dx * .5f
                            val y = center.dy + dy * .5f
                            val score = correlation(previous, next, x, y, scale, 2)
                            if (score > best.score) best = Candidate(x, y, scale, score)
                        }
                    }
                    val subpixel = best
                    for (dy in -1..1) for (dx in -1..1) {
                        val x = subpixel.dx + dx * .25f
                        val y = subpixel.dy + dy * .25f
                        val score = correlation(previous, next, x, y, subpixel.scale, 2)
                        if (score > best.score) best = Candidate(x, y, subpixel.scale, score)
                    }
                    best
                }
                .sortedByDescending { it.score }
        val best = refined.first()
        if (best.score < .70f) return null
        // Compare full-resolution alternatives: similar printed rows can alias in the small search.
        if (refined.drop(1).any { it.score > .65f && best.score - it.score < .035f }) return null
        // A tilted/warped page often agrees on only one side. Both must agree.
        for (side in 0..1) {
            if (correlation(previous, next, best.dx, best.dy, best.scale, 2, side) < .56f)
                return null
        }
        return ReceiptMatch(best.dx, best.dy, best.scale, best.score)
    }

    private fun correlation(
        a: ReceiptTexture,
        b: ReceiptTexture,
        dx: Float,
        dy: Float,
        scale: Float,
        step: Int,
        side: Int? = null,
    ): Float {
        val cx = (a.width - 1) / 2f
        val cy = (a.height - 1) / 2f
        val margin = a.width / 10
        val left = if (side == 1) a.width / 2 else margin
        val right = if (side == 0) a.width / 2 else a.width - margin
        var dot = 0.0
        var aa = 0.0
        var bb = 0.0
        var count = 0
        for (y in 4 until b.height - 4 step step) {
            val sy = (y - cy) * scale + cy + dy
            val iy = sy.toInt()
            if (sy < 2 || iy >= a.height - 3) continue
            val fy = sy - iy
            for (x in left until right step step) {
                val sx = (x - cx) * scale + cx + dx
                val ix = sx.toInt()
                if (sx < 2 || ix >= a.width - 3) continue
                val fx = sx - ix
                val top =
                    a.values[iy * a.width + ix] * (1 - fx) + a.values[iy * a.width + ix + 1] * fx
                val bottom =
                    a.values[(iy + 1) * a.width + ix] * (1 - fx) +
                        a.values[(iy + 1) * a.width + ix + 1] * fx
                val av = top * (1 - fy) + bottom * fy
                val bv = b.values[y * b.width + x]
                dot += av * bv
                aa += av * av
                bb += bv * bv
                count++
            }
        }
        if (count < 300 || aa / count < 12 || bb / count < 12) return -1f
        return (dot / sqrt(aa * bb)).toFloat()
    }
}
