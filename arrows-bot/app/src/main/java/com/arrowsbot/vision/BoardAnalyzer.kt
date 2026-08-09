package com.arrowsbot.vision

import android.graphics.RectF
import com.arrowsbot.model.BoardRegion
import com.arrowsbot.model.VisionResult
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.imgproc.Imgproc

class BoardAnalyzer(
    private val detector: ArrowDetector = ArrowDetector(),
    private val classifier: ScreenStateClassifier = ScreenStateClassifier(),
) {
    fun analyze(frame: Frame, accessibilityText: String = ""): VisionResult {
        val mat = Mat()
        return try {
            Utils.bitmapToMat(frame.bitmap, mat)
            val boardRect = findBoard(mat)
            val board = boardRect?.let {
                BoardRegion(RectF(it.x.toFloat(), it.y.toFloat(), (it.x + it.width).toFloat(), (it.y + it.height).toFloat()), 0.72f)
            }
            val arrows = boardRect?.let { detector.detect(frame, it) }.orEmpty()
            val (state, stateConfidence) = classifier.classify(frame.bitmap, arrows.size, accessibilityText)
            VisionResult(board, arrows, state, minOf(stateConfidence, board?.confidence ?: stateConfidence), frame.id)
        } finally {
            mat.release()
        }
    }

    private fun findBoard(source: Mat): Rect? {
        val gray = Mat()
        val dark = Mat()
        try {
            Imgproc.cvtColor(source, gray, Imgproc.COLOR_RGBA2GRAY)
            Imgproc.threshold(gray, dark, 120.0, 255.0, Imgproc.THRESH_BINARY_INV)
            val firstBoardRow = (source.height() * 0.10f).toInt()
            var consecutive = 0
            var top = (source.height() * 0.14f).toInt()
            for (y in firstBoardRow until source.height()) {
                val row = dark.row(y)
                val darkPixels = org.opencv.core.Core.countNonZero(row)
                row.release()
                if (darkPixels >= source.width() * 0.045) consecutive++ else consecutive = 0
                if (consecutive >= 3) {
                    top = (y - 2).coerceAtLeast(0)
                    break
                }
            }
            return Rect(0, top, source.width(), source.height() - top)
        } finally {
            gray.release()
            dark.release()
        }
    }
}