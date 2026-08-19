package com.example.vocaltrainer.audio

/**
 * Klassische Mitte-Kanal-Auslöschung ("Karaoke-Trick") für interleaviertes Stereo-PCM,
 * begrenzt auf das per [VocalBandFilter] vorberechnete, phasenfehlerfreie
 * Gesangs-Frequenzband. Funktioniert nur bei echten Stereo-Aufnahmen mit mittig
 * abgemischtem Gesang — siehe Hilfe-Seite in der App für die Einschränkungen.
 */
object VocalReducer {

    /**
     * Wendet die Auslöschung auf [frames] Stereo-Frames ab [offsetFrames] in [src] an
     * und schreibt das Ergebnis ab Index 0 nach [dst]. [vocalBandMid] muss dieselbe
     * Framezahl wie [src] abdecken (siehe [VocalBandFilter.computeVocalBandMid]).
     * [k]=0 lässt das Signal unverändert, [k]=1 löscht den Gesangsanteil maximal aus.
     */
    fun applyCancellation(
        src: ShortArray,
        vocalBandMid: FloatArray,
        dst: ShortArray,
        offsetFrames: Int,
        frames: Int,
        k: Float
    ) {
        for (i in 0 until frames) {
            val frame = offsetFrames + i
            val li = frame * 2
            val ri = li + 1
            val l = src[li].toInt()
            val r = src[ri].toInt()
            val band = vocalBandMid[frame]
            dst[i * 2] = clampToShort(l - k * band)
            dst[i * 2 + 1] = clampToShort(r - k * band)
        }
    }

    fun clampToShort(value: Double): Short =
        value.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()

    fun clampToShort(value: Float): Short = clampToShort(value.toDouble())
}
