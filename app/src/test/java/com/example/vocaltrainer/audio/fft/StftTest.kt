package com.example.vocaltrainer.audio.fft

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

class ComplexFftTest {

    @Test
    fun `forward then inverse reconstructs a random signal`() {
        val n = 256
        val random = Random(42)
        val re = FloatArray(n) { random.nextFloat() * 2f - 1f }
        val im = FloatArray(n) { random.nextFloat() * 2f - 1f }
        val originalRe = re.copyOf()
        val originalIm = im.copyOf()

        ComplexFft.forward(re, im)
        ComplexFft.inverse(re, im)

        for (i in 0 until n) {
            assertEquals("re[$i]", originalRe[i], re[i], 1e-4f)
            assertEquals("im[$i]", originalIm[i], im[i], 1e-4f)
        }
    }

    @Test
    fun `impulse produces flat spectrum`() {
        val n = 64
        val re = FloatArray(n)
        val im = FloatArray(n)
        re[0] = 1f

        ComplexFft.forward(re, im)

        for (i in 0 until n) {
            assertEquals("re[$i]", 1f, re[i], 1e-5f)
            assertEquals("im[$i]", 0f, im[i], 1e-5f)
        }
    }

    @Test
    fun `pure sine concentrates energy at expected bin`() {
        val n = 256
        val targetBin = 10
        val re = FloatArray(n) { i -> sin(2.0 * PI * targetBin * i / n).toFloat() }
        val im = FloatArray(n)

        ComplexFft.forward(re, im)

        val magnitudes = DoubleArray(n) { i -> Math.hypot(re[i].toDouble(), im[i].toDouble()) }
        val peakBin = magnitudes.indices.maxByOrNull { magnitudes[it] }!!
        // Energie eines reellen Sinus verteilt sich auf Bin k und dessen Spiegel-Bin n-k.
        assertTrue(peakBin == targetBin || peakBin == n - targetBin)
    }
}

class StftTest {

    @Test
    fun `stft then istft reconstructs original signal`() {
        val nFft = 4096
        val hop = 1024
        val dimF = 2048
        val stft = Stft(nFft, hop, dimF)

        // Synthetisches Testsignal: Summe zweier Sinustöne, mehrere Frames lang.
        val sampleRate = 44100
        val durationSeconds = 3.0
        val length = (sampleRate * durationSeconds).toInt()
        val original = FloatArray(length) { i ->
            (0.5 * sin(2.0 * PI * 440.0 * i / sampleRate) + 0.3 * sin(2.0 * PI * 1000.0 * i / sampleRate)).toFloat()
        }

        val padded = stft.padReflect(original)
        val frameCount = stft.frameCount(length)

        val (specRe, specIm) = stft.forward(padded, 0, frameCount)

        val paddedLength = stft.paddedLength(length)
        val outAccum = FloatArray(paddedLength)
        val windowSumAccum = FloatArray(paddedLength)
        stft.accumulateInverse(specRe, specIm, 0, frameCount, outAccum, windowSumAccum)

        val padOffset = stft.padOffset()
        val reconstructed = FloatArray(length)
        for (i in 0 until length) {
            val denom = windowSumAccum[padOffset + i]
            reconstructed[i] = if (denom > 1e-8f) outAccum[padOffset + i] / denom else 0f
        }

        // Ränder (erste/letzte halbe Fensterlänge) dürfen etwas ungenauer sein, die Mitte muss
        // sehr genau übereinstimmen — das beweist Fenster/Padding/Overlap-Add/Hermitesche
        // Rekonstruktion sind insgesamt korrekt verdrahtet.
        val margin = nFft
        var maxDiff = 0f
        for (i in margin until length - margin) {
            val diff = abs(original[i] - reconstructed[i])
            if (diff > maxDiff) maxDiff = diff
        }
        assertTrue("maxDiff in der Mitte war $maxDiff, erwartet < 0.01", maxDiff < 0.01f)
    }

    @Test
    fun `chunked istft matches single-pass istft`() {
        // Bestätigt, dass Overlap-Add über mehrere separate accumulateInverse-Aufrufe
        // (wie beim echten chunk-weisen Modell-Inferenzlauf) dasselbe Ergebnis liefert wie
        // ein einziger Durchlauf über alle Frames — kritisch für VocalSeparator, das den
        // Track in 256-Frame-Portionen verarbeitet statt auf einmal.
        val nFft = 4096
        val hop = 1024
        val dimF = 2048
        val stft = Stft(nFft, hop, dimF)

        val sampleRate = 44100
        val length = sampleRate * 2
        val original = FloatArray(length) { i -> sin(2.0 * PI * 220.0 * i / sampleRate).toFloat() }
        val padded = stft.padReflect(original)
        val frameCount = stft.frameCount(length)
        val (specRe, specIm) = stft.forward(padded, 0, frameCount)

        val paddedLength = stft.paddedLength(length)

        // Einzeldurchlauf.
        val singleOut = FloatArray(paddedLength)
        val singleWin = FloatArray(paddedLength)
        stft.accumulateInverse(specRe, specIm, 0, frameCount, singleOut, singleWin)

        // Chunk-weise in 32er-Portionen.
        val chunkedOut = FloatArray(paddedLength)
        val chunkedWin = FloatArray(paddedLength)
        val chunkSize = 32
        var start = 0
        while (start < frameCount) {
            val count = minOf(chunkSize, frameCount - start)
            val chunkRe = specRe.copyOfRange(start * dimF, (start + count) * dimF)
            val chunkIm = specIm.copyOfRange(start * dimF, (start + count) * dimF)
            stft.accumulateInverse(chunkRe, chunkIm, start, count, chunkedOut, chunkedWin)
            start += count
        }

        for (i in 0 until paddedLength) {
            assertEquals("out[$i]", singleOut[i], chunkedOut[i], 1e-4f)
            assertEquals("win[$i]", singleWin[i], chunkedWin[i], 1e-6f)
        }
    }
}
