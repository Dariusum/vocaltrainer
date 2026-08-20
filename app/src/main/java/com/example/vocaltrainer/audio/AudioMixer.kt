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
 * übereinstimmen. Nutzt die von [VocalSeparator] getrennten echten Stems (Instrumental +
 * Original-Gesang) statt einer Frequenzband-Näherung: der "Original-Gesang"-Fader steuert
 * direkt die Lautstärke des tatsächlich abgetrennten Gesangs-Stems.
 */
object AudioMixer {

    /**
     * [instrumental]: interleaviertes Stereo-PCM des Instrumental-Stems (Original minus
     * abgetrennter Original-Gesang).
     * [originalVocalStem]: interleaviertes Stereo-PCM des abgetrennten Original-Gesangs.
     * [userVocal]: mono PCM der Aufnahme (kann kürzer als der Track sein — fehlende
     * Abschnitte werden als Stille behandelt, nie länger als der Original-Track).
     * Schreibt [frames] Stereo-Frames ab [offsetFrames] nach [out] (ab Index 0).
     */
    fun renderChunk(
        instrumental: ShortArray,
        originalVocalStem: ShortArray,
        userVocal: ShortArray,
        offsetFrames: Int,
        frames: Int,
        gains: MixGains,
        out: ShortArray
    ) {
        for (i in 0 until frames) {
            val frame = offsetFrames + i
            val li = frame * 2
            val ri = li + 1
            val instL = instrumental[li].toInt()
            val instR = instrumental[ri].toInt()
            val vocL = originalVocalStem[li].toInt()
            val vocR = originalVocalStem[ri].toInt()
            val userSample = if (frame < userVocal.size) userVocal[frame].toInt() else 0

            out[i * 2] = softLimit(gains.master * (instL + gains.originalVocal * vocL + gains.userVocal * userSample))
            out[i * 2 + 1] = softLimit(gains.master * (instR + gains.originalVocal * vocR + gains.userVocal * userSample))
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
