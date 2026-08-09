package com.arrowsbot.game

import android.graphics.PointF
import com.arrowsbot.accessibility.ArrowsAccessibilityService
import com.arrowsbot.mapping.ExplorationSwipe
import com.arrowsbot.model.PlannedTap
import kotlinx.coroutines.delay

class GameActions {
    suspend fun tap(action: PlannedTap): Boolean {
        val service = ArrowsAccessibilityService.instance ?: return false
        return service.tap(action)
    }

    suspend fun swipe(swipe: ExplorationSwipe): Boolean {
        val service = ArrowsAccessibilityService.instance ?: return false
        return service.swipe(swipe.startX, swipe.startY, swipe.endX, swipe.endY)
    }

    suspend fun waitForSettledUi() = delay(420L)

    suspend fun waitForAdvertisement() = delay(2_000L)
}