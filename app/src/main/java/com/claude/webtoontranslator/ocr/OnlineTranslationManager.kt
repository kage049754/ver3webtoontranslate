package com.claude.webtoontranslator.ocr

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * Online translation with automatic fallback between LibreTranslate instances.
 *
 * The main libretranslate.com service currently requires an API key, so the
 * app does not depend on that single endpoint.
 */
class OnlineTranslationManager {

    companion object {

        private const val TIMEOUT_MS = 12_000

        private val ENDPOINTS = listOf(
            "https://libretranslate.de/translate",
            "https://translate.argosopentech.com/translate",
            "https://es.libretranslate.com/translate",
            "https://ru.libretranslate.com/translate"
        )

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

    suspend fun translate(
        text: String,
        targetLanguageCode: String
    ): OnlineTranslationResult? {

        if (text.isBlank()) return null

        return withContext(Dispatchers.IO) {

            for (endpoint in ENDPOINTS) {

                val result = tryTranslate(
                    endpoint,
                    text,
                    targetLanguageCode
                )

                if (result != null) {
                    return@withContext result
                }
            }

            null
        }
    }

    private fun tryTranslate(
        endpoint: String,
        text: String,
        targetLanguageCode: String
    ): OnlineTranslationResult? {

        var connection: HttpURLConnection? = null

        return try {

            connection =
                (URL(endpoint).openConnection() as HttpURLConnection).apply {

                    requestMethod = "POST"

                    connectTimeout = TIMEOUT_MS
                    readTimeout = TIMEOUT_MS

                    doOutput = true

                    setRequestProperty(
                        "Content-Type",
                        "application/json; charset=UTF-8"
                    )

                    setRequestProperty(
                        "Accept",
                        "application/json"
                    )

                    setRequestProperty(
                        "User-Agent",
                        "WebtoonTranslator/1.0"
                    )
                }

            val body = JSONObject().apply {

                put("q", text)

                put(
                    "source",
                    "auto"
                )

                put(
                    "target",
                    targetLanguageCode
                )

                put(
                    "format",
                    "text"
                )
            }

            connection.outputStream.use { output ->

                output.write(
                    body
                        .toString()
                        .toByteArray(
                            StandardCharsets.UTF_8
                        )
                )

                output.flush()
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

            val translated =
                json
                    .optString(
                        "translatedText",
                        ""
                    )
                    .trim()

            if (translated.isBlank()) {
                return null
            }

            val detected =
                when {

                    json.has("detectedLanguage") -> {

                        val detectedObject =
                            json.optJSONObject(
                                "detectedLanguage"
                            )

                        detectedObject
                            ?.optString("language")
                            ?.takeIf {
                                it.isNotBlank()
                            }
                            ?: json
                                .optString(
                                    "detectedLanguage",
                                    ""
                                )
                                .takeIf {
                                    it.isNotBlank()
                                }
                    }

                    else -> null
                }

            if (
                detected != null &&
                detected.equals(
                    targetLanguageCode,
                    ignoreCase = true
                )
            ) {
                return null
            }

            OnlineTranslationResult(
                detectedSourceLanguage = detected,
                translatedText = translated
            )

        } catch (_: Exception) {

            null

        } finally {

            connection?.disconnect()
        }
    }
}
