package com.example.vocaltrainer.audio.fft

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random

/** Direkte O(n²)-Referenz-DFT, nur für Tests — bewusst naiv, um von [FftPlan]/[ComplexFft] unabhängig zu sein. */
private fun referenceDft(re: FloatArray, im: FloatArray, inverse: Boolean): Pair<FloatArray, FloatArray> {
    val n = re.size
    val outRe = FloatArray(n)
    val outIm = FloatArray(n)
    val sign = if (inverse) 1.0 else -1.0
    for (k in 0 until n) {
        var sumRe = 0.0
        var sumIm = 0.0
        for (t in 0 until n) {
            val angle = sign * 2.0 * Math.PI * k * t / n
            val cos = Math.cos(angle)
            val sin = Math.sin(angle)
            sumRe += re[t] * cos - im[t] * sin
            sumIm += re[t] * sin + im[t] * cos
        }
        if (inverse) {
            outRe[k] = (sumRe / n).toFloat()
            outIm[k] = (sumIm / n).toFloat()
        } else {
            outRe[k] = sumRe.toFloat()
            outIm[k] = sumIm.toFloat()
        }
    }
    return outRe to outIm
}

/** Referenzwert für einen einzelnen DFT-Ausgabe-Bin [k], O(n) statt der vollen O(n²)-DFT. */
private fun referenceDftBin(re: FloatArray, im: FloatArray, k: Int, inverse: Boolean): Pair<Double, Double> {
    val n = re.size
    val sign = if (inverse) 1.0 else -1.0
    var sumRe = 0.0
    var sumIm = 0.0
    for (t in 0 until n) {
        val angle = sign * 2.0 * Math.PI * k * t / n
        val cos = Math.cos(angle)
        val sin = Math.sin(angle)
        sumRe += re[t] * cos - im[t] * sin
        sumIm += re[t] * sin + im[t] * cos
    }
    return if (inverse) sumRe / n to sumIm / n else sumRe to sumIm
}

class FftPlanTest {

    private fun randomSignal(n: Int, seed: Int): Pair<FloatArray, FloatArray> {
        val random = Random(seed)
        return FloatArray(n) { random.nextFloat() * 2f - 1f } to FloatArray(n) { random.nextFloat() * 2f - 1f }
    }

    @Test
    fun `matches reference DFT for odd non-power-of-two lengths`() {
        for (n in intArrayOf(3, 5, 6, 9, 25, 100, 257)) {
            val (re, im) = randomSignal(n, seed = n)
            val (expectedRe, expectedIm) = referenceDft(re, im, inverse = false)

            val actualRe = re.copyOf()
            val actualIm = im.copyOf()
            FftPlan(n).forward(actualRe, actualIm)

            for (i in 0 until n) {
                assertEquals("n=$n re[$i]", expectedRe[i], actualRe[i], 1e-2f)
                assertEquals("n=$n im[$i]", expectedIm[i], actualIm[i], 1e-2f)
            }
        }
    }

    @Test
    fun `matches reference inverse DFT for odd non-power-of-two lengths`() {
        for (n in intArrayOf(3, 5, 6, 9, 25, 100, 257)) {
            val (re, im) = randomSignal(n, seed = n + 1000)
            val (expectedRe, expectedIm) = referenceDft(re, im, inverse = true)

            val actualRe = re.copyOf()
            val actualIm = im.copyOf()
            FftPlan(n).inverse(actualRe, actualIm)

            for (i in 0 until n) {
                assertEquals("n=$n re[$i]", expectedRe[i], actualRe[i], 1e-2f)
                assertEquals("n=$n im[$i]", expectedIm[i], actualIm[i], 1e-2f)
            }
        }
    }

    @Test
    fun `forward then inverse reconstructs a random signal for non-power-of-two length`() {
        for (n in intArrayOf(3, 6, 100, 6144)) {
            val (re, im) = randomSignal(n, seed = n + 2000)
            val originalRe = re.copyOf()
            val originalIm = im.copyOf()

            val plan = FftPlan(n)
            plan.forward(re, im)
            plan.inverse(re, im)

            for (i in 0 until n) {
                assertEquals("n=$n re[$i]", originalRe[i], re[i], 1e-3f)
                assertEquals("n=$n im[$i]", originalIm[i], im[i], 1e-3f)
            }
        }
    }

    @Test
    fun `matches ComplexFft directly for power-of-two lengths`() {
        for (n in intArrayOf(64, 256, 4096)) {
            val (re, im) = randomSignal(n, seed = n + 3000)

            val viaPlanRe = re.copyOf()
            val viaPlanIm = im.copyOf()
            FftPlan(n).forward(viaPlanRe, viaPlanIm)

            val viaDirectRe = re.copyOf()
            val viaDirectIm = im.copyOf()
            ComplexFft.forward(viaDirectRe, viaDirectIm)

            for (i in 0 until n) {
                assertEquals("n=$n re[$i]", viaDirectRe[i], viaPlanRe[i], 1e-5f)
                assertEquals("n=$n im[$i]", viaDirectIm[i], viaPlanIm[i], 1e-5f)
            }
        }
    }

    @Test
    fun `production Voc_FT n_fft matches reference DFT on a small subsampled check`() {
        // Volle O(n²)-Referenz-DFT für n=6144 wäre in einem Unit-Test zu langsam (~38 Mio.
        // komplexe Multiplikationen pro Aufruf) - stattdessen wird nur ein Sinus-Ton geprüft,
        // dessen Energiekonzentration am erwarteten Bin analytisch bekannt ist (analog zum
        // bestehenden ComplexFftTest), um n=6144 gezielt end-to-end abzudecken.
        val n = 6144
        val targetBin = 37
        val re = FloatArray(n) { i -> kotlin.math.sin(2.0 * Math.PI * targetBin * i / n).toFloat() }
        val im = FloatArray(n)

        FftPlan(n).forward(re, im)

        val magnitudes = DoubleArray(n) { i -> Math.hypot(re[i].toDouble(), im[i].toDouble()) }
        val peakBin = magnitudes.indices.maxByOrNull { magnitudes[it] }!!
        assertEquals(true, peakBin == targetBin || peakBin == n - targetBin)
    }

    @Test
    fun `production Voc_FT n_fft matches per-bin reference DFT magnitude exactly (no scale bug)`() {
        // Deckt gezielt einen Verdacht ab: der Vergleich zweier realer Geräte-Logs zeigte am
        // exakt selben Frame eines Tracks eine ~8x kleinere vocalPeak-Amplitude mit dem neuen
        // Voc_FT-Modell (n_fft=6144, Bluestein-Pfad) gegenüber dem alten 9482-Modell
        // (n_fft=4096, direkter Zweierpotenz-Pfad) - obwohl die bisherigen Tests (Rundreise,
        // kleine Referenz-DFTs bis n=257) unauffällig waren. Die bisherigen n=6144-Tests
        // prüften nur Rundreise (Skalierungsfehler würden sich dabei durch Vorwärts+Rückwärts
        // gegenseitig aufheben!) oder Bin-Konzentration (skaleninvariant) - keiner davon hätte
        // einen reinen Amplituden-/Skalierungsfehler aufgedeckt. Dieser Test prüft die absolute
        // Magnitude einzelner Bins gegen eine direkte O(n)-Referenzsumme (nicht die volle
        // O(n²)-DFT, die für n=6144 in einem Unit-Test zu langsam wäre).
        val n = 6144
        val random = Random(6144)
        val re = FloatArray(n) { random.nextFloat() * 2f - 1f }
        val im = FloatArray(n) { random.nextFloat() * 2f - 1f }

        val actualRe = re.copyOf()
        val actualIm = im.copyOf()
        FftPlan(n).forward(actualRe, actualIm)

        for (k in intArrayOf(0, 1, 7, 100, 1500, 3071, 3072, 4500, 6000, 6143)) {
            val (expectedRe, expectedIm) = referenceDftBin(re, im, k, inverse = false)
            assertEquals("re[$k]", expectedRe, actualRe[k].toDouble(), 1e-1)
            assertEquals("im[$k]", expectedIm, actualIm[k].toDouble(), 1e-1)
        }
    }
}
