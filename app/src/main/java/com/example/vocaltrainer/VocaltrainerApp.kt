package com.example.vocaltrainer

import android.app.Application
import com.example.vocaltrainer.data.RecordingRepository
import com.example.vocaltrainer.log.VocaltrainerLogger

class VocaltrainerApp : Application() {

    val recordingRepository: RecordingRepository by lazy { RecordingRepository(applicationContext) }

    override fun onCreate() {
        super.onCreate()
        VocaltrainerLogger.init(this)
    }
}
