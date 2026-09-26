package work.gadmin.finora.capture

import org.opencv.core.*
import org.opencv.imgproc.Imgproc

/** Finds the connected bright paper, fills its ink holes, and excludes the surrounding desk. */
object ReceiptPaper {
    fun mask(gray: Mat): Mat {
        val smooth = Mat()
        val binary = Mat()
        val hierarchy = Mat()
        val contours = ArrayList<MatOfPoint>()
        val output = Mat.zeros(gray.rows(), gray.cols(), CvType.CV_8UC1)
        try {
            Imgproc.GaussianBlur(gray, smooth, Size(5.0, 5.0), 0.0)
            // Estimate paper brightness separately along the scan. A single threshold can
            // erase a shaded footer or QR; no closing operation may join the desk to the paper.
            val pixels = ByteArray(smooth.total().toInt())
            smooth.get(0, 0, pixels)
            val selected = ByteArray(pixels.size)
            val bandHeight = 32
            val tones = ArrayList<Float>()
            for (center in 0 until gray.rows() + bandHeight step bandHeight) {
                val histogram = IntArray(256)
                var samples = 0
                for (y in
                    (center - 48).coerceAtLeast(0) until
                        (center + 49).coerceAtMost(gray.rows()) step
                        2) {
                    for (x in gray.cols() * 3 / 10 until gray.cols() * 7 / 10 step 2) {
                        histogram[pixels[y * gray.cols() + x].toInt() and 255]++
                        samples++
                    }
                }
                var cumulative = 0
                tones.add(
                    (histogram.indices.firstOrNull {
                            cumulative += histogram[it]
                            cumulative >= samples * .80
                        } ?: 255)
                        .toFloat()
                )
            }
            for (y in 0 until gray.rows()) {
                val band = y / bandHeight
                val fraction = (y % bandHeight).toFloat() / bandHeight
                val tone =
                    tones[band] * (1 - fraction) +
                        tones[minOf(band + 1, tones.lastIndex)] * fraction
                val threshold = maxOf(70f, tone * .92f)
                for (x in 0 until gray.cols()) {
                    if ((pixels[y * gray.cols() + x].toInt() and 255) >= threshold)
                        selected[y * gray.cols() + x] = -1
                }
            }
            binary.create(gray.rows(), gray.cols(), CvType.CV_8UC1)
            binary.put(0, 0, selected)
            Imgproc.findContours(
                binary,
                contours,
                hierarchy,
                Imgproc.RETR_EXTERNAL,
                Imgproc.CHAIN_APPROX_SIMPLE,
            )
            val centerX = gray.cols() / 2
            val centerY = gray.rows() / 2
            val best =
                contours.indices
                    .filter { index ->
                        val bounds = Imgproc.boundingRect(contours[index])
                        Imgproc.contourArea(contours[index]) > gray.total() * .18 &&
                            centerX in bounds.x until bounds.x + bounds.width &&
                            centerY in bounds.y until bounds.y + bounds.height
                    }
                    .maxByOrNull { Imgproc.contourArea(contours[it]) }
            if (best != null)
                Imgproc.drawContours(output, contours, best, Scalar(255.0), Imgproc.FILLED)
            trimPaperEdges(smooth, output)
            return output
        } catch (error: Exception) {
            output.release()
            throw error
        } finally {
            smooth.release()
            binary.release()
            hierarchy.release()
            contours.forEach { it.release() }
        }
    }

    /** Long paper edges exclude bright keyboard/desk patches that touch the threshold mask. */
    private fun trimPaperEdges(gray: Mat, mask: Mat) {
        val bounds = Imgproc.boundingRect(mask)
        if (bounds.width == 0) return
        val edges = Mat()
        val lines = Mat()
        try {
            Imgproc.Canny(gray, edges, 20.0, 60.0)
            Imgproc.HoughLinesP(
                edges,
                lines,
                1.0,
                Math.PI / 180,
                maxOf(30, gray.rows() / 6),
                gray.rows() * .50,
                gray.rows() * .12,
            )
            data class Edge(val x: Double, val y: Double, val slope: Double) {
                fun at(row: Double) = x + (row - y) * slope
            }
            val candidates =
                (0 until lines.rows()).mapNotNull { index ->
                    val line = lines.get(index, 0)
                    val dy = line[3] - line[1]
                    if (kotlin.math.abs(dy) < gray.rows() * .50) null
                    else {
                        val slope = (line[2] - line[0]) / dy
                        if (kotlin.math.abs(slope) < .15) Edge(line[0], line[1], slope) else null
                    }
                }
            val middle = gray.rows() / 2.0
            val margin = gray.cols() * .08
            val luminance = ByteArray(gray.total().toInt())
            gray.get(0, 0, luminance)
            fun isPaperBoundary(edge: Edge, insideDirection: Int): Boolean {
                var contrast = 0.0
                var brighterRows = 0
                var samples = 0
                for (y in gray.rows() / 10 until gray.rows() * 9 / 10 step 8) {
                    val x = edge.at(y.toDouble()).toInt()
                    if (x < 12 || x >= gray.cols() - 12) continue
                    var difference = 0.0
                    for (distance in 4..12 step 2) {
                        val inside = luminance[y * gray.cols() + x + distance * insideDirection]
                        val outside = luminance[y * gray.cols() + x - distance * insideDirection]
                        difference += (inside.toInt() and 255) - (outside.toInt() and 255)
                    }
                    difference /= 5
                    contrast += difference
                    if (difference > 8) brighterRows++
                    samples++
                }
                // Aligned letters also produce long Hough lines. Only trim when the
                // paper side is consistently brighter than the surrounding surface.
                return samples >= 12 && contrast / samples > 10 && brighterRows > samples * .55
            }
            val left =
                candidates
                    .filter {
                        it.at(middle) in bounds.x.toDouble()..bounds.x + margin &&
                            isPaperBoundary(it, 1)
                    }
                    .maxByOrNull { it.at(middle) }
            val right =
                candidates
                    .filter {
                        it.at(middle) in
                            bounds.x + bounds.width - margin..(bounds.x + bounds.width)
                                    .toDouble() && isPaperBoundary(it, -1)
                    }
                    .minByOrNull { it.at(middle) }
            if (left == null && right == null) return
            val pixels = ByteArray(mask.total().toInt())
            mask.get(0, 0, pixels)
            for (y in 0 until mask.rows()) {
                val start = ((left?.at(y.toDouble()) ?: 0.0) - 3).toInt().coerceIn(0, mask.cols())
                val end =
                    ((right?.at(y.toDouble()) ?: mask.cols().toDouble()) + 3)
                        .toInt()
                        .coerceIn(start, mask.cols())
                for (x in 0 until start) pixels[y * mask.cols() + x] = 0
                for (x in end until mask.cols()) pixels[y * mask.cols() + x] = 0
            }
            mask.put(0, 0, pixels)
        } finally {
            edges.release()
            lines.release()
        }
    }
}
