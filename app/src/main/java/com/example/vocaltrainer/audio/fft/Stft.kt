package com.example.vocaltrainer.audio.fft

/**
 * Kurzzeit-Fourier-Transformation (STFT/ISTFT) nach der exakten Konvention, die das
 * verwendete MDX-Net-Modell erwartet (siehe Modell-Dokumentation, huggingface.co/gyoom-sa/
 * UVR-MDX-LiteRT): periodisches Hann-Fenster, zentriert mit Reflect-Padding, unnormiert,
 * Bins [0, dimF) behalten (Nyquist-Bin wird verworfen). Deckt sich mit der Standard-
 * `center=True, pad_mode='reflect'`-Konvention (librosa/torch.stft).
 *
 * ISTFT arbeitet bewusst NICHT auf dem kompletten Track als einem Array (bei mehreren
 * Minuten Audio wäre das vollständige Spektrogramm hunderte MB groß), sondern akkumuliert
 * Chunk für Chunk per Overlap-Add in gemeinsame, trackgroße Puffer. Die Division durch die
 * Fenster-Quadratsumme (Standard-Normalisierung für Overlap-Add-Rekonstruktion) erfolgt erst
 * ganz am Ende, nachdem alle Chunks eingetragen wurden — siehe [VocalSeparator].
 */
class Stft(private val nFft: Int, private val hop: Int, private val dimF: Int) {

    private val window: FloatArray = periodicHann(nFft)
    private val padLen = nFft / 2
    // Manche Modelle (z.B. Voc_FT) verwenden ein nFft, das keine Zweierpotenz ist — FftPlan
    // wählt automatisch zwischen dem schnellen Zweierpotenz-Pfad und Bluesteins Algorithmus.
    private val fft = FftPlan(nFft)

    /** Reflect-Padding (numpy-`mode='reflect'`-Konvention) an beiden Enden um [nFft]/2. */
    fun padReflect(samples: FloatArray): FloatArray {
        val n = samples.size
        val out = FloatArray(n + 2 * padLen)
        System.arraycopy(samples, 0, out, padLen, n)
        for (i in 0 until padLen) {
            out[padLen - 1 - i] = samples[(i + 1).coerceAtMost(n - 1).coerceAtLeast(0)]
            out[padLen + n + i] = samples[(n - 2 - i).coerceIn(0, n - 1)]
        }
        return out
    }

    /** Anzahl STFT-Frames für ein Original-Signal der Länge [originalLength]. */
    fun frameCount(originalLength: Int): Int = originalLength / hop + 1

    /**
     * Berechnet Real-/Imaginärteil für [frameCount] aufeinanderfolgende Frames ab [startFrame].
     * [padded] muss über [padReflect] erzeugt worden sein. Layout der Rückgabe: [frame][bin],
     * bin läuft schneller (Größe frameCount*dimF).
     */
    fun forward(padded: FloatArray, startFrame: Int, frameCount: Int): Pair<FloatArray, FloatArray> {
        val outRe = FloatArray(frameCount * dimF)
        val outIm = FloatArray(frameCount * dimF)
        val frameRe = FloatArray(nFft)
        val frameIm = FloatArray(nFft)
        for (f in 0 until frameCount) {
            val offset = (startFrame + f) * hop
            for (i in 0 until nFft) {
                frameRe[i] = padded[offset + i] * window[i]
                frameIm[i] = 0f
            }
            fft.forward(frameRe, frameIm)
            val base = f * dimF
            System.arraycopy(frameRe, 0, outRe, base, dimF)
            System.arraycopy(frameIm, 0, outIm, base, dimF)
        }
        return outRe to outIm
    }

    /**
     * Rekonstruiert [frameCount] Frames ab [startFrame] aus [specRe]/[specIm] (Layout wie bei
     * [forward]) per Overlap-Add in die geteilten Puffer [outAccum]/[windowSumAccum] (beide
     * müssen mindestens so lang wie das gepaddete Originalsignal sein). Bins jenseits von
     * [dimF] (inkl. Nyquist) werden über hermitesche Symmetrie ergänzt bzw. als 0 behandelt.
     */
    fun accumulateInverse(
        specRe: FloatArray,
        specIm: FloatArray,
        startFrame: Int,
        frameCount: Int,
        outAccum: FloatArray,
        windowSumAccum: FloatArray
    ) {
        val frameRe = FloatArray(nFft)
        val frameIm = FloatArray(nFft)
        for (f in 0 until frameCount) {
            val base = f * dimF
            frameRe[0] = specRe[base]
            frameIm[0] = specIm[base]
            for (bin in 1 until dimF) {
                val re = specRe[base + bin]
                val im = specIm[base + bin]
                frameRe[bin] = re
                frameIm[bin] = im
                val mirror = nFft - bin
                frameRe[mirror] = re
                frameIm[mirror] = -im
            }
            frameRe[nFft / 2] = 0f
            frameIm[nFft / 2] = 0f
            fft.inverse(frameRe, frameIm)
            val offset = (startFrame + f) * hop
            for (i in 0 until nFft) {
                outAccum[offset + i] += frameRe[i] * window[i]
                windowSumAccum[offset + i] += window[i] * window[i]
            }
        }
    }

    /** Länge des gepaddeten Signals für ein Original der Länge [originalLength]. */
    fun paddedLength(originalLength: Int): Int = originalLength + 2 * padLen

    /** Start-Offset des unveränderten Originalsignals innerhalb des gepaddeten Puffers. */
    fun padOffset(): Int = padLen

    private fun periodicHann(n: Int): FloatArray {
        val w = FloatArray(n)
        for (i in 0 until n) {
            w[i] = (0.5 - 0.5 * Math.cos(2.0 * Math.PI * i / n)).toFloat()
        }
        return w
    }
}
