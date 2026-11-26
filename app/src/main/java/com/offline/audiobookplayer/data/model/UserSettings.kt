package com.offline.audiobookplayer.data.model

import com.offline.audiobookplayer.ui.theme.AppThemeMode
import kotlin.math.roundToInt

data class UserSettings(
    val playbackSpeed: Float = DEFAULT_SPEED,
    val speedPresets: List<Float> = DEFAULT_SPEED_PRESETS,
    val seekStepSeconds: Int = DEFAULT_SEEK_STEP_SECONDS,
    val autoRewindSeconds: Int = DEFAULT_AUTO_REWIND_SECONDS,
    val themeMode: AppThemeMode = AppThemeMode.OLED,
    val vbrIndexSeekingEnabled: Boolean = false
) {
    companion object {
        const val MIN_SPEED = 0.5f
        const val MAX_SPEED = 3.0f
        const val DEFAULT_SPEED = 1.0f
        val DEFAULT_SPEED_PRESETS = listOf(0.5f, 1.0f, 2.0f)

        const val MIN_SEEK_STEP_SECONDS = 1
        const val MAX_SEEK_STEP_SECONDS = 300
        const val DEFAULT_SEEK_STEP_SECONDS = 10

        const val DEFAULT_AUTO_REWIND_SECONDS = 0
        const val MAX_AUTO_REWIND_SECONDS = 60

        fun clampSpeed(speed: Float): Float {
            return ((speed * 10f).roundToInt() / 10f).coerceIn(MIN_SPEED, MAX_SPEED)
        }
    }
}
