package com.offline.audiobookplayer.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.offline.audiobookplayer.data.local.entity.QueueTrackEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface QueueDao {

    @Query("SELECT * FROM queue_tracks ORDER BY queueIndex ASC")
    fun observeQueue(): Flow<List<QueueTrackEntity>>

    @Query("SELECT * FROM queue_tracks ORDER BY queueIndex ASC")
    suspend fun getQueueSnapshot(): List<QueueTrackEntity>

    @Query("SELECT * FROM queue_tracks WHERE uri = :uri LIMIT 1")
    suspend fun getTrackByUri(uri: String): QueueTrackEntity?

    @Query("SELECT COALESCE(MAX(queueIndex), -1) FROM queue_tracks")
    suspend fun getMaxQueueIndex(): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAllIgnore(tracks: List<QueueTrackEntity>): List<Long>

    @Query(
        """
        UPDATE queue_tracks
        SET lastPositionMs = :positionMs,
            durationMs = CASE WHEN :durationMs > 0 THEN :durationMs ELSE durationMs END,
            lastPlayedTimestamp = :lastPlayedTimestamp
        WHERE uri = :uri
        """
    )
    suspend fun updateTrackProgress(
        uri: String,
        positionMs: Long,
        durationMs: Long,
        lastPlayedTimestamp: Long
    )

    @Query(
        """
        UPDATE queue_tracks
        SET durationMs = :durationMs
        WHERE uri = :uri AND :durationMs > 0 AND durationMs != :durationMs
        """
    )
    suspend fun updateTrackDuration(uri: String, durationMs: Long)

    @Query("UPDATE queue_tracks SET queueIndex = :queueIndex WHERE uri = :uri")
    suspend fun updateQueueIndex(uri: String, queueIndex: Int)

    @Query("DELETE FROM queue_tracks WHERE uri = :uri")
    suspend fun deleteTrackByUri(uri: String)

    @Query("DELETE FROM queue_tracks")
    suspend fun clearQueue()

    @Transaction
    suspend fun replaceOrdering(orderedUris: List<String>) {
        orderedUris.forEachIndexed { index, uri ->
            updateQueueIndex(uri = uri, queueIndex = index)
        }
    }
}
