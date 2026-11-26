package com.offline.audiobookplayer.player

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Watchdog that detects when ExoPlayer stalls in `STATE_BUFFERING` after a seek
 * (e.g. stale SAF file descriptor or transient storage stall), evicts the cached FD,
 * and cleanly retries the seek once (`stop()` -> `seekTo()` -> `prepare()`).
 */
@OptIn(UnstableApi::class)
class SeekTimeoutWatchdog(
    private val scope: CoroutineScope,
    private val player: ExoPlayer,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    private val onBeforeRetry: (uri: Uri?) -> Unit = {},
    private val onSeekFailed: ((targetMs: Long) -> Unit)? = null
) {
    private var watchJob: Job? = null
    private var retryCount = 0

    fun onSeekStarted(targetMs: Long) {
        watchJob?.cancel()
        retryCount = 0
        startWatch(targetMs)
    }

    fun cancel() {
        watchJob?.cancel()
        watchJob = null
        retryCount = 0
    }

    private fun startWatch(targetMs: Long) {
        watchJob = scope.launch {
            delay(timeoutMs)
            if (player.playbackState == Player.STATE_BUFFERING) {
                if (retryCount < MAX_RETRIES) {
                    retryCount++
                    val wasPlaying = player.playWhenReady
                    val currentIndex = player.currentMediaItemIndex.coerceAtLeast(0)
                    val currentUri = player.currentMediaItem?.localConfiguration?.uri
                    onBeforeRetry(currentUri)

                    // Important: seekTo MUST precede prepare() after stop() so ExoPlayer opens
                    // directly at targetMs rather than loading offset 0 first.
                    player.stop()
                    player.seekTo(currentIndex, targetMs.coerceAtLeast(0L))
                    player.prepare()
                    player.playWhenReady = wasPlaying
                    startWatch(targetMs)
                } else {
                    onSeekFailed?.invoke(targetMs)
                }
            }
        }
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 10_000L
        const val MAX_RETRIES = 1
    }
}
