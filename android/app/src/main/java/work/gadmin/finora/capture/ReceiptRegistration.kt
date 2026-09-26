package work.gadmin.finora.capture

import android.graphics.Bitmap
import java.io.Closeable
import kotlin.math.hypot
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.calib3d.Calib3d
import org.opencv.core.*
import org.opencv.features2d.BFMatcher
import org.opencv.features2d.ORB
import org.opencv.imgproc.Imgproc

/** Native feature registration; owned by the stitching worker, never the camera callback. */
class ReceiptRegistration : Closeable {
    class Features(
        val points: List<Point>,
        val descriptors: Mat,
        val paper: android.graphics.RectF,
        val octaves: IntArray,
    ) : Closeable {
        override fun close() = descriptors.release()
    }

    private val detector: ORB
    private val matcher: BFMatcher

    init {
        check(OpenCVLoader.initLocal()) { "Не удалось загрузить обработку панорамы" }
        detector = ORB.create(1800, 1.2f, 8, 15, 0, 2, ORB.HARRIS_SCORE, 31, 10)
        matcher = BFMatcher.create(Core.NORM_HAMMING, false)
    }

    fun features(bitmap: Bitmap): Features {
        val rgba = Mat()
        val gray = Mat()
        var mask: Mat? = null
        val inverted = Mat()
        val keys = MatOfKeyPoint()
        val descriptors = Mat()
        val width = minOf(720, bitmap.width)
        val small =
            Bitmap.createScaledBitmap(bitmap, width, bitmap.height * width / bitmap.width, true)
        try {
            Utils.bitmapToMat(small, rgba)
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
            mask = ReceiptPaper.mask(gray)
            detector.detectAndCompute(gray, mask, keys, descriptors)
            val sx = bitmap.width.toDouble() / small.width
            val sy = bitmap.height.toDouble() / small.height
            val bounds = Imgproc.boundingRect(mask)
            // The original resolution is retained. Only the background is removed; faint ink
            // keeps its gray levels instead of destructive black/white thresholding.
            Core.bitwise_not(mask, inverted)
            val fullGray = Mat()
            val fullMask = Mat()
            try {
                Utils.bitmapToMat(bitmap, rgba)
                Imgproc.cvtColor(rgba, fullGray, Imgproc.COLOR_RGBA2GRAY)
                Imgproc.resize(
                    inverted,
                    fullMask,
                    Size(bitmap.width.toDouble(), bitmap.height.toDouble()),
                    0.0,
                    0.0,
                    Imgproc.INTER_NEAREST,
                )
                fullGray.setTo(Scalar(255.0), fullMask)
                Imgproc.cvtColor(fullGray, rgba, Imgproc.COLOR_GRAY2RGBA)
                Utils.matToBitmap(rgba, bitmap)
            } finally {
                fullGray.release()
                fullMask.release()
            }
            return Features(
                keys.toList().map { Point(it.pt.x * sx, it.pt.y * sy) },
                descriptors,
                android.graphics.RectF(
                    (bounds.x * sx).toFloat(),
                    (bounds.y * sy).toFloat(),
                    ((bounds.x + bounds.width) * sx).toFloat(),
                    ((bounds.y + bounds.height) * sy).toFloat(),
                ),
                keys.toList().map { it.octave }.toIntArray(),
            )
        } catch (error: Exception) {
            descriptors.release()
            throw error
        } finally {
            rgba.release()
            gray.release()
            mask?.release()
            inverted.release()
            keys.release()
            if (small !== bitmap) small.recycle()
        }
    }

    fun match(previous: Features, next: Features, width: Int, height: Int): ReceiptTransform? {
        if (previous.points.size < 16 || next.points.size < 16) return null
        val forward = ArrayList<MatOfDMatch>()
        val backward = ArrayList<MatOfDMatch>()
        val source = MatOfPoint2f()
        val target = MatOfPoint2f()
        val mask = Mat()
        var homography: Mat? = null
        try {
            matcher.knnMatch(next.descriptors, previous.descriptors, forward, 2)
            matcher.knnMatch(previous.descriptors, next.descriptors, backward, 2)
            fun distinctive(matches: List<MatOfDMatch>): Map<Int, DMatch> =
                matches
                    .mapNotNull { row ->
                        val pair = row.toArray()
                        if (
                            pair.size == 2 &&
                                pair[0].distance < pair[1].distance * .78f &&
                                pair[0].distance < 64
                        )
                            pair[0]
                        else null
                    }
                    .associateBy { it.queryIdx }
            val reverse = distinctive(backward)
            val pairs =
                distinctive(forward).values.filter { reverse[it.trainIdx]?.trainIdx == it.queryIdx }
            if (pairs.size < 16) return null
            source.fromList(pairs.map { next.points[it.queryIdx] })
            target.fromList(pairs.map { previous.points[it.trainIdx] })
            homography =
                Calib3d.findHomography(source, target, Calib3d.RANSAC, 2.8, mask, 1500, .995)
            if (homography.empty()) return null
            val inliers = pairs.indices.filter { mask.get(it, 0)[0] != 0.0 }
            if (inliers.size < 14 || inliers.size < pairs.size * .5) return null
            val points = inliers.map { next.points[pairs[it].queryIdx] }
            if (
                points.maxOf { it.x } - points.minOf { it.x } <
                    minOf(previous.paper.width(), next.paper.width()) * .30 ||
                    points.maxOf { it.y } - points.minOf { it.y } < height * .12
            )
                return null
            // Prefer exact translation when the inliers agree: an unnecessary projective
            // fit introduces tiny scale errors that accumulate over a long printed page.
            val offsets = inliers.map { index ->
                val pair = pairs[index]
                val a = previous.points[pair.trainIdx]
                val b = next.points[pair.queryIdx]
                Point(a.x - b.x, a.y - b.y)
            }
            val fullResolution = inliers.mapNotNull { index ->
                val pair = pairs[index]
                if (previous.octaves[pair.trainIdx] == 0 && next.octaves[pair.queryIdx] == 0) {
                    val a = previous.points[pair.trainIdx]
                    val b = next.points[pair.queryIdx]
                    Point(a.x - b.x, a.y - b.y)
                } else null
            }
            val precise = fullResolution.takeIf { it.size >= 12 } ?: offsets
            val dx = precise.map { it.x }.sorted()[precise.size / 2]
            val dy = precise.map { it.y }.sorted()[precise.size / 2]
            val errors = offsets.map { hypot(it.x - dx, it.y - dy) }.sorted()
            val exactSupport =
                fullResolution.size >= 12 &&
                    fullResolution.count { hypot(it.x - dx, it.y - dy) < .15 } >=
                        fullResolution.size * .85
            if (
                (errors[errors.size / 2] < .35 && errors[(errors.size * .85).toInt()] < 1.0) ||
                    (exactSupport && errors[(errors.size * .85).toInt()] < 2.0)
            ) {
                val translation =
                    ReceiptTransform(
                        floatArrayOf(1f, 0f, dx.toFloat(), 0f, 1f, dy.toFloat(), 0f, 0f, 1f)
                    )
                if (translation.plausible(width, height)) return translation
            }
            val data = DoubleArray(9)
            homography.get(0, 0, data)
            val transform = ReceiptTransform(FloatArray(9) { data[it].toFloat() })
            return transform.takeIf { it.plausible(width, height) }
        } finally {
            forward.forEach { it.release() }
            backward.forEach { it.release() }
            source.release()
            target.release()
            mask.release()
            homography?.release()
        }
    }

    override fun close() {
        detector.clear()
        matcher.clear()
    }
}
