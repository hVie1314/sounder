package com.offline.audiobookplayer.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.ForwardingSimpleBasePlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * Wraps ExoPlayer to expose dynamic seek-back/seek-forward increments (+/- N seconds from Settings)
 * across MediaSession, Lock Screen, Notification controls, and external media buttons.
 */
@OptIn(UnstableApi::class)
class DynamicSeekPlayer(
    player: Player,
    initialSeekIncrementMs: Long = 10_000L,
    private val onSeekCommitted: (trackUri: String?, targetPositionMs: Long) -> Unit = { _, _ -> }
) : ForwardingSimpleBasePlayer(player) {

    @Volatile
    private var seekIncrementMs: Long = initialSeekIncrementMs.coerceAtLeast(1_000L)

    fun updateSeekIncrementMs(newIncrementMs: Long) {
        val sanitized = newIncrementMs.coerceAtLeast(1_000L)
        if (seekIncrementMs != sanitized) {
            seekIncrementMs = sanitized
            invalidateState()
        }
    }

    override fun getState(): State {
        val currentState = super.getState()
        return currentState.buildUpon()
            .setSeekBackIncrementMs(seekIncrementMs)
            .setSeekForwardIncrementMs(seekIncrementMs)
            .build()
    }

    override fun handleSeek(
        mediaItemIndex: Int,
        positionMs: Long,
        seekCommand: @Player.Command Int
    ): ListenableFuture<*> {
        if ((seekCommand == COMMAND_SEEK_BACK || seekCommand == COMMAND_SEEK_FORWARD) &&
            mediaItemIndex != C.INDEX_UNSET &&
            positionMs != C.TIME_UNSET
        ) {
            val targetPos = positionMs.coerceAtLeast(0L)
            player.seekTo(mediaItemIndex, targetPos)
            onSeekCommitted(player.currentMediaItem?.mediaId, targetPos)
            return Futures.immediateVoidFuture()
        }
        return super.handleSeek(mediaItemIndex, positionMs, seekCommand)
    }
}
