package com.claude.webtoontranslator.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.claude.webtoontranslator.MainActivity
import com.claude.webtoontranslator.R
import com.claude.webtoontranslator.ocr.TextRecognitionManager
import com.claude.webtoontranslator.ocr.TranslationManager
import com.claude.webtoontranslator.util.SettingsDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.abs

class OverlayService : Service() {

    companion object {
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"
        const val CHANNEL_ID = "overlay_translator_channel"
        const val NOTIFICATION_ID = 1001
        private const val CLICK_DRAG_THRESHOLD = 12
    }

    private enum class State { IDLE, WORKING, SHOWING }

    private lateinit var windowManager: WindowManager
    private var mediaProjection: MediaProjection? = null
    private var captureManager: ScreenCaptureManager? = null

    private val textRecognitionManager = TextRecognitionManager()
    private val translationManager = TranslationManager()
    private lateinit var settingsDataStore: SettingsDataStore

    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())

    private var buttonView: TextView? = null
    private var buttonParams: WindowManager.LayoutParams? = null
    private var overlayView: TranslationOverlayView? = null
    private var overlayParams: WindowManager.LayoutParams? = null

    private var state = State.IDLE

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        settingsDataStore = SettingsDataStore(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, -1) ?: -1
        val resultData: Intent? = intent?.getParcelableExtra(EXTRA_RESULT_DATA)

        startForeground(NOTIFICATION_ID, buildNotification(), foregroundServiceType())

        if (resultData != null && resultCode != -1) {
            val projectionManager =
                getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = projectionManager.getMediaProjection(resultCode, resultData)

            val metrics = DisplayMetrics()
            windowManager.defaultDisplay.getRealMetrics(metrics)

            mediaProjection?.let { projection ->
                val capture = ScreenCaptureManager(projection, metrics)
                capture.start()
                captureManager = capture
            }

            serviceScope.launch {
                translationManager.preDownloadModels()
                settingsDataStore.setModelsDownloaded(true)
            }

            addButtonOverlay()
        } else {
            stopSelf()
        }

        return START_NOT_STICKY
    }

    private fun foregroundServiceType(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        } else {
            0
        }
    }

    // ---------- Floating button ----------

    private fun addButtonOverlay() {
        if (buttonView != null) return

        val button = TextView(this).apply {
            text = "訳"
            setTextColor(Color.WHITE)
            textSize = 18f
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#6750A4"))
            alpha = 0.95f
        }
        val sizePx = (56 * resources.displayMetrics.density).toInt()

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            sizePx, sizePx,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 300
        }

        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var isDrag = false

        button.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = params.x
                    startY = params.y
                    isDrag = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downX).toInt()
                    val dy = (event.rawY - downY).toInt()
                    if (abs(dx) > CLICK_DRAG_THRESHOLD || abs(dy) > CLICK_DRAG_THRESHOLD) {
                        isDrag = true
                        params.x = startX + dx
                        params.y = startY + dy
                        windowManager.updateViewLayout(button, params)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDrag) {
                        onButtonTapped()
                    }
                    true
                }
                else -> false
            }
        }

        windowManager.addView(button, params)
        buttonView = button
        buttonParams = params
    }

    /**
     * The toggle button is always in its own touchable window, independent of
     * overlay state, so the user can tap it to stop or re-run translation at any
     * time - even while translated text is currently showing on screen.
     */
    private fun onButtonTapped() {
        when (state) {
            State.IDLE -> runCaptureAndTranslate()
            State.SHOWING -> clearOverlay()
            State.WORKING -> { /* ignore taps while a capture is already in progress */ }
        }
    }

    // ---------- Capture + OCR + translate ----------

    private fun runCaptureAndTranslate() {
        val capture = captureManager ?: return
        state = State.WORKING
        setButtonLabel("…")

        serviceScope.launch {
            try {
                val bitmap = capture.captureFrame()
                if (bitmap == null) {
                    setButtonLabel("!")
                    state = State.IDLE
                    return@launch
                }

                val blocks = withDispatcherIO { textRecognitionManager.recognize(bitmap) }

                if (blocks.isEmpty()) {
                    // Empty state: nothing recognized - let the user know briefly, no crash.
                    setButtonLabel("∅")
                    state = State.IDLE
                    return@launch
                }

                val overlayItems = mutableListOf<OverlayItem>()
                for (block in blocks) {
                    val result = translationManager.detectAndTranslate(block.text) ?: continue
                    val bgColor = TranslationOverlayView.sampleBackgroundColor(bitmap, block.boundingBox)
                    overlayItems.add(OverlayItem(block.boundingBox, result.translatedText, bgColor))
                }

                if (overlayItems.isEmpty()) {
                    setButtonLabel("EN?")
                    state = State.IDLE
                    return@launch
                }

                showOverlay(overlayItems)
                setButtonLabel("✕")
                state = State.SHOWING
            } catch (_: Exception) {
                setButtonLabel("!")
                state = State.IDLE
            }
        }
    }

    private suspend fun <T> withDispatcherIO(block: suspend () -> T): T {
        return kotlinx.coroutines.withContext(Dispatchers.Default) { block() }
    }

    private fun setButtonLabel(label: String) {
        buttonView?.text = label
    }

    // ---------- Translation overlay window ----------

    private fun showOverlay(items: List<OverlayItem>) {
        removeOverlayView()

        val view = TranslationOverlayView(this)
        view.items = items

        serviceScope.launch {
            view.overlayOpacity = try {
                settingsDataStore.opacity.first()
            } catch (_: Exception) {
                0.92f
            }
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            // NOT_TOUCHABLE: taps pass through to the app underneath so scrolling
            // still works while translations are displayed. Only the separate
            // button window above remains touchable.
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )

        windowManager.addView(view, params)
        overlayView = view
        overlayParams = params
    }

    private fun clearOverlay() {
        removeOverlayView()
        setButtonLabel("訳")
        state = State.IDLE
    }

    private fun removeOverlayView() {
        overlayView?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
        }
        overlayView = null
    }

    // ---------- Notification / lifecycle ----------

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        removeOverlayView()
        buttonView?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
        }
        captureManager?.stop()
        mediaProjection?.stop()
        textRecognitionManager.close()
        translationManager.close()
        serviceScope.launch { }.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
