package com.offline.audiobookplayer.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "playback_state")
data class PlaybackStateEntity(
    @PrimaryKey
    val id: Int = SINGLETON_ID,
    val activeTrackUri: String?,
    val activeQueueIndex: Int,
    val positionMs: Long,
    val durationMs: Long,
    val lastPlayedTimestamp: Long
) {
    companion object {
        const val SINGLETON_ID = 1
    }
}
