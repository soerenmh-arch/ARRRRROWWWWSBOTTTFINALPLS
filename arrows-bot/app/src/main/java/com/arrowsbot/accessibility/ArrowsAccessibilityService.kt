package com.arrowsbot.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.PointF
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.arrowsbot.model.PlannedTap
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class ArrowsAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event != null) latestText = extractText(rootInActiveWindow)
    }

    override fun onInterrupt() = Unit

    suspend fun tap(action: PlannedTap): Boolean {
        val path = Path().apply { moveTo(action.point.x, action.point.y) }
        return dispatch(path, 80L)
    }

    suspend fun swipe(startX: Float, startY: Float, endX: Float, endY: Float, duration: Long = 420L): Boolean {
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        return dispatch(path, duration)
    }

    /**
     * Text/content-description is preferred over a visual guess for the
     * post-level action. This prevents the blue "Tipp" control in the game
     * header from being mistaken for "Spielen".
     */
    fun findPlayAgainCenter(): PointF? {
        val root = rootInActiveWindow ?: return null
        return findTextNode(root) { value ->
            listOf("spielen", "play again", "weiter", "next level", "continue")
                .any(value.lowercase()::contains)
        }?.let { node ->
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            if (bounds.isEmpty) null else PointF(bounds.centerX().toFloat(), bounds.centerY().toFloat())
        }
    }

    private suspend fun dispatch(path: Path, duration: Long): Boolean =
        suspendCancellableCoroutine { continuation ->
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
                .build()
            val accepted = dispatchGesture(
                gesture,
                object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        if (continuation.isActive) continuation.resume(true)
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        if (continuation.isActive) continuation.resume(false)
                    }
                },
                null,
            )
            if (!accepted && continuation.isActive) continuation.resume(false)
            continuation.invokeOnCancellation { /* Android owns callback lifetime. */ }
        }

    private fun extractText(node: AccessibilityNodeInfo?): String {
        if (node == null) return ""
        val result = StringBuilder()
        node.text?.let { result.append(it).append(' ') }
        node.contentDescription?.let { result.append(it).append(' ') }
        for (index in 0 until node.childCount) {
            result.append(extractText(node.getChild(index)))
        }
        return result.toString()
    }

    private fun findTextNode(
        node: AccessibilityNodeInfo?,
        predicate: (String) -> Boolean,
    ): AccessibilityNodeInfo? {
        if (node == null) return null
        val value = buildString {
            node.text?.let { append(it).append(' ') }
            node.contentDescription?.let { append(it) }
        }
        if (predicate(value)) return node
        for (index in 0 until node.childCount) {
            findTextNode(node.getChild(index), predicate)?.let { return it }
        }
        return null
    }

    companion object {
        @Volatile
        var instance: ArrowsAccessibilityService? = null
            private set

        @Volatile
        var latestText: String = ""
            private set
    }
}