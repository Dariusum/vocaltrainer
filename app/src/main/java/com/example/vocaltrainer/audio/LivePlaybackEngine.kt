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

enum class PlaybackState { STOPPED, PLAYING, PAUSED }

/**
 * Spielt einen einzelnen Track über [AudioTrack] im Streaming-Modus ab und wendet dabei
 * live die Gesangsreduzierung ([VocalReducer]) an — nur ein Vorhör-Regler, verändert
 * weder die Original-PCM noch das, was währenddessen aufgenommen wird. [VocalSeparator]
 * muss vorab die Stems berechnet haben (nur bei Stereo-Tracks nötig/vorhanden); bei Mono
 * oder fehlender Trennung wird [instrumental] unverändert abgespielt.
 */
class LivePlaybackEngine {

    @Volatile private var vocalReduction: Float = 0f
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

    fun start(instrumental: PcmAudio, vocal: PcmAudio?, startFrame: Int = 0) {
        stop()
        _finished.value = false
        stopRequested.set(false)
        pauseRequested.set(false)

        val channelConfig =
            if (instrumental.channelCount == 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
        val minBufferSize =
            AudioTrack.getMinBufferSize(instrumental.sampleRate, channelConfig, AudioFormat.ENCODING_PCM_16BIT)
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
                    .setSampleRate(instrumental.sampleRate)
                    .setChannelMask(channelConfig)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        audioTrack = track
        track.play()
        _state.value = PlaybackState.PLAYING

        val thread = Thread { playLoop(instrumental, vocal, startFrame, track, bufferSize) }
        thread.name = "LivePlaybackEngine"
        playThread = thread
        thread.start()
    }

    fun setVocalReduction(k: Float) {
        vocalReduction = k.coerceIn(0f, 1f)
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

    private fun playLoop(instrumental: PcmAudio, vocal: PcmAudio?, startFrame: Int, track: AudioTrack, bufferSizeBytes: Int) {
        val channelCount = instrumental.channelCount
        val framesPerChunk = (bufferSizeBytes / 2 / channelCount).coerceAtLeast(1)
        val scratch = ShortArray(framesPerChunk * channelCount)
        var frame = startFrame
        var framesSinceLastLog = 0
        val logIntervalFrames = instrumental.sampleRate // ~einmal pro Sekunde Wiedergabe
        val useStems = channelCount == 2 && vocal != null

        while (!stopRequested.get()) {
            if (frame >= instrumental.frameCount) {
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
            val framesThisChunk = minOf(framesPerChunk, instrumental.frameCount - frame)
            val k = vocalReduction

            if (useStems) {
                VocalReducer.applyReduction(instrumental.samples, vocal.samples, scratch, frame, framesThisChunk, k)
            } else {
                System.arraycopy(instrumental.samples, frame * channelCount, scratch, 0, framesThisChunk * channelCount)
            }

            // Diagnose: einmal pro Sekunde den Peak des abgetrennten Gesangs-Stems in diesem
            // Ausschnitt loggen — beweist, ob das Modell für den aktuellen Abschnitt
            // überhaupt nennenswerten Gesang erkannt hat (statt nur zu vermuten).
            framesSinceLastLog += framesThisChunk
            if (framesSinceLastLog >= logIntervalFrames) {
                framesSinceLastLog = 0
                var vocalPeak = 0
                if (useStems) {
                    val srcOffset = frame * channelCount
                    for (i in 0 until framesThisChunk * channelCount) {
                        val a = abs(vocal.samples[srcOffset + i].toInt())
                        if (a > vocalPeak) vocalPeak = a
                    }
                }
                VocaltrainerLogger.d(
                    "LivePlaybackEngine",
                    "Frame $frame: k=$k, useStems=$useStems, vocalPeak=$vocalPeak"
                )
            }

            track.write(scratch, 0, framesThisChunk * channelCount)
            frame += framesThisChunk
            _positionFrames.value = frame
        }
    }
}
