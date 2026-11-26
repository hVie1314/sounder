package com.offline.audiobookplayer.di

import android.content.Context
import com.offline.audiobookplayer.data.local.SettingsDataStore
import com.offline.audiobookplayer.data.local.TruyenCastDatabase
import com.offline.audiobookplayer.data.repository.AudiobookRepository
import com.offline.audiobookplayer.player.SleepTimerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Lightweight manual Dependency Injection container keeping APK footprint minimal.
 */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val database: TruyenCastDatabase by lazy {
        TruyenCastDatabase.create(appContext)
    }

    val settingsDataStore: SettingsDataStore by lazy {
        SettingsDataStore(appContext)
    }

    val audiobookRepository: AudiobookRepository by lazy {
        AudiobookRepository(database)
    }

    @Volatile
    private var volumeSetter: ((Float) -> Unit)? = null

    @Volatile
    private var fadeCompletePauser: (suspend () -> Unit)? = null

    val sleepTimerManager: SleepTimerManager by lazy {
        SleepTimerManager(
            scope = appScope,
            onSetVolume = { volume -> volumeSetter?.invoke(volume) },
            onFadeCompletePause = { fadeCompletePauser?.invoke() }
        )
    }

    fun bindSleepTimerCallbacks(
        setVolume: (Float) -> Unit,
        fadeCompletePause: suspend () -> Unit
    ) {
        volumeSetter = setVolume
        fadeCompletePauser = fadeCompletePause
    }

    fun unbindSleepTimerCallbacks() {
        volumeSetter = null
        fadeCompletePauser = null
    }
}
