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
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(15.0, 15.0))
        val output = Mat.zeros(gray.rows(), gray.cols(), CvType.CV_8UC1)
        try {
            Imgproc.GaussianBlur(gray, smooth, Size(5.0, 5.0), 0.0)
            val threshold =
                Imgproc.threshold(
                    smooth,
                    binary,
                    0.0,
                    255.0,
                    Imgproc.THRESH_BINARY or Imgproc.THRESH_OTSU,
                )
            // The paper's upper quartile excludes ink without mistaking a gray desk for paper.
            val pixels = ByteArray(smooth.total().toInt())
            smooth.get(0, 0, pixels)
            val histogram = IntArray(256)
            var samples = 0
            for (y in gray.rows() / 10 until gray.rows() * 9 / 10 step 3) {
                for (x in gray.cols() * 3 / 10 until gray.cols() * 7 / 10 step 3) {
                    histogram[pixels[y * gray.cols() + x].toInt() and 255]++
                    samples++
                }
            }
            var cumulative = 0
            val paperTone =
                histogram.indices.firstOrNull {
                    cumulative += histogram[it]
                    cumulative >= samples * .75
                } ?: 255
            Imgproc.threshold(
                smooth,
                binary,
                maxOf(95.0, threshold, paperTone * .94),
                255.0,
                Imgproc.THRESH_BINARY,
            )
            Imgproc.morphologyEx(binary, binary, Imgproc.MORPH_CLOSE, kernel)
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
            return output
        } catch (error: Exception) {
            output.release()
            throw error
        } finally {
            smooth.release()
            binary.release()
            hierarchy.release()
            kernel.release()
            contours.forEach { it.release() }
        }
    }
}
