package com.example.vocaltrainer.audio

/**
 * Klassische Mitte-Kanal-Auslöschung ("Karaoke-Trick") für interleaviertes Stereo-PCM,
 * aber auf den Gesangs-Frequenzbereich begrenzt (siehe [VocalBandFilter]), damit
 * überwiegend der Gesang und nicht das gesamte mittig abgemischte Signal betroffen
 * ist. Funktioniert nur bei echten Stereo-Aufnahmen mit mittig abgemischtem Gesang —
 * siehe Hilfe-Seite in der App für die Einschränkungen dieses Verfahrens. Hält
 * Filterzustand, muss also für aufeinanderfolgende Aufrufe an derselben Spur
 * wiederverwendet werden (nicht pro Chunk neu erzeugen).
 */
class VocalReducer(sampleRate: Int) {

    private val bandFilter = VocalBandFilter(sampleRate)

    /**
     * Wendet die Auslöschung auf [frames] Stereo-Frames ab [offsetFrames] in [src] an
     * und schreibt das Ergebnis ab Index 0 nach [dst]. [k]=0 lässt das Signal
     * unverändert, [k]=1 löscht den Gesangsanteil maximal aus.
     */
    fun applyCancellation(src: ShortArray, dst: ShortArray, offsetFrames: Int, frames: Int, k: Float) {
        for (i in 0 until frames) {
            val li = (offsetFrames + i) * 2
            val ri = li + 1
            val l = src[li].toInt()
            val r = src[ri].toInt()
            val mid = (l + r) / 2.0
            val vocalBand = bandFilter.process(mid)
            dst[i * 2] = clampToShort(l - k * vocalBand)
            dst[i * 2 + 1] = clampToShort(r - k * vocalBand)
        }
    }

    companion object {
        fun clampToShort(value: Double): Short =
            value.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
    }
}
