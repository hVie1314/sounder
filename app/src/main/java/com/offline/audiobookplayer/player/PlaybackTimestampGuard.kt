package com.offline.audiobookplayer.player

enum class SaveReason {
    PERIODIC_TICK,
    PAUSE,
    USER_SEEK,
    TRACK_SWITCH,
    AUDIO_BECOMING_NOISY,
    SLEEP_TIMER,
    TRACK_ENDED,
    SERVICE_TEARDOWN
}

sealed interface PositionSaveDecision {
    data class Persist(val positionMs: Long) : PositionSaveDecision
    data class Skip(val reason: String) : PositionSaveDecision
}

/**
 * Pure domain guard implementing the mandatory Anti-Zero-Overwrite architecture.
 * Prevents player initialization, buffering, or error states from clobbering saved progress with 0L.
 */
object PlaybackTimestampGuard {

    fun evaluate(
        currentSavedPositionMs: Long,
        candidatePositionMs: Long,
        durationMs: Long,
        reason: SaveReason,
        isInitialSeekPending: Boolean,
        hasPlayerError: Boolean
    ): PositionSaveDecision {
        if (candidatePositionMs < 0L) {
            return PositionSaveDecision.Skip("Invalid negative or unset position: $candidatePositionMs")
        }

        if (hasPlayerError && reason != SaveReason.USER_SEEK) {
            return PositionSaveDecision.Skip("Blocked timestamp write during player error state ($reason)")
        }

        if (isInitialSeekPending &&
            reason != SaveReason.USER_SEEK &&
            reason != SaveReason.TRACK_ENDED
        ) {
            return PositionSaveDecision.Skip("Blocked timestamp write while initial resume seek is pending ($reason)")
        }

        val clampedPositionMs = if (durationMs > 0L) {
            candidatePositionMs.coerceIn(0L, durationMs)
        } else {
            candidatePositionMs.coerceAtLeast(0L)
        }

        if (clampedPositionMs == 0L) {
            val isZeroAllowed = reason == SaveReason.USER_SEEK || reason == SaveReason.TRACK_ENDED
            if (!isZeroAllowed && currentSavedPositionMs > 0L) {
                return PositionSaveDecision.Skip(
                    "Anti-Zero-Overwrite Guard blocked overwriting saved ${currentSavedPositionMs}ms with 0L ($reason)"
                )
            }
        }

        return PositionSaveDecision.Persist(clampedPositionMs)
    }
}
