package com.arrowsbot.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.arrowsbot.ArrowsBotApp
import com.arrowsbot.capture.CaptureForegroundService
import com.arrowsbot.model.BotEvent
import com.arrowsbot.model.DetectedArrow
import com.arrowsbot.model.PlannedTap
import com.arrowsbot.vision.OpenCvInitializer
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private lateinit var app: ArrowsBotApp
    private lateinit var status: TextView
    private lateinit var details: TextView
    private lateinit var debugView: DebugOverlayView
    private var debug = false

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            ContextCompat.startForegroundService(
                this,
                Intent(this, CaptureForegroundService::class.java),
            )
            val metrics = resources.displayMetrics
            app.captureManager.start(
                result.resultCode,
                result.data!!,
                metrics.widthPixels,
                metrics.heightPixels,
                metrics.densityDpi,
            )
            status.text = "Bildschirmaufnahme bereit"
        } else {
            status.text = "Bildschirmaufnahme abgebrochen"
        }
    }

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app = application as ArrowsBotApp
        OpenCvInitializer.initialize()
        setContentView(buildContent())
        lifecycleScope.launch {
            app.botEngine.eventFlow.collect { event -> render(event) }
        }
    }

    private fun buildContent(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
            setBackgroundColor(Color.rgb(16, 19, 28))
        }
        val title = TextView(this).apply {
            text = "ARROWS BOT"
            textSize = 26f
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, 4)
        }
        val subtitle = TextView(this).apply {
            text = "Lokale Analyse · sichere Aktionen · kein Blindklick"
            textSize = 14f
            setTextColor(Color.rgb(180, 190, 214))
        }
        status = TextView(this).apply {
            text = "Status: IDLE"
            textSize = 18f
            setTextColor(Color.rgb(128, 203, 196))
            setPadding(0, 26, 0, 14)
        }
        details = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.rgb(220, 225, 235))
            setPadding(0, 0, 0, 14)
        }
        debugView = DebugOverlayView(this).apply { visibility = View.GONE }
        val startCapture = button("1  Bildschirmaufnahme erlauben") {
            requestNotificationPermissionIfNeeded()
            val manager = getSystemService(MediaProjectionManager::class.java)
            projectionLauncher.launch(manager.createScreenCaptureIntent())
        }
        val accessibility = button("2  Accessibility Service öffnen") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val start = button("START") { app.botEngine.start() }
        val pause = button("PAUSE") { app.botEngine.pause() }
        val stop = button("STOP") { app.botEngine.stop() }
        controls.addView(start, buttonParams())
        controls.addView(pause, buttonParams())
        controls.addView(stop, buttonParams())
        val debugButton = button("DEBUG ANZEIGEN") {
            debug = !debug
            debugView.visibility = if (debug) View.VISIBLE else View.GONE
            app.botEngine.setDebug(debug)
        }
        root.addView(title)
        root.addView(subtitle)
        root.addView(status)
        root.addView(details)
        root.addView(startCapture)
        root.addView(accessibility)
        root.addView(controls)
        root.addView(debugButton)
        root.addView(debugView, LinearLayout.LayoutParams(-1, 0, 1f))
        return ScrollView(this).apply { addView(root) }
    }

    private fun button(label: String, action: () -> Unit): Button =
        Button(this).apply {
            text = label
            isAllCaps = false
            setOnClickListener { action() }
        }

    private fun buttonParams(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            setMargins(4, 0, 4, 0)
        }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun render(event: BotEvent) {
        when (event) {
            is BotEvent.StateChanged -> status.text = "Status: ${event.state.name}"
            is BotEvent.Message -> details.text = event.text
            is BotEvent.Analysis -> {
                val result = event.result
                details.text = "Level ${app.botEngine.currentLevel}  ·  Pfeile ${result.arrows.size}  ·  " +
                    "Fortschritt ${"%.0f".format(app.botEngine.currentProgress * 100)}%  ·  " +
                    "Confidence ${"%.0f".format(result.confidence * 100)}%"
                debugView.update(result.arrows)
            }
            is BotEvent.Plan -> {
                details.text = "Geplante Aktionen: ${event.actions.size}\n" +
                    event.actions.joinToString(" → ") { it.direction.name }
                debugView.update(debugView.arrows, event.actions)
            }
        }
    }
}

class DebugOverlayView(context: android.content.Context) : View(context) {
    internal var arrows: List<DetectedArrow> = emptyList()
    private var actions: List<PlannedTap> = emptyList()
    private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)

    fun update(value: List<DetectedArrow>, planned: List<PlannedTap> = emptyList()) {
        arrows = value
        actions = planned
        invalidate()
    }

    override fun onDraw(canvas: android.graphics.Canvas) {
        super.onDraw(canvas)
        paint.style = android.graphics.Paint.Style.STROKE
        paint.strokeWidth = 3f
        arrows.forEach { arrow ->
            paint.color = if (arrow.confidence >= 0.8f) Color.rgb(80, 220, 150) else Color.YELLOW
            canvas.drawRect(arrow.bounds, paint)
            val vector = arrow.direction.vector
            canvas.drawLine(
                arrow.center.x,
                arrow.center.y,
                arrow.center.x + vector.x * arrow.bounds.width,
                arrow.center.y + vector.y * arrow.bounds.height,
                paint,
            )
        }
        paint.color = Color.rgb(230, 140, 70)
        paint.style = android.graphics.Paint.Style.FILL
        actions.forEachIndexed { index, action ->
            canvas.drawCircle(action.point.x, action.point.y, 8f, paint)
            paint.textSize = 24f
            canvas.drawText("${index + 1}", action.point.x + 10f, action.point.y, paint)
        }
    }
}