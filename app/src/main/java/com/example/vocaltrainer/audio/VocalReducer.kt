package com.example.vocaltrainer.audio

/**
 * Mischt Instrumental- und Original-Gesangs-Stem für den Vorhör-Regler auf dem
 * Wiedergabe-Screen: bei k=0 klingt es wie das Original (Instrumental + voller Gesang),
 * bei k=1 bleibt nur das Instrumental übrig. Nutzt die von [VocalSeparator] getrennten
 * echten Stems statt einer Frequenzband-Näherung — Instrumental + Gesang ergeben per
 * Konstruktion exakt wieder das Original.
 */
object VocalReducer {

    /**
     * Wendet die Reduzierung auf [frames] Stereo-Frames ab [offsetFrames] an und schreibt
     * das Ergebnis ab Index 0 nach [dst]. [k]=0 lässt das Signal unverändert (Original),
     * [k]=1 lässt nur das Instrumental übrig.
     */
    fun applyReduction(
        instrumental: ShortArray,
        vocal: ShortArray,
        dst: ShortArray,
        offsetFrames: Int,
        frames: Int,
        k: Float
    ) {
        val vocalGain = 1f - k
        for (i in 0 until frames) {
            val frame = offsetFrames + i
            val li = frame * 2
            val ri = li + 1
            dst[i * 2] = clampToShort(instrumental[li] + vocalGain * vocal[li])
            dst[i * 2 + 1] = clampToShort(instrumental[ri] + vocalGain * vocal[ri])
        }
    }

    fun clampToShort(value: Double): Short =
        value.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()

    fun clampToShort(value: Float): Short = clampToShort(value.toDouble())
}
