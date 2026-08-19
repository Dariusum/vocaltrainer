package com.example.vocaltrainer.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.annotation.RequiresPermission
import com.example.vocaltrainer.audio.wav.WavFileWriter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** Nimmt Mono-Mikrofonaudio auf und streamt es direkt in eine WAV-Datei (kein In-Memory-Puffer). */
class VocalRecorder(private val sampleRate: Int) {

    private var audioRecord: AudioRecord? = null
    private var recordThread: Thread? = null
    private var wavWriter: WavFileWriter? = null
    private var currentFile: File? = null
    private val stopRequested = AtomicBoolean(false)

    private val _recordedFrames = MutableStateFlow(0)
    val recordedFrames: StateFlow<Int> = _recordedFrames.asStateFlow()

    @RequiresPermission(android.Manifest.permission.RECORD_AUDIO)
    fun start(outputFile: File) {
        val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val bufferSize = maxOf(minBufferSize, 4096)

        val record = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )
        check(record.state == AudioRecord.STATE_INITIALIZED) { "Mikrofon konnte nicht initialisiert werden" }

        val writer = WavFileWriter(outputFile, sampleRate, channelCount = 1)
        audioRecord = record
        wavWriter = writer
        currentFile = outputFile
        _recordedFrames.value = 0
        stopRequested.set(false)

        record.startRecording()

        val thread = Thread { recordLoop(record, writer, bufferSize) }
        thread.name = "VocalRecorder"
        recordThread = thread
        thread.start()
    }

    /** Stoppt die Aufnahme, schließt die WAV-Datei und gibt die fertige Datei zurück. */
    fun stop(): File? {
        stopRequested.set(true)
        recordThread?.join(500)
        recordThread = null
        audioRecord?.let {
            runCatching { it.stop() }
            it.release()
        }
        audioRecord = null
        wavWriter?.close()
        wavWriter = null
        return currentFile
    }

    private fun recordLoop(record: AudioRecord, writer: WavFileWriter, bufferSizeBytes: Int) {
        val buffer = ShortArray(bufferSizeBytes / 2)
        while (!stopRequested.get()) {
            val read = record.read(buffer, 0, buffer.size)
            if (read > 0) {
                writer.writeFrames(buffer, 0, read)
                _recordedFrames.value += read
            }
        }
    }
}
