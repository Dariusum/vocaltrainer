package com.example.vocaltrainer.audio.fft

/**
 * FFT für eine feste Länge [n], die auch Nicht-Zweierpotenzen unterstützt. Manche MDX-Net-
 * Modelle (z.B. Voc_FT: n_fft=6144=2^11·3) verwenden ein n_fft, das keine Zweierpotenz ist —
 * FFT-Effizienz war bei der Modellwahl offenbar kein Kriterium, sondern Trennqualität.
 *
 * Für Zweierpotenzen läuft direkt [ComplexFft] (schnellster Pfad, unverändert). Für andere
 * Längen kommt Bluesteins Algorithmus (Chirp-Z-Transformation) zum Einsatz: er drückt die DFT
 * beliebiger Länge N über die Identität k·n = (k² + n² − (n−k)²)/2 als lineare Faltung aus,
 * die wiederum als zirkuläre Faltung über eine Zweierpotenz-FFT der Länge M ≥ 2N−1 berechnet
 * wird (Standardkonstruktion, siehe z.B. Project Nayukis Referenzimplementierung). Das pro
 * Richtung (vorwärts/rückwärts) einmalig nötige Chirp-Spektrum wird pro Instanz gecacht, da
 * eine [Stft]-Instanz dieselbe Länge über tausende Frames hinweg wiederverwendet — ohne diesen
 * Cache würde jeder einzelne Frame die komplette Vorberechnung wiederholen.
 *
 * Korrektheit ist ausschließlich über [FftPlanTest] gegen eine direkte Referenz-DFT verifiziert
 * (kein Gerät zum Testen verfügbar) — für Zweierpotenzen zusätzlich gegen den bestehenden,
 * bereits produktiv genutzten [ComplexFft]-Pfad, und end-to-end über [StftTest] mit den realen
 * Voc_FT-Parametern (n_fft=6144).
 */
class FftPlan(private val n: Int) {

    private val isPowerOfTwo = n > 0 && (n and (n - 1)) == 0
    private val bluesteinForward: Bluestein? = if (isPowerOfTwo) null else Bluestein(n, forward = true)
    private val bluesteinInverse: Bluestein? = if (isPowerOfTwo) null else Bluestein(n, forward = false)

    fun forward(re: FloatArray, im: FloatArray) {
        require(re.size == n && im.size == n) { "Länge muss $n sein, war ${re.size}" }
        if (isPowerOfTwo) ComplexFft.forward(re, im) else bluesteinForward!!.transform(re, im)
    }

    fun inverse(re: FloatArray, im: FloatArray) {
        require(re.size == n && im.size == n) { "Länge muss $n sein, war ${re.size}" }
        if (isPowerOfTwo) ComplexFft.inverse(re, im) else bluesteinInverse!!.transform(re, im)
    }

    /**
     * Eine Richtung (vorwärts oder rückwärts) von Bluesteins Algorithmus für feste Länge [n].
     * Vorzeichenkonvention identisch zu [ComplexFft]: vorwärts nutzt exp(-i·2π·k·n/N), rückwärts
     * exp(+i·2π·k·n/N) mit abschließender Division durch [n].
     */
    private class Bluestein(private val n: Int, private val forward: Boolean) {
        private val m = nextPowerOfTwo(2 * n - 1)
        private val sign = if (forward) -1.0 else 1.0
        private val chirpRe = FloatArray(n)
        private val chirpIm = FloatArray(n)
        private val bSpecRe: FloatArray
        private val bSpecIm: FloatArray

        init {
            for (k in 0 until n) {
                // k*k passt für alle in der Praxis vorkommenden STFT-Fenstergrößen locker in
                // Double ohne Präzisionsverlust (weit unter der 2^53-Grenze).
                val angle = sign * Math.PI * (k.toDouble() * k.toDouble()) / n
                chirpRe[k] = Math.cos(angle).toFloat()
                chirpIm[k] = Math.sin(angle).toFloat()
            }

            // b = zirkulär gespiegeltes konjugiertes Chirp-Spektrum, Länge m: b[0]=conj(chirp[0]),
            // b[k]=b[m-k]=conj(chirp[k]) für k=1..n-1, dazwischen 0 (Standard-Bluestein-Aufbau).
            val bRe = FloatArray(m)
            val bIm = FloatArray(m)
            bRe[0] = chirpRe[0]
            bIm[0] = -chirpIm[0]
            for (k in 1 until n) {
                val re = chirpRe[k]
                val im = -chirpIm[k]
                bRe[k] = re
                bIm[k] = im
                bRe[m - k] = re
                bIm[m - k] = im
            }
            ComplexFft.forward(bRe, bIm)
            bSpecRe = bRe
            bSpecIm = bIm
        }

        fun transform(re: FloatArray, im: FloatArray) {
            val aRe = FloatArray(m)
            val aIm = FloatArray(m)
            for (k in 0 until n) {
                val xr = re[k]
                val xi = im[k]
                val cr = chirpRe[k]
                val ci = chirpIm[k]
                aRe[k] = xr * cr - xi * ci
                aIm[k] = xr * ci + xi * cr
            }

            ComplexFft.forward(aRe, aIm)
            for (i in 0 until m) {
                val ar = aRe[i]
                val ai = aIm[i]
                val br = bSpecRe[i]
                val bi = bSpecIm[i]
                aRe[i] = ar * br - ai * bi
                aIm[i] = ar * bi + ai * br
            }
            ComplexFft.inverse(aRe, aIm)

            for (k in 0 until n) {
                val cr = chirpRe[k]
                val ci = chirpIm[k]
                val xr = aRe[k]
                val xi = aIm[k]
                var outRe = xr * cr - xi * ci
                var outIm = xr * ci + xi * cr
                if (!forward) {
                    outRe /= n
                    outIm /= n
                }
                re[k] = outRe
                im[k] = outIm
            }
        }
    }

    private companion object {
        fun nextPowerOfTwo(value: Int): Int {
            var v = 1
            while (v < value) v = v shl 1
            return v
        }
    }
}
