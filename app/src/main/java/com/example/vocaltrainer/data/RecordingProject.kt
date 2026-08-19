package com.example.vocaltrainer.data

data class RecordingProject(
    val id: String,
    val title: String,
    val createdAt: Long,
    val durationMs: Long,
    val sampleRate: Int,
    val lastFaderMaster: Float,
    val lastFaderUserVocal: Float,
    val lastFaderOriginalVocal: Float
)
