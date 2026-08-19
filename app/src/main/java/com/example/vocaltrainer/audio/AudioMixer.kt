package com.example.vocaltrainer.audio

/** Drei unabhängige Regler: Gesamtlautstärke, eigene aufgenommene Stimme, Original-Gesang. */
data class MixGains(val master: Float, val userVocal: Float, val originalVocal: Float) {
    companion object {
        val FULL = MixGains(master = 1f, userVocal = 1f, originalVocal = 1f)
    }
}

/**
 * Misch-Mathematik, geteilt zwischen Live-Remix-Wiedergabe und Export — beide rufen
 * exakt dieselbe Funktion auf, damit Vorschau und exportierte Datei garantiert
 * übereinstimmen. Der "Original-Gesang"-Fader nutzt dieselbe frequenzband-begrenzte
 * Mitte-Kanal-Auslöschung wie der Vorhör-Regler im Wiedergabe-Tab (siehe
 * [VocalBandFilter]), nur mit invertiertem Parameter (k = 1 - originalVocalFader).
 * Hält Filterzustand, daher pro abzuspielender/zu exportierender Spur einmal
 * erzeugen und für alle Chunks dieser Spur wiederverwenden.
 */
class AudioMixer(sampleRate: Int) {

    private val bandFilter = VocalBandFilter(sampleRate)

    /**
     * [original]: interleaviertes Stereo-PCM des unveränderten Original-Tracks.
     * [userVocal]: mono PCM der Aufnahme (kann kürzer als der Track sein — fehlende
     * Abschnitte werden als Stille behandelt, nie länger als der Original-Track).
     * Schreibt [frames] Stereo-Frames ab [offsetFrames] nach [out] (ab Index 0).
     */
    fun renderChunk(
        original: ShortArray,
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
            val mid = (l + r) / 2.0
            val vocalBand = bandFilter.process(mid)
            val vocalReducedL = l - k * vocalBand
            val vocalReducedR = r - k * vocalBand
            val userSample = if (frame < userVocal.size) userVocal[frame].toInt() else 0

            out[i * 2] = VocalReducer.clampToShort(gains.master * (vocalReducedL + gains.userVocal * userSample))
            out[i * 2 + 1] = VocalReducer.clampToShort(gains.master * (vocalReducedR + gains.userVocal * userSample))
        }
    }
}
