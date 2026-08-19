package com.example.vocaltrainer.audio

import kotlin.math.abs
import kotlin.math.tanh

/** Drei unabhängige Regler: Gesamtlautstärke, eigene aufgenommene Stimme, Original-Gesang. */
data class MixGains(val master: Float, val userVocal: Float, val originalVocal: Float) {
    companion object {
        val FULL = MixGains(master = 1f, userVocal = 1f, originalVocal = 1f)
    }
}

/**
 * Misch-Mathematik, geteilt zwischen Live-Remix-Wiedergabe und Export — beide rufen
 * exakt dieselbe Funktion auf, damit Vorschau und exportierte Datei garantiert
 * übereinstimmen. Der "Original-Gesang"-Fader nutzt dieselbe vorberechnete,
 * phasenfehlerfreie Mitte-Kanal-Auslöschung wie der Vorhör-Regler im Wiedergabe-Tab
 * (siehe [VocalBandFilter]), nur mit invertiertem Parameter (k = 1 - originalVocalFader).
 */
object AudioMixer {

    /**
     * [original]: interleaviertes Stereo-PCM des unveränderten Original-Tracks.
     * [vocalBandMid]: vorberechnetes, phasenfehlerfreies Gesangsband für [original]
     * (siehe [VocalBandFilter.computeVocalBandMid]).
     * [userVocal]: mono PCM der Aufnahme (kann kürzer als der Track sein — fehlende
     * Abschnitte werden als Stille behandelt, nie länger als der Original-Track).
     * Schreibt [frames] Stereo-Frames ab [offsetFrames] nach [out] (ab Index 0).
     */
    fun renderChunk(
        original: ShortArray,
        vocalBandMid: FloatArray,
        userVocal: ShortArray,
        offsetFrames: Int,
        frames: Int,
        gains: MixGains,
        out: ShortArray
    ) {
        val k = 1f - gains.originalVocal
        for (i in 0 until frames) {
            val frame = offsetFrames + i
            val oi = frame * 2
            val l = original[oi].toInt()
            val r = original[oi + 1].toInt()
            val band = vocalBandMid[frame]
            val vocalReducedL = l - k * band
            val vocalReducedR = r - k * band
            val userSample = if (frame < userVocal.size) userVocal[frame].toInt() else 0

            out[i * 2] = softLimit(gains.master * (vocalReducedL + gains.userVocal * userSample))
            out[i * 2 + 1] = softLimit(gains.master * (vocalReducedR + gains.userVocal * userSample))
        }
    }

    // Diagnose-Logs zeigten wiederholtes Clipping (outputPeak=32767/32768) schon bei
    // Standard-Reglerstellung: Original-Track plus aufgenommene Stimme werden roh addiert,
    // ohne Rücksicht auf gemeinsame Kopfraum-Reserven im 16-Bit-Bereich. Sobald der Mix an
    // der Decke hängt, verschlucken harte Clips genau die Lautstärke-Unterschiede, die die
    // Regler eigentlich hörbar machen sollen ("Regler wirkungslos"). Ein weicher Limiter
    // lässt Pegel unterhalb der Schwelle unverändert (voll transparent) und rundet nur den
    // Bereich oberhalb sanft in Richtung Vollausschlag ab, statt hart abzuschneiden.
    private const val LIMITER_THRESHOLD = 27852f // ~85% von Short.MAX_VALUE
    private const val CEILING = 32767f

    private fun softLimit(value: Float): Short {
        val sign = if (value < 0f) -1f else 1f
        val magnitude = abs(value)
        val limited = if (magnitude <= LIMITER_THRESHOLD) {
            magnitude
        } else {
            val excess = magnitude - LIMITER_THRESHOLD
            val headroom = CEILING - LIMITER_THRESHOLD
            LIMITER_THRESHOLD + headroom * tanh(excess / headroom)
        }
        return (sign * limited).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
    }
}
