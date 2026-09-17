package com.claude.webtoontranslator.ocr

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.tasks.await

/**
 * Detects the source language of OCR'd text and translates it to English on-device.
 * Supports Korean, Japanese, Chinese, Spanish -> English (the common webtoon/manga/
 * manhua/manhwa source languages requested), auto-detecting per text block.
 */
class TranslationManager {

    private val languageIdentifier = LanguageIdentification.getClient()
    private val translators = mutableMapOf<String, Translator>()

    private val supportedSources = setOf(
        TranslateLanguage.KOREAN,
        TranslateLanguage.JAPANESE,
        TranslateLanguage.CHINESE,
        TranslateLanguage.SPANISH,
        TranslateLanguage.ENGLISH
    )

    /** Pre-downloads all offline models so first real use isn't slow. */
    suspend fun preDownloadModels() {
        val conditions = DownloadConditions.Builder().build()
        for (lang in supportedSources) {
            if (lang == TranslateLanguage.ENGLISH) continue
            try {
                getOrCreateTranslator(lang).downloadModelIfNeeded(conditions).await()
            } catch (_: Exception) {
                // Non-fatal: model will attempt download again on first real use.
            }
        }
    }

    private fun getOrCreateTranslator(sourceLanguage: String): Translator {
        return translators.getOrPut(sourceLanguage) {
            val options = TranslatorOptions.Builder()
                .setSourceLanguage(sourceLanguage)
                .setTargetLanguage(TranslateLanguage.ENGLISH)
                .build()
            Translation.getClient(options)
        }
    }

    /**
     * Identifies the language of [text] and translates to English.
     * Returns null if language can't be confidently identified or is already English.
     */
    suspend fun detectAndTranslate(text: String): TranslationResult? {
        if (text.isBlank()) return null

        val languageCode = try {
            languageIdentifier.identifyLanguage(text).await()
        } catch (_: Exception) {
            "und"
        }

        val mlKitLang = mapToMlKitLanguage(languageCode) ?: return null
        if (mlKitLang == TranslateLanguage.ENGLISH) return null

        return try {
            val translator = getOrCreateTranslator(mlKitLang)
            val conditions = DownloadConditions.Builder().requireWifi().build()
            try {
                translator.downloadModelIfNeeded(conditions).await()
            } catch (_: Exception) {
                translator.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
            }
            val translated = translator.translate(text).await()
            TranslationResult(sourceLanguage = mlKitLang, translatedText = translated)
        } catch (_: Exception) {
            null
        }
    }

    private fun mapToMlKitLanguage(languageIdCode: String): String? {
        return when (languageIdCode) {
            "ko" -> TranslateLanguage.KOREAN
            "ja" -> TranslateLanguage.JAPANESE
            "zh" -> TranslateLanguage.CHINESE
            "es" -> TranslateLanguage.SPANISH
            "en" -> TranslateLanguage.ENGLISH
            else -> null
        }
    }

    fun close() {
        languageIdentifier.close()
        translators.values.forEach { it.close() }
        translators.clear()
    }

    data class TranslationResult(
        val sourceLanguage: String,
        val translatedText: String
    )
}
