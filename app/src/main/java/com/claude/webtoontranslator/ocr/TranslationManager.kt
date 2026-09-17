package com.claude.webtoontranslator.ocr

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.tasks.await

/**
 * Detects the source language of OCR text and translates
 * it to English using downloaded on-device ML Kit models.
 *
 * Supported offline source languages:
 * Korean
 * Japanese
 * Chinese
 * Spanish
 * French
 */
class TranslationManager {

    private val languageIdentifier =
        LanguageIdentification.getClient()

    private val translators =
        mutableMapOf<String, Translator>()

    private val supportedSources =
        setOf(
            TranslateLanguage.KOREAN,
            TranslateLanguage.JAPANESE,
            TranslateLanguage.CHINESE,
            TranslateLanguage.SPANISH,
            TranslateLanguage.FRENCH,
            TranslateLanguage.ENGLISH
        )

    /**
     * Downloads all required offline translation models.
     */
    suspend fun preDownloadModels() {

        val conditions =
            DownloadConditions
                .Builder()
                .build()

        for (language in supportedSources) {

            if (
                language ==
                TranslateLanguage.ENGLISH
            ) {
                continue
            }

            try {

                getOrCreateTranslator(
                    language
                )
                    .downloadModelIfNeeded(
                        conditions
                    )
                    .await()

            } catch (_: Exception) {
                // Non-fatal.
            }
        }
    }

    private fun getOrCreateTranslator(
        sourceLanguage: String
    ): Translator {

        return translators.getOrPut(
            sourceLanguage
        ) {

            val options =
                TranslatorOptions
                    .Builder()
                    .setSourceLanguage(
                        sourceLanguage
                    )
                    .setTargetLanguage(
                        TranslateLanguage.ENGLISH
                    )
                    .build()

            Translation.getClient(
                options
            )
        }
    }

    /**
     * Detects the language and translates
     * the text into English.
     */
    suspend fun detectAndTranslate(
        text: String
    ): TranslationResult? {

        if (text.isBlank()) {
            return null
        }

        val languageCode =
            try {

                languageIdentifier
                    .identifyLanguage(text)
                    .await()

            } catch (_: Exception) {

                "und"
            }

        val mlKitLanguage =
            mapToMlKitLanguage(
                languageCode
            ) ?: return null

        /*
         * Already English.
         */
        if (
            mlKitLanguage ==
            TranslateLanguage.ENGLISH
        ) {
            return null
        }

        /*
         * Only translate languages that
         * have offline models installed.
         */
        if (
            mlKitLanguage !in
            supportedSources
        ) {
            return null
        }

        return try {

            val translator =
                getOrCreateTranslator(
                    mlKitLanguage
                )

            val conditions =
                DownloadConditions
                    .Builder()
                    .requireWifi()
                    .build()

            try {

                translator
                    .downloadModelIfNeeded(
                        conditions
                    )
                    .await()

            } catch (_: Exception) {

                translator
                    .downloadModelIfNeeded(
                        DownloadConditions
                            .Builder()
                            .build()
                    )
                    .await()
            }

            val translated =
                translator
                    .translate(text)
                    .await()

            TranslationResult(
                sourceLanguage =
                    mlKitLanguage,
                translatedText =
                    translated
            )

        } catch (_: Exception) {

            null
        }
    }

    private fun mapToMlKitLanguage(
        languageIdCode: String
    ): String? {

        return when (languageIdCode) {

            "ko" ->
                TranslateLanguage.KOREAN

            "ja" ->
                TranslateLanguage.JAPANESE

            "zh" ->
                TranslateLanguage.CHINESE

            "es" ->
                TranslateLanguage.SPANISH

            "fr" ->
                TranslateLanguage.FRENCH

            "en" ->
                TranslateLanguage.ENGLISH

            else ->
                null
        }
    }

    fun close() {

        languageIdentifier.close()

        translators.values.forEach {
            it.close()
        }

        translators.clear()
    }

    data class TranslationResult(
        val sourceLanguage: String,
        val translatedText: String
    )
}
