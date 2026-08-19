package com.example.vocaltrainer.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.example.vocaltrainer.log.VocaltrainerLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/** Spielt Original-Track + aufgenommene Stimme live gemischt ab, Fader wirken sofort. */
class RemixPlaybackEngine {

    @Volatile private var gains: MixGains = MixGains.FULL
    @Volatile private var loopEnabled: Boolean = false
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

    fun start(
        original: PcmAudio,
        vocalBandMid: FloatArray,
        userVocal: PcmAudio,
        initialGains: MixGains,
        startFrame: Int = 0
    ) {
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

        val thread = Thread { playLoop(original, vocalBandMid, userVocal, startFrame, track, bufferSize) }
        thread.name = "RemixPlaybackEngine"
        playThread = thread
        thread.start()
    }

    fun setGains(newGains: MixGains) {
        gains = newGains
    }

    fun setLoopEnabled(enabled: Boolean) {
        loopEnabled = enabled
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

    private fun playLoop(
        original: PcmAudio,
        vocalBandMid: FloatArray,
        userVocal: PcmAudio,
        startFrame: Int,
        track: AudioTrack,
        bufferSizeBytes: Int
    ) {
        val framesPerChunk = (bufferSizeBytes / 2 / 2).coerceAtLeast(1)
        val scratch = ShortArray(framesPerChunk * 2)
        var frame = startFrame
        var framesSinceLastLog = 0
        val logIntervalFrames = original.sampleRate // ~einmal pro Sekunde Wiedergabe

        while (!stopRequested.get()) {
            if (frame >= original.frameCount) {
                if (loopEnabled) {
                    frame = 0
                    continue
                } else {
                    _finished.value = true
                    return
                }
            }
            if (pauseRequested.get()) {
                Thread.sleep(20)
                continue
            }
            val framesThisChunk = minOf(framesPerChunk, original.frameCount - frame)
            val currentGains = gains
            AudioMixer.renderChunk(original.samples, vocalBandMid, userVocal.samples, frame, framesThisChunk, currentGains, scratch)

            // Diagnose: einmal pro Sekunde die aktuellen Fader-Werte sowie den Peak der
            // eigenen Stimme in diesem Ausschnitt loggen — beweist, ob userVocal-Samples
            // im aktuellen Abschnitt überhaupt vorhanden/lautstark sind (statt nur zu
            // vermuten, dass die Aufnahme oder die Regler wirken).
            framesSinceLastLog += framesThisChunk
            if (framesSinceLastLog >= logIntervalFrames) {
                framesSinceLastLog = 0
                var userVocalPeak = 0
                val end = minOf(frame + framesThisChunk, userVocal.frameCount)
                for (i in frame until end) {
                    val a = abs(userVocal.samples[i].toInt())
                    if (a > userVocalPeak) userVocalPeak = a
                }
                var outPeak = 0
                for (i in 0 until framesThisChunk * 2) {
                    val a = abs(scratch[i].toInt())
                    if (a > outPeak) outPeak = a
                }
                VocaltrainerLogger.d(
                    "RemixPlaybackEngine",
                    "Frame $frame: gains=$currentGains, userVocalPeak(roh)=$userVocalPeak, outputPeak(gemischt)=$outPeak"
                )
            }

            track.write(scratch, 0, framesThisChunk * 2)
            frame += framesThisChunk
            _positionFrames.value = frame
        }
    }
}
