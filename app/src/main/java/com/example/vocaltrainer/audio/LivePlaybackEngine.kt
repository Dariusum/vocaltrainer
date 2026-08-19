package com.example.vocaltrainer.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean

enum class PlaybackState { STOPPED, PLAYING, PAUSED }

/**
 * Spielt einen einzelnen Track über [AudioTrack] im Streaming-Modus ab und wendet dabei
 * live die Gesangsreduzierung ([VocalReducer]) an — nur ein Vorhör-Regler, verändert
 * weder die Original-PCM noch das, was währenddessen aufgenommen wird.
 */
class LivePlaybackEngine {

    @Volatile private var vocalReduction: Float = 0f
    private var audioTrack: AudioTrack? = null
    private var playThread: Thread? = null
    private val stopRequested = AtomicBoolean(false)
    private val pauseRequested = AtomicBoolean(false)

    private val _state = MutableStateFlow(PlaybackState.STOPPED)
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private val _positionFrames = MutableStateFlow(0)
    val positionFrames: StateFlow<Int> = _positionFrames.asStateFlow()

    private val _finished = MutableStateFlow(false)
    val finished: StateFlow<Boolean> = _finished.asStateFlow()

    fun start(pcm: PcmAudio, startFrame: Int = 0) {
        stop()
        _finished.value = false
        stopRequested.set(false)
        pauseRequested.set(false)

        val channelConfig = if (pcm.channelCount == 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
        val minBufferSize = AudioTrack.getMinBufferSize(pcm.sampleRate, channelConfig, AudioFormat.ENCODING_PCM_16BIT)
        val bufferSize = maxOf(minBufferSize, 8192)

        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(pcm.sampleRate)
                    .setChannelMask(channelConfig)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        audioTrack = track
        track.play()
        _state.value = PlaybackState.PLAYING

        val thread = Thread { playLoop(pcm, startFrame, track, bufferSize) }
        thread.name = "LivePlaybackEngine"
        playThread = thread
        thread.start()
    }

    fun setVocalReduction(k: Float) {
        vocalReduction = k.coerceIn(0f, 1f)
    }

    fun pause() {
        pauseRequested.set(true)
        audioTrack?.pause()
        _state.value = PlaybackState.PAUSED
    }

    fun resume() {
        pauseRequested.set(false)
        audioTrack?.play()
        _state.value = PlaybackState.PLAYING
    }

    fun stop() {
        stopRequested.set(true)
        playThread?.join(500)
        playThread = null
        audioTrack?.let {
            runCatching { it.stop() }
            it.release()
        }
        audioTrack = null
        _state.value = PlaybackState.STOPPED
    }

    private fun playLoop(pcm: PcmAudio, startFrame: Int, track: AudioTrack, bufferSizeBytes: Int) {
        val channelCount = pcm.channelCount
        val framesPerChunk = (bufferSizeBytes / 2 / channelCount).coerceAtLeast(1)
        val scratch = ShortArray(framesPerChunk * channelCount)
        var frame = startFrame

        while (!stopRequested.get() && frame < pcm.frameCount) {
            if (pauseRequested.get()) {
                Thread.sleep(20)
                continue
            }
            val framesThisChunk = minOf(framesPerChunk, pcm.frameCount - frame)
            val k = vocalReduction

            if (channelCount == 2 && k > 0f) {
                VocalReducer.applyCancellation(pcm.samples, scratch, frame, framesThisChunk, k)
            } else {
                System.arraycopy(pcm.samples, frame * channelCount, scratch, 0, framesThisChunk * channelCount)
            }
            track.write(scratch, 0, framesThisChunk * channelCount)
            frame += framesThisChunk
            _positionFrames.value = frame
        }
        if (!stopRequested.get()) {
            _finished.value = true
        }
    }
}
