package com.arrowsbot.state

import android.content.Context
import android.graphics.PointF
import com.arrowsbot.accessibility.ArrowsAccessibilityService
import com.arrowsbot.capture.CaptureManager
import com.arrowsbot.game.GameActions
import com.arrowsbot.mapping.ExplorationSwipe
import com.arrowsbot.mapping.GlobalBoardMap
import com.arrowsbot.mapping.ExplorationPlanner
import com.arrowsbot.mapping.Viewport
import com.arrowsbot.model.BotEvent
import com.arrowsbot.model.BotState
import com.arrowsbot.model.BoardSnapshot
import com.arrowsbot.model.ScreenState
import com.arrowsbot.solver.ArrowSolver
import com.arrowsbot.vision.BoardAnalyzer
import com.arrowsbot.vision.Frame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BotEngine(
    context: Context,
    private val capture: CaptureManager,
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val actions = GameActions()
    private val analyzer = BoardAnalyzer()
    private val solver = ArrowSolver()
    private val boardMap = GlobalBoardMap()
    private val events = MutableSharedFlow<BotEvent>(extraBufferCapacity = 32)
    private var job: Job? = null
    private var lastFrame: Frame? = null
    private var latestSnapshot: BoardSnapshot? = null
    private var state = BotState.IDLE
    private var debugEnabled = false
    private var level = 0
    private var viewport: Viewport? = null
    private var explorationStep = 0
    private var explorationTotal = 1

    val eventFlow: SharedFlow<BotEvent> = events.asSharedFlow()
    val currentState: BotState get() = state
    val currentLevel: Int get() = level
    val currentSnapshot: BoardSnapshot? get() = latestSnapshot
    val currentProgress: Float get() = (explorationStep.toFloat() / explorationTotal).coerceIn(0f, 1f)

    init {
        capture.setListener { frame ->
            lastFrame?.bitmap?.recycle()
            lastFrame = frame
        }
    }

    fun setDebug(enabled: Boolean) {
        debugEnabled = enabled
    }

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch { runLoop() }
    }

    fun pause() {
        job?.cancel()
        job = null
        transition(BotState.PAUSED)
    }

    fun stop() {
        job?.cancel()
        job = null
        boardMap.clear()
        latestSnapshot = null
        viewport = null
        explorationStep = 0
        transition(BotState.IDLE)
    }

    private suspend fun runLoop() {
        transition(BotState.START)
        if (ArrowsAccessibilityService.instance == null || !capture.isRunning()) {
            message("MediaProjection und Accessibility Service müssen aktiv sein.")
            transition(BotState.PAUSED)
            return
        }
        transition(BotState.EXPLORE)
        val metrics = appContext.resources.displayMetrics
        val planner = ExplorationPlanner(metrics.widthPixels, metrics.heightPixels)
        val explorationPlan = planner.swipes()
        explorationTotal = explorationPlan.size
        viewport = Viewport(metrics.widthPixels, metrics.heightPixels)
        boardMap.setViewport(viewport!!)
        var explorationRounds = 0
        while (isActive) {
            val frame = awaitFrame(lastFrame?.id ?: -1L) ?: continue
            transition(BotState.ANALYZE)
            val result = withContext(Dispatchers.Default) {
                analyzer.analyze(frame, ArrowsAccessibilityService.latestText)
            }
            events.emit(BotEvent.Analysis(result))
            // A visually uniform ad can only receive a lower-confidence
            // classification. It is safe to wait on that signal because no
            // input is sent; every interactive state still needs 0.55+.
            if (result.confidence < 0.55f && result.screenState != ScreenState.ADVERTISEMENT) {
                message("Analyse unsicher (${format(result.confidence)}). Keine Aktion.")
                transition(BotState.PAUSED)
                return
            }
            when (result.screenState) {
                ScreenState.GAME -> {
                    transition(BotState.MAP)
                    val currentViewport = viewport ?: Viewport(metrics.widthPixels, metrics.heightPixels)
                    boardMap.recordView(result.arrows, currentViewport)
                    latestSnapshot = BoardSnapshot(boardMap.allArrows(), result.board ?: continue, result.frameId, result.confidence)
                    if (explorationRounds < explorationPlan.size) {
                        val swipe = explorationPlan[explorationRounds]
                        explorationRounds++
                        explorationStep = explorationRounds
                        if (!actions.swipe(swipe)) {
                            message("Swipe wurde vom Accessibility Service abgelehnt.")
                            transition(BotState.PAUSED)
                            return
                        }
                        viewport = currentViewport.copy(
                            offset = PointF(
                                currentViewport.offset.x + swipe.offsetDelta.x,
                                currentViewport.offset.y + swipe.offsetDelta.y,
                            ),
                        )
                        actions.waitForSettledUi()
                        transition(BotState.EXPLORE)
                    } else {
                        transition(BotState.SOLVE)
                        solveAndTap(
                            metrics.widthPixels.toFloat() + kotlin.math.abs(viewport?.offset?.x ?: 0f),
                            metrics.heightPixels.toFloat() + kotlin.math.abs(viewport?.offset?.y ?: 0f),
                        )
                        explorationRounds = 0
                    }
                }
                ScreenState.LEVEL_COMPLETE -> {
                    transition(BotState.COMPLETE)
                    actions.waitForAdvertisement()
                    transition(BotState.AD)
                }
                ScreenState.ADVERTISEMENT -> {
                    transition(BotState.AD)
                    actions.waitForAdvertisement()
                }
                ScreenState.PLAY_AGAIN -> {
                    transition(BotState.NEXT_LEVEL)
                    val play = ArrowsAccessibilityService.instance?.findPlayAgainCenter()
                    if (play != null) {
                        actions.tap(
                            com.arrowsbot.model.PlannedTap(
                                "play-again",
                                play,
                                com.arrowsbot.model.ArrowDirection.RIGHT,
                            ),
                        )
                        boardMap.clear()
                        viewport = Viewport(metrics.widthPixels, metrics.heightPixels)
                        boardMap.setViewport(viewport!!)
                        level++
                        explorationRounds = 0
                        explorationStep = 0
                        transition(BotState.EXPLORE)
                    } else {
                        message("Spielen-Schaltfläche nicht sicher genug erkannt.")
                        transition(BotState.PAUSED)
                        return
                    }
                }
                ScreenState.UNKNOWN -> {
                    message("Unbekannter Bildschirm. Bot pausiert zur Sicherheit.")
                    transition(BotState.PAUSED)
                    return
                }
            }
        }
    }

    private suspend fun solveAndTap(width: Float, height: Float) {
        val snapshot = latestSnapshot ?: run {
            message("Kein vollständiger Kartenstand.")
            transition(BotState.PAUSED)
            return
        }
        val plan = solver.solve(snapshot.arrows, width, height) ?: run {
            message("Keine sichere Lösung oder zu geringe Pfeil-Confidence.")
            transition(BotState.PAUSED)
            return
        }
        events.emit(BotEvent.Plan(plan.actions))
        for (action in plan.actions) {
            val currentViewport = viewport ?: run {
                message("Kein aktueller Ansichtsbereich.")
                transition(BotState.PAUSED)
                return
            }
            if (!ensureVisible(action.point, action.direction, currentViewport.width, currentViewport.height)) {
                message("Pfeil konnte vor dem Tap nicht sicher sichtbar gemacht werden.")
                transition(BotState.PAUSED)
                return
            }
            val visibleViewport = viewport ?: return
            val localPoint = PointF(
                action.point.x - visibleViewport.offset.x,
                action.point.y - visibleViewport.offset.y,
            )
            if (localPoint.x !in 0f..visibleViewport.width.toFloat() ||
                localPoint.y !in 0f..visibleViewport.height.toFloat()
            ) {
                message("Aktion außerhalb des Bildschirms verworfen.")
                transition(BotState.PAUSED)
                return
            }
            transition(BotState.TAP)
            if (!actions.tap(action.copy(point = localPoint))) {
                message("Tap wurde vom Accessibility Service abgelehnt.")
                transition(BotState.PAUSED)
                return
            }
            actions.waitForSettledUi()
            transition(BotState.VERIFY)
            val verified = verifyRemoved(localPoint, action.direction)
            if (!verified) {
                message("Tap nicht bestätigt. Bot pausiert.")
                transition(BotState.PAUSED)
                return
            }
        }
    }

    private suspend fun ensureVisible(
        worldPoint: PointF,
        direction: com.arrowsbot.model.ArrowDirection,
        width: Int,
        height: Int,
    ): Boolean {
        repeat(6) {
            val view = viewport ?: return false
            val marginX = width * 0.12f
            val marginY = height * 0.16f
            val local = PointF(worldPoint.x - view.offset.x, worldPoint.y - view.offset.y)
            val horizontal = when {
                local.x < marginX -> ExplorationSwipe(
                    insetX(width), height * 0.47f, rightX(width), height * 0.47f,
                    PointF(-width * 0.55f, 0f),
                )
                local.x > width - marginX -> ExplorationSwipe(
                    rightX(width), height * 0.47f, insetX(width), height * 0.47f,
                    PointF(width * 0.55f, 0f),
                )
                else -> null
            }
            val vertical = when {
                local.y < marginY -> ExplorationSwipe(
                    width * 0.52f, height * 0.20f, width * 0.52f, height * 0.82f,
                    PointF(0f, -height * 0.48f),
                )
                local.y > height - marginY -> ExplorationSwipe(
                    width * 0.52f, height * 0.82f, width * 0.52f, height * 0.20f,
                    PointF(0f, height * 0.48f),
                )
                else -> null
            }
            val swipe = horizontal ?: vertical
            if (swipe == null) {
                val frame = awaitFrame(lastFrame?.id ?: -1L) ?: return false
                val result = analyzer.analyze(frame, ArrowsAccessibilityService.latestText)
                events.emit(BotEvent.Analysis(result))
                return result.arrows.any {
                    val dx = it.center.x - local.x
                    val dy = it.center.y - local.y
                    kotlin.math.hypot(dx, dy) < maxOf(it.bounds.width, it.bounds.height) * 0.9f &&
                        it.direction == direction && it.confidence >= 0.7f
                }
            }
            if (!actions.swipe(swipe)) return false
            viewport = view.copy(
                offset = PointF(view.offset.x + swipe.offsetDelta.x, view.offset.y + swipe.offsetDelta.y),
            )
            actions.waitForSettledUi()
        }
        return false
    }

    private fun insetX(width: Int): Float = width * 0.14f

    private fun rightX(width: Int): Float = width * 0.86f

    private suspend fun verifyRemoved(point: PointF, direction: com.arrowsbot.model.ArrowDirection): Boolean {
        val frame = awaitFrame(lastFrame?.id ?: -1L) ?: return false
        val result = analyzer.analyze(frame, ArrowsAccessibilityService.latestText)
        events.emit(BotEvent.Analysis(result))
        val stillPresent = result.arrows.any {
            val dx = it.center.x - point.x
            val dy = it.center.y - point.y
            kotlin.math.hypot(dx, dy) < maxOf(it.bounds.width, it.bounds.height) * 0.75f &&
                it.direction == direction
        }
        return !stillPresent
    }

    private suspend fun awaitFrame(afterId: Long): Frame? {
        repeat(18) {
            lastFrame?.takeIf { it.id > afterId }?.let { return it }
            delay(250L)
        }
        return lastFrame?.takeIf { it.id > afterId }
    }

    private fun transition(next: BotState) {
        state = next
        events.tryEmit(BotEvent.StateChanged(next))
    }

    private fun message(text: String) {
        events.tryEmit(BotEvent.Message(text))
    }

    private fun format(value: Float): String = "%.2f".format(value)
}