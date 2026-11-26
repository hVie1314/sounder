package com.offline.audiobookplayer.ui.player

import com.offline.audiobookplayer.data.local.entity.QueueTrackEntity
import com.offline.audiobookplayer.data.model.UserSettings
import com.offline.audiobookplayer.player.SleepTimerState
import com.offline.audiobookplayer.util.MediaVolumeState

data class PlayerUiState(
    val queue: List<QueueTrackEntity> = emptyList(),
    val activeTrackIndex: Int = 0,
    val activeTrack: QueueTrackEntity? = null,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val currentPositionMs: Long = 0L,
    val durationMs: Long = 0L,
    val settings: UserSettings = UserSettings(),
    val sleepTimerState: SleepTimerState = SleepTimerState.Inactive,
    val volumeState: MediaVolumeState = MediaVolumeState(current = 8, max = 15),
    val isImporting: Boolean = false,
    val statusMessage: String? = null,
    val errorMessage: String? = null
) {
    val hasTracks: Boolean get() = queue.isNotEmpty()
    val queuePositionLabel: String
        get() = if (queue.isEmpty()) "0 / 0" else "${activeTrackIndex + 1} / ${queue.size}"
}
