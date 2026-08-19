package com.example.vocaltrainer.audio

/** Interleaviertes 16-Bit-PCM im Speicher (z.B. bei Stereo: L,R,L,R,...). */
class PcmAudio(
    val samples: ShortArray,
    val sampleRate: Int,
    val channelCount: Int
) {
    val frameCount: Int get() = samples.size / channelCount
    val durationMs: Long get() = if (sampleRate <= 0) 0L else frameCount * 1000L / sampleRate
}
