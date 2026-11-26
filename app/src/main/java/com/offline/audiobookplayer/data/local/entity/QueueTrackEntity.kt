package com.offline.audiobookplayer.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "queue_tracks",
    indices = [
        Index(value = ["queueIndex"]),
        Index(value = ["uri"], unique = true)
    ]
)
data class QueueTrackEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val uri: String,
    val fileName: String,
    val title: String,
    val fileSizeBytes: Long,
    val durationMs: Long,
    val lastPositionMs: Long,
    val queueIndex: Int,
    val lastPlayedTimestamp: Long,
    val addedTimestamp: Long
)
