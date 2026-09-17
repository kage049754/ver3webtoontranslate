package com.claude.webtoontranslator.service

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.View

data class OverlayItem(
    val box: Rect,
    val translatedText: String,
    val backgroundColor: Int
)

/**
 * Draws translated text directly on top of the original speech-bubble location,
 * approximating the local background color so it blends with typical
 * solid/simple-color webtoon bubbles. Not touchable - taps pass through to the
 * app underneath so the user can keep scrolling while translations are shown.
 */
class TranslationOverlayView(context: Context) : View(context) {

    var items: List<OverlayItem> = emptyList()
        set(value) {
            field = value
            invalidate()
        }

    var fontScale: Float = 1.0f
    var overlayOpacity: Float = 0.92f

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textAlign = Paint.Align.LEFT
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = Color.parseColor("#33000000")
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        for (item in items) {
            drawItem(canvas, item)
        }
    }

    private fun drawItem(canvas: Canvas, item: OverlayItem) {
        val rect = RectF(item.box)
        bgPaint.color = item.backgroundColor
        bgPaint.alpha = (overlayOpacity * 255).toInt()
        canvas.drawRoundRect(rect, 12f, 12f, bgPaint)
        canvas.drawRoundRect(rect, 12f, 12f, borderPaint)

        // Pick readable text color against the sampled background.
        textPaint.color = if (isColorLight(item.backgroundColor)) Color.BLACK else Color.WHITE

        // Fit text: shrink font size until it fits the box width, down to a floor.
        var textSize = (item.box.height() * 0.32f * fontScale).coerceAtLeast(20f)
        textPaint.textSize = textSize
        val padding = 12f
        val maxWidth = item.box.width() - padding * 2
        val lines = wrapText(item.translatedText, maxWidth)

        // Shrink further if lines overflow the box height.
        while (lines.size * (textPaint.fontSpacing) > item.box.height() - padding * 2 && textSize > 14f) {
            textSize -= 2f
            textPaint.textSize = textSize
        }

        val finalLines = wrapText(item.translatedText, maxWidth)
        var y = item.box.top + padding - textPaint.ascent()
        for (line in finalLines) {
            canvas.drawText(line, item.box.left + padding, y, textPaint)
            y += textPaint.fontSpacing
        }
    }

    private fun wrapText(text: String, maxWidth: Float): List<String> {
        val words = text.split(" ")
        val lines = mutableListOf<String>()
        var current = StringBuilder()
        for (word in words) {
            val candidate = if (current.isEmpty()) word else "${current} $word"
            if (textPaint.measureText(candidate) > maxWidth && current.isNotEmpty()) {
                lines.add(current.toString())
                current = StringBuilder(word)
            } else {
                current = StringBuilder(candidate)
            }
        }
        if (current.isNotEmpty()) lines.add(current.toString())
        return lines
    }

    private fun isColorLight(color: Int): Boolean {
        val luminance = (0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color))
        return luminance > 150
    }

    companion object {
        /** Samples the average color around a box's edge to approximate the bubble background. */
        fun sampleBackgroundColor(bitmap: Bitmap, box: Rect): Int {
            return try {
                val x = box.left.coerceIn(0, bitmap.width - 1)
                val y = (box.top - 4).coerceIn(0, bitmap.height - 1)
                val samples = listOf(
                    bitmap.getPixel(x, y),
                    bitmap.getPixel((box.left + box.width() / 2).coerceIn(0, bitmap.width - 1), y),
                    bitmap.getPixel(box.right.coerceIn(0, bitmap.width - 1), y)
                )
                var r = 0; var g = 0; var b = 0
                for (s in samples) {
                    r += Color.red(s); g += Color.green(s); b += Color.blue(s)
                }
                Color.rgb(r / samples.size, g / samples.size, b / samples.size)
            } catch (_: Exception) {
                Color.WHITE
            }
        }
    }
}
