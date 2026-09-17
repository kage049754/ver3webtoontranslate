package com.claude.webtoontranslator.ocr

import com.google.mlkit.nl.languageid.LanguageIdentification
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Online translation.
 *
 * Uses ML Kit to identify the source language and MyMemory
 * for the actual translation.
 *
 * This avoids depending on LibreTranslate public instances,
 * which may require an API key or become unavailable.
 */
class OnlineTranslationManager {

    companion object {

        private const val ENDPOINT =
            "https://api.mymemory.translated.net/get"

        private const val TIMEOUT_MS = 12_000

        val SUPPORTED_TARGET_LANGUAGES: List<Pair<String, String>> =
            listOf(
                "en" to "English",
                "fr" to "French",
                "es" to "Spanish",
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

    private val languageIdentifier =
        LanguageIdentification.getClient()

    data class OnlineTranslationResult(
        val detectedSourceLanguage: String?,
        val translatedText: String
    )

    suspend fun translate(
        text: String,
        targetLanguageCode: String
    ): OnlineTranslationResult? {

        if (text.isBlank()) {
            return null
        }

        return withContext(Dispatchers.IO) {

            try {

                val detectedLanguage =
                    try {
                        languageIdentifier
                            .identifyLanguage(text)
                            .await()
                    } catch (_: Exception) {
                        "und"
                    }

                if (
                    detectedLanguage == "und" ||
                    detectedLanguage.isBlank()
                ) {
                    return@withContext null
                }

                if (
                    detectedLanguage.equals(
                        targetLanguageCode,
                        ignoreCase = true
                    )
                ) {
                    return@withContext null
                }

                translateWithMyMemory(
                    text = text,
                    sourceLanguage = detectedLanguage,
                    targetLanguage = targetLanguageCode
                )

            } catch (_: Exception) {

                null
            }
        }
    }

    private fun translateWithMyMemory(
        text: String,
        sourceLanguage: String,
        targetLanguage: String
    ): OnlineTranslationResult? {

        var connection: HttpURLConnection? = null

        return try {

            /*
             * MyMemory has a 500-byte limit for q.
             * Keep OCR requests within that limit.
             */
            val safeText =
                text
                    .trim()
                    .take(450)

            if (safeText.isBlank()) {
                return null
            }

            val encodedText =
                URLEncoder.encode(
                    safeText,
                    StandardCharsets.UTF_8.name()
                )

            val encodedPair =
                URLEncoder.encode(
                    "$sourceLanguage|$targetLanguage",
                    StandardCharsets.UTF_8.name()
                )

            val url =
                URL(
                    "$ENDPOINT?q=$encodedText&langpair=$encodedPair&mt=1"
                )

            connection =
                (url.openConnection() as HttpURLConnection).apply {

                    requestMethod = "GET"

                    connectTimeout = TIMEOUT_MS
                    readTimeout = TIMEOUT_MS

                    useCaches = false

                    setRequestProperty(
                        "Accept",
                        "application/json"
                    )

                    setRequestProperty(
                        "User-Agent",
                        "WebtoonTranslator/1.0"
                    )
                }

            if (
                connection.responseCode !in 200..299
            ) {
                return null
            }

            val response =
                connection
                    .inputStream
                    .bufferedReader(
                        StandardCharsets.UTF_8
                    )
                    .use {
                        it.readText()
                    }

            val json =
                JSONObject(response)

            val responseStatus =
                json.optInt(
                    "responseStatus",
                    0
                )

            if (
                responseStatus != 200
            ) {
                return null
            }

            val responseData =
                json.optJSONObject(
                    "responseData"
                ) ?: return null

            val translated =
                responseData
                    .optString(
                        "translatedText",
                        ""
                    )
                    .trim()

            if (translated.isBlank()) {
                return null
            }

            OnlineTranslationResult(
                detectedSourceLanguage =
                    sourceLanguage,
                translatedText =
                    translated
            )

        } catch (_: Exception) {

            null

        } finally {

            connection?.disconnect()
        }
    }

    fun close() {
        languageIdentifier.close()
    }
}
