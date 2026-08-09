package com.arrowsbot.solver

import android.graphics.PointF
import android.graphics.RectF
import com.arrowsbot.model.ArrowDirection
import com.arrowsbot.model.DetectedArrow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class ArrowSolverTest {
    @Test
    fun blockerIsTappedBeforeArrowBehindIt() {
        val arrows = listOf(
            arrow("front", 100f, 200f, ArrowDirection.RIGHT),
            arrow("behind", 200f, 200f, ArrowDirection.RIGHT),
        )
        val result = ArrowSolver().solve(arrows, 400f, 400f)
        assertNotNull(result)
        assertEquals("front", result!!.actions.first().arrowId)
    }

    @Test
    fun diagonalDirectionIsPreservedInPlan() {
        val arrow = arrow("diagonal", 100f, 100f, ArrowDirection.DOWN_RIGHT)
        val result = ArrowSolver().solve(listOf(arrow), 400f, 400f)
        assertEquals(ArrowDirection.DOWN_RIGHT, result!!.actions.single().direction)
    }

    private fun arrow(id: String, x: Float, y: Float, direction: ArrowDirection) =
        DetectedArrow(id, PointF(x, y), RectF(x - 18, y - 18, x + 18, y + 18), direction, 0.95f, 1L)
}