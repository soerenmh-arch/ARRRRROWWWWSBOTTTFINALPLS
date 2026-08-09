package com.arrowsbot.solver

import android.graphics.PointF
import com.arrowsbot.model.ArrowDirection
import com.arrowsbot.model.DetectedArrow
import com.arrowsbot.model.PlannedTap
import kotlin.math.abs
import kotlin.math.max

data class SolverResult(
    val actions: List<PlannedTap>,
    val dependencyCount: Int,
    val confidence: Float,
)

/**
 * Models the puzzle as a precedence graph. An arrow is currently executable
 * when no remaining arrow intersects its ray toward the board edge. DFS with
 * memoized states handles ambiguous choices without making any UI action.
 */
class ArrowSolver {
    fun solve(arrows: List<DetectedArrow>, boardWidth: Float, boardHeight: Float): SolverResult? {
        if (arrows.isEmpty() || arrows.any { it.confidence < 0.65f }) return null
        val minX = arrows.minOf { (it.worldPosition ?: it.center).x }
        val minY = arrows.minOf { (it.worldPosition ?: it.center).y }
        val maxX = arrows.maxOf { (it.worldPosition ?: it.center).x }
        val maxY = arrows.maxOf { (it.worldPosition ?: it.center).y }
        val normalizedWidth = maxOf(boardWidth, maxX - minX + 100f)
        val normalizedHeight = maxOf(boardHeight, maxY - minY + 100f)
        val nodes = arrows.mapIndexed { index, arrow ->
            val point = arrow.worldPosition ?: arrow.center
            Node(index, arrow, PointF(point.x - minX + 50f, point.y - minY + 50f))
        }
        val dependencies = Array(nodes.size) { BooleanArray(nodes.size) }
        for (target in nodes) {
            for (blocker in nodes) {
                if (target.index == blocker.index) continue
                if (blocksRay(target, blocker, normalizedWidth, normalizedHeight)) {
                    dependencies[target.index][blocker.index] = true
                }
            }
        }
        val allMask = if (nodes.size >= Long.SIZE_BITS) return null else (1L shl nodes.size) - 1
        val memo = HashMap<Long, List<Int>?>()
        val sequence = search(nodes, dependencies, 0L, allMask, memo) ?: return null
        val actions = sequence.map { index ->
            val arrow = nodes[index].arrow
            PlannedTap(
                arrowId = arrow.id,
                point = arrow.worldPosition ?: arrow.center,
                direction = arrow.direction,
            )
        }
        val confidence = nodes.map { it.arrow.confidence }.average().toFloat()
        return SolverResult(actions, dependencies.sumOf { row -> row.count { it } }, confidence)
    }

    private fun search(
        nodes: List<Node>,
        dependencies: Array<BooleanArray>,
        mask: Long,
        allMask: Long,
        memo: MutableMap<Long, List<Int>?>,
    ): List<Int>? {
        if (mask == allMask) return emptyList()
        if (memo.containsKey(mask)) return memo[mask]
        val available = nodes.indices.filter { index ->
            mask and (1L shl index) == 0L &&
                dependencies[index].withIndex().none { (blocker, required) ->
                    required && mask and (1L shl blocker) == 0L
                }
        }.sortedByDescending { nodes[it].arrow.confidence }
        for (index in available) {
            val tail = search(nodes, dependencies, mask or (1L shl index), allMask, memo)
            if (tail != null) {
                val result = listOf(index) + tail
                memo[mask] = result
                return result
            }
        }
        memo[mask] = null
        return null
    }

    private fun blocksRay(target: Node, blocker: Node, width: Float, height: Float): Boolean {
        val origin = target.position
        val point = blocker.position
        val direction = target.arrow.direction.vector
        val dx = point.x - origin.x
        val dy = point.y - origin.y
        val cross = abs(dx * direction.y - dy * direction.x)
        val along = dx * direction.x + dy * direction.y
        if (along <= 0f) return false
        val tolerance = max(target.arrow.bounds.width, target.arrow.bounds.height) * 0.42f
        if (cross > tolerance) return false
        val edgeDistance = rayDistanceToEdge(origin, direction, width, height)
        return along < edgeDistance
    }

    private fun rayDistanceToEdge(origin: PointF, direction: PointF, width: Float, height: Float): Float {
        val candidates = mutableListOf<Float>()
        if (direction.x > 0) candidates += (width - origin.x) / direction.x
        if (direction.x < 0) candidates += -origin.x / direction.x
        if (direction.y > 0) candidates += (height - origin.y) / direction.y
        if (direction.y < 0) candidates += -origin.y / direction.y
        return candidates.filter { it > 0 }.minOrNull() ?: Float.MAX_VALUE
    }

    private data class Node(val index: Int, val arrow: DetectedArrow, val position: PointF)
}