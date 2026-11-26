package com.offline.audiobookplayer.player

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface SleepTimerState {
    data object Inactive : SleepTimerState

    data class Countdown(
        val remainingMs: Long,
        val totalMs: Long,
        val isFadingOut: Boolean
    ) : SleepTimerState

    data class EndOfTrack(
        val isFadingOut: Boolean
    ) : SleepTimerState
}

/**
 * Manages Sleep Timer countdowns and "End of current file" mode with a smooth 3-second fade-out
 * before pausing playback and immediately persisting the playback timestamp.
 */
class SleepTimerManager(
    private val scope: CoroutineScope,
    private val onSetVolume: (Float) -> Unit,
    private val onFadeCompletePause: suspend () -> Unit
) {
    private val _state = MutableStateFlow<SleepTimerState>(SleepTimerState.Inactive)
    val state: StateFlow<SleepTimerState> = _state.asStateFlow()

    private var timerJob: Job? = null

    fun isEndOfTrackActive(): Boolean = _state.value is SleepTimerState.EndOfTrack

    fun startCountdown(durationMs: Long) {
        cancelInternal(restoreVolume = true)
        if (durationMs <= 0L) return

        timerJob = scope.launch {
            var remainingMs = durationMs
            while (remainingMs > 0L) {
                val isFading = remainingMs <= FADE_OUT_DURATION_MS
                if (isFading) {
                    val volume = (remainingMs.toFloat() / FADE_OUT_DURATION_MS).coerceIn(0f, 1f)
                    onSetVolume(volume)
                } else {
                    onSetVolume(1.0f)
                }
                _state.value = SleepTimerState.Countdown(
                    remainingMs = remainingMs,
                    totalMs = durationMs,
                    isFadingOut = isFading
                )

                val stepMs = if (isFading) FADE_TICK_INTERVAL_MS else NORMAL_TICK_INTERVAL_MS
                val sleepDuration = minOf(stepMs, remainingMs)
                delay(sleepDuration)
                remainingMs -= sleepDuration
            }

            onSetVolume(0f)
            _state.value = SleepTimerState.Inactive
            onFadeCompletePause()
            onSetVolume(1.0f)
        }
    }

    fun startEndOfTrack() {
        cancelInternal(restoreVolume = true)
        _state.value = SleepTimerState.EndOfTrack(isFadingOut = false)
    }

    fun onPlaybackProgress(isPlaying: Boolean, positionMs: Long, durationMs: Long) {
        val current = _state.value
        if (current !is SleepTimerState.EndOfTrack) return

        if (!isPlaying || durationMs <= 0L || positionMs < 0L) {
            if (current.isFadingOut) {
                onSetVolume(1.0f)
                _state.value = SleepTimerState.EndOfTrack(isFadingOut = false)
            }
            return
        }

        val remainingMs = durationMs - positionMs
        if (remainingMs in 1L..FADE_OUT_DURATION_MS) {
            val volume = (remainingMs.toFloat() / FADE_OUT_DURATION_MS).coerceIn(0f, 1f)
            onSetVolume(volume)
            _state.value = SleepTimerState.EndOfTrack(isFadingOut = true)
        } else if (remainingMs > FADE_OUT_DURATION_MS && current.isFadingOut) {
            onSetVolume(1.0f)
            _state.value = SleepTimerState.EndOfTrack(isFadingOut = false)
        }
    }

    suspend fun onTrackReachedEnd() {
        if (_state.value !is SleepTimerState.EndOfTrack) return
        _state.value = SleepTimerState.Inactive
        onSetVolume(0f)
        onFadeCompletePause()
        onSetVolume(1.0f)
    }

    fun cancel() {
        cancelInternal(restoreVolume = true)
        _state.value = SleepTimerState.Inactive
    }

    private fun cancelInternal(restoreVolume: Boolean) {
        timerJob?.cancel()
        timerJob = null
        if (restoreVolume) {
            onSetVolume(1.0f)
        }
    }

    companion object {
        const val FADE_OUT_DURATION_MS = 3_000L
        const val NORMAL_TICK_INTERVAL_MS = 500L
        const val FADE_TICK_INTERVAL_MS = 100L
    }
}
