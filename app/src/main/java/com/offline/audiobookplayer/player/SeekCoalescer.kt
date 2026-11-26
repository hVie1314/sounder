package com.offline.audiobookplayer.player

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Coalesces rapid step-seek taps (+/- N seconds) into a single player seek so repeated button
 * presses do not trigger redundant re-buffer cycles on low-RAM devices.
 */
class SeekCoalescer(
    private val scope: CoroutineScope,
    private val debounceMs: Long = DEBOUNCE_MS,
    private val onSeek: (positionMs: Long) -> Unit
) {
    private var pendingJob: Job? = null

    @Volatile
    var latestRequestedMs: Long = -1L
        private set

    fun requestSeek(positionMs: Long) {
        latestRequestedMs = positionMs
        pendingJob?.cancel()
        pendingJob = scope.launch {
            delay(debounceMs)
            latestRequestedMs = -1L
            onSeek(positionMs)
        }
    }

    fun requestSeekImmediate(positionMs: Long) {
        pendingJob?.cancel()
        pendingJob = null
        latestRequestedMs = -1L
        onSeek(positionMs)
    }

    fun cancel() {
        pendingJob?.cancel()
        pendingJob = null
        latestRequestedMs = -1L
    }

    companion object {
        const val DEBOUNCE_MS = 180L
    }
}
