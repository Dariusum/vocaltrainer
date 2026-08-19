package com.example.vocaltrainer.audio

/**
 * Begrenzt ein Signal auf den typischen Frequenzbereich von Lead-Gesang (Grundton +
 * untere Obertöne, ca. 200–4000 Hz), bevor es zur Mitte-Kanal-Auslöschung verwendet
 * wird. Ohne diese Begrenzung würde die Auslöschung das komplette mittig abgemischte
 * Signal treffen (auch Bass, Kick, tiefe Gitarren) statt überwiegend den Gesang —
 * genau das war das gemeldete Problem. Bleibt weiterhin eine Annäherung (siehe
 * Hilfe-Seite), aber deutlich gezielter als eine Vollband-Auslöschung.
 */
class VocalBandFilter(sampleRate: Int) {

    private val highPass = Biquad.highPass(sampleRate, VOCAL_LOW_HZ)
    private val lowPass = Biquad.lowPass(sampleRate, VOCAL_HIGH_HZ)

    fun process(sample: Double): Double = lowPass.process(highPass.process(sample))

    companion object {
        private const val VOCAL_LOW_HZ = 200.0
        private const val VOCAL_HIGH_HZ = 4000.0
    }
}
