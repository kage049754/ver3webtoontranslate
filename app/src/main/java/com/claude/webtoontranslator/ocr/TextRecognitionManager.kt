package com.claude.webtoontranslator.ocr

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await

/** A merged block of text ready for translation + overlay drawing. */
data class TextBlockResult(
    val text: String,
    val boundingBox: Rect
)

/**
 * Runs OCR across Korean, Japanese, Chinese and Latin recognizers (the common
 * webtoon/manhwa/manhua/manga source scripts) and picks whichever script
 * actually matched the content, then merges fragmented lines into
 * speech-bubble-sized paragraphs (webtoon/manga text tends to break across many
 * short lines that need grouping, not word-by-word translation).
 */
class TextRecognitionManager {

    private val recognizers: Map<String, TextRecognizer> = mapOf(
        "latin" to TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS),
        "korean" to TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build()),
        "japanese" to TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build()),
        "chinese" to TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    )

    /** Minimum characters for a block to be considered reliable enough to translate/overlay. */
    private val minConfidentLength = 2

    suspend fun recognize(bitmap: Bitmap): List<TextBlockResult> {
        val image = InputImage.fromBitmap(bitmap, 0)

        var bestResult: Text? = null
        var bestScore = 0

        for ((_, recognizer) in recognizers) {
            try {
                val result = recognizer.process(image).await()
                val score = result.text.replace("\\s".toRegex(), "").length
                if (score > bestScore) {
                    bestScore = score
                    bestResult = result
                }
            } catch (_: Exception) {
                // Skip this recognizer if it fails; others may still succeed.
            }
        }

        val text = bestResult ?: return emptyList()
        val rawBlocks = extractLines(text)
        val filtered = rawBlocks.filter { it.text.trim().length >= minConfidentLength }
        return mergeNearbyLines(filtered)
    }

    /** Flattens ML Kit's block/line hierarchy into individual lines with bounding boxes. */
    private fun extractLines(text: Text): List<TextBlockResult> {
        val lines = mutableListOf<TextBlockResult>()
        for (block in text.textBlocks) {
            for (line in block.lines) {
                val box = line.boundingBox ?: continue
                if (line.text.isNotBlank()) {
                    lines.add(TextBlockResult(line.text, box))
                }
            }
        }
        return lines
    }

    /**
     * Groups lines that belong to the same speech bubble: close vertically,
     * overlapping horizontally. This keeps multi-line dialogue as one
     * translatable unit instead of fragmenting per line.
     */
    private fun mergeNearbyLines(lines: List<TextBlockResult>): List<TextBlockResult> {
        if (lines.isEmpty()) return emptyList()

        val sorted = lines.sortedBy { it.boundingBox.top }
        val used = BooleanArray(sorted.size)
        val merged = mutableListOf<TextBlockResult>()

        for (i in sorted.indices) {
            if (used[i]) continue
            var currentBox = Rect(sorted[i].boundingBox)
            val currentTextParts = mutableListOf(sorted[i].text)
            used[i] = true

            var changed = true
            while (changed) {
                changed = false
                for (j in sorted.indices) {
                    if (used[j]) continue
                    val box = sorted[j].boundingBox
                    val verticalGap = box.top - currentBox.bottom
                    val horizontalOverlap = horizontalOverlapRatio(currentBox, box)
                    val avgLineHeight = currentBox.height().coerceAtLeast(1)

                    if (verticalGap in -avgLineHeight..(avgLineHeight) && horizontalOverlap > 0.3f) {
                        currentBox.union(box)
                        currentTextParts.add(sorted[j].text)
                        used[j] = true
                        changed = true
                    }
                }
            }

            merged.add(TextBlockResult(currentTextParts.joinToString(" "), currentBox))
        }

        return merged
    }

    private fun horizontalOverlapRatio(a: Rect, b: Rect): Float {
        val overlapLeft = maxOf(a.left, b.left)
        val overlapRight = minOf(a.right, b.right)
        val overlapWidth = (overlapRight - overlapLeft).coerceAtLeast(0)
        val minWidth = minOf(a.width(), b.width()).coerceAtLeast(1)
        return overlapWidth.toFloat() / minWidth.toFloat()
    }

    fun close() {
        recognizers.values.forEach { it.close() }
    }
}
