package com.example.vocaltrainer.audio

/**
 * Berechnet einmalig eine auf den Gesangs-Frequenzbereich begrenzte Version des
 * Mittensignals (L+R)/2 für eine komplette Spur.
 *
 * Wichtig: Ein normaler (kausaler) Echtzeit-Filter verschiebt die Phase des
 * Signals. Zieht man ein phasenverschobenes Signal vom Original ab, entsteht
 * KEINE saubere Auslöschung mehr, sondern Kammfilter-Klang (der Gesang klingt
 * dumpf/phasig, wird aber kaum leiser) — das war ein früherer Fehler in dieser App.
 * Die Lösung: den Filter einmal vorwärts und einmal rückwärts durch das komplette
 * Signal laufen lassen ("filtfilt"-Technik). Das hebt die Phasenverschiebung exakt
 * auf, sodass das Ergebnis phasengleich mit dem Original bleibt und die
 * anschließende Auslöschung wieder korrekt funktioniert. Da der komplette Track
 * ohnehin schon vollständig im Speicher liegt, ist eine Vorberechnung (statt eines
 * Live-Filters während der Wiedergabe) hier möglich und nötig.
 *
 * Arbeitet mit einem einzigen, in-place wiederverwendeten FloatArray (statt
 * mehrerer separater Double-Arrays), um Speicherbandbreite und Allokationen für
 * lange Tracks (mehrere Millionen Frames) gering zu halten.
 */
object VocalBandFilter {

    const val DEFAULT_LOW_HZ = 200f
    const val DEFAULT_HIGH_HZ = 4000f

    fun computeVocalBandMid(pcm: PcmAudio, lowHz: Float, highHz: Float): FloatArray {
        val frameCount = pcm.frameCount
        val buffer = FloatArray(frameCount)

        if (pcm.channelCount == 2) {
            for (i in 0 until frameCount) {
                val l = pcm.samples[i * 2]
                val r = pcm.samples[i * 2 + 1]
                buffer[i] = (l + r) / 2f
            }
        } else {
            for (i in 0 until frameCount) {
                buffer[i] = pcm.samples[i].toFloat()
            }
        }

        // Vorwärtsdurchlauf, in-place.
        var highPass = Biquad.highPass(pcm.sampleRate, lowHz.toDouble())
        var lowPass = Biquad.lowPass(pcm.sampleRate, highHz.toDouble())
        for (i in 0 until frameCount) {
            buffer[i] = lowPass.process(highPass.process(buffer[i].toDouble())).toFloat()
        }

        // Rückwärtsdurchlauf mit frischem Filterzustand hebt die Phasenverschiebung auf, in-place.
        highPass = Biquad.highPass(pcm.sampleRate, lowHz.toDouble())
        lowPass = Biquad.lowPass(pcm.sampleRate, highHz.toDouble())
        for (i in frameCount - 1 downTo 0) {
            buffer[i] = lowPass.process(highPass.process(buffer[i].toDouble())).toFloat()
        }

        return buffer
    }
}
