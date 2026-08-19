package com.example.vocaltrainer.audio

/**
 * Klassische Mitte-Kanal-Auslöschung ("Karaoke-Trick") für interleaviertes Stereo-PCM.
 * Funktioniert nur bei echten Stereo-Aufnahmen mit mittig abgemischtem Gesang — siehe
 * Hilfe-Seite in der App für die Einschränkungen dieses Verfahrens. Nicht für Mono
 * geeignet (Aufrufer müssen das vorher prüfen, diese Funktion nimmt Stereo an).
 */
object VocalReducer {

    /**
     * Wendet die Auslöschung auf [frames] Stereo-Frames ab [offsetFrames] in [src] an
     * und schreibt das Ergebnis ab Index 0 nach [dst]. [k]=0 lässt das Signal
     * unverändert, [k]=1 löscht mittige Inhalte maximal aus.
     */
    fun applyCancellation(src: ShortArray, dst: ShortArray, offsetFrames: Int, frames: Int, k: Float) {
        for (i in 0 until frames) {
            val li = (offsetFrames + i) * 2
            val ri = li + 1
            val l = src[li].toInt()
            val r = src[ri].toInt()
            val mid = (l + r) / 2
            dst[i * 2] = clampToShort(l - k * mid)
            dst[i * 2 + 1] = clampToShort(r - k * mid)
        }
    }

    fun clampToShort(value: Float): Short =
        value.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
}
