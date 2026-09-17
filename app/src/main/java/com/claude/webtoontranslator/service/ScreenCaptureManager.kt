package com.claude.webtoontranslator.service

import android.graphics.Bitmap
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * Wraps MediaProjection + ImageReader to grab a single screen frame on demand.
 * The virtual display stays alive for the life of the projection session (required
 * by the platform); we simply pull the latest frame each time [captureFrame] is
 * called rather than tearing everything down between captures.
 */
class ScreenCaptureManager(
    private val mediaProjection: MediaProjection,
    private val metrics: DisplayMetrics
) {
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    private val handler = Handler(Looper.getMainLooper())

    fun start() {
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        val reader = ImageReader.newInstance(width, height, android.graphics.PixelFormat.RGBA_8888, 2)
        imageReader = reader

        virtualDisplay = mediaProjection.createVirtualDisplay(
            "WebtoonTranslatorCapture",
            width, height, density,
            android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface, null, handler
        )
    }

    suspend fun captureFrame(): Bitmap? = suspendCoroutine { cont ->
        val reader = imageReader
        if (reader == null) {
            cont.resume(null)
            return@suspendCoroutine
        }
        try {
            // Drain to the freshest available frame.
            var image = reader.acquireLatestImage()
            if (image == null) {
                cont.resume(null)
                return@suspendCoroutine
            }
            val planes = image.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * image.width

            val bitmap = Bitmap.createBitmap(
                image.width + rowPadding / pixelStride,
                image.height,
                Bitmap.Config.ARGB_8888
            )
            bitmap.copyPixelsFromBuffer(buffer)
            image.close()

            val cropped = if (bitmap.width != metrics.widthPixels) {
                Bitmap.createBitmap(bitmap, 0, 0, metrics.widthPixels, metrics.heightPixels)
            } else {
                bitmap
            }
            cont.resume(cropped)
        } catch (e: Exception) {
            cont.resume(null)
        }
    }

    fun stop() {
        virtualDisplay?.release()
        imageReader?.close()
        virtualDisplay = null
        imageReader = null
    }
}
