package com.offline.audiobookplayer.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.offline.audiobookplayer.data.local.dao.PlaybackStateDao
import com.offline.audiobookplayer.data.local.dao.QueueDao
import com.offline.audiobookplayer.data.local.entity.PlaybackStateEntity
import com.offline.audiobookplayer.data.local.entity.QueueTrackEntity

@Database(
    entities = [
        QueueTrackEntity::class,
        PlaybackStateEntity::class
    ],
    version = 1,
    exportSchema = true
)
abstract class TruyenCastDatabase : RoomDatabase() {

    abstract fun queueDao(): QueueDao
    abstract fun playbackStateDao(): PlaybackStateDao

    companion object {
        private const val DATABASE_NAME = "truyencast.db"

        fun create(context: Context): TruyenCastDatabase {
            return Room.databaseBuilder(
                context.applicationContext,
                TruyenCastDatabase::class.java,
                DATABASE_NAME
            )
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .build()
        }
    }
}
