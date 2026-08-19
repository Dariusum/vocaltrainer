package com.example.vocaltrainer.ui.widget

import com.example.vocaltrainer.audio.PcmAudio
import kotlin.math.abs
import kotlin.math.max

/** Reduziert eine dekodierte PCM-Spur auf eine kleine Anzahl Peak-Buckets für [WaveformView]. */
object WaveformPeaks {

    fun compute(pcm: PcmAudio, bucketCount: Int = 300): FloatArray {
        if (pcm.frameCount == 0) return FloatArray(0)
        val framesPerBucket = max(1, pcm.frameCount / bucketCount)
        val result = FloatArray(bucketCount)
        var maxPeak = 1

        for (b in 0 until bucketCount) {
            val startFrame = b * framesPerBucket
            if (startFrame >= pcm.frameCount) break
            val endFrame = minOf(startFrame + framesPerBucket, pcm.frameCount)
            var peak = 0
            for (frame in startFrame until endFrame) {
                for (ch in 0 until pcm.channelCount) {
                    val sample = abs(pcm.samples[frame * pcm.channelCount + ch].toInt())
                    if (sample > peak) peak = sample
                }
            }
            result[b] = peak.toFloat()
            if (peak > maxPeak) maxPeak = peak
        }
        for (b in result.indices) result[b] = result[b] / maxPeak
        return result
    }
}
