package com.example.vocaltrainer

import android.app.Application
import com.example.vocaltrainer.data.RecordingRepository

class VocaltrainerApp : Application() {

    val recordingRepository: RecordingRepository by lazy { RecordingRepository(applicationContext) }
}
