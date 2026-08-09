package com.arrowsbot.vision

import android.graphics.Bitmap
import android.graphics.Color
import com.arrowsbot.model.ScreenState

class ScreenStateClassifier {
    fun classify(bitmap: Bitmap, arrowCount: Int, accessibilityText: String = ""): Pair<ScreenState, Float> {
        val text = accessibilityText.lowercase()
        if (listOf("play again", "spielen", "weiter", "next level").any(text::contains)) {
            return ScreenState.PLAY_AGAIN to 0.94f
        }
        if (listOf("level complete", "completed", "geschafft", "gewonnen").any(text::contains)) {
            return ScreenState.LEVEL_COMPLETE to 0.92f
        }
        if (listOf("advertisement", "werbung", "skip ad", "überspringen").any(text::contains)) {
            return ScreenState.ADVERTISEMENT to 0.96f
        }
        if (arrowCount >= 1) return ScreenState.GAME to 0.78f

        // A conservative visual fallback: ad screens are often dominated by a
        // nearly uniform full-screen panel. Uniformity alone never enables taps.
        val sample = sampleVariance(bitmap)
        return if (sample < 900f) ScreenState.ADVERTISEMENT to 0.45f
        else ScreenState.UNKNOWN to 0.2f
    }

    private fun sampleVariance(bitmap: Bitmap): Float {
        val stepX = (bitmap.width / 24).coerceAtLeast(1)
        val stepY = (bitmap.height / 24).coerceAtLeast(1)
        var count = 0
        var sum = 0f
        var sumSq = 0f
        var y = 0
        while (y < bitmap.height) {
            var x = 0
            while (x < bitmap.width) {
                val pixel = bitmap.getPixel(x, y)
                val luminance = (0.2126f * Color.red(pixel) + 0.7152f * Color.green(pixel) + 0.0722f * Color.blue(pixel))
                sum += luminance
                sumSq += luminance * luminance
                count++
                x += stepX
            }
            y += stepY
        }
        val mean = sum / count.coerceAtLeast(1)
        return sumSq / count.coerceAtLeast(1) - mean * mean
    }
}