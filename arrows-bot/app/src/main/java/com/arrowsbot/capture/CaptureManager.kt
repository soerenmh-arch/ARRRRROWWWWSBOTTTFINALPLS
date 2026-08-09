package com.arrowsbot.capture

import android.content.Context
import android.graphics.Bitmap
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.Looper
import android.view.Surface
import com.arrowsbot.vision.Frame
import java.util.concurrent.atomic.AtomicLong

class CaptureManager(private val context: Context) {
    private val handler = Handler(Looper.getMainLooper())
    private val frameCounter = AtomicLong(0)
    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private var listener: ((Frame) -> Unit)? = null

    fun setListener(listener: ((Frame) -> Unit)?) {
        this.listener = listener
    }

    fun start(resultCode: Int, data: android.content.Intent, width: Int, height: Int, density: Int) {
        stop()
        val manager = context.getSystemService(MediaProjectionManager::class.java)
        projection = manager.getMediaProjection(resultCode, data)
        val imageReader = ImageReader.newInstance(width, height, android.graphics.PixelFormat.RGBA_8888, 2)
        reader = imageReader
        imageReader.setOnImageAvailableListener({ source ->
            val image = source.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val bitmap = imageToBitmap(image) ?: return@setOnImageAvailableListener
                listener?.invoke(Frame(frameCounter.incrementAndGet(), bitmap))
            } finally {
                image.close()
            }
        }, handler)
        display = projection?.createVirtualDisplay(
            "ArrowsBotCapture",
            width,
            height,
            density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader.surface,
            null,
            handler,
        )
    }

    fun stop() {
        display?.release()
        display = null
        reader?.close()
        reader = null
        projection?.stop()
        projection = null
    }

    private fun imageToBitmap(image: Image): Bitmap? {
        val plane = image.planes.firstOrNull() ?: return null
        val width = image.width
        val height = image.height
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        if (pixelStride <= 0 || rowStride <= 0) return null
        val rowPadding = rowStride - pixelStride * width
        val padded = Bitmap.createBitmap(
            width + rowPadding / pixelStride,
            height,
            Bitmap.Config.ARGB_8888,
        )
        padded.copyPixelsFromBuffer(plane.buffer)
        val cropped = Bitmap.createBitmap(padded, 0, 0, width, height)
        if (cropped !== padded) padded.recycle()
        return cropped
    }

    fun isRunning(): Boolean = projection != null
}