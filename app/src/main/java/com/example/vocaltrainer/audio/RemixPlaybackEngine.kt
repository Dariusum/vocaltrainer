package com.example.vocaltrainer.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean

/** Spielt Original-Track + aufgenommene Stimme live gemischt ab, Fader wirken sofort. */
class RemixPlaybackEngine {

    @Volatile private var gains: MixGains = MixGains.FULL
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

    fun start(original: PcmAudio, userVocal: PcmAudio, initialGains: MixGains, startFrame: Int = 0) {
        stop()
        gains = initialGains
        _finished.value = false
        stopRequested.set(false)
        pauseRequested.set(false)

        val minBufferSize = AudioTrack.getMinBufferSize(
            original.sampleRate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT
        )
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
                    .setSampleRate(original.sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        audioTrack = track
        track.play()
        _state.value = PlaybackState.PLAYING

        val thread = Thread { playLoop(original, userVocal, startFrame, track, bufferSize) }
        thread.name = "RemixPlaybackEngine"
        playThread = thread
        thread.start()
    }

    fun setGains(newGains: MixGains) {
        gains = newGains
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

    private fun playLoop(original: PcmAudio, userVocal: PcmAudio, startFrame: Int, track: AudioTrack, bufferSizeBytes: Int) {
        val framesPerChunk = (bufferSizeBytes / 2 / 2).coerceAtLeast(1)
        val scratch = ShortArray(framesPerChunk * 2)
        var frame = startFrame

        while (!stopRequested.get() && frame < original.frameCount) {
            if (pauseRequested.get()) {
                Thread.sleep(20)
                continue
            }
            val framesThisChunk = minOf(framesPerChunk, original.frameCount - frame)
            AudioMixer.renderChunk(original.samples, userVocal.samples, frame, framesThisChunk, gains, scratch)
            track.write(scratch, 0, framesThisChunk * 2)
            frame += framesThisChunk
            _positionFrames.value = frame
        }
        if (!stopRequested.get()) {
            _finished.value = true
        }
    }
}
