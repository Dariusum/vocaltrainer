package com.example.vocaltrainer

import android.app.Application
import com.example.vocaltrainer.data.PlaylistRepository
import com.example.vocaltrainer.data.RecentTracksStore
import com.example.vocaltrainer.data.RecordingRepository
import com.example.vocaltrainer.data.StemsCache
import com.example.vocaltrainer.log.VocaltrainerLogger

class VocaltrainerApp : Application() {

    val recordingRepository: RecordingRepository by lazy { RecordingRepository(applicationContext) }
    val playlistRepository: PlaylistRepository by lazy { PlaylistRepository(applicationContext) }
    val recentTracksStore: RecentTracksStore by lazy { RecentTracksStore(applicationContext) }
    val stemsCache: StemsCache by lazy { StemsCache(applicationContext) }

    override fun onCreate() {
        super.onCreate()
        VocaltrainerLogger.init(this)
    }
}
