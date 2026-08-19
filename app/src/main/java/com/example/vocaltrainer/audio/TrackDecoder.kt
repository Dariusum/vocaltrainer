package com.example.vocaltrainer.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Dekodiert eine beliebige lokale Audiodatei (MP3, AAC, OGG, WAV, ...) zu rohem PCM. */
object TrackDecoder {

    private const val TIMEOUT_US = 10_000L

    suspend fun decode(context: Context, uri: Uri): PcmAudio = withContext(Dispatchers.Default) {
        val extractor = MediaExtractor()
        extractor.setDataSource(context, uri, null)
        try {
            var trackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val candidate = extractor.getTrackFormat(i)
                val mime = candidate.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    trackIndex = i
                    format = candidate
                    break
                }
            }
            val selectedFormat = requireNotNull(format) { "Keine Audiospur in der Datei gefunden" }
            extractor.selectTrack(trackIndex)

            val mime = selectedFormat.getString(MediaFormat.KEY_MIME)!!
            val sampleRate = selectedFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channelCount = selectedFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

            val codec = MediaCodec.createDecoderByType(mime)
            codec.configure(selectedFormat, null, null, 0)
            codec.start()

            val output = ShortArrayBuilder()
            try {
                pump(extractor, codec, output)
            } finally {
                codec.stop()
                codec.release()
            }

            PcmAudio(output.toShortArray(), sampleRate, channelCount)
        } finally {
            extractor.release()
        }
    }

    private fun pump(extractor: MediaExtractor, codec: MediaCodec, output: ShortArrayBuilder) {
        val bufferInfo = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false

        while (!outputDone) {
            if (!inputDone) {
                val inputIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                if (inputIndex >= 0) {
                    val inputBuffer = codec.getInputBuffer(inputIndex)!!
                    val sampleSize = extractor.readSampleData(inputBuffer, 0)
                    if (sampleSize < 0) {
                        codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(inputIndex, 0, sampleSize, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }

            var outputIndex = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
            while (outputIndex >= 0) {
                if (bufferInfo.size > 0) {
                    val outputBuffer = codec.getOutputBuffer(outputIndex)!!
                    outputBuffer.position(bufferInfo.offset)
                    outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                    output.appendPcm(outputBuffer)
                }
                codec.releaseOutputBuffer(outputIndex, false)
                if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                    outputDone = true
                    break
                }
                outputIndex = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
            }
        }
    }
}

/** Wächst dynamisch, um decodierte PCM-Chunks unbekannter Gesamtlänge zu sammeln. */
private class ShortArrayBuilder {
    private var array = ShortArray(1_048_576)
    private var size = 0

    fun appendPcm(buffer: ByteBuffer) {
        val shortBuffer = buffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val count = shortBuffer.remaining()
        ensureCapacity(size + count)
        shortBuffer.get(array, size, count)
        size += count
    }

    private fun ensureCapacity(minCapacity: Int) {
        if (minCapacity <= array.size) return
        var newSize = array.size
        while (newSize < minCapacity) newSize *= 2
        array = array.copyOf(newSize)
    }

    fun toShortArray(): ShortArray = array.copyOf(size)
}
