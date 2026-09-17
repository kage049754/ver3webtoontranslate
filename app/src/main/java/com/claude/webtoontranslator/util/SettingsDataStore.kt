package com.claude.webtoontranslator.util

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "wt_settings")

/**
 * Persists user preferences (overlay opacity, font size, last-used state) so they
 * survive the app or service being killed and the phone restarting. No user
 * "content" (translations) is persisted - each capture is transient by design.
 */
class SettingsDataStore(private val context: Context) {

    companion object {
        private val KEY_OPACITY = floatPreferencesKey("overlay_opacity")
        private val KEY_FONT_SCALE = floatPreferencesKey("overlay_font_scale")
        private val KEY_ALWAYS_ON_TOP = booleanPreferencesKey("always_on_top")
        private val KEY_AUTO_HIDE_SECONDS = intPreferencesKey("auto_hide_seconds")
        private val KEY_MODELS_DOWNLOADED = booleanPreferencesKey("models_downloaded")
    }

    val opacity: Flow<Float> = context.dataStore.data.map { it[KEY_OPACITY] ?: 0.92f }
    val fontScale: Flow<Float> = context.dataStore.data.map { it[KEY_FONT_SCALE] ?: 1.0f }
    val alwaysOnTop: Flow<Boolean> = context.dataStore.data.map { it[KEY_ALWAYS_ON_TOP] ?: true }
    val autoHideSeconds: Flow<Int> = context.dataStore.data.map { it[KEY_AUTO_HIDE_SECONDS] ?: 5 }
    val modelsDownloaded: Flow<Boolean> = context.dataStore.data.map { it[KEY_MODELS_DOWNLOADED] ?: false }

    suspend fun setOpacity(value: Float) {
        context.dataStore.edit { it[KEY_OPACITY] = value }
    }

    suspend fun setFontScale(value: Float) {
        context.dataStore.edit { it[KEY_FONT_SCALE] = value }
    }

    suspend fun setAlwaysOnTop(value: Boolean) {
        context.dataStore.edit { it[KEY_ALWAYS_ON_TOP] = value }
    }

    suspend fun setAutoHideSeconds(value: Int) {
        context.dataStore.edit { it[KEY_AUTO_HIDE_SECONDS] = value }
    }

    suspend fun setModelsDownloaded(value: Boolean) {
        context.dataStore.edit { it[KEY_MODELS_DOWNLOADED] = value }
    }
}
