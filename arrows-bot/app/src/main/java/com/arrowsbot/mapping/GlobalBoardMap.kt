package com.arrowsbot.mapping

import android.graphics.PointF
import com.arrowsbot.model.DetectedArrow
import kotlin.math.floor
import kotlin.math.hypot

data class Viewport(
    val width: Int,
    val height: Int,
    val offset: PointF = PointF(0f, 0f),
)

class GlobalBoardMap {
    private val arrows = LinkedHashMap<String, DetectedArrow>()
    private val visitedCells = HashSet<Pair<Int, Int>>()
    private var viewport: Viewport? = null

    fun setViewport(view: Viewport) {
        viewport = view
        visitedCells += cell(view.offset)
    }

    fun recordView(detected: List<DetectedArrow>, view: Viewport) {
        viewport = view
        visitedCells += cell(view.offset)
        detected.forEach { arrow ->
            val world = PointF(arrow.center.x + view.offset.x, arrow.center.y + view.offset.y)
            val key = nearestKey(world)
            val keyPosition = key?.worldPosition ?: key?.center
            if (key == null || keyPosition == null ||
                hypot(world.x - keyPosition.x, world.y - keyPosition.y) > minCellSize()
            ) {
                arrows[arrow.id] = arrow.copy(worldPosition = world)
            } else if (arrow.confidence > key.confidence) {
                arrows.entries.firstOrNull { it.value.id == key.id }?.let {
                    arrows[it.key] = arrow.copy(worldPosition = world)
                }
            }
        }
    }

    fun currentViewport(): Viewport? = viewport

    fun shouldExplore(view: Viewport): Boolean = cell(view.offset) !in visitedCells

    fun allArrows(): List<DetectedArrow> = arrows.values.toList()

    fun visitedCount(): Int = visitedCells.size

    fun clear() {
        arrows.clear()
        visitedCells.clear()
        viewport = null
    }

    private fun nearestKey(point: PointF): DetectedArrow? =
        arrows.values.minByOrNull { arrow ->
            val p = arrow.worldPosition ?: arrow.center
            hypot(point.x - p.x, point.y - p.y)
        }

    private fun minCellSize(): Float =
        viewport?.let { minOf(it.width, it.height) * 0.06f } ?: 48f

    private fun cell(offset: PointF): Pair<Int, Int> {
        val size = viewport?.let { minOf(it.width, it.height).coerceAtLeast(1) } ?: 1000
        return floor(offset.x / size).toInt() to floor(offset.y / size).toInt()
    }
}

class ExplorationPlanner(
    private val screenWidth: Int,
    private val screenHeight: Int,
) {
    private val insetX = screenWidth * 0.14f
    private val insetY = screenHeight * 0.20f
    private val right = screenWidth * 0.86f
    private val bottom = screenHeight * 0.82f

    /**
     * Repeated sweeps cover boards larger than one viewport. Reversing only
     * after three passes is intentional: a single back-swipe does not explore
     * a large board and the overlap is needed for map registration.
     */
    fun swipes(): List<ExplorationSwipe> = buildList {
        repeat(3) {
            add(ExplorationSwipe(right, screenHeight * 0.47f, insetX, screenHeight * 0.47f, PointF(screenWidth * 0.55f, 0f)))
        }
        repeat(6) {
            add(ExplorationSwipe(insetX, screenHeight * 0.47f, right, screenHeight * 0.47f, PointF(-screenWidth * 0.55f, 0f)))
        }
        repeat(3) {
            add(ExplorationSwipe(screenWidth * 0.52f, bottom, screenWidth * 0.20f, insetY, PointF(0f, screenHeight * 0.48f)))
        }
        repeat(6) {
            add(ExplorationSwipe(screenWidth * 0.52f, insetY, screenWidth * 0.20f, bottom, PointF(0f, -screenHeight * 0.48f)))
        }
    }
}

data class ExplorationSwipe(
    val startX: Float,
    val startY: Float,
    val endX: Float,
    val endY: Float,
    val offsetDelta: PointF,
)