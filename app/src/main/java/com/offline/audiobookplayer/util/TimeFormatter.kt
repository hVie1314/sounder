package com.offline.audiobookplayer.util

import java.util.Locale

sealed interface JumpTimeValidation {
    data class Valid(val targetPositionMs: Long) : JumpTimeValidation
    data class Invalid(val error: JumpTimeError) : JumpTimeValidation
}

enum class JumpTimeError {
    EMPTY_INPUT,
    INVALID_NUMBER,
    NEGATIVE_VALUE,
    MINUTES_OUT_OF_RANGE,
    SECONDS_OUT_OF_RANGE,
    UNKNOWN_DURATION,
    EXCEEDS_DURATION,
    OVERFLOW
}

/**
 * 64-bit safe time formatting and Jump-to-Time parsing utilities.
 * Supports audiobooks exceeding 100+ hours without 24h wraparound or 32-bit Int overflow.
 */
object TimeFormatter {

    private const val MILLIS_PER_SECOND = 1_000L
    private const val SECONDS_PER_MINUTE = 60L
    private const val SECONDS_PER_HOUR = 3_600L

    fun formatDuration(durationMs: Long): String {
        if (durationMs <= 0L) return "00:00:00"
        val totalSeconds = durationMs / MILLIS_PER_SECOND
        val hours = totalSeconds / SECONDS_PER_HOUR
        val minutes = (totalSeconds % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE
        val seconds = totalSeconds % SECONDS_PER_MINUTE
        return String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
    }

    fun parseAndValidateJumpTime(
        hoursText: String,
        minutesText: String,
        secondsText: String,
        totalDurationMs: Long
    ): JumpTimeValidation {
        val hStr = hoursText.trim()
        val mStr = minutesText.trim()
        val sStr = secondsText.trim()

        if (hStr.isEmpty() && mStr.isEmpty() && sStr.isEmpty()) {
            return JumpTimeValidation.Invalid(JumpTimeError.EMPTY_INPUT)
        }

        val hours = parsePart(hStr) ?: return JumpTimeValidation.Invalid(JumpTimeError.INVALID_NUMBER)
        val minutes = parsePart(mStr) ?: return JumpTimeValidation.Invalid(JumpTimeError.INVALID_NUMBER)
        val seconds = parsePart(sStr) ?: return JumpTimeValidation.Invalid(JumpTimeError.INVALID_NUMBER)

        if (hours < 0L || minutes < 0L || seconds < 0L) {
            return JumpTimeValidation.Invalid(JumpTimeError.NEGATIVE_VALUE)
        }
        if (minutes >= SECONDS_PER_MINUTE) {
            return JumpTimeValidation.Invalid(JumpTimeError.MINUTES_OUT_OF_RANGE)
        }
        if (seconds >= SECONDS_PER_MINUTE) {
            return JumpTimeValidation.Invalid(JumpTimeError.SECONDS_OUT_OF_RANGE)
        }
        if (totalDurationMs <= 0L) {
            return JumpTimeValidation.Invalid(JumpTimeError.UNKNOWN_DURATION)
        }

        val targetMs = try {
            val hourSeconds = Math.multiplyExact(hours, SECONDS_PER_HOUR)
            val minuteSeconds = Math.multiplyExact(minutes, SECONDS_PER_MINUTE)
            val totalSeconds = Math.addExact(Math.addExact(hourSeconds, minuteSeconds), seconds)
            Math.multiplyExact(totalSeconds, MILLIS_PER_SECOND)
        } catch (_: ArithmeticException) {
            return JumpTimeValidation.Invalid(JumpTimeError.OVERFLOW)
        }

        if (targetMs > totalDurationMs) {
            return JumpTimeValidation.Invalid(JumpTimeError.EXCEEDS_DURATION)
        }

        return JumpTimeValidation.Valid(targetMs)
    }

    fun extractCleanTrackTitle(fileName: String): String {
        val trimmed = fileName.trim()
        if (trimmed.isEmpty()) return "Unknown Audio"
        val cleaned = if (trimmed.endsWith(".mp3", ignoreCase = true)) {
            trimmed.dropLast(4).trim()
        } else {
            trimmed
        }
        return cleaned.ifEmpty { trimmed }
    }

    private fun parsePart(raw: String): Long? {
        if (raw.isEmpty()) return 0L
        return raw.toLongOrNull()
    }
}
