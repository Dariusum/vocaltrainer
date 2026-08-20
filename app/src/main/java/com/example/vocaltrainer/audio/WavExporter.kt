package com.example.vocaltrainer.audio

import com.example.vocaltrainer.audio.wav.WavFileWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStream

/**
 * Rendert Original-Track + Aufnahme mit den aktuell gewählten Reglern (v1: nur WAV,
 * verlustfrei, keine Encoder-/Lizenz-Fragen) zu einer einzelnen Datei. Wird zunächst
 * in eine temporäre, seekbare Datei geschrieben (der WAV-Header muss nach hinten
 * korrigiert werden können), dann komplett in den vom Aufrufer bereitgestellten
 * [OutputStream] (z.B. von einem SAF-CreateDocument-Uri) kopiert.
 */
object WavExporter {

    private const val CHUNK_FRAMES = 65536

    suspend fun export(
        instrumental: PcmAudio,
        originalVocalStem: PcmAudio,
        userVocal: PcmAudio,
        gains: MixGains,
        out: OutputStream
    ) = withContext(Dispatchers.IO) {
        val tempFile = File.createTempFile("vocaltrainer_export", ".wav")
        try {
            val writer = WavFileWriter(tempFile, instrumental.sampleRate, 2)
            val scratch = ShortArray(CHUNK_FRAMES * 2)
            var frame = 0
            while (frame < instrumental.frameCount) {
                val framesThisChunk = minOf(CHUNK_FRAMES, instrumental.frameCount - frame)
                AudioMixer.renderChunk(
                    instrumental.samples, originalVocalStem.samples, userVocal.samples,
                    frame, framesThisChunk, gains, scratch
                )
                writer.writeFrames(scratch, 0, framesThisChunk * 2)
                frame += framesThisChunk
            }
            writer.close()
            tempFile.inputStream().use { input -> input.copyTo(out) }
        } finally {
            tempFile.delete()
        }
    }
}
