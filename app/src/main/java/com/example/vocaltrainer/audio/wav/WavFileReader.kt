package com.example.vocaltrainer.audio.wav

import com.example.vocaltrainer.audio.PcmAudio
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Liest eine WAV-Datei mit kanonischem 44-Byte-PCM-Header (wie von [WavFileWriter] erzeugt). */
object WavFileReader {

    fun read(file: File): PcmAudio {
        RandomAccessFile(file, "r").use { raf ->
            val header = ByteArray(44)
            raf.readFully(header)
            require(String(header, 0, 4, Charsets.US_ASCII) == "RIFF" &&
                String(header, 8, 4, Charsets.US_ASCII) == "WAVE") {
                "Keine gültige WAV-Datei: ${file.name}"
            }
            val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            val channelCount = buffer.getShort(22).toInt()
            val sampleRate = buffer.getInt(24)
            val dataSize = buffer.getInt(40)

            val dataBytes = ByteArray(dataSize)
            raf.readFully(dataBytes)
            val samples = ShortArray(dataSize / 2)
            ByteBuffer.wrap(dataBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(samples)

            return PcmAudio(samples, sampleRate, channelCount)
        }
    }
}
