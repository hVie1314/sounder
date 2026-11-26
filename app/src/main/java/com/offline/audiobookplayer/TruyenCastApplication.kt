package com.offline.audiobookplayer

import android.app.Application
import com.offline.audiobookplayer.di.AppContainer

/**
 * Application entry point for TruyenCast hosting the lightweight manual DI container.
 */
class TruyenCastApplication : Application() {

    lateinit var appContainer: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        appContainer = AppContainer(this)
    }
}
