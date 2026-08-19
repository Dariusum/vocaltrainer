package com.example.vocaltrainer.audio

import android.content.Context

/** Persistente, app-weite Einstellung für die Bandgrenzen der Gesangsreduzierung. */
class VocalBandSettings(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var lowHz: Float
        get() = prefs.getFloat(KEY_LOW_HZ, VocalBandFilter.DEFAULT_LOW_HZ)
        set(value) = prefs.edit().putFloat(KEY_LOW_HZ, value).apply()

    var highHz: Float
        get() = prefs.getFloat(KEY_HIGH_HZ, VocalBandFilter.DEFAULT_HIGH_HZ)
        set(value) = prefs.edit().putFloat(KEY_HIGH_HZ, value).apply()

    companion object {
        private const val PREFS_NAME = "vocal_band_settings"
        // _v2: Der Standard-Frequenzbereich wurde deutlich verbreitert (siehe
        // VocalBandFilter.DEFAULT_LOW_HZ/HIGH_HZ), weil ein schmales Band kaum hörbare
        // Auslöschung brachte. Neue Key-Namen sorgen dafür, dass zuvor gespeicherte,
        // zu enge Werte aus alten Versionen verworfen werden statt den neuen breiten
        // Standard zu überschreiben.
        private const val KEY_LOW_HZ = "low_hz_v2"
        private const val KEY_HIGH_HZ = "high_hz_v2"
    }
}
