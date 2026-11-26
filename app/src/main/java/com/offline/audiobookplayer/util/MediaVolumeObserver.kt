package com.offline.audiobookplayer.util

import android.content.Context
import android.database.ContentObserver
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

data class MediaVolumeState(
    val current: Int,
    val max: Int
)

/**
 * Observes system STREAM_MUSIC volume changes safely via ContentObserver,
 * automatically unregistering when collection stops to prevent Context leaks.
 */
object MediaVolumeObserver {

    fun observe(context: Context): Flow<MediaVolumeState> = callbackFlow {
        val appContext = context.applicationContext
        val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        fun snapshot(): MediaVolumeState {
            val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
            val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).coerceIn(0, max)
            return MediaVolumeState(current = current, max = max)
        }

        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                trySend(snapshot())
            }
        }

        appContext.contentResolver.registerContentObserver(
            Settings.System.CONTENT_URI,
            true,
            observer
        )
        trySend(snapshot())

        awaitClose {
            appContext.contentResolver.unregisterContentObserver(observer)
        }
    }.distinctUntilChanged()

    fun setVolume(context: Context, volumeIndex: Int) {
        val audioManager = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val clamped = volumeIndex.coerceIn(0, max)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, clamped, 0)
    }
}
