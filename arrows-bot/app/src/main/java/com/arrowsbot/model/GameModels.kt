package com.arrowsbot.model

import android.graphics.PointF
import android.graphics.RectF
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

enum class ArrowDirection(val radians: Double) {
    UP(-Math.PI / 2),
    DOWN(Math.PI / 2),
    LEFT(Math.PI),
    RIGHT(0.0),
    UP_RIGHT(-Math.PI / 4),
    DOWN_RIGHT(Math.PI / 4),
    DOWN_LEFT(3 * Math.PI / 4),
    UP_LEFT(-3 * Math.PI / 4);

    val vector: PointF
        get() = PointF(cos(radians).toFloat(), sin(radians).toFloat())

    companion object {
        fun fromAngle(angle: Double): ArrowDirection {
            val normalized = (angle + 2 * Math.PI) % (2 * Math.PI)
            return entries.minBy { difference(normalized, (it.radians + 2 * Math.PI) % (2 * Math.PI)) }
        }

        private fun difference(a: Double, b: Double): Double {
            val d = kotlin.math.abs(a - b)
            return minOf(d, 2 * Math.PI - d)
        }
    }
}

data class DetectedArrow(
    val id: String,
    val center: PointF,
    val bounds: RectF,
    val direction: ArrowDirection,
    val confidence: Float,
    val sourceFrame: Long,
    val worldPosition: PointF? = null,
)

data class BoardRegion(
    val bounds: RectF,
    val confidence: Float,
)

data class VisionResult(
    val board: BoardRegion?,
    val arrows: List<DetectedArrow>,
    val screenState: ScreenState,
    val confidence: Float,
    val frameId: Long,
)

enum class ScreenState {
    UNKNOWN,
    GAME,
    LEVEL_COMPLETE,
    ADVERTISEMENT,
    PLAY_AGAIN,
}

data class BoardSnapshot(
    val arrows: List<DetectedArrow>,
    val board: BoardRegion,
    val frameId: Long,
    val confidence: Float,
)

data class PlannedTap(
    val arrowId: String,
    val point: PointF,
    val direction: ArrowDirection,
)

sealed interface BotEvent {
    data class StateChanged(val state: BotState) : BotEvent
    data class Analysis(val result: VisionResult) : BotEvent
    data class Plan(val actions: List<PlannedTap>) : BotEvent
    data class Message(val text: String) : BotEvent
}

enum class BotState {
    IDLE,
    START,
    EXPLORE,
    ANALYZE,
    MAP,
    SOLVE,
    TAP,
    VERIFY,
    COMPLETE,
    AD,
    NEXT_LEVEL,
    PAUSED,
}