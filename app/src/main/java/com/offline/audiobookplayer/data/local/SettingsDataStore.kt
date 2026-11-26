package com.offline.audiobookplayer.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.offline.audiobookplayer.data.model.UserSettings
import com.offline.audiobookplayer.ui.theme.AppThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException
import kotlin.math.roundToInt

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "truyencast_settings"
)

class SettingsDataStore(context: Context) {

    private val dataStore = context.applicationContext.settingsDataStore

    val settingsFlow: Flow<UserSettings> = dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { prefs -> prefs.toUserSettings() }

    suspend fun getSnapshot(): UserSettings = settingsFlow.first()

    suspend fun setPlaybackSpeed(speed: Float) {
        val clamped = normalizeSpeed(speed)
        dataStore.edit { it[KEY_PLAYBACK_SPEED] = clamped }
    }

    suspend fun setSpeedPresets(presets: List<Float>) {
        val sanitized = presets
            .take(3)
            .map(::normalizeSpeed)
            .ifEmpty { UserSettings.DEFAULT_SPEED_PRESETS }
        dataStore.edit { prefs ->
            prefs[KEY_SPEED_PRESETS] = sanitized.joinToString(",")
        }
    }

    suspend fun setSeekStepSeconds(seconds: Int) {
        val clamped = seconds.coerceIn(
            UserSettings.MIN_SEEK_STEP_SECONDS,
            UserSettings.MAX_SEEK_STEP_SECONDS
        )
        dataStore.edit { it[KEY_SEEK_STEP_SECONDS] = clamped }
    }

    suspend fun setAutoRewindSeconds(seconds: Int) {
        val clamped = seconds.coerceIn(0, UserSettings.MAX_AUTO_REWIND_SECONDS)
        dataStore.edit { it[KEY_AUTO_REWIND_SECONDS] = clamped }
    }

    suspend fun setThemeMode(mode: AppThemeMode) {
        dataStore.edit { it[KEY_THEME_MODE] = mode.name }
    }

    suspend fun setVbrIndexSeekingEnabled(enabled: Boolean) {
        dataStore.edit { it[KEY_VBR_INDEX_SEEKING] = enabled }
    }

    private fun Preferences.toUserSettings(): UserSettings {
        val speed = normalizeSpeed(this[KEY_PLAYBACK_SPEED] ?: UserSettings.DEFAULT_SPEED)
        val presets = parsePresets(this[KEY_SPEED_PRESETS])
        val seekStep = (this[KEY_SEEK_STEP_SECONDS] ?: UserSettings.DEFAULT_SEEK_STEP_SECONDS)
            .coerceIn(UserSettings.MIN_SEEK_STEP_SECONDS, UserSettings.MAX_SEEK_STEP_SECONDS)
        val autoRewind = (this[KEY_AUTO_REWIND_SECONDS] ?: UserSettings.DEFAULT_AUTO_REWIND_SECONDS)
            .coerceIn(0, UserSettings.MAX_AUTO_REWIND_SECONDS)
        val theme = this[KEY_THEME_MODE]
            ?.let { raw -> AppThemeMode.entries.firstOrNull { it.name == raw } }
            ?: AppThemeMode.OLED
        val vbrSeeking = this[KEY_VBR_INDEX_SEEKING] ?: false

        return UserSettings(
            playbackSpeed = speed,
            speedPresets = presets,
            seekStepSeconds = seekStep,
            autoRewindSeconds = autoRewind,
            themeMode = theme,
            vbrIndexSeekingEnabled = vbrSeeking
        )
    }

    private fun parsePresets(raw: String?): List<Float> {
        if (raw.isNullOrBlank()) return UserSettings.DEFAULT_SPEED_PRESETS
        val parsed = raw.split(",")
            .mapNotNull { it.trim().toFloatOrNull() }
            .map(::normalizeSpeed)
            .take(3)
        return if (parsed.size == 3) parsed else UserSettings.DEFAULT_SPEED_PRESETS
    }

    private fun normalizeSpeed(speed: Float): Float {
        val roundedTenth = (speed * 10f).roundToInt() / 10f
        return roundedTenth.coerceIn(UserSettings.MIN_SPEED, UserSettings.MAX_SPEED)
    }

    private companion object {
        val KEY_PLAYBACK_SPEED = floatPreferencesKey("playback_speed")
        val KEY_SPEED_PRESETS = stringPreferencesKey("speed_presets")
        val KEY_SEEK_STEP_SECONDS = intPreferencesKey("seek_step_seconds")
        val KEY_AUTO_REWIND_SECONDS = intPreferencesKey("auto_rewind_seconds")
        val KEY_THEME_MODE = stringPreferencesKey("theme_mode")
        val KEY_VBR_INDEX_SEEKING = booleanPreferencesKey("vbr_index_seeking")
    }
}
