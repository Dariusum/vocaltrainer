package com.example.vocaltrainer.audio.wav

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Streamender WAV-Writer: schreibt zunächst einen Platzhalter-Header, hängt
 * PCM-Frames laufend an und korrigiert die Größenfelder erst bei [close], sobald
 * die endgültige Länge bekannt ist. So lassen sich auch lange Aufnahmen direkt
 * auf die Platte schreiben, ohne alles im Speicher zu halten.
 */
class WavFileWriter(file: File, private val sampleRate: Int, private val channelCount: Int) {

    private val raf = RandomAccessFile(file, "rw")
    private var dataBytesWritten = 0L

    init {
        raf.setLength(0)
        writeHeader(0)
    }

    fun writeFrames(buffer: ShortArray, offset: Int, lengthInShorts: Int) {
        val byteBuffer = ByteBuffer.allocate(lengthInShorts * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until lengthInShorts) byteBuffer.putShort(buffer[offset + i])
        raf.write(byteBuffer.array())
        dataBytesWritten += lengthInShorts * 2L
    }

    fun close() {
        writeHeader(dataBytesWritten)
        raf.close()
    }

    private fun writeHeader(dataSize: Long) {
        raf.seek(0)
        val byteRate = sampleRate * channelCount * 2
        val blockAlign = channelCount * 2
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray())
        header.putInt((36 + dataSize).toInt())
        header.put("WAVE".toByteArray())
        header.put("fmt ".toByteArray())
        header.putInt(16)
        header.putShort(1.toShort()) // AudioFormat = PCM
        header.putShort(channelCount.toShort())
        header.putInt(sampleRate)
        header.putInt(byteRate)
        header.putShort(blockAlign.toShort())
        header.putShort(16.toShort()) // BitsPerSample
        header.put("data".toByteArray())
        header.putInt(dataSize.toInt())
        raf.write(header.array())
    }
}
