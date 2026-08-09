package com.arrowsbot.vision

import android.graphics.PointF
import android.graphics.RectF
import com.arrowsbot.model.ArrowDirection
import com.arrowsbot.model.DetectedArrow
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * The game draws each arrowhead into the corridor line, so an external contour
 * search would incorrectly return the whole maze. Opening the dark-pixel mask
 * with a kernel wider than a corridor removes the long strokes and leaves the
 * compact arrowheads for shape analysis.
 */
class ArrowDetector {
    fun detect(frame: Frame, board: Rect): List<DetectedArrow> {
        val source = Mat()
        Utils.bitmapToMat(frame.bitmap, source)
        val crop = source.submat(board)
        val gray = Mat()
        val binary = Mat()
        val opened = Mat()
        // The reference screenshot has roughly 10–12 px corridor strokes at
        // 1080 px width. The kernel is deliberately wider than that stroke
        // but smaller than an arrowhead, so only the local arrowhead bulge
        // remains after opening.
        val kernelSide = ((board.width / 75).coerceIn(9, 31) or 1)
        val kernel = Imgproc.getStructuringElement(
            Imgproc.MORPH_ELLIPSE,
            Size(kernelSide.toDouble(), kernelSide.toDouble()),
        )
        val contours = ArrayList<MatOfPoint>()
        try {
            Imgproc.cvtColor(crop, gray, Imgproc.COLOR_RGBA2GRAY)
            Imgproc.GaussianBlur(gray, gray, org.opencv.core.Size(3.0, 3.0), 0.0)
            Imgproc.threshold(gray, binary, 120.0, 255.0, Imgproc.THRESH_BINARY_INV)
            Imgproc.morphologyEx(binary, opened, Imgproc.MORPH_OPEN, kernel)
            Imgproc.findContours(
                opened,
                contours,
                Mat(),
                Imgproc.RETR_EXTERNAL,
                Imgproc.CHAIN_APPROX_SIMPLE,
            )

            val result = mutableListOf<DetectedArrow>()
            contours.forEachIndexed { index, contour ->
                val bounds = Imgproc.boundingRect(contour)
                val area = Imgproc.contourArea(contour)
                if (!isPlausible(bounds, area, board)) return@forEachIndexed
                val direction = estimateDirection(contour)
                val confidence = confidence(contour, bounds, area)
                val center = PointF(
                    board.x + bounds.x + bounds.width / 2f,
                    board.y + bounds.y + bounds.height / 2f,
                )
                result += DetectedArrow(
                    id = "f${frame.id}_$index",
                    center = center,
                    bounds = RectF(
                        (board.x + bounds.x).toFloat(),
                        (board.y + bounds.y).toFloat(),
                        (board.x + bounds.x + bounds.width).toFloat(),
                        (board.y + bounds.y + bounds.height).toFloat(),
                    ),
                    direction = direction,
                    confidence = confidence,
                    sourceFrame = frame.id,
                )
            }
            return mergeNearby(result)
        } finally {
            contours.forEach { it.release() }
            binary.release()
            opened.release()
            kernel.release()
            gray.release()
            crop.release()
            source.release()
        }
    }

    private fun isPlausible(bounds: Rect, area: Double, board: Rect): Boolean {
        val minSide = minOf(bounds.width, bounds.height)
        val maxSide = maxOf(bounds.width, bounds.height)
        val fill = area / (bounds.width.toDouble() * bounds.height.toDouble()).coerceAtLeast(1.0)
        val aspect = maxSide.toFloat() / minSide.coerceAtLeast(1)
        return minSide >= 8 &&
            maxSide <= board.width / 8 &&
            aspect <= 3.2f &&
            fill in 0.20..0.96
    }

    private fun estimateDirection(contour: MatOfPoint): ArrowDirection {
        val moments = Imgproc.moments(contour)
        val cx = moments.m10 / moments.m00.coerceAtLeast(1e-6)
        val cy = moments.m01 / moments.m00.coerceAtLeast(1e-6)
        val points = contour.toArray()
        val tip = points.maxByOrNull { hypot(it.x - cx, it.y - cy) } ?: Point(cx + 1.0, cy)
        return ArrowDirection.fromAngle(atan2(tip.y - cy, tip.x - cx))
    }

    private fun confidence(contour: MatOfPoint, bounds: Rect, area: Double): Float {
        val polygon = MatOfPoint2f()
        val approx = MatOfPoint2f()
        try {
            contour.convertTo(polygon, org.opencv.core.CvType.CV_32F)
            Imgproc.approxPolyDP(polygon, approx, 0.04 * Imgproc.arcLength(polygon, true), true)
            val fill = (area / (bounds.width.toDouble() * bounds.height).coerceAtLeast(1.0)).toFloat()
            val polygonScore = if (approx.total().toDouble() in 3.0..9.0) 1f else 0.35f
            val sizeScore = (minOf(bounds.width, bounds.height) / 18f).coerceIn(0f, 1f)
            return (0.32f + fill.coerceIn(0f, 1f) * 0.28f + polygonScore * 0.25f + sizeScore * 0.15f)
                .coerceIn(0f, 1f)
        } finally {
            polygon.release()
            approx.release()
        }
    }

    private fun mergeNearby(input: List<DetectedArrow>): List<DetectedArrow> =
        input.sortedByDescending { it.confidence }.filterIndexed { index, item ->
            input.take(index).none { previous ->
                distance(item.center, previous.center) < minOf(item.bounds.width, item.bounds.height) * 0.35f
            }
        }

    private fun distance(a: PointF, b: PointF): Float = hypot(a.x - b.x, a.y - b.y)
}