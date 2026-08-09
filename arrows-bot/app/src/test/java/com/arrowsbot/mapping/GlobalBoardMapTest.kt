package com.arrowsbot.mapping

import android.graphics.PointF
import android.graphics.RectF
import com.arrowsbot.model.ArrowDirection
import com.arrowsbot.model.DetectedArrow
import org.junit.Assert.assertEquals
import org.junit.Test

class GlobalBoardMapTest {
    @Test
    fun sameArrowFromTwoViewsIsDeduplicated() {
        val map = GlobalBoardMap()
        val view = Viewport(1000, 1000, PointF(0f, 0f))
        val arrow = DetectedArrow(
            "a", PointF(400f, 400f), RectF(380f, 380f, 420f, 420f),
            ArrowDirection.UP, 0.9f, 1L,
        )
        map.recordView(listOf(arrow), view)
        map.recordView(listOf(arrow.copy(id = "a2", confidence = 0.95f)), view)
        assertEquals(1, map.allArrows().size)
    }
}