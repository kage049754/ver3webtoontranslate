package com.claude.webtoontranslator.ocr

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * Online translation path: auto-detects the source language (any language, not
 * limited to the offline set) and translates to a user-chosen target language
 * using a free public LibreTranslate-compatible endpoint.
 *
 * This needs an internet connection and, being a free public instance, can be
 * rate-limited or occasionally unavailable - unlike the on-device offline path,
 * which always works without network access.
 */
class OnlineTranslationManager {

    companion object {
        // Free public LibreTranslate instance. No API key required for light use.
        // Self-hosting or swapping in a paid key here would make this path
        // more reliable if the public instance is ever rate-limited/down.
        private const val ENDPOINT = "https://libretranslate.com/translate"
        private const val TIMEOUT_MS = 10_000

        /** Languages offered in the "translate to" picker for online mode. */
        val SUPPORTED_TARGET_LANGUAGES: List<Pair<String, String>> = listOf(
            "en" to "English",
            "es" to "Spanish",
            "fr" to "French",
            "de" to "German",
            "pt" to "Portuguese",
            "it" to "Italian",
            "ru" to "Russian",
            "ja" to "Japanese",
            "ko" to "Korean",
            "zh" to "Chinese",
            "vi" to "Vietnamese",
            "th" to "Thai",
            "id" to "Indonesian",
            "ar" to "Arabic",
            "hi" to "Hindi",
            "tr" to "Turkish",
            "nl" to "Dutch",
            "pl" to "Polish"
        )
    }

    data class OnlineTranslationResult(
        val detectedSourceLanguage: String?,
        val translatedText: String
    )

    /**
     * Auto-detects [text]'s language and translates to [targetLanguageCode].
     * Returns null on network failure, rate limiting, or if the source already
     * matches the target (nothing meaningful to translate).
     */
    suspend fun translate(text: String, targetLanguageCode: String): OnlineTranslationResult? {
        if (text.isBlank()) return null

        return withContext(Dispatchers.IO) {
            var connection: HttpURLConnection? = null
            try {
                val url = URL(ENDPOINT)
                connection = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = TIMEOUT_MS
                    readTimeout = TIMEOUT_MS
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("Accept", "application/json")
                }

                val body = JSONObject().apply {
                    put("q", text)
                    put("source", "auto")
                    put("target", targetLanguageCode)
                    put("format", "text")
                }

                connection.outputStream.use { os ->
                    os.write(body.toString().toByteArray(StandardCharsets.UTF_8))
                }

                if (connection.responseCode !in 200..299) {
                    return@withContext null
                }

                val responseText = connection.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
                val json = JSONObject(responseText)
                val translated = json.optString("translatedText", "")
                if (translated.isBlank()) return@withContext null

                val detected = json.optJSONObject("detectedLanguage")?.optString("language")

                // If the detected source is already the target, there's nothing worth overlaying.
                if (detected != null && detected == targetLanguageCode) return@withContext null

                OnlineTranslationResult(detectedSourceLanguage = detected, translatedText = translated)
            } catch (_: Exception) {
                null
            } finally {
                connection?.disconnect()
            }
        }
    }
}
