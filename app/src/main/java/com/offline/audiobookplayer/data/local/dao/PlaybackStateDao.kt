package com.offline.audiobookplayer.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.offline.audiobookplayer.data.local.entity.PlaybackStateEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaybackStateDao {

    @Query("SELECT * FROM playback_state WHERE id = ${PlaybackStateEntity.SINGLETON_ID} LIMIT 1")
    fun observePlaybackState(): Flow<PlaybackStateEntity?>

    @Query("SELECT * FROM playback_state WHERE id = ${PlaybackStateEntity.SINGLETON_ID} LIMIT 1")
    suspend fun getPlaybackState(): PlaybackStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPlaybackState(state: PlaybackStateEntity)

    @Query("DELETE FROM playback_state")
    suspend fun clearPlaybackState()
}
